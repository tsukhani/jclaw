# TTS sidecar (JCLAW-789)

Text-to-speech worker for jclaw's read-aloud / voice mode. `serve.py` is a
stdlib HTTP supervisor (`POST /synthesize`, `GET /health`, `POST /shutdown`) that shells each
request to a persistent PEP-723 worker (`synth.py --worker`) over a stdin/stdout
JSON line protocol, so the model loads once. The Java side (`app/services/tts/`)
drives it via `TtsSidecarClient` → `TtsSidecarManager` (default port 9531).

## Authentication

Every request must carry `X-Sidecar-Token`, matching the `SIDECAR_TOKEN` the JVM derives
from its own install secret and passes in the child's environment. Without that variable
the sidecar refuses to start. A custom header is deliberately not CORS-simple, so a page
the operator visits cannot reach a warm sidecar even though it listens on loopback.

Hand-running: set a token of your own and send it, or pass `--no-auth` (off by default) to
serve unauthenticated.

```bash
SIDECAR_TOKEN=dev uv run serve.py --port 9531
curl -s -H 'X-Sidecar-Token: dev' localhost:9531/health
```

## Engines / models

Selection is `tts.engine=sidecar` + `tts.sidecar.model=<id>` (Settings › Speech).
`<id>` is the `/synthesize` `model` field, routed inside `synth.py`.

| id | engine path | platform |
|----|-------------|----------|
| `qwen3-0.6b` (default) | mlx-audio | Apple silicon only |
| `qwen3-0.6b-4bit` | mlx-audio | Apple silicon only |
| `qwen3-1.7b` | mlx-audio | Apple silicon only — routed by `synth.py` (`MLX_REPOS`) but not in `TtsModel.java`, so `TtsRouter` coerces it to the default; only reachable by a hand-run `/synthesize` |
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

### Validated on Apple Silicon (2026-07-23, MPS)
Chatterbox loads and synthesizes on MPS. Findings from the live bring-up:
- **perth watermarker fix:** chatterbox-tts hardcodes `perth.PerthImplicitWatermarker()`
  in its constructor, but perth's implicit watermarker imports as `None` in this env,
  crashing model construction. `_load_chatterbox` substitutes a no-op watermarker (the
  mark is optional and inaudible) so synthesis works; audio is otherwise unchanged.
- **Deps resolve fine** — torch + chatterbox-tts install and the model downloads. Note the
  ~2 GB torch pulled into the shared worker env (tradeoff below).
- **Speed (MPS):** cold load+synth ~19 s; warm ~3–4.3 s per short sentence — about
  3.5–5× slower than the in-JVM sherpa engine (~0.9 s first-chunk, JCLAW-800). So
  Chatterbox is a voice-**quality** option (more natural voice + zero-shot cloning), not a
  latency win: enabling it pushes voice-to-voice from ~5.3 s toward ~8 s.

Still open: the torch-in-shared-env tradeoff (below); the CUDA path (`_pick_device`) is
written but unverified on an NVIDIA box.

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
