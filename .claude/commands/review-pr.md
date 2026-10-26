# /review-pr — Pull Request Review

Reviews the current PR (or the one specified as argument) exhaustively.

## Usage
- `/review-pr` — reviews the PR for the current branch
- `/review-pr 42` — reviews PR number 42

## Step 1: Identify the PR

If a number was passed as argument, use that PR.

If not, run:
```
git branch --show-current
gh pr list --head [current-branch] --json number,title,url,baseRefName
```

If no PR exists for the current branch, notify the user and stop.

## Step 2: Get PR information

Run:
```
gh pr view [number] --json title,body,baseRefName,headRefName,additions,deletions,changedFiles,commits
gh pr diff [number]
```

## Step 3: Review the code (exhaustive analysis)

Analyze the full diff evaluating **all** of the following categories:

---

### 🔴 CRITICAL ERRORS (block merge)

- Unhandled exceptions that could crash the server or a worker task
- Broken compilation (`./mvnw compile`)
- Incompatible or destructive DB migrations (server module)
- Forgotten `TODO`, `FIXME`, `HACK`, or `System.out.println` in the diff
- Empty PR description

---

### 🏗️ MODULE BOUNDARIES (ClipHive-specific)

- [ ] **`protocol` has zero business logic** — only messages, DTOs, state enums. Any `if`/loop beyond trivial equality/validation here is a red flag.
- [ ] **`server` never transcribes or renders** — that's worker-only work. If a new server class touches ffmpeg/whisper.cpp directly, question it.
- [ ] **`worker` never touches social accounts, OAuth tokens, superadmin logs, or statistics**.
- [ ] **Extension points stay behind interfaces** — new publisher/LLM backend/encoder implementations extend `Publisher`/`LlmClient`/`Encoder`, callers don't branch on concrete type.
- [ ] **WebSocket messages carry `taskId` + `attempt`** on every task-scoped message (fencing token — see `docs/DESIGN.md` §6.3).

---

### 🌐 MULTIPLATFORM (Linux + Windows — see CLAUDE.md)

- [ ] Paths via `java.nio.file.Path`, never hand-written `/` or `\` separators
- [ ] External processes via `ProcessBuilder` with an **argument list**, never `cmd`/`bash`/string concatenation
- [ ] External binary paths (`yt-dlp`, `ffmpeg`, `ffprobe`, `whisper-cli`, `claude`) configurable, not hardcoded
- [ ] If touching ffmpeg's `subtitles`/`ass` filter: working directory + relative paths used (Windows drive-letter escaping is fragile — avoid needing it)
- [ ] Data directories respect the OS convention and are configurable, not hardcoded

---

### 🔐 SECURITY (see `docs/DESIGN.md` §13)

- [ ] **No secrets in code, config files, or commit history** — only `.env`/environment variables
- [ ] **File URLs are signed and expiring**, tied to `taskId + attempt` (worker files) or a single clip token (media route)
- [ ] **OAuth tokens and the TOTP secret are encrypted at rest** — never logged or returned in a response
- [ ] New endpoints/routes respect the three-listener separation (admin = localhost, media = localhost `/media/**` only, workers = Tailscale interface only) — nothing new should widen what's reachable from where
- [ ] Worker tokens only grant access to the WebSocket and that worker's own task files

---

### 📚 DOCUMENTATION

- [ ] `docs/DESIGN.md` updated if the PR introduces or changes a design decision
- [ ] New public classes/methods have Javadoc where the *why* isn't obvious from the name
- [ ] Module `README.md` updated if the module's responsibility or boundaries shifted

---

### ⚡ PERFORMANCE

- [ ] No unnecessary full re-encodes where `-c copy` or a stream copy would do (see `docs/DESIGN.md` §9.2, §10 pipeline step 10)
- [ ] No N+1 queries once JPA/Flyway exist in `server`
- [ ] Expensive work off the request thread where relevant

---

### ✅ TESTS

- New code has unit or integration tests
- Error cases covered in addition to the happy path
- **Note:** it is not possible to verify test results from the diff alone. If new tests exist, indicate they must be run manually before merge.

---

## Step 4: Generate report

```
## PR Review #[number]: [title]

**Base:** [base-branch] ← [head-branch]
**Changes:** +[additions] / -[deletions] in [N] files

---

### 🔴 Critical Errors
[list or "None found"]

### 🏗️ Module Boundary Issues
[list or "None found"]

### 🌐 Multiplatform Issues
[list or "None found"]

### 🔐 Security Issues
[list or "None found"]

### 📚 Missing Documentation
[list or "Complete"]

### ⚡ Performance Issues
[list or "None found"]

### ✅ Test Coverage
[observations]

---

### Verdict
[APPROVED / APPROVED WITH SUGGESTIONS / CHANGES REQUIRED]

### Suggested next steps:
1. [concrete action]
2. [concrete action]
```

If there are critical errors or security issues, explain the risk and propose a concrete fix with code.

---

## Step 5: Update documentation if needed

After generating the report, check whether the PR makes `CLAUDE.md` or a module `README.md` outdated (new module responsibility, new convention worth documenting, new interface). If nothing needs updating, state: `"Documentation is up to date, no changes needed."` Otherwise apply the changes directly and summarize them.
