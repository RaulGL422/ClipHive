# ClipHive — Deployment Guide (Raspberry Pi / Linux)

This guide covers deploying the ClipHive server on a **Raspberry Pi 4/5 with a 64-bit OS** (Raspberry Pi OS Lite 64-bit or Ubuntu Server 24.04 ARM64). The same steps apply to any Linux x86\_64 machine. The server is documented for **Linux only**: the code avoids OS assumptions, but running it on Windows isn't covered by this guide or tested.

> **Verify you're on 64-bit:** `uname -m` must return `aarch64` (ARM64) or `x86_64`. A 32-bit OS won't run Java 25.

There are two ways to run the server:

- **Docker (recommended)** — section 0 below. No Java, ffmpeg, yt-dlp, Deno or Claude Code on the host; everything is inside the image.
- **Bare metal + systemd** — sections 1–9.

Cloudflare Tunnel (section 8) and the first boot checklist (section 10) apply to both.

The examples use `cliphive.example.com` (superadmin) and `cliphive-media.example.com` (media for Instagram) on a domain of yours managed in Cloudflare: replace them with your own hostnames everywhere, including `.env` and the OAuth redirect URIs you register. Keep them **one-level** subdomains: Cloudflare's free certificate covers `*.example.com` but not `*.cliphive.example.com`.

What you'll end up with is described in [ARCHITECTURE.md](ARCHITECTURE.md#network-three-listeners). Once the server runs, install a worker ([INSTALL-WORKER.md](INSTALL-WORKER.md)) and follow the [user guide](USER-GUIDE.md).

