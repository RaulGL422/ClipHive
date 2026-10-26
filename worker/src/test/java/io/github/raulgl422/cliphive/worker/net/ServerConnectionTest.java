package io.github.raulgl422.cliphive.worker.net;

import io.github.raulgl422.cliphive.protocol.codec.ProtocolCodec;
import io.github.raulgl422.cliphive.protocol.message.Hello;
import io.github.raulgl422.cliphive.protocol.message.MessageDirection;
import io.github.raulgl422.cliphive.protocol.message.Pong;
import io.github.raulgl422.cliphive.protocol.message.Progress;
import io.github.raulgl422.cliphive.protocol.message.ProtocolMessage;
import io.github.raulgl422.cliphive.protocol.message.TaskDone;
import io.github.raulgl422.cliphive.protocol.message.TaskFailed;
import io.github.raulgl422.cliphive.protocol.message.TaskRelease;
import io.github.raulgl422.cliphive.protocol.message.Welcome;
import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.runtime.WorkerController;
import io.github.raulgl422.cliphive.worker.task.TaskOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Worker side of the connection (docs/DESIGN.md §8.2): a rejected HELLO is
 * reported instead of retried blindly, a silent server is dropped by the
 * worker's own watchdog, and task results that couldn't be sent go out after
 * the next WELCOME.
 */
class ServerConnectionTest {

    private static final long WAIT_S = 15;

    private final List<ServerConnection> connections = new ArrayList<>();
    private final List<Thread> loops = new ArrayList<>();
    private FakeServer server;

    @AfterEach
    void tearDown() throws Exception {
        connections.forEach(ServerConnection::shutdown);
        for (Thread t : loops) {
            t.interrupt(); // a rejected worker sleeps 60 s before retrying
            t.join(TimeUnit.SECONDS.toMillis(5));
        }
        if (server != null) server.close();
    }

    // ── serverUri ───────────────────────────────────────────────────────────

    @Test
    void server_uri_accepts_ws_and_wss_with_a_host() {
        assertThat(ServerConnection.serverUri("ws://100.64.0.1:8082/ws/worker").getHost()).isEqualTo("100.64.0.1");
        assertThat(ServerConnection.serverUri("  wss://cliphive.example/ws/worker ").getScheme()).isEqualTo("wss");
        assertThat(ServerConnection.serverUri("WS://host/ws/worker").getHost()).isEqualTo("host");
    }

    @Test
    void server_uri_rejects_anything_else_saying_what_it_should_look_like() {
        for (String bad : new String[] {"http://host:8082/ws/worker", "", "   ", null,
                                        "not a url", "ws:///ws/worker", "host:8082"}) {
            assertThatThrownBy(() -> ServerConnection.serverUri(bad))
                    .as("'%s'", bad)
                    .isInstanceOf(ServerConnection.RejectedException.class)
                    .hasMessageContaining("ws://");
        }
    }

    // ── explainRejection ────────────────────────────────────────────────────

    @Test
    void explain_rejection_tells_the_user_what_to_fix() {
        assertThat(ServerConnection.explainRejection("Unknown token")).contains("no reconoce el token");
        assertThat(ServerConnection.explainRejection("Token revoked")).contains("revocado");
        assertThat(ServerConnection.explainRejection("Protocol version mismatch: update your worker"))
                .contains("no es compatible");
        assertThat(ServerConnection.explainRejection("Too many workers"))
                .isEqualTo("El servidor rechazó la conexión: Too many workers");
        assertThat(ServerConnection.explainRejection("")).isEqualTo("El servidor rechazó la conexión.");
        assertThat(ServerConnection.explainRejection(null)).isEqualTo("El servidor rechazó la conexión.");
    }

    // ── resultTaskId ────────────────────────────────────────────────────────

    @Test
    void only_task_results_are_worth_keeping_for_later() {
        UUID id = UUID.randomUUID();
        assertThat(ServerConnection.resultTaskId(new TaskDone(id, 1, Map.of()))).isEqualTo(id);
        assertThat(ServerConnection.resultTaskId(new TaskFailed(id, 1, "boom"))).isEqualTo(id);
        assertThat(ServerConnection.resultTaskId(new TaskRelease(id, 1))).isEqualTo(id);
        assertThat(ServerConnection.resultTaskId(new Progress(id, 1, 50, "encoding"))).isNull();
        assertThat(ServerConnection.resultTaskId(new Pong())).isNull();
    }

