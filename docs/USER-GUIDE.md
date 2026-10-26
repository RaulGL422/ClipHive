# User guide: the superadmin

The superadmin is ClipHive's web app: it's where you send links, watch the pipeline, connect your accounts and control the publications. It works on a phone too. This guide walks through it screen by screen.

**The interface is in Spanish.** Labels are quoted here as they appear on screen, *in italics*, with an English explanation. For how the pieces fit together, see [ARCHITECTURE.md](ARCHITECTURE.md); for installing the server, [DEPLOY.md](DEPLOY.md).

- [Logging in](#logging-in)
- [Dashboard](#dashboard)
- [Jobs: submitting a link](#jobs-submitting-a-link)
- [A job's page](#a-jobs-page)
- [Review mode](#review-mode)
- [Manual clip selection](#manual-clip-selection)
- [Accounts](#accounts)
- [Publications](#publications)
- [Statistics](#statistics)
- [Workers](#workers)
- [Logs](#logs)
- [Settings](#settings)
- [Email alerts](#email-alerts)

---

## Logging in

Open your superadmin URL (e.g. `https://cliphive.example.com`). Cloudflare Access asks for your identity first; then ClipHive's own login: *Usuario* (user) and *Contraseña* (password) → *Entrar*. The account is the one from `CLIPHIVE_ADMIN_USER`/`CLIPHIVE_ADMIN_PASSWORD`, created on the first boot. With 2FA enabled, a second screen asks for the 6-digit code from your authenticator app (*Verificar*).

Five wrong passwords block logins from your IP for 15 minutes (*Demasiados intentos fallidos. Espera 15 minutos.*); five wrong codes block the 2FA step of the account for 15 minutes.

**Right after the first login**, change the password and enable 2FA in [Settings](#settings).

The side menu has: *Dashboard*, *Trabajos* (jobs), *Workers*, *Publicaciones* (publications), *Estadísticas* (statistics), *Cuentas* (accounts), *Logs*, *Ajustes* (settings) and *Cerrar sesión* (log out).

## Dashboard

Totals (*Trabajos*, *Clips*, *Workers activos* — connected workers), a quick *Nuevo trabajo* form, the latest jobs and the latest log lines. It refreshes itself every few seconds.

## Jobs: submitting a link

*Trabajos* has the *Nuevo trabajo* (new job) form and the list of all jobs.

1. Paste the link in *URL del vídeo*: YouTube, Twitch, Kick or any site [yt-dlp](https://github.com/yt-dlp/yt-dlp) supports. Only `http(s)` links are accepted.
2. Choose the options (they start at the defaults from [Settings](#settings)):

   | Field | Options | Meaning |
   |---|---|---|
   | *Idioma* | *Auto*, *ES*, *EN* | Language of the video: transcription, subtitles and the titles Claude writes. *Auto* doesn't detect anything: it means the default language from [Settings](#settings) (Spanish or English) |
   | *FPS* | *Auto*, *30*, *60* | Frame rate of the clips. *Auto* currently always renders at 30 fps (the automatic 60 fps choice for gameplay isn't implemented yet): pick *60* for 60 fps gameplay |
   | *Audio* | *Original*, *Doblaje español*, *Doblaje inglés* | With a dub selected, if the video has an audio track in that language (the creator's dub or YouTube's automatic one) it's used, and the job's language becomes that one; if not, the original audio |
   | *Clips* | *Automático*, *Manual* | *Automático*: Claude chooses the clips. *Manual*: you mark them yourself watching the video ([below](#manual-clip-selection)) |
   | *Revisión* | checkbox | Review mode: the clips wait for your approval before rendering ([below](#review-mode)). It starts ticked or not according to the default in Settings; unticking it really turns review off for this job. Only on this page; the dashboard form uses the default |

3. Press *Enviar*. The job appears in the list and moves through its states on its own: `VALIDATING` → `DOWNLOADING` → `TRANSCRIBING` → `ANALYZING` → `RENDERING` → `RENDERED` → `SCHEDULED` → `PUBLISHING` → `COMPLETED`.

A link that can't be used ends in `REJECTED` with the reason on its page: private, geo-blocked, needs a login, a live stream still in progress, DRM, unsupported site, or longer than the maximum duration (2 hours by default).

Transcription and rendering need **at least one worker connected**; without one the job waits in `TRANSCRIBING` or `RENDERING`. The *Eliminar* button in the list deletes a job (see below).

## A job's page

Click a job's id. The page refreshes itself every few seconds and shows:

- **The job**: state, title, URL, source platform, language, fps, audio (including whether the dub was found: *Original (no había doblaje en)*…), duration, resolution, review mode, creation date and, if it was rejected or failed, *Motivo* (the reason). When the job is `RENDERED` and waiting for the planner, a note says what it's waiting for.
- **Buttons**, depending on the state:
  - *Revisar cortes* — open the review (only in `AWAITING_REVIEW`).
  - *Regenerar textos* — ask Claude again for the title, description and hashtags of the clips that haven't been uploaded yet. The on-screen title only changes on clips not rendered yet.
  - *Reintentar tareas fallidas (n)* — put failed tasks back in the queue.
  - *Volver a lanzar* — for a finished job that didn't complete (`FAILED`, `CANCELLED`, `REJECTED`): create a new job with the same link and options.
  - *Cancelar* — stop the job: pending and running tasks are cancelled (the worker is told to stop) and unfinished clips cancelled.
  - *Eliminar* — delete the job from the database (clips, tasks, publications and their statistics, transcript, analysis; logs are kept) and its folder from disk. A running job is cancelled first; it isn't allowed while one of its clips is being uploaded. **Whatever was already published stays on the platforms.**
- **Clips**: number, state, section and part (*parte 2/3*), title, on-screen title (*En pantalla*), fps, duration, and *Ver* / *Descargar* while the file exists. If Claude couldn't analyse the video, a warning says the emergency cut (every ~60 s at pauses) was used — delete and relaunch to retry. *Ajustes del servidor al plan de Claude* lists what the server changed in Claude's plan (merged short clips, split long ones, gaps filled…).
- **Publications** of its clips: platform, time (Madrid), state, error if any, *Abrir* (link), and the same confirmation buttons as in [Publications](#publications). *Reintentar publicaciones fallidas* retries all failed ones.
- **Tasks**: type (`TRANSCRIBE` / `RENDER_CLIP`), state, last progress and when it arrived, attempt, failures and the worker running it.

## Review mode

With *Revisión* on, after the analysis the job stops in `AWAITING_REVIEW`. *Revisar cortes* shows:

- the content type Claude detected and how many clips it proposes;
- *Segmentos eliminados por Claude / SponsorBlock*: what was cut out as filler, with its source and category;
- each proposed clip with its time range and duration, and editable *Título* (≤ 100 characters) and *Descripción* (≤ 2,000). Leave them blank and Claude writes them.

The cuts themselves can't be edited here yet: you approve them as they are (or delete the job and use manual mode). *Aprobar y continuar renderizado* approves the cuts and starts rendering. Review mode is a good idea for your first videos; once you trust the cuts, turn it off by default.

## Manual clip selection

With *Clips: Manual*, the job page shows *Clips elegidos a mano*:

1. When the download finishes, the source video plays right there (reload the page if it isn't there yet). Meanwhile the worker transcribes the whole video, which the subtitles need.
2. Play the video, press *Marcar inicio aquí* and *Marcar fin aquí* (or type the seconds in *Inicio (s)* / *Fin (s)*) and *Añadir clip*. The page shows the allowed duration per clip. Ranges are saved as you add them; *Ver* plays one, *Quitar* removes it.
3. *Confirmar cortes (n)* — **after confirming, the cuts can't be changed.** If the transcription finished first, the job waits for you in `AWAITING_SELECTION`.

Each range is snapped to the nearest word boundary and becomes one clip; there's no content analysis or review. Claude still writes the titles and descriptions.

## Accounts

*Cuentas* lists TikTok, Instagram and YouTube with the connected account, when it was connected and when its token expires (*se renueva sola* — it renews itself).

- *Conectar* starts the platform's OAuth login. If it says *Faltan las credenciales de la app en el .env*, the server doesn't have that platform's app keys yet ([DEPLOY.md](DEPLOY.md) sections 11, 12 and 15).
- *Desconectar* removes the account.
- *URLs de redirección OAuth* shows the exact redirect URI to register in each platform's developer app. If it says *define CLIPHIVE_ADMIN_PUBLIC_URL en el .env*, set that variable first.

Every rendered clip is published on **every connected account**, with the same title and description. Until the apps pass each platform's audit, YouTube uploads are **private** and TikTok uploads are **drafts**: making them public is up to you (see below). A YouTube account connected before the playlist feature needs to be reconnected once for playlists to work.

## Publications

*Publicaciones* is where you follow and steer what goes out. From top to bottom:

**Connected accounts**, or *Conectar una cuenta* if there are none.

**Falta publicar (n)** — uploads that need you. TikTok drafts (*EN BORRADORES · FALTA PUBLICAR*) and private YouTube uploads (*SUBIDO · FALTA PUBLICAR*) aren't public until you finish them in the app; ClipHive can't tell whether you did. For each one:

- *Abrir* — the publication's link, when known.
- *Descargar* — the clip, to upload it by hand if the draft got lost.
- *Reenviar borrador* (TikTok only) — send the same clip to your TikTok inbox again as a new draft, right now. It counts towards TikTok's pending-draft limit.
- *Ya está publicado* — confirm it's public; you can paste the link (optional). On TikTok, that link becomes the publication's link.

While a confirmation is pending the clip's file is kept and the job doesn't complete (the file is still deleted after the retention days in Settings). Instagram publishes directly and never asks.

**Plan de hoy** (today's plan, only in *Claude* publish mode): who made it (Claude or the default slots), how many publications it scheduled, when and why it was made, Claude's explanation and *Ajustes del servidor* (what the server corrected). The plan is made at 07:00 Madrid time (or as soon as the server is up after that) and extended when 3 new clips are ready. *Replanificar* adds the clips that fit into today's plan now; **what's already scheduled is never moved**.

**Programadas y en curso** — scheduled and in-progress publications with time (Madrid), platform, title, job and state. States you'll see: `SCHEDULED`, `UPLOADING`, `PROCESSING`, *APLAZADA* (postponed by a platform limit, with the reason: it retries every hour without spending an attempt), *ESPERA A LA ANTERIOR* (waits for the previous part of the same video on that platform), and retry notes (*intento 2: …*). For a scheduled one:

- *Publicar ahora* — publish now. Like *Cambiar hora*, it moves the clip **on all its platforms** that haven't started uploading, so a clip stays in step everywhere.
- *Cambiar hora* — pick a new date and time (Madrid), *Guardar*.
- *Descargar* — the clip.
- *Ya lo subí a mano* — you uploaded it yourself: optionally paste the link and *Marcar*. It becomes *SUBIDA A MANO* and ClipHive stops trying. Use this when a platform refuses uploads because of its limit (you get an email with the text and a download link) — **if you upload it by hand, mark it**, or ClipHive would upload it again once the platform accepts it. With a YouTube link, ClipHive also collects its statistics.
- *Cancelar* — cancel this publication only.

**Últimas terminadas** — recently finished publications. A `FAILED` one shows its error and offers *Reintentar*, *Descargar* and *Ya lo subí a mano*.

## Statistics

*Estadísticas* has one tab per connected platform: subscribers or followers with their 7-day change, channel views, clips published, average views per clip; the subscriber curve for the last 90 days; the clips with most views; average views by publication hour (Madrid); and a sortable table of every published clip (views, likes, comments, % watched, subscribers gained, publication time, average view duration, minutes watched, *Abrir*).

What each platform provides differs: YouTube gives per-clip metrics (analytics arrive 1–3 days late); Instagram gives per-Reel insights; TikTok only account totals while it publishes drafts. Private YouTube videos barely move until they're public.

## Workers

*Workers* is where machines are registered and watched:

- **Registrar worker** — type a name (*Nombre del worker*, e.g. "Laptop") and *Registrar*. The token appears **once** in a yellow box: copy it into the worker's setup window ([INSTALL-WORKER.md](INSTALL-WORKER.md#3-first-run)). If you lose it, register the worker again.
- **Conectados (n)** — connected workers with their active task, its state and progress, the time since the last progress (in red, *sin avances desde hace…*, when it's past the stuck-task timeout) and the last heartbeat.
- **Todos los workers** — every registered worker, *Activo* or *Revocado*. *Revocar* invalidates its token and closes its session; to use that machine again, register it again.

## Logs

*Logs* shows the events the server records for you, live (the status at the top shows the live connection; *Auto-scroll: ON/OFF* toggles scrolling). It isn't the full technical log (that's `journalctl` or `docker compose logs`). Filter by *Nivel* (level: INFO, WARN, ERROR), *Componente* and *Job ID* (the job's UUID), *Filtrar*; *Limpiar* clears the filters. There's no filter by worker. The components have Spanish names: `trabajos` (jobs), `herramientas` (tools, e.g. the yt-dlp update), `publicaciones` (publications), `tareas` (tasks), `limpieza` (cleanup), `cuentas` (accounts), `workers`, `seguridad` (security) and `transcripción` (transcription). Logs are kept for the days set in Settings (30 by default).

## Settings

*Ajustes*. Everything here is stored in the database and applies without restarting. Press *Guardar ajustes* at the bottom to save the form.

| Card | Settings |
|---|---|
| *Valores por defecto de nuevos trabajos* | Default language, fps and review mode; *Corregir transcripción con Claude* (Claude fixes misrecognised words without touching the timing; on by default) |
| *Clips* | *Cobertura del vídeo*: *Completa* (every part of the content becomes a clip; long sections become a series of parts) or *Destacados* (only the best sections, for long streams). Only filler is ever removed |
| *Ficheros* | Space used by jobs and free disk space; *Días con publicaciones fallidas* (how long a failed publication keeps its file for a retry, 7 by default — also how long an unconfirmed draft or private upload keeps it); *Días que se guardan los logs*; *Limpiar ahora* runs the cleanup at once |
| *Subtítulos* | *Altura desde abajo (%)* (25 by default; below ~20 % the apps' buttons cover them); *Título en pantalla* on/off. Applies to clips planned from now on |
| *Límites* | *Duración máx. vídeo fuente (s)* (7,200 = 2 h by default); *Tarea atascada sin progreso (min)* (30 by default) |
| *Publicación* | *Modo de publicación*: *Inmediato (pruebas)* (the default on a new installation: each clip goes out as soon as it's rendered) or *Claude (planificador)* (the daily plan — switch to it once publishing works); *Separación mínima (min)* between two publications on the same platform (90); *Franjas si Claude falla* (fallback times, `13:00,20:00`); *Clips al día (normal)* (3) and *(máximo)* (6); *Acelerar con cola de más de (días)* (5); *YouTube: lista por vídeo* (one public playlist per source video); *YouTube: app auditada* — turn it on **only** after Google approves the API audit, then clips are uploaded public; *Público al que va dirigido* (the audience Claude picks times for) |
| *Avisos por email* | See [below](#email-alerts) |
| *Seguridad — 2FA* | *Activar 2FA*: scan the QR code with any TOTP app (Google Authenticator, Authy…) or type the key, then confirm with a 6-digit code. *Gestionar 2FA* → *Desactivar 2FA* with a valid code. Needs `CLIPHIVE_ENCRYPTION_KEY` |
| *Seguridad — Contraseña* | Current password, the new one twice (at least 12 characters and different from the current one) and the 2FA code if enabled → *Cambiar contraseña*. Five failed attempts block password changes for 15 minutes. Changing `CLIPHIVE_ADMIN_PASSWORD` in `.env` after the first boot does nothing — use this form |

## Email alerts

Configured in *Ajustes → Avisos por email*. The server must have SMTP set in `.env` (`CLIPHIVE_SMTP_*`, see [DEPLOY.md §14](DEPLOY.md#14-email-alerts-resend)). If `CLIPHIVE_SMTP_HOST` or `CLIPHIVE_SMTP_FROM` is empty, a warning says so, the test button is disabled and nothing is sent. The warning doesn't check the user or password: if those are wrong, sends fail and the reason is stored (see the *Email alerts never arrive* row in [DEPLOY.md](DEPLOY.md#troubleshooting)).

- *Umbral cola baja (clips)* — when the clips still to be published fall below this number, you get one email (with an estimate of how many days of content are left) telling you to send more links. It's sent once when crossing the threshold and re-armed when the queue goes back above it. 5 by default; 0 disables it.
- *Email destinatario* — who receives every alert. Empty disables all of them.
- *Recordatorio cada N horas* — repeat the low-queue email while it stays low (0 = off).
- *Enviar email de prueba* — check the SMTP settings.

The same recipient also gets, regardless of the threshold: a **draft-ready** email for every TikTok draft (title and description to paste, since TikTok's draft API doesn't take them), and a **manual-upload** email the first time a platform refuses a publication because of its limit (platform, reason, text to paste, a link to download the clip and a link to *Publicaciones*). Checks run every 5 minutes; a failed send is retried an hour later at the earliest.
