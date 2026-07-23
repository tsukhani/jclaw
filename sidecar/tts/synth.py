# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = [
#   "numpy",
#   "soundfile>=0.12",
#   "mlx-audio; sys_platform == 'darwin' and platform_machine == 'arm64'",
#   "misaki[en]; sys_platform == 'darwin' and platform_machine == 'arm64'",
#   "chatterbox-tts",
# ]
# ///
"""TTS synthesis worker (JCLAW-789). Text in, audio bytes out.

Runs in its OWN uv script env, shelled from serve.py per /synthesize call. The
engine + weights are chosen by the request `model` id (the router), mirroring
the ASR AsrModel routing. Apple silicon runs via mlx-audio (validated
JCLAW-788: Qwen3-TTS 0.6B ~2.6-3x realtime, streaming TTFA ~170ms, EN/ZH/Malay,
voice cloning). The NVIDIA/CPU backend (vLLM/transformers on the original
Qwen/Qwen3-TTS repos) is DEFERRED until the JCLAW-788 RTX 4090 validation.

usage (one-shot): uv run synth.py "text to speak" [model-id]   -> WAV bytes on stdout
usage (worker):   uv run synth.py --worker
  worker protocol: one request per line on stdin:
    {"op":"synthesize","text":..,"model":id,"voice":..,"ref_audio":..,"ref_text":..,"speed":..,"format":"wav"}
  one response per line on stdout: {"audio_b64":..,"sample_rate":..,"format":".."} or {"error":..}
  The loaded model is cached in-process so repeated calls skip the ~1.5s reload.
"""

import base64
import io
import json
import os
import platform
import sys

# Router: config id -> HF repo (Apple silicon / mlx-community, per JCLAW-788).
# Adding an engine/voice = one line here + one AsrModel-style enum entry on the
# Java side; no protocol change. Qwen3-TTS is primary; Kokoro is the light
# fallback (its misaki[en] G2P needs espeak — works on Linux, flaky on macOS).
MLX_REPOS = {
    "qwen3-0.6b": "mlx-community/Qwen3-TTS-12Hz-0.6B-Base-bf16",
    "qwen3-0.6b-4bit": "mlx-community/Qwen3-TTS-12Hz-0.6B-Base-4bit",
    "qwen3-1.7b": "mlx-community/Qwen3-TTS-12Hz-1.7B-Base-bf16",
    "kokoro": "mlx-community/Kokoro-82M-bf16",
}
DEFAULT_MODEL = "qwen3-0.6b"

_MODEL_CACHE = {}

# The JSON line protocol lives on fd 1 (real stdout). mlx-audio prints model-load
# chatter ("Initialized encoder codebooks", "Loaded speech tokenizer ...") to
# stdout, which would corrupt the protocol — so we dup the real stdout aside and
# point fd 1 (and thus every library print, Python or C-level) at stderr, then
# write protocol frames only to the saved real-stdout fd.
_REAL_STDOUT_FD = None


def _init_protocol_stdout():
    global _REAL_STDOUT_FD
    if _REAL_STDOUT_FD is not None:
        return
    sys.stdout.flush()
    _REAL_STDOUT_FD = os.dup(1)
    os.dup2(2, 1)  # fd 1 -> stderr, so noisy library prints never hit the protocol


def _emit_json(obj):
    os.write(_REAL_STDOUT_FD, (json.dumps(obj) + "\n").encode("utf-8"))


def _emit_bytes(data):
    os.write(_REAL_STDOUT_FD, data)


def is_apple_silicon():
    return sys.platform == "darwin" and platform.machine() == "arm64"


def _load_mlx(model_id):
    from pathlib import Path
    from huggingface_hub import snapshot_download
    from mlx_audio.tts.utils import load_model
    m = _MODEL_CACHE.get(model_id)
    if m is None:
        repo = MLX_REPOS.get(model_id, MLX_REPOS[DEFAULT_MODEL])
        sys.stderr.write("[synth] loading %s (%s)\n" % (model_id, repo))
        m = load_model(Path(snapshot_download(repo)))
        _MODEL_CACHE[model_id] = m
    return m


def _voice_seed(voice):
    """Map the `voice` field to a deterministic mlx RNG seed: a numeric voice
    picks a specific Qwen3-TTS speaker; anything else keeps one stable default."""
    if voice:
        v = voice.strip()
        if v.lstrip("-").isdigit():
            return int(v)
    return 42


def _pick_device():
    """Best torch device for the Chatterbox path: cuda > mps > cpu, with a
    TTS_DEVICE env override. Mirrors sidecar/diarize/diarize.py so a boxed GPU
    (CUDA) or Apple GPU (MPS) is used automatically."""
    override = os.environ.get("TTS_DEVICE", "").strip().lower()
    if override in ("cuda", "mps", "cpu"):
        return override
    import torch
    if torch.cuda.is_available():
        return "cuda"
    if getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def _load_chatterbox():
    """Load Chatterbox (PyTorch) once on the best device. Unlike the mlx-audio
    models this is cross-platform torch (Apple MPS and NVIDIA CUDA), so it is
    exempt from the Apple-silicon gate in _synthesize (JCLAW-814). Weights pull
    from the public HF cache on first use."""
    m = _MODEL_CACHE.get("chatterbox")
    if m is None:
        from chatterbox.tts import ChatterboxTTS
        device = _pick_device()
        sys.stderr.write("[synth] loading chatterbox on %s\n" % device)
        m = ChatterboxTTS.from_pretrained(device=device)
        _MODEL_CACHE["chatterbox"] = m
    return m


