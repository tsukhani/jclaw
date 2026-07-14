# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = [
#   "torch>=2.4",
#   "torchaudio>=2.4",
#   "soundfile",
#   "numpy",
# ]
# ///
"""Forced-alignment worker for the jclaw ASR sidecar.

Gives a timestamp-less ASR (MERaLiON) the segment times the diarization fusion
needs: torchaudio MMS multilingual forced alignment maps each transcript word
back onto the audio, then words are re-grouped into pause-delimited segments —
re-imposing the pause structure Whisper produces natively, which the segment-
level fusion keys on (per-word fusion was measured NOISIER, so we segment).
Alignment also drops silence-hallucinated words (no acoustic evidence to align
to). CPU by design — MMS is small and fast, run in its own PEP 723 env.

usage (worker): uv run align.py --worker
  request per line on stdin:  {"audio_path": path, "text": transcript}
  response per line on stdout: {"segments": [{startMs, endMs, text}, ...]}  or  {"error": ...}
"""
import json
import os
import re
import subprocess
import sys
import tempfile

os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

PAUSE_MS = 600    # gap between words that starts a new segment
MAX_SEC = 300     # cap alignment audio length (MERaLiON's own limit)


def _to_wav16k(src):
    fd, wav = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src,
                    "-ar", "16000", "-ac", "1", wav], check=True)
    return wav


def _load():
    import torch
    from torchaudio.pipelines import MMS_FA as bundle
    return {"torch": torch, "model": bundle.get_model().eval(),
            "tok": bundle.get_tokenizer(), "aligner": bundle.get_aligner()}


def _align(state, audio_path, text):
    import numpy as np
    import soundfile as sf
    torch = state["torch"]
    wav16 = _to_wav16k(audio_path)
    try:
        data, sr = sf.read(wav16, dtype="float32")
    finally:
        os.remove(wav16)
    if data.ndim > 1:
        data = data.mean(1)
    data = data[:MAX_SEC * 16000]

    orig = text.split()
    norm = [re.sub(r"[^a-z']", "", w.lower()) for w in orig]
    valid = [i for i, w in enumerate(norm) if w]
    if not valid:
        return []
    words_norm = [norm[i] for i in valid]

    w = torch.from_numpy(np.ascontiguousarray(data)).unsqueeze(0)
    with torch.inference_mode():
        emission, _ = state["model"](w)
    spans = state["aligner"](emission[0], state["tok"](words_norm))
    ratio = w.shape[1] / emission.shape[1] / 16000 * 1000  # frames → ms

    segs = []
    for k, sp in enumerate(spans):
        s0 = sp[0].start * ratio
        s1 = sp[-1].end * ratio
        word = orig[valid[k]]
        if segs and s0 - segs[-1]["endMs"] <= PAUSE_MS:
            segs[-1]["endMs"] = s1
            segs[-1]["words"].append(word)
        else:
            segs.append({"startMs": s0, "endMs": s1, "words": [word]})
    return [{"startMs": int(round(s["startMs"])), "endMs": int(round(s["endMs"])),
             "text": " ".join(s["words"])} for s in segs]


def worker():
    state = _load()
    sys.stderr.write("[align] worker ready\n")
    print(json.dumps({"ready": True}), flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            print(json.dumps({"segments": _align(state, req["audio_path"], req["text"])}), flush=True)
        except Exception as e:  # noqa: BLE001 — reported per-request
            print(json.dumps({"error": str(e)}), flush=True)
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        return worker()
    sys.stderr.write("usage: uv run align.py --worker\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
