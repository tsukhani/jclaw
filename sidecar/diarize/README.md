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
| POST | `/diarize` | `{audio_path, num_speakers?, emotions?, emotion_model?}` → `{turns: [{startMs, endMs, speaker, emotion?}, ...]}` (`emotions=true` runs an SER pass per turn — best-effort; `emotion_model` picks the SER repo, default MERaLiON-SER-v1) |
| GET | `/diarize/models?ids=repo1,repo2` | → per-repo cached/bytesOnDisk download status (pyannote + the operator's SER model) for the Settings page — answered by a one-shot `uv run hf_prefetch.py --status`, a minimal env that spawns no pyannote/SER worker |
| POST | `/diarize/prefetch` | `{model}` (an HF repo) → kicks a **detached** `uv run hf_prefetch.py --prefetch` and returns immediately, so `/diarize/models` keeps reporting live progress |
| POST | `/shutdown` | graceful exit (JVM shutdown hook) |

The audio file is passed **by path** (same host; attachments are already on
disk) and transcoded to 16 kHz mono wav via ffmpeg before pyannote (which
expects 16 kHz — jclaw's audio is commonly 8 kHz telephony). One inference at
a time; concurrent callers get `409` and queue on the JVM-wide fair lock.

## Requirements

- `uv` on PATH (shared prerequisite with the asr/image/video sidecars).
- `ffmpeg` on PATH (for the 16 kHz transcode).
- A Hugging Face token in `HF_TOKEN` (the JVM passes
  `transcription.diarization.local.hfToken`, falling back to `imagegen.local.hfToken`
  when that is blank) — `pyannote/speaker-diarization-community-1` is
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
shelled from `serve.py`, GPU when available (CUDA/MPS — ~2× faster than CPU
with identical labels; `DIARIZE_DEVICE` forces one, `PYTORCH_ENABLE_MPS_FALLBACK`
+ a CPU retry cover MPS op gaps), **best-effort** (a failure returns turns
without labels). Turns under 1 s are skipped (too little signal).

The SER model is **operator-configurable** (`transcription.diarization.emotionModel`
→ `emotion_model` in the `/diarize` request; the worker caches per model). The
sidecar accepts any Hugging Face `AutoModelForAudioClassification` SER model —
`AutoProcessor`, falling back to `AutoFeatureExtractor` for audio-only wav2vec2
models — but JClaw's Settings offer a fixed trio (`DiarizeModelStore.SER_MODELS`:
MERaLiON-SER v1, superb, Dpngtm) and coerce any other value back to the default.
**MERaLiON is a robust multilingual default** (English/Chinese/Malay/Tamil/
Indonesian, conversational training, + V/A/D) — and the one verified accurate on
the hardest tested domain (8 kHz Malay telephony), where the wav2vec2
alternatives (superb, Dpngtm — English/RAVDESS-trained) load fine but collapse
(e.g. "happy" on a hostile turn). Match the model to the audio: an English
wav2vec2 SER can be fine on clean English content. MERaLiON is ungated but under
the **MERaLiON Public License** — check commercial terms before a paid-edition
ship.

## Configuration

Keys live in the Config DB (Settings), not `conf/application.conf`; none is seeded by
`DefaultConfigJob`, so an absent key means the default below.

| Key | Default | Read by | Meaning |
|---|---|---|---|
| `transcription.diarization.local.port` | `9530` | `LocalSidecarDaemon.port()` | loopback port; passed as `--port` and used for every call |
| `transcription.diarization.local.hfToken` | blank | `DiarizeSidecarManager.resolveHfToken` | exported to the child as `HF_TOKEN`; blank falls back to `imagegen.local.hfToken`, both blank → no token and the gated community-1 download fails fast |
| `transcription.diarization.local.idleTimeoutMinutes` | `15` | `LocalSidecarDaemon.spawnNow` | passed as `--idle-timeout-min`; the daemon self-evicts after that long without a request |
| `transcription.diarization.local.timeoutSeconds` | `1800` | `DiarizeSidecarClient` (per-call deadline) and `LocalSidecarDaemon.spawnNow` | the JVM's `/diarize` call timeout; also exported as `SIDECAR_REQUEST_TIMEOUT_SEC = max(60, n − 60)`, the sidecar's own subprocess ceiling, so it gives up before the JVM's socket does |
| `transcription.diarization.local.startupTimeoutSeconds` | `300` | `LocalSidecarDaemon.awaitHealthy` | how long `/health` may go unanswered after spawn before the launch fails |

`serve.py` flags: `--host` (`127.0.0.1`), `--port` (required), `--model` (`diarize`, the
identity echoed on `/health`), `--cache-dir` (`data/diarize-models`; becomes `HF_HOME`),
`--idle-timeout-min` (`15`), `--no-auth`. No `--probe`. `SIDECAR_REQUEST_TIMEOUT_SEC` defaults
to `1740` when unset.

## Authentication

Every request must carry `X-Sidecar-Token`, matching the `SIDECAR_TOKEN` the JVM derives
from its own install secret and passes in the child's environment. Without that variable
the sidecar refuses to start. A custom header is deliberately not CORS-simple, so a page
the operator visits cannot reach a warm sidecar even though it listens on loopback.

Hand-running: set a token of your own and send it, or pass `--no-auth` (off by default) to
serve unauthenticated.

## Running by hand (debugging)

```bash
SIDECAR_TOKEN=dev uv run serve.py --port 9530   # standalone launch
curl -s -H 'X-Sidecar-Token: dev' localhost:9530/health
curl -s -X POST localhost:9530/diarize \
  -H 'X-Sidecar-Token: dev' \
  -H 'Content-Type: application/json' \
  -d '{"audio_path": "/absolute/path/to/recording.mp3"}'
```

## Architecture

Two-tier uv split (same shape as `sidecar/asr`): a stdlib-only `serve.py`
supervisor holds no ML deps and shells every request to `diarize.py`, whose
`pyannote.audio` + `torch` deps live in its own PEP 723 inline script env
(numpy ≥ 2; separate from the asr scripts' envs). The pyannote pipeline
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
