#!/usr/bin/env python3
"""Local image-generation sidecar for jclaw, default engine Flux 2 Klein (JCLAW-226).

A long-running localhost HTTP daemon, launched on demand by the jclaw JVM
(LocalImageSidecarManager) and holding the diffusers pipeline resident in
GPU/unified memory between calls. The shape and protocol were chosen in the
JCLAW-509 spike: a localhost HTTP daemon beats a per-request subprocess
(which would re-pay the multi-second model load on every image) and a model
server like torchserve (too heavy for a single-user Personal Edition).

Protocol (bound to 127.0.0.1 only):
  GET  /health  -> 200 {status, device, dtype, model, weights_present, loaded}
  GET  /capability -> 200 {kind, gpu, freeVramGb, totalVramGb, runnable, tier, reason}
  GET  /progress-> 200 {percent}   (0..100 while generating, null when idle)
  (CLI) --probe -> same capability JSON to stdout, one-shot (no server, no model load)
  POST /generate {prompt, width?, height?, steps?, seed?}
        -> 200 image/png  (raw bytes)
        -> 409 {error}     when weights are not present (call /pull first),
                           OR when a generation is already in progress (one at a time)
        -> 500 {error}     on generation failure
  POST /pull
        -> 200 application/x-ndjson, one JSON object per line:
           {"status":"downloading","bytesDownloaded":N,"totalBytes":M}
           ... terminated by {"status":"done",...} or {"status":"error","error":...}

Device/dtype (JCLAW-509 addenda): mps->float16 (+ PYTORCH_ENABLE_MPS_FALLBACK),
cuda->bfloat16, cpu->float32 (slow; logged as a warning). The JVM cannot see
CUDA/MPS, so the chosen device is reported here via /health.

Weights live under the --cache-dir (jclaw passes data/image-models) via HF_HOME,
matching jclaw's data/ runtime-artifact convention (asr-models, lucene).
"""

import argparse
import fnmatch
import io
import json
import os
import platform
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Enable CPU fallback for the handful of ops not yet implemented on MPS; without
# this, generation hard-crashes on Apple Silicon (JCLAW-509 Apple-Silicon addendum).
# Must be set before torch is imported, so it lives at module top.
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

# Repo files the diffusers AutoPipeline does NOT use, skipped on download (JCLAW-226).
# model_index.json declares Flux2KleinPipeline, which loads only the subfolders
# (transformer/, vae/, text_encoder/, tokenizer/, scheduler/) — the top-level
# single-file checkpoint (~7.7 GB) is the original/ComfyUI format and the *.jpg are
# example images, so skipping them cuts ~33% off the ~23.7 GB download.
IGNORE_PATTERNS = ["flux-2-klein-4b.safetensors", "*.jpg"]

IS_APPLE_SILICON = platform.system() == "Darwin" and platform.machine() == "arm64"

# Free-VRAM floors for Flux 2 Klein 4B (fp16): ~8 GB transformer + text encoder/VAE/activations. Estimates
# to revisit from real runs; mirrors the video sidecar's per-engine min/comfortable tiers (JCLAW-232/233).
FLUX_MIN_GB = 10
FLUX_COMFORTABLE_GB = 14


def detect_vram():
    """(freeGb, totalGb, kind). FREE VRAM is what gates local generation. Torch-free on Apple Silicon
    (unified memory via sysctl); torch only on the CUDA path. Mirrors the video sidecar's detector."""
    if IS_APPLE_SILICON:
        total = int(subprocess.check_output(["sysctl", "-n", "hw.memsize"]))
        return total * 0.7 / 1e9, total / 1e9, "mps"  # no free-VRAM API on unified memory; ~70% budget
    try:
        import torch
        if torch.cuda.is_available():
            free, total = torch.cuda.mem_get_info()
            return free / 1e9, total / 1e9, "cuda"
        if torch.backends.mps.is_available():
            total = int(subprocess.check_output(["sysctl", "-n", "hw.memsize"]))
            return total * 0.7 / 1e9, total / 1e9, "mps"
    except ImportError:
        pass
    return 0.0, 0.0, "cpu"


