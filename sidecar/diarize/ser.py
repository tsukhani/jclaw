# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = [
#   "transformers>=4.44,<4.50",
#   "torch>=2.4",
#   "soundfile",
#   "numpy",
#   "peft",
#   "omegaconf",
#   "einops",
# ]
# ///
"""MERaLiON-SER emotion worker (JCLAW-565 emotion stage). Per-turn
speech-emotion recognition over diarization turns — a categorical label plus
continuous valence/arousal/dominance. Runs in its OWN uv script env
(transformers + MERaLiON custom modeling code), shelled from serve.py per
/diarize call when emotions are requested — isolated from the pyannote
worker's env.

usage (worker): uv run ser.py --worker
  request per line on stdin:  {"audio_path": path, "spans": [[startMs,endMs],...]}
  response per line on stdout: {"emotions": [ {label,confidence,valence,arousal,dominance} | null, ... ]}
  aligned 1:1 with spans; null for spans under the min-duration floor (too
  little signal to score).

Audio is transcoded to 16kHz mono via ffmpeg (the model resamples to 16k;
jclaw's audio is commonly 8kHz telephony). Slices are batched through the
model. MERaLiON-SER-v1 is ungated (license: MERaLiON Public License). CPU by
design — the custom LoRA/ECAPA head is not MPS-safe; emotion is opt-in so the
latency is acceptable.
"""
import json
import os
import subprocess
import sys
import tempfile

MODEL = "MERaLiON/MERaLiON-SER-v1"
MIN_MS = 1000   # skip turns under 1s — too little signal for a reliable label
MAX_S = 15      # the model's evaluation cap
BATCH = 8


def _to_wav16k(src):
    fd, wav = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src,
                    "-ar", "16000", "-ac", "1", wav], check=True)
    return wav


def _load_model():
    import torch
    from transformers import AutoModelForAudioClassification, AutoProcessor
    proc = AutoProcessor.from_pretrained(MODEL, trust_remote_code=True)
    model = AutoModelForAudioClassification.from_pretrained(
        MODEL, trust_remote_code=True).eval()
    return {"proc": proc, "model": model, "id2label": model.config.id2label, "torch": torch}


def _label(state, probs_row, dims_row):
    torch = state["torch"]
    ci = int(torch.argmax(probs_row))
    emo = {"label": state["id2label"][str(ci)],
           "confidence": round(float(probs_row[ci]), 3)}
    if dims_row is not None:
        v, a, d = dims_row.tolist()
        emo.update(valence=round(v, 3), arousal=round(a, 3), dominance=round(d, 3))
    return emo


def _emotions(state, audio_path, spans):
    import soundfile as sf
    torch = state["torch"]
    wav = _to_wav16k(audio_path)
    try:
        data, sr = sf.read(wav, dtype="float32")
    finally:
        os.remove(wav)
    if data.ndim > 1:
        data = data.mean(axis=1)

    out = [None] * len(spans)
    idxs, slices = [], []
    for i, (s_ms, e_ms) in enumerate(spans):
        if e_ms - s_ms < MIN_MS:
            continue
        s = int(s_ms / 1000 * sr)
        e = min(int(e_ms / 1000 * sr), s + MAX_S * sr)
        seg = data[s:e]
        if seg.size >= sr // 10:
            idxs.append(i)
            slices.append(seg)

    for b in range(0, len(slices), BATCH):
        batch = slices[b:b + BATCH]
        inputs = state["proc"](batch, sampling_rate=16000, return_tensors="pt")
        with torch.inference_mode():
            res = state["model"](**inputs)
        logits = res["logits"] if "logits" in res else res.logits
        probs = torch.softmax(logits, dim=1)
        dims = res["dims"] if "dims" in res else None
        for j in range(len(batch)):
            out[idxs[b + j]] = _label(state, probs[j], dims[j] if dims is not None else None)
    return out


def worker():
    state = _load_model()
    sys.stderr.write("[ser] worker ready\n")
    print(json.dumps({"ready": True}), flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            emos = _emotions(state, req["audio_path"], req["spans"])
            print(json.dumps({"emotions": emos}), flush=True)
        except Exception as e:  # noqa: BLE001 — reported per-request
            print(json.dumps({"error": str(e)}), flush=True)
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        return worker()
    sys.stderr.write("usage: uv run ser.py --worker\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