> **Use the production build.** The `embedded` profile (throwaway embedded PostgreSQL, admin `admin`/`admin123`, test keys) is for local tries on a development machine only. It isn't in the production jar or the Docker image — it's only compiled in with `-Pembedded` ([DEVELOPMENT.md](DEVELOPMENT.md#server-with-the-embedded-profile)). Never deploy a jar built with `-Pembedded`.

---

## 0. Docker deployment

### 0.1 Requirements on the host

- Docker with the Compose plugin (`docker compose version`).
- PostgreSQL reachable from the host at `localhost:<port>` (a Postgres container with its port published works — `docker ps` must show something like `127.0.0.1:5432->5432/tcp` or `0.0.0.0:5432->5432/tcp`). No Postgres yet? See section 0.2.
- Tailscale on the host, logged in and **up before the container starts**:
  ```bash
  curl -fsSL https://tailscale.com/install.sh | sh
  sudo tailscale up        # prints a login URL; open it in any browser
  tailscale ip -4          # the address for CLIPHIVE_WORKER_BIND
  ```
  The server binds the worker listener to that Tailscale IP. If Tailscale isn't up yet when the container starts, the address doesn't exist and the bind fails, so the server doesn't start (`restart: unless-stopped` retries it). If you leave `CLIPHIVE_WORKER_BIND` blank, the worker listener isn't started at all and the log only shows a warning: no worker can connect.
- cloudflared on the host (section 1 shows how to install it, section 8 how to set it up).

The container uses **host networking** (`network_mode: host`). The server binds its admin and media connectors to `127.0.0.1` and the worker connector to `CLIPHIVE_WORKER_BIND` (docs/DESIGN.md §3.2); with host networking those are the host's own addresses, so the security model is the same as bare metal. In bridge mode the `127.0.0.1` listeners would be unreachable from outside the container.

Because it shares the host's ports, make sure 8080/8081/8082 are free (`sudo ss -tlnp | grep -E ':808[0-2]'`). If another service already uses one, change `CLIPHIVE_ADMIN_PORT` / `CLIPHIVE_MEDIA_PORT` / `CLIPHIVE_WORKER_PORT` in `.env`.

### 0.2 Database

**If you already run a Postgres container**, create the user and database inside it:

```bash
docker exec -it <postgres-container> psql -U postgres -c "CREATE USER cliphive WITH PASSWORD 'change-me';"
docker exec -it <postgres-container> psql -U postgres -c "CREATE DATABASE cliphive OWNER cliphive;"
```

**If you have no Postgres at all**, either install it on the host (`sudo apt install -y postgresql`, then section 3), or add a database service to `docker-compose.yml`. The repository's compose file has no database service; this is a snippet you can add yourself (the `cliphive` service uses host networking, so publish the port on `127.0.0.1` and it reaches it at `localhost:5432`):

```yaml
services:
  # ... the existing cliphive service ...
  db:
    image: postgres:16
    restart: unless-stopped
    environment:
      POSTGRES_USER: cliphive
      POSTGRES_PASSWORD: change-me        # same as CLIPHIVE_DB_PASSWORD
      POSTGRES_DB: cliphive
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  claude-home:
  pgdata:
```

The official image creates the user and database from those variables on its first start. Start it before the server (`docker compose up -d db`).

Flyway creates the schema on first boot.

### 0.3 Install and run

Each server release (`server-vX.Y.Z` on the [Releases](https://github.com/RaulGL422/ClipHive/releases) page) publishes a ready-made image for `linux/amd64` and `linux/arm64` (Raspberry Pi) at `ghcr.io/raulgl422/cliphive-server`. The repository is only needed for `docker-compose.yml` and `.env.example`:

```bash
git clone https://github.com/RaulGL422/ClipHive.git /opt/cliphive
cd /opt/cliphive
git checkout server-v0.1.0        # optional: the files of the release you run
cp .env.example .env && chmod 600 .env
nano .env                         # see section 6 for the values

mkdir -p data && sudo chown 1001:1001 data   # container runs as uid 1001

docker compose pull               # downloads the published image
docker compose up -d
docker compose logs -f
```

The compose file uses the `latest` image. To pin a release, add `CLIPHIVE_VERSION=0.1.0` to `.env` (Compose reads it for the image tag; the server ignores it).

**Building the image yourself** instead (a fork, or local changes): `docker compose up -d --build` builds it from the checked-out sources. The first build on a Pi takes several minutes. The tools the image downloads while it builds (Deno, Claude Code and the latest yt-dlp) aren't pinned, so two builds of the same version can carry different versions of them.

Notes for `.env` under Docker:

- `CLIPHIVE_DATA_DIR` is ignored — the compose file pins it to `/data` inside the container, mapped to `./data` on the host (override with `CLIPHIVE_DATA_HOST_DIR`). Put it on an SSD.
- Leave the `*_PATH` tool variables empty — all tools are on the image's PATH.
- Claude Code runs inside the container: authenticate it with `CLAUDE_CODE_OAUTH_TOKEN` (section 4 explains how to get one) or set `ANTHROPIC_API_KEY` (the CLI uses it; the server always goes through `claude -p`).
- After changing `.env`, recreate the container with `docker compose up -d`.
- `CLIPHIVE_JAVA_OPTS` (optional) overrides the default heap `-Xms128m -Xmx512m`.

If the container stops right after starting, read `docker compose logs`: the server checks its configuration first and prints **the full list** of what's missing or malformed (section 6).

### 0.4 Updating

Read the [CHANGELOG](../CHANGELOG.md) upgrade notes first (new `.env` keys, for example), then:

```bash
cd /opt/cliphive
git pull                          # newer docker-compose.yml and .env.example
docker compose pull               # or set the new CLIPHIVE_VERSION in .env first
docker compose up -d
```

If you build the image yourself, use `git pull` and `docker compose up -d --build` instead.

Update only yt-dlp (it breaks often when sites change) without a new image:

```bash
docker compose exec cliphive yt-dlp -U
```

The server also runs `yt-dlp -U` once a day by itself (section 9). A new image comes with the yt-dlp that was latest when it was built.

---

## 1. Install system dependencies

```bash
sudo apt update && sudo apt upgrade -y

# PostgreSQL
sudo apt install -y postgresql postgresql-contrib

# ffmpeg (from the distribution — LGPL build sufficient for the server; the worker
# ships its own static ffmpeg from the installer)
sudo apt install -y ffmpeg

# yt-dlp (arm64 binary — replace with yt-dlp_linux for x86_64), in its own folder
# so the cliphive user can own it and `yt-dlp -U` can replace it (section 9)
sudo mkdir -p /opt/tools
sudo curl -Lo /opt/tools/yt-dlp \
  https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64
sudo chmod +x /opt/tools/yt-dlp

# Deno (needed by yt-dlp to bypass YouTube bot detection), system-wide in
# /usr/local/bin so the cliphive service user finds it too — same as the Dockerfile
sudo apt install -y unzip          # the Deno installer needs it
curl -fsSL https://deno.land/install.sh | sudo DENO_INSTALL=/usr/local sh -s -- -y
deno --version

# Tailscale
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up   # opens browser login

# cloudflared
curl -L https://pkg.cloudflare.com/cloudflared/key.gpg | \
  sudo gpg --dearmor -o /usr/share/keyrings/cloudflare-main.gpg
echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] \
  https://pkg.cloudflare.com/cloudflared bookworm main" | \
  sudo tee /etc/apt/sources.list.d/cloudflared.list
sudo apt update && sudo apt install -y cloudflared
```

---

## 2. Install Java 25

Download the latest **Temurin 25 JRE or JDK** `.tar.gz` for your architecture (`aarch64` on a Pi, `x64` otherwise) from [adoptium.net](https://adoptium.net/temurin/releases/?version=25), then:

```bash
sudo tar -xzf OpenJDK25U-*_linux_hotspot_*.tar.gz -C /opt
ls /opt                                      # e.g. jdk-25.0.1+8
sudo ln -sf /opt/jdk-25*/bin/java /usr/local/bin/java
java -version                                # should print "openjdk version "25..."
```

The systemd unit in section 7 runs `/usr/local/bin/java`. (Your distribution may also package Temurin 25 through the Adoptium apt repository.)

---

## 3. Set up PostgreSQL

```bash
sudo -u postgres psql <<'SQL'
CREATE USER cliphive WITH PASSWORD 'change-me';
CREATE DATABASE cliphive OWNER cliphive;
SQL
```

Test:
```bash
psql -h localhost -U cliphive -d cliphive -c '\l'
```

---

## 4. Install Claude Code

```bash
# Install via npm (requires Node.js)
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
sudo npm install -g @anthropic-ai/claude-code
```

**Headless authentication (no browser on the Pi):**

On your desktop machine:
```bash
claude setup-token
# Copy the printed token
```

On the Pi (do this **before** creating the systemd service):
```bash
export CLAUDE_CODE_OAUTH_TOKEN=<token-from-setup-token>
# Test it:
echo "Say hello" | claude -p --output-format text
```

Add it to the `.env` file in the next step.

---

## 5. Create the ClipHive user and directories

```bash
sudo useradd -r -m -d /opt/cliphive -s /bin/bash cliphive
sudo mkdir -p /var/lib/cliphive/data
sudo chown cliphive:cliphive /var/lib/cliphive/data

# yt-dlp's folder from section 1: the user must own the directory, not just the
# file, because `yt-dlp -U` writes the new binary next to the old one
sudo chown -R cliphive:cliphive /opt/tools

# Place the server JAR — the production jar of a release (replace 0.1.0)
V=0.1.0
cd /tmp
curl -fLO "https://github.com/RaulGL422/ClipHive/releases/download/server-v$V/cliphive-server-$V.jar"
curl -fLO "https://github.com/RaulGL422/ClipHive/releases/download/server-v$V/cliphive-server-$V.jar.sha256"
sha256sum -c "cliphive-server-$V.jar.sha256"
sudo cp "cliphive-server-$V.jar" /opt/cliphive/server.jar
sudo chown cliphive:cliphive /opt/cliphive/server.jar
```

To build the jar from the sources instead: `./mvnw -pl server -am package -DskipTests` (never with `-Pembedded`), then copy `server/target/server-*.jar` to `/opt/cliphive/server.jar`.

---

## 6. Configure `.env`

```bash
sudo -u cliphive cp /path/to/ClipHive/.env.example /opt/cliphive/.env
sudo -u cliphive nano /opt/cliphive/.env
```

Every variable is documented in `.env.example` and in [DESIGN.md §13.1](DESIGN.md#131-variables-de-entorno-del-servidor-envexample). Values that belong to you (public URLs, operator name and email, SMTP host, user and sender) are **empty** in `.env.example`, with an `# e.g.` comment above each: until you fill them, the startup log and the Settings page keep warning about them. Minimum values:

```env
CLIPHIVE_DB_URL=jdbc:postgresql://localhost:5432/cliphive
CLIPHIVE_DB_USER=cliphive
CLIPHIVE_DB_PASSWORD=change-me

CLIPHIVE_DATA_DIR=/var/lib/cliphive/data

CLIPHIVE_ADMIN_USER=admin
CLIPHIVE_ADMIN_PASSWORD=<strong-password>

# Encryption key MUST be Base64 (EncryptionService decodes it as Base64):
#   openssl rand -base64 32
CLIPHIVE_ENCRYPTION_KEY=<32-byte-base64>
# Signing key is any long random string:
#   openssl rand -hex 32
CLIPHIVE_URL_SIGNING_KEY=<random-string>

# Worker listener — use the Tailscale IP of this machine
# Find it with: tailscale ip -4
CLIPHIVE_WORKER_BIND=100.x.x.x
CLIPHIVE_WORKER_PORT=8082

# Public URLs — the hostnames of the Cloudflare Tunnel (step 8)
CLIPHIVE_ADMIN_PUBLIC_URL=https://cliphive.example.com
CLIPHIVE_MEDIA_PUBLIC_URL=https://cliphive-media.example.com

# Who runs this installation — named on the public privacy and terms pages (section 13)
CLIPHIVE_OPERATOR_NAME=Your Name
CLIPHIVE_OPERATOR_EMAIL=you@example.com

# Claude Code token (from step 4)
CLAUDE_CODE_OAUTH_TOKEN=<token>

# yt-dlp lives outside the PATH (section 1)
CLIPHIVE_YTDLP_PATH=/opt/tools/yt-dlp
```

`CLIPHIVE_ADMIN_USER` and `CLIPHIVE_ADMIN_PASSWORD` create the superadmin account on the first boot only. If either is empty, **no account is created** and the log only shows a warning, so you can't log in: set both and restart.

**Startup check.** Before starting anything, the server validates this configuration and, if something essential is wrong, stops with the complete list instead of a Spring stack trace:

- **Required**: `CLIPHIVE_DB_URL` (a `jdbc:postgresql:` URL) and `CLIPHIVE_DB_USER`; `CLIPHIVE_DATA_DIR` as an **absolute** path (a relative one would end up next to the program, perhaps on the SD card); `CLIPHIVE_URL_SIGNING_KEY` of at least 32 characters; `CLIPHIVE_ENCRYPTION_KEY` as Base64 of exactly 32 bytes (`openssl rand -base64 32`).
- **Warnings only** (in the log): a missing `CLIPHIVE_ADMIN_PUBLIC_URL` or `CLIPHIVE_MEDIA_PUBLIC_URL` (you can't connect accounts or publish to Instagram without them) or missing operator details. When set, the public URLs must be `http(s)`.

Secure the file:
```bash
sudo chmod 600 /opt/cliphive/.env
```

---

## 7. Create the systemd service

```bash
sudo tee /etc/systemd/system/cliphive.service > /dev/null <<'UNIT'
[Unit]
Description=ClipHive Server
After=network.target postgresql.service

[Service]
Type=simple
User=cliphive
WorkingDirectory=/opt/cliphive
EnvironmentFile=/opt/cliphive/.env
ExecStart=/usr/local/bin/java -jar /opt/cliphive/server.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=cliphive

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable cliphive
sudo systemctl start cliphive
sudo systemctl status cliphive
```

Check logs:
```bash
journalctl -u cliphive -f
```

The service reads `.env` only when it starts: after every later change to `.env`, run `sudo systemctl restart cliphive`.

Tailscale must be up before the server starts, or binding the worker listener to `CLIPHIVE_WORKER_BIND` fails (section 0.1 explains why). `Restart=on-failure` retries every 10 s, so it recovers once Tailscale is connected.

---

## 8. Cloudflare Tunnel

### 8.1 Create the tunnel

```bash
sudo cloudflared tunnel login     # prints a URL: open it in a browser on any machine and pick your domain
sudo cloudflared tunnel create cliphive
```

Running both with `sudo` keeps the files in root's home: the login certificate in `/root/.cloudflared/cert.pem` and the tunnel credentials in `/root/.cloudflared/<tunnel-id>.json`, which is the path the config below uses. (If you run them as your own user, the files go to `~/.cloudflared/` instead: point `credentials-file` there, or copy the JSON to `/etc/cloudflared/` and use that path.)

`tunnel create` prints the tunnel ID. Create the config file:

```bash
sudo mkdir -p /etc/cloudflared
sudo tee /etc/cloudflared/config.yml > /dev/null <<EOF
tunnel: <tunnel-id>
credentials-file: /root/.cloudflared/<tunnel-id>.json
ingress:
  - hostname: cliphive.example.com
    service: http://localhost:8080
  # Only /media/ goes to the media port: this hostname has no Access in front
  - hostname: cliphive-media.example.com
    path: ^/media/
    service: http://localhost:8081
  - service: http_status:404
EOF
```

### 8.2 DNS records

In your Cloudflare dashboard, add two CNAME records pointing to the tunnel:
- `cliphive.example.com` → `<tunnel-id>.cfargotunnel.com`
- `cliphive-media.example.com` → `<tunnel-id>.cfargotunnel.com`

### 8.3 Cloudflare Access (admin only)

For `cliphive.example.com`, create a **Cloudflare Access Application**:
- Type: Self-hosted
- Domain: `cliphive.example.com`
- Policy: allow only your email address (or your identity provider)

The media subdomain (`cliphive-media.example.com`) does NOT get an Access policy — it's public, but each URL is short-lived and HMAC-signed.

### 8.4 Run cloudflared as a service

```bash
sudo cloudflared service install
sudo systemctl enable cloudflared
sudo systemctl start cloudflared
```

---

## 9. Automatic yt-dlp updates

The server runs `yt-dlp -U` ten minutes after it starts and then once a day, and logs the version (superadmin →
Logs, component `herramientas`). In Docker this just works: the image keeps yt-dlp in `/opt/tools`, owned by the app
user. On bare metal, do the same as sections 1, 5 and 6: the standalone binary in `/opt/tools`, the **directory**
owned by `cliphive` (`yt-dlp -U` writes the new binary next to the old one and swaps them, so owning only the file
isn't enough), and `CLIPHIVE_YTDLP_PATH=/opt/tools/yt-dlp` in `.env`:

```bash
sudo chown -R cliphive:cliphive /opt/tools
```

A yt-dlp installed with pip or apt can't update itself, and the log says so.

---

## 10. First boot checklist

After starting the service:

- [ ] The log shows no `Configuration:` warnings you didn't expect (missing public URLs or operator details)
- [ ] Visit `https://cliphive.example.com` — Cloudflare Access login, then ClipHive login
- [ ] Change the admin password and enable 2FA in **Ajustes → Seguridad** (Settings → Security)
- [ ] Register a worker in **Workers** and install it on a machine with a GPU ([INSTALL-WORKER.md](INSTALL-WORKER.md))
- [ ] Connect social accounts in **Cuentas** (Accounts; sections 11, 12 and 15)
- [ ] Paste a test video link in **Trabajos** (Jobs). New installations publish in *Inmediato* mode; switch to the Claude planner in **Ajustes → Publicación** once publishing works ([USER-GUIDE.md](USER-GUIDE.md#settings))

---

## 11. Connect YouTube

Rendered clips are published to every connected account (DESIGN.md §11). YouTube uploads them as **private**
until the Google project passes its audit; making them public is a manual step in YouTube Studio.

1. In [console.cloud.google.com](https://console.cloud.google.com), create a project and enable **YouTube Data API v3**.
2. **OAuth consent screen**: type *External*, add the scopes `.../auth/youtube.upload`, `.../auth/youtube`
   (the per-video playlists), `.../auth/youtube.readonly` and `.../auth/yt-analytics.readonly` (watch time and
   retention for the Estadísticas page), and add your Google account as a test user. Also enable the **YouTube
   Analytics API** in the project's API library.
3. **Publish the app** (*Publishing status → In production*). In *Testing*, Google expires refresh tokens after
   7 days and you'd have to reconnect every week. An unverified app in production just shows a warning screen
   when you connect — that's fine for your own account.
4. **Credentials → Create OAuth client ID → Web application**, with this authorized redirect URI:
   ```
   https://cliphive.example.com/admin/accounts/youtube/callback
   ```
   (it's `<CLIPHIVE_ADMIN_PUBLIC_URL>/admin/accounts/youtube/callback`; the Accounts page shows the exact value).
5. Put the client id and secret in `.env`, plus the public URL, and restart the server:
   ```env
   CLIPHIVE_ADMIN_PUBLIC_URL=https://cliphive.example.com
   CLIPHIVE_GOOGLE_CLIENT_ID=<client id>.apps.googleusercontent.com
   CLIPHIVE_GOOGLE_CLIENT_SECRET=<client secret>
   ```
   ```bash
   docker compose up -d               # Docker: recreates the container with the new .env
   sudo systemctl restart cliphive    # bare metal
   ```
6. Superadmin → **Cuentas** → YouTube → **Conectar**, pick the channel and accept.

With the publish mode set to *inmediato* (Settings), every job that reaches `RENDERED` gets its clips uploaded;
follow them in **Publicaciones**.

---

## 12. Connect Instagram

Instagram publishes Reels **for real** (not as drafts), with no app review needed for your own account (DESIGN.md
§11.4). It fetches each clip from a signed, short-lived URL on `cliphive-media.example.com` — the media hostname from
section 8, which must stay **outside** Cloudflare Access.

1. In the Instagram app, switch your account to **professional** (Creator or Business). No Facebook Page needed.
2. In [developers.facebook.com](https://developers.facebook.com), create an app and add the **Instagram** product →
   *API setup with Instagram login*.
3. Permissions: `instagram_business_basic` and `instagram_business_content_publish`.
4. Add your Instagram account as an **Instagram tester** of the app and accept the invitation in Instagram
   (Settings → Apps and websites → Tester invites). In development mode, accounts with a role in the app can publish
   without App Review.
5. Business login settings → **OAuth redirect URI**:
   ```
   https://cliphive.example.com/admin/accounts/instagram/callback
   ```
6. Copy the **Instagram app ID** and **Instagram app secret** (shown in the Instagram product's API setup — not the
   Facebook app ones) into `.env`, then restart the server (`docker compose up -d`, or `sudo systemctl restart cliphive` on bare metal):
   ```env
   CLIPHIVE_META_APP_ID=<instagram app id>
   CLIPHIVE_META_APP_SECRET=<instagram app secret>
   CLIPHIVE_MEDIA_PUBLIC_URL=https://cliphive-media.example.com
   ```
7. Superadmin → **Cuentas** → Instagram → **Conectar**.

The 60-day token renews itself a week before it expires, as long as the server keeps running.

---

## 13. Privacy policy and terms pages

Google, Meta and TikTok ask for public privacy-policy and terms URLs. The server itself serves them, in Spanish and
English:

- `https://cliphive.example.com/legal/privacy`
- `https://cliphive.example.com/legal/terms`

They name you as the person running the installation, from `.env`:

```
CLIPHIVE_OPERATOR_NAME=Your Name
CLIPHIVE_OPERATOR_EMAIL=you@example.com
```

(After changing them, restart the server: `docker compose up -d`, or `sudo systemctl restart cliphive`.) Without them the pages say "not configured" — the platforms will reject the app — and the server logs a warning at
startup.

Besides the login page itself (`/login`), the error page (`/error`) and static files (`/css/`, `/js/`, `/favicon.ico`),
they're the only superadmin routes without a login. `cliphive.example.com` sits behind Cloudflare Access, so Access
needs an exception **for exactly the `/legal/` path** — nothing changes in cloudflared.

1. Cloudflare Zero Trust → **Access → Applications → Add an application → Self-hosted**:
   - Name: `ClipHive legal pages`
   - Domain: `cliphive.example.com`, path **`legal/*`** — exactly this; a wider path would expose the superadmin.
   - Policy: action **Bypass**, include **Everyone**.

   Cloudflare applies the most specific application to a request, so `/legal/*` is public and the rest of
   `cliphive.example.com` keeps the existing Access login.
2. Check from outside (another network, or a private window not logged into Access):
   ```bash
   curl -sI https://cliphive.example.com/legal/privacy   # 200, content-type: text/html
   curl -sI https://cliphive.example.com/admin           # still a redirect to Cloudflare Access
   ```
3. Google Cloud → Google Auth Platform → **Branding**: application home page and terms of service
   `https://cliphive.example.com/legal/terms`, privacy policy `https://cliphive.example.com/legal/privacy`, and add `example.com`
   under **Authorized domains** (it covers its subdomains). Use the same two URLs in the Meta and TikTok apps.

---

## 14. Email alerts (Resend)

The superadmin emails you when fewer than X clips are left to publish (DESIGN.md §12.3). It sends over plain SMTP;
this deployment uses [Resend](https://resend.com) (free tier: 3,000 emails/month, far more than alerts need).

1. Create a Resend account and go to **Domains → Add domain** → `example.com`.
2. Add the DNS records Resend shows (SPF/MX on a `send` subdomain and a DKIM `TXT`) in **Cloudflare → DNS** — or use
   Resend's *Auto configure* with Cloudflare. Leave the records **DNS only** (grey cloud). Wait until the domain shows
   **Verified**.
3. **API Keys → Create API key**, permission *Sending access*, restricted to `example.com`. Copy it (it's shown once).
4. In `.env`, then restart the server (`docker compose up -d`, or `sudo systemctl restart cliphive` on bare metal):
   ```env
   CLIPHIVE_SMTP_HOST=smtp.resend.com
   CLIPHIVE_SMTP_PORT=587
   CLIPHIVE_SMTP_USER=resend
   CLIPHIVE_SMTP_PASSWORD=re_xxxxxxxx        # the API key
   CLIPHIVE_SMTP_FROM=ClipHive <cliphive@example.com>
   ```
   `CLIPHIVE_ADMIN_PUBLIC_URL` (section 11) also puts a link to the superadmin in the email.
5. Superadmin → **Ajustes → Avisos por email**: set the threshold and your address, **Guardar**, then
   **Enviar email de prueba**.

Before the domain is verified you can test with `CLIPHIVE_SMTP_FROM=onboarding@resend.dev`: Resend only delivers it to
the address of your own Resend account. Every alert sent (or failed, with the reason) is stored in the
`notification` table.

---

## 15. Connect TikTok

Until TikTok audits the app, clips arrive as **drafts** in the TikTok app (DESIGN.md §11.4): you open the
notification and post them yourself. The draft API takes no title or description, so each draft also sends you an
email (section 14) with the text to paste.

1. In [developers.tiktok.com](https://developers.tiktok.com) → **Manage apps** → **Connect an app** (web).
2. App details: name, icon, category, description, and
   - Terms of Service URL: `https://cliphive.example.com/legal/terms`
   - Privacy Policy URL: `https://cliphive.example.com/legal/privacy`
   - Website: `https://cliphive.example.com`
3. **Verify URL properties** → **Domain** → `example.com`. TikTok gives a `tiktok-developers-site-verification=…` string:
   add it in **Cloudflare → DNS** as a `TXT` record named `@`, then press **Verify**. Verifying your domain covers
   `cliphive.example.com` too.
4. Products: **Login Kit** with redirect URI
   ```
   https://cliphive.example.com/admin/accounts/tiktok/callback
   ```
   and **Content Posting API**. Scopes: `user.info.basic`, `user.info.stats`, `video.upload`
   (`video.publish` only once audited).
5. Create a **Sandbox** and add your TikTok account as a **target user**. The sandbox has its **own** client key and
   secret — those go in `.env`, then restart the server (`docker compose up -d`, or `sudo systemctl restart cliphive`
   on bare metal):
   ```env
   CLIPHIVE_TIKTOK_CLIENT_KEY=<sandbox client key>
   CLIPHIVE_TIKTOK_CLIENT_SECRET=<sandbox client secret>
   ```
6. Superadmin → **Cuentas** → TikTok → **Conectar**.

The access token lasts 24 h and renews itself with a 1-year refresh token. At most **5 drafts** can wait unposted in
the TikTok app per 24 h; past that, publications are postponed an hour at a time until you post or discard some.

---

## 16. Backups and keys

Losing the database is an inconvenience, not a disaster: published clips stay on the platforms, and the queue can be
rebuilt by resubmitting the links (DESIGN.md §14.1). What you **can't** recreate is in `.env`:

- **`CLIPHIVE_ENCRYPTION_KEY`** encrypts the OAuth tokens and the 2FA secret in the database. Lose it (or change it)
  and every connected account and the 2FA setup become unreadable: you'd reconnect each platform and set up 2FA again.
- **`CLIPHIVE_URL_SIGNING_KEY`** signs worker and media URLs; changing it only invalidates links in flight.

Keep a copy of `.env` somewhere safe and private (a password manager works) — never in the repository.

Optional database dump, e.g. from a weekly cron job:

```bash
# Docker
docker exec <postgres-container> pg_dump -U cliphive cliphive | gzip > cliphive-$(date +%F).sql.gz
# Bare metal
sudo -u postgres pg_dump cliphive | gzip > cliphive-$(date +%F).sql.gz
```

Restore into an empty database with `gunzip -c file.sql.gz | psql -U cliphive cliphive`, then start the server
(Flyway sees the schema is already there). The data directory (`CLIPHIVE_DATA_DIR`) only holds working files and
clips waiting to be published; it doesn't need a backup.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Server stops at startup with a list of `CLIPHIVE_*` problems | Startup configuration check (section 6) | Fix every item in the list and start again |
| `uname -m` returns `armv7l` | 32-bit OS | Re-flash with 64-bit image |
| `java: command not found`, or the service fails with `/usr/local/bin/java: No such file` | The symlink from section 2 is missing or points to a removed JDK | Re-create it: `sudo ln -sf /opt/jdk-25*/bin/java /usr/local/bin/java`. The systemd unit calls that path directly, so `/etc/environment` or your shell's `PATH` don't matter |
| Can't log in on first boot | `CLIPHIVE_ADMIN_USER` or `CLIPHIVE_ADMIN_PASSWORD` was empty, so no account was created (only a warning in the log) | Set both in `.env` and restart |
| Server won't start: cannot bind the worker port | Tailscale wasn't up yet, so `CLIPHIVE_WORKER_BIND` doesn't exist | `sudo tailscale up`; the server restarts on its own (Docker `restart`, systemd `Restart=on-failure`) |
| `Error creating bean 'flyway'` | `spring-boot-starter-flyway` missing (Boot 4) | Already in `pom.xml`; rebuild |
| Cloudflare Access loop | Access policy too strict | Check Application → Policies |
| `claude -p` hangs | ARM64 login issue | Use `CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token` on another machine |
| Workers can't connect | Tailscale not routing | `sudo tailscale status`; check firewall on port 8082 |
| Email alerts never arrive | SMTP not set, domain unverified or wrong API key | Settings shows a warning if SMTP is unset; the failure reason is in `notification.metadata_json` and the server log |
