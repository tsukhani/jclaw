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

  requirement; the operator downloads the weights directly from Hugging Face.
- ASR: [mlx-whisper](https://github.com/ml-explore/mlx-examples) (MIT) on Apple silicon; [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (MIT) elsewhere — OpenAI Whisper weights (MIT), same as whisper.cpp (JCLAW-627).

## Evaluation (JCLAW-617)

Run the DER/JER harness whenever a calibrated threshold changes (the
constants in EnrollmentHarvester,

```bash
# Score a cached production diarization against the committed gold:
uv run eval.py score /path/to/<attachment>.wav.diarization.json \
  eval/gold/haram-debate.rttm --uem eval/gold/haram-debate.uem

# Or run the pipeline fresh on audio and score it:
HF_TOKEN=hf_... uv run eval.py run recording.wav eval/gold/haram-debate.rttm --uem ...
```

Committed gold: `eval/gold/haram-debate.{rttm,uem}` — 68 turn-level
references derived from an independent frontier-model transcription plus
operator verification (English/Malay code-switched, 2 speakers). Baseline
raw community-1 against this gold: **DER 11.69% / JER 18.85%** (the full
pipeline's correction passes recover most of the confusion downstream).
`eval/fetch-ami.sh` adds AMI ES2004a (4 speakers, close-talk + far-field,
CC-BY-4.0). A dedicated non-English recording remains a documented gap.

End-to-end transcript metric (JCLAW-643): `eval.py cpwer` scores the FINAL
diarized transcript — the word-splitter, overlap re-attribution and
under-speech stages that DER cannot see — against the committed gold
transcript (`eval/gold/haram-debate-transcript.json`, 35 turns / 633 words,
independent frontier-model transcription, operator-verified):

```bash
uv run eval.py cpwer /path/to/diarize-output.json eval/gold/haram-debate-transcript.json
```

Timing on the M4 (3-minute recording, fresh attachment, JCLAW-644 epic):
serial pre-epic 454s → **145.5s at exact parity** (cpWER 25.91). Every
separation (JCLAW-645) and MSDD itself (JCLAW-648: 19.6s on MPS vs 225s
CPU — PYTORCH_ENABLE_MPS_FALLBACK plus a one-line stride patch on NeMo's
decoder), launched concurrently on a persistent worker so its wall-clock
still exists as a speed dial (144.7s at cpWER 27.33%) but no longer buys
meaningful time.

Baseline full pipeline against this gold: **cpWER 24.65%** (was 25.91% against the superseded machine reference) with the speaker
mapping resolving to identity (attribution is right; the number blends real
word errors with transcription-style disagreement between two independent
ASRs — treat it as a RELATIVE regression bound, like the DER baseline).
Alignment model: wav2vec2-base-960h stays (JCLAW-643 decision — Malay is
swap to an MMS-style checkpoint and re-measure cpWER if it drifts).

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
