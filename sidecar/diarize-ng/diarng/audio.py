"""Audio ingestion: decode -> 16 kHz mono float32, loudness norm, chunk plan.

This is the first stage in the cascade (see the module map in README.md). It
owns the boundary between "whatever container/codec the user handed us" and the
canonical in-memory representation the rest of the pipeline assumes: a
contiguous ``float32`` numpy array of mono PCM sampled at ``types.SAMPLE_RATE``
(16 kHz), amplitudes in roughly ``[-1, 1]``.

Decoding is delegated to an ``ffmpeg`` subprocess rather than a Python codec
library: ffmpeg already lives in the JCLAW sidecar toolchain, handles every
container we care about, and keeps heavy audio deps out of this package. Only
numpy and the stdlib are imported here.
"""

from __future__ import annotations

import subprocess
import threading
import wave

import numpy as np

from diarng.types import SAMPLE_RATE, Span

# float32 PCM is 4 bytes/sample; used to convert a seconds ceiling to a byte
# budget and to size the frombuffer reinterpretation.
_BYTES_PER_SAMPLE = 4
# How much of ffmpeg's stderr to surface on failure. ffmpeg is chatty; the last
# lines carry the actual error ("No such file", "Invalid data", ...).
_STDERR_TAIL_CHARS = 4000


def load_pcm(path, max_seconds: float = 7200.0) -> np.ndarray:
    """Decode ``path`` to mono 16 kHz ``float32`` PCM via an ffmpeg subprocess.

    ffmpeg is asked to emit raw little-endian float samples on stdout
    (``-f f32le -ac 1 -ar 16000``). We drain stdout on the calling thread while
    a helper thread drains stderr, so neither pipe can fill and deadlock the
    other for a long recording (the classic single-pipe subprocess hang).

    Args:
        path: any container/codec ffmpeg can open.
        max_seconds: hard ceiling on decoded duration. Reading is aborted the
            moment the byte budget is exceeded, so a mistakenly huge input can't
            balloon memory; a too-low ceiling raises with an actionable message.

    Returns:
        1-D ``float32`` array of length ``round(duration * SAMPLE_RATE)``.

    Raises:
        RuntimeError: ffmpeg is missing, the decode failed (stderr tail
            included), or the input is longer than ``max_seconds``.
    """
    max_bytes = int(max_seconds * SAMPLE_RATE) * _BYTES_PER_SAMPLE
    cmd = [
        "ffmpeg",
        "-nostdin",  # never try to read the controlling terminal
        "-loglevel",
        "error",  # keep stderr to actual errors so the tail is meaningful
        "-i",
        str(path),
        "-f",
        "f32le",
        "-acodec",
        "pcm_f32le",
        "-ac",
        "1",
        "-ar",
        str(SAMPLE_RATE),
        "-",  # write PCM to stdout
    ]

    try:
        proc = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
    except FileNotFoundError as exc:
        raise RuntimeError(
            "ffmpeg not found on PATH; install ffmpeg to decode audio"
        ) from exc

    stderr_buf = bytearray()

    def _drain_stderr() -> None:
        # read() blocks until EOF, collecting the whole error stream; runs on a
        # side thread so it can't back-pressure the stdout read below.
        try:
            data = proc.stderr.read()
            if data:
                stderr_buf.extend(data)
        except (OSError, ValueError):
            pass  # process torn down mid-read; nothing useful to add.

    stderr_thread = threading.Thread(target=_drain_stderr, daemon=True)
    stderr_thread.start()

    pcm_buf = bytearray()
    overflow = False
    try:
        while True:
            block = proc.stdout.read(1 << 20)  # 1 MiB
            if not block:
                break
            pcm_buf.extend(block)
            if len(pcm_buf) > max_bytes:
                overflow = True
                break
    finally:
        proc.stdout.close()

    if overflow:
        proc.kill()  # stop the decode; we've already blown the ceiling.
    proc.wait()
    stderr_thread.join()

    if overflow:
        raise RuntimeError(
            f"{path}: audio exceeds the max_seconds={max_seconds:g}s "
            f"(~{max_seconds / 60:.0f} min) ceiling. Raise max_seconds or "
            "split the recording into shorter chunks before decoding."
        )

    if proc.returncode != 0:
        tail = stderr_buf.decode("utf-8", "replace").strip()[-_STDERR_TAIL_CHARS:]
        raise RuntimeError(
            f"ffmpeg failed to decode {path} (exit {proc.returncode}): {tail}"
        )

    # Trim any trailing partial sample (only possible on a truncated stream)
    # so the reinterpret is always a whole number of float32 values. The array
    # shares memory with pcm_buf, which its .base keeps alive — no extra copy.
    usable = len(pcm_buf) - (len(pcm_buf) % _BYTES_PER_SAMPLE)
    return np.frombuffer(memoryview(pcm_buf)[:usable], dtype="<f4")


