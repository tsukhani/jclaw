#!/usr/bin/env python3
"""Local speaker-diarization sidecar for jclaw (JCLAW-565 lineage; revived for
the local privacy path).

A long-running localhost HTTP daemon, launched on demand by the jclaw JVM
(DiarizeSidecarManager) and hosting a persistent pyannote worker
(diarize.py --worker: speaker-diarization-community-1 on GPU when available —
CUDA or Apple MPS — else CPU). Diarization produces speaker TURNS (who spoke
when) — no transcription, no emotion. The
JVM fuses these turns with the ASR sidecar's transcript to build the
speaker-attributed transcript. Emotion is deliberately absent: the measured
classical-SER path (DiaRemot) did not transfer to 8kHz telephony.

Protocol (bound to 127.0.0.1 only):
  GET  /health  -> 200 {status, model, loaded}
  POST /diarize {audio_path, num_speakers?, emotions?}
        -> 200 {turns: [{startMs, endMs, speaker, emotion?}, ...]}
        emotions=true adds a MERaLiON-SER pass (ser.py) per turn — a
        categorical label + valence/arousal/dominance — best-effort.
  POST /shutdown -> 200 (JCLAW-637: evict an adopted orphan)

The audio file is passed by path, not uploaded: both processes run on the
same host and jclaw's attachments are already on disk. pyannote's gated
community-1 weights need HF_TOKEN in the environment (the JVM passes it from
imagegen.local.hfToken); weights cache under --cache-dir via HF_HOME.
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Subprocess ceiling derives from the JVM's one knob
# (transcription.diarization.local.timeoutSeconds), passed at spawn as an env
# var with a margin so the sidecar gives up BEFORE the JVM does.
REQUEST_TIMEOUT_SEC = int(os.environ.get("SIDECAR_REQUEST_TIMEOUT_SEC", "1740"))

DEFAULT_IDENTITY = "diarize"


class SidecarState:
    def __init__(self, model: str, idle_timeout_s: float):
        self.model = model  # identity string echoed on /health
        self.idle_timeout_s = idle_timeout_s
        self.last_activity = time.monotonic()
        self.run_lock = threading.Lock()
        self._worker = None
        self._ser_worker = None
        self.io_lock = threading.Lock()  # serializes worker line-protocol I/O

    def worker(self):
        """Persistent pyannote worker — the ~20s pipeline load is paid once,
        on the first /diarize, then amortized. Caller must hold io_lock."""
        import subprocess
        w = self._worker
        if w is not None and w.poll() is None:
            return w
        script_dir = os.path.dirname(os.path.abspath(__file__))
        sys.stderr.write("[diarize-sidecar] spawning persistent pyannote worker\n")
        w = subprocess.Popen(["uv", "run", "diarize.py", "--worker"],
                             cwd=script_dir, stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, bufsize=1)
        ready = w.stdout.readline()
        if not ready or not json.loads(ready).get("ready"):
            raise RuntimeError("pyannote worker failed to start: %r" % ready)
        self._worker = w
        return w

    def ser_worker(self):
        """Persistent MERaLiON-SER worker — spawned lazily on the first
        emotions=true request (the transformers env + model load are paid once,
        then amortized). Caller must hold io_lock."""
        import subprocess
        w = self._ser_worker
        if w is not None and w.poll() is None:
            return w
        script_dir = os.path.dirname(os.path.abspath(__file__))
        sys.stderr.write("[diarize-sidecar] spawning persistent SER worker\n")
        w = subprocess.Popen(["uv", "run", "ser.py", "--worker"],
                             cwd=script_dir, stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, bufsize=1)
        ready = w.stdout.readline()
        if not ready or not json.loads(ready).get("ready"):
            raise RuntimeError("SER worker failed to start: %r" % ready)
        self._ser_worker = w
        return w

    def touch(self):
        self.last_activity = time.monotonic()


class Handler(BaseHTTPRequestHandler):
    state: SidecarState = None  # injected in main()

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
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        return json.loads(raw.decode("utf-8"))

    def handle(self):
        try:
            super().handle()
        except BrokenPipeError:
            pass  # client went away mid-response — not worth a traceback

    def do_GET(self):
        if self.path == "/health":
            # A health probe signals imminent use — touching the idle clock
            # closes the evict race between the JVM's check and its POST.
            self.state.touch()
            self._send_json(200, {
                "status": "ok",
                "model": self.state.model,
                "loaded": self.state._worker is not None,
            })
        else:
            self._send_json(404, {"error": "unknown path %s" % self.path})

    def do_POST(self):
        if self.path == "/shutdown":
            # JCLAW-637: lets a restarted JVM evict an adopted orphan whose
            # identity no longer matches config. Localhost-only server.
            sys.stderr.write("[diarize-sidecar] shutdown requested — exiting\n")
            self._send_json(200, {"status": "bye"})
            threading.Thread(target=lambda: (time.sleep(0.2), os._exit(0)),
                             daemon=True).start()
            return
        if self.path == "/diarize":
            self._handle_diarize()
            return
        self._send_json(404, {"error": "unknown path %s" % self.path})

    def _handle_diarize(self):
        """Speaker turns via the pyannote worker (diarize.py script env)."""
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
        if not self.state.run_lock.acquire(blocking=False):
            self._send_json(409, {"error": "another inference is already in progress"})
            return
        try:
            t0 = time.time()
            payload = {"audio_path": audio_path}
            if req.get("num_speakers"):
                payload["num_speakers"] = req["num_speakers"]
            with self.state.io_lock:
                w = self.state.worker()
                w.stdin.write(json.dumps(payload) + "\n")
                w.stdin.flush()
                line = w.stdout.readline()
            if not line:
                self.state._worker = None
                raise RuntimeError("pyannote worker died mid-request")
            out = json.loads(line)
            if "error" in out:
                raise RuntimeError(out["error"])
            turns = out.get("turns", [])
            if req.get("emotions") and turns:
                self._attach_emotions(audio_path, turns)
            sys.stderr.write("[diarize-sidecar] diarized %s: %d turn(s)%s in %.1fs\n"
                             % (os.path.basename(audio_path), len(turns),
                                " +emotion" if req.get("emotions") else "", time.time() - t0))
            self._send_json(200, out)
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(e)})
        finally:
            self.state.touch()
            self.state.run_lock.release()

    def _attach_emotions(self, audio_path, turns):
        """Second pass: MERaLiON-SER per turn, merged onto the turns in place.
        Best-effort (JCLAW-563 pattern) — an emotion failure must not sink the
        diarization; the turns still return, just without labels."""
        spans = [[t["startMs"], t["endMs"]] for t in turns]
        try:
            with self.state.io_lock:
                w = self.state.ser_worker()
                w.stdin.write(json.dumps({"audio_path": audio_path, "spans": spans}) + "\n")
                w.stdin.flush()
                line = w.stdout.readline()
            if not line:
                self.state._ser_worker = None
                raise RuntimeError("SER worker died mid-request")
            res = json.loads(line)
            if "error" in res:
                raise RuntimeError(res["error"])
            for turn, emo in zip(turns, res.get("emotions", [])):
                if emo:
                    turn["emotion"] = emo
        except Exception as e:  # noqa: BLE001 — emotion is best-effort
            sys.stderr.write("[diarize-sidecar] emotion pass failed: %s\n" % e)


def _prewarm(state: SidecarState):
    """Resolve the pyannote script env off the request path so the first real
    diarize doesn't pay `uv sync`. Env resolution only — the ~20s model load
    still happens on the first /diarize."""
    import subprocess
    script_dir = os.path.dirname(os.path.abspath(__file__))
    while state.run_lock.locked():
        time.sleep(15)  # a real request owns the machine — wait
    try:
        t0 = time.time()
        proc = subprocess.run(["uv", "sync", "--script", "diarize.py"],
                              cwd=script_dir, capture_output=True, text=True,
                              timeout=REQUEST_TIMEOUT_SEC)
        sys.stderr.write("[diarize-sidecar] prewarm diarize env: rc=%d in %.0fs\n"
                         % (proc.returncode, time.time() - t0))
    except Exception as e:  # noqa: BLE001 — prewarm must never hurt
        sys.stderr.write("[diarize-sidecar] prewarm failed: %s\n" % e)
    state.touch()


def _idle_watcher(state: SidecarState):
    """Self-evict after the idle timeout so the daemon releases resources when
    unused. 0 disables eviction."""
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(min(60.0, state.idle_timeout_s))
        # An in-flight inference holds run_lock but does not touch
        # last_activity — a single run longer than the idle timeout must
        # never be killed mid-inference.
        if state.run_lock.locked():
            continue
        if time.monotonic() - state.last_activity >= state.idle_timeout_s:
            sys.stderr.write("[diarize-sidecar] idle for %.0fs — exiting\n" % state.idle_timeout_s)
            os._exit(0)


def main():
    ap = argparse.ArgumentParser(description="jclaw diarization sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--model", default=DEFAULT_IDENTITY)
    ap.add_argument("--cache-dir", default=os.path.join("data", "diarize-models"))
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
    sys.stderr.write("[diarize-sidecar] listening on http://%s:%d (identity=%s)\n"
                     % (args.host, args.port, args.model))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
