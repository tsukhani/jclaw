# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = ["sherpa-onnx>=1.12", "numpy"]
# ///
"""Batched WeSpeaker embedding worker (JCLAW-630).

Runs the SAME wespeaker_en_voxceleb_resnet34_LM ONNX through sherpa-onnx's
Python package — identical feature pipeline and model as the retired JVM
JNI stack, so embeddings are bit-compatible and no matching threshold
needs recalibration. Batched: one process invocation embeds N windows.

usage: uv run embed.py <model.onnx> <windows.json>
  windows.json: {"paths": ["w1.wav", ...]}    (16 kHz mono PCM16 WAVs)
stdout (last line): {"embeddings": [[f, ...], ...]}  (aligned with paths)
"""
import json
import sys
import wave

import numpy as np
import sherpa_onnx


def read_wav(path):
    with wave.open(path, "rb") as w:
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
        return (pcm.astype(np.float32) / 32768.0), w.getframerate()


def main():
    model, spec = sys.argv[1], json.load(open(sys.argv[2]))
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=model, num_threads=2))
    out = []
    for path in spec["paths"]:
        samples, rate = read_wav(path)
        stream = extractor.create_stream()
        stream.accept_waveform(rate, samples)
        stream.input_finished()
        out.append([float(x) for x in extractor.compute(stream)])
    print(json.dumps({"embeddings": out}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
