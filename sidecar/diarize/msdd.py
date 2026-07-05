# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = ["nemo_toolkit[asr]", "huggingface_hub"]
# ///
"""NeMo MSDD second-opinion worker (JCLAW-612).

Runs in its OWN uv script environment (NeMo is multi-GB and must not touch
the pyannote env — the clearvoice/numpy lesson from JCLAW-605). serve.py
shells this script per /msdd request. MSDD (multi-scale diarization
decoder, TS-VAD lineage) tracks each speaker profile frame-by-frame with
temporal continuity, which resolves soft overlapped speech that isolated
window embeddings misjudge (JCLAW-610 spike: DER 5.97% vs community-1's
7.88% on the debate benchmark, natively correct on its residual turns).

usage: uv run msdd.py <audio.wav> <num_speakers>
stdout (last line): {"segments": [{"start": s, "end": e, "speaker": n}, ...]}
Segments may overlap in time (the output is overlap-aware). Logs to stderr.
"""
import json
import sys


def main():
    audio, num_speakers = sys.argv[1], int(sys.argv[2])
    import torch
    from nemo.collections.asr.models.msdd_models import NeuralDiarizer
    # JCLAW-638: NeMo supports CUDA but not MPS — pick CUDA when present,
    # otherwise CPU, and SAY so (the silent-CPU-on-Mac behavior hid a 5x
    # cost). serve.py logs devices the same way.
    device = "cuda" if torch.cuda.is_available() else "cpu"
    sys.stderr.write("[msdd] loading diar_msdd_telephonic on %s%s\n"
                     % (device, " (NeMo has no MPS support)" if device == "cpu"
                        and torch.backends.mps.is_available() else ""))
    diarizer = NeuralDiarizer.from_pretrained("diar_msdd_telephonic")
    diarizer = diarizer.to(device)
    # num_workers=0: NeMo dataloader workers hit a spawn-pickling failure on
    # macOS (SpeechLabelEntity is not picklable across spawn).
    annotation = diarizer(audio, num_speakers=num_speakers,
                          max_speakers=max(2, num_speakers), num_workers=0)
    segments = []
    for segment, _, label in annotation.itertracks(yield_label=True):
        segments.append({"start": round(segment.start, 3),
                         "end": round(segment.end, 3),
                         "speaker": int(label.split("_")[-1])})
    print(json.dumps({"segments": segments}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
