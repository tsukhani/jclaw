#!/bin/sh
# Fetch the AMI-corpus additions to the diarization gold corpus (JCLAW-617).
# AMI audio + annotations are CC-BY-4.0; the ready-made RTTMs come from the
# pyannote/AMI-diarization-setup "only_words" references (the standard used
# by pyannote's own benchmarks).
#
#   ES2004a Mix-Headset : 4 speakers, clean close-talk  (~330 MB)
#   ES2004a Array1-01   : same meeting, far-field/noisy (~330 MB)
#
# Non-English coverage note: the committed haram-debate benchmark is already
# English/Malay code-switched; a dedicated non-English recording (e.g.
# AliMeeting, application-gated) remains a documented gap.
set -eu
cd "$(dirname "$0")"
mkdir -p audio gold

fetch() { [ -f "$2" ] || curl -fL --retry 3 -o "$2" "$1"; echo "ok: $2"; }

fetch "https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/ES2004a/audio/ES2004a.Mix-Headset.wav" \
      "audio/ES2004a.Mix-Headset.wav"
fetch "https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/ES2004a/audio/ES2004a.Array1-01.wav" \
      "audio/ES2004a.Array1-01.wav"
fetch "https://raw.githubusercontent.com/pyannote/AMI-diarization-setup/main/only_words/rttms/test/ES2004a.rttm" \
      "gold/ES2004a.rttm"

echo "Evaluate with:"
echo "  HF_TOKEN=... uv run ../eval.py run audio/ES2004a.Mix-Headset.wav gold/ES2004a.rttm"
