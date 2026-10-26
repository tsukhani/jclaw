#!/usr/bin/env python3
"""Local TTS sidecar for jclaw (JCLAW-789).

A long-running localhost HTTP daemon, launched on demand by the jclaw JVM
(TtsSidecarManager) and hosting a persistent TTS worker (synth.py --worker).
Mirrors the ASR sidecar (sidecar/asr/serve.py) — a stdlib-only HTTP supervisor
that shells each request to a PEP-723 worker carrying the ML deps — but the
data direction is INVERTED: it takes TEXT and returns AUDIO BYTES.

Engine router (JCLAW-789): the request `model` id selects the engine + weights
inside the worker (Qwen3-TTS variants, Kokoro), mirroring the ASR AsrModel
routing. Apple silicon runs via mlx-audio (validated JCLAW-788); the
NVIDIA/vLLM backend is deferred (JCLAW-788 RTX 4090 validation).

Protocol (--host defaults to 127.0.0.1; the server binds what it is given):
  GET  /health -> 200 {status, model, loaded}
  POST /synthesize {text, model?, voice?, ref_audio?, speed?, format?}
        -> 200 audio bytes (Content-Type: audio/wav) | 400/409/500 JSON on error
  POST /shutdown -> 200 (evict an adopted orphan whose identity no longer matches)

Every request must carry `X-Sidecar-Token: $SIDECAR_TOKEN`, the secret the JVM
derives from its own install secret; without it in the environment the sidecar
refuses to start.
`--no-auth` drops both requirements for hand-running (see README).

Weights cache under --cache-dir via HF_HOME, matching jclaw's data/
runtime-artifact convention.
"""

import argparse
import base64
import hmac
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Subprocess ceiling derives from the JVM's timeout knob, passed at spawn with a
# 60s margin so the sidecar gives up BEFORE the JVM does (mirrors the ASR sidecar).
REQUEST_TIMEOUT_SEC = int(os.environ.get("SIDECAR_REQUEST_TIMEOUT_SEC", "1740"))

DEFAULT_IDENTITY = "tts"

# audio format -> response MIME. WAV is the only guaranteed format today; others
# depend on the worker's libsndfile build, so unknown formats fall back to octet-stream.
_MIME = {"wav": "audio/wav", "flac": "audio/flac", "mp3": "audio/mpeg", "opus": "audio/opus"}


def _open_worker_stderr(tag):
    """Temp file for a persistent worker's stderr, plus its path.

    JCLAW-859: this used to be subprocess.DEVNULL. A worker that dies mid-request
    is already gone by the time we notice, so its stderr is the only evidence of
    why — discarding it made a corrupt uv cache, an OOM kill, a failed weight
    download and a plain traceback all surface as the same opaque
    "died mid-request". Mirrors the ASR sidecar's prefetch path.
    """
    import tempfile
    fd, path = tempfile.mkstemp(suffix=".%s.stderr" % tag)
    return os.fdopen(fd, "w"), path


# tqdm / huggingface download frames. Dropped from the tail because a long
# model pull emits hundreds of them and would otherwise push the actual
# traceback out of the window — which is exactly what happened on the first
# cut of this fix: the uv error was truncated one character in.
_PROGRESS_MARKERS = ("%|", "it/s]", "B/s]")


def _stderr_tail(path, limit=1200):
    """Trailing lines of a dead worker's stderr, formatted for an error message.

    Progress frames are stripped and in-place carriage-return redraws collapsed
    to their final state, so the budget is spent on diagnostics rather than
    download bars. Still bounded — a deep traceback should not flood the HTTP
    reply — and the tail is the right end to keep, since the exception is the
    last thing a dying worker writes.
    """
    if not path:
        return ""
    try:
        with open(path, "r", errors="replace") as fh:
            raw = fh.read()
    except OSError:
        return ""
    lines = []
    for chunk in raw.split("\n"):
        line = chunk.split("\r")[-1].rstrip()
        if not line or any(m in line for m in _PROGRESS_MARKERS):
            continue
        lines.append(line)
    text = "\n".join(lines).strip()
    return (": " + text[-limit:]) if text else ""


