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
  POST /diarize {audio_path, num_speakers?, emotions?, emotion_model?}
        -> 200 {turns: [{startMs, endMs, speaker, emotion?}, ...]}
        emotions=true adds an SER pass (ser.py) per turn — a categorical label
        + valence/arousal/dominance — best-effort; emotion_model picks the SER
        model (defaults MERaLiON-SER-v1).
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


def _hf_hub_cache():
    """HF hub cache dir (stdlib mirror of huggingface_hub's default resolution)
    so status can size an in-flight download without importing huggingface_hub
    into this stdlib-only daemon. HF_HOME is set to the --cache-dir in main()."""
    c = os.environ.get("HF_HUB_CACHE")
    if c:
        return c
    home = os.environ.get("HF_HOME")
    if home:
        return os.path.join(home, "hub")
    xdg = os.environ.get("XDG_CACHE_HOME") or os.path.join(os.path.expanduser("~"), ".cache")
    return os.path.join(xdg, "huggingface", "hub")


def _repo_bytes(repo):
    """Real on-disk bytes for a repo's cache dir. Skips symlinks so a completed
    snapshot isn't double-counted; mid-download only the growing blobs exist,
    giving an honest progress numerator."""
    d = os.path.join(_hf_hub_cache(), "models--" + repo.replace("/", "--"))
    total = 0
    for root, _, files in os.walk(d):
        for f in files:
            fp = os.path.join(root, f)
            try:
                if not os.path.islink(fp):
                    total += os.path.getsize(fp)
            except OSError:
                pass
    return total


