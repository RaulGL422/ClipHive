package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.Worker;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.service.worker.ConnectedWorker;
import io.github.raulgl422.cliphive.server.service.worker.WorkerSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskDispatchSchedulerTest {

    private TaskRepository taskRepository;
    private SignedUrlService signedUrlService;
    private TransactionTemplate txTemplate;
    private WorkerSessionRegistry registry;
    private TaskDispatchScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        signedUrlService = mock(SignedUrlService.class);
        txTemplate = mock(TransactionTemplate.class);
        registry = new WorkerSessionRegistry();

        doAnswer(inv -> {
            Consumer<TransactionStatus> consumer =
                    inv.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        ClipHiveProperties props = new ClipHiveProperties();
        props.setWorkerBind("127.0.0.1");
        props.setWorkerPort(8082);
        props.setUrlSigningKey("test-key");

        scheduler = new TaskDispatchScheduler(registry, taskRepository, signedUrlService, txTemplate, props, null, null);
    }

    @Test
    void dispatch_no_workers_does_nothing() {
        // registry is empty
        scheduler.dispatch();
        verify(taskRepository, never()).countByAssignedWorkerIdAndStateIn(any(), any());
    }

    @Test
    void dispatch_skips_worker_without_capabilities() throws Exception {
        Worker worker = new Worker("w", "h");
        setField(worker, "id", UUID.randomUUID());
        // lastCapabilities is null by default

        ConnectedWorker cw = new ConnectedWorker(UUID.randomUUID(), worker, mockWsSession("ws-1"));
        registry.register(cw);

        scheduler.dispatch();

        verify(taskRepository, never()).countByAssignedWorkerIdAndStateIn(any(), any());
    }

    @Test
    void dispatch_skips_worker_at_full_capacity() throws Exception {
        Worker worker = workerWithCapabilities(2);
        ConnectedWorker cw = new ConnectedWorker(UUID.randomUUID(), worker, mockWsSession("ws-2"));
        registry.register(cw);

        when(taskRepository.countByAssignedWorkerIdAndStateIn(
                eq(worker.getId()), any())).thenReturn(2); // at capacity

        scheduler.dispatch();

        verify(taskRepository, never()).lockNextPending(anyInt());
    }

    @Test
    void dispatch_assigns_up_to_available_slots() throws Exception {
        Worker worker = workerWithCapabilities(2);
        ConnectedWorker cw = new ConnectedWorker(UUID.randomUUID(), worker, mockWsSession("ws-3"));
        registry.register(cw);

        when(taskRepository.countByAssignedWorkerIdAndStateIn(
                eq(worker.getId()), any())).thenReturn(0); // 2 free slots

        VideoJob job = new VideoJob("http://x", "es", null, false);
        setField(job, "id", UUID.randomUUID());
        Task t1 = Task.forTranscribe(job, Map.of("chunkIndex", 0));
        setField(t1, "id", UUID.randomUUID());

        when(taskRepository.lockNextPending(2)).thenReturn(List.of(t1));
        when(signedUrlService.generateWorkerUrl(any(), any(), anyInt(), any())).thenReturn("http://signed");

        scheduler.dispatch();

        verify(taskRepository).lockNextPending(2);
        assertThat(t1.getState()).isEqualTo(TaskState.ASSIGNED);
        assertThat(t1.getAssignedWorker()).isEqualTo(worker);
    }

    @Test
    void workerBaseUrl_uses_configured_bind_and_port() {
        assertThat(scheduler.workerBaseUrl()).isEqualTo("http://127.0.0.1:8082");
    }

    @Test
    void workerBaseUrl_defaults_to_localhost_when_bind_is_blank() throws Exception {
        ClipHiveProperties props = new ClipHiveProperties();
        props.setWorkerBind("");
        props.setWorkerPort(8082);
        props.setUrlSigningKey("k");
        TaskDispatchScheduler s = new TaskDispatchScheduler(registry, taskRepository,
                signedUrlService, txTemplate, props, null, null);
        assertThat(s.workerBaseUrl()).isEqualTo("http://127.0.0.1:8082");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Worker workerWithCapabilities(int maxConcurrency) throws Exception {
        Worker worker = new Worker("w", "h");
        setField(worker, "id", UUID.randomUUID());
        WorkerCapabilities caps = new WorkerCapabilities(
                false, List.of("libx264"), "cpu", List.of("medium"), maxConcurrency);
        worker.setLastCapabilities(caps);
        return worker;
    }

    private static WebSocketSession mockWsSession(String id) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(id);
        when(ws.isOpen()).thenReturn(true);
        return ws;
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