class SidecarState:
    def __init__(self, model: str, idle_timeout_s: float):
        self.model = model  # identity string echoed on /health (orphan-eviction key)
        self.idle_timeout_s = idle_timeout_s
        self.last_activity = time.monotonic()
        self.run_lock = threading.Lock()
        self.io_lock = threading.Lock()  # serializes worker line-protocol I/O
        self._tts_worker = None
        self._worker_stderr = {}  # attr -> stderr temp-file path (JCLAW-859)

    def _spawn(self, attr, script):
        """Lazily (re)spawn a persistent PEP-723 worker for `script`, cached on
        `self.<attr>` — amortizes the python start + engine import + model load
        that a one-shot call would pay per request. Caller must hold io_lock."""
        import subprocess
        w = getattr(self, attr)
        if w is not None and w.poll() is None:
            return w
        script_dir = os.path.dirname(os.path.abspath(__file__))
        sys.stderr.write("[tts-sidecar] spawning persistent %s worker\n" % script)
        errfh, errpath = _open_worker_stderr(attr.lstrip("_"))
        w = subprocess.Popen(["uv", "run", script, "--worker"],
                             cwd=script_dir, stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=errfh,
                             text=True, bufsize=1)
        errfh.close()  # the child holds its own dup of the fd
        self._discard_worker_stderr(attr)
        self._worker_stderr[attr] = errpath
        ready = w.stdout.readline()
        if not ready or not json.loads(ready).get("ready"):
            raise RuntimeError("%s worker failed to start: %r%s"
                               % (script, ready, _stderr_tail(errpath)))
        setattr(self, attr, w)
        return w

    def _discard_worker_stderr(self, attr):
        """Drop the previous generation's stderr file so respawns don't leak
        temp files over a long-running sidecar."""
        prev = self._worker_stderr.pop(attr, None)
        if not prev:
            return
        try:
            os.unlink(prev)
        except OSError:
            pass

    def tts_worker(self):
        return self._spawn("_tts_worker", "synth.py")

    def ask(self, attr, spawn, req):
        """One line-protocol round trip: write request, read reply, clear the
        cached handle if the worker died, raise on an {"error"} reply. Caller
        must hold io_lock."""
        w = spawn()
        w.stdin.write(json.dumps(req) + "\n")
        w.stdin.flush()
        line = w.stdout.readline()
        if not line:
            setattr(self, attr, None)
            # Append the worker's own last words — without them this error names
            # the symptom and nothing else (JCLAW-859).
            raise RuntimeError("%s died mid-request%s"
                               % (attr.lstrip("_"),
                                  _stderr_tail(self._worker_stderr.get(attr))))
        payload = json.loads(line)
        if "error" in payload:
            raise RuntimeError(payload["error"])
        return payload

    def touch(self):
        self.last_activity = time.monotonic()


# Not a CORS-simple header, so a page the operator visits cannot forge a request this
# sidecar honours: the browser would have to preflight, and this server answers no OPTIONS.
AUTH_HEADER = "X-Sidecar-Token"


def _require_token(ap):
    """The secret the JVM hands the child in its environment. Missing means fail closed —
    an unauthenticated loopback sidecar is reachable by every local process."""
    token = os.environ.get("SIDECAR_TOKEN", "")
    if not token:
        ap.error("SIDECAR_TOKEN is unset — pass --no-auth to run this sidecar unauthenticated")
    return token


class Handler(BaseHTTPRequestHandler):
    state: SidecarState = None  # injected in main()
    token = None  # shared secret; None only under --no-auth

    def log_message(self, fmt, *args):
        sys.stderr.write("[tts-sidecar] %s\n" % (fmt % args))

    def _send_json(self, code: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, code: int, data: bytes, mime: str):
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        return json.loads(raw.decode("utf-8"))

    def handle(self):
        try:
            super().handle()
        except BrokenPipeError:
            pass  # client went away mid-response — not worth a traceback

    def _authorized(self):
        if self.token is None:
            return True
        if hmac.compare_digest(self.headers.get(AUTH_HEADER, "").encode("utf-8", "replace"),
                               self.token.encode("utf-8")):
            return True
        self._send_json(401, {"error": "missing or invalid %s" % AUTH_HEADER})
        return False

    def do_GET(self):
        if not self._authorized():
            return
        if self.path == "/health":
            # A health probe signals imminent use — touching the idle clock closes
            # the evict race between the JVM's check and its subsequent request.
            self.state.touch()
            self._send_json(200, {
                "status": "ok",
                "model": self.state.model,
                "loaded": self.state._tts_worker is not None,
            })
        else:
            self._send_json(404, {"error": "unknown path %s" % self.path})

    def do_POST(self):
        if not self._authorized():
            return
        if self.path == "/shutdown":
            # Lets a restarted JVM evict an adopted orphan whose identity no longer
            # matches config (no Process handle exists for a JVM-crash survivor).
            sys.stderr.write("[tts-sidecar] shutdown requested — exiting\n")
            self._send_json(200, {"status": "bye"})
            threading.Thread(target=lambda: (time.sleep(0.2), os._exit(0)), daemon=True).start()
            return
        if self.path == "/synthesize":
            self._handle_synthesize()
            return
        self._send_json(404, {"error": "unknown path %s" % self.path})

    def _handle_synthesize(self):
        self.state.touch()
        try:
            req = self._read_json()
        except (ValueError, UnicodeDecodeError) as e:
            self._send_json(400, {"error": "invalid JSON body: %s" % e})
            return
        text = (req.get("text") or "").strip()
        if not text:
            self._send_json(400, {"error": "text is required"})
            return
        fmt = (req.get("format") or "wav").lower()
        # One synthesis at a time — mirror the ASR 409 so a busy worker never
        # blocks; the Java client serializes calls behind a fair lock.
        if not self.state.run_lock.acquire(blocking=False):
            self._send_json(409, {"error": "another synthesis is already in progress"})
            return
        try:
            t0 = time.time()
            with self.state.io_lock:
                payload = self.state.ask("_tts_worker", self.state.tts_worker, {
                    "op": "synthesize", "text": text,
                    "model": req.get("model"), "voice": req.get("voice"),
                    "ref_audio": req.get("ref_audio"),
                    "speed": req.get("speed"), "format": fmt,
                })
            audio = base64.b64decode(payload["audio_b64"])
            sys.stderr.write("[tts-sidecar] synthesized %d chars -> %d bytes in %.1fs\n"
                             % (len(text), len(audio), time.time() - t0))
            self._send_bytes(200, audio, _MIME.get(fmt, "application/octet-stream"))
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(e)})
        finally:
            self.state.touch()
            self.state.run_lock.release()


