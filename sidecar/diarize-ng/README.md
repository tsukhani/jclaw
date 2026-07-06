# diarize-ng — next-generation speaker diarization stack

A from-scratch, cascade+LLM hybrid diarizer built to compete with joint
multimodal systems (Gemini-class) on who-said-what quality while beating
them on timestamps, overlap handling, speaker identity, privacy and cost.
Independent of `sidecar/diarize/` (the production JCLAW-565 sidecar);
nothing here is wired into the JVM yet.

## Architecture (stage order is the contract)

```
audio.py         ffmpeg decode -> 16 kHz mono float32, loudness norm, chunk plan
segmentation.py  powerset local segmentation (pyannote community-1): speech,
                 speaker-change, overlap in one sliding-window model
embeddings.py    speaker embeddings over sliding windows (sherpa-onnx WeSpeaker
                 default; optional wespeaker/ReDimNet torch backend)
clustering.py    NME-SC spectral clustering -> VBx (VB-HMM) resegmentation
asr.py           word-timestamped ASR (faster-whisper + vad_filter + confidence
                 fields; optional NeMo Parakeet-TDT backend)
assign.py        word -> speaker assignment (midpoint vs diarized timeline),
                 turn building
refine.py        DiarizationLM-style LLM refinement: constrained, word-preserving
                 speaker-label correction via an OpenAI-compatible endpoint
identity.py      enrollment voiceprints, greedy-exclusive matching with
                 ambiguity gap, cross-session speaker linking
evalkit.py       RTTM/UEM/STM IO, DER (0.25 s collar), cpWER/WDER, harness CLI
pipeline.py      Diarizer facade orchestrating the stages
serve.py         localhost HTTP daemon (mirrors sidecar/diarize conventions)
cli.py           python -m diarng.cli {diarize,eval,enroll,serve}
train/           fine-tuning recipes (segmentation powerset, embeddings,
                 DiarizationLM PEFT) — scripts, not executed here
```

Design references: pyannote 4 powerset segmentation (Plaquet & Bredin), VBx
(Landini et al., BUT), NME-SC (Park et al.), DiarizationLM (Wang et al.,
Google), meeteval cpWER.

## Rules of the codebase

- **The dataclasses in `diarng/types.py` are the inter-module contract.**
  Modules communicate only via those types plus numpy arrays.
- Heavy deps (torch, pyannote, faster-whisper, sherpa-onnx, NeMo) are
  **optional extras**, imported lazily inside the class that needs them.
  `import diarng` must succeed with numpy/scipy only.
- Tests are hermetic: no model downloads, no network, no GPU. Model-backed
  classes get a thin seam and are tested with fakes/monkeypatching. Pure
  logic (chunking, clustering, assignment, refinement verifier, metrics)
  is tested for real.
- `uv` manages the env; `uv.lock` is deliberately gitignored (per-machine
  torch resolution — repo convention).
- Times are float seconds; speakers are zero-based int cluster indices;
  sample rate is `types.SAMPLE_RATE` (16000) everywhere.

## Running

```bash
cd sidecar/diarize-ng
uv run --extra dev -m pytest              # hermetic unit tests
uv run --extra full -m diarng.cli diarize path/to/audio.m4a
uv run --extra full -m diarng.cli serve --port 9531
uv run --extra eval -m diarng.cli eval --ref gold.rttm --hyp out.rttm
```

## Status

Built by a phased agent workflow on branch `feature/diarizer-ng`.
Unit-tested hermetically; end-to-end inference requires downloading gated
models (HF token for pyannote) and is exercised via `scripts/smoke.py`,
not in CI.
