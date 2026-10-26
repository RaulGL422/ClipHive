# Installing a worker

A **worker** is a machine with a GPU (a desktop PC, a laptop) that does the heavy lifting for your ClipHive server: it transcribes audio with whisper.cpp and renders the vertical clips with ffmpeg. You can have several, and they can connect and disconnect whenever they like — a task that was running on a machine that goes away goes back to the queue ([ARCHITECTURE.md](ARCHITECTURE.md#tasks-and-fault-tolerance)).

A worker never sees your social accounts, OAuth tokens, global logs or statistics: it only downloads the files of the task it's working on, and deletes them when it hands the task back.

You need a running server first — see [DEPLOY.md](DEPLOY.md).

- [1. Requirements](#1-requirements)
- [2. Install the MSI](#2-install-the-msi)
- [3. First run](#3-first-run)
- [4. The window and the tray icon](#4-the-window-and-the-tray-icon)
- [5. worker.yml reference](#5-workeryml-reference)
- [6. Headless mode](#6-headless-mode)
- [7. Files and logs](#7-files-and-logs)
- [8. Updating and uninstalling](#8-updating-and-uninstalling)
- [9. Troubleshooting](#9-troubleshooting)

---

## 1. Requirements

- **Windows 10/11 x64.** The only installer published today is the Windows `.msi`. On Linux you can run the worker from source (see [DEVELOPMENT.md](DEVELOPMENT.md)); Linux packages are planned but disabled in CI.
- **GPU drivers** — the installer brings everything else:

  | GPU | Install | Used for |
  |---|---|---|
  | AMD | AMD Software: Adrenalin Edition | Vulkan (whisper.cpp) and AMF (`h264_amf`) |
  | NVIDIA | Current GeForce/Studio driver | Vulkan (whisper.cpp) and NVENC (`h264_nvenc`) |
  | Intel | Current graphics driver | Vulkan (whisper.cpp) |
  | None | — | Works on the CPU (`libopenh264`, small whisper model), only slower |

- **[Tailscale](https://tailscale.com/download)**, signed in to the same tailnet as the server. The worker reaches the server only through its Tailscale address; check with `tailscale ping <server>`.
- Disk space for the whisper model (up to ~1.6 GB for `large-v3-turbo`) plus a few hundred MB per task in progress.

The MSI bundles a trimmed Java 25 runtime, an LGPL build of ffmpeg/ffprobe and a Vulkan build of whisper-cli. Their licences and exact sources are listed in `app\licenses\THIRD-PARTY-NOTICES.txt` inside the install folder (by default `C:\Program Files\ClipHive Worker\app\licenses\`) (source in the repo: [`worker/packaging/licenses/`](../worker/packaging/licenses/THIRD-PARTY-NOTICES.txt)).

## 2. Install the MSI

1. Open the repository's **Releases** page on GitHub and download the latest `ClipHive Worker-<version>.msi` from a `worker-v*` release.
2. Run it. You can choose the install folder (default `C:\Program Files\ClipHive Worker`). It adds a desktop shortcut and a *ClipHive* group in the Start menu.
3. Windows SmartScreen may warn that the publisher is unknown: the MSI isn't code-signed. Check you downloaded it from the project's own Releases page, then choose *More info → Run anyway*.

## 3. First run

1. In the superadmin, open **Workers**, type a name for this machine under **Registrar worker** (e.g. "Desktop PC") and press **Registrar**. The worker's **token is shown only once**: copy it now. Only its hash is stored, so if you lose it, register the worker again.
2. Start **ClipHive Worker**. With no token configured, the configuration window opens:
   - **URL del servidor** — `ws://<server-tailscale-ip>:8082/ws/worker` (the IP from `tailscale ip -4` on the server, and `CLIPHIVE_WORKER_PORT` if you changed it). The worker accepts `wss://` too, but the server's worker listener speaks plain HTTP/WebSocket: `wss://` only works if you put a TLS proxy in front of it. Over Tailscale the traffic is already encrypted, so `ws://` is the normal choice.
   - **Token del worker** — the token from step 1.
   - The rest can stay as it is; see the [reference](#5-workeryml-reference).
3. Press **Guardar**. The worker restarts by itself. The first start takes several minutes **before the main window opens**, and nothing is shown meanwhile: it detects your hardware, **downloads the whisper model** (SHA-256 checked) and runs a **self-test** (transcribes and encodes a few seconds with the same encoder setup the renders use), all before the window appears. If something fails at that point, a dialog shows the error and the log folder. Self-test outcomes:
   - With **Encoder de vídeo** on `auto` and a GPU encoder that fails, the worker tests the software encoder; if that works, it switches to it (`forcedEncoder: software`) and says so in the log.
   - An encoder you **chose yourself** that fails is kept, but the worker shows *No puede aceptar tareas* with the reason until you fix the driver or choose another encoder.
   - If the software encoder fails too, the worker shows *No puede aceptar tareas*.
   - A self-test that didn't pass runs again on the next start.
4. The window shows **En espera de tareas** once connected. In the superadmin, the machine appears under **Workers → Conectados**.

From then on the worker starts with your Windows session, minimised to the tray (see `startWithSystem`).

## 4. The window and the tray icon

The window shows the state, the current task with its phase and progress, and the recent log. Buttons: **Pausar / Reanudar**, **Parar** and **Configuración**. The tray icon menu has **Abrir ventana**, **Pausar / Reanudar**, **Parar worker** and **Salir**; its tooltip shows the state and any problem. Closing the window hides it to the tray (or minimises it, if the tray icon couldn't be installed).

| State (window / tray) | Meaning | What to do |
|---|---|---|
| *Iniciando…* | Loading the config, detecting hardware, downloading the model, self-test | Wait. The first start can take minutes |
| *Conectando con el servidor…* / *Conectando…* | Trying to connect; network errors and a server that's down are retried by themselves (2 s to 60 s between attempts) | Check Tailscale is up on both machines and the server is running |
| *El servidor rechaza la conexión* / *Rechazado por el servidor* | The server refused this worker, or the URL is wrong. Retrying won't fix it by itself; the message says what to change | See [Rejected](#rejected) below. The worker keeps retrying every minute, so fixing the configuration is enough |
| *No puede aceptar tareas* / *No puede trabajar* | Connected, but ffmpeg or whisper-cli is missing, or the self-test failed. It advertises 0 parallel tasks, so it gets no work and no job wastes its attempts | See [Cannot take tasks](#cannot-take-tasks) below |
| *En espera de tareas* / *En espera* | Connected and idle | — |
| *Procesando* | Working on one or more tasks | — |
| *Pausado (sin nuevas tareas)* / *Pausado* | Takes no new tasks; the current one finishes. The pause survives a reconnect. Known limitation: the server still offers tasks to a paused worker, which hands each one straight back; nothing is lost, but if this is your only worker, the work just waits | **Reanudar** |
| *Parando…*, *Detenido* / *Parado* | **Parar** cancels the running tasks and closes the session; the server puts them back in the queue at once, without spending an attempt | Close the worker, or start it again |

**Salir** also stops cleanly: running tasks are cancelled (any ffmpeg or whisper-cli still running is killed) and go back to the queue.

## 5. worker.yml reference

The configuration lives in `worker.yml` in the worker's data directory — `%LOCALAPPDATA%\ClipHive\worker\worker.yml` on Windows, `~/.local/share/cliphive/worker/worker.yml` on Linux (or `$XDG_DATA_HOME/cliphive/worker`). The **Configuración** window reads and writes the same file; it only applies changes once they're saved. **Every setting except the encoder needs a restart of the worker** to take effect (URL, token, parallel tasks, whisper model, tool paths…); `forcedEncoder` is read on every render, so it applies to the next one.

| Key | Default | Environment variable | Meaning |
|---|---|---|---|
| `serverUrl` | `ws://localhost:8082/ws/worker` | `CLIPHIVE_WORKER_SERVER_URL` | The server's worker WebSocket: `ws://<tailscale-ip>:8082/ws/worker` |
| `workerToken` | empty | `CLIPHIVE_WORKER_TOKEN` | Token from *Workers → Registrar*. Never commit it anywhere |
| `dataDir` | OS data directory (above) | `CLIPHIVE_WORKER_DATA_DIR` | Where task working files and models go. `worker.yml` and the logs always stay in the default directory |
| `maxConcurrentTasks` | `1` | `CLIPHIVE_WORKER_MAX_CONCURRENT_TASKS` | Tasks in parallel (whole number ≥ 1). One is right for most machines: a render already uses the GPU fully |
| `whisperModel` | `auto` | `CLIPHIVE_WORKER_WHISPER_MODEL` | `auto`, `base`, `small`, `medium` or `large-v3-turbo`. `auto` picks `large-v3-turbo` when the whisper-cli build is Vulkan or CUDA (the bundled one is Vulkan) and `base` when it's a CPU build |
| `forcedEncoder` | `auto` | `CLIPHIVE_WORKER_FORCED_ENCODER` | `auto` (best encoder that passed the startup test), `amf`, `nvenc`, `vaapi` or `software`. A forced GPU encoder that isn't available at all is ignored with a warning in the log; one that's available but fails the self-test leaves the worker unable to take tasks (see [First run](#3-first-run)). The self-test sets it to `software` when the GPU fails while on `auto` and software works |
| `startWithSystem` | `true` | — | Start minimised to the tray when you log in (per user, no admin rights: a `ClipHive Worker` value under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`; an XDG autostart file on Linux). Only for the installed app, not for `java -jar` or headless |
| `ffmpegPath` | empty | `CLIPHIVE_WORKER_FFMPEG_PATH` | Use another ffmpeg instead of the bundled one. A path that doesn't exist is reported at startup |
| `ffprobePath` | empty | `CLIPHIVE_WORKER_FFPROBE_PATH` | Same for ffprobe |
| `whisperPath` | empty | `CLIPHIVE_WORKER_WHISPER_PATH` | Same for whisper-cli (e.g. a CUDA build) |
| `onlyWhenPluggedIn` | `false` | — | **Not enforced yet.** Read from the file but has no effect, so the window doesn't offer it |
| `workSchedule` | empty | `CLIPHIVE_WORKER_WORK_SCHEDULE` | **Not enforced yet.** Same as above |

Environment variables win over the file — handy for headless or service installs. Tools are looked up in this order: the configured path, the installer's `bin\` folder next to the jar, then the `PATH`.

Validation: a file that can't be read, isn't valid YAML or has an impossible value (`maxConcurrentTasks: abc` or `0`) **stops the start** with a message naming the file and the key — the worker never falls back to defaults, which would look like a first run and overwrite your file. A blank key (`ffmpegPath:`) keeps the default; an unknown key (a typo) is warned about in the log. The file is saved through a temporary file, so a power cut can't leave it half-written. A commented example: [`worker/src/main/resources/worker.yml.example`](../worker/src/main/resources/worker.yml.example).

## 6. Headless mode

`--headless` runs the same worker without a window, for Linux machines without a desktop or as a service. It needs the token in `worker.yml` or `CLIPHIVE_WORKER_TOKEN` (there's no setup window).

```bash
CLIPHIVE_WORKER_SERVER_URL=ws://100.x.y.z:8082/ws/worker \
CLIPHIVE_WORKER_TOKEN=<token> \
java -jar worker-<version>.jar --headless
```

Ctrl+C or `SIGTERM` is a clean stop: running tasks are cancelled and the session closed with `GOODBYE`, so the server puts them back in the queue without spending an attempt. Exit codes: `1` when it can't start (no token, broken configuration), `2` when it can't take tasks (missing ffmpeg/whisper-cli or a failed self-test), so a service manager shows the failure instead of a worker that silently does nothing. Under systemd, `Restart=on-failure` plus `journalctl` is enough; the worker itself already retries lost connections.

## 7. Files and logs

Everything lives in the data directory (`%LOCALAPPDATA%\ClipHive\worker` on Windows):

| Path | Content |
|---|---|
| `worker.yml` | Configuration |
| `logs\worker.log` | Log (INFO and above), rotated daily and at 10 MB, 7 days and 50 MB at most. Attach the relevant part when reporting a problem — it never contains the token |
| `models\ggml-<model>.bin` | Downloaded whisper models |
| `tasks\<taskId>-<attempt>\` | Working folder of a running task, deleted when it's delivered |
| `.autotest_done` | Marks that the self-test passed. Delete it to force a new self-test |

If the worker fails before its window exists, it shows a dialog with the error and the log folder instead of closing silently.

## 8. Updating and uninstalling

- **Update**: install the newer MSI over the old one; it upgrades in place and keeps your data directory. If the server says the protocol version is incompatible, updating the worker is the fix.
- **Uninstall**: first untick *Arrancar al iniciar sesión* in **Configuración** (uninstalling doesn't remove the login entry; an orphan one is harmless and ignored by Windows), then *Settings → Apps → ClipHive Worker → Uninstall*. Delete `%LOCALAPPDATA%\ClipHive\worker` to remove the configuration, models and logs. In the superadmin, **Workers → Revocar** invalidates its token.

## 9. Troubleshooting

### Rejected

| Message | Cause | Fix |
|---|---|---|
| *El servidor no reconoce el token…* | The token doesn't match any registered worker (typo, extra spaces, a token from another server) | Copy the token again from the registration; if lost, register the worker again and paste the new token in **Configuración** |
| *El token de este worker está revocado…* | Revoked in **Workers** | Register the worker again and use the new token |
| *Esta versión del worker no es compatible con el servidor…* | Protocol major version mismatch | Install the latest MSI (or update the server, if the worker is newer) |
| *La URL del servidor no es válida…* | `serverUrl` isn't a `ws://` / `wss://` URL | Use `ws://<tailscale-ip>:8082/ws/worker` |

### Cannot take tasks

The reason is shown next to the state and in `worker.log`:

- **"no se encuentra ffmpeg" / "no se encuentra whisper-cli"** — a configured path is wrong, or the bundled `bin\` folder is missing. Clear `ffmpegPath`/`whisperPath` to use the bundled tools, or reinstall.
- **"la prueba de arranque ha fallado"** — the self-test failed: the encoder you forced, or the software encoder too. Read the lines above it in the log. If you forced an encoder, set **Encoder de vídeo** back to `auto` (or `software`). The self-test is repeated on the next start, so close and reopen the worker after fixing the cause.

### Other problems

| Symptom | What to check |
|---|---|
| Stuck on *Conectando…* | `tailscale status` on both machines; the server's `CLIPHIVE_WORKER_BIND` is its Tailscale IP; the port (8082 by default) isn't blocked by a firewall |
| Renders on the CPU although there's a GPU | The self-test fell back to `software` (e.g. old drivers). Update the GPU driver, set **Encoder de vídeo** to `auto` and delete `.autotest_done` |
| The first start takes very long | The model download (up to ~1.6 GB). A download that receives nothing for 60 s is aborted with an error; an already-downloaded model is used if there is one |
| Tasks fail in the superadmin with an ffmpeg error | The error contains the last lines of ffmpeg's output; the full output was in the task folder. Try `forcedEncoder: software` to rule out the GPU driver |
| The window doesn't appear at login | It starts minimised: look for the tray icon (it may be in the hidden icons) |
