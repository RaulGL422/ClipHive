package io.github.raulgl422.cliphive.protocol.message;

import io.github.raulgl422.cliphive.protocol.state.TaskType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static io.github.raulgl422.cliphive.protocol.message.MessageDirection.SERVER_TO_WORKER;
import static io.github.raulgl422.cliphive.protocol.message.MessageDirection.WORKER_TO_SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks {@link MessageDirection#of(ProtocolMessage)} against the "Dirección"
 * column of the docs/DESIGN.md section 7 table, one case per message type.
 * The compiler already guarantees every message has <i>some</i> case (sealed
 * switch); this test guarantees it's the <i>correct</i> one.
 */
class MessageDirectionTest {

    private static final UUID TASK_ID = UUID.randomUUID();

    static Stream<Arguments> messagesWithExpectedDirection() {
        return Stream.of(
                Arguments.of(new Hello("t", 1, "1.0", "linux",
                        new WorkerCapabilities(true, List.of(), "cpu", List.of(), 1)), WORKER_TO_SERVER),
                Arguments.of(new Welcome(UUID.randomUUID(), 20, 10, 3), SERVER_TO_WORKER),
                Arguments.of(new TaskAssign(TASK_ID, 1, TaskType.TRANSCRIBE, Map.of(), Map.of(), Map.of()), SERVER_TO_WORKER),
                Arguments.of(new TaskAccept(TASK_ID, 1), WORKER_TO_SERVER),
                Arguments.of(new TaskReject(TASK_ID, 1, "busy"), WORKER_TO_SERVER),
                Arguments.of(new Progress(TASK_ID, 1, 50, "encoding"), WORKER_TO_SERVER),
                Arguments.of(new TaskDone(TASK_ID, 1, Map.of()), WORKER_TO_SERVER),
                Arguments.of(new TaskFailed(TASK_ID, 1, "boom"), WORKER_TO_SERVER),
                Arguments.of(new TaskRelease(TASK_ID, 1), WORKER_TO_SERVER),
                Arguments.of(new TaskCancel(TASK_ID), SERVER_TO_WORKER),
                Arguments.of(new Goodbye(), WORKER_TO_SERVER),
                Arguments.of(new Ping(), SERVER_TO_WORKER),
                Arguments.of(new Pong(), WORKER_TO_SERVER)
        );
    }

    @ParameterizedTest
    @MethodSource("messagesWithExpectedDirection")
    void matchesTheDesignDocTable(ProtocolMessage message, MessageDirection expected) {
        assertEquals(expected, MessageDirection.of(message));
    }
}