def _read_error(path):
    """Best-effort error text from a finished prefetch subprocess's captured
    output: the last JSON {"error":...} line, else the tail."""
    try:
        with open(path) as f:
            text = f.read().strip()
    except OSError:
        return "download failed"
    for line in reversed(text.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                e = json.loads(line).get("error")
                if e:
                    return e
            except ValueError:
                pass
    return text[-300:] or "download failed"


def _engine_of(repo):
    """Cosmetic engine label for the Settings row."""
    return "pyannote" if "diarization" in repo.lower() else "ser"


class SidecarState:
    def __init__(self, model: str, idle_timeout_s: float):
        self.model = model  # identity string echoed on /health
        self.idle_timeout_s = idle_timeout_s
        self.last_activity = time.monotonic()
        self.run_lock = threading.Lock()
        self._worker = None
        self._ser_worker = None
        self.io_lock = threading.Lock()  # serializes worker line-protocol I/O
        # Model downloads run as DETACHED subprocesses (via hf_prefetch.py, not
        # the heavy pyannote/SER workers) so a multi-GB pull can't stall status
        # polling; status sizes them straight off the cache dir instead.
        self._prefetches = {}        # repo -> {proc, out}
        self._prefetch_errors = {}   # repo -> error
        self._prefetch_lock = threading.Lock()

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

    def start_prefetch(self, repo):
        """Launch a DETACHED download of `repo` via hf_prefetch.py. Holds no
        lock or worker pipe, so the download can't stall status polling.
        Idempotent per repo; subprocess output is captured for error reporting."""
        import subprocess
        import tempfile
        with self._prefetch_lock:
            cur = self._prefetches.get(repo)
            if cur is not None and cur["proc"].poll() is None:
                return
            self._prefetch_errors.pop(repo, None)
            script_dir = os.path.dirname(os.path.abspath(__file__))
            fd, outpath = tempfile.mkstemp(suffix=".prefetch")
            fh = os.fdopen(fd, "w")
            proc = subprocess.Popen(["uv", "run", "hf_prefetch.py", "--prefetch", repo],
                                    cwd=script_dir, stdout=fh, stderr=subprocess.STDOUT)
            fh.close()
            self._prefetches[repo] = {"proc": proc, "out": outpath}

    def prefetch_state(self, repo):
        """Worker-free status for a repo with an active/failed download:
        {engine, repo, cached, downloading, bytesOnDisk[, error]}. Returns None
        when nothing is in flight (caller does a lightweight cache check)."""
        engine = _engine_of(repo)
        with self._prefetch_lock:
            entry = self._prefetches.get(repo)
            if entry is None:
                err = self._prefetch_errors.get(repo)
                if err is None:
                    return None
                return {"engine": engine, "repo": repo, "cached": False,
                        "downloading": False, "bytesOnDisk": _repo_bytes(repo), "error": err}
            proc, outf = entry["proc"], entry["out"]
            if proc.poll() is None:
                return {"engine": engine, "repo": repo, "cached": False,
                        "downloading": True, "bytesOnDisk": _repo_bytes(repo)}
            self._prefetches.pop(repo, None)
            err = _read_error(outf) if proc.returncode != 0 else None
            try:
                os.remove(outf)
            except OSError:
                pass
            if err:
                self._prefetch_errors[repo] = err
                return {"engine": engine, "repo": repo, "cached": False,
                        "downloading": False, "bytesOnDisk": _repo_bytes(repo), "error": err}
            return None  # success -> a cache check now reports cached=true

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

    def _hf_status(self, repos):
        """Cache status for repos NOT in flight, via a one-shot hf_prefetch.py
        (minimal env — no pyannote/SER worker spawned). Returns the same shape
        as prefetch_state so the JVM sees one contract."""
        import subprocess
        script_dir = os.path.dirname(os.path.abspath(__file__))
        proc = subprocess.run(["uv", "run", "hf_prefetch.py", "--status", *repos],
                              cwd=script_dir, capture_output=True, text=True,
                              timeout=REQUEST_TIMEOUT_SEC)
        if proc.returncode != 0:
            raise RuntimeError("hf_prefetch --status failed: %s"
                               % (proc.stderr or proc.stdout)[:300])
        raw = json.loads(proc.stdout.strip().splitlines()[-1])["status"]
        return {repo: {"engine": _engine_of(repo), "repo": repo, "cached": s["cached"],
                       "downloading": False, "bytesOnDisk": s["bytesOnDisk"]}
                for repo, s in raw.items()}

    def do_GET(self):
        if self.path.startswith("/diarize/models"):
            # Download status for the on-device diarization weights (pyannote +
            # the operator's SER model). An in-flight/failed download is sized
            # off the cache dir (worker-free); everything else gets a lightweight
            # cache check. Query: ?ids=repo1,repo2 (HF repos).
            self.state.touch()
            try:
                from urllib.parse import urlparse, parse_qs, unquote
                ids = unquote(parse_qs(urlparse(self.path).query).get("ids", [""])[0])
                repos = [r for r in ids.split(",") if r]
                status, pending = {}, []
                for r in repos:
                    ps = self.state.prefetch_state(r)
                    if ps is not None:
                        status[r] = ps
                    else:
                        pending.append(r)
                if pending:
                    status.update(self._hf_status(pending))
                self._send_json(200, {"status": status})
            except Exception as e:  # noqa: BLE001
                self._send_json(500, {"error": str(e)})
            return
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
        if self.path == "/diarize/prefetch":
            # Kick a DETACHED download of a diarization weight (pyannote or SER
            # repo) and return immediately — the download never holds a lock or
            # worker, so /diarize/models stays responsive with live progress.
            self.state.touch()
            try:
                repo = self._read_json().get("model")
                if not repo:
                    self._send_json(400, {"error": "model (HF repo) required"})
                    return
                self.state.start_prefetch(repo)
                self._send_json(200, {"status": "downloading", "model": repo})
            except Exception as e:  # noqa: BLE001
                self._send_json(500, {"error": str(e)})
            finally:
                self.state.touch()
            return
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
                self._attach_emotions(audio_path, turns, req.get("emotion_model"))
            sys.stderr.write("[diarize-sidecar] diarized %s: %d turn(s)%s in %.1fs\n"
                             % (os.path.basename(audio_path), len(turns),
                                " +emotion" if req.get("emotions") else "", time.time() - t0))
            self._send_json(200, out)
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(e)})
        finally:
            self.state.touch()
            self.state.run_lock.release()

    def _attach_emotions(self, audio_path, turns, model=None):
        """Second pass: SER per turn (MERaLiON by default, or the operator's
        chosen model), merged onto the turns in place. Best-effort (JCLAW-563
        pattern) — an emotion failure must not sink the diarization; the turns
        still return, just without labels."""
        spans = [[t["startMs"], t["endMs"]] for t in turns]
        payload = {"audio_path": audio_path, "spans": spans}
        if model:
            payload["model"] = model
        try:
            with self.state.io_lock:
                w = self.state.ser_worker()
                w.stdin.write(json.dumps(payload) + "\n")
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
