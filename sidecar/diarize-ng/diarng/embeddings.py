"""Speaker embeddings over sliding windows.

The clustering stage needs a fixed-dimension speaker-embedding vector per
short window of speech. This module turns the segmentation timeline into
windows, extracts an embedding per window through a pluggable backend
(`Embedder`), and provides the two vector operations the downstream
clustering/identity stages share: per-speaker centroids and cosine
similarity.

Only numpy is imported eagerly. The one model-backed embedder
(`SherpaWeSpeakerEmbedder`) imports `sherpa_onnx` lazily inside
`_extractor()`, so `import diarng.embeddings` costs nothing on a
numpy/scipy-only install.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

import numpy as np

from diarng.types import SAMPLE_RATE, Segment, Span

# Tolerance for the "does a full window still fit inside the segment?" test.
# Windows are placed at i*hop from the segment start; the multiply keeps
# float drift tiny, and this epsilon stops a window whose end lands on the
# boundary (e.g. 1.5 + 1.5 == 3.0) from being dropped by rounding noise.
_EPS = 1e-9


def window_spans(
    segments,
    win_s: float = 1.5,
    hop_s: float = 0.75,
    min_s: float = 0.5,
) -> list[tuple[Span, int]]:
    """Slide fixed windows *inside* each segment, never crossing its edges.

    Speaker embeddings are only meaningful over single-speaker audio, so a
    window must stay within one segment (which carries one local speaker).
    For each segment, ordered by the caller's order:

    - duration < ``min_s``          -> skipped (too little signal to embed).
    - ``min_s`` <= duration < ``win_s`` -> one whole-segment window.
    - duration >= ``win_s``         -> windows of length ``win_s`` stepped by
      ``hop_s``, keeping only those that fit entirely inside the segment.
      The sub-``win_s`` tail past the last full window is dropped (it would
      be a partial, lower-quality embedding of the same speaker already
      covered by the preceding window).

    Each window is paired with the segment's local speaker index so the
    embedding rows stay aligned to a speaker after clustering.

    Args:
        segments: iterable of `Segment` (local speaker indices).
        win_s: window length in seconds.
        hop_s: step between window starts in seconds.
        min_s: shortest segment worth embedding, in seconds.

    Returns:
        List of (Span, speaker) in segment/window order.
    """
    out: list[tuple[Span, int]] = []
    for seg in segments:
        dur = seg.duration
        if dur < min_s:
            continue
        if dur < win_s:
            # Whole-segment window: shorter than a full window but still
            # enough audio to embed.
            out.append((Span(seg.start, seg.end), seg.speaker))
            continue
        i = 0
        while True:
            start = seg.start + i * hop_s
            end = start + win_s
            if end > seg.end + _EPS:
                break
            # Clamp the end to the segment edge to absorb float drift so the
            # window is provably inside [seg.start, seg.end].
            out.append((Span(start, min(end, seg.end)), seg.speaker))
            i += 1
    return out


@runtime_checkable
class Embedder(Protocol):
    """A speaker-embedding backend: waveform chunks -> unit vectors.

    The seam between this module and any concrete model. `embed_batch`
    takes a list of `SAMPLE_RATE` mono float32 chunks and returns an
    ``(N, D)`` array whose rows are L2-normalized (unit-norm), so cosine
    similarity reduces to a dot product downstream. `runtime_checkable`
    lets tests assert a fake satisfies the protocol.
    """

    def embed_batch(self, chunks: list[np.ndarray]) -> np.ndarray:
        """Embed N waveform chunks into an ``(N, D)`` unit-norm matrix."""
        ...


def _l2_normalize_rows(x: np.ndarray) -> np.ndarray:
    """Scale each row to unit L2 norm; leave all-zero rows untouched."""
    norms = np.linalg.norm(x, axis=1, keepdims=True)
    # Avoid divide-by-zero: a zero row stays zero (norm treated as 1).
    norms = np.where(norms == 0.0, 1.0, norms)
    return x / norms


class SherpaWeSpeakerEmbedder:
    """WeSpeaker ONNX embeddings via sherpa-onnx's extractor stream API.

    Uses the same WeSpeaker ONNX family as the production JCLAW-565 sidecar,
    so the cosine thresholds tuned there (~0.3 cluster / ~0.6 match) are
    bit-compatible here. `sherpa_onnx` is imported lazily in `_extractor()`
    and the extractor is built once and reused across `embed_batch` calls.
    """

    def __init__(self, model_path: str, num_threads: int = 4):
        self.model_path = model_path
        self.num_threads = num_threads
        self._ex = None  # built on first use in _extractor()

    def _extractor(self):
        """Lazily build (and cache) the sherpa-onnx embedding extractor."""
        if self._ex is None:
            import sherpa_onnx

            self._ex = sherpa_onnx.SpeakerEmbeddingExtractor(
                sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                    model=self.model_path,
                    num_threads=self.num_threads,
                )
            )
        return self._ex

    def embed_batch(self, chunks: list[np.ndarray]) -> np.ndarray:
        """Extract one embedding per chunk; return unit-norm ``(N, D)`` rows.

        Each chunk is pushed through a fresh sherpa stream
        (accept_waveform -> input_finished -> compute), matching the
        production sidecar's per-window extraction.
        """
        if not chunks:
            # No windows to embed (e.g. all-silence input): the dimension is
            # unknown without the model, so hand back an empty (0, 0) matrix.
            return np.empty((0, 0), dtype=np.float32)
        ex = self._extractor()
        rows = []
        for chunk in chunks:
            samples = np.ascontiguousarray(chunk, dtype=np.float32)
            stream = ex.create_stream()
            stream.accept_waveform(SAMPLE_RATE, samples)
            stream.input_finished()
            rows.append(np.asarray(ex.compute(stream), dtype=np.float32))
        return _l2_normalize_rows(np.vstack(rows))


def centroids(x: np.ndarray, labels) -> dict[int, np.ndarray]:
    """Per-speaker centroid: normalized mean of the rows, re-normalized.

    Standard speaker-verification practice is to average length-normalized
    embeddings and then L2-normalize the average, so each speaker is
    represented by a unit vector on the same hypersphere as the enrollment
    voiceprints and cosine scoring stays consistent (cf. Snyder et al.
    x-vectors; the length-normalized mean used by pyannote's clustering).

    Args:
        x: ``(N, D)`` embedding matrix.
        labels: length-N int labels (zero-based speaker/cluster indices).

    Returns:
        {label: (D,) unit-norm centroid}, one entry per distinct label.
    """
    x = np.asarray(x, dtype=np.float64)
    labels = np.asarray(labels)
    out: dict[int, np.ndarray] = {}
    for lab in np.unique(labels):
        rows = _l2_normalize_rows(x[labels == lab])
        mean = rows.mean(axis=0)
        norm = np.linalg.norm(mean)
        if norm > 0.0:
            mean = mean / norm
        out[int(lab)] = mean.astype(np.float32)
    return out


def cosine(a, b) -> float:
    """Cosine similarity of two vectors; 0.0 if either has zero norm."""
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    if denom == 0.0:
        return 0.0
    return float(np.dot(a, b) / denom)
