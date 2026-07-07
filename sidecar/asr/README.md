# jclaw diarization sidecar (JCLAW-565)

lifecycle-owned by `services.transcription.AsrSidecarManager`. This
sidecar is jclaw's ONLY diarization engine (JCLAW-614 scrapped the inferior
in-process sherpa fallback, matching the image/video sidecar architecture):
missing prerequisites or sidecar failures surface as actionable errors —
nothing silently degrades.


Why: the JCLAW-565 bake-off on real 2-speaker podcast audio — community-1
DER 12.5% / pairwise F1 0.864 with the speaker count found automatically vs
sherpa's best-tuned F1 0.799 with knife-edge threshold behavior (sherpa's
agglomerative clustering is the ceiling; community-1's pipeline clustering and
segmentation are jointly tuned). ~18x realtime on Apple MPS.

## Protocol

| Method | Path | Body → Response |
|---|---|---|
| GET | `/health` | → `{status, device, model, loaded}` |
| POST | `/transcribe` | `{audio_path, model, language?}` → `{segments: [{startMs, endMs, text}...]}` — GPU ASR: mlx-whisper (Apple silicon) / faster-whisper (CUDA, CPU int8), own uv script env (JCLAW-627) |

The audio file is passed **by path** (same host; attachments are already on
disk). One diarization at a time; concurrent callers get `409` and queue in
the JVM.

time — the model resolves overlapping speech to the dominant/transcribable
voice). jclaw's only consumer is a mono ASR transcript, so exclusive output
beats handing overlap-aware segments to a naive max-overlap merge: on the
JCLAW-565 debate benchmark it cut DER 12.5% → 7.9% with identical turn
attribution.

## Requirements

- `uv` on PATH (shared prerequisite with the image/video sidecars).
- A Hugging Face token with the **gated** community-1 model's conditions
  accepted (visit the model page, click "Agree and access repository").
  the JVM passes it to this process as `HF_TOKEN`.
- First launch resolves the Python env (torch, ~2 GB) and downloads the
  pipeline weights (~30 MB) into `--cache-dir`.

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
