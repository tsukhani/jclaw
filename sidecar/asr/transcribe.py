# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = [
#   "faster-whisper>=1.0",
#   "mlx-whisper>=0.4; sys_platform == 'darwin' and platform_machine == 'arm64'",
# ]
# ///
"""GPU ASR worker (JCLAW-627). Best-of-breed Whisper engine per
architecture, same weights as whisper.cpp so transcript quality is
unchanged while wall clock drops 5-20x:

  - Apple silicon: mlx-whisper on Metal
  - NVIDIA:        faster-whisper (CTranslate2) on CUDA
  - plain CPU:     faster-whisper int8 (still beats whisper.cpp)

Runs in its OWN uv script env, shelled from serve.py per /transcribe call
(one uv-managed script per model family).

usage (one-shot): uv run transcribe.py <audio> <model-size> [language]
usage (worker):   uv run transcribe.py --worker
  worker protocol: one request per line on stdin:
    {"audio": path, "model": size, "language": code-or-null}
  one response per line on stdout: {"segments": [...]} or {"error": ...}
  JCLAW-649: mlx_whisper caches its model in-process (ModelHolder) and
  faster-whisper's WhisperModel is held per size — a persistent worker
  amortizes the ~5s model load that every one-shot call paid (the
  under-speech pass transcribes several sub-second slices per run).
"""
import json
import os
import platform
import sys

SIZES = {"large": "large-v3", "large-turbo": "large-v3-turbo"}
MLX_REPOS = {
    "base.en": "mlx-community/whisper-base.en-mlx",
    "small.en": "mlx-community/whisper-small.en-mlx",
    "medium.en": "mlx-community/whisper-medium.en-mlx",
    "small": "mlx-community/whisper-small-mlx",
    "medium": "mlx-community/whisper-medium-mlx",
    "large-v3": "mlx-community/whisper-large-v3-mlx",
    "large-v3-turbo": "mlx-community/whisper-large-v3-turbo",
}


def is_apple_silicon():
    return sys.platform == "darwin" and platform.machine() == "arm64"


def run_mlx(audio, size, language):
    import mlx_whisper
    repo = MLX_REPOS.get(size, MLX_REPOS["large-v3"])
    sys.stderr.write("[transcribe] mlx-whisper %s\n" % repo)
    # NO word_timestamps: enabling it perturbs mlx's decode (measured: text
    # changes + 31s stamp drift on the debate benchmark) and its attention-
    # derived stamps are imprecise — word TIMES come from the JVM's CTC
    # aligner (wav2vec2 Viterbi), the WhisperX principle (JCLAW-651 r2).
    result = mlx_whisper.transcribe(audio, path_or_hf_repo=repo, language=language,
                                    condition_on_previous_text=False)
    return [(s["start"], s["end"], s["text"])
            for s in result["segments"]]


_CT2_CACHE = {}


def _transcribe(audio, size, language):
    if is_apple_silicon():
        return run_mlx(audio, size, language)
    return run_ct2_cached(audio, size, language)


def run_ct2_cached(audio, size, language):
    from faster_whisper import WhisperModel
    model = _CT2_CACHE.get(size)
    if model is None:
        try:
            model = WhisperModel(size, device="cuda", compute_type="float16")
            sys.stderr.write("[transcribe] faster-whisper %s on cuda\n" % size)
        except Exception:  # noqa: BLE001 — no CUDA: int8 CPU
            model = WhisperModel(size, device="cpu", compute_type="int8")
            sys.stderr.write("[transcribe] faster-whisper %s on cpu/int8\n" % size)
        _CT2_CACHE[size] = model
    # JCLAW-635: VAD pre-filter suppresses hallucination on silence/music.
    segments, _ = model.transcribe(audio, language=language,
                                   condition_on_previous_text=False,
                                   vad_filter=True)
    return [(s.start, s.end, s.text)
            for s in segments]


def _payload(triples):
    return {"segments": [
        {"startMs": int(round(s * 1000)), "endMs": int(round(e * 1000)),
         "text": t.strip()}
        for s, e, t in triples]}


def _engine_repo(size):
    """The HF repo the HOST engine actually loads for this model size."""
    if is_apple_silicon():
        return "mlx-whisper", MLX_REPOS.get(size, MLX_REPOS["large-v3"])
    return "faster-whisper", "Systran/faster-whisper-" + size


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


def _status(size):
    """JCLAW-650: is the HOST engine's artifact for this size cached?"""
    from huggingface_hub import snapshot_download
    engine, repo = _engine_repo(size)
    try:
        snapshot_download(repo, local_files_only=True)
        cached = True
    except Exception:  # noqa: BLE001 — any miss means not fully cached
        cached = False
    return {"engine": engine, "repo": repo, "cached": cached,
            "bytesOnDisk": _cache_dir_bytes(repo)}


def _prefetch(size):
    from huggingface_hub import snapshot_download
    engine, repo = _engine_repo(size)
    sys.stderr.write("[transcribe] prefetching %s (%s)\n" % (repo, engine))
    snapshot_download(repo)
    return _status(size)


def worker():
    sys.stderr.write("[transcribe] worker ready\n")
    print(json.dumps({"ready": True}), flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            op = req.get("op", "transcribe")
            if op == "status":
                sizes = [SIZES.get(m, m) for m in req.get("models", [])]
                print(json.dumps({"status": {m: _status(SIZES.get(m, m))
                                             for m in req.get("models", [])}}), flush=True)
            elif op == "prefetch":
                size = SIZES.get(req.get("model") or "large", req.get("model") or "large")
                print(json.dumps({"prefetched": _prefetch(size)}), flush=True)
            else:
                size = SIZES.get(req.get("model") or "large", req.get("model") or "large")
                triples = _transcribe(req["audio"], size, req.get("language") or None)
                print(json.dumps(_payload(triples)), flush=True)
        except Exception as e:  # noqa: BLE001 — reported per-request
            print(json.dumps({"error": str(e)}), flush=True)
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        return worker()
    if len(sys.argv) > 2 and sys.argv[1] == "--prefetch":
        # One-shot download of an already-resolved HF repo (serve.py runs this
        # detached so it can't stall the worker/status). JSON line; nonzero=fail.
        try:
            from huggingface_hub import snapshot_download
            snapshot_download(sys.argv[2])
            print(json.dumps({"ok": True}), flush=True)
            return 0
        except Exception as e:  # noqa: BLE001
            print(json.dumps({"error": str(e)}), flush=True)
            return 1
    audio = sys.argv[1]
    size = SIZES.get(sys.argv[2], sys.argv[2])
    language = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] != "-" else None
    triples = _transcribe(audio, size, language)
    print(json.dumps(_payload(triples)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
