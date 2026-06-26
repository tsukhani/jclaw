#!/usr/bin/env python3
"""
jclaw local video-generation sidecar (JCLAW-232 WAN / JCLAW-233 LTX).

Async by design (protocol from SV-3 / JCLAW-512): video takes minutes, so submit returns a job
handle immediately and the jclaw runner polls for state + percent, then fetches the bytes. One model
per process (the active engine, via --model), one job at a time (VRAM-bound), gated on FREE VRAM
(the SV-2 finding — a 24 GB card with 6 GB free must refuse, not OOM), with real per-step progress.

  GET  /health            -> {status, device, model, weights_present, loaded}     (fast; no torch import)
  GET  /capability        -> {kind, gpu, freeVramGb, totalVramGb, models:[{id,label,tier,runnable}...]}
  (CLI) --probe           -> same capability JSON to stdout, one-shot (no server, no model load)
  POST /jobs {prompt, num_frames?, steps?, width?, height?}
                          -> 202 {job_id, state} | 409 {busy} | 400 {insufficient_vram|unknown}
  GET  /jobs/<id>         -> {job_id, state, percent, error, output}
  GET  /jobs/<id>/result  -> mp4 bytes | 409 {not_ready}
  POST /pull              -> ndjson stream of {bytesDownloaded,totalBytes} while weights download

Weights live under --cache-dir (jclaw passes data/video-models) via the HF cache layout.
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Registry: the --model id -> HF repo, diffusers pipeline family, free-VRAM floor, default dims, and the
# jclaw provider that selects it. Mono-model per process; jclaw launches one sidecar per active engine.
MODELS = {
    "ltx":     {"repo": "Lightricks/LTX-Video",             "family": "ltx", "provider": "ltx-local", "label": "LTX-Video",       "min_gb": 8,  "comfortable_gb": 12, "width": 704, "height": 480},
    "wan-5b":  {"repo": "Wan-AI/Wan2.2-TI2V-5B-Diffusers",  "family": "wan", "provider": "wan-local", "label": "WAN 2.2 TI2V-5B",  "min_gb": 8,  "comfortable_gb": 12, "width": 832, "height": 480},
    "wan-14b": {"repo": "Wan-AI/Wan2.2-T2V-A14B-Diffusers", "family": "wan", "provider": "wan-local", "label": "WAN 2.2 T2V-A14B", "min_gb": 12, "comfortable_gb": 40, "width": 832, "height": 480},
}


def detect_vram():
    """(freeGb, totalGb, kind). FREE VRAM is what the gate + picker check (SV-2). Imports torch."""
    import torch
    if torch.cuda.is_available():
        free, total = torch.cuda.mem_get_info()
        return free / 1e9, total / 1e9, "cuda"
    if torch.backends.mps.is_available():
        total = int(subprocess.check_output(["sysctl", "-n", "hw.memsize"]))
        return total * 0.7 / 1e9, total / 1e9, "mps"  # MPS exposes no free-VRAM API; ~70% budget
    return 0.0, 0.0, "cpu"


def host_capability():
    """Adaptive picker payload (SV-2): GPU + free VRAM + EVERY engine tiered against this host, so the
    Settings dropdown can show what runs (ready / fits-but-slow) and grey out what won't. Model-independent
    (no pipeline load) — used by both `--probe` (one-shot) and the HTTP /capability endpoint."""
    free, total, kind = detect_vram()
    gpu = {"cuda": "NVIDIA (CUDA)", "mps": "Apple Silicon (unified)", "cpu": "CPU only"}[kind]
    models = []
    for mid, spec in MODELS.items():
        # WAN is NVIDIA-only (SV-2): on Apple Silicon / CPU it's not a real local option, so grey it out
        # regardless of free VRAM. LTX runs on both CUDA and MPS.
        if spec["family"] == "wan" and kind != "cuda":
            tier, reason = "no", "requires an NVIDIA (CUDA) GPU"
        elif free >= spec["comfortable_gb"]:
            tier, reason = "ready", None
        elif free >= spec["min_gb"]:
            tier, reason = "fits", "runs but slow (low free VRAM — CPU offload)"
        else:
            tier, reason = "no", f"needs {spec['min_gb']} GB free VRAM"
        models.append({"id": mid, "label": spec["label"], "provider": spec["provider"],
                       "minVramGb": spec["min_gb"], "tier": tier, "runnable": tier != "no", "reason": reason})
    return {"kind": kind, "gpu": gpu, "freeVramGb": round(free, 1), "totalVramGb": round(total, 1),
            "models": models}


class State:
    def __init__(self, model_id, cache_dir, idle_s):
        if model_id not in MODELS:
            raise SystemExit(f"unknown --model {model_id} (have {list(MODELS)})")
        self.model_id = model_id
        self.spec = MODELS[model_id]
        self.cache_dir = cache_dir
        self.idle_s = idle_s
        self.pipeline = None
        self.device = None
        self._load_lock = threading.Lock()   # serialize the one-time pipeline load
        self._gate = threading.Lock()         # protects busy/jobs for the one-job guard
        self.jobs = {}                        # job_id -> {state, percent, error, path, mime}
        self.busy = None                      # active job id, or None
        self.last_activity = time.monotonic()

    def touch(self):
        self.last_activity = time.monotonic()

    def weights_present(self):
        # Cheap on-disk heuristic so /health never imports torch/hf (which would make it slow).
        repo_dir = "models--" + self.spec["repo"].replace("/", "--")
        snaps = os.path.join(self.cache_dir, repo_dir, "snapshots")
        try:
            return os.path.isdir(snaps) and any(os.scandir(snaps))
        except OSError:
            return False

    def vram(self):
        return detect_vram()

    def load(self):
        if self.pipeline is not None:
            return self.pipeline
        with self._load_lock:
            if self.pipeline is not None:
                return self.pipeline
            import torch
            dev = "cuda" if torch.cuda.is_available() else ("mps" if torch.backends.mps.is_available() else "cpu")
            dtype = torch.bfloat16 if dev == "cuda" else torch.float16
            if self.spec["family"] == "ltx":
                from diffusers import LTXPipeline
                pipe = LTXPipeline.from_pretrained(self.spec["repo"], torch_dtype=dtype, cache_dir=self.cache_dir)
            else:
                from diffusers import DiffusionPipeline
                pipe = DiffusionPipeline.from_pretrained(self.spec["repo"], torch_dtype=dtype, cache_dir=self.cache_dir)
            # Free-VRAM-aware placement: full GPU when comfortable, else sequential offload (fits, slow).
            free, _total, kind = self.vram()
            if kind == "cuda" and free < self.spec["comfortable_gb"]:
                pipe.enable_sequential_cpu_offload()
                sys.stderr.write(f"[video-sidecar] {self.model_id}: {free:.0f}GB free < "
                                 f"{self.spec['comfortable_gb']}GB — sequential CPU offload (slower)\n")
            else:
                pipe.to(dev)
            self.device = dev
            self.pipeline = pipe
            return pipe


def run_job(state, jid, prompt, frames, steps, width, height):
    try:
        pipe = state.load()

        def on_step(_p, step, _t, kw):  # diffusers step callback -> real progress
            state.jobs[jid]["percent"] = int(round((step + 1) / steps * 100))
            state.touch()
            return kw

        kwargs = dict(prompt=prompt, num_frames=frames, num_inference_steps=steps, callback_on_step_end=on_step)
        if state.spec["family"] == "ltx":
            kwargs.update(width=width, height=height)
        frames_out = pipe(**kwargs).frames[0]
        from diffusers.utils import export_to_video
        path = os.path.join("/tmp", f"jclaw_video_{jid}.mp4")
        export_to_video(frames_out, path, fps=24)
        state.jobs[jid].update(state="succeeded", percent=100, path=path, mime="video/mp4")
    except Exception as e:  # generation failure is a FAILED job, never a sidecar crash
        state.jobs[jid].update(state="failed", error=str(e)[:400])
    finally:
        state.busy = None
        state.touch()


def _pull_stream(state, wfile):
    """Download the weights, emitting ndjson {bytesDownloaded,totalBytes} progress (mirrors Flux /pull)."""
    from huggingface_hub import snapshot_download

    repo_dir = "models--" + state.spec["repo"].replace("/", "--")
    blobs = os.path.join(state.cache_dir, repo_dir, "blobs")
    done = {"flag": False, "err": None}

    def dl():
        try:
            snapshot_download(state.spec["repo"], cache_dir=state.cache_dir)
        except Exception as e:
            done["err"] = str(e)[:300]
        finally:
            done["flag"] = True

    threading.Thread(target=dl, daemon=True).start()
    while not done["flag"]:
        total = 0
        if os.path.isdir(blobs):
            for e in os.scandir(blobs):
                try:
                    total += e.stat().st_size
                except OSError:
                    pass
        wfile.write((json.dumps({"bytesDownloaded": total, "totalBytes": 0}) + "\n").encode())
        wfile.flush()
        state.touch()
        time.sleep(1.0)
    wfile.write((json.dumps({"done": True, "error": done["err"]}) + "\n").encode())
    wfile.flush()


class Handler(BaseHTTPRequestHandler):
    def __init__(self, *a, state=None, **k):
        self.state = state
        super().__init__(*a, **k)

    def log_message(self, *a):  # quiet
        pass

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        s = self.state
        path = self.path.split("?")[0]
        if path == "/health":
            self._json(200, {"status": "ok", "model": s.model_id, "device": s.device,
                             "weights_present": s.weights_present(), "loaded": s.pipeline is not None})
        elif path == "/capability":
            cap = host_capability()  # full adaptive picker payload (all engines tiered for this host)
            cap["activeModel"] = s.model_id
            self._json(200, cap)
        elif path.startswith("/jobs/") and path.endswith("/result"):
            jid = path[len("/jobs/"):-len("/result")]
            j = s.jobs.get(jid)
            if not j or j["state"] != "succeeded":
                self._json(409, {"error": "not_ready", "state": j["state"] if j else "unknown"})
                return
            data = open(j["path"], "rb").read()
            self.send_response(200)
            self.send_header("Content-Type", j["mime"])
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            s.touch()
        elif path.startswith("/jobs/"):
            jid = path[len("/jobs/"):]
            j = s.jobs.get(jid)
            if not j:
                self._json(404, {"error": "unknown_job"})
                return
            out = {"path": j["path"], "mimeType": j["mime"]} if j["state"] == "succeeded" else None
            self._json(200, {"job_id": jid, "state": j["state"], "percent": j.get("percent"),
                             "error": j.get("error"), "output": out})
        else:
            self._json(404, {"error": "not_found"})

    def do_POST(self):
        s = self.state
        path = self.path.split("?")[0]
        if path == "/pull":
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.end_headers()
            _pull_stream(s, self.wfile)
            return
        if path != "/jobs":
            self._json(404, {"error": "not_found"})
            return
        n = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        free, _total, _kind = s.vram()
        if free < s.spec["min_gb"]:  # SV-2 free-VRAM gate -> jclaw FAILs the job -> cloud fallback
            self._json(400, {"error": "insufficient_vram", "freeVramGb": round(free, 1),
                             "requiredGb": s.spec["min_gb"]})
            return
        with s._gate:
            if s.busy:  # one job at a time (VRAM-bound)
                self._json(409, {"error": "busy", "active_job_id": s.busy})
                return
            jid = uuid.uuid4().hex[:12]
            s.jobs[jid] = {"state": "running", "percent": 0, "error": None, "path": None, "mime": None}
            s.busy = jid
        s.touch()
        threading.Thread(target=run_job, args=(
            s, jid, body.get("prompt", ""), int(body.get("num_frames", 49)),
            int(body.get("steps", 30)), int(body.get("width", s.spec["width"])),
            int(body.get("height", s.spec["height"])), ), daemon=True).start()
        self._json(202, {"job_id": jid, "state": "running"})


def _idle_watcher(state):
    """Self-evict after the idle timeout so the daemon releases the GPU/VRAM (mirrors Flux)."""
    if state.idle_s <= 0:
        return
    while True:
        time.sleep(min(60.0, state.idle_s))
        if state.busy is None and time.monotonic() - state.last_activity >= state.idle_s:
            sys.stderr.write(f"[video-sidecar] idle {state.idle_s:.0f}s — exiting to free the GPU\n")
            os._exit(0)


def main():
    ap = argparse.ArgumentParser(description="jclaw local video-generation sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9528)
    ap.add_argument("--model", help="engine id: " + " | ".join(MODELS))
    ap.add_argument("--cache-dir")
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    # One-shot adaptive probe (SV-2): detect GPU + free VRAM, print the tiered engine list, exit. No server,
    # no model load — this is what the Settings "detect capability" flow runs to populate the dropdown.
    ap.add_argument("--probe", action="store_true")
    a = ap.parse_args()
    if a.probe:
        print(json.dumps(host_capability()))
        return
    if not a.model or not a.cache_dir:
        ap.error("--model and --cache-dir are required unless --probe is given")
    os.environ.setdefault("HF_HOME", a.cache_dir)
    state = State(a.model, a.cache_dir, a.idle_timeout_min * 60.0)
    threading.Thread(target=_idle_watcher, args=(state,), daemon=True).start()

    def make(*args, **kw):
        return Handler(*args, state=state, **kw)

    srv = ThreadingHTTPServer((a.host, a.port), make)
    sys.stderr.write(f"[video-sidecar] {a.model} listening on {a.host}:{a.port} (cache={a.cache_dir})\n")
    sys.stderr.flush()
    srv.serve_forever()


if __name__ == "__main__":
    main()
