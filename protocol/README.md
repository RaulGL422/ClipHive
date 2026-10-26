# protocol

Shared WebSocket messages, DTOs and state enums used by both `server` and `worker`. This module **contains no business logic** — only the contract both sides agree on, defined once so server and worker never drift apart.

See [`docs/DESIGN.md`](../docs/DESIGN.md) section 7 (WebSocket protocol) and section 5 (states) for the design. Every class also has Javadoc pointing back at the specific subsection it implements — read that first before changing anything here.

## Layout

```
io.github.raulgl422.cliphive.protocol
├── ProtocolVersion       major protocol version + compatibility check (HELLO / R14)
├── state/                enums for the four state machines + TaskType
│   ├── VideoJobState
│   ├── ClipState
│   ├── TaskState
│   ├── ScheduledPostState
│   └── TaskType          TRANSCRIBE | RENDER_CLIP
├── message/              the 13 WebSocket messages from the DESIGN.md §7 table
│   ├── ProtocolMessage   sealed interface every message implements
│   ├── Hello, Welcome, TaskAssign, TaskAccept, TaskReject,
│   │   Progress, TaskDone, TaskFailed, TaskRelease, TaskCancel,
│   │   Goodbye, Ping, Pong
│   ├── WorkerCapabilities  value object carried in Hello
│   └── MessageDirection    SERVER_TO_WORKER | WORKER_TO_SERVER per message type
└── codec/
    ├── ProtocolCodec           encode/decode a ProtocolMessage to/from JSON
    └── ProtocolCodecException  unchecked wrapper for encode/decode failures
```

## Key decisions worth knowing before touching this module

- **Messages are a `sealed` hierarchy of `record`s.** Every message type in the DESIGN.md §7 table is listed in `ProtocolMessage`'s `permits` clause. Adding a 14th message means: add the record, add it to `permits`, add it to `@JsonSubTypes` in `ProtocolMessage`. The compiler will then flag every `switch` over `ProtocolMessage` that doesn't handle it yet.
- **Wire format is JSON with a `type` discriminator field** (e.g. `{"type":"TASK_ASSIGN", ...}`), via Jackson. DESIGN.md doesn't fix this explicitly (see the note added to §7) — it was the simplest choice that keeps this module framework-free (no Spring Boot dependency), usable as-is from the plain-Java worker.
- **`taskId` + `attempt` is the fencing token** from DESIGN.md §6.3. Every task-scoped message carries both; `TaskCancel` is the one exception (server-initiated, cancels whatever attempt is currently running).
- **`TaskAssign`'s `parameters`/`downloadUrls`/`uploadUrls` are untyped maps**, not per-task-type records — `TRANSCRIBE` and `RENDER_CLIP` need different keys (DESIGN.md §9), and typing them here would make `protocol` change every time a task payload gains a field. Define and validate the exact keys in each task type's handler in `worker`, not here.
- **`ProtocolCodec` is the only place that knows about Jackson, and it's Jackson 3** (`tools.jackson.*`, groupId `tools.jackson.core`), not Jackson 2 — `server` runs Spring Boot 4, which defaults to Jackson 3. The one exception is `jackson-annotations` (`@JsonTypeInfo`/`@JsonSubTypes` on `ProtocolMessage`), which deliberately kept the old `com.fasterxml.jackson.annotation` package/groupId in Jackson 3 - don't "fix" that import. Jackson 3's `ObjectMapper` is immutable once built, so `ProtocolCodec` constructs it via `JsonMapper.builder()...build()` rather than `new ObjectMapper()`; its exceptions are unchecked (`JacksonException`, not the old checked `JsonProcessingException`). The version itself isn't pinned here - it comes from the `spring-boot-dependencies` BOM imported in the root `pom.xml`, so this module always matches whatever Jackson version `server` runs, without depending on Spring Boot itself. Both `server` and `worker` should call `ProtocolCodec.encode`/`decode` rather than building their own mapper for protocol messages, so there's exactly one place to fix if the wire format ever changes.
- **`MessageDirection` makes the §7 table's "Dirección" column checkable at runtime.** Decode with `ProtocolCodec.decode(json, MessageDirection.WORKER_TO_SERVER)` (server side) or `SERVER_TO_WORKER` (worker side) instead of the plain `decode(json)` on each side's receive path, so a message sent on the wrong channel is rejected immediately instead of reaching business logic that assumed the other direction.
