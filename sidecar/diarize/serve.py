#!/usr/bin/env python3
"""Local speaker-diarization sidecar for jclaw — pyannote community-1 (JCLAW-565).

A long-running localhost HTTP daemon, launched on demand by the jclaw JVM
(PyannoteSidecarManager) and holding the pyannote pipeline resident between
calls (model load is seconds; the pipeline itself diarizes ~18x realtime on
Apple MPS). Chosen over the in-process sherpa-onnx path by the JCLAW-565
bake-off: on real 2-speaker podcast audio the community-1 pipeline scored
DER 12.5% / pairwise F1 0.864 with the speaker count found automatically,
where sherpa's threshold clustering capped at F1 0.799 and over- or
under-split depending on a knife-edge threshold. The sherpa fallback was
scrapped (JCLAW-614): this sidecar IS jclaw's diarization engine, and its
prerequisites (uv + a Hugging Face token) are hard requirements.

Attribution: pyannote community-1 model (c) pyannoteAI, released under
CC-BY-4.0 (https://huggingface.co/pyannote/speaker-diarization-community-1);
pyannote.audio library MIT. The model is gated — the operator must accept
the conditions on the model page and configure a HF token in jclaw Settings
(passed to this process as HF_TOKEN).

Protocol (bound to 127.0.0.1 only):
  GET  /health  -> 200 {status, device, model, loaded}
  POST /diarize {audio_path, num_speakers?}
        -> 200 {segments: [{start, end, speaker}, ...],
                overlaps: [{start, end}, ...], device, seconds}
           (overlaps = regions where the overlap-aware annotation has 2+
            active speakers — the JCLAW-605 re-attribution gate)
  POST /msdd {audio_path, num_speakers}
        -> 200 {segments: [{start, end, speaker}, ...]}
           (NeMo MSDD second opinion, TS-VAD lineage — overlap-aware,
            segments may overlap in time; runs in its OWN uv script env
            like the separator; JCLAW-612)
  POST /transcribe {audio_path, model, language?}
        -> 200 {segments: [{startMs, endMs, text}, ...]}
           (GPU ASR, JCLAW-627 — mlx-whisper on Apple silicon,
            faster-whisper CUDA/CPU elsewhere; own uv script env)
  POST /separate {audio_paths: ["<w1.wav>", ...]}
        -> 200 {stems: {"<w1.wav>": ["..._s1.wav", "..._s2.wav"], ...}}
           (MossFormer2 2-speaker separation of ready-made 16kHz mono WAVs;
            runs in a SEPARATE uv script env — clearvoice pins numpy<2,
            incompatible with this env's pyannote 4 — shelled out once per
            batch so the model loads once; stems written beside each input)
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
        if self.path == "/transcribe":
            self._handle_transcribe()
            return
        if self.path == "/separate":
            self._handle_separate()
            return
        if self.path == "/msdd":
            self._handle_msdd()
            return
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
            # Prefer pyannote 4's *exclusive* diarization: one speaker at a
            # time, with the model itself picking the dominant/transcribable
            # voice inside overlaps — built precisely for reconciling with a
            # mono ASR transcript, which is jclaw's only consumer. On the
            # JCLAW-565 debate benchmark (9.4% overlapped speech) it cut DER
            # from 12.5% to 7.9% vs the overlap-aware output by eliminating
            # double-claimed time, with identical turn attribution. Fall back
            # to the plain annotation for pipelines that don't expose it.
            annotation = getattr(result, "exclusive_speaker_diarization", None)
            if annotation is None:
                annotation = getattr(result, "speaker_diarization", result)
            labels = {}
            segments = []
            for turn, _, speaker in annotation.itertracks(yield_label=True):
                idx = labels.setdefault(speaker, len(labels))
                segments.append({"start": round(turn.start, 3),
                                 "end": round(turn.end, 3),
                                 "speaker": idx})
            # JCLAW-605: overlap regions from the overlap-aware annotation —
            # where re-attribution is even possible. The exclusive annotation
            # above intentionally has no overlaps.
            plain = getattr(result, "speaker_diarization", None)
            overlaps = []
            if plain is not None and hasattr(plain, "get_overlap"):
                for seg in plain.get_overlap():
                    overlaps.append({"start": round(seg.start, 3), "end": round(seg.end, 3)})
            seconds = time.time() - t0
            sys.stderr.write("[diarize-sidecar] %s: %d segments, %d speaker(s), "
                             "%d overlap region(s) in %.1fs\n"
                             % (os.path.basename(audio_path), len(segments), len(labels),
                                len(overlaps), seconds))
            self._send_json(200, {"segments": segments,
                                  "overlaps": overlaps,
                                  "device": self.state.device,
                                  "seconds": round(seconds, 1)})
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(e)})
        finally:
            self.state.touch()
            self.state.run_lock.release()


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
            import subprocess
            t0 = time.time()
            script_dir = os.path.dirname(os.path.abspath(__file__))
            proc = subprocess.run(
                ["uv", "run", "transcribe.py", audio_path, model, language],
                cwd=script_dir, capture_output=True, text=True, timeout=1740)
            for line in proc.stderr.splitlines()[-10:]:
                sys.stderr.write("[diarize-sidecar] %s\n" % line)
            if proc.returncode != 0:
                raise RuntimeError("transcribe.py exited %d: %s"
                                   % (proc.returncode, proc.stderr.strip()[-300:]))
            payload = json.loads(proc.stdout.strip().splitlines()[-1])
            sys.stderr.write("[diarize-sidecar] transcribed %s: %d segment(s) in %.1fs\n"
                             % (os.path.basename(audio_path),
                                len(payload.get("segments", [])), time.time() - t0))
            self._send_json(200, payload)
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(e)})
        finally:
            self.state.touch()
            self.state.run_lock.release()

    def _handle_msdd(self):
        """JCLAW-612: MSDD second opinion via the msdd.py script env."""
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
        num_speakers = int(req.get("num_speakers") or 2)
        if not self.state.run_lock.acquire(blocking=False):
            self._send_json(409, {"error": "another inference is already in progress"})
            return
        try:
            import subprocess
            t0 = time.time()
            script_dir = os.path.dirname(os.path.abspath(__file__))
            proc = subprocess.run(
                ["uv", "run", "msdd.py", audio_path, str(num_speakers)],
                cwd=script_dir, capture_output=True, text=True, timeout=1740)
            for line in proc.stderr.splitlines()[-20:]:
                sys.stderr.write("[diarize-sidecar] %s\n" % line)
            if proc.returncode != 0:
                raise RuntimeError("msdd.py exited %d: %s"
                                   % (proc.returncode, proc.stderr.strip()[-300:]))
            payload = json.loads(proc.stdout.strip().splitlines()[-1])
            sys.stderr.write("[diarize-sidecar] msdd: %d segment(s) in %.1fs\n"
                             % (len(payload.get("segments", [])), time.time() - t0))
            self._send_json(200, payload)
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(e)})
        finally:
            self.state.touch()
            self.state.run_lock.release()

    def _handle_separate(self):
        """JCLAW-605: batch MossFormer2 separation via the separate.py
        script env (clearvoice is numpy<2, incompatible with this env)."""
        self.state.touch()
        try:
            req = self._read_json()
        except (ValueError, UnicodeDecodeError) as e:
            self._send_json(400, {"error": "invalid JSON body: %s" % e})
            return
        paths = req.get("audio_paths") or ([req["audio_path"]] if req.get("audio_path") else [])
        if not paths or not all(os.path.isfile(p) for p in paths):
            self._send_json(400, {"error": "audio_paths missing or not files: %r" % paths})
            return
        if not self.state.run_lock.acquire(blocking=False):
            self._send_json(409, {"error": "another inference is already in progress"})
            return
        try:
            import subprocess
            t0 = time.time()
            script_dir = os.path.dirname(os.path.abspath(__file__))
            proc = subprocess.run(
                ["uv", "run", "separate.py", *paths],
                cwd=script_dir, capture_output=True, text=True, timeout=1740)
            for line in proc.stderr.splitlines():
                sys.stderr.write("[diarize-sidecar] %s\n" % line)
            if proc.returncode != 0:
                raise RuntimeError("separate.py exited %d: %s"
                                   % (proc.returncode, proc.stderr.strip()[-300:]))
            payload = json.loads(proc.stdout.strip().splitlines()[-1])
            if "error" in payload:
                raise RuntimeError(payload["error"])
            sys.stderr.write("[diarize-sidecar] separated %d window(s) in %.1fs\n"
                             % (len(paths), time.time() - t0))
            self._send_json(200, payload)
        except Exception as e:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(e)})
        finally:
            self.state.touch()
            self.state.run_lock.release()


def _prewarm(state: SidecarState):
    """JCLAW-632: build the script envs and prefetch weights in the
    background so cold-start cliffs (multi-GB NeMo env, MossFormer2 and
    whisper weights) never land inside a user's first request. Never runs
    while an inference holds the run lock; failures are logged and the
    lazy in-request path remains the fallback."""
    import subprocess
    script_dir = os.path.dirname(os.path.abspath(__file__))
    steps = [
        # Env resolution only — no model load, cheap after first run.
        (["uv", "sync", "--script", "transcribe.py"], "transcribe env"),
        (["uv", "sync", "--script", "separate.py"], "separate env"),
        (["uv", "sync", "--script", "msdd.py"], "msdd env"),
    ]
    for cmd, label in steps:
        while state.run_lock.locked():
            time.sleep(15)  # a real request owns the machine — wait
        try:
            t0 = time.time()
            proc = subprocess.run(cmd, cwd=script_dir, capture_output=True,
                                  text=True, timeout=1740)
            sys.stderr.write("[diarize-sidecar] prewarm %s: rc=%d in %.0fs\n"
                             % (label, proc.returncode, time.time() - t0))
        except Exception as e:  # noqa: BLE001 — prewarm must never hurt
            sys.stderr.write("[diarize-sidecar] prewarm %s failed: %s\n" % (label, e))
    state.touch()  # don't let prewarm time count as idle


def _idle_watcher(state: SidecarState):
    """Self-evict after the idle timeout so the daemon releases device memory
    when unused. 0 disables eviction."""
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(min(60.0, state.idle_timeout_s))
        # JCLAW-619: an in-flight inference holds run_lock but does not touch
        # last_activity — a single run longer than the idle timeout (CPU-only
        # is ~0.2x realtime) must never be killed mid-inference.
        if state.run_lock.locked():
            continue
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
    threading.Thread(target=_prewarm, args=(Handler.state,), daemon=True).start()
    sys.stderr.write("[diarize-sidecar] listening on http://%s:%d (model=%s)\n"
                     % (args.host, args.port, args.model))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