def _prewarm(state: SidecarState):
    """Resolve the synth.py script env off the request path so the first real
    synthesis doesn't pay `uv sync`. Env resolution only — no model load."""
    import subprocess
    script_dir = os.path.dirname(os.path.abspath(__file__))
    while state.run_lock.locked():
        time.sleep(15)  # a real request owns the machine — wait
    try:
        t0 = time.time()
        proc = subprocess.run(["uv", "sync", "--script", "synth.py"], cwd=script_dir,
                              capture_output=True, text=True, timeout=REQUEST_TIMEOUT_SEC)
        sys.stderr.write("[tts-sidecar] prewarm synth env: rc=%d in %.0fs\n"
                         % (proc.returncode, time.time() - t0))
    except Exception as e:  # noqa: BLE001 — prewarm must never hurt
        sys.stderr.write("[tts-sidecar] prewarm failed: %s\n" % e)
    state.touch()  # don't let prewarm time count as idle


def _idle_watcher(state: SidecarState):
    """Self-evict after the idle timeout so the daemon releases the model when
    unused. 0 disables. An in-flight synthesis holds run_lock but does not touch
    last_activity — a single long synthesis is never killed mid-flight."""
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(min(60.0, state.idle_timeout_s))
        if state.run_lock.locked():
            continue
        if time.monotonic() - state.last_activity >= state.idle_timeout_s:
            sys.stderr.write("[tts-sidecar] idle for %.0fs — exiting\n" % state.idle_timeout_s)
            os._exit(0)


class _ResilientStderr:
    """stderr whose writes never raise. JCLAW-989: a sidecar that outlives its JVM has no
    reader left on the pipe, and an unguarded BrokenPipeError there killed the idle watcher
    before its os._exit (leaving the process immortal) and aborted send_response's access-log
    line before any byte reached the socket (leaving it a port squatter that answered nothing)."""

    def __init__(self, raw):
        self._raw = raw

    def write(self, s):
        try:
            return self._raw.write(s)
        except (OSError, ValueError):
            return len(s)

    def flush(self):
        try:
            self._raw.flush()
        except (OSError, ValueError):
            pass

    def __getattr__(self, name):
        return getattr(self._raw, name)


def main():
    # An orphaned sidecar must stay adoptable (JCLAW-637), so install this before anything logs.
    sys.stderr = _ResilientStderr(sys.stderr)
    ap = argparse.ArgumentParser(description="jclaw TTS sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--model", default=DEFAULT_IDENTITY)
    ap.add_argument("--cache-dir", default=os.path.join("data", "tts-models"))
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    ap.add_argument("--no-auth", action="store_true",
                    help="serve unauthenticated — for hand-running this sidecar without the JVM")
    args = ap.parse_args()

    cache_dir = os.path.abspath(args.cache_dir)
    os.makedirs(cache_dir, exist_ok=True)
    # Point Hugging Face at jclaw's data dir so weights land under data/.
    os.environ.setdefault("HF_HOME", cache_dir)

    Handler.token = None if args.no_auth else _require_token(ap)
    Handler.state = SidecarState(args.model, args.idle_timeout_min * 60.0)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(target=_idle_watcher, args=(Handler.state,), daemon=True).start()
    threading.Thread(target=_prewarm, args=(Handler.state,), daemon=True).start()
    sys.stderr.write("[tts-sidecar] listening on http://%s:%d (identity=%s)\n"
                     % (args.host, args.port, args.model))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
