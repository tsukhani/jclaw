# jclaw image sidecar (JCLAW-226)

A long-running localhost HTTP daemon that runs a local diffusion image model —
by default [FLUX.2 klein](https://huggingface.co/black-forest-labs/FLUX.2-klein-4B),
configurable via `imagegen.local.model` — for jclaw's `generate_image` tool. The
jclaw JVM launches it on demand (`LocalImageSidecarManager`) and talks to it over
`127.0.0.1`; you normally never run this by hand. Shape and protocol were chosen
in the **JCLAW-509** spike.

## Why a sidecar

Local image generation needs a Python runtime (diffusers / Flux are Python-first;
there is no JNI binding). A resident daemon holds the model in GPU/unified memory
between calls, so only the first image after idle pays the multi-second model
load — a per-request subprocess would re-pay it every time, and a model server
(torchserve) is too heavy for a single-user Personal Edition.

## Requirements

- **Python 3.10+** and **[uv](https://docs.astral.sh/uv/)** on `PATH`.
  jclaw probes for these (`UvProbe`); if absent, the Settings UI shows a
  banner and cloud image providers remain the working path.
- A GPU helps a lot: Apple Silicon (MPS) or NVIDIA (CUDA). CPU works but is slow.
- Enough memory to hold klein 4B (~13 GB at fp16). On a Mac that is **unified**
  memory shared with the OS + the jclaw JVM, so 24 GB+ is comfortable.

## Protocol

| Method | Path        | Body / Response |
|--------|-------------|-----------------|
| GET    | `/health`   | `{status, device, dtype, model, weights_present, loaded}` |
| GET    | `/capability` | `{kind, gpu, freeVramGb, totalVramGb, minVramGb, runnable, tier, reason}` — host GPU/VRAM verdict for the Settings gate (also one-shot via `--probe`) |
| POST   | `/generate` | `{prompt, width?, height?, steps?, seed?, image?}` (`image` = base64 reference for image-to-image) → `image/png` bytes; `400` for a body that is not JSON, a missing `prompt`, or an `image` that does not decode; `409` if weights absent or a generation is already in progress (one at a time); `500` when the pipeline fails to load or the generation fails |
| GET    | `/progress` | `{percent}` — live 0–100 step progress, `null` when idle (polled for the chat progress bar) |
| POST   | `/pull`     | `application/x-ndjson` progress lines, then `{"status":"done"}` — skips `IGNORE_PATTERNS` (the top-level `flux-2-klein-4b.safetensors` single-file checkpoint and the `*.jpg` examples, ~33% of the repo), since the diffusers pipeline loads only the subfolders |
| CLI    | `--probe`   | the `/capability` JSON on stdout — no server, no model load, no token; `--port` and `--model` are not required |

Device/dtype is picked here (the JVM can't see CUDA/MPS): `mps`→fp16
(with `PYTORCH_ENABLE_MPS_FALLBACK=1`), `cuda`→bf16, `cpu`→fp32.

## Configuration

Keys live in the Config DB (Settings), not `conf/application.conf`. `DefaultConfigJob` seeds
the first four; the rest fall back to the defaults below when absent.

| Key | Default | Read by | Meaning |
|---|---|---|---|
| `imagegen.local.model` | `black-forest-labs/FLUX.2-klein-4B` | `LocalImageSidecarManager.ensureRunning` | HF repo passed as `--model` at spawn; a running sidecar keeps serving what it was launched with until it stops (the health check does not compare models) |
| `imagegen.local.port` | `9527` | `LocalSidecarDaemon.port()` | loopback port; passed as `--port` and used for every call |
| `imagegen.local.idleTimeoutMinutes` | `15` | `LocalSidecarDaemon.spawnNow` | passed as `--idle-timeout-min`; the daemon self-evicts and releases the GPU after that long idle |
| `imagegen.local.hfToken` | blank | `LocalSidecarDaemon.spawn` | exported to the child as `HF_TOKEN` when non-blank — klein is ungated, so a token only lifts rate limits and unlocks gated repos; also the fallback token for `sidecar/diarize` |
| `imagegen.local.generateTimeoutSeconds` | `300` | `LocalImageGenerationClient` | the JVM's per-call deadline on `/generate` |
| `imagegen.local.startupTimeoutSeconds` | `180` | `LocalSidecarDaemon.awaitHealthy` | how long `/health` may go unanswered after spawn before the launch fails |

`LocalSidecarDaemon` also reads `imagegen.local.timeoutSeconds` and exports it as
`SIDECAR_REQUEST_TIMEOUT_SEC`; this sidecar does not read that variable, so the key has no
effect here — `generateTimeoutSeconds` is the deadline that counts.

`serve.py` flags: `--host` (`127.0.0.1`), `--port`, `--model` (HF repo id), `--cache-dir`
(`data/image-models`; becomes `HF_HOME`), `--idle-timeout-min` (`15`), `--no-auth`, `--probe`.
`--port` and `--model` are required unless `--probe` is given.

## Authentication

Every request must carry `X-Sidecar-Token`, matching the `SIDECAR_TOKEN` the JVM derives
from its own install secret and passes in the child's environment. Without that variable
the sidecar refuses to start. A custom header is deliberately not CORS-simple, so a page
the operator visits cannot reach a warm sidecar even though it listens on loopback.

Hand-running: set a token of your own and send it, or pass `--no-auth` (off by default) to
serve unauthenticated.

## Running by hand (debugging)

```bash
cd sidecar/image
SIDECAR_TOKEN=dev uv run serve.py --port 9527 --model black-forest-labs/FLUX.2-klein-4B
# then, from another shell:
curl -H 'X-Sidecar-Token: dev' localhost:9527/health
curl -H 'X-Sidecar-Token: dev' -X POST localhost:9527/pull    # downloads weights (ndjson progress)
curl -H 'X-Sidecar-Token: dev' -X POST localhost:9527/generate -d '{"prompt":"a red bicycle"}' -o out.png
```

Weights download to `--cache-dir` (jclaw passes `data/image-models/`) via `HF_HOME`.

## Platform notes (torch wheels)

`uv run` provisions an isolated venv from `pyproject.toml` on first launch.
torch wheels are platform-specific:

- **macOS / Apple Silicon** — the default PyPI wheel ships CPU + MPS. No extra step.
- **Linux + NVIDIA** — the default PyPI wheel is CPU-only. Select the CUDA build:
  ```bash
  UV_TORCH_BACKEND=cu124 uv run serve.py ...   # match your driver's CUDA version
  ```
- **Linux + AMD (ROCm)** — use the ROCm wheel index. klein-on-ROCm coverage is
  unverified (flagged in the JCLAW-509 spike).
