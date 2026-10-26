# CLAUDE.md — ClipHive

## Project Overview

ClipHive is a self-hosted, cross-platform (Linux + Windows) pipeline: a video link goes in, vertical clips with karaoke subtitles come out, auto-published to TikTok, Instagram Reels and YouTube Shorts.

**[`docs/DESIGN.md`](docs/DESIGN.md) is the single source of truth.** It contains the full architecture, the complete video pipeline, all state machines, the WebSocket protocol, the processing spec (transcription/render), the Claude integration, the publishing strategy, the superadmin spec, the security model, the DB schema, the phased action plan, and every decision taken (and still pending). Any design change is written there **before** it's implemented. If something you're about to build contradicts or isn't covered by `docs/DESIGN.md`, stop and ask — don't improvise an architectural decision silently.

**Status:** all phases (Fase 1 → Fase 9, `docs/DESIGN.md` §19) are implemented and running for real: the server in Docker on a Raspberry Pi, the worker as a Windows MSI (`worker-v*` releases). Fase 0's platform paperwork (TikTok/Google audits) is still in progress. Current work is refining the app towards a public release — check §19 and §20.2 before starting non-trivial work.

---

## Module layout

| Module | Responsibility |
|--------|----------------|
| `protocol/` | Shared WebSocket messages, DTOs and state enums. **Zero business logic** — if you're writing an `if` here, it belongs somewhere else. |
| `server/` | Spring Boot: queue, download/validation, Claude analysis, scheduler, publishers (TikTok/Instagram/YouTube), superadmin web UI. Never transcribes or renders. |
| `worker/` | Transcription + rendering core, optional JavaFX UI, headless mode. Never touches social accounts, tokens, global logs, or statistics. |

---

## Local Setup

```bash
cp .env.example .env     # fill in values — see docs/DESIGN.md section 13.1
./mvnw compile            # Linux/macOS
mvnw.cmd compile          # Windows
./mvnw test
```

No separate Maven install needed — this repo ships the Maven Wrapper. Newcomer-oriented English guides (architecture, worker install, user guide, development) are indexed in [`docs/README.md`](docs/README.md); they summarise `docs/DESIGN.md` and must be kept in step with it.

---

## Conventions

