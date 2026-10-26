# Contributing to ClipHive

Thanks for your interest in contributing! This guide covers the workflow; [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) covers building, testing, running locally and the code conventions in detail.

---

## Before you start

- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and for anything non-trivial the relevant section of [docs/DESIGN.md](docs/DESIGN.md) (Spanish). DESIGN.md is the **single source of truth** for architecture and design decisions.
- **Design document first**: if your change implies a design decision, update `docs/DESIGN.md` first — in its own commit (`docs(design): …`) — and then implement it. Decisions marked as taken in DESIGN.md §20.1 aren't re-opened in a PR; pending ones (§20.2) are discussed in an issue before being settled.
- For bigger changes, open an issue first to agree on the approach.

## Getting started

1. Fork the repository and clone your fork. You need **JDK 25**; Maven comes with the wrapper.
2. Build and run the tests:
   ```bash
   ./mvnw compile        # mvnw.cmd on Windows
   ./mvnw test
   ```
   Or per module: `./mvnw -q -B -pl server test` and `./mvnw -q -B -pl protocol,worker test`. After changing `protocol`, run `./mvnw -q -B -pl protocol install -DskipTests` first so `server` and `worker` see it. Tests need no Docker; install ffmpeg to run the render tests too.
3. To try the server locally, use the `embedded` profile (no `.env` needed): see [DEVELOPMENT.md](docs/DEVELOPMENT.md#running-locally). For a real setup, `cp .env.example .env` and fill it in.

---

## Branches

- `development` is the integration branch: **branch off from `development`** and open your PR against it.
- `main` only moves when `development` is merged for a release. Never target `main`.

Branch names:

```
<prefix>/<short-description-in-kebab-case>
```

| Change type | Prefix |
|-------------|--------|
| New feature | `feature/` |
| Bug fix | `fix/` |
| Tests | `test/` |
| Chore / config | `chore/` |
| Documentation | `docs/` |

Examples: `feature/render-clip-encoder`, `fix/websocket-heartbeat-timeout`. ASCII only, at most 55 characters.

---

## Commit format

[Conventional Commits](https://www.conventionalcommits.org/), in **English**:

```
<type>(<optional scope>): <short description>
```

| Type | When to use |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Code restructuring without behavior change |
| `test` | Adding or fixing tests |
| `chore` | Dependencies, config, tooling |
| `docs` | Documentation only (`docs(design): …` for DESIGN.md) |
| `perf` | Performance improvement |
| `ci` | CI/CD changes |

Scopes are usually the module: `server`, `worker`, `protocol`, `design`, `deps`.

```
feat(worker): add karaoke ASS subtitle generation
fix(protocol): bump attempt on TASK_RELEASE reassignment
chore(deps): bump maven-compiler-plugin
```

---

## Pull requests

1. Push your branch and open a PR toward `development`.
2. Describe what it does, the main changes and how to test it.
3. Make sure the build and tests pass (`./mvnw test`); CI runs the same on every PR.
4. Request a review.

### Rules

- One logical change per PR — keep diffs focused.
- Add or update tests for the behaviour you change.
- `protocol` stays free of business logic — only messages, DTOs and state enums.
- The worker never gains access to social accounts, OAuth tokens, global logs or statistics.
- Multiplatform rules apply to every module: `java.nio.file.Path`, never hand-written separators; external binaries configurable and run through `ProcessBuilder` with an argument list, never through `cmd`/`bash` ([DEVELOPMENT.md](docs/DEVELOPMENT.md#conventions)).
- No secrets in code, config, tests or commit history — use `.env` / environment variables, and document new variables in `.env.example`.
- Database changes go in a new Flyway migration; never edit a committed one.
- Update the docs your change affects: `docs/DESIGN.md` for design, and the English guides in `docs/` (and `CHANGELOG.md` under *Unreleased*) for user-visible behaviour.

---

## AI-assisted contributions

ClipHive itself was built with substantial help from AI (Claude, through Claude Code), and AI-assisted contributions are welcome on the same terms as any other: **you're responsible for what you submit**. Read and understand every line, test it, make sure it follows the rules above and doesn't include code or text you don't have the right to contribute. Mentioning in the PR that you used an AI tool is appreciated. If you use Claude Code, [CLAUDE.md](CLAUDE.md) gives it the project's context and conventions.

---

## Code style

- **Java 25**; names and comments in English. User-facing text in the superadmin and the worker window is currently in Spanish.
- Prefer immutable types (`record`) for DTOs and protocol messages.
- Use interfaces for anything with more than one possible implementation (`Publisher`, `LlmClient`, `Encoder` — DESIGN.md §2.3) so new platforms or backends don't require touching callers.
- There's no auto-formatter on purpose (some constants are hand-aligned): follow the style of the surrounding code.

---

## Licence of contributions

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE), as stated in its section 5.

## Security

If you find a security vulnerability, **do not open a public issue**. See [SECURITY.md](SECURITY.md) for private reporting.
