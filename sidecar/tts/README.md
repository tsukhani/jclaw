# TTS sidecar (JCLAW-789)

Text-to-speech worker for jclaw's read-aloud / voice mode. `serve.py` is a
stdlib HTTP supervisor (`POST /synthesize`, `GET /health`, `POST /shutdown`) that shells each
request to a persistent PEP-723 worker (`synth.py --worker`) over a stdin/stdout
JSON line protocol, so the model loads once. The Java side (`app/services/tts/`)
drives it via `TtsSidecarClient` → `TtsSidecarManager` (default port 9531).

## Protocol

| Method | Path | Body → Response |
|---|---|---|
| GET | `/health` | → `{status, model, loaded}` |
| POST | `/synthesize` | `{text, model?, voice?, ref_audio?, speed?, format?}` → audio bytes, `Content-Type` from `format` (default `wav`); `400` for a body that is not JSON or an empty `text`; `409` while another synthesis is running (one at a time — the Java client queues on its fair lock); `500` for a worker failure, message verbatim |
| POST | `/shutdown` | → `200`, then exit — how a restarted JVM evicts an adopted orphan whose identity no longer matches |

`_MIME` advertises `wav`, `flac`, `mp3` and `opus`, but `synth.py` writes only WAV, or FLAC when
`format=flac` and the worker's libsndfile build supports it — so `mp3`/`opus` come back as WAV
bytes labelled `audio/mpeg`/`audio/opus`. `TtsSidecarClient` sends `text`, `model`, `voice`,
`format` and `ref_audio`; `speed` is accepted here but never sent by the JVM.

## Configuration

Keys live in the Config DB (Settings), not `conf/application.conf`. `DefaultConfigJob` seeds
`tts.local.port` explicitly so the client and the spawned daemon agree; the rest fall back to
the defaults below. Engine and model selection (`tts.engine`, `tts.sidecar.model`) is under
[Engines / models](#engines--models).

| Key | Default | Read by | Meaning |
|---|---|---|---|
| `tts.local.port` | `9531` | `LocalSidecarDaemon.port()` | loopback port; passed as `--port` and used for every call |
| `tts.local.idleTimeoutMinutes` | `15` | `LocalSidecarDaemon.spawnNow` | passed as `--idle-timeout-min`; the daemon self-evicts after that long without a request |
| `tts.local.timeoutSeconds` | `1800` | `TtsSidecarClient` (per-call deadline) and `LocalSidecarDaemon.spawnNow` | the JVM's `/synthesize` call timeout; also exported as `SIDECAR_REQUEST_TIMEOUT_SEC = max(60, n − 60)`, the sidecar's own request ceiling, so it gives up before the JVM's socket does |
| `tts.local.startupTimeoutSeconds` | `300` | `LocalSidecarDaemon.awaitHealthy` | how long `/health` may go unanswered after spawn before the launch fails |

`serve.py` flags: `--host` (`127.0.0.1`), `--port` (required), `--model` (`tts`, the identity
echoed on `/health`), `--cache-dir` (`data/tts-models`), `--idle-timeout-min` (`15`), `--no-auth`.
No `--probe`. `SIDECAR_REQUEST_TIMEOUT_SEC` defaults to `1740` when unset; no `HF_TOKEN` is passed
(`TtsSidecarManager` spawns with an explicit null).

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
