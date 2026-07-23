# TTS sidecar (JCLAW-789)

Text-to-speech worker for jclaw's read-aloud / voice mode. `serve.py` is a
stdlib HTTP supervisor (`POST /synthesize`, `GET /health`) that shells each
request to a persistent PEP-723 worker (`synth.py --worker`) over a stdin/stdout
JSON line protocol, so the model loads once. The Java side (`app/services/tts/`)
drives it via `TtsSidecarClient` → `TtsSidecarManager` (default port 9531).

## Engines / models

Selection is `tts.engine=sidecar` + `tts.sidecar.model=<id>` (Settings › Speech).
`<id>` is the `/synthesize` `model` field, routed inside `synth.py`.

| id | engine path | platform |
|----|-------------|----------|
| `qwen3-0.6b` (default) | mlx-audio | Apple silicon only |
| `qwen3-0.6b-4bit` | mlx-audio | Apple silicon only |
| `kokoro` | mlx-audio | Apple silicon only |
| `chatterbox` | PyTorch (torch) | Apple MPS + NVIDIA CUDA (+ CPU) |

The mlx-audio models raise on non-Apple platforms (the NVIDIA Qwen backend is
deferred, JCLAW-788). **Chatterbox is the cross-platform option** — a PyTorch
model, so its branch in `synth.py` bypasses the Apple-only gate.

## Chatterbox (JCLAW-814)

- Package `chatterbox-tts` (resemble-ai, MIT). Loaded via
  `ChatterboxTTS.from_pretrained(device=...)`; synth via `model.generate(text)`;
  zero-shot voice cloning by passing `ref_audio` (a short clean clip).
- Device auto-selection `cuda > mps > cpu` (`_pick_device`), overridable with the
  `TTS_DEVICE` env var. Weights auto-provision from the public HF cache on first
  use — no Java-side download.
- To use: `tts.engine=sidecar`, `tts.sidecar.model=chatterbox`.

### Not yet validated on hardware
The Java / config / frontend wiring is complete and Chatterbox appears in Settings
automatically, but the `synth.py` torch branch has **not been run end-to-end** —
it needs the model download + `torch`/`chatterbox-tts` installed + an MPS or CUDA
device. First-run checklist:
- `uv sync --script synth.py` resolves `chatterbox-tts` + torch under
  `requires-python >=3.10,<3.13` (verify Chatterbox's own version pins fit that ceiling).
- Confirm `model.generate` returns a tensor and `model.sr` is the true sample rate.
- Note: declaring `chatterbox-tts` pulls torch (~2 GB) into the shared worker env,
  so even the MLX-only path now installs it — split into a separate sidecar env if
  that cost is unwanted.

## Benchmark: Chatterbox vs sherpa (JCLAW-814)

The JCLAW-800 instrumentation already records per-chunk TTS synthesis under the
`voice_tts_synth` segment (channel `voice`) on the Chat Performance dashboard. To
compare turn-by-turn:
1. `tts.engine=jvm`, `tts.jvm.model=kokoro-multi-lang-v1_0` (in-JVM sherpa) → run a
   few voice turns → read `voice_tts_synth` p50.
2. `tts.engine=sidecar`, `tts.sidecar.model=chatterbox` → repeat.
3. Compare. UAT baseline: sherpa first-chunk synth ~0.9 s, and TTS is not the
   voice-to-voice latency lever (JCLAW-800) — so this measures the voice-quality
   tradeoff against a likely-modest latency cost (a torch sidecar round-trip vs
   in-process sherpa).
