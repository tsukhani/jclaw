# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = ["pyannote.metrics", "pyannote.core"]
# ///
"""DER/JER evaluation harness for the diarization pipeline (JCLAW-617).

Run this whenever a calibrated threshold changes (OverlapReattributor,
MsddSecondOpinion, EnrollmentHarvester, SpeakerClipExtractor, SpeakerNamer
constants — see each class's javadoc) or the diarization model/pipeline is
upgraded. The gold corpus lives in eval/gold/; eval/fetch-ami.sh downloads
the additional AMI recordings.

Two modes:

  1. Score an existing hypothesis (RTTM or the JClaw per-attachment
     .diarization.json cache file) against gold:

         uv run eval.py score <hyp.rttm|hyp.diarization.json> \
             eval/gold/haram-debate.rttm [--uem eval/gold/haram-debate.uem]

  2. Run the community-1 pipeline on audio, then score (needs HF_TOKEN
     with the gated model accepted):

         HF_TOKEN=hf_... uv run eval.py run <audio.wav> \
             eval/gold/haram-debate.rttm [--uem ...]

Metrics: DER with the standard 0.25s collar per side (0.5 total) and JER.
The debate gold is turn-level, so evaluation is restricted to the labeled
turns via the UEM file — always pass it for that corpus.
"""
import argparse
import json
import sys

from pyannote.core import Annotation, Segment, Timeline
from pyannote.metrics.diarization import DiarizationErrorRate, JaccardErrorRate


def load_rttm(path):
    ann = Annotation()
    for line in open(path):
        p = line.split()
        if len(p) >= 8 and p[0] == "SPEAKER":
            start, dur = float(p[3]), float(p[4])
            ann[Segment(start, start + dur)] = p[7]
    return ann


def load_cache_json(path):
    """JClaw's per-attachment .diarization.json cache as a hypothesis."""
    ann = Annotation()
    for s in json.load(open(path))["segments"]:
        ann[Segment(s["start"], s["end"])] = f"spk{s['speaker']}"
    return ann


def load_uem(path):
    tl = Timeline()
    for line in open(path):
        p = line.split()
        if len(p) >= 4:
            tl.add(Segment(float(p[2]), float(p[3])))
    return tl.support()


def run_pipeline(audio):
    from pyannote.audio import Pipeline
    pipeline = Pipeline.from_pretrained("pyannote/speaker-diarization-community-1")
    if pipeline is None:
        sys.exit("gated model: accept the license and set HF_TOKEN")
    result = pipeline(audio)
    # Same projection the sidecar serves: exclusive when available.
    return getattr(result, "exclusive_speaker_diarization", None) \
        or getattr(result, "speaker_diarization", result)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["score", "run"])
    ap.add_argument("hyp_or_audio")
    ap.add_argument("gold_rttm")
    ap.add_argument("--uem", default=None)
    args = ap.parse_args()

    gold = load_rttm(args.gold_rttm)
    uem = load_uem(args.uem) if args.uem else None

    if args.mode == "run":
        hyp = run_pipeline(args.hyp_or_audio)
    elif args.hyp_or_audio.endswith(".json"):
        hyp = load_cache_json(args.hyp_or_audio)
    else:
        hyp = load_rttm(args.hyp_or_audio)

    der = DiarizationErrorRate(collar=0.5)
    jer = JaccardErrorRate(collar=0.5)
    d = der(gold, hyp, uem=uem, detailed=True)
    j = jer(gold, hyp, uem=uem)
    print(f"DER = {d['diarization error rate'] * 100:6.2f}%  "
          f"(confusion={d['confusion']:.1f}s missed={d['missed detection']:.1f}s "
          f"fa={d['false alarm']:.1f}s / total={d['total']:.1f}s)")
    print(f"JER = {j * 100:6.2f}%")


if __name__ == "__main__":
    main()
