package io.github.raulgl422.cliphive.protocol.codec;

import io.github.raulgl422.cliphive.protocol.message.Goodbye;
import io.github.raulgl422.cliphive.protocol.message.Hello;
import io.github.raulgl422.cliphive.protocol.message.MessageDirection;
import io.github.raulgl422.cliphive.protocol.message.Ping;
import io.github.raulgl422.cliphive.protocol.message.Pong;
import io.github.raulgl422.cliphive.protocol.message.Progress;
import io.github.raulgl422.cliphive.protocol.message.ProtocolMessage;
import io.github.raulgl422.cliphive.protocol.message.TaskAccept;
import io.github.raulgl422.cliphive.protocol.message.TaskAssign;
import io.github.raulgl422.cliphive.protocol.message.TaskCancel;
import io.github.raulgl422.cliphive.protocol.message.TaskDone;
import io.github.raulgl422.cliphive.protocol.message.TaskFailed;
import io.github.raulgl422.cliphive.protocol.message.TaskReject;
import io.github.raulgl422.cliphive.protocol.message.TaskRelease;
import io.github.raulgl422.cliphive.protocol.message.Welcome;
import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips every {@link ProtocolMessage} type through {@link ProtocolCodec}.
 * This is the one piece of behavior in {@code protocol} that can actually
 * break silently (a field renamed on only one side of a message would still
 * compile but desync server and worker at runtime), so every message type
 * gets a case here deliberately instead of relying on one generic sample.
 */
class ProtocolCodecTest {

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    static Stream<ProtocolMessage> messages() {
        return Stream.of(
                new Hello(
                        "worker-token",
                        1,
                        "1.0.0",
                        "linux",
                        new WorkerCapabilities(true, List.of("h264_vaapi", "libx264"), "vulkan",
                                List.of("large-v3-turbo"), 2)
                ),
                new Welcome(UUID.randomUUID(), 20, 10, 3),
                new TaskAssign(
                        TASK_ID, 1, TaskType.RENDER_CLIP,
                        Map.of("fpsMode", "auto"),
                        Map.of("sourceClip", "https://example.invalid/download"),
                        Map.of("clip", "https://example.invalid/upload")
                ),
                new TaskAccept(TASK_ID, 1),
                new TaskReject(TASK_ID, 1, "no longer has a free GPU slot"),
                new Progress(TASK_ID, 1, 42, "encoding"),
                new TaskDone(TASK_ID, 1, Map.of("clip", "sha256:abc123")),
                new TaskFailed(TASK_ID, 1, "ffmpeg exited with code 1"),
                new TaskRelease(TASK_ID, 2),
                new TaskCancel(TASK_ID),
                new Goodbye(),
                new Ping(),
                new Pong()
        );
    }

    @ParameterizedTest
    @MethodSource("messages")
    void roundTripsThroughJson(ProtocolMessage original) {
        String json = ProtocolCodec.encode(original);
        ProtocolMessage decoded = ProtocolCodec.decode(json);

        assertEquals(original, decoded);
        assertInstanceOf(original.getClass(), decoded);
    }

    @Test
    void encodesWithTheExpectedTypeDiscriminator() {
        String json = ProtocolCodec.encode(new Ping());
        assertTrue(json.contains("\"type\":\"PING\""), "expected a PING type discriminator, got: " + json);
    }

    @Test
    void decodeRejectsUnknownMessageType() {
        assertThrows(ProtocolCodecException.class,
                () -> ProtocolCodec.decode("{\"type\":\"NOT_A_REAL_MESSAGE\"}"));
    }

    @Test
    void decodeRejectsMalformedJson() {
        assertThrows(ProtocolCodecException.class, () -> ProtocolCodec.decode("not json"));
    }

    @Test
    void decodeWithDirectionAcceptsAMatchingDirection() {
        String json = ProtocolCodec.encode(new TaskAccept(TASK_ID, 1));
        ProtocolMessage decoded = ProtocolCodec.decode(json, MessageDirection.WORKER_TO_SERVER);
        assertEquals(new TaskAccept(TASK_ID, 1), decoded);
    }

    @Test
    void decodeWithDirectionRejectsAMismatchedDirection() {
        // TaskCancel is SERVER_TO_WORKER - decoding it as if it came from the
        // worker must fail instead of silently returning it.
        String json = ProtocolCodec.encode(new TaskCancel(TASK_ID));
        assertThrows(ProtocolCodecException.class,
                () -> ProtocolCodec.decode(json, MessageDirection.WORKER_TO_SERVER));
    }
}
