#!/usr/bin/env python3
"""Flux 2 Klein local image-generation sidecar for jclaw (JCLAW-226).

A long-running localhost HTTP daemon, launched on demand by the jclaw JVM
(LocalFluxSidecarManager) and holding the diffusers pipeline resident in
GPU/unified memory between calls. The shape and protocol were chosen in the
JCLAW-509 spike: a localhost HTTP daemon beats a per-request subprocess
(which would re-pay the multi-second model load on every image) and a model
server like torchserve (too heavy for a single-user Personal Edition).

Protocol (bound to 127.0.0.1 only):
  GET  /health  -> 200 {status, device, dtype, model, weights_present, loaded}
  POST /generate {prompt, width?, height?, steps?, seed?}
        -> 200 image/png  (raw bytes)
        -> 409 {error}     when weights are not present (call /pull first)
        -> 500 {error}     on generation failure
  POST /pull
        -> 200 application/x-ndjson, one JSON object per line:
           {"status":"downloading","bytesDownloaded":N,"totalBytes":M}
           ... terminated by {"status":"done",...} or {"status":"error","error":...}

Device/dtype (JCLAW-509 addenda): mps->float16 (+ PYTORCH_ENABLE_MPS_FALLBACK),
cuda->bfloat16, cpu->float32 (slow; logged as a warning). The JVM cannot see
CUDA/MPS, so the chosen device is reported here via /health.

Weights live under the --cache-dir (jclaw passes data/flux-models) via HF_HOME,
matching jclaw's data/ runtime-artifact convention (whisper-models, lucene).
"""

import argparse
import io
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Enable CPU fallback for the handful of ops not yet implemented on MPS; without
# this, generation hard-crashes on Apple Silicon (JCLAW-509 Apple-Silicon addendum).
# Must be set before torch is imported, so it lives at module top.
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")


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
        out the JVM's health probe. Mirrors the Java FluxModelManager.availableLocally
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
                    "Flux weights for '%s' are not present — POST /pull first" % self.model)
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
                    "[flux-sidecar] WARNING: no GPU backend (MPS/CUDA) — running on CPU; "
                    "generation will be slow\n")
            pipe = AutoPipelineForText2Image.from_pretrained(
                self.model, torch_dtype=dtype, cache_dir=self.cache_dir)
            pipe = pipe.to(device)
            self.pipeline = pipe
            self.device = device
            self.dtype = str(dtype).replace("torch.", "")
            sys.stderr.write("[flux-sidecar] pipeline loaded on %s (%s)\n" % (device, self.dtype))
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
    """Generator yielding ndjson progress bytes for POST /pull. Downloads every
    sibling file in the repo (what snapshot_download does) but file-by-file so we
    can emit byte-granular progress for the Settings UI. hf_hub_download handles
    caching + integrity (etag/sha) per file."""
    from huggingface_hub import HfApi, hf_hub_download

    def line(obj):
        return (json.dumps(obj) + "\n").encode("utf-8")

    try:
        info = HfApi().model_info(state.model, files_metadata=True)
        files = [(s.rfilename, s.size or 0) for s in info.siblings]
        total = sum(sz for _, sz in files)
        done = 0
        yield line({"status": "downloading", "bytesDownloaded": 0, "totalBytes": total})
        for name, sz in files:
            hf_hub_download(state.model, name, cache_dir=state.cache_dir)
            done += sz
            yield line({"status": "downloading", "bytesDownloaded": done, "totalBytes": total})
        yield line({"status": "done", "bytesDownloaded": total, "totalBytes": total})
    except Exception as e:
        yield line({"status": "error", "error": str(e)})


class Handler(BaseHTTPRequestHandler):
    state: SidecarState = None  # set on the class before the server starts

    # Quieter logging: BaseHTTPRequestHandler logs every request to stderr by
    # default; route it through one prefix so jclaw's stderr drainer can tag it.
    def log_message(self, fmt, *args):
        sys.stderr.write("[flux-sidecar] " + (fmt % args) + "\n")

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
        if self.path.split("?")[0] != "/health":
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
        try:
            pipe = self.state.ensure_pipeline()
        except FileNotFoundError as e:
            self._send_json(409, {"error": str(e)})
            return
        except Exception as e:
            self._send_json(500, {"error": "pipeline load failed: %s" % e})
            return
        try:
            kwargs = {"prompt": prompt, "width": width, "height": height,
                      "num_inference_steps": steps}
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
            sys.stderr.write("[flux-sidecar] idle for %.0fs — exiting to free the GPU\n"
                             % state.idle_timeout_s)
            # os._exit avoids hanging on torch/CUDA background threads at shutdown.
            os._exit(0)


def main():
    ap = argparse.ArgumentParser(description="jclaw Flux 2 Klein image sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--model", required=True, help="Hugging Face repo id")
    ap.add_argument("--cache-dir", default=os.path.join("data", "flux-models"))
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    args = ap.parse_args()

    cache_dir = os.path.abspath(args.cache_dir)
    os.makedirs(cache_dir, exist_ok=True)
    # Point Hugging Face at jclaw's data dir so weights land under data/flux-models.
    os.environ.setdefault("HF_HOME", cache_dir)

    Handler.state = SidecarState(args.model, cache_dir, args.idle_timeout_min * 60.0)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(
        target=_idle_watcher, args=(Handler.state, server), daemon=True).start()
    sys.stderr.write("[flux-sidecar] listening on http://%s:%d (model=%s)\n"
                     % (args.host, args.port, args.model))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
