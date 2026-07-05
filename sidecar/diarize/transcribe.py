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
(the msdd.py/separate.py pattern).

usage: uv run transcribe.py <audio> <model-size> [language]
stdout (last line): {"segments": [{"startMs": i, "endMs": i, "text": s}, ...]}
"""
import json
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
    result = mlx_whisper.transcribe(audio, path_or_hf_repo=repo, language=language,
                                    condition_on_previous_text=False)
    # mlx-whisper exposes the same per-segment confidence fields.
    return [(s["start"], s["end"], s["text"],
             s.get("no_speech_prob", 0.0), s.get("avg_logprob", 0.0),
             s.get("compression_ratio", 1.0))
            for s in result["segments"]]


def run_ct2(audio, size, language):
    from faster_whisper import WhisperModel
    try:
        model = WhisperModel(size, device="cuda", compute_type="float16")
        sys.stderr.write("[transcribe] faster-whisper %s on cuda\n" % size)
    except Exception:  # noqa: BLE001 — no CUDA: int8 CPU
        model = WhisperModel(size, device="cpu", compute_type="int8")
        sys.stderr.write("[transcribe] faster-whisper %s on cpu/int8\n" % size)
    # JCLAW-635: VAD pre-filter suppresses hallucination on silence/music;
    # the confidence triple (no_speech_prob / avg_logprob /
    # compression_ratio) rides through so the JVM can gate or flag.
    segments, _ = model.transcribe(audio, language=language,
                                   condition_on_previous_text=False,
                                   vad_filter=True)
    return [(s.start, s.end, s.text,
             s.no_speech_prob, s.avg_logprob, s.compression_ratio)
            for s in segments]


def main():
    audio = sys.argv[1]
    size = SIZES.get(sys.argv[2], sys.argv[2])
    language = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] != "-" else None
    triples = run_mlx(audio, size, language) if is_apple_silicon() \
        else run_ct2(audio, size, language)
    print(json.dumps({"segments": [
        {"startMs": int(round(s * 1000)), "endMs": int(round(e * 1000)),
         "text": t.strip(), "noSpeechProb": round(nsp, 4),
         "avgLogprob": round(alp, 4), "compressionRatio": round(cr, 4)}
        for s, e, t, nsp, alp, cr in triples]}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
