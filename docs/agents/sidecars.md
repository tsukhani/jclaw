# Sidecars — local Python daemons

Seven Python services live under `sidecar/<name>/` — `asr`, `diarize`, `fetch`, `image`,
`stealth`, `tts`, `video` — each a `serve.py` plus `pyproject.toml`, run through `uv`
(`uv.lock` is per machine and gitignored). The JVM owns their lifecycle: each has a manager
under `app/services` holding one static `LocalSidecarDaemon`, built from a `Config` naming the
directory, the cache under `data/`, the Config-DB prefix, the default port and the startup
budget. The daemon spawns `uv run serve.py --port … --idle-timeout-min …` with a per-process
`SIDECAR_TOKEN` that every request echoes as `X-Sidecar-Token`, drains stdout and stderr on
virtual threads, polls `/health` until the startup budget runs out, and stops with `destroy()`
then `destroyForcibly()`. A spawn is single-flight under one lock (JCLAW-830): a second starter
waits, re-checks health and short-circuits, because a double spawn on a fixed port poisons the
spawn-failure cooldown of the healthy process. An idle sidecar exits on its own timer and is
respawned by the next call.

Keys are read live under each manager's prefix — `<prefix>.port`, `.timeoutSeconds`,
`.idleTimeoutMinutes` (default 15), `.startupTimeoutSeconds`, and `.hfToken` where a gated
model needs one — and each sidecar's README documents its own. Default ports: image 9527,
video 9528, asr 9529, diarize 9530, tts 9531, stealth 9532, fetch 9533.
`SidecarDefaultPortsConformanceTest` fails on any two managers sharing one: tts and fetch both
compiled in 9531 until JCLAW-1172, and whichever spawned second died on the bind. It also fails on a
manager whose port is not a literal number, since it cannot check that one. `ShutdownJob` stops the
managers from a list kept by hand, and `SidecarShutdownConformanceTest` fails on one missing from it.

Two supporting pieces. `services.sidecar.SidecarHttpClient` is the shared skeleton for the
inference clients (ASR, diarization, TTS): each of those sidecars handles one inference at a
time and answers 409 when busy, so calls are serialized JVM-wide through a fair lock owned by
each subclass (JCLAW-828) and concurrent conversations queue instead of surfacing the busy
condition. `services.SidecarCapabilityProbe` runs the image and video sidecars' one-shot
`serve.py --probe` on a background thread to detect the GPU and free VRAM for the Settings
"can this machine run it?" gate. The daemon and the probe are the sidecar entries in
`archunit_store/shell-process-spawners` (see [capabilities.md](capabilities.md)); a new sidecar goes through
`LocalSidecarDaemon` rather than adding a spawner of its own.
