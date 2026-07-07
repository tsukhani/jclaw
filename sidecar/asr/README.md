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

## Evaluation harness (`eval/`, dev tree only)

`eval/` and `eval.py` are offline measurement tooling — excluded from the
distribution bundle by `.distignore`; they exist only in the source tree.
They anchored every architecture decision of the diarization campaign
(JCLAW-617/643/653/654) and remain the yardstick for ASR-quality and
diarized-transcript regressions.

- `eval/gold/haram-debate-transcript.json` — the human-arbitrated golden
  transcript (English/Malay code-switched, 2 speakers, 35 turns / 633
  words). The measurement anchor: it scored the local pipeline's rounds,
  the cloud-tier decision (Gemini 23.56 cpWER at perfect attribution vs
  local 34.32), and the 2026-07 ASR bake-off (whisper-large-v3 17.0% WER
  / 4-10 Malay key phrases vs Voxtral-Mini-3B 19.7% / 7-10).
- `eval.py cpwer <diarized-output.json> eval/gold/haram-debate-transcript.json`
  — concatenated-permutation WER of a final diarized transcript against
  gold. Works on any producer, including the shipped cloud tier's output.
- `eval/parity.py` — turn-attribution parity: how many gold turns landed
  on the right speaker.
- `eval/gold/haram-debate.{rttm,uem}` + `eval.py score` — DER/JER timing
  ground truth from the local-diarization era. The pipeline they measured
  was removed in JCLAW-653/654; they remain valid ground truth for
  scoring any future diarizer that emits timed segments.
- `eval/fetch-ami.sh` — pulls AMI ES2004a (4 speakers, CC-BY-4.0) for
  broader-than-one-recording evaluation.

## Running by hand (debugging)

```bash
curl -s localhost:9529/health
curl -s -X POST localhost:9529/diarize \
  -H 'Content-Type: application/json' \
  -d '{"audio_path": "/absolute/path/to/recording.wav"}'
```

## Platform notes

- macOS / Apple Silicon: default PyPI torch wheel ships MPS — no extra config.
- Linux + NVIDIA: `UV_TORCH_BACKEND=cu124 uv run serve.py ...` for the CUDA wheel.
- CPU-only also works (~0.2x realtime) — slower but correct. There is no
  fallback engine (JCLAW-614): a sidecar failure surfaces as an error.
