# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = [
#   "clearvoice",
#   # JCLAW-645: torch>=2.4 is what makes clearvoice's device auto-detect
#   # pick MPS on Apple silicon — the numpy<2-era lock chose an older torch
#   # and silently ran MossFormer2 on CPU (87.6s vs 14.7s for a 50s window).
#   "torch>=2.4",
# ]
# ///
"""MossFormer2 batch separation worker (JCLAW-605).

Runs in its OWN uv script environment because clearvoice pins numpy<2 while
the serve.py env (pyannote.audio 4) needs numpy>=2. serve.py shells this
script out per /separate request with all window WAVs of one diarization,
so the model loads once per batch, not once per overlap region.

usage: uv run separate.py <window1.wav> [window2.wav ...]
stdout (last line): {"stems": {"<input path>": ["..._s1.wav", "..._s2.wav"], ...}}
Stems are written beside each input. All logging goes to stderr.
"""
import json
import math
import os
import sys


CHUNK_SEC = 40.0
OVERLAP_SEC = 1.0
CHUNK_THRESHOLD_SEC = 50.0


def _separate_chunked(cv, path, out_dir):
    """JCLAW-649: MossFormer2's attention is quadratic in input length — a
    110s window measured 64.4s on MPS where a 50s window costs ~16s. Inputs
    over CHUNK_THRESHOLD_SEC are split into CHUNK_SEC pieces with
    OVERLAP_SEC overlap, separated independently, stem identity aligned
    across the seam (L2 over the shared overlap region — MossFormer2's
    output order is arbitrary per chunk), and cross-faded back into two
    full-length stems. Returns the stem paths, or None for short inputs
    (which take the direct path)."""
    import wave
    import numpy as np
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    total_sec = len(pcm) / rate
    if total_sec <= CHUNK_THRESHOLD_SEC:
        return None
    chunk = int(CHUNK_SEC * rate)
    hop = chunk - int(OVERLAP_SEC * rate)
    base = os.path.splitext(os.path.basename(path))[0]
    n = max(1, math.ceil((len(pcm) - chunk) / hop) + 1)
    sys.stderr.write("[separate] chunking %.0fs input into %d x %.0fs pieces\n"
                     % (total_sec, n, CHUNK_SEC))
    stems_a = np.zeros(len(pcm), dtype=np.float64)
    stems_b = np.zeros(len(pcm), dtype=np.float64)
    weight = np.zeros(len(pcm), dtype=np.float64)
    prev_tail = None  # (stemA_overlap, stemB_overlap) of the previous chunk
    for i in range(n):
        at = min(i * hop, max(0, len(pcm) - chunk))
        piece = pcm[at:at + chunk]
        cpath = os.path.join(out_dir, "%s_chunk%d.wav" % (base, i))
        with wave.open(cpath, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
            w.writeframes(piece.tobytes())
        cv(input_path=cpath, online_write=True, output_path=out_dir)
        cbase = os.path.splitext(os.path.basename(cpath))[0]
        pair = []
        for k in (1, 2):
            for cand in (os.path.join(out_dir, "MossFormer2_SS_16K", "%s_s%d.wav" % (cbase, k)),
                         os.path.join(out_dir, "%s_s%d.wav" % (cbase, k))):
                if os.path.isfile(cand):
                    with wave.open(cand, "rb") as w:
                        pair.append(np.frombuffer(w.readframes(w.getnframes()),
                                                  dtype=np.int16).astype(np.float64))
                    break
        os.unlink(cpath)
        if len(pair) != 2:
            return None  # unexpected layout: fall back to the direct path
        sa, sb = pair
        ov = int(OVERLAP_SEC * rate)
        if prev_tail is not None:
            pa, pb = prev_tail
            m = min(ov, len(sa), len(pa))
            same = np.sum((sa[:m] - pa[-m:]) ** 2) + np.sum((sb[:m] - pb[-m:]) ** 2)
            swapped = np.sum((sb[:m] - pa[-m:]) ** 2) + np.sum((sa[:m] - pb[-m:]) ** 2)
            if swapped < same:
                sa, sb = sb, sa
        prev_tail = (sa, sb)
        seg = slice(at, at + len(sa))
        ramp = np.ones(len(sa))
        if at > 0:
            m = min(ov, len(sa))
            ramp[:m] = np.linspace(0, 1, m)
        stems_a[seg] += sa * ramp
        stems_b[seg] += sb * ramp
        weight[seg] += ramp
    weight[weight == 0] = 1
    out_paths = []
    for name, arr in (("s1", stems_a / weight), ("s2", stems_b / weight)):
        op = os.path.join(out_dir, "%s_%s.wav" % (base, name))
        with wave.open(op, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
            w.writeframes(np.clip(arr, -32768, 32767).astype(np.int16).tobytes())
        out_paths.append(op)
    return out_paths


def main():
    inputs = sys.argv[1:]
    if not inputs:
        print(json.dumps({"error": "no input files"}))
        return 1
    from clearvoice import ClearVoice
    cv = ClearVoice(task="speech_separation", model_names=["MossFormer2_SS_16K"])
    # JCLAW-638/645: report the device clearvoice ACTUALLY selected — with
    # torch>=2.4 in this env its auto-detect picks MPS on Apple silicon
    # (measured 16.7s vs 87.6s CPU for a 50s window).
    sys.stderr.write("[separate] loading MossFormer2_SS_16K on %s\n"
                     % getattr(cv.models[0], "device", "unknown"))
    result = {}
    for path in inputs:
        out_dir = os.path.dirname(os.path.abspath(path))
        chunked = _separate_chunked(cv, path, out_dir)
        if chunked is not None:
            result[path] = chunked
            continue
        cv(input_path=path, online_write=True, output_path=out_dir)
        base = os.path.splitext(os.path.basename(path))[0]
        stems = []
        for n in (1, 2):
            for candidate in (
                    os.path.join(out_dir, "MossFormer2_SS_16K", "%s_s%d.wav" % (base, n)),
                    os.path.join(out_dir, "%s_s%d.wav" % (base, n))):
                if os.path.isfile(candidate):
                    stems.append(candidate)
                    break
        if len(stems) != 2:
            print(json.dumps({"error": "separator produced %d stems for %s" % (len(stems), path)}))
            return 1
        result[path] = stems
        sys.stderr.write("[separate] %s done\n" % os.path.basename(path))
    print(json.dumps({"stems": result}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
