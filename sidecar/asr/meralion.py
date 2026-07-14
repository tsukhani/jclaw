# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = [
#   "transformers==4.50.1",
#   "torch>=2.4",
#   "accelerate",
#   "soundfile",
#   "numpy",
# ]
# ///
"""MERaLiON-3-ASR worker for the jclaw ASR sidecar (SEA-tuned speech LLM).

Produces a PLAIN transcript — no timestamps. Whisper emits segment times
natively; MERaLiON doesn't, so the diarization path pairs this with align.py
(forced alignment) to recover segment times. Runs in its OWN uv script env
(transformers==4.50.1 — a tight pin the model requires), isolated from the
whisper worker's env.

usage (worker): uv run meralion.py --worker
  request per line on stdin:  {"audio_path": path, "model": hf-id (optional)}
  response per line on stdout: {"text": transcript}  or  {"error": ...}

The model is trained on <=30 s windows, so audio is transcoded to 16 kHz mono
via ffmpeg then chunked into 30 s non-overlapping slices; each is transcribed
and the texts are joined. GPU when available (CUDA/MPS), else CPU.
"""
import json
import os
import subprocess
import sys
import tempfile

os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

DEFAULT_MODEL = "MERaLiON/MERaLiON-3-3B-ASR"
# The fixed ASR prompt from the meralion-3-asr package; "<SpeechHere>" is the
# audio placeholder the processor expands into speech tokens.
ASR_CONTENT = ("Instruction: Please transcribe this speech. \n"
               "Follow the text instruction based on the following audio: <SpeechHere>")
CHUNK = 30 * 16000
GEN = {"max_new_tokens": 512, "do_sample": False, "no_repeat_ngram_size": 6}


def _to_wav16k(src):
    fd, wav = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src,
                    "-ar", "16000", "-ac", "1", wav], check=True)
    return wav


def _pick_device():
    import torch
    forced = os.environ.get("ASR_DEVICE", "").strip().lower()
    if forced:
        return forced
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def _load(model_id):
    import torch
    from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor
    proc = AutoProcessor.from_pretrained(model_id, trust_remote_code=True)
    model = AutoModelForSpeechSeq2Seq.from_pretrained(
        model_id, trust_remote_code=True, torch_dtype=torch.float16).eval()
    device = _pick_device()
    model.to(device)
    prompt = proc.tokenizer.apply_chat_template(
        [{"role": "user", "content": ASR_CONTENT}], tokenize=False, add_generation_prompt=True)
    sys.stderr.write("[meralion] %s loaded on %s\n" % (model_id, device))
    return {"proc": proc, "model": model, "torch": torch, "device": device,
            "dtype": next(model.parameters()).dtype, "prompt": prompt}


def _transcribe(state, audio_path):
    import soundfile as sf
    torch = state["torch"]
    wav = _to_wav16k(audio_path)
    try:
        data, sr = sf.read(wav, dtype="float32")
    finally:
        os.remove(wav)
    if data.ndim > 1:
        data = data.mean(1)
    dev, dtype = state["device"], state["dtype"]
    texts = []
    for i in range(0, len(data), CHUNK):
        chunk = data[i:i + CHUNK]
        if chunk.size < 1600:  # <0.1s tail — skip
            continue
        inputs = state["proc"](text=[state["prompt"]], audios=[chunk],
                               return_tensors="pt", padding=True)
        inputs = {k: (v.to(dev, dtype) if (hasattr(v, "to") and v.is_floating_point())
                      else (v.to(dev) if hasattr(v, "to") else v))
                  for k, v in inputs.items()}
        with torch.inference_mode():
            gen = state["model"].generate(**inputs, **GEN)
        new = gen[:, inputs["input_ids"].shape[1]:]
        texts.append(state["proc"].batch_decode(new, skip_special_tokens=True)[0].strip())
    return " ".join(t for t in texts if t)


def worker():
    cache = {}
    sys.stderr.write("[meralion] worker ready\n")
    print(json.dumps({"ready": True}), flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            model_id = req.get("model") or DEFAULT_MODEL
            if model_id not in cache:
                cache[model_id] = _load(model_id)
            print(json.dumps({"text": _transcribe(cache[model_id], req["audio_path"])}), flush=True)
        except Exception as e:  # noqa: BLE001 — reported per-request
            print(json.dumps({"error": str(e)}), flush=True)
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        return worker()
    sys.stderr.write("usage: uv run meralion.py --worker\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
