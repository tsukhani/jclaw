# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = [
#   "pyannote.audio>=4.0",
#   "torch>=2.4",
#   "torchaudio>=2.4",
# ]
# ///
"""Pyannote diarization worker (JCLAW-565 revival). Speaker turns only — no
transcription, no emotion.

Runs in its OWN uv script env (torch + pyannote, numpy>=2), shelled from
serve.py per /diarize call — isolated from the ASR sidecar's env.

usage (one-shot): uv run diarize.py <audio> [num_speakers]
usage (worker):   uv run diarize.py --worker
  worker protocol: one request per line on stdin:
    {"audio_path": path, "num_speakers": int-or-absent}
  one response per line on stdout:
    {"turns": [{"startMs","endMs","speaker"}, ...]}  or  {"error": ...}

The pyannote pipeline (~20s load) is held in-process so a persistent worker
pays it once. Gated community-1 weights need HF_TOKEN in the environment.
Audio is transcoded to 16kHz mono wav via ffmpeg first: pyannote expects
16kHz, and this makes 8kHz telephony mp3 (jclaw's common case) deterministic
regardless of torchaudio's codec backend.
"""
import json
import os
import subprocess
import sys
import tempfile

MODEL = "pyannote/speaker-diarization-community-1"


def _to_wav16k(src):
    """ffmpeg -> 16kHz mono wav in a temp file; caller removes it."""
    fd, wav = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src,
                    "-ar", "16000", "-ac", "1", wav], check=True)
    return wav


def _diarize(pipe, audio_path, num_speakers=None):
    wav = _to_wav16k(audio_path)
    try:
        kw = {"num_speakers": int(num_speakers)} if num_speakers else {}
        try:
            out = pipe(wav, **kw)
        except (TypeError, ValueError):
            # community-1 rejected the hint — fall back to auto speaker count.
            out = pipe(wav)
    finally:
        os.remove(wav)
    diar = getattr(out, "exclusive_speaker_diarization", None) \
        or getattr(out, "speaker_diarization", out)
    return [{"startMs": int(round(t.start * 1000)),
             "endMs": int(round(t.end * 1000)),
             "speaker": spk}
            for t, _, spk in diar.itertracks(yield_label=True)]


def _load_pipeline():
    import torch
    from pyannote.audio import Pipeline
    pipe = Pipeline.from_pretrained(MODEL)
    pipe.to(torch.device("cpu"))
    return pipe


def worker():
    pipe = _load_pipeline()
    sys.stderr.write("[diarize] worker ready\n")
    print(json.dumps({"ready": True}), flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            turns = _diarize(pipe, req["audio_path"], req.get("num_speakers"))
            print(json.dumps({"turns": turns}), flush=True)
        except Exception as e:  # noqa: BLE001 — reported per-request
            print(json.dumps({"error": str(e)}), flush=True)
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        return worker()
    pipe = _load_pipeline()
    ns = sys.argv[2] if len(sys.argv) > 2 else None
    print(json.dumps({"turns": _diarize(pipe, sys.argv[1], ns)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
