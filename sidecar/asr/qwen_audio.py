#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "mlx-audio==0.4.4; platform_machine == 'arm64' and sys_platform == 'darwin'",
#     "transformers==5.12.1; platform_machine == 'arm64' and sys_platform == 'darwin'",
#     "mlx==0.31.2; platform_machine == 'arm64' and sys_platform == 'darwin'",
#     "mlx-lm==0.31.3; platform_machine == 'arm64' and sys_platform == 'darwin'",
#     "numpy==2.4.6; platform_machine == 'arm64' and sys_platform == 'darwin'",
#     "transformers>=4.45; platform_machine != 'arm64' or sys_platform != 'darwin'",
#     "torch>=2.4; platform_machine != 'arm64' or sys_platform != 'darwin'",
#     "librosa>=0.10; platform_machine != 'arm64' or sys_platform != 'darwin'",
#     "accelerate>=0.30; platform_machine != 'arm64' or sys_platform != 'darwin'",
# ]
# ///
"""Local audio-LLM worker for jclaw (JCLAW-656): Qwen2-Audio.

Hardware-aware backend selection, mirroring transcribe.py's pattern:
  * Apple silicon  -> mlx-community/Qwen2-Audio-7B-Instruct-4bit via mlx-audio
  * NVIDIA / CPU   -> Qwen/Qwen2-Audio-7B-Instruct via transformers
                      (CUDA when available, CPU otherwise)

Weights live in the USER'S default Hugging Face cache — the parent daemon
(serve.py) points HF_HOME at data/asr-models for whisper weights, but the
Qwen model was installed to ~/.cache/huggingface and must NOT be
re-downloaded (JCLAW-656 hard requirement), so this script restores the
default cache before any HF import.

Worker line protocol (one JSON per line on stdin/stdout):
  {"op": "status"}                          -> {"installed": bool, "backend": str,
                                                "model": str, "size_gb": float}
  {"audios": [paths], "prompt": str,
   "max_tokens"?: int}                      -> {"text": str, "seconds": float}
Errors come back as {"error": "..."} — the worker never dies on a bad request.
"""

import json
import os
import platform
import sys
import time

# JCLAW-656: the model was installed to the DEFAULT HF cache; serve.py's
# HF_HOME override (whisper weights under data/) must not apply here or
# the 6.1 GB model would re-download into the wrong cache.
os.environ["HF_HOME"] = os.path.expanduser("~/.cache/huggingface")

MLX_MODEL = "mlx-community/Qwen2-Audio-7B-Instruct-4bit"
HF_MODEL = "Qwen/Qwen2-Audio-7B-Instruct"


def is_apple_silicon() -> bool:
    return sys.platform == "darwin" and platform.machine() == "arm64"


def cached_size_gb(repo: str) -> float:
    root = os.path.join(os.environ["HF_HOME"], "hub",
                        "models--" + repo.replace("/", "--"))
    if not os.path.isdir(root):
        return 0.0
    total = 0
    for dirpath, _, files in os.walk(os.path.join(root, "blobs")):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(dirpath, f))
            except OSError:
                pass
    return round(total / 1e9, 2)


def status() -> dict:
    repo = MLX_MODEL if is_apple_silicon() else HF_MODEL
    size = cached_size_gb(repo)
    return {"installed": size > 0.5, "backend": "mlx" if is_apple_silicon() else "transformers",
            "model": repo, "size_gb": size}


class MlxBackend:
    def __init__(self):
        from mlx_audio.stt.utils import load
        self.model = load(MLX_MODEL)

    def generate(self, audios, prompt, max_tokens):
        result = self.model.generate(audios if len(audios) > 1 else audios[0],
                                     prompt=prompt, max_tokens=max_tokens)
        return result.text if hasattr(result, "text") else str(result)


class TransformersBackend:
    def __init__(self):
        import torch
        from transformers import Qwen2AudioForConditionalGeneration, AutoProcessor
        self.torch = torch
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.processor = AutoProcessor.from_pretrained(HF_MODEL)
        self.model = Qwen2AudioForConditionalGeneration.from_pretrained(
            HF_MODEL,
            torch_dtype=torch.float16 if self.device == "cuda" else torch.float32,
            device_map=self.device)

    def generate(self, audios, prompt, max_tokens):
        import librosa
        content = [{"type": "audio", "audio_url": p} for p in audios]
        content.append({"type": "text", "text": prompt})
        conversation = [{"role": "user", "content": content}]
        text = self.processor.apply_chat_template(conversation, add_generation_prompt=True,
                                                  tokenize=False)
        clips = [librosa.load(p, sr=self.processor.feature_extractor.sampling_rate)[0]
                 for p in audios]
        inputs = self.processor(text=text, audios=clips, return_tensors="pt",
                                padding=True).to(self.device)
        out = self.model.generate(**inputs, max_new_tokens=max_tokens)
        out = out[:, inputs.input_ids.shape[1]:]
        return self.processor.batch_decode(out, skip_special_tokens=True)[0]


def main() -> int:
    backend = None
    sys.stdout.write(json.dumps({"ready": True, **status()}) + "\n")
    sys.stdout.flush()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            if req.get("op") == "status":
                sys.stdout.write(json.dumps(status()) + "\n")
                sys.stdout.flush()
                continue
            audios = req.get("audios") or []
            if not audios or not all(os.path.isfile(a) for a in audios):
                raise ValueError("audios must be existing file paths: %r" % audios)
            if backend is None:
                t0 = time.time()
                backend = MlxBackend() if is_apple_silicon() else TransformersBackend()
                sys.stderr.write("[qwen-audio] model loaded in %.1fs\n" % (time.time() - t0))
            t0 = time.time()
            text = backend.generate(audios, req.get("prompt") or "Transcribe this audio.",
                                    int(req.get("max_tokens") or 2048))
            sys.stdout.write(json.dumps({"text": text,
                                         "seconds": round(time.time() - t0, 1)}) + "\n")
        except Exception as e:  # noqa: BLE001 — reported per request, worker survives
            sys.stdout.write(json.dumps({"error": "%s: %s" % (type(e).__name__, e)}) + "\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