    // ── Against a real (tiny) WebSocket server ──────────────────────────────

    @Test
    void a_rejected_hello_is_reported_with_what_to_fix() throws Exception {
        server = new FakeServer(peer -> {
            peer.readMessage(); // HELLO
            peer.sendClose(1008, "Unknown token");
            peer.drain();
        });
        AtomicInteger connected = new AtomicInteger();
        BlockingQueue<String> rejections = new LinkedBlockingQueue<>();
        ServerConnection conn = newConnection();

        start(conn, () -> {}, connected::incrementAndGet, rejections::add);

        String reason = rejections.poll(WAIT_S, TimeUnit.SECONDS);
        assertThat(reason).isNotNull().contains("no reconoce el token");
        assertThat(server.received.poll(1, TimeUnit.SECONDS)).isInstanceOf(Hello.class);
        assertThat(connected).hasValue(0);
    }

    @Test
    void a_server_that_goes_silent_after_welcome_is_dropped_and_reconnected() throws Exception {
        server = new FakeServer(peer -> {
            peer.readMessage(); // HELLO
            // Expects a PING every second and allows no misses; then never says anything again
            peer.send(new Welcome(UUID.randomUUID(), 1, 1, 0));
            peer.drain();
        });
        AtomicInteger connecting = new AtomicInteger();
        CountDownLatch connectedTwice = new CountDownLatch(2);
        ServerConnection conn = newConnection();

        start(conn, connecting::incrementAndGet, connectedTwice::countDown, r -> {});

        // Watchdog tick (5 s) + reconnect backoff (2 s)
        assertThat(connectedTwice.await(WAIT_S, TimeUnit.SECONDS)).isTrue();
        assertThat(server.received.poll(1, TimeUnit.SECONDS)).isInstanceOf(Hello.class);
        assertThat(server.received.poll(1, TimeUnit.SECONDS)).isInstanceOf(Hello.class);
        assertThat(server.accepted).hasValueGreaterThanOrEqualTo(2);
        assertThat(connecting).hasValueGreaterThanOrEqualTo(2);
    }

    @Test
    void a_task_result_sent_while_offline_goes_out_right_after_the_next_welcome() throws Exception {
        server = new FakeServer(peer -> {
            peer.readMessage(); // HELLO
            peer.send(new Welcome(UUID.randomUUID(), 20, 10, 3));
            peer.drain();
        });
        ServerConnection conn = newConnection();
        UUID taskId = UUID.randomUUID();
        TaskDone done = new TaskDone(taskId, 1, Map.of("clip", "abc"));

        conn.sendMessage(done); // no connection yet: kept in the outbox
        CountDownLatch connected = new CountDownLatch(1);
        start(conn, () -> {}, connected::countDown, r -> {});

        assertThat(connected.await(WAIT_S, TimeUnit.SECONDS)).isTrue();
        assertThat(server.received.poll(WAIT_S, TimeUnit.SECONDS)).isInstanceOf(Hello.class);
        assertThat(server.received.poll(WAIT_S, TimeUnit.SECONDS)).isEqualTo(done);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ServerConnection newConnection() {
        WorkerProperties props = new WorkerProperties();
        props.setServerUrl("ws://127.0.0.1:" + server.port() + "/ws/worker");
        props.setWorkerToken("t");
        WorkerCapabilities caps = new WorkerCapabilities(false, List.of("libx264"), "cpu", List.of(), 1);
        // No TASK_ASSIGN is sent in these tests, so the handlers are never used
        TaskOrchestrator orchestrator = new TaskOrchestrator(null, null, new WorkerController());
        ServerConnection conn = new ServerConnection(props, caps, orchestrator);
        connections.add(conn);
        return conn;
    }

    private void start(ServerConnection conn, Runnable onConnecting, Runnable onConnected,
                       Consumer<String> onRejected) {
        loops.add(Thread.ofVirtual().name("reconnect-loop")
                .start(() -> conn.runWithReconnect(onConnecting, onConnected, onRejected)));
    }

    /**
     * Just enough of RFC 6455 to stand in for the server: the HTTP upgrade,
     * masked client text frames in, unmasked text and close frames out.
     */
    private static final class FakeServer implements AutoCloseable {

        interface Handler { void handle(Peer peer) throws Exception; }

        private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        final BlockingQueue<ProtocolMessage> received = new LinkedBlockingQueue<>();
        final AtomicInteger accepted = new AtomicInteger();
        private final ServerSocket socket;
        private final List<Socket> clients = new CopyOnWriteArrayList<>();

        FakeServer(Handler handler) throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofPlatform().daemon().name("fake-ws-accept").start(() -> {
                while (!socket.isClosed()) {
                    Socket client;
                    try {
                        client = socket.accept();
                    } catch (IOException e) {
                        return;
                    }
                    clients.add(client);
                    accepted.incrementAndGet();
                    Thread.ofPlatform().daemon().name("fake-ws-peer").start(() -> {
                        try (client) {
                            Peer peer = new Peer(client, received);
                            peer.handshake();
                            handler.handle(peer);
                        } catch (Exception ignored) {
                            // the worker hung up (abort, shutdown): nothing to do
                        }
                    });
                }
            });
        }

        int port() { return socket.getLocalPort(); }

        @Override
        public void close() throws IOException {
            socket.close();
            for (Socket c : clients) c.close();
        }
    }

