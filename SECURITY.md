# Security Policy

## Supported versions

| Version | Support |
|---------|---------|
| `development` branch and the latest `worker-v*` release | Active |
| Older worker releases | Not supported — update to the latest MSI |

## Reporting a vulnerability

If you find a security vulnerability, **do not open a public issue, discussion or pull request**. Report it privately through GitHub instead:

1. Open the repository on GitHub and go to the **Security** tab.
2. Click **Report a vulnerability** (GitHub's private vulnerability reporting).
3. Include a description of the issue, the steps to reproduce it, the affected component (server, worker, protocol) and version or commit, and the impact you estimate.

The report is only visible to the maintainer. You'll get an answer there as soon as possible — this is a project maintained in spare time, so please allow a few days. Once the vulnerability is confirmed, a fix is published before any public disclosure, and you'll be credited in the advisory unless you prefer otherwise.

## Scope

ClipHive handles OAuth tokens for TikTok, Instagram and YouTube, a single superadmin account, and worker authentication tokens. The security model is described in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#security-model) and in full in [`docs/DESIGN.md`](docs/DESIGN.md) section 13: Cloudflare Access + login with 2FA on the superadmin, a Tailscale-only worker port, signed and time-limited file URLs, and tokens encrypted at rest. Reports about any of these are especially welcome.

Out of scope: weaknesses that need an attacker who already controls the server machine or its `.env`, and issues in the third-party tools ClipHive runs (yt-dlp, ffmpeg, whisper.cpp, Claude Code) — report those upstream.
