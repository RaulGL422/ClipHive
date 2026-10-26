# /check-structure — Verify Project Structure Compliance

Audits the current branch diff (or a specific file) to verify it follows ClipHive's module boundaries and multiplatform conventions defined in `CLAUDE.md`. Reports violations and proposes concrete fixes.

## Usage
- `/check-structure` — audits all Java files changed since `development`
- `/check-structure Encoder.java` — audits a specific file
- `/check-structure --full` — audits all production Java files across the three modules

---

## Step 1: Determine scope

**If a file is passed:** read that file only.

**If `--full`:** list all files under `protocol/src/main/java`, `server/src/main/java`, `worker/src/main/java` and read every one.

**Default (no argument):**
```bash
git diff development...HEAD --name-only -- "*.java"
```
Read each changed Java file (skip test files).

If nothing changed since `development`, inform the user and stop.

---

## Step 2: Run all structural checks

For each file in scope, evaluate the following rules. Mark each as ✅ or ❌.

---

### 🏗️ MODULE BOUNDARIES

- [ ] **`protocol` has no business logic** — only message/DTO records and state enums. No service classes, no I/O, no conditionals beyond basic validation.
- [ ] **`server` doesn't transcribe or render** — no direct ffmpeg/whisper.cpp invocation; that belongs to `worker`. The server only prepares inputs (e.g. cutting the source chunk with `-c copy`) and hands the task off.
- [ ] **`worker` doesn't touch social accounts, OAuth tokens, superadmin state, logs, or statistics** — if a worker class references any of these, it's in the wrong module.
- [ ] **No module depends on a module that isn't `protocol`** — `server` and `worker` both depend on `protocol`, never on each other.

---

### 🔌 EXTENSION POINTS

- [ ] New publisher implementations (TikTok/Instagram/YouTube/intermediary) implement `Publisher` — callers never branch on platform type
- [ ] New LLM backends (`claude -p` vs Anthropic API) implement `LlmClient`
- [ ] New video encoders (AMF/NVENC/VAAPI/x264) implement `Encoder`, with the full command built inside the implementation (VAAPI needs `hwupload` — a different command shape, not just a different codec name)

---

### 🌐 MULTIPLATFORM

- [ ] **Paths:** `java.nio.file.Path` everywhere; no string concatenation with `/` or `\`
- [ ] **External processes:** `ProcessBuilder` with an **argument list** — never a shell string passed to `cmd`/`bash`
- [ ] **Binary paths** (`yt-dlp`, `ffmpeg`, `ffprobe`, `whisper-cli`, `claude`) are configurable with PATH autodetection, not hardcoded, and account for `.exe` on Windows
- [ ] **ffmpeg subtitle/ass filter:** invoked with the task's working directory and relative paths, not absolute Windows paths (drive-letter escaping is fragile)
- [ ] **Data directories** follow the OS convention and come from configuration, not a hardcoded literal

---

### 📦 PROTOCOL CONTRACT

- [ ] Every task-scoped WebSocket message includes `taskId` and `attempt`
- [ ] New message types are added to `protocol` once, not duplicated ad hoc in `server`/`worker`
- [ ] State enum values match exactly the state machines in `docs/DESIGN.md` section 5 — no silent extra states

---

### 🔧 JAVA STYLE

- [ ] Immutable `record` types used for DTOs/messages where there's no behavior to attach
- [ ] Variable and method names in **English**
- [ ] No unused imports or dead commented-out code

---

## Step 3: Generate the report

```
## Structure Audit — [date]
Branch: [current branch] vs development
Files analyzed: [N files]

---

### 🔴 Violations (must fix before merge)
[rule] — [file:line] — [description]
[proposed fix with code snippet]

### 🟡 Warnings (strong recommendation)
[rule] — [file:line] — [description]

### ✅ Compliant checks
[list of passing rules, grouped by category]

---

**Overall:** [PASS / FAIL]
**Summary:** [N violations, N warnings across N files]
```

If there are no violations, state clearly:
> "All structural rules pass. The diff is consistent with CLAUDE.md conventions."

---

## Step 4: Apply fixes (if authorized)

If the user says "fix it" or "apply the fixes":
- Apply only the clearly mechanical fixes (hardcoded separator → `Path`, shell string → `ProcessBuilder` arg list, missing `taskId`/`attempt` field)
- For architectural violations (business logic leaking into `protocol`, a module depending on the wrong module), explain why and ask how to proceed
- Never silently resolve a decision `docs/DESIGN.md` §20.2 marks as pending (e.g. Spring Boot/OpenJFX version) — flag it instead
