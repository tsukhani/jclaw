# jclaw video-generation sidecar (JCLAW-232 WAN / JCLAW-233 LTX)

Async local video generation. One model per process (`--model`), one job at a time, gated on
**free** VRAM. Launched + lifecycle-owned by `services.videogen.LocalVideoSidecarManager`.

    SIDECAR_TOKEN=dev uv run serve.py --model ltx --port 9528 --cache-dir ../../data/video-models --idle-timeout-min 15

Every request must carry `X-Sidecar-Token`, matching the `SIDECAR_TOKEN` the JVM derives from its
own install secret and passes in the child's environment; without that variable the sidecar refuses
to start.
A custom header is deliberately not CORS-simple, so a page the operator visits cannot reach a warm
sidecar even though it listens on loopback. `--no-auth` (off by default) serves unauthenticated.

Protocol (SV-3 / JCLAW-512): `GET /health`, `GET /capability` (the adaptive-picker payload plus `activeModel`,
the engine this process serves), `POST /jobs {prompt, num_frames?, steps?, fps?, width?, height?}` -> 202 {job_id}
(defaults `num_frames=49`, `steps=30`, `fps=24` clamped to 1–60) | 409 {busy} while a job is running |
400 {insufficient_vram} when free VRAM is under the engine's floor, `GET /jobs/<id>` -> {state,percent} |
404 {unknown_job}, `GET /jobs/<id>/result` -> mp4 | 409 {not_ready}, `POST /pull` -> ndjson progress.
`--probe` prints the `/capability` JSON and exits — no server, no model load, no token. This is the only sidecar
with **no `POST /shutdown`**: `LocalSidecarDaemon.evict()`, the JVM's one handle on a sidecar it did not spawn,
gets `404 {not_found}` here, so an orphan stays up until its idle timeout.

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
- **Linux / Windows (CUDA)** → LTX-2.3 via the **official Lightricks `ltx_pipelines`** (the CUDA sibling of the MLX
  fork) — one 22B model at three free-VRAM tiers via quantization + offload: `ltx` bf16/none (~32 GB),
  `ltx-fp8` fp8-cast/none (~16 GB), `ltx-fp8-offload` fp8-cast/**CPU offload** (~10 GB; streams weights
  from system RAM, needs ~36 GB free RAM). **Validated end-to-end on an RTX 4090** (the fp8 + CPU-offload
  tier): a valid h264+AAC clip, ~8.3 GB peak, ~62 s for a 25-frame clip. The weight-load build needs ~8 GB
  regardless of offload, so there's no sub-8 GB tier. WAN stays on diffusers (`wan-5b`, `wan-14b`). No mlx.
  The branch is every non-Apple-Silicon host; Windows is wired the same way but unverified.

The two stacks are platform-conditional deps (`pyproject.toml` `sys_platform` markers + `[tool.uv.sources]`
for the MLX workspace packages) so they never share a venv. Live percent on both LTX paths (MLX and
CUDA `ltx_pipelines`) comes from wrapping the sampler's tqdm (neither pipeline exposes a callback);
only the diffusers/WAN path uses a real `callback_on_step_end`.

## Configuration

Keys live in the Config DB (Settings), not `conf/application.conf`; none is seeded by `DefaultConfigJob`, so an
absent key means the default below.

| Key | Default | Read by | Meaning |
|---|---|---|---|
| `videogen.local.model` | `ltx` under `videogen.provider=ltx-local`, `wan-5b` under `wan-local` | `VideoGenerationRouter.serviceFor` | the engine id passed as `--model`; switching it stops and respawns the sidecar |
| `videogen.local.port` | `9528` | `LocalSidecarDaemon.port()` | loopback port; passed as `--port` and used for every call |
| `videogen.local.idleTimeoutMinutes` | `15` | `LocalSidecarDaemon.spawnNow` | passed as `--idle-timeout-min`; the daemon self-evicts and releases the VRAM after that long idle |
| `videogen.local.hfToken` | blank | `LocalSidecarDaemon.spawn` | exported to the child as `HF_TOKEN` when non-blank |
| `videogen.local.startupTimeoutSeconds` | `300` | `LocalSidecarDaemon.awaitHealthy` | how long `/health` may go unanswered after spawn before the launch fails |

`LocalSidecarDaemon` also reads `videogen.local.timeoutSeconds` and exports it as `SIDECAR_REQUEST_TIMEOUT_SEC`; this
sidecar does not read that variable, so the key has no effect here.

`serve.py` flags: `--host` (`127.0.0.1`), `--port` (`9528`), `--model` (engine id), `--cache-dir` (becomes `HF_HOME`),
`--idle-timeout-min` (`15`), `--no-auth`, `--probe`. `--model` and `--cache-dir` are required unless `--probe` is given.
