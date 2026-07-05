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
| POST | `/separate` | `{audio_path}` (ready-made 16 kHz mono WAV) → `{stems: ["..._s1.wav", "..._s2.wav"]}` — MossFormer2 2-speaker separation, stems written beside the input (JCLAW-605) |
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
- Second opinion: [NVIDIA NeMo](https://github.com/NVIDIA/NeMo) MSDD (`diar_msdd_telephonic` + `titanet_large`), Apache-2.0 toolkit / CC-BY-4.0 weights (JCLAW-612); runs in its own uv script env (`msdd.py`), first `/msdd` builds it.

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
- CPU-only also works (~0.2x realtime) — slower but correct; the router's
  fallback to sherpa only triggers on sidecar *failure*, not slowness.
