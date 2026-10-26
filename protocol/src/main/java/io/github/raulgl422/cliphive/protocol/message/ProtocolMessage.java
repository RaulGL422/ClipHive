package io.github.raulgl422.cliphive.protocol.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Common supertype of every message exchanged over the server&lt;-&gt;worker
 * WebSocket connection - docs/DESIGN.md section 7. The WebSocket only
 * coordinates; actual files (audio, video, clips, transcripts) move over
 * HTTP with signed, expiring URLs tied to {@code taskId + attempt}.
 *
 * <p><b>Wire format:</b> each message is one JSON object with a {@code type}
 * discriminator field matching the message names in the docs/DESIGN.md
 * section 7 table (e.g. {@code "type": "TASK_ASSIGN"}). This isn't specified
 * verbatim in the design doc, which only fixes the message types and
 * payloads - JSON-with-a-type-field was chosen here as the simplest encoding
 * that doesn't tie {@code protocol} to any particular framework (plain
 * Jackson, usable from both the Spring Boot server and the plain-Java
 * worker). See {@link io.github.raulgl422.cliphive.protocol.codec.ProtocolCodec}.
 *
 * <p>Using {@code sealed} here is deliberate: every message in the
 * docs/DESIGN.md table is listed in {@code permits} below, so a
 * {@code switch} over a {@code ProtocolMessage} that's missing a case fails
 * to compile instead of failing at runtime when a new message type is added
 * later.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Hello.class, name = "HELLO"),
        @JsonSubTypes.Type(value = Welcome.class, name = "WELCOME"),
        @JsonSubTypes.Type(value = TaskAssign.class, name = "TASK_ASSIGN"),
        @JsonSubTypes.Type(value = TaskAccept.class, name = "TASK_ACCEPT"),
        @JsonSubTypes.Type(value = TaskReject.class, name = "TASK_REJECT"),
        @JsonSubTypes.Type(value = Progress.class, name = "PROGRESS"),
        @JsonSubTypes.Type(value = TaskDone.class, name = "TASK_DONE"),
        @JsonSubTypes.Type(value = TaskFailed.class, name = "TASK_FAILED"),
        @JsonSubTypes.Type(value = TaskRelease.class, name = "TASK_RELEASE"),
        @JsonSubTypes.Type(value = TaskCancel.class, name = "TASK_CANCEL"),
        @JsonSubTypes.Type(value = Goodbye.class, name = "GOODBYE"),
        @JsonSubTypes.Type(value = Ping.class, name = "PING"),
        @JsonSubTypes.Type(value = Pong.class, name = "PONG"),
})
public sealed interface ProtocolMessage
        permits Hello, Welcome, TaskAssign, TaskAccept, TaskReject, Progress,
        TaskDone, TaskFailed, TaskRelease, TaskCancel, Goodbye, Ping, Pong {
}
