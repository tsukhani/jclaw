# jclaw diarization sidecar (JCLAW-565 revival; local privacy path)

Lifecycle-owned by `services.transcription.DiarizeSidecarManager` (JVM).
Speaker diarization for jclaw — produces speaker **turns** (who spoke when),
which the JVM fuses with the ASR sidecar's transcript to build a
speaker-attributed transcript. Optionally adds a per-turn **emotion** label +
valence/arousal/dominance (MERaLiON-SER) when requested. No transcription —
that's the ASR sidecar's job.

## Protocol

| Method | Path | Body → Response |
|---|---|---|
| GET | `/health` | → `{status, model, loaded}` |
| POST | `/diarize` | `{audio_path, num_speakers?, emotions?}` → `{turns: [{startMs, endMs, speaker, emotion?}, ...]}` (`emotions=true` runs a MERaLiON-SER pass per turn — best-effort) |
| POST | `/shutdown` | graceful exit (JVM shutdown hook) |

The audio file is passed **by path** (same host; attachments are already on
disk) and transcoded to 16 kHz mono wav via ffmpeg before pyannote (which
expects 16 kHz — jclaw's audio is commonly 8 kHz telephony). One inference at
a time; concurrent callers get `409` and queue on the JVM-wide fair lock.

## Requirements

- `uv` on PATH (shared prerequisite with the asr/image/video sidecars).
- `ffmpeg` on PATH (for the 16 kHz transcode).
- A Hugging Face token in `HF_TOKEN` (the JVM passes it from
  `imagegen.local.hfToken`) — `pyannote/speaker-diarization-community-1` is
  gated; accept its terms once per HF account on the model page. Weights
  cache under `data/diarize-models` via HF_HOME on first use.

## Model

`pyannote/speaker-diarization-community-1` — neural end-to-end segmentation +
clustering. Chosen over the classical VAD + ECAPA + AHC pipeline (DiaRemot)
after the 8 kHz-telephony bake-off: on a two-party call pyannote recovered
57 turns / 2 speakers where the classical pipeline collapsed the whole call
to a single 110 s segment.

## Emotion (optional, `emotions=true`)

`MERaLiON/MERaLiON-SER-v1` (Whisper-Medium encoder) — per-turn categorical
label (neutral/happy/sad/angry/fearful/disgusted/surprised) + valence /
arousal / dominance in [0,1]. Chosen after the same bake-off: DiaRemot's
RAVDESS-lineage SER pinned to "angry" on every 8 kHz Malay turn, while
MERaLiON — trained on conversational corpora with Malay coverage — produced a
differentiated spread that tracked the call (angry on the confrontation turns,
neutral on the rest). Runs in its own PEP 723 env (`ser.py`, transformers)
shelled from `serve.py`, on **CPU** (the custom LoRA/ECAPA head isn't
MPS-safe), **best-effort** (a failure returns turns without labels). Turns
under 1 s are skipped (too little signal). Ungated, but the **MERaLiON Public
License** — check commercial terms before a paid-edition ship.

## Running by hand (debugging)

```bash
uv run serve.py --port 9530            # standalone launch
curl -s localhost:9530/health
curl -s -X POST localhost:9530/diarize \
  -H 'Content-Type: application/json' \
  -d '{"audio_path": "/absolute/path/to/recording.mp3"}'
```

## Architecture

Two-tier uv split (same shape as `sidecar/asr`): a stdlib-only `serve.py`
supervisor holds no ML deps and shells every request to `diarize.py`, whose
`pyannote.audio` + `torch` deps live in its own PEP 723 inline script env
(numpy ≥ 2; isolated from the asr env's numpy pin). The pyannote pipeline
(~20 s load) is held in a persistent worker so the load is paid once, then
amortized across `/diarize` calls until the daemon self-evicts on idle.

The worker auto-selects the fastest torch device — CUDA on NVIDIA, Apple
**MPS** on Apple silicon (~7× faster than CPU in testing: 8 s vs 57 s on a
110 s clip), else CPU — with `PYTORCH_ENABLE_MPS_FALLBACK=1` set and a
one-shot CPU retry if a GPU op fails. Set `DIARIZE_DEVICE=cpu` to force CPU
(e.g. for bit-reproducible output).

Word-level speaker attribution (forced alignment of the ASR transcript, the
WhisperX principle) is a later phase — v1 fuses at ASR-segment granularity in
the JVM (`DiarizationFusion`).