def _synthesize_chatterbox(text, ref_audio):
    """Chatterbox synth -> (float32 mono audio, sample_rate). `ref_audio` (a path
    to a short clean clip) does zero-shot voice cloning; Chatterbox has no named
    voices, so the `voice` field is unused on this path."""
    import numpy as np
    model = _load_chatterbox()
    kw = {"audio_prompt_path": ref_audio} if ref_audio else {}
    wav = model.generate(text, **kw)  # torch tensor, shape (1, N) or (N,)
    audio = np.asarray(wav.detach().to("cpu").numpy(), dtype="float32").reshape(-1)
    sr = int(getattr(model, "sr", 24000) or 24000)
    return audio, sr


def _synthesize_mlx(text, model_id, voice, ref_audio, ref_text, speed):
    """mlx-audio synth (Apple silicon) -> (float mono audio, sample_rate)."""
    import numpy as np
    model = _load_mlx(model_id)
    kw = {"verbose": False}
    # Kokoro has named voices; Qwen3-TTS-Base does not — for it, `voice` selects a
    # speaker via the RNG seed below.
    if voice and "kokoro" in model_id.lower():
        kw["voice"] = voice
    if speed:
        kw["speed"] = float(speed)
    if ref_audio:  # zero-shot voice cloning (Qwen3-TTS)
        kw["ref_audio"] = ref_audio
    if ref_text:
        kw["ref_text"] = ref_text

    # Pin the speaker so the voice stays consistent across sentence chunks and
    # turns: Qwen3-TTS-Base samples a fresh voice per call otherwise. Seed mlx's
    # RNG deterministically (a numeric `voice` picks a specific speaker; blank
    # keeps one stable default). Harmless for the already-deterministic Kokoro.
    import mlx.core as mx
    mx.random.seed(_voice_seed(voice))

    segments = list(model.generate(text=text, **kw))
    if not segments:
        raise RuntimeError("model produced no audio")
    audio = np.concatenate([np.array(getattr(s, "audio", s)).reshape(-1) for s in segments])
    sr = int(getattr(segments[0], "sample_rate", 24000) or 24000)
    return audio, sr


def _synthesize(text, model_id, voice, ref_audio, ref_text, speed, fmt):
    """Synthesize `text` to audio bytes; returns (base64_str, sample_rate).
    Routes by model id: Chatterbox takes the cross-platform torch path (MPS/CUDA);
    every other id is mlx-audio, which is Apple-silicon only."""
    import soundfile as sf

    model_id = model_id or DEFAULT_MODEL

    if model_id == "chatterbox":
        audio, sr = _synthesize_chatterbox(text, ref_audio)
    else:
        if not is_apple_silicon():
            # The mlx-audio (Qwen3/Kokoro) path is Apple-only today; the NVIDIA/CPU
            # backend for those repos is tracked by the deferred JCLAW-788 RTX 4090
            # validation. Chatterbox (model=chatterbox) is the cross-platform option.
            raise RuntimeError(
                "TTS on this platform is not yet supported for mlx-audio models — only "
                "Apple silicon is implemented (JCLAW-789). Use model=chatterbox for the "
                "cross-platform MPS/CUDA engine (JCLAW-814); the NVIDIA/vLLM Qwen backend "
                "is pending the JCLAW-788 RTX 4090 validation.")
        audio, sr = _synthesize_mlx(text, model_id, voice, ref_audio, ref_text, speed)

    # WAV is the guaranteed format (libsndfile always writes it). FLAC too if the
    # build supports it; anything else falls back to WAV for the batch cut.
    sf_fmt = "FLAC" if fmt == "flac" else "WAV"
    buf = io.BytesIO()
    sf.write(buf, audio, sr, format=sf_fmt)
    return base64.b64encode(buf.getvalue()).decode("ascii"), sr


def worker():
    _init_protocol_stdout()
    sys.stderr.write("[synth] worker ready (platform=%s)\n"
                     % ("apple-silicon" if is_apple_silicon() else sys.platform))
    _emit_json({"ready": True})
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            b64, sr = _synthesize(
                req.get("text", ""), req.get("model"), req.get("voice"),
                req.get("ref_audio"), req.get("ref_text"), req.get("speed"),
                (req.get("format") or "wav").lower())
            _emit_json({"audio_b64": b64, "sample_rate": sr,
                        "format": (req.get("format") or "wav").lower()})
        except Exception as e:  # noqa: BLE001 — reported per-request
            _emit_json({"error": str(e)})
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        return worker()
    # one-shot: uv run synth.py "text" [model] -> WAV bytes on stdout (debug aid)
    _init_protocol_stdout()
    text = sys.argv[1] if len(sys.argv) > 1 else "Hello from the jclaw text to speech sidecar."
    model_id = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_MODEL
    b64, _ = _synthesize(text, model_id, None, None, None, None, "wav")
    _emit_bytes(base64.b64decode(b64))
    return 0


if __name__ == "__main__":
    sys.exit(main())