def host_capability():
    """GPU + free VRAM + whether THIS machine can run local Flux. Model-independent (no pipeline load) —
    used by both `--probe` (one-shot) and the HTTP /capability endpoint. CPU is treated as not runnable:
    Flux on CPU is too slow to be a real local option, so the Settings radio greys out."""
    free, total, kind = detect_vram()
    gpu = {"cuda": "NVIDIA (CUDA)", "mps": "Apple Silicon (unified)", "cpu": "CPU only"}[kind]
    if kind == "cpu":
        tier, reason = "no", "no GPU detected (CPU is too slow for Flux)"
    elif free >= FLUX_COMFORTABLE_GB:
        tier, reason = "ready", None
    elif free >= FLUX_MIN_GB:
        tier, reason = "fits", "runs but slow (low free VRAM)"
    else:
        tier, reason = "no", f"needs {FLUX_MIN_GB} GB free VRAM"
    return {"kind": kind, "gpu": gpu, "freeVramGb": round(free, 1), "totalVramGb": round(total, 1),
            "minVramGb": FLUX_MIN_GB, "tier": tier, "runnable": tier != "no", "reason": reason}


class SidecarState:
    """Holds the configured model, lazily-loaded pipeline, and activity clock."""

    def __init__(self, model: str, cache_dir: str, idle_timeout_s: float):
        self.model = model
        self.cache_dir = cache_dir
        self.idle_timeout_s = idle_timeout_s
        self.pipeline = None          # diffusers pipeline, loaded on first /generate
        self.device = None            # "mps" | "cuda" | "cpu"
        self.dtype = None             # torch dtype name, e.g. "float16"
        self.load_lock = threading.Lock()
        self.last_activity = time.monotonic()
        # One generation at a time (ThreadingHTTPServer would otherwise drive the single resident
        # pipeline from two threads and corrupt its sampler state). gen_lock gates /generate; gen_percent
        # is the live 0..100 step progress (None when idle), surfaced by GET /progress for the chat bar.
        self.gen_lock = threading.Lock()
        self.gen_percent = None

    def touch(self):
        self.last_activity = time.monotonic()

    def detect_device(self):
        """Pick the best backend and a matching dtype. The JVM can't see this,
        so /health reports it back to jclaw for the Settings UI + logs."""
        import torch
        if torch.backends.mps.is_available():
            return "mps", torch.float16
        if torch.cuda.is_available():
            return "cuda", torch.bfloat16
        return "cpu", torch.float32

    def weights_present(self) -> bool:
        """True when the model snapshot exists locally. Pure filesystem check (no
        huggingface_hub import) so /health stays fast and never blocks on a slow
        first import — importing hf/torch here made /health take seconds and time
        out the JVM's health probe. Mirrors the Java ImageModelManager.availableLocally
        heuristic: a non-empty snapshots dir under the HF cache layout."""
        repo_dir = "models--" + self.model.replace("/", "--")
        snapshots = os.path.join(self.cache_dir, repo_dir, "snapshots")
        if not os.path.isdir(snapshots):
            return False
        with os.scandir(snapshots) as it:
            return any(True for _ in it)

    def ensure_pipeline(self):
        """Lazily load + cache the diffusers pipeline. Raises FileNotFoundError
        when weights aren't present (mapped to HTTP 409 by the handler)."""
        if self.pipeline is not None:
            return self.pipeline
        with self.load_lock:
            if self.pipeline is not None:
                return self.pipeline
            if not self.weights_present():
                raise FileNotFoundError(
                    "model weights for '%s' are not present — POST /pull first" % self.model)
            import torch  # noqa: F401  (ensures torch is importable before diffusers)
            # AutoPipeline reads the repo's model_index.json and instantiates the
            # right pipeline class, so the sidecar doesn't hard-code a class name
            # that may differ across diffusers/FLUX.2 versions. NOTE (JCLAW-226
            # open item): the exact pipeline class + min diffusers/torch versions
            # for FLUX.2 klein on MPS must be confirmed against the HF model card.
            from diffusers import AutoPipelineForText2Image
            device, dtype = self.detect_device()
            if device == "cpu":
                sys.stderr.write(
                    "[image-sidecar] WARNING: no GPU backend (MPS/CUDA) — running on CPU; "
                    "generation will be slow\n")
            pipe = AutoPipelineForText2Image.from_pretrained(
                self.model, torch_dtype=dtype, cache_dir=self.cache_dir)
            pipe = pipe.to(device)
            self.pipeline = pipe
            self.device = device
            self.dtype = str(dtype).replace("torch.", "")
            sys.stderr.write("[image-sidecar] pipeline loaded on %s (%s)\n" % (device, self.dtype))
            return self.pipeline

    def current_device(self):
        """Device/dtype for /health WITHOUT importing torch. Reports the real values
        only once the pipeline has been loaded (on first /generate, which sets
        self.device). Importing torch here would make the first /health take several
        seconds — long enough to time out the JVM's health probe and spam BrokenPipe
        tracebacks — so device is reported as 'unknown' until the model is loaded."""
        if self.device is not None:
            return self.device, self.dtype
        return "unknown", "unknown"


