# Sidecar Architecture — Local Python ML Daemons

The `sidecar/` part holds seven Python daemons; the five covered here give JClaw local, on-device ML capability the JVM cannot provide: speech recognition, speaker diarization, image generation, speech synthesis, and video generation. They are the one place the "no Python on the server" principle is deliberately relaxed — the platform stays Java-first, and these run as **separate, on-demand, loopback-only processes** rather than as part of the runtime.

Each is launched, health-checked and torn down by a JVM-side lifecycle manager. None is required: with a sidecar absent or its prerequisites missing, the corresponding feature reports an actionable error rather than silently degrading (the JCLAW-614 pattern).

## The five daemons

| Sidecar | Port | Purpose | JVM manager |
|---|---|---|---|
| `sidecar/image` | 9527 | Local image generation (default engine FLUX.2 klein) | `services.imagegen.LocalImageSidecarManager` |
| `sidecar/video` | 9528 | Local video generation (LTX-2.3 / WAN) | `services.videogen.LocalVideoSidecarManager` |
| `sidecar/asr` | 9529 | Speech recognition — the ingest transcript behind every audio attachment | `services.transcription.AsrSidecarManager` |
| `sidecar/diarize` | 9530 | Speaker diarization (+ optional per-turn emotion) | `services.transcription.DiarizeSidecarManager` |
| `sidecar/tts` | 9531 | Speech synthesis for read-aloud / voice mode | `services.tts.TtsSidecarManager` |

All bind `127.0.0.1` only. Audio and image files are passed **by path**, not by upload — the daemon runs on the same host and attachments are already on disk.

## The two-tier uv split

The defining pattern of this part. `serve.py` is a **stdlib-only HTTP supervisor holding no ML dependencies at all** — its `pyproject.toml` declares an empty runtime dependency set. Every request is shelled to a worker script whose heavy dependencies live in its own **PEP 723 inline script environment**, resolved by `uv` on first launch.

```
JVM  ──HTTP/127.0.0.1──►  serve.py            (stdlib only, deps = [])
                              │
                              └── stdin/stdout JSON line protocol
                                      │
                                      ▼
                              worker.py        (PEP 723 env: torch, transformers, …)
                              persistent — model loads once, amortized across calls
```

Why it is built this way:

- **Env isolation.** Workers pin mutually incompatible dependency sets. The MERaLiON ASR worker requires `transformers==4.50.1`; the diarize worker needs `numpy>=2`, which the ASR env pins against. Separate PEP 723 envs mean they never have to agree.
- **Model load paid once.** The worker is persistent and held across requests, so a ~20 s pyannote pipeline load is amortized until the daemon self-evicts on idle.
- **A cheap supervisor.** Because `serve.py` carries no ML deps, starting it is fast and its env never conflicts with anything.

`sidecar/image` and `sidecar/video` are the exceptions: they run their models **in-process** rather than shelling to a worker, so their `pyproject.toml` carries real dependencies.

## Shared lifecycle

`services.LocalSidecarDaemon` is the shared mechanism for every sidecar daemon: spawn `uv run serve.py`, drain stdout/stderr on virtual threads, poll `/health` until ready, and stop with a `destroy()` → `destroyForcibly()` discipline. Only directories, config keys, labels and exception type differ, captured in its `Config`. The closest precedent for the start/drain/graceful-close discipline is `mcp.transport.McpStdioTransport`.

The ASR, diarize and TTS managers add dedicated clients (`AsrSidecarClient`, `DiarizeSidecarClient`, `TtsSidecarClient`), with `services.sidecar.SidecarHttpClient` as the shared HTTP surface and `services.SidecarCapabilityProbe` reporting what the host can actually run.

**Concurrency.** One inference at a time per daemon. Concurrent callers receive `409` and queue on a JVM-wide fair lock, so a second request never starts a second model load.

## Protocols

Every daemon exposes `GET /health`. Beyond that:

### `sidecar/asr` — speech recognition

| Method | Path | Contract |
|---|---|---|
| POST | `/transcribe` | `{audio_path, model, language?}` → `{segments:[{startMs,endMs,text}]}`; `model` selects the engine |
| GET | `/asr/models?ids=a,b` | per-model cached / bytesOnDisk / engine status for Settings |
| POST | `/asr/prefetch` | `{model}` → downloads the host engine's weights ahead of use |
| POST | `/shutdown` | graceful exit (JVM shutdown hook) |

**Two engines, routed on model id** via the `MERALION_HF` map — anything not in it is Whisper.

- **Whisper** (default): mlx-whisper on Apple silicon, faster-whisper (CTranslate2) on CUDA / CPU int8. Emits segment times natively.
- **MERaLiON** (`meralion-3-3b` → `MERaLiON/MERaLiON-3-3B-ASR`): a Southeast-Asia-tuned speech LLM producing a **plain transcript with no timestamps**. Audio is transcoded to 16 kHz mono, then silero-VAD extracts ≤30 s speech regions (the window it was trained on) and only those are transcribed — dropping the silence is what stops the model fabricating text, often in another language, on silent tails. Forced alignment (`align.py`, torchaudio MMS) then maps words back onto the audio and regroups them into pause-delimited segments, recovering the structure Whisper gives for free and discarding hallucinated words that have no acoustic evidence to align to.

