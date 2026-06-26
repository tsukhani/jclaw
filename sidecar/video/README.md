# jclaw video-generation sidecar (JCLAW-232 WAN / JCLAW-233 LTX)

Async local video generation. One model per process (`--model`), one job at a time, gated on
**free** VRAM. Launched + lifecycle-owned by `services.videogen.LocalVideoSidecarManager`.

    uv run serve.py --model ltx --port 9528 --cache-dir ../../data/video-models --idle-timeout-min 15

Protocol (SV-3 / JCLAW-512): `GET /health`, `GET /capability`, `POST /jobs` -> 202 {job_id},
`GET /jobs/<id>` -> {state,percent}, `GET /jobs/<id>/result` -> mp4, `POST /pull` -> ndjson progress.

Models: `ltx`, `wan-5b`, `wan-14b`. WAN is NVIDIA-only (SV-2). On Linux+NVIDIA use the CUDA torch
wheel: `UV_TORCH_BACKEND=cu124 uv run serve.py ...`.

The `ltx` engine is **dual-runtime** (JCLAW-233):

- **Apple Silicon** → MLX (`ltx_pipelines_mlx`, LTX-2.3 int4 distilled). Faster than diffusers+MPS,
  higher quality, and it generates synchronized **audio**. Pulls only the int4-essential weights
  (`ignore_patterns` skips the bundled dev/old transformers + LoRAs, ~36 GB) plus a Gemma text
  encoder → ~28 GB. No torch/diffusers on this platform.
- **Linux / CUDA** → diffusers (`Lightricks/LTX-Video`). No mlx on this platform.

The two stacks are platform-conditional deps (`pyproject.toml` `sys_platform` markers + `[tool.uv.sources]`
for the MLX workspace packages) so they never share a venv. Live percent on the MLX path comes from
wrapping the sampler's tqdm (the pipeline exposes no callback).
