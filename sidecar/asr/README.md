# jclaw ASR sidecar (JCLAW-565 lineage; ASR-only since JCLAW-654)

Lifecycle-owned by `services.transcription.AsrSidecarManager`. GPU speech
recognition for jclaw — the ingest transcript behind every uploaded audio
attachment (search, previews, and the text a non-audio chat model sees).
Local speaker diarization was removed in JCLAW-654 after the measured tier
comparison; speaker attribution now runs through an audio-capable cloud
chat model (the `diarize_audio` tool). Missing prerequisites surface as
actionable errors — nothing silently degrades (the JCLAW-614 pattern,
matching the image/video sidecar architecture).

## Protocol

| Method | Path | Body → Response |
|---|---|---|
| GET | `/health` | → `{status, model, loaded}` |
| POST | `/transcribe` | `{audio_path, model, language?}` → `{segments: [{startMs, endMs, text}...]}` — mlx-whisper (Apple silicon) / faster-whisper (CUDA, CPU int8), persistent worker in its own uv script env (JCLAW-627/650) |
| GET | `/asr/models?ids=a,b` | → per-model cached/bytesOnDisk/engine status for the Settings page |
| POST | `/asr/prefetch` | `{model}` → downloads the host engine's weights ahead of use |
| POST | `/shutdown` | graceful exit (JVM shutdown hook) |

The audio file is passed **by path** (same host; attachments are already on
disk). One inference at a time; concurrent callers get `409` and queue on
the JVM-wide fair lock.

## Requirements

- `uv` on PATH (shared prerequisite with the image/video sidecars). That is
  the ONLY prerequisite — whisper weights are ungated, no Hugging Face
  token needed.
- First launch resolves the Python env (mlx-whisper or faster-whisper) and
  downloads the selected model's weights into `data/asr-models` on first
  use (or ahead of time via the Settings page).

## Licenses / attribution

- ASR: [mlx-whisper](https://github.com/ml-explore/mlx-examples) (MIT) on Apple silicon; [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (MIT) elsewhere — OpenAI Whisper weights (MIT), same as whisper.cpp (JCLAW-627).

## Running by hand (debugging)

```bash
uv run serve.py --port 9529   # standalone launch
curl -s localhost:9529/health
curl -s -X POST localhost:9529/transcribe \
  -H 'Content-Type: application/json' \
  -d '{"audio_path": "/absolute/path/to/recording.wav", "model": "large"}'
```

## Platform notes

- macOS / Apple Silicon: mlx-whisper on Metal — no extra config.
- Linux + NVIDIA: faster-whisper (CTranslate2) picks up CUDA automatically
  when the CUDA runtime is present — no extra config.
- CPU-only also works (faster-whisper int8) — slower but correct. There is
  no fallback engine (JCLAW-614): a sidecar failure surfaces as an error.
