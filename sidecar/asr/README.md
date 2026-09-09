# jclaw ASR sidecar (JCLAW-565 lineage; ASR-only since JCLAW-654)

Lifecycle-owned by `services.transcription.AsrSidecarManager`. GPU speech
recognition for jclaw — the ingest transcript behind every uploaded audio
attachment (search, previews, and the text a non-audio chat model sees).
Speaker diarization was removed from *this* sidecar in JCLAW-654 after the
measured tier comparison. Speaker attribution now runs through the
`diarize_audio` tool, on either of two paths: an audio-capable cloud chat
model, or the separate on-device `sidecar/diarize` (pyannote, the JCLAW-565
revival), whose turns are fused with this sidecar's transcript. Missing
prerequisites surface as
actionable errors — nothing silently degrades (the JCLAW-614 pattern,
matching the image/video sidecar architecture).

## Protocol

| Method | Path | Body → Response |
|---|---|---|
| GET | `/health` | → `{status, model, loaded}` |
| POST | `/transcribe` | `{audio_path, model, language?}` → `{segments: [{startMs, endMs, text}...]}` — `model` selects the engine (see below); persistent worker in its own uv script env (JCLAW-627/650) |
| GET | `/asr/models?ids=a,b` | → `{status: {<id>: …}}` — per-model cached/bytesOnDisk/engine status for the Settings page, wrapped in a `status` object |
| POST | `/asr/prefetch` | `{model}` → downloads the host engine's weights ahead of use |
| POST | `/shutdown` | graceful exit (JVM shutdown hook) |

The audio file is passed **by path** (same host; attachments are already on
disk). One inference at a time; concurrent callers get `409` and queue on
the JVM-wide fair lock.

## Engines

`serve.py` routes on the requested model id — the `MERALION_HF` map decides,
everything not in it is Whisper. The two engines live in separate PEP 723
script envs (MERaLiON pins `transformers==4.50.1`, which the model requires)
so they never share a venv.

| Engine | Model ids | How it produces segments |
|---|---|---|
| **Whisper** (default) | `small`, `medium`, `large-turbo`, `large` | mlx-whisper on Apple silicon, faster-whisper (CTranslate2) on CUDA / CPU int8. Emits segment times natively. |
| **MERaLiON** | `meralion-3-3b` → `MERaLiON/MERaLiON-3-3B-ASR` | A Southeast-Asia-tuned speech LLM (`meralion.py`). Produces a **plain transcript with no timestamps**, so it is paired with forced alignment (`align.py`) to recover the segment times Whisper gives for free. |

**MERaLiON pipeline.** Audio is transcoded to 16 kHz mono, then silero-VAD
extracts speech regions (≤30 s each, the window the model was trained on) and
only those are transcribed. Dropping the silence is what stops the model
hallucinating — it fabricates text, often in another language, on silent tails.
GPU when available (CUDA/MPS), else CPU.

**Forced alignment** (`align.py`) maps each transcript word back onto the audio
with torchaudio's MMS multilingual aligner, then re-groups words into
pause-delimited segments — re-imposing the pause structure the segment-level
diarization fusion keys on (per-word fusion measured noisier). It also drops
silence-hallucinated words, which have no acoustic evidence to align to. CPU by
design: MMS is small and fast.

## Requirements

- `uv` on PATH (shared prerequisite with the image/video sidecars). That is
  the ONLY prerequisite for the Whisper path — whisper weights are ungated, no
  Hugging Face token needed. The MERaLiON weights are ungated too.
- `ffmpeg` on PATH for the MERaLiON path (16 kHz mono transcode before VAD).
- First launch resolves the Python env (mlx-whisper or faster-whisper) and
  downloads the selected model's weights into `data/asr-models` on first
  use (or ahead of time via the Settings page).

## Licenses / attribution

