# worker

Java program that connects to the server over Tailscale and processes `TRANSCRIBE` (whisper.cpp) and `RENDER_CLIP` (ffmpeg) tasks. The same core runs in two modes:

- **Desktop** — JavaFX window + system tray icon (status, progress, log, pause/resume/stop, configuration editor).
- **Headless** (`--headless`) — no UI, for Linux machines without a desktop or services.

The worker has **no access** to social accounts, OAuth tokens, global logs or statistics: it only ever sees its own task's files, and deletes them once the task is handed back.

- **Installing and configuring a worker** (MSI, first run, `worker.yml`, states, logs, troubleshooting): [docs/INSTALL-WORKER.md](../docs/INSTALL-WORKER.md).
- **How it works** (hardware detection, encoders, autotest, fault tolerance): [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md#worker-internals), and in full [docs/DESIGN.md](../docs/DESIGN.md) §8 (worker) and §9 (processing: transcription, render, encoders, fps).
- **Building and running from source**: [docs/DEVELOPMENT.md](../docs/DEVELOPMENT.md).

## Code map

`src/main/java/io/github/raulgl422/cliphive/worker/`:

| Package | Contents |
|---|---|
| `WorkerApplication` | Startup: config, hardware detection, model download, autotest, wiring, UI or headless |
| `config/` | `WorkerProperties` and the `worker.yml` + `CLIPHIVE_WORKER_*` loader |
| `hardware/` | Encoder and whisper backend detection, startup encoder test |
| `model/` | Whisper model download with SHA-256 check |
| `net/` | `ServerConnection`: WebSocket, reconnects, heartbeat watchdog, outbox for results |
| `task/` | Task orchestration and the `TRANSCRIBE` / `RENDER_CLIP` handlers, file transfer |
| `render/` | `Encoder` implementations (AMF, NVENC, VAAPI, software), ASS karaoke generator, fps decision |
| `tool/` | Locating and running ffmpeg/ffprobe/whisper-cli with timeouts and cancellation |
| `test/` | First-run autotest |
| `runtime/` | `WorkerController`, `WorkerState`, start-at-login |
| `ui/` | JavaFX window, tray, configuration editor, first-run setup |
| `logging/` | Log directory and the window's log panel |

## Building whisper.cpp yourself (optional)

The installer bundles a Vulkan `whisper-cli`; CI builds it in [`whisper-build.yml`](../.github/workflows/whisper-build.yml). To use another build (e.g. CUDA, or on Linux), compile it and point `whisperPath` at it:

```bash
git clone https://github.com/ggml-org/whisper.cpp
cd whisper.cpp
cmake -B build -DGGML_VULKAN=ON      # or -DGGML_CUDA=ON, or nothing for CPU only
cmake --build build --config Release -j4
```

The binary ends up at `build/bin/whisper-cli` (Linux) or `build/bin/Release/whisper-cli.exe` (Windows). Per-OS and per-GPU prerequisites (Vulkan SDK, CUDA Toolkit, Visual Studio Build Tools, Mesa drivers) and known Windows build issues are in [docs/DESIGN.md](../docs/DESIGN.md) §17.2.
