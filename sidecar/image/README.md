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
| POST   | `/generate` | `{prompt, width?, height?, steps?, seed?}` → `image/png` bytes; `409` if weights absent |
| POST   | `/pull`     | `application/x-ndjson` progress lines, then `{"status":"done"}` |

Device/dtype is picked here (the JVM can't see CUDA/MPS): `mps`→fp16
(with `PYTORCH_ENABLE_MPS_FALLBACK=1`), `cuda`→bf16, `cpu`→fp32.

## Running by hand (debugging)

```bash
cd sidecar/image
uv run serve.py --port 9527 --model black-forest-labs/FLUX.2-klein-4B
# then, from another shell:
curl localhost:9527/health
curl -X POST localhost:9527/pull           # downloads weights (ndjson progress)
curl -X POST localhost:9527/generate -d '{"prompt":"a red bicycle"}' -o out.png
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
