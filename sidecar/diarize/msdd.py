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

usage (one-shot):  uv run msdd.py <audio.wav> <num_speakers>
usage (worker):    uv run msdd.py --worker
  worker protocol: one request per line on stdin: {"audio": path, "num_speakers": n}
  one response per line on stdout: {"segments": [...]} or {"error": "..."}
  The model loads ONCE (JCLAW-646: the per-call NeMo import + checkpoint
  load cost ~80s of the measured 300s); serve.py keeps the worker alive.
"""
import json
import os
import sys

# JCLAW-648: MSDD runs on Metal. Two ingredients: per-op CPU fallback for
# the handful of ops MPS lacks, and a one-line upstream patch below.
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")


def _patch_mps_stride_quirk():
    """NeMo's MSDD decoder calls .view() on a conv output that is
    non-contiguous on MPS (contiguous on CUDA by coincidence of op
    implementations). reshape() is semantically identical and copies only
    when required. Measured on the debate benchmark: MPS output is
    numerically equivalent to CPU (identical per-turn activity to 2dp);
    inference 9.2s vs 225s CPU on an M4."""
    import torch
    import torch.nn.functional as F
    from nemo.collections.asr.modules import msdd_diarizer as _md

    def conv_scale_weights(self, ms_avg_embs_perm, ms_emb_seq_single):
        ms_cnn_input_seq = torch.cat([ms_avg_embs_perm, ms_emb_seq_single], dim=2)
        ms_cnn_input_seq = ms_cnn_input_seq.unsqueeze(2).flatten(0, 1)
        conv_out = self.conv_forward(
            ms_cnn_input_seq, conv_module=self.conv[0], bn_module=self.conv_bn[0],
            first_layer=True)
        for conv_idx in range(1, self.conv_repeat + 1):
            conv_out = self.conv_forward(
                conv_input=conv_out, conv_module=self.conv[conv_idx],
                bn_module=self.conv_bn[conv_idx], first_layer=False)
        lin_input_seq = conv_out.reshape(
            self.batch_size, self.length, self.cnn_output_ch * self.emb_dim)
        hidden_seq = self.conv_to_linear(lin_input_seq)
        hidden_seq = self.dropout(F.leaky_relu(hidden_seq))
        scale_weights = self.softmax(self.linear_to_weights(hidden_seq))
        return scale_weights.unsqueeze(3).expand(-1, -1, -1, self.num_spks)

    _md.MSDD_module.conv_scale_weights = conv_scale_weights


def _load():
    import torch
    from nemo.collections.asr.models.msdd_models import NeuralDiarizer
    if torch.cuda.is_available():
        device = "cuda"
    elif torch.backends.mps.is_available():
        device = "mps"
        _patch_mps_stride_quirk()
    else:
        device = "cpu"
    sys.stderr.write("[msdd] loading diar_msdd_telephonic on %s\n" % device)
    diarizer = NeuralDiarizer.from_pretrained("diar_msdd_telephonic")
    return diarizer.to(device)


def _infer(diarizer, audio, num_speakers):
    annotation = diarizer(audio, num_speakers=num_speakers,
                          max_speakers=max(2, num_speakers), num_workers=0)
    return [{"start": round(seg.start, 3), "end": round(seg.end, 3),
             "speaker": int(label.split("_")[-1])}
            for seg, _, label in annotation.itertracks(yield_label=True)]


def worker():
    diarizer = _load()
    sys.stderr.write("[msdd] worker ready\n")
    sys.stderr.flush()
    print(json.dumps({"ready": True}), flush=True)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            segments = _infer(diarizer, req["audio"], int(req["num_speakers"]))
            print(json.dumps({"segments": segments}), flush=True)
        except Exception as e:  # noqa: BLE001 — reported per-request
            print(json.dumps({"error": str(e)}), flush=True)
    return 0


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--worker":
        return worker()
    audio, num_speakers = sys.argv[1], int(sys.argv[2])
    diarizer = _load()
    print(json.dumps({"segments": _infer(diarizer, audio, num_speakers)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