- ASR: [mlx-whisper](https://github.com/ml-explore/mlx-examples) (MIT) on Apple silicon; [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (MIT) elsewhere — OpenAI Whisper weights (MIT), same as whisper.cpp (JCLAW-627).
- MERaLiON: [`MERaLiON/MERaLiON-3-3B-ASR`](https://huggingface.co/MERaLiON/MERaLiON-3-3B-ASR) — **not MIT**; ships under the MERaLiON Public License (the same license family flagged in `sidecar/diarize/README.md` for MERaLiON-SER). Check the model card's commercial terms before a paid-edition ship.
- Forced alignment: torchaudio MMS multilingual aligner (part of [torchaudio](https://github.com/pytorch/audio), BSD-2-Clause).

## Configuration

Keys live in the Config DB (Settings), not `conf/application.conf`. `DefaultConfigJob` seeds
only `transcription.localModel`; the `transcription.asr.local.*` keys fall back to the
defaults below when absent.

| Key | Default | Read by | Meaning |
|---|---|---|---|
| `transcription.localModel` | `small` (`AsrModel.DEFAULT`) | `WhisperLocalTranscriptionService`, `DiarizeAudioTool`, `VoiceController`, `ApiTranscriptionController` | the `model` id sent on `/transcribe`; an id `AsrModel` no longer knows is coerced back to the default at boot |
| `transcription.asr.local.port` | `9529` | `LocalSidecarDaemon.port()` | loopback port; passed as `--port` and used for every call |
| `transcription.asr.local.idleTimeoutMinutes` | `15` | `LocalSidecarDaemon.spawnNow` | passed as `--idle-timeout-min`; the daemon self-evicts after that long without a request |
| `transcription.asr.local.timeoutSeconds` | `1800` | `AsrSidecarClient` (per-call deadline) and `LocalSidecarDaemon.spawnNow` | the JVM's call timeout on `/transcribe` and `/asr/prefetch`; also exported as `SIDECAR_REQUEST_TIMEOUT_SEC = max(60, n − 60)`, the sidecar's own subprocess ceiling, so it gives up before the JVM's socket does |
| `transcription.asr.local.startupTimeoutSeconds` | `300` | `LocalSidecarDaemon.awaitHealthy` | how long `/health` may go unanswered after spawn before the launch fails |

`serve.py` flags: `--host` (`127.0.0.1`), `--port` (required), `--model` (`asr`, the identity
echoed on `/health`), `--cache-dir` (`data/asr-models`; becomes `HF_HOME`), `--idle-timeout-min`
(`15`), `--no-auth`. No `--probe`. `SIDECAR_REQUEST_TIMEOUT_SEC` defaults to `1740` when unset;
no `HF_TOKEN` is passed (`AsrSidecarManager` spawns with an explicit null — the weights are ungated).

## Authentication

Every request must carry `X-Sidecar-Token`, matching the `SIDECAR_TOKEN` the JVM derives
from its own install secret and passes in the child's environment. Without that variable
the sidecar refuses to start. A custom header is deliberately not CORS-simple, so a page
the operator visits cannot reach a warm sidecar even though it listens on loopback.

Hand-running: set a token of your own and send it, or pass `--no-auth` (off by default) to
serve unauthenticated.

## Running by hand (debugging)

```bash
SIDECAR_TOKEN=dev uv run serve.py --port 9529   # standalone launch
curl -s -H 'X-Sidecar-Token: dev' localhost:9529/health
curl -s -X POST localhost:9529/transcribe \
  -H 'X-Sidecar-Token: dev' \
  -H 'Content-Type: application/json' \
  -d '{"audio_path": "/absolute/path/to/recording.wav", "model": "large"}'
```

## Platform notes

- macOS / Apple Silicon: mlx-whisper on Metal — no extra config.
- Linux + NVIDIA: faster-whisper (CTranslate2) picks up CUDA automatically
  when the CUDA runtime is present — no extra config.
- CPU-only also works (faster-whisper int8) — slower but correct. There is
  no fallback engine (JCLAW-614): a sidecar failure surfaces as an error.