    private static final class Peer {

        private final InputStream in;
        private final OutputStream out;
        private final BlockingQueue<ProtocolMessage> received;

        Peer(Socket socket, BlockingQueue<ProtocolMessage> received) throws IOException {
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.received = received;
        }

        void handshake() throws Exception {
            String key = null;
            for (String line = readLine(); !line.isEmpty(); line = readLine()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("sec-websocket-key")) {
                    key = line.substring(colon + 1).trim();
                }
            }
            if (key == null) throw new IOException("no Sec-WebSocket-Key");
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + FakeServer.GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                       + "Upgrade: websocket\r\n"
                       + "Connection: Upgrade\r\n"
                       + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        /** Next text message from the worker (decoded and recorded), or null once it closes. */
        ProtocolMessage readMessage() throws IOException {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            while (true) {
                int b0 = readByte();
                int b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = (readByte() << 8) | readByte();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                }
                byte[] mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : null;
                byte[] payload = in.readNBytes((int) len);
                if (payload.length < len) throw new EOFException();
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                }
                switch (opcode) {
                    case 0x8 -> { return null; }                  // close
                    case 0x9, 0xA -> { continue; }                // ping / pong
                    default -> text.write(payload);               // text or continuation
                }
                if (fin) break;
            }
            ProtocolMessage msg = ProtocolCodec.decode(text.toString(StandardCharsets.UTF_8),
                                                       MessageDirection.WORKER_TO_SERVER);
            received.add(msg);
            return msg;
        }

        /** Keeps reading (and recording) until the worker closes or the socket drops. */
        void drain() throws IOException {
            while (readMessage() != null) { /* keep going */ }
        }

        void send(ProtocolMessage message) throws IOException {
            writeFrame(0x1, ProtocolCodec.encode(message).getBytes(StandardCharsets.UTF_8));
        }

        void sendClose(int code, String reason) throws IOException {
            byte[] r = reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + r.length];
            payload[0] = (byte) (code >> 8);
            payload[1] = (byte) code;
            System.arraycopy(r, 0, payload, 2, r.length);
            writeFrame(0x8, payload);
        }

        private synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
            out.write(0x80 | opcode);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                out.write(126);
                out.write(payload.length >> 8);
                out.write(payload.length);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) ((long) payload.length >> (8 * i)));
            }
            out.write(payload);
            out.flush();
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException();
            return b;
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = readByte()) != '\n') {
                if (b != '\r') sb.append((char) b);
            }
            return sb.toString();
        }
    }
}
