# server

Spring Boot core that owns everything the worker doesn't: receiving links, validating and downloading them, orchestrating the task queue, analyzing content with Claude, planning and scheduling publications, serving files over signed URLs, and the superadmin web UI (Thymeleaf + HTMX, server-rendered, no separate frontend; the statistics charts are hand-drawn SVG, no chart library). The server never transcribes or renders — if no worker is connected, jobs simply wait.

Three separate listeners live in this one Spring Boot application:

| Listener | Bind | Exposed via | Purpose |
|---|---|---|---|
| Admin | `localhost:8080` | Cloudflare Tunnel + Access | Superadmin web UI |
| Media | `localhost:8081` | Cloudflare Tunnel (no Access) | Signed video URLs for Instagram |
| Worker | `<tailscale-ip>:8082` | Tailscale only | WebSocket coordination + file transfer |

See [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for an overview, [`docs/USER-GUIDE.md`](../docs/USER-GUIDE.md) for the superadmin screens, and [`docs/DESIGN.md`](../docs/DESIGN.md) sections 3 (architecture), 12 (superadmin) and 13 (security) for the full design. Code lives in `src/main/java/io/github/raulgl422/cliphive/server/` (`config`, `security`, `web`, `service`, `publish`, `llm`, `model`, `repository`, `properties`).

---

## Prerequisites

| | Linux (x86\_64 / ARM64) | Windows 10/11 x64 |
|---|---|---|
| **Java 25** | Download from [adoptium.net](https://adoptium.net) or `apt install temurin-25-jdk` (if the repo is set up) | `winget install EclipseAdoptium.Temurin.25.JDK` |
| **PostgreSQL 16+** | `sudo apt install postgresql` | [postgresql.org/download/windows](https://www.postgresql.org/download/windows/) or `winget install PostgreSQL.PostgreSQL` |
| **yt-dlp** | `sudo curl -Lo /usr/local/bin/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux` then `chmod +x` (or `yt-dlp_linux_aarch64` on ARM64) | `winget install yt-dlp.yt-dlp` |
| **Deno** | `curl -fsSL https://deno.land/install.sh \| sh` | `winget install DenoLand.Deno` |
| **ffmpeg** | `sudo apt install ffmpeg` | Download from [gyan.dev/ffmpeg/builds](https://www.gyan.dev/ffmpeg/builds/) or `winget install Gyan.FFmpeg` |
| **Claude Code** | [claude.com/claude-code](https://claude.com/claude-code) (native installer) or `npm install -g @anthropic-ai/claude-code` | Same |
| **cloudflared** | [pkg.cloudflare.com/cloudflared](https://pkg.cloudflare.com/cloudflared) | `winget install Cloudflare.cloudflared` |
| **Tailscale** | [tailscale.com/download/linux](https://tailscale.com/download/linux) | [tailscale.com/download/windows](https://tailscale.com/download/windows) |

> **ARM64 note (Raspberry Pi):** use `yt-dlp_linux_aarch64`, and make sure Claude Code's login works on a headless machine — see the deployment guide at [`docs/DEPLOY.md`](../docs/DEPLOY.md).

---

## Configuration

```bash
cp .env.example .env
```

Fill in `.env` — every variable is documented in the file itself and in `docs/DESIGN.md` §13.1. The minimum required to start:

```env
# Database
CLIPHIVE_DB_URL=jdbc:postgresql://localhost:5432/cliphive
CLIPHIVE_DB_USER=cliphive
CLIPHIVE_DB_PASSWORD=<password>

# Data directory (put on SSD — jobs write GBs here temporarily)
CLIPHIVE_DATA_DIR=/var/lib/cliphive/data

# Superadmin account (created once on first boot)
CLIPHIVE_ADMIN_USER=admin
CLIPHIVE_ADMIN_PASSWORD=<strong-password>

# Encryption key MUST be Base64 (EncryptionService decodes it as Base64):
#   openssl rand -base64 32
CLIPHIVE_ENCRYPTION_KEY=<32-byte-base64>
# Signing key is any long random string:
#   openssl rand -hex 32
CLIPHIVE_URL_SIGNING_KEY=<random-string>

# Worker listener — bind to the Tailscale IP
CLIPHIVE_WORKER_BIND=<tailscale-ip>
CLIPHIVE_WORKER_PORT=8082

# Public URLs (OAuth redirects, Instagram media) and the operator named on /legal pages
CLIPHIVE_ADMIN_PUBLIC_URL=https://cliphive.example.com
CLIPHIVE_MEDIA_PUBLIC_URL=https://cliphive-media.example.com
CLIPHIVE_OPERATOR_NAME=Your Name
CLIPHIVE_OPERATOR_EMAIL=you@example.com
```

At startup the server checks this configuration and stops with the full list of problems if something essential is missing or malformed; missing public URLs or operator details are only warnings ([DEPLOY.md §6](../docs/DEPLOY.md#6-configure-env)).

Non-secret runtime settings (publish mode, email alerts, default language, etc.) are edited from the superadmin web UI at runtime — they live in the `setting` table, not in `.env`.

---

## Running (development)

On a fresh clone, install the parent POM and the `protocol` module into the local Maven repository first, or `-pl server` can't resolve them:

```bash
./mvnw -q -B -N install && ./mvnw -q -B -pl protocol install -DskipTests
```

`spring-boot:run` doesn't read `.env`: export its variables in your shell first, or use `run-server.ps1 -Mode normal` on Windows ([DEVELOPMENT.md](../docs/DEVELOPMENT.md#server-with-the-embedded-profile)).

```bash
# Linux/macOS
./mvnw spring-boot:run -pl server

# Windows
mvnw.cmd spring-boot:run -pl server
```

With the `embedded` Spring profile the server starts against a throwaway **embedded PostgreSQL** with a seeded test admin — for local tries only. It is never the default: without it, `CLIPHIVE_DB_URL` must point at a real database. Its code lives in `src/embedded/` and only gets into the build with the `-Pembedded` Maven profile, so the production jar can't run it.

```bash
./mvnw spring-boot:run -pl server -Pembedded -Dspring-boot.run.profiles=embedded
```

Log in with `admin` / `admin123`. On Windows, `run-server.ps1` does the same ([DEVELOPMENT.md](../docs/DEVELOPMENT.md#running-locally)).

For a real database:
```bash
./mvnw spring-boot:run -pl server -Dspring-boot.run.profiles=dev
```

---

## Running (production)

Each `server-vX.Y.Z` release ships the production jar (`cliphive-server-X.Y.Z.jar`) and a multi-arch Docker image (`ghcr.io/raulgl422/cliphive-server`). To build the JAR yourself:

```bash
./mvnw -pl server -am package -DskipTests
```

Then run:
```bash
java -jar server/target/server-*.jar
```

See [`docs/DEPLOY.md`](../docs/DEPLOY.md) for the full Raspberry Pi deployment guide: PostgreSQL setup, systemd service, Tailscale, Cloudflare Tunnel.

---

## Workers

Workers connect over Tailscale to `ws://CLIPHIVE_WORKER_BIND:CLIPHIVE_WORKER_PORT/ws/worker`. Register a worker in the superadmin (*Workers → Registrar*) to obtain a token, then give the token to the worker — see [docs/INSTALL-WORKER.md](../docs/INSTALL-WORKER.md).

---

## Data model

Flyway migrations in `src/main/resources/db/migration/` (`V1`, `V2`, …; a migration is never edited once committed), matching the schema in [`docs/DESIGN.md` §14.2](../docs/DESIGN.md). One JPA entity per table under `model/`; enums shared with `protocol` where a column is a state machine (`VideoJobState`, `TaskState`, `ClipState`, `ScheduledPostState`) and a `server`-local enum under `model/enums/` otherwise (`Platform`, `ReviewStatus`, `ScheduledPostOrigin`, `NotificationStatus`, `ClipMode`, `PublishMode`).

`ClipHiveServerApplicationTests` boots the full context against an embedded PostgreSQL (`io.zonky.test:embedded-postgres`) to apply every migration and let Hibernate validate every entity — no Docker needed for tests.

> **Spring Boot 4 note:** `spring-boot-starter-flyway` is required as a separate dependency (Boot 4 split Flyway's autoconfiguration). Just having `flyway-core` on the classpath, which was enough in Boot 3, now makes Flyway silently never run.
