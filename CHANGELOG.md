# Changelog

All notable changes to ClipHive. The format follows [Keep a Changelog](https://keepachangelog.com/);
the server and the worker are released separately, as `server-vX.Y.Z` tags (Docker image on
`ghcr.io/raulgl422/cliphive-server` and the jar on the Releases page) and `worker-vX.Y.Z` tags (Windows MSI on
the Releases page). Versions before the first public release were published privately and aren't on the
Releases page; their notes are kept below.

## [Unreleased]

## server-v0.1.0 and worker-v0.1.7 (2026-10-26)

The first public release, and the first versioned release of the server: before it, the server was built
from the sources. Worker changes are counted from `worker-v0.1.6`.

### Upgrade notes
- **Server `.env`**: add `CLIPHIVE_OPERATOR_NAME` and `CLIPHIVE_OPERATOR_EMAIL` — the public privacy and
  terms pages now name whoever runs the installation (they said "not configured" without them).
  `.env.example` now leaves every personal value empty (public URLs, operator, SMTP host/user/sender) with an
  `# e.g.` comment, so the startup and Settings warnings fire until you fill them.
- **Server startup** now stops with a list when required configuration is missing or malformed:
  database URL and user, an absolute `CLIPHIVE_DATA_DIR`, a signing key of at least 32 characters and a
  Base64 encryption key of exactly 32 bytes (`openssl rand -base64 32`).
- **Local tries**: the `embedded` profile is no longer in the production jar; build with `-Pembedded` for it
  (`run-server.ps1` does). A production jar started with that profile stops with a clear error. The embedded
  profile binds the worker listener to `127.0.0.1`, so a local worker can connect.
- **Java packages** moved to `io.github.raulgl422.cliphive` (Maven groupId too). A fork with local changes
  has to move its own files the same way.

### Security
- 2FA could be bypassed: a password-only session reached the TOTP setup page. Setup and disable now need
  the full session.
- The login lockout now blocks (per IP for passwords, per account for TOTP codes), and the client IP is
  the last `X-Forwarded-For` hop.
- The public media hostname can't reach the login: the per-port path filter runs before Spring Security.
- A worker can only accept, finish, fail or release tasks assigned to it, not another worker's.
- `claude -p` runs with no tools, in an empty temporary folder, so a crafted transcript can't make it read
  server files.
- The worker only accepts a whisper model *name* from the server, never a path.
- Worker file endpoints accept only the task's current attempt.
- Session cookie marked `Secure` and `SameSite=Lax`.
- Only `http(s)` video links are accepted, and yt-dlp gets the URL after `--`.
- The production jar no longer carries the embedded profile's test admin and keys (148 MB → 67 MB).

### Fixed
- Server: external tools (`claude -p`, ffmpeg, yt-dlp) and API calls can no longer hang it; scheduled jobs
  run on their own threads.
- Server: nothing stays stuck after a restart, a stalled task or a late callback, and a video is never
  uploaded twice; when Claude's analysis fails the provisional cuts go to review.
- Superadmin: unticking *Revisión* on a new job really turns review off for it.
- Worker: reports its real version in `HELLO`. An encoder you forced that fails the autotest leaves the worker
  unable to take tasks (instead of failing every render); with `auto`, the software encoder is tested before
  switching to it.
- Docs: the server README generated an encryption key the server rejects; DEPLOY.md and `.env.example`
  used the author's domain; the design document and the guides were checked against the code.

### Added
- Server: startup configuration check; startup banner with the app name and version; Docker healthcheck, log
  rotation and pinned base images.
- Publishing: one YouTube playlist per source video; drafts and private uploads wait for your confirmation,
  with resend and download.
- Superadmin: change the password from Settings (current password, new one twice, 2FA code).
- Server: yt-dlp updates itself daily and logs its version.
- Docs: English user, install, architecture and development guides; DISCLAIMER, NOTICE and a note on AI
  assistance; DEPLOY.md covers backups and what losing the encryption key costs.

### Changed
- Server releases: `server-vX.Y.Z` tags publish the production jar and a multi-arch Docker image (amd64 and
  arm64), and `docker-compose.yml` uses that image; `--build` still builds it from the sources.
- CI: uploading test reports or installers as workflow artifacts can no longer fail a build.

## worker-v0.1.6 (2026-10-24)

### Upgrade notes
- `forcedEncoder` is now honoured on every render. A worker whose first-run autotest switched it to
  `software` renders on the CPU until you set it back to `auto`.
- A worker missing ffmpeg or whisper-cli, or failing its autotest, now takes no tasks and says why
  (headless exits with code 2). The "only on AC power" and "schedule" settings are no longer offered: they
  never did anything.

### Added
- Installer: a `licenses/` folder (`app\licenses\` in the install folder) with THIRD-PARTY-NOTICES.txt and
  every bundled licence, and a pinned, checksum-verified ffmpeg build whose exact source is recorded.
- Dependabot: weekly update PRs for Maven, GitHub Actions and the Docker base images.

### Fixed
- VAAPI renders (they always failed), ffmpeg's timeout and cancel, killing ffmpeg and whisper-cli when the
  worker closes, and an autotest that no longer overrides an encoder you chose.
- Two attempts of the same task no longer share a working folder or a slot.
- Connection: a rejected token or bad server URL shows "Rechazado" with what to fix instead of
  "Conectando…" forever; a watchdog detects half-open connections and reconnects; task results that can't be
  sent are kept and delivered after reconnecting; a pause survives a reconnect.
- Startup: a broken `worker.yml`, a stalled model download or any crash before the window opens now shows an
  error instead of nothing; whisper-cli can be timed out and cancelled; hardware probes can't hang; the window
  can't vanish when the tray icon is missing.
- A worker that can't work (missing tools, failed autotest) is shown as *No puede aceptar tareas* and
  advertises no capacity.

### Changed
- CI: read-only workflows except the release job, third-party actions pinned to commits, installers built
  with tests, ffmpeg installed so the render tests run; the graphify workflow was removed (the graph is
  updated locally after each commit).

## worker-v0.1.0 – worker-v0.1.5 (2026-10-07 – 2026-10-14)
First Windows MSI releases of the worker, published before the repository went public.
