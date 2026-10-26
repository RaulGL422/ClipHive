# ClipHive documentation

Start with the [project README](../README.md) for what ClipHive is. Then, depending on what you want to do:

| Document | What it's for |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | How ClipHive is built: components and their boundaries, the three network listeners, the video pipeline step by step, task fault tolerance, the WebSocket protocol, worker internals, Claude, publishing, security, data retention. |
| [DEPLOY.md](DEPLOY.md) | Installing the server on a Raspberry Pi or any 64-bit Linux machine — Docker or bare metal, PostgreSQL, Tailscale, Cloudflare Tunnel + Access, the startup configuration check, legal pages, email alerts, the YouTube/Instagram/TikTok developer apps, backups. |
| [INSTALL-WORKER.md](INSTALL-WORKER.md) | Installing a worker from the Windows MSI: GPU drivers and Tailscale, first-run setup, the window and tray states, the `worker.yml` reference, headless mode, logs, uninstalling, troubleshooting. |
| [USER-GUIDE.md](USER-GUIDE.md) | Using the superadmin day to day: submitting links and their options, review and manual selection, accounts, publications and confirmations, statistics, workers, logs, settings, 2FA, email alerts. |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Working on the code: prerequisites, module layout, build and test commands, the `embedded` profile and the `run-*.ps1` helpers, conventions, CI, releasing the worker, Dependabot. |
| [DESIGN.md](DESIGN.md) | **The single source of truth** (in Spanish): objective, principles, architecture, pipeline, state machines, queue and fault tolerance, protocol, processing spec, Claude prompts and validation, publishing strategy, superadmin, security, database schema, retention, CI and packaging, test results, the phased plan, every decision taken and pending, risks, and the technical review behind fixes R1–R17. Design changes are written here before they're implemented; the English guides summarise it. |

Elsewhere in the repository:

- [CONTRIBUTING.md](../CONTRIBUTING.md) — branches, commit format, pull requests.
- [SECURITY.md](../SECURITY.md) — how to report a vulnerability privately.
- [CHANGELOG.md](../CHANGELOG.md) — notable changes and upgrade notes.
- [DISCLAIMER.md](../DISCLAIMER.md) — no warranty, lawful use, responsibility (English and Spanish).
- [LICENSE](../LICENSE), [NOTICE](../NOTICE) and the worker's [THIRD-PARTY-NOTICES.txt](../worker/packaging/licenses/THIRD-PARTY-NOTICES.txt).
- Module READMEs: [protocol](../protocol/README.md), [server](../server/README.md), [worker](../worker/README.md).
- The public privacy policy and terms pages that TikTok, Meta and Google review are served by the server from [`server/src/main/resources/legal/`](../server/src/main/resources/legal/) and name the operator from `CLIPHIVE_OPERATOR_NAME`/`CLIPHIVE_OPERATOR_EMAIL` ([DESIGN.md §11.5](DESIGN.md#115-alta-como-desarrollador-en-cada-plataforma)).
- [CLAUDE.md](../CLAUDE.md) — context and conventions for Claude Code sessions in this repository.
