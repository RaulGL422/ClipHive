# /check-security — Security Audit

Performs a security review focused on the project's code, scoped to ClipHive's actual security model (`docs/DESIGN.md` section 13).

## Usage
- `/check-security` — audits all code modified since `development`
- `/check-security server/src/main/java/io/github/raulgl422/cliphive/server/security/TokenService.java` — audits a specific file

## Step 1: Determine scope

If a file was passed as argument, audit that file.

If not, get the files modified since development:
```bash
git diff development...HEAD --name-only -- "*.java"
```

If no files are modified, audit the project's critical security surfaces once they exist:
- Superadmin login + 2FA
- OAuth token storage/refresh (TikTok, Meta, Google)
- Worker authentication (token issuance, revocation)
- Signed URL generation (worker files, `/media/{token}`)

## Step 2: Read and analyze the files

Read each file in scope and evaluate against ClipHive's model:

---

### Network boundary (docs/DESIGN.md §3.2)
- Does anything bind to more than its intended interface? Admin must be `localhost`-only, media `localhost`-only (`/media/**` exclusively), workers Tailscale-interface-only.
- Does a new route under the media connector serve anything beyond `GET /media/{token}`?

### Signed URLs (§13, §7)
- Are file URLs (worker transfer, `/media/{token}`) signed and time-limited?
- Are worker-file URLs tied to `taskId + attempt` (the fencing token), rejecting stale `attempt` values?
- Is the `/media/{token}` token single-clip and short-lived (on the order of ~1h)?

### Secrets and encryption (§13.1)
- Are OAuth tokens (TikTok/Meta/Google) and the TOTP secret encrypted at rest with `CLIPHIVE_ENCRYPTION_KEY`, never plaintext in the DB or logs?
- Is `CLIPHIVE_URL_SIGNING_KEY` the only thing that can produce a valid signed URL?
- Any hardcoded credential, key, or token anywhere in the diff?

### Superadmin auth
- Cloudflare Access is the first gate, a local login with 2FA (TOTP) is the second — does any admin route bypass either?
- Rate limiting and lockout on repeated login failures?
- Single account, no public registration endpoint?

### Worker auth
- Does a worker token grant access to anything beyond the WebSocket and that worker's own task files?
- Is token revocation effective immediately (closes the active session)?

### Protocol integrity (§6.3, §7)
- Does every task-scoped message carry `taskId` + `attempt`, and does the server reject messages with a stale `attempt`?
- Does `HELLO` carry a protocol version, and does a mismatch get rejected rather than silently processed?

### Logging / retention (§14, §15)
- Do logs avoid storing titles, links, or other content that should be purged under the "no trace left" retention policy?
- Is log retention bounded and configurable?

---

## Step 3: Generate security report

```
## Security Audit — [date]

**Files analyzed:** [list]

### 🔴 Critical Vulnerabilities (patch urgently)
[list with description, exact location (file:line) and proposed fix]

### 🟡 Medium Vulnerabilities (patch soon)
[list with description, location and recommendation]

### 🟢 Hardening Improvements (best practices)
[optional suggestions to strengthen security]

### ✅ Correct Controls
[highlights what is implemented correctly]

---

**Risk score:** [HIGH / MEDIUM / LOW]
**Recommendation:** [immediate action if there are critical issues]
```

For each vulnerability found, show the vulnerable code and the proposed fix with code.
