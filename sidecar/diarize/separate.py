# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = ["clearvoice"]
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
import os
import sys


def main():
    inputs = sys.argv[1:]
    if not inputs:
        print(json.dumps({"error": "no input files"}))
        return 1
    import torch
    from clearvoice import ClearVoice
    # JCLAW-638: clearvoice picks CUDA itself when available but never MPS
    # (upstream limitation) — log the effective device so silent-CPU-on-Mac
    # is at least visible, the way serve.py logs its device.
    device = "cuda" if torch.cuda.is_available() else "cpu"
    sys.stderr.write("[separate] loading MossFormer2_SS_16K on %s%s\n"
                     % (device, " (clearvoice has no MPS support)" if device == "cpu"
                        and torch.backends.mps.is_available() else ""))
    cv = ClearVoice(task="speech_separation", model_names=["MossFormer2_SS_16K"])
    result = {}
    for path in inputs:
        out_dir = os.path.dirname(os.path.abspath(path))
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
