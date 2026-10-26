# Development guide

How to build, test and run ClipHive from source, and the conventions the code follows. Before working on anything non-trivial, read [ARCHITECTURE.md](ARCHITECTURE.md) and the relevant section of [DESIGN.md](DESIGN.md) — the design document is the source of truth, and design changes are written there **before** they're implemented. Contribution rules (branches, commits, PRs) are in [CONTRIBUTING.md](../CONTRIBUTING.md).

---

## Prerequisites

- **JDK 25** — e.g. Eclipse Temurin from [adoptium.net](https://adoptium.net) (`winget install EclipseAdoptium.Temurin.25.JDK` on Windows).
- **Nothing else for the build**: the repo ships the Maven Wrapper (`./mvnw`, `mvnw.cmd`), which downloads the right Maven by itself.
- **ffmpeg** on the `PATH` if you want the render, audio-chunking and cutting tests to run instead of being skipped (CI installs it).
- To actually process videos locally: `yt-dlp` (+ Deno for YouTube), `ffmpeg`/`ffprobe`, `whisper-cli` and Claude Code (`claude`) — per-OS details in [server/README.md](../server/README.md) and [worker/README.md](../worker/README.md), or reuse the tools of an installed worker (see `run-worker.ps1` below).

No Docker is needed for the tests: the server tests start an embedded PostgreSQL (`io.zonky.test:embedded-postgres`).

## Repository layout

```
ClipHive/
├── pom.xml              parent POM: modules, Java 25, Spring Boot 4.1.2 BOM (groupId io.github.raulgl422.cliphive)
├── protocol/            WebSocket messages, DTOs and state enums (no business logic)
├── server/              Spring Boot application + superadmin
│   ├── src/main/java/io/github/raulgl422/cliphive/server/
│   │   ├── config/  security/  web/  service/  publish/  llm/  model/  repository/  properties/
│   ├── src/main/resources/  application*.properties, db/migration (Flyway), templates (Thymeleaf), static, legal
│   └── src/embedded/        embedded-PostgreSQL profile for local tries (only built with -Pembedded)
├── worker/              worker core + JavaFX UI
│   ├── src/main/java/io/github/raulgl422/cliphive/worker/
│   │   ├── config/  hardware/  model/  net/  render/  runtime/  task/  test/  tool/  ui/  logging/
│   └── packaging/licenses/  third-party notices bundled into the installer
├── docs/                design document and guides (index: docs/README.md)
├── .github/             CI workflows and Dependabot
├── Dockerfile, docker-compose.yml   server image
├── run-server.ps1, run-worker.ps1   Windows helpers for local runs
└── .env.example         every server environment variable, documented
```

## Build and test

```bash
./mvnw compile                 # everything (mvnw.cmd on Windows)
./mvnw test                    # all tests

./mvnw -q -B -pl server test             # server only
./mvnw -q -B -pl protocol,worker test    # protocol + worker
```

`server` and `worker` depend on `protocol` through the local Maven repository. **After changing `protocol`**, install it first so the other modules see the change:

```bash
./mvnw -q -B -pl protocol install -DskipTests
```

With `-q` the console says little; count results from `*/target/surefire-reports/TEST-*.xml`.

Packaging:

```bash
./mvnw -pl server -am package -DskipTests   # server/target/server-<version>.jar (production jar)
./mvnw -pl worker -am package -DskipTests   # worker/target/worker-<version>.jar (shaded, JavaFX included)
```

What the tests cover (see [DESIGN §16.3](DESIGN.md#163-pruebas-automáticas-r16)): unit tests for clip planning, id-to-time translation, subtitle retiming, fps decisions and the state machines; `ClipHiveServerApplicationTests` boots the full context against embedded PostgreSQL, applying every Flyway migration and letting Hibernate validate every entity; protocol round-trips; and a CPU render test with a short sample when ffmpeg is available.

## Running locally

### Server with the `embedded` profile

For local tries only: a throwaway embedded PostgreSQL (data is lost when the JVM exits), a seeded admin `admin` / `admin123` and test keys. It lives in `server/src/embedded/` and is only compiled in with the **`-Pembedded` Maven profile** — the production jar and the Docker image don't contain it, so it can't be switched on by accident in production: a production jar started with `--spring.profiles.active=embedded` stops with "The 'embedded' profile is not in this build…". The startup configuration check is skipped in this profile, and it binds the worker listener to `127.0.0.1` so a local worker can connect.

```bash
./mvnw install -DskipTests      # once, so the server finds the protocol module
./mvnw spring-boot:run -pl server -Pembedded -Dspring-boot.run.profiles=embedded
```

Or as a jar, e.g. on another port:

```bash
./mvnw -q -B -pl server package -DskipTests -Pembedded
java -jar server/target/server-0.1.0-SNAPSHOT.jar --spring.profiles.active=embedded --server.port=18080
```

Then open `http://localhost:8080/login` (or your port).

**Sample data (`demo` profile).** Add `demo` to see a superadmin that isn't empty: `-Dspring-boot.run.profiles=embedded,demo` (or `--spring.profiles.active=embedded,demo` for the jar). It loads made-up jobs in several states, published clips with statistics, scheduled publications, three connected accounts, two workers and log entries: the data behind the README screenshots. It's a repeatable Flyway migration, [`server/src/embedded/resources/db/demo/R__demo_data.sql`](../server/src/embedded/resources/db/demo/R__demo_data.sql), with dates relative to the start, so it ships only where `embedded` does. Nothing acts on it: the accounts' tokens are placeholders, and the profile turns off the planner, publishing, statistics, alerts and cleanup schedules, so no platform is called and no email is sent. Started without `embedded` (with `dev`, say), the server stops at once, so the made-up data can't reach a real database. Clip videos can't be played, because there are no files. `DemoDataTest` loads it in CI, so a schema change that breaks it fails the build.

The `dev` profile (`-Dspring-boot.run.profiles=dev`) uses a real PostgreSQL, with debug logging and a non-`Secure` session cookie so plain-HTTP localhost logins work. Unlike `embedded`, it **does** go through the startup configuration check, so it needs every variable that check requires: `CLIPHIVE_DB_URL`, `CLIPHIVE_DB_USER`, an absolute `CLIPHIVE_DATA_DIR`, `CLIPHIVE_URL_SIGNING_KEY` (32+ characters) and `CLIPHIVE_ENCRYPTION_KEY` (Base64 of 32 bytes), plus `CLIPHIVE_DB_PASSWORD` for your database ([DEPLOY.md §6](DEPLOY.md#6-configure-env)).

**`.env` is not read by `spring-boot:run`** (nor by `java -jar`): Spring Boot only sees real environment variables. Export them in your shell first, for example in Git Bash or Linux `set -a; . ./.env; set +a`, or use `run-server.ps1 -Mode normal` on Windows, which loads `.env` for you.

On Windows, **`run-server.ps1`** does all of this: it finds a JDK 25 (in `~/.jdks`, `C:\Program Files\Eclipse Adoptium\jdk-25*`, a few other usual folders, or `JAVA_HOME`, which it refuses if it isn't Java 25), installs the modules into the local Maven repository if needed and starts the server.

```powershell
.\run-server.ps1                # embedded profile (default); ignores .env
.\run-server.ps1 -Mode demo     # embedded + the demo sample data
.\run-server.ps1 -Mode normal   # real PostgreSQL; loads <repo>/.env (variables already set win)
                                # and stops if CLIPHIVE_DB_URL, CLIPHIVE_DB_USER, CLIPHIVE_DATA_DIR
                                # or either key is missing
```

### Worker from source

```bash
java -jar worker/target/worker-*.jar              # window + tray
java -jar worker/target/worker-*.jar --headless   # no UI
```

It uses the same `worker.yml` as an installed worker ([INSTALL-WORKER.md](INSTALL-WORKER.md#5-workeryml-reference)). To connect it to a local embedded server, register a worker in the local superadmin and use `ws://localhost:8082/ws/worker`. That works because the `embedded` profile binds the worker listener to `127.0.0.1`; with any other profile the listener only starts when `CLIPHIVE_WORKER_BIND` is set.

**On Linux**, the worker from source needs the tools on the `PATH` (or set in `worker.yml` / `CLIPHIVE_WORKER_*_PATH`), since there's no installer to bring them:

1. ffmpeg and ffprobe with a software H.264 encoder: `sudo apt install ffmpeg`, then check `ffmpeg -hide_banner -encoders | grep -E "libopenh264|libx264"` lists at least one (VAAPI or NVENC are used too if they pass the startup test).
2. whisper-cli from [whisper.cpp](https://github.com/ggml-org/whisper.cpp): `cmake -B build` (add `-DGGML_VULKAN=1` for a GPU build) and `cmake --build build --config Release`, then put `build/bin/whisper-cli` on the `PATH`. A binary named `whisper-cli` is taken as a CPU build unless `whisper-cli --version` mentions Vulkan or CUDA.
3. `java -jar worker/target/worker-*.jar`. The model is downloaded on the first start.

More per-OS and per-GPU detail in [worker/README.md](../worker/README.md).

On Windows, **`run-worker.ps1`** builds and runs the worker with a JDK 25 it finds by itself (same search as `run-server.ps1`, including `C:\Program Files\Eclipse Adoptium`); if ffmpeg, ffprobe and whisper-cli aren't configured or on the `PATH`, it uses the ones from an installed ClipHive Worker (`C:\Program Files\ClipHive Worker\app\bin`):

```powershell
.\run-worker.ps1              # desktop mode
.\run-worker.ps1 -Headless    # logs to the console; needs the token in worker.yml or CLIPHIVE_WORKER_TOKEN
.\run-worker.ps1 -SkipBuild   # reuse the last build
```

## Conventions

These come from [CLAUDE.md](../CLAUDE.md) and apply to every contribution, human or AI-assisted.

- **Java 25**, package root `io.github.raulgl422.cliphive` (`.protocol`, `.server`, `.worker`). Names in English; user-facing text (superadmin, worker window) is currently in Spanish.
- **Design document first.** If a change implies a design decision, update [DESIGN.md](DESIGN.md) first (commit `docs(design): …`), then the code. Don't re-open decisions marked as taken in §20.1, and don't silently settle one still pending in §20.2. Keep these English guides in step when behaviour changes.
- **`protocol` has no logic**: records, enums and the codec only. A new field goes there; the behaviour that uses it lives in `server` or `worker`. Adding a message means adding the record, listing it in `ProtocolMessage`'s `permits` and `@JsonSubTypes` (details in [protocol/README.md](../protocol/README.md)).
- **The worker never touches accounts, tokens, global logs or statistics.**
- **Multiplatform discipline — never assume the OS** ([DESIGN §2.2](DESIGN.md#22-multiplataforma-no-se-da-nada-por-hecho)):
  - paths always through `java.nio.file.Path`, never hand-written separators;
  - external binaries (`yt-dlp`, `ffmpeg`, `ffprobe`, `whisper-cli`, `claude`) configurable, with `PATH` autodetection (`.exe` on Windows);
  - external processes through `ProcessBuilder` with an **argument list**, never via `cmd` or `bash`;
  - ffmpeg runs in the task's working directory with **relative** paths (its `subtitles`/`ass` filters break on Windows drive letters);
  - data directories follow the OS convention and are configurable.
- **Extension points are interfaces** — add an implementation instead of branching on type in callers: `Publisher` (a platform or an intermediary), `LlmClient` (`claude -p` or the API), `Encoder` (AMF, NVENC, VAAPI, software).
- **No secrets in the repository, ever** (history included). Secrets and per-installation values go in `.env` (document new ones in `.env.example` and DESIGN §13.1); non-secret runtime settings go in the `setting` table and the *Ajustes* page.
- **Database**: one Flyway migration per change in `server/src/main/resources/db/migration/` (`V<n>__description.sql`); a committed migration is never edited. Hibernate only validates (`ddl-auto=validate`).
- **Spring Boot 4 notes**: test-slice annotations (`@WebMvcTest`, `@DataJpaTest`…) no longer exist — use `MockMvcBuilders.standaloneSetup(...)` for controllers and `@SpringBootTest @ActiveProfiles("embedded")` for integration tests. Jackson is 3 (`tools.jackson.*`), except `jackson-annotations`, which keeps `com.fasterxml.jackson.annotation`. `spring-boot-starter-flyway` is required for Flyway to run at all.
- **No auto-formatter**: the code uses hand-aligned constants; keep the surrounding style.

## CI

GitHub Actions, in [`.github/workflows/`](../.github/workflows/). All workflows have read-only permissions except the jobs that attach files to a release or push the server image, and third-party actions are pinned to a commit.

| Workflow | When | What |
|---|---|---|
| `ci.yml` — *Build and Test* | Push to `main`, `development`, `feature/**`; PRs to `main`/`development` | JDK 25 + ffmpeg on Ubuntu, `./mvnw -B compile test`, uploads the test reports (a failed upload doesn't fail the run) |
| `whisper-build.yml` — *Build whisper.cpp* | Manually, or called by the installer workflow | Builds `whisper-cli` for Windows x64 (Vulkan and CPU variants) from a pinned whisper.cpp tag |
| `installer.yml` — *Build Installers* | A `worker-v*` tag, or manually | Tests and builds the worker jar, downloads the pinned LGPL ffmpeg build (SHA-256 checked), makes a jlink runtime and runs `jpackage` to produce the MSI, with the `licenses/` folder; on a tag, attaches it to the GitHub release. Fails if a licence file is missing |
| `server.yml` — *Server Release* | A `server-v*` tag, or manually | Sets the tag's version in the poms, tests and builds the production jar (fails if the embedded profile is in it) and builds the `linux/amd64` + `linux/arm64` image; on a tag, attaches `cliphive-server-X.Y.Z.jar` and its SHA-256 to the release and pushes `ghcr.io/raulgl422/cliphive-server:X.Y.Z` and `:latest`. A manual run publishes nothing |

Linux installer targets are present but disabled for now: only the Windows MSI is released.

## Releasing the worker

1. Make sure `development` is green and merged into `main` as needed.
2. Add the changes under a new version in [CHANGELOG.md](../CHANGELOG.md).
3. Tag and push: `git tag worker-v0.1.7 && git push origin worker-v0.1.7` (the version must be numeric `x.y.z`: jpackage uses it as the MSI version).
4. `installer.yml` builds the MSI and attaches it to the `worker-v0.1.7` release. The MSI has a fixed upgrade code, so installing it over an older version upgrades in place.

If you change the WebSocket protocol incompatibly, bump the major protocol version (`ProtocolVersion`): older workers are then refused with a message telling the user to update.

## Releasing the server

1. As for the worker: `development` green and merged into `main`, and the changes under a new version in [CHANGELOG.md](../CHANGELOG.md), with upgrade notes for any new `.env` key.
2. Tag and push: `git tag server-v0.1.1 && git push origin server-v0.1.1`. Leave the `0.1.0-SNAPSHOT` in the poms alone: the workflow sets the tag's version only for the build.
3. `server.yml` attaches the jar to the `server-v0.1.1` release and pushes the image. Only the image's final stage is emulated for arm64: the jar is compiled natively on the runner.
4. The first time, check that the `cliphive-server` package on GitHub (profile → *Packages*) is **public**: `docker compose pull` needs no login only then. It's linked to the repository through the image's `org.opencontainers.image.source` label.

The server and the worker are versioned separately; the WebSocket protocol version decides whether they can talk to each other.

## Dependabot

[`.github/dependabot.yml`](../.github/dependabot.yml) opens weekly PRs **against `development`** for Maven dependencies, GitHub Actions and the Docker base images. It only offers a release once it has been out for seven days (`cooldown`). Minor and patch updates are grouped into one PR per ecosystem; majors (Spring Boot, for example) come alone so they can be read first. Major versions of Java (the Temurin images) and OpenJFX are never offered: both stay on 25, the LTS line the project uses ([DESIGN.md §20.2](DESIGN.md)), and moving off it is a design decision, not a Dependabot PR. When a Temurin image is bumped, keep both `FROM` lines in the `Dockerfile` on the same version. When a dependency that ships inside the worker installer changes (a library in the worker jar, or the pinned ffmpeg build or whisper.cpp tag in the workflows), update [`worker/packaging/licenses/THIRD-PARTY-NOTICES.txt`](../worker/packaging/licenses/THIRD-PARTY-NOTICES.txt) in the same PR: its versions and licences must match what the MSI distributes.

## Working with Claude Code

The project is developed with Claude Code: [CLAUDE.md](../CLAUDE.md) gives it the context, and `.claude/commands/` and `.claude/agents/` hold project commands and a `design-guardian` review agent that checks a change against DESIGN.md. The knowledge graph in `graphify-out/` is refreshed locally after each commit (`graphify update .`), not in CI. None of this is required to contribute.
