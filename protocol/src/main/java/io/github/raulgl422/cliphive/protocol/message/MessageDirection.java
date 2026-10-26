package io.github.raulgl422.cliphive.protocol.message;

/**
 * Which side of the connection a {@link ProtocolMessage} travels on - the
 * "Dirección" column of the docs/DESIGN.md section 7 table, made checkable
 * in code instead of living only in a comment.
 *
 * <p>{@link #of(ProtocolMessage)} switches over the sealed {@link
 * ProtocolMessage} hierarchy, so it's exhaustive: if a 14th message is ever
 * added to {@code permits} without a case here, this file fails to compile.
 * That's deliberate - it's the same safety the design doc's table gives you
 * by eye, kept true automatically as the contract grows.
 */
public enum MessageDirection {
    /** Sent by the server, received by the worker. */
    SERVER_TO_WORKER,
    /** Sent by the worker, received by the server. */
    WORKER_TO_SERVER;

    /** The direction the design doc's section 7 table assigns to {@code message}. */
    public static MessageDirection of(ProtocolMessage message) {
        return switch (message) {
            case Hello ignored -> WORKER_TO_SERVER;
            case Welcome ignored -> SERVER_TO_WORKER;
            case TaskAssign ignored -> SERVER_TO_WORKER;
            case TaskAccept ignored -> WORKER_TO_SERVER;
            case TaskReject ignored -> WORKER_TO_SERVER;
            case Progress ignored -> WORKER_TO_SERVER;
            case TaskDone ignored -> WORKER_TO_SERVER;
            case TaskFailed ignored -> WORKER_TO_SERVER;
            case TaskRelease ignored -> WORKER_TO_SERVER;
            case TaskCancel ignored -> SERVER_TO_WORKER;
            case Goodbye ignored -> WORKER_TO_SERVER;
            // The design doc lists PING/PONG together as "S <-> W" heartbeat, but
            // structurally the server always initiates PING and the worker always
            // replies PONG (section 6.5) - never the reverse.
            case Ping ignored -> SERVER_TO_WORKER;
            case Pong ignored -> WORKER_TO_SERVER;
        };
    }
}
