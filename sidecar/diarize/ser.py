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
  request per line on stdin:  {"audio_path": path, "spans": [[startMs,endMs],...],
                               "model": hf-id (optional; defaults MERaLiON-SER-v1)}
  response per line on stdout: {"emotions": [ {label,confidence,valence,arousal,dominance} | null, ... ]}
  aligned 1:1 with spans; null for spans under the min-duration floor (too
  little signal to score).

Audio is transcoded to 16kHz mono via ffmpeg (the model resamples to 16k;
jclaw's audio is commonly 8kHz telephony). Slices are batched through the
model. MERaLiON-SER-v1 is ungated (license: MERaLiON Public License). Runs on
GPU when available (CUDA/MPS — measured ~2x faster than CPU with identical
labels), else CPU; DIARIZE_DEVICE (shared with the pyannote worker) forces one.
"""
import json
import os
import subprocess
import sys
import tempfile

# Metal (MPS) has op-coverage gaps; let unsupported ops fall back to CPU rather
# than erroring. Harmless on CUDA/CPU. Must be set before torch runs.
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

DEFAULT_MODEL = "MERaLiON/MERaLiON-SER-v1"
MIN_MS = 1000   # skip turns under 1s — too little signal for a reliable label
MAX_S = 15      # the model's evaluation cap
BATCH = 8


def _to_wav16k(src):
    fd, wav = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src,
                    "-ar", "16000", "-ac", "1", wav], check=True)
    return wav


def _pick_device():
    """cuda > mps > cpu, unless DIARIZE_DEVICE forces one (shared with the
    pyannote worker). torch is already imported by the caller."""
    import torch
    forced = os.environ.get("DIARIZE_DEVICE", "").strip().lower()
    if forced:
        return forced
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def _load_model(model_id):
    import torch
    from transformers import AutoModelForAudioClassification
    # AutoProcessor covers models with a tokenizer (MERaLiON's Whisper stack);
    # audio-only wav2vec2 SER models have only a feature extractor, so fall
    # back to that. Any AutoModelForAudioClassification SER model then works.
    try:
        from transformers import AutoProcessor
        proc = AutoProcessor.from_pretrained(model_id, trust_remote_code=True)
    except Exception:  # noqa: BLE001 — no tokenizer → feature-extractor only
        from transformers import AutoFeatureExtractor
        proc = AutoFeatureExtractor.from_pretrained(model_id, trust_remote_code=True)
    model = AutoModelForAudioClassification.from_pretrained(
        model_id, trust_remote_code=True).eval()
    device = _pick_device()
    model.to(torch.device(device))
    sys.stderr.write("[ser] %s loaded on %s\n" % (model_id, device))
    return {"proc": proc, "model": model, "id2label": model.config.id2label,
            "torch": torch, "device": device, "pad": _needs_pad(proc)}


def _needs_pad(proc):
    """wav2vec2 feature extractors must be told to pad, to batch
    variable-length slices; Whisper ones already pad to a fixed mel length
    (padding=True would pad to the batch's longest and break the model).
    Detect by the output key on a 0.1s dummy: Whisper -> input_features."""
    import numpy as np
    out = proc(np.zeros(1600, dtype="float32"), sampling_rate=16000, return_tensors="pt")
    return "input_features" not in out


def _infer(state, batch):
    """Batch forward on the picked device; a GPU failure moves the model to
    CPU and retries once, staying on CPU thereafter. Returns (probs, dims) on
    CPU."""
    torch = state["torch"]
    kw = {"return_tensors": "pt"}
    if state["pad"]:
        kw["padding"] = True
    inputs = state["proc"](batch, sampling_rate=16000, **kw)
    dev = state["device"]
    on_dev = {k: (v.to(dev) if hasattr(v, "to") else v) for k, v in inputs.items()}
    try:
        with torch.inference_mode():
            res = state["model"](**on_dev)
    except Exception:  # noqa: BLE001 — GPU op unimplemented/OOM
        if dev == "cpu":
            raise
        sys.stderr.write("[ser] %s inference failed — falling back to CPU\n" % dev)
        state["model"].to(torch.device("cpu"))
        state["device"] = "cpu"
        on_cpu = {k: (v.to("cpu") if hasattr(v, "to") else v) for k, v in inputs.items()}
        with torch.inference_mode():
            res = state["model"](**on_cpu)
    logits = res["logits"] if "logits" in res else res.logits
    probs = torch.softmax(logits, dim=1).cpu()
    dims = res["dims"].cpu() if "dims" in res else None
    return probs, dims


def _label(state, probs_row, dims_row):
    torch = state["torch"]
    ci = int(torch.argmax(probs_row))
    id2label = state["id2label"]  # keys may be int or str depending on the model
    emo = {"label": id2label.get(ci, id2label.get(str(ci), str(ci))),
           "confidence": round(float(probs_row[ci]), 3)}
    if dims_row is not None:
        v, a, d = dims_row.tolist()
        emo.update(valence=round(v, 3), arousal=round(a, 3), dominance=round(d, 3))
    return emo


def _emotions(state, audio_path, spans):
    import soundfile as sf
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
        probs, dims = _infer(state, batch)
        for j in range(len(batch)):
            out[idxs[b + j]] = _label(state, probs[j], dims[j] if dims is not None else None)
    return out


def worker():
    cache = {}  # model_id -> loaded state, lazily loaded per requested model
    sys.stderr.write("[ser] worker ready\n")
    print(json.dumps({"ready": True}), flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            model_id = req.get("model") or DEFAULT_MODEL
            if model_id not in cache:
                cache[model_id] = _load_model(model_id)
            emos = _emotions(cache[model_id], req["audio_path"], req["spans"])
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