def _pull_stream(state: SidecarState):
    """Generator yielding ndjson progress bytes for POST /pull.

    The download runs on a background thread (snapshot_download, which uses the
    fast xet backend); this generator polls the on-disk size of the HF cache's
    blobs/ dir once a second and reports it against the repo total from
    model_info. Disk-polling gives smooth BYTE-granular progress — a per-file
    scheme sits at 0% for the entire first shard (the klein safetensors are
    ~7.7 GB each), which reads as a stuck download."""
    from huggingface_hub import HfApi, snapshot_download

    def line(obj):
        return (json.dumps(obj) + "\n").encode("utf-8")

    def ignored(name):
        return any(fnmatch.fnmatch(name, p) for p in IGNORE_PATTERNS)

    try:
        info = HfApi().model_info(state.model, files_metadata=True)
        # Total must reflect the FILTERED file set, or the bar would top out below
        # 100% (we skip ~7.7 GB) and never reach done.
        total = sum((s.size or 0) for s in info.siblings if not ignored(s.rfilename))
    except Exception as e:
        yield line({"status": "error", "error": "metadata fetch failed: %s" % e})
        return

    blobs = os.path.join(
        state.cache_dir, "models--" + state.model.replace("/", "--"), "blobs")

    def disk_bytes():
        if not os.path.isdir(blobs):
            return 0
        tot = 0
        with os.scandir(blobs) as it:
            for entry in it:
                try:
                    tot += entry.stat(follow_symlinks=False).st_size
                except OSError:
                    pass
        return tot

    outcome = {"done": False, "error": None}

    def run_download():
        try:
            snapshot_download(state.model, cache_dir=state.cache_dir,
                              ignore_patterns=IGNORE_PATTERNS)
        except Exception as e:
            outcome["error"] = str(e)
        finally:
            outcome["done"] = True

    threading.Thread(target=run_download, name="image-snapshot", daemon=True).start()
    yield line({"status": "downloading", "bytesDownloaded": min(disk_bytes(), total), "totalBytes": total})
    while not outcome["done"]:
        time.sleep(1.0)
        yield line({"status": "downloading", "bytesDownloaded": min(disk_bytes(), total), "totalBytes": total})
    if outcome["error"]:
        yield line({"status": "error", "error": outcome["error"]})
    else:
        yield line({"status": "done", "bytesDownloaded": total, "totalBytes": total})


