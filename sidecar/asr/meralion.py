# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = [
#   "transformers==4.50.1",
#   "torch>=2.4",
#   "accelerate",
#   "soundfile",
#   "numpy",
#   "silero-vad",
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

The model is trained on <=30 s windows. Audio is transcoded to 16 kHz mono via
ffmpeg, then silero-VAD extracts speech regions (<=30 s each) — only those are
transcribed, and the texts are joined. Dropping the silence is what stops the
model hallucinating (it fabricates text — often in another language — on silent
tails). GPU when available (CUDA/MPS), else CPU.
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
GEN = {"max_new_tokens": 512, "do_sample": False, "no_repeat_ngram_size": 6}


def _cache_dir_bytes(repo):
    from huggingface_hub.constants import HF_HUB_CACHE
    d = os.path.join(HF_HUB_CACHE, "models--" + repo.replace("/", "--"))
    total = 0
    for root, _, files in os.walk(d):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


def _status(repo):
    """Is the MERaLiON snapshot cached? (Settings download state, JCLAW-650.)"""
    from huggingface_hub import snapshot_download
    try:
        snapshot_download(repo, local_files_only=True)
        cached = True
    except Exception:  # noqa: BLE001 — any miss means not fully cached
        cached = False
    return {"engine": "meralion", "repo": repo, "cached": cached,
            "bytesOnDisk": _cache_dir_bytes(repo)}


def _prefetch(repo):
    from huggingface_hub import snapshot_download
    sys.stderr.write("[meralion] prefetching %s\n" % repo)
    snapshot_download(repo)
    return _status(repo)


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
    from silero_vad import load_silero_vad
    sys.stderr.write("[meralion] %s loaded on %s\n" % (model_id, device))
    return {"proc": proc, "model": model, "torch": torch, "device": device,
            "dtype": next(model.parameters()).dtype, "prompt": prompt, "vad": load_silero_vad()}


def _transcribe(state, audio_path):
    import numpy as np
    import soundfile as sf
    from silero_vad import get_speech_timestamps
    torch = state["torch"]
    wav = _to_wav16k(audio_path)
    try:
        data, sr = sf.read(wav, dtype="float32")
    finally:
        os.remove(wav)
    if data.ndim > 1:
        data = data.mean(1)
    # Pause-aware CONTINUOUS chunking: each chunk is a real audio slice from a
    # speech-region start to a speech-region end (<=30s), split at the pause that
    # crosses 30s. Continuous (no concatenation discontinuity, which hurt short
    # clips) AND every chunk ends at speech, not silence (which is what the model
    # hallucinates on). Beats both trim-only and merge-only.
    speech = get_speech_timestamps(
        torch.from_numpy(np.ascontiguousarray(data)), state["vad"],
        sampling_rate=16000, return_seconds=False)
    if not speech:
        return ""
    maxs = 30 * 16000
    chunks = []
    cs, last_end = speech[0]["start"], speech[0]["end"]
    for seg in speech[1:]:
        if seg["end"] - cs > maxs:
            chunks.append(data[cs:last_end])
            cs = seg["start"]
        last_end = seg["end"]
    chunks.append(data[cs:last_end])
    dev, dtype = state["device"], state["dtype"]
    texts = []
    for chunk in chunks:
        if chunk.size < 1600:  # <0.1s — skip
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
            op = req.get("op", "transcribe")
            if op == "status":
                print(json.dumps({"status": {m: _status(m)
                                             for m in req.get("models", [])}}), flush=True)
            elif op == "prefetch":
                print(json.dumps({"prefetched": _prefetch(req["model"])}), flush=True)
            else:
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
    if len(sys.argv) > 2 and sys.argv[1] == "--prefetch":
        # One-shot download (serve.py runs this detached so it can't stall the
        # worker/status). Prints a JSON line; nonzero exit signals failure.
        try:
            _prefetch(sys.argv[2])
            print(json.dumps({"ok": True}), flush=True)
            return 0
        except Exception as e:  # noqa: BLE001
            print(json.dumps({"error": str(e)}), flush=True)
            return 1
    sys.stderr.write("usage: uv run meralion.py --worker | --prefetch <repo>\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
