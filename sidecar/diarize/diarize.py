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

# Metal (MPS) has gaps in op coverage; let unsupported ops fall back to CPU
# instead of erroring. Harmless on CUDA/CPU. Must be set before torch runs.
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

MODEL = "pyannote/speaker-diarization-community-1"


def _to_wav16k(src):
    """ffmpeg -> 16kHz mono wav in a temp file; caller removes it."""
    fd, wav = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src,
                    "-ar", "16000", "-ac", "1", wav], check=True)
    return wav


def _pick_device():
    """cuda > mps > cpu, unless DIARIZE_DEVICE forces one (e.g. cpu for
    bit-reproducibility). torch is already imported by the caller."""
    import torch
    forced = os.environ.get("DIARIZE_DEVICE", "").strip().lower()
    if forced:
        return forced
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def _run(pipe, wav, num_speakers):
    kw = {"num_speakers": int(num_speakers)} if num_speakers else {}
    try:
        return pipe(wav, **kw)
    except (TypeError, ValueError):
        # community-1 rejected the hint — fall back to auto speaker count.
        return pipe(wav)


def _diarize(state, audio_path, num_speakers=None):
    wav = _to_wav16k(audio_path)
    try:
        try:
            out = _run(state["pipe"], wav, num_speakers)
        except Exception:  # noqa: BLE001 — GPU op may be unimplemented/OOM
            if state["device"] == "cpu":
                raise
            # Move the persistent pipeline to CPU and retry once; stay on CPU
            # for later calls rather than thrash back to the failing device.
            import torch
            sys.stderr.write("[diarize] %s inference failed — falling back to CPU\n"
                             % state["device"])
            state["pipe"].to(torch.device("cpu"))
            state["device"] = "cpu"
            out = _run(state["pipe"], wav, num_speakers)
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
    device = _pick_device()
    pipe.to(torch.device(device))
    sys.stderr.write("[diarize] pipeline loaded on %s\n" % device)
    return {"pipe": pipe, "device": device}


def worker():
    state = _load_pipeline()
    sys.stderr.write("[diarize] worker ready\n")
    print(json.dumps({"ready": True}), flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            turns = _diarize(state, req["audio_path"], req.get("num_speakers"))
            print(json.dumps({"turns": turns}), flush=True)
        except Exception as e:  # noqa: BLE001 — reported per-request
            print(json.dumps({"error": str(e)}), flush=True)
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        return worker()
    state = _load_pipeline()
    ns = sys.argv[2] if len(sys.argv) > 2 else None
    print(json.dumps({"turns": _diarize(state, sys.argv[1], ns)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
