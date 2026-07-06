#!/usr/bin/env python3
"""End-to-end, real-model smoke run for diarize-ng.

THIS IS NOT A TEST. It downloads gated multi-GB models and runs real
inference on the GPU/CPU, so it is deliberately excluded from the hermetic
pytest suite. Use it to sanity-check that the whole cascade actually runs on a
real machine after a fresh install.

Prerequisites
-------------
1. ffmpeg on PATH (audio decode).
2. A Hugging Face token that has accepted the pyannote community-1 model
   licence, exported as HF_TOKEN:

       export HF_TOKEN=hf_xxx

   Accept the conditions at
   https://huggingface.co/pyannote/speaker-diarization-community-1 first.
3. The optional backends installed:

       uv sync --extra full

4. A sherpa-onnx WeSpeaker embedding model (.onnx). Pass its path with
   --embedding-model or the EMBEDDING_MODEL env var. Without it the embedding
   stage cannot run.

Usage
-----
    export HF_TOKEN=hf_xxx
    uv run --extra full scripts/smoke.py path/to/audio.m4a \
        --embedding-model path/to/wespeaker.onnx --num-speakers 2

Add --refine with --llm-base-url / --llm-model to exercise the optional
DiarizationLM refinement pass against an OpenAI-compatible endpoint.
"""

from __future__ import annotations

import argparse
import os
import sys

from diarng.pipeline import Diarizer, DiarizerConfig


def main() -> int:
    ap = argparse.ArgumentParser(description="diarize-ng real-model smoke run")
    ap.add_argument("audio", help="audio file to diarize (any ffmpeg codec)")
    ap.add_argument("--num-speakers", type=int, default=None)
    ap.add_argument(
        "--embedding-model",
        default=os.environ.get("EMBEDDING_MODEL"),
        help="sherpa WeSpeaker ONNX path (or EMBEDDING_MODEL env var)",
    )
    ap.add_argument("--language", default=None)
    ap.add_argument("--voiceprint-store", default=None)
    ap.add_argument("--refine", action="store_true")
    ap.add_argument("--llm-base-url", default=None)
    ap.add_argument("--llm-model", default=None)
    args = ap.parse_args()

    hf_token = os.environ.get("HF_TOKEN")
    if not hf_token:
        print(
            "HF_TOKEN is not set — the pyannote model is gated and the "
            "segmentation stage will fail. See this script's docstring.",
            file=sys.stderr,
        )
    if not args.embedding_model:
        print(
            "No --embedding-model / EMBEDDING_MODEL — the embedding stage "
            "cannot run. See this script's docstring.",
            file=sys.stderr,
        )

    config = DiarizerConfig(
        embedding_model_path=args.embedding_model,
        language=args.language,
        voiceprint_store_path=args.voiceprint_store,
        hf_token=hf_token,
        refine_enabled=args.refine,
        llm_base_url=args.llm_base_url,
        llm_model=args.llm_model,
    )

    print(f"diarizing {args.audio} ...", file=sys.stderr)
    result = Diarizer(config).diarize(args.audio, num_speakers=args.num_speakers)

    print(
        f"\n=== {result.num_speakers} speaker(s), {len(result.turns)} turn(s), "
        f"device={result.meta.get('device')} ===\n"
    )
    for turn in result.turns:
        label = turn.name or f"Speaker {turn.speaker}"
        print(f"[{turn.start:7.2f} - {turn.end:7.2f}] {label}: {turn.text}")

    print("\nstage timings (s):", file=sys.stderr)
    for stage, secs in result.meta.get("timings", {}).items():
        print(f"  {stage:20s} {secs:.2f}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
