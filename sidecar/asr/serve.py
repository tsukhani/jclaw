#!/usr/bin/env python3
"""Local ASR sidecar for jclaw (JCLAW-565 lineage; ASR-only since JCLAW-654).

A long-running localhost HTTP daemon, launched on demand by the jclaw JVM
(AsrSidecarManager) and hosting a persistent GPU whisper worker
(transcribe.py --worker: mlx-whisper on Apple silicon, faster-whisper on
CUDA/CPU). Local speaker diarization was removed in JCLAW-654 after the
measured tier comparison — speaker attribution now runs through an
audio-capable cloud chat model (the diarize_audio tool); this daemon's job
is plain transcription and ASR model management for the Settings page.

Protocol (bound to 127.0.0.1 only):
  GET  /health  -> 200 {status, model, loaded}
  GET  /asr/models?ids=a,b -> 200 {models: {...}}   (JCLAW-650)
  POST /transcribe {audio_path, model, language?}
        -> 200 {segments: [{startMs, endMs, text, ...confidence}, ...]}
  POST /asr/prefetch {model} -> 200 {...}           (JCLAW-650)
  POST /shutdown -> 200 (JCLAW-637: evict an adopted orphan)

The audio file is passed by path, not uploaded: both processes run on the
same host and jclaw's attachments are already on disk.

Weights cache under --cache-dir via HF_HOME, matching jclaw's data/
runtime-artifact convention.
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# JCLAW-641: subprocess ceilings derive from the JVM's one knob
# (transcription.asr.local.timeoutSeconds), passed at spawn as an
# env var with a 60s margin so the sidecar gives up BEFORE the JVM does
# and can return a real error instead of a hung socket.
REQUEST_TIMEOUT_SEC = int(os.environ.get("SIDECAR_REQUEST_TIMEOUT_SEC", "1740"))

DEFAULT_IDENTITY = "asr"

# ASR ids that route through the MERaLiON speech-LLM (plain transcript) + forced
# alignment (segment times) instead of the Whisper engine, which emits times
# natively. Maps the stable config id -> the HF repo passed to meralion.py.
MERALION_HF = {"meralion-3-3b": "MERaLiON/MERaLiON-3-3B-ASR"}


class SidecarState:
    def __init__(self, model: str, idle_timeout_s: float):
        self.model = model  # identity string echoed on /health (JCLAW-637)
        self.idle_timeout_s = idle_timeout_s
        self.last_activity = time.monotonic()
        self.run_lock = threading.Lock()
        self._asr_worker = None
        # MERaLiON path: the speech-LLM emits a plain transcript, forced
        # alignment (align.py) recovers the segment times Whisper gives natively.
        self._meralion_worker = None
        self._align_worker = None
        # JCLAW-650: serializes ALL worker line-protocol I/O — status and
        # prefetch share the worker with transcription.
        self.asr_io_lock = threading.Lock()

    def _spawn(self, attr, script):
        """JCLAW-649: lazily (re)spawn a persistent PEP-723 worker for `script`,
        cached on `self.<attr>` — one-shot calls paid a python start + engine
        import + model load (~5s) per request. Every ASR-family worker
        (transcribe/meralion/align) shares this line-protocol lifecycle. Caller
        must hold asr_io_lock."""
        import subprocess
        w = getattr(self, attr)
        if w is not None and w.poll() is None:
            return w
        script_dir = os.path.dirname(os.path.abspath(__file__))
        sys.stderr.write("[asr-sidecar] spawning persistent %s worker\n" % script)
        w = subprocess.Popen(["uv", "run", script, "--worker"],
                             cwd=script_dir, stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, bufsize=1)
        ready = w.stdout.readline()
        if not ready or not json.loads(ready).get("ready"):
            raise RuntimeError("%s worker failed to start: %r" % (script, ready))
        setattr(self, attr, w)
        return w

    def asr_worker(self):
        return self._spawn("_asr_worker", "transcribe.py")

    def meralion_worker(self):
        return self._spawn("_meralion_worker", "meralion.py")

    def align_worker(self):
        return self._spawn("_align_worker", "align.py")

    def ask(self, attr, spawn, req):
        """One line-protocol round trip on a worker: write request, read reply,
        clear the cached handle if the worker died, raise on an {"error"} reply.
        Caller must hold asr_io_lock."""
        w = spawn()
        w.stdin.write(json.dumps(req) + "\n")
        w.stdin.flush()
        line = w.stdout.readline()
        if not line:
            setattr(self, attr, None)
            raise RuntimeError("%s died mid-request" % attr.lstrip("_"))
        payload = json.loads(line)
        if "error" in payload:
            raise RuntimeError(payload["error"])
        return payload

    def touch(self):
        self.last_activity = time.monotonic()


class Handler(BaseHTTPRequestHandler):
    state: SidecarState = None  # injected in main()

    def log_message(self, fmt, *args):
        sys.stderr.write("[asr-sidecar] %s\n" % (fmt % args))

    def _send_json(self, code: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        return json.loads(raw.decode("utf-8"))

    def handle(self):
        try:
            super().handle()
        except BrokenPipeError:
            pass  # client went away mid-response — not an error worth a traceback

    # ---- endpoints ------------------------------------------------------
    def do_GET(self):
        if self.path.startswith("/asr/models"):
            # JCLAW-650: host-relevant ASR artifact status for the Settings
            # UI — which engine will run each model and whether its weights
            # are cached. Query: ?ids=comma,separated,model,ids
            self.state.touch()
            try:
                from urllib.parse import urlparse, parse_qs
                ids = parse_qs(urlparse(self.path).query).get("ids", [""])[0]
                models = [m for m in ids.split(",") if m]
                with self.state.asr_io_lock:
                    w = self.state.asr_worker()
                    w.stdin.write(json.dumps({"op": "status", "models": models}) + "\n")
                    w.stdin.flush()
                    line = w.stdout.readline()
                if not line:
                    self.state._asr_worker = None
                    raise RuntimeError("asr worker died mid-request")
                self._send_json(200, json.loads(line))
            except Exception as e:  # noqa: BLE001
                self._send_json(500, {"error": str(e)})
            return
        if self.path == "/health":
            # JCLAW-641: a health probe signals imminent use — touching the
            # idle clock closes the evict race between the JVM's check and
            # its POST. Only ensureRunning probes health, so this cannot
            # keep an unused sidecar alive.
            self.state.touch()
            self._send_json(200, {
                "status": "ok",
                "model": self.state.model,
                "loaded": any(w is not None for w in (
                    self.state._asr_worker, self.state._meralion_worker,
                    self.state._align_worker)),
            })
        else:
            self._send_json(404, {"error": "unknown path %s" % self.path})

    def do_POST(self):
        if self.path == "/asr/prefetch":
            # JCLAW-650: download the host engine's weights for a model.
            # Synchronous (the JVM tracks async state and polls /asr/models);
            # shares the worker, so an in-flight ASR request queues behind it.
            self.state.touch()
            try:
                req = self._read_json()
                with self.state.asr_io_lock:
                    w = self.state.asr_worker()
                    w.stdin.write(json.dumps({"op": "prefetch",
                                              "model": req.get("model")}) + "\n")
                    w.stdin.flush()
                    line = w.stdout.readline()
                if not line:
                    self.state._asr_worker = None
                    raise RuntimeError("asr worker died mid-request")
                self._send_json(200, json.loads(line))
            except Exception as e:  # noqa: BLE001
                self._send_json(500, {"error": str(e)})
            finally:
                self.state.touch()
            return
        if self.path == "/shutdown":
            # JCLAW-637: lets a restarted JVM evict an adopted orphan whose
            # identity no longer matches config (no Process handle exists for
            # a sidecar that survived a JVM crash). Localhost-only server.
            sys.stderr.write("[asr-sidecar] shutdown requested — exiting\n")
            self._send_json(200, {"status": "bye"})
            threading.Thread(target=lambda: (time.sleep(0.2), os._exit(0)),
                             daemon=True).start()
            return
        if self.path == "/transcribe":
            self._handle_transcribe()
            return
        self._send_json(404, {"error": "unknown path %s" % self.path})

    def _handle_transcribe(self):
        """JCLAW-627: GPU ASR via the transcribe.py script env."""
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
        model = req.get("model") or "large"
        language = req.get("language") or "-"
        if not self.state.run_lock.acquire(blocking=False):
            self._send_json(409, {"error": "another inference is already in progress"})
            return
        try:
            t0 = time.time()
            with self.state.asr_io_lock:
                if model in MERALION_HF:
                    # MERaLiON -> plain transcript, then forced-align to segments.
                    text = self.state.ask(
                        "_meralion_worker", self.state.meralion_worker,
                        {"audio_path": audio_path, "model": MERALION_HF[model]}).get("text", "")
                    payload = {"segments": self.state.ask(
                        "_align_worker", self.state.align_worker,
                        {"audio_path": audio_path, "text": text}).get("segments", [])}
                else:
                    payload = self.state.ask(
                        "_asr_worker", self.state.asr_worker,
                        {"audio": audio_path, "model": model,
                         "language": None if language == "-" else language})
            sys.stderr.write("[asr-sidecar] transcribed %s: %d segment(s) in %.1fs\n"
                             % (os.path.basename(audio_path),
                                len(payload.get("segments", [])), time.time() - t0))
            self._send_json(200, payload)
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(e)})
        finally:
            self.state.touch()
            self.state.run_lock.release()


def _prewarm(state: SidecarState):
    """Resolve the ASR script env off the request path so the first real
    transcription doesn't pay `uv sync`. Env resolution only — no model
    load, cheap after the first run."""
    import subprocess
    script_dir = os.path.dirname(os.path.abspath(__file__))
    steps = [
        (["uv", "sync", "--script", "transcribe.py"], "transcribe env"),
    ]
    for cmd, label in steps:
        while state.run_lock.locked():
            time.sleep(15)  # a real request owns the machine — wait
        try:
            t0 = time.time()
            proc = subprocess.run(cmd, cwd=script_dir, capture_output=True,
                                  text=True, timeout=REQUEST_TIMEOUT_SEC)
            sys.stderr.write("[asr-sidecar] prewarm %s: rc=%d in %.0fs\n"
                             % (label, proc.returncode, time.time() - t0))
        except Exception as e:  # noqa: BLE001 — prewarm must never hurt
            sys.stderr.write("[asr-sidecar] prewarm %s failed: %s\n" % (label, e))
    state.touch()  # don't let prewarm time count as idle


def _idle_watcher(state: SidecarState):
    """Self-evict after the idle timeout so the daemon releases resources
    when unused. 0 disables eviction."""
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(min(60.0, state.idle_timeout_s))
        # JCLAW-619: an in-flight inference holds run_lock but does not touch
        # last_activity — a single run longer than the idle timeout must
        # never be killed mid-inference.
        if state.run_lock.locked():
            continue
        if time.monotonic() - state.last_activity >= state.idle_timeout_s:
            sys.stderr.write("[asr-sidecar] idle for %.0fs — exiting\n" % state.idle_timeout_s)
            os._exit(0)


def main():
    ap = argparse.ArgumentParser(description="jclaw ASR sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--model", default=DEFAULT_IDENTITY)
    ap.add_argument("--cache-dir", default=os.path.join("data", "asr-models"))
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    args = ap.parse_args()

    cache_dir = os.path.abspath(args.cache_dir)
    os.makedirs(cache_dir, exist_ok=True)
    # Point Hugging Face at jclaw's data dir so weights land under data/.
    os.environ.setdefault("HF_HOME", cache_dir)

    Handler.state = SidecarState(args.model, args.idle_timeout_min * 60.0)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(target=_idle_watcher, args=(Handler.state,), daemon=True).start()
    threading.Thread(target=_prewarm, args=(Handler.state,), daemon=True).start()
    sys.stderr.write("[asr-sidecar] listening on http://%s:%d (identity=%s)\n"
                     % (args.host, args.port, args.model))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