class Handler(BaseHTTPRequestHandler):
    state: SidecarState = None  # set on the class before the server starts

    # Quieter logging: BaseHTTPRequestHandler logs every request to stderr by
    # default; route it through one prefix so jclaw's stderr drainer can tag it.
    def log_message(self, fmt, *args):
        sys.stderr.write("[image-sidecar] " + (fmt % args) + "\n")

    # Swallow client-disconnect errors so they don't spam stderr with tracebacks.
    # The JVM health probe closes the socket as soon as it has the response, which
    # can race our write; that's benign, not an error worth a traceback.
    def handle(self):
        try:
            super().handle()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def finish(self):
        try:
            super().finish()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _send_json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        return json.loads(raw.decode("utf-8")) if raw else {}

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/capability":
            # GPU + free-VRAM verdict for the Settings "can this machine run local Flux?" gate. Pure host
            # detection (no model load); also runnable one-shot via --probe.
            self._send_json(200, host_capability())
            return
        if path == "/progress":
            # Live step progress for the chat's image-gen bar. Deliberately does NOT touch() — it's a
            # read-only status poll; the in-flight generation keeps the idle watcher at bay on its own.
            self._send_json(200, {"percent": self.state.gen_percent})
            return
        if path != "/health":
            self._send_json(404, {"error": "not found"})
            return
        self.state.touch()
        device, dtype = self.state.current_device()
        self._send_json(200, {
            "status": "ok",
            "device": device,
            "dtype": dtype,
            "model": self.state.model,
            "weights_present": self.state.weights_present(),
            "loaded": self.state.pipeline is not None,
        })

    def do_POST(self):
        path = self.path.split("?")[0]
        self.state.touch()
        if path == "/generate":
            self._handle_generate()
        elif path == "/pull":
            self._handle_pull()
        else:
            self._send_json(404, {"error": "not found"})

    def _handle_generate(self):
        try:
            body = self._read_json()
        except (ValueError, json.JSONDecodeError) as e:
            self._send_json(400, {"error": "invalid JSON body: %s" % e})
            return
        prompt = (body.get("prompt") or "").strip()
        if not prompt:
            self._send_json(400, {"error": "prompt is required"})
            return
        width = int(body.get("width") or 1024)
        height = int(body.get("height") or 1024)
        # klein is step-distilled to ~4 steps — the default that keeps MPS tolerable.
        steps = int(body.get("steps") or 4)
        seed = body.get("seed")
        # One generation at a time. The resident pipeline isn't safe to drive concurrently, and the
        # shared %-progress assumes a single in-flight gen. A second caller gets 409 (the Java client
        # surfaces it the same way the video sidecar's busy gate does).
        if not self.state.gen_lock.acquire(blocking=False):
            self._send_json(409, {"error": "a generation is already in progress"})
            return
        try:
            self.state.gen_percent = 0  # bar shows "starting" through the (one-time) pipeline load
            try:
                pipe = self.state.ensure_pipeline()
            except FileNotFoundError as e:
                self._send_json(409, {"error": str(e)})
                return
            except Exception as e:
                self._send_json(500, {"error": "pipeline load failed: %s" % e})
                return
            try:
                def on_step(_p, step, _t, kw):  # diffusers step callback -> live percent for /progress
                    self.state.gen_percent = int(round((step + 1) / steps * 100))
                    self.state.touch()
                    return kw

                kwargs = {"prompt": prompt, "width": width, "height": height,
                          "num_inference_steps": steps, "callback_on_step_end": on_step}
                if seed is not None:
                    import torch
                    kwargs["generator"] = torch.Generator(
                        device=self.state.device).manual_seed(int(seed))
                image = pipe(**kwargs).images[0]
                buf = io.BytesIO()
                image.save(buf, format="PNG")
                data = buf.getvalue()
            except Exception as e:
                self._send_json(500, {"error": "generation failed: %s" % e})
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        finally:
            self.state.gen_percent = None
            self.state.gen_lock.release()

    def _handle_pull(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.end_headers()
        for chunk in _pull_stream(self.state):
            self.wfile.write(chunk)
            self.wfile.flush()
            self.state.touch()  # a long download must not trip the idle watcher


def _idle_watcher(state: SidecarState, server: ThreadingHTTPServer):
    """Self-evict after the idle timeout so the daemon releases the GPU/VRAM
    when unused (JCLAW-509 memory-residency tradeoff). 0 disables eviction."""
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(min(60.0, state.idle_timeout_s))
        if time.monotonic() - state.last_activity >= state.idle_timeout_s:
            sys.stderr.write("[image-sidecar] idle for %.0fs — exiting to free the GPU\n"
                             % state.idle_timeout_s)
            # os._exit avoids hanging on torch/CUDA background threads at shutdown.
            os._exit(0)


def main():
    ap = argparse.ArgumentParser(description="jclaw image sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int)
    ap.add_argument("--model", help="Hugging Face repo id")
    ap.add_argument("--cache-dir", default=os.path.join("data", "image-models"))
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    # One-shot capability probe: detect GPU + free VRAM, print the verdict, exit. No server, no model
    # load — what the Settings "detect GPU" flow runs to decide whether local Flux is offered.
    ap.add_argument("--probe", action="store_true")
    args = ap.parse_args()
    if args.probe:
        print(json.dumps(host_capability()))
        return
    if args.port is None or not args.model:
        ap.error("--port and --model are required unless --probe is given")

    cache_dir = os.path.abspath(args.cache_dir)
    os.makedirs(cache_dir, exist_ok=True)
    # Point Hugging Face at jclaw's data dir so weights land under data/image-models.
    os.environ.setdefault("HF_HOME", cache_dir)

    Handler.state = SidecarState(args.model, cache_dir, args.idle_timeout_min * 60.0)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(
        target=_idle_watcher, args=(Handler.state, server), daemon=True).start()
    sys.stderr.write("[image-sidecar] listening on http://%s:%d (model=%s)\n"
                     % (args.host, args.port, args.model))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