### `sidecar/diarize` — speaker diarization

| Method | Path | Contract |
|---|---|---|
| POST | `/diarize` | `{audio_path, num_speakers?, emotions?, emotion_model?}` → `{turns:[{startMs,endMs,speaker,emotion?}]}` |
| GET | `/diarize/models?ids=…` | per-repo download status (diarizer + SER) |
| POST | `/diarize/prefetch` | `{model}` → **detached** download, so `/diarize/models` keeps reporting live progress |
| POST | `/shutdown` | graceful exit |

Produces speaker **turns** only — no transcription. The JVM fuses those turns with the ASR transcript (`DiarizationFusion`) at segment granularity; per-word fusion measured noisier. Audio is transcoded to 16 kHz mono via ffmpeg first, because pyannote expects 16 kHz and JClaw's audio is commonly 8 kHz telephony.

`pyannote/speaker-diarization-community-1` was chosen over a classical VAD + ECAPA + AHC pipeline (DiaRemot) after an 8 kHz-telephony bake-off: pyannote recovered 57 turns / 2 speakers where the classical pipeline collapsed a 110 s call to a single segment. The weights are **gated** — they need a Hugging Face token, shared with Image Generation.

**Per-turn emotion** is opt-in (`emotions=true`) and runs in a separate SER worker (`ser.py`). The model is operator-selectable from a fixed trio, defaulting to `MERaLiON/MERaLiON-SER-v1` (multilingual — English, Chinese, Malay, Tamil, Indonesian; 7 categories plus valence/arousal/dominance). Best-effort: a failure returns turns without labels, and turns under 1 s are skipped as too little signal.

### `sidecar/tts` — speech synthesis

`POST /synthesize`, `GET /health`, `POST /shutdown`. Shells to a persistent `synth.py --worker`; `model` routes the engine. `qwen3-0.6b` (default), `qwen3-0.6b-4bit` and `kokoro` run on mlx-audio (Apple silicon only); **`chatterbox`** is the cross-platform PyTorch option (CUDA / MPS / CPU) and supports zero-shot voice cloning from a reference clip. Chatterbox is a voice-*quality* choice, not a latency win — measured ~3.5–5× slower than the in-JVM sherpa engine.

### `sidecar/image` — image generation

`GET /health`, `GET /capability`, `POST /generate`, `GET /progress`, `POST /pull`. Runs the model in-process (torch + diffusers). `/capability` reports what the host can run so the UI can tier by free VRAM.

### `sidecar/video` — video generation

`GET /health`, `GET /capability`, `POST /jobs` → `202 {job_id}`, `GET /jobs/<id>` → `{state,percent}`, `GET /jobs/<id>/result` → mp4, `POST /pull` → ndjson progress. **Asynchronous** — one model per process (`--model`), one job at a time, gated on *free* VRAM.

**Dual runtime, split by platform** so the MLX and diffusers stacks never share a venv (they pin incompatible `transformers` versions); `pyproject.toml` uses `sys_platform` markers and `serve.py` branches on the same check:

- **Apple Silicon → MLX** (LTX-2.3 distilled): faster and higher quality than diffusers+MPS, and it generates synchronized audio. Offered as a free-VRAM-tiered spectrum — `ltx` int4 (~11 GB), `ltx-q8` (~21 GB), `ltx-bf16` (~40 GB). No torch/diffusers on this platform.
- **Linux / CUDA → official Lightricks `ltx_pipelines`**: one 22B model at three tiers via quantization + offload — `ltx` bf16 (~32 GB), `ltx-fp8` (~16 GB), `ltx-fp8-offload` (~10 GB, streaming weights from system RAM). WAN (`wan-5b`, `wan-14b`) stays on diffusers and is NVIDIA-only.

Live percent on the MLX path comes from wrapping the sampler's tqdm, since the pipeline exposes no callback.

## Prerequisites

- **`uv` on PATH** — the shared prerequisite for all five. It resolves each PEP 723 env on first launch.
- **`ffmpeg`** — for the diarize 16 kHz transcode and the MERaLiON ASR path.
- **A Hugging Face token** — only for gated weights (pyannote diarization). Supplied by the JVM from `transcription.diarization.local.hfToken`, falling back to `imagegen.local.hfToken`. Whisper and MERaLiON ASR weights are ungated.

Weights cache under `data/` (`data/asr-models`, `data/diarize-models`, `data/video-models`) via `HF_HOME`, downloaded on first use or ahead of time from the Settings page.

## Packaging

`uv.lock` is **gitignored for every sidecar** — resolution is per-machine, because the right torch build depends on the host (CUDA vs MPS vs CPU). Only `pyproject.toml`, `serve.py` and the worker scripts are tracked.

The sidecar scripts ship in `jclaw-bundle.zip`, but `uv`, Python and the weights are **not** part of it. The bundle's only runtime dependency is a Java 25 JRE; local ML capability is opt-in on hosts that install `uv` and the weights.

## Licensing note

Most models here are permissively licensed (Whisper MIT, torchaudio MMS BSD-2-Clause), but **MERaLiON models ship under the MERaLiON Public License, not MIT**. Check the model card's commercial terms before a paid-edition ship. See each `sidecar/*/README.md` for per-daemon attribution.
