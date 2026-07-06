"""Localhost HTTP daemon for diarize-ng.

A long-running daemon that holds the :class:`~diarng.pipeline.Diarizer`
resident so the heavy models load once and stay warm between calls. It mirrors
the conventions of the production JCLAW-565 sidecar (``sidecar/diarize/serve.py``):

  * Bound to 127.0.0.1 only. Audio is passed **by path**, never uploaded —
    the caller runs on the same host, so streaming tens of MB through a
    localhost socket buys nothing.
  * ``GET  /health`` -> ``{status, device, model, loaded}``.
  * ``POST /diarize`` ``{audio_path, num_speakers?}`` -> the
    :class:`~diarng.types.DiarizationResult` as JSON (see
    :func:`diarng.pipeline.result_to_dict`).
  * A single non-blocking run lock: one diarization at a time, a concurrent
    request gets ``409`` rather than thrashing the device.
  * Idle self-eviction after ``--idle-timeout-min`` (``0`` disables), and
    never mid-inference — an in-flight run holds the lock and is not killed.

Only stdlib + :mod:`diarng.pipeline` are imported here; the pipeline pulls its
model backends lazily on the first ``/diarize``.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from diarng.pipeline import Diarizer, DiarizerConfig, result_to_dict


class ServerState:
    """Shared daemon state: the resident diarizer, the run lock, idle clock."""

    def __init__(self, config: DiarizerConfig, idle_timeout_s: float):
        self.config = config
        self.idle_timeout_s = idle_timeout_s
        self.last_activity = time.monotonic()
        self.run_lock = threading.Lock()
        self._diarizer: Diarizer | None = None
        self.device: str | None = config.device

    def touch(self) -> None:
        self.last_activity = time.monotonic()

    def diarizer(self) -> Diarizer:
        """Build the diarizer on first use; reuse it (warm models) after."""
        if self._diarizer is None:
            self._diarizer = Diarizer(self.config)
        return self._diarizer

    @property
    def loaded(self) -> bool:
        return self._diarizer is not None


class Handler(BaseHTTPRequestHandler):
    state: ServerState = None  # set on the class in serve()

    # ---- plumbing --------------------------------------------------------
    def log_message(self, fmt, *args):  # noqa: A002 - BaseHTTPRequestHandler API
        sys.stderr.write("[diarize-ng] %s\n" % (fmt % args))

    def _send_json(self, code: int, payload: dict) -> None:
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
            pass  # client hung up mid-response — not worth a traceback.

    # ---- endpoints -------------------------------------------------------
    def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path == "/health":
            # A health probe signals imminent use: touch the idle clock so the
            # evict race between a caller's check and its POST stays closed.
            self.state.touch()
            self._send_json(
                200,
                {
                    "status": "ok",
                    "device": self.state.device,
                    "model": self.state.config.segmentation_model,
                    "loaded": self.state.loaded,
                },
            )
        else:
            self._send_json(404, {"error": "unknown path %s" % self.path})

    def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path != "/diarize":
            self._send_json(404, {"error": "unknown path %s" % self.path})
            return

        self.state.touch()
        try:
            req = self._read_json()
        except (ValueError, UnicodeDecodeError) as exc:
            self._send_json(400, {"error": "invalid JSON body: %s" % exc})
            return

        audio_path = req.get("audio_path")
        if not audio_path or not os.path.isfile(audio_path):
            self._send_json(
                400, {"error": "audio_path missing or not a file: %r" % audio_path}
            )
            return
        num_speakers = req.get("num_speakers")

        # One diarization at a time — a second concurrent run would thrash the
        # device. Callers queue upstream.
        if not self.state.run_lock.acquire(blocking=False):
            self._send_json(409, {"error": "a diarization is already in progress"})
            return
        try:
            t0 = time.time()
            result = self.state.diarizer().diarize(
                audio_path,
                num_speakers=int(num_speakers) if num_speakers else None,
            )
            self.state.device = result.meta.get("device") or self.state.device
            payload = result_to_dict(result)
            sys.stderr.write(
                "[diarize-ng] %s: %d turn(s), %d speaker(s) in %.1fs\n"
                % (
                    os.path.basename(audio_path),
                    len(result.turns),
                    result.num_speakers,
                    time.time() - t0,
                )
            )
            self._send_json(200, payload)
        except Exception as exc:  # noqa: BLE001 — reported to the client verbatim
            self._send_json(500, {"error": str(exc)})
        finally:
            self.state.touch()
            self.state.run_lock.release()


def _idle_watcher(state: ServerState) -> None:
    """Self-evict after the idle timeout so an unused daemon frees its memory.

    ``0`` disables eviction. An in-flight inference holds ``run_lock`` but does
    not touch ``last_activity``, so a single run longer than the timeout is
    never killed mid-inference.
    """
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(min(60.0, state.idle_timeout_s))
        if state.run_lock.locked():
            continue
        if time.monotonic() - state.last_activity >= state.idle_timeout_s:
            sys.stderr.write(
                "[diarize-ng] idle for %.0fs — exiting\n" % state.idle_timeout_s
            )
            os._exit(0)


def serve(
    config: DiarizerConfig,
    host: str = "127.0.0.1",
    port: int = 9531,
    idle_timeout_min: float = 15.0,
) -> None:
    """Start the blocking HTTP server on ``host:port`` (127.0.0.1 by default)."""
    Handler.state = ServerState(config, idle_timeout_min * 60.0)
    server = ThreadingHTTPServer((host, port), Handler)
    threading.Thread(
        target=_idle_watcher, args=(Handler.state,), daemon=True
    ).start()
    sys.stderr.write(
        "[diarize-ng] listening on http://%s:%d (model=%s)\n"
        % (host, port, config.segmentation_model)
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


def main(argv: list[str] | None = None) -> int:
    """``python -m diarng.serve`` entry point."""
    ap = argparse.ArgumentParser(description="diarize-ng localhost daemon")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9531)
    ap.add_argument("--segmentation-model", default=DiarizerConfig.segmentation_model)
    ap.add_argument("--asr-model", default=DiarizerConfig.asr_model)
    ap.add_argument("--embedding-model", default=None, help="sherpa WeSpeaker ONNX")
    ap.add_argument("--voiceprint-store", default=None)
    ap.add_argument("--language", default=None)
    ap.add_argument("--device", default=None)
    ap.add_argument(
        "--hf-token", default=os.environ.get("HF_TOKEN"), help="Hugging Face token"
    )
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    args = ap.parse_args(argv)

    config = DiarizerConfig(
        segmentation_model=args.segmentation_model,
        asr_model=args.asr_model,
        embedding_model_path=args.embedding_model,
        voiceprint_store_path=args.voiceprint_store,
        language=args.language,
        device=args.device,
        hf_token=args.hf_token,
    )
    serve(config, host=args.host, port=args.port, idle_timeout_min=args.idle_timeout_min)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