- **Java 25**, package root `io.github.raulgl422.cliphive` (`io.github.raulgl422.cliphive.protocol`, `.server`, `.worker`); Maven groupId `io.github.raulgl422.cliphive`.
- **Build:** Maven, multi-module. **Spring Boot 4.1.2** is pinned (root `pom.xml`, imported as a BOM — `server`'s `pom.xml` is not a child of `spring-boot-starter-parent` since it already has `cliphive-parent`). Spring Boot 4 defaults to **Jackson 3** (`tools.jackson.*`, except `jackson-annotations` which keeps the old `com.fasterxml.jackson.annotation` package/groupId) — `protocol`'s `ProtocolCodec` uses it without depending on Spring Boot itself. **OpenJFX** is 25 (`docs/DESIGN.md` §20.2), built in code without FXML.
- **Spring Boot 4 removed all test-slice annotations** — `@WebMvcTest`, `@DataJpaTest`, `@JsonTest`, and every other `@...Test` slice from `spring-boot-test-autoconfigure` are gone. Don't reach for them: they will fail at compile time. Use `MockMvcBuilders.standaloneSetup(controller).setValidator(new LocalValidatorFactoryBean()).build()` for controller tests, and `@SpringBootTest @ActiveProfiles("embedded")` with `@TestConfiguration @Primary` mocks for integration tests that need a real application context.
- **Spring Boot 4 moved embedded-server packages** — `TomcatServletWebServerFactory` is now in `org.springframework.boot.tomcat.servlet` (was `org.springframework.boot.web.embedded.tomcat` in Boot 3). `WebServerFactoryCustomizer` stays at `org.springframework.boot.web.server`. The add-connector method is now `addAdditionalConnectors()` (was `addAdditionalTomcatConnectors()`). If a server customizer doesn't compile, check the `spring-boot-tomcat` module.
- **Multiplatform discipline — never assume the OS** (`docs/DESIGN.md` §2.2):
  - Paths always via `java.nio.file.Path`; never hand-written separators (`/` or `\`).
  - External binary paths (`yt-dlp`, `ffmpeg`, `ffprobe`, `whisper-cli`, `claude`) are **configurable**, with PATH autodetection (`.exe` suffix on Windows).
  - External processes via `ProcessBuilder` with an **argument list** — never through `cmd` or `bash`.
  - ffmpeg's `subtitles` filter breaks on Windows paths (drive letter needs escaping, `C\:/...`). Run ffmpeg with the task's working directory and pass **relative** paths instead.
  - Data directories follow the OS convention (`%LOCALAPPDATA%` on Windows; `~/.local/share` or `/var/lib` on Linux) and are configurable.
- **Extension points are interfaces** (`docs/DESIGN.md` §2.3) — add new implementations behind these, don't branch on type inside callers:
  - `Publisher` (TikTok, Instagram, YouTube, or a future intermediary like Upload-Post/Ayrshare)
  - `LlmClient` (`claude -p` or the Anthropic API)
  - `Encoder` (AMF, NVENC, VAAPI, software `libopenh264` — in the worker)
- **No secrets in the repo** — ever, including git history. Config goes in `.env` (see `.env.example`); non-secret runtime config goes in the superadmin `setting` table, not environment variables.

---

## Rules for Claude

- Git: the **maintainer** commits and pushes directly to `development` (the owner's choice for this solo project); **contributors** fork the repo and open pull requests against `development` (see [CONTRIBUTING.md](CONTRIBUTING.md)). Nobody commits or pushes to `main` — it's updated by merging `development` when releasing.
- `protocol` stays free of business logic. If a message/DTO needs a new field, add it there; the behavior that uses it lives in `server` or `worker`.
- The worker must never gain access to social accounts, OAuth tokens, global logs, or statistics — that's superadmin/server-only territory.
- Before implementing a phase from `docs/DESIGN.md` §19, re-read the relevant numbered section it references and check §20 (decisions) for anything already settled — don't re-litigate closed decisions, and don't silently resolve one still marked pending in §20.2.
- When a design decision changes during implementation, update `docs/DESIGN.md` first, then the code — it must stay the living source of truth.
- This project does not (yet) use an issue tracker integration — don't invent task-reference conventions (like `VC-N` in commit messages) that don't apply here.

### Project commands

`.claude/commands/` has no commands for adding JSON endpoints or error codes on purpose: the superadmin is server-rendered (Thymeleaf + HTMX) and the cross-process contract is the WebSocket `protocol` module, not a JSON REST API. If a JSON API appears, add commands for *that* contract.

---

## Workflow

Finish every change with `/ship` (global skill). The commands it needs for this repo:

- **Java**: Java 25. Point `JAVA_HOME` at a JDK 25 and put its `bin` first on the `PATH` (e.g. in Git Bash: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.1.8-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"`, adjusted to where your JDK 25 lives).
- **Build/test**: `./mvnw -q -B -pl server test` and `./mvnw -q -B -pl protocol,worker test` (after changing `protocol`, run `./mvnw -q -B -pl protocol install -DskipTests` first so `server`/`worker` see it). Count results from `*/target/surefire-reports/TEST-*.xml`, not from the quiet console output.
- **Order**: `docs/DESIGN.md` is updated and committed (`docs(design): …`) **before** the code commit.
- **Review agents**: before committing a non-trivial change, run the project agent `design-guardian` (design conformance) next to `/code-review`.
- **Post-commit**: `graphify update .` (or `python -m graphify update .`) to refresh the local knowledge graph — `graphify-out/` is gitignored, so there is nothing to commit — then push to `development`.
- **Local end-to-end check**: `java -jar server/target/server-0.1.0-SNAPSHOT.jar --spring.profiles.active=embedded --server.port=18080` (build it with `./mvnw -q -B -pl server package -DskipTests -Pembedded`: the embedded profile lives in `server/src/embedded/` and the production jar leaves it out); log in at `/login` with the seeded admin from the embedded profile.
- **Worker release**: push a `worker-v<version>` tag; GitHub Actions (`installer.yml`) builds the MSI.
- **Editing on Windows**: accented text through Bash heredocs gets mangled — write such edits with the Write tool or a script file.
- **Formatting**: no formatter is configured on purpose (the code uses hand-aligned constants that an auto-formatter would undo), so the global format-on-edit hook leaves this repo alone.

---

## graphify

This project has a knowledge graph at `graphify-out/` with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when `graphify-out/graph.json` exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than `GRAPH_REPORT.md` or raw grep output.
- If `graphify-out/wiki/index.md` exists, use it for broad navigation instead of raw source browsing.
- Read `graphify-out/GRAPH_REPORT.md` only for broad architecture review or when query/path/explain don't surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- If the bare `graphify` command isn't on PATH (common on Windows Python installs), use `python -m graphify` (or the interpreter saved at `graphify-out/.graphify_python`) instead of failing silently.