def loudness_normalize(pcm: np.ndarray, target_dbfs: float = -23.0) -> np.ndarray:
    """Scale ``pcm`` to a target RMS level, clamping peaks at 0.99.

    A single global gain moves the signal's RMS to ``target_dbfs`` dBFS (0 dBFS
    == full-scale amplitude 1.0, so ``rms_target = 10**(target_dbfs/20)``). This
    is intentionally *not* a full loudness model (no K-weighting / true-peak): a
    plain RMS match is enough to keep segmentation and embedding models in the
    amplitude range they were trained on, and it's cheap and deterministic.

    After applying the gain, if the loudest sample would exceed 0.99 the whole
    signal is scaled down so its peak sits at 0.99, avoiding hard clipping when
    a quiet recording with a stray transient gets a large gain.

    Silence-safe: a signal whose RMS is ~0 would need an unbounded gain, so it
    is returned unchanged.
    """
    pcm = np.asarray(pcm, dtype=np.float32)
    if pcm.size == 0:
        return pcm

    # Accumulate in float64 so the mean-of-squares doesn't lose precision on
    # long recordings; the comparison/gain stay well-conditioned.
    rms = float(np.sqrt(np.mean(np.square(pcm, dtype=np.float64))))
    if rms < 1e-8:
        return pcm  # effectively silence — no meaningful level to normalize.

    target_rms = 10.0 ** (target_dbfs / 20.0)
    out = pcm * np.float32(target_rms / rms)

    peak = float(np.max(np.abs(out)))
    if peak > 0.99:
        out = out * np.float32(0.99 / peak)

    return out.astype(np.float32, copy=False)


def chunk_spans(
    duration_s: float, chunk_s: float = 600.0, overlap_s: float = 30.0
) -> list[Span]:
    """Plan overlapping chunks covering ``[0, duration_s]``.

    Long recordings are decoded and segmented chunk-by-chunk to bound memory;
    consecutive chunks overlap by ``overlap_s`` so a speaker turn straddling a
    boundary is seen whole by at least one chunk (the overlap is later used to
    stitch labels across boundaries). Windows advance by ``chunk_s - overlap_s``.

    A recording shorter than ``chunk_s`` yields a single span. A trailing
    fragment whose length is shorter than ``overlap_s`` is merged into the
    previous chunk: such a sliver is already fully inside the window before it,
    so a separate chunk would only add redundant model work.

    Args:
        duration_s: total recording length in seconds.
        chunk_s: target chunk length (> 0).
        overlap_s: neighbour overlap in ``[0, chunk_s)``.
    """
    if chunk_s <= 0:
        raise ValueError("chunk_s must be positive")
    if not 0 <= overlap_s < chunk_s:
        raise ValueError("overlap_s must be in [0, chunk_s)")

    duration_s = float(duration_s)
    if duration_s <= chunk_s:
        return [Span(0.0, max(duration_s, 0.0))]

    step = chunk_s - overlap_s
    spans: list[Span] = []
    start = 0.0
    while start < duration_s:
        spans.append(Span(start, min(start + chunk_s, duration_s)))
        start += step

    # Fold a too-short tail into its predecessor (which already covers it).
    if len(spans) >= 2 and spans[-1].duration < overlap_s:
        prev = spans[-2]
        spans[-2] = Span(prev.start, max(prev.end, spans[-1].end))
        spans.pop()

    return spans


def write_wav_pcm16(pcm: np.ndarray, path) -> None:
    """Write ``pcm`` to ``path`` as a 16 kHz mono PCM16 RIFF WAV.

    Float amplitudes are clipped to ``[-1, 1]`` and quantized to signed 16-bit
    little-endian (the WAV byte order). Used for debugging dumps and to hand a
    chunk to tools that only speak WAV; the stdlib ``wave`` module keeps it
    dependency-free.
    """
    pcm = np.asarray(pcm, dtype=np.float32)
    clipped = np.clip(pcm, -1.0, 1.0)
    # Symmetric scale by 32767 keeps +1.0 and -1.0 within int16 range.
    ints = np.round(clipped * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(ints.tobytes())


def slice_pcm(pcm: np.ndarray, span: Span) -> np.ndarray:
    """Return the ``pcm`` samples inside ``span`` as a view, clamped to bounds.

    Times are converted to sample indices at ``SAMPLE_RATE`` and clamped to
    ``[0, len(pcm)]``, so a span reaching past either end yields the available
    portion (possibly empty) rather than raising. numpy basic slicing returns a
    view, so no audio is copied.
    """
    n = len(pcm)
    i0 = max(0, min(n, int(round(span.start * SAMPLE_RATE))))
    i1 = max(i0, min(n, int(round(span.end * SAMPLE_RATE))))
    return pcm[i0:i1]
