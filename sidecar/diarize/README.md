# jclaw diarization sidecar (JCLAW-565)

Speaker diarization via the **pyannote community-1** pipeline. Launched +
lifecycle-owned by `services.transcription.PyannoteSidecarManager`. This
sidecar is jclaw's ONLY diarization engine (JCLAW-614 scrapped the inferior
in-process sherpa fallback, matching the image/video sidecar architecture):
missing prerequisites or sidecar failures surface as actionable errors —
nothing silently degrades.

    uv run serve.py --port 9529 --cache-dir ../../data/pyannote-models --idle-timeout-min 15

Why: the JCLAW-565 bake-off on real 2-speaker podcast audio — community-1
DER 12.5% / pairwise F1 0.864 with the speaker count found automatically vs
sherpa's best-tuned F1 0.799 with knife-edge threshold behavior (sherpa's
agglomerative clustering is the ceiling; community-1's pipeline clustering and
segmentation are jointly tuned). ~18x realtime on Apple MPS.

## Protocol

| Method | Path | Body → Response |
|---|---|---|
| GET | `/health` | → `{status, device, model, loaded}` |
| POST | `/diarize` | `{audio_path, num_speakers?}` → `{segments: [{start, end, speaker}...], overlaps: [{start, end}...], device, seconds}`; `400` bad path, `409` busy, `500` load/inference error |
| POST | `/separate` | `{audio_paths: [...]}` (ready-made 16 kHz mono WAVs) → `{stems: {"<path>": ["..._s1.wav", "..._s2.wav"], ...}}` — batched MossFormer2 2-speaker separation, one model load per batch, stems written beside each input (JCLAW-605) |
| POST | `/transcribe` | `{audio_path, model, language?}` → `{segments: [{startMs, endMs, text}...]}` — GPU ASR: mlx-whisper (Apple silicon) / faster-whisper (CUDA, CPU int8), own uv script env (JCLAW-627) |
| POST | `/embed` | `{audio_paths: [...], model}` → `{embeddings: [[...], ...]}` — batched WeSpeaker embeddings, same ONNX + feature pipeline as the retired JVM JNI stack (JCLAW-630) |
| POST | `/msdd` | `{audio_path, num_speakers}` (16 kHz mono WAV) → `{segments: [{start, end, speaker}...]}` — NeMo MSDD second opinion, overlap-aware, segments may overlap in time (JCLAW-612) |

The audio file is passed **by path** (same host; attachments are already on
disk). One diarization at a time; concurrent callers get `409` and queue in
the JVM.

Segments come from pyannote 4's **exclusive** diarization (one speaker at a
time — the model resolves overlapping speech to the dominant/transcribable
voice). jclaw's only consumer is a mono ASR transcript, so exclusive output
beats handing overlap-aware segments to a naive max-overlap merge: on the
JCLAW-565 debate benchmark it cut DER 12.5% → 7.9% with identical turn
attribution.

## Requirements

- `uv` on PATH (shared prerequisite with the image/video sidecars).
- A Hugging Face token with the **gated** community-1 model's conditions
  accepted (visit the model page, click "Agree and access repository").
  Configure it in jclaw Settings (`transcription.diarization.local.hfToken`);
  the JVM passes it to this process as `HF_TOKEN`.
- First launch resolves the Python env (torch, ~2 GB) and downloads the
  pipeline weights (~30 MB) into `--cache-dir`.

## Licenses / attribution

- Model: [pyannote/speaker-diarization-community-1](https://huggingface.co/pyannote/speaker-diarization-community-1),
  © pyannoteAI, **CC-BY-4.0** — this attribution satisfies the license's
  requirement; the operator downloads the weights directly from Hugging Face.
- Library: [pyannote.audio](https://github.com/pyannote/pyannote-audio), MIT.
- Separator: [MossFormer2 via ClearerVoice-Studio](https://github.com/modelscope/ClearerVoice-Studio), Apache-2.0 (JCLAW-605); weights download on first `/separate`.
- ASR: [mlx-whisper](https://github.com/ml-explore/mlx-examples) (MIT) on Apple silicon; [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (MIT) elsewhere — OpenAI Whisper weights (MIT), same as whisper.cpp (JCLAW-627).
- Second opinion: [NVIDIA NeMo](https://github.com/NVIDIA/NeMo) MSDD (`diar_msdd_telephonic` + `titanet_large`), Apache-2.0 toolkit / CC-BY-4.0 weights (JCLAW-612); runs in its own uv script env (`msdd.py`), first `/msdd` builds it.

## Evaluation (JCLAW-617)

Run the DER/JER harness whenever a calibrated threshold changes (the
constants in OverlapReattributor, MsddSecondOpinion, EnrollmentHarvester,
SpeakerClipExtractor, SpeakerNamer) or the model/pipeline is upgraded:

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

## Running by hand (debugging)

```bash
HF_TOKEN=hf_... uv run serve.py --port 9529 --cache-dir /tmp/pyannote-cache
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
