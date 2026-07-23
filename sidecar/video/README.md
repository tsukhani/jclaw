# jclaw video-generation sidecar (JCLAW-232 WAN / JCLAW-233 LTX)

Async local video generation. One model per process (`--model`), one job at a time, gated on
**free** VRAM. Launched + lifecycle-owned by `services.videogen.LocalVideoSidecarManager`.

    uv run serve.py --model ltx --port 9528 --cache-dir ../../data/video-models --idle-timeout-min 15

Protocol (SV-3 / JCLAW-512): `GET /health`, `GET /capability`, `POST /jobs` -> 202 {job_id},
`GET /jobs/<id>` -> {state,percent}, `GET /jobs/<id>/result` -> mp4, `POST /pull` -> ndjson progress.

Models: `ltx` (plus `ltx-q8`/`ltx-bf16` on Apple Silicon, `ltx-fp8`/`ltx-fp8-offload` on CUDA — the
tier spectrum below), `wan-5b`, `wan-14b`. WAN is NVIDIA-only (SV-2). On Linux+NVIDIA use the CUDA torch
wheel: `UV_TORCH_BACKEND=cu124 uv run serve.py ...`.

The `ltx` engine is **dual-runtime** (JCLAW-233):

- **Apple Silicon** → MLX (`ltx_pipelines_mlx`, LTX-2.3 distilled). Faster than diffusers+MPS, higher
  quality, and it generates synchronized **audio**. Offered as a free-VRAM-tiered spectrum (the adaptive
  picker shows what fits, up to the largest the Mac's unified memory allows):
  `ltx` int4 (~11 GB), `ltx-q8` int8 (~21 GB), `ltx-bf16` (~40 GB). Each pulls only the essential weights
  (`ignore_patterns` skips the bundled dev/old transformers + LoRAs) plus a Gemma text encoder. No
  torch/diffusers on this platform.
- **Linux / CUDA** → LTX-2.3 via the **official Lightricks `ltx_pipelines`** (the CUDA sibling of the MLX
  fork) — one 22B model at three free-VRAM tiers via quantization + offload: `ltx` bf16/none (~32 GB),
  `ltx-fp8` fp8-cast/none (~16 GB), `ltx-fp8-offload` fp8-cast/**CPU offload** (~10 GB; streams weights
  from system RAM, needs ~36 GB free RAM). **Validated end-to-end on an RTX 4090** (the fp8 + CPU-offload
  tier): a valid h264+AAC clip, ~8.3 GB peak, ~62 s for a 25-frame clip. The weight-load build needs ~8 GB
  regardless of offload, so there's no sub-8 GB tier. WAN stays on diffusers (`wan-5b`, `wan-14b`). No mlx.

The two stacks are platform-conditional deps (`pyproject.toml` `sys_platform` markers + `[tool.uv.sources]`
for the MLX workspace packages) so they never share a venv. Live percent on the MLX path comes from
wrapping the sampler's tqdm (the pipeline exposes no callback).
