# jclaw video-generation sidecar (JCLAW-232 WAN / JCLAW-233 LTX)

Async local video generation. One model per process (`--model`), one job at a time, gated on
**free** VRAM. Launched + lifecycle-owned by `services.videogen.LocalVideoSidecarManager`.

    uv run serve.py --model ltx --port 9528 --cache-dir ../../data/video-models --idle-timeout-min 15

Protocol (SV-3 / JCLAW-512): `GET /health`, `GET /capability`, `POST /jobs` -> 202 {job_id},
`GET /jobs/<id>` -> {state,percent}, `GET /jobs/<id>/result` -> mp4, `POST /pull` -> ndjson progress.

Models: `ltx` (LTX-Video), `wan-5b`, `wan-14b`. WAN is NVIDIA-only (SV-2). On Linux+NVIDIA use the
CUDA torch wheel: `UV_TORCH_BACKEND=cu124 uv run serve.py ...`. LTX on Apple Silicon runs via
diffusers+MPS today (best-effort, slow); the faster MLX backend is a JCLAW-233 follow-up.
