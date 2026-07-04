#!/usr/bin/env python3
"""Local speaker-diarization sidecar for jclaw — pyannote community-1 (JCLAW-565).

A long-running localhost HTTP daemon, launched on demand by the jclaw JVM
(PyannoteSidecarManager) and holding the pyannote pipeline resident between
calls (model load is seconds; the pipeline itself diarizes ~18x realtime on
Apple MPS). Chosen over the in-process sherpa-onnx path by the JCLAW-565
bake-off: on real 2-speaker podcast audio the community-1 pipeline scored
DER 12.5% / pairwise F1 0.864 with the speaker count found automatically,
where sherpa's threshold clustering capped at F1 0.799 and over- or
under-split depending on a knife-edge threshold. sherpa remains jclaw's
zero-setup fallback; this sidecar is opt-in via uv + a Hugging Face token.

Attribution: pyannote community-1 model (c) pyannoteAI, released under
CC-BY-4.0 (https://huggingface.co/pyannote/speaker-diarization-community-1);
pyannote.audio library MIT. The model is gated — the operator must accept
the conditions on the model page and configure a HF token in jclaw Settings
(passed to this process as HF_TOKEN).

Protocol (bound to 127.0.0.1 only):
  GET  /health  -> 200 {status, device, model, loaded}
  POST /diarize {audio_path, num_speakers?}
        -> 200 {segments: [{start, end, speaker}, ...], device, seconds}
           (speaker is a zero-based int index, times in seconds — the same
            shape jclaw's SherpaDiarizer produces)
        -> 400 {error}   audio_path missing/unreadable
        -> 409 {error}   a diarization is already in progress (one at a time)
        -> 500 {error}   pipeline load or inference failure (e.g. gated model
                         without an accepted license / valid HF_TOKEN)

The audio file is passed by path, not uploaded: both processes run on the
same host and jclaw's attachments are already on disk — no reason to stream
tens of MB through a localhost socket.

Weights cache under --cache-dir via HF_HOME, matching jclaw's data/
runtime-artifact convention (whisper-models, diarization-models, ...).
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# CPU fallback for ops not yet implemented on MPS — must precede torch import.
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

DEFAULT_MODEL = "pyannote/speaker-diarization-community-1"


def pick_device():
    import torch
    if torch.cuda.is_available():
        return "cuda"
    if torch.backends.mps.is_available():
        return "mps"
    return "cpu"


class SidecarState:
    def __init__(self, model: str, idle_timeout_s: float):
        self.model = model
        self.idle_timeout_s = idle_timeout_s
        self.last_activity = time.monotonic()
        self.pipeline = None
        self.device = None
        self.load_error = None
        self.run_lock = threading.Lock()

    def touch(self):
        self.last_activity = time.monotonic()

    def ensure_pipeline(self):
        """Lazy one-time pipeline load; raises on failure (cached so repeated
        calls fail fast instead of re-downloading)."""
        if self.pipeline is not None:
            return self.pipeline
        if self.load_error is not None:
            raise RuntimeError(self.load_error)
        try:
            import torch
            from pyannote.audio import Pipeline
            sys.stderr.write("[diarize-sidecar] loading %s\n" % self.model)
            pipeline = Pipeline.from_pretrained(self.model)
            if pipeline is None:
                raise RuntimeError(
                    "Pipeline.from_pretrained returned None — the model is gated: accept the "
                    "conditions at https://huggingface.co/%s and configure a valid HF token"
                    % self.model)
            self.device = pick_device()
            if self.device != "cpu":
                pipeline.to(torch.device(self.device))
            self.pipeline = pipeline
            sys.stderr.write("[diarize-sidecar] pipeline ready on %s\n" % self.device)
            return pipeline
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self.load_error = "failed to load %s: %s" % (self.model, e)
            raise


class Handler(BaseHTTPRequestHandler):
    state: SidecarState = None

    # ---- plumbing -------------------------------------------------------
    def log_message(self, fmt, *args):
        sys.stderr.write("[diarize-sidecar] %s\n" % (fmt % args))

    def _send_json(self, code: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def handle(self):
        try:
            super().handle()
        except BrokenPipeError:
            pass  # client went away mid-response — not an error worth a traceback

    # ---- endpoints ------------------------------------------------------
    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {
                "status": "ok",
                "device": self.state.device,
                "model": self.state.model,
                "loaded": self.state.pipeline is not None,
            })
        else:
            self._send_json(404, {"error": "unknown path %s" % self.path})

    def do_POST(self):
        if self.path != "/diarize":
            self._send_json(404, {"error": "unknown path %s" % self.path})
            return
        self.state.touch()
        try:
            req = self._read_json()
        except (ValueError, UnicodeDecodeError) as e:
            self._send_json(400, {"error": "invalid JSON body: %s" % e})
            return
        audio_path = req.get("audio_path")
        if not audio_path or not os.path.isfile(audio_path):
            self._send_json(400, {"error": "audio_path missing or not a file: %r" % audio_path})
            return
        num_speakers = req.get("num_speakers")

        # One diarization at a time — the pipeline mutates device memory and a
        # second concurrent run would thrash the GPU. Callers queue in the JVM.
        if not self.state.run_lock.acquire(blocking=False):
            self._send_json(409, {"error": "a diarization is already in progress"})
            return
        try:
            pipeline = self.state.ensure_pipeline()
            t0 = time.time()
            kwargs = {"num_speakers": int(num_speakers)} if num_speakers else {}
            result = pipeline(audio_path, **kwargs)
            # pyannote.audio 4 returns a result object wrapping the annotation;
            # 3.x returned the annotation itself. Support both.
            annotation = getattr(result, "speaker_diarization", result)
            labels = {}
            segments = []
            for turn, _, speaker in annotation.itertracks(yield_label=True):
                idx = labels.setdefault(speaker, len(labels))
                segments.append({"start": round(turn.start, 3),
                                 "end": round(turn.end, 3),
                                 "speaker": idx})
            seconds = time.time() - t0
            sys.stderr.write("[diarize-sidecar] %s: %d segments, %d speaker(s) in %.1fs\n"
                             % (os.path.basename(audio_path), len(segments), len(labels), seconds))
            self._send_json(200, {"segments": segments,
                                  "device": self.state.device,
                                  "seconds": round(seconds, 1)})
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(e)})
        finally:
            self.state.touch()
            self.state.run_lock.release()


def _idle_watcher(state: SidecarState):
    """Self-evict after the idle timeout so the daemon releases device memory
    when unused. 0 disables eviction."""
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(min(60.0, state.idle_timeout_s))
        if time.monotonic() - state.last_activity >= state.idle_timeout_s:
            sys.stderr.write("[diarize-sidecar] idle for %.0fs — exiting\n" % state.idle_timeout_s)
            os._exit(0)


def main():
    ap = argparse.ArgumentParser(description="jclaw diarization sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--cache-dir", default=os.path.join("data", "pyannote-models"))
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    args = ap.parse_args()

    cache_dir = os.path.abspath(args.cache_dir)
    os.makedirs(cache_dir, exist_ok=True)
    # Point Hugging Face at jclaw's data dir so weights land under data/.
    os.environ.setdefault("HF_HOME", cache_dir)

    Handler.state = SidecarState(args.model, args.idle_timeout_min * 60.0)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(target=_idle_watcher, args=(Handler.state,), daemon=True).start()
    sys.stderr.write("[diarize-sidecar] listening on http://%s:%d (model=%s)\n"
                     % (args.host, args.port, args.model))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
