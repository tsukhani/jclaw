"""Hermetic tests for diarng.embeddings.

Pure logic (windowing, centroids, cosine) is tested for real on hand
vectors. The model-backed `SherpaWeSpeakerEmbedder` is exercised through a
fake `sherpa_onnx` module injected into `sys.modules` — the real sherpa-onnx
package is never imported (no network, no model download, no GPU).
"""

from __future__ import annotations

import sys

import numpy as np
import pytest

from diarng.embeddings import (
    Embedder,
    SherpaWeSpeakerEmbedder,
    centroids,
    cosine,
    window_spans,
)
from diarng.types import SAMPLE_RATE, Segment, Span


# --------------------------------------------------------------------------
# window_spans
# --------------------------------------------------------------------------


def test_window_spans_hop_arithmetic():
    """A 3.0 s segment at win=1.5/hop=0.75 yields exactly three full windows
    at 0.00, 0.75, 1.50 — the last fits because 1.5 + 1.5 == 3.0."""
    seg = Segment(0.0, 3.0, speaker=0)
    got = window_spans([seg], win_s=1.5, hop_s=0.75, min_s=0.5)

    starts = [round(span.start, 6) for span, _ in got]
    ends = [round(span.end, 6) for span, _ in got]
    assert starts == [0.0, 0.75, 1.5]
    assert ends == [1.5, 2.25, 3.0]
    assert all(spk == 0 for _, spk in got)


def test_window_spans_offset_segment_arithmetic():
    """Windowing is relative to the segment start, not absolute zero."""
    seg = Segment(10.0, 13.0, speaker=4)
    got = window_spans([seg], win_s=1.5, hop_s=0.75, min_s=0.5)

    starts = [round(span.start, 6) for span, _ in got]
    ends = [round(span.end, 6) for span, _ in got]
    assert starts == [10.0, 10.75, 11.5]
    assert ends == [11.5, 12.25, 13.0]
    assert all(spk == 4 for _, spk in got)


def test_window_spans_short_segment_single_window():
    """min_s <= dur < win_s => one window covering the whole segment."""
    seg = Segment(2.0, 3.0, speaker=7)  # 1.0 s: >= min_s, < win_s
    got = window_spans([seg], win_s=1.5, hop_s=0.75, min_s=0.5)

    assert got == [(Span(2.0, 3.0), 7)]


def test_window_spans_exact_win_length_single_window():
    """A segment exactly one window long yields exactly one window."""
    seg = Segment(0.0, 1.5, speaker=0)
    got = window_spans([seg], win_s=1.5, hop_s=0.75, min_s=0.5)

    assert len(got) == 1
    span, spk = got[0]
    assert (round(span.start, 6), round(span.end, 6)) == (0.0, 1.5)
    assert spk == 0


def test_window_spans_min_s_skip():
    """dur < min_s => the segment produces no windows."""
    seg = Segment(5.0, 5.3, speaker=1)  # 0.3 s < min_s (0.5)
    assert window_spans([seg], win_s=1.5, hop_s=0.75, min_s=0.5) == []


def test_window_spans_never_crosses_segment_boundary():
    """Every window stays strictly inside its own segment; the local speaker
    index rides along so windows never leak across the boundary."""
    segs = [
        Segment(0.0, 3.0, speaker=0),
        Segment(3.0, 4.5, speaker=1),
    ]
    got = window_spans(segs, win_s=1.5, hop_s=0.75, min_s=0.5)

    for span, spk in got:
        owner = segs[spk]
        assert span.start >= owner.start - 1e-9
        assert span.end <= owner.end + 1e-9
    # Second segment is exactly one window long -> one window for speaker 1.
    assert [spk for _, spk in got].count(1) == 1


def test_window_spans_tail_past_last_full_window_is_dropped():
    """A 2.5 s segment: full windows at 0.0 and 0.75 fit (end 2.25); the
    0.25 s tail past 2.25 is not emitted as a partial window."""
    seg = Segment(0.0, 2.5, speaker=0)
    got = window_spans([seg], win_s=1.5, hop_s=0.75, min_s=0.5)

    starts = [round(span.start, 6) for span, _ in got]
    assert starts == [0.0, 0.75]
    assert all(span.end <= 2.5 + 1e-9 for span, _ in got)


# --------------------------------------------------------------------------
# cosine
# --------------------------------------------------------------------------


def test_cosine_orthogonal_identical_opposite():
    assert cosine([1.0, 0.0], [0.0, 1.0]) == pytest.approx(0.0)
    assert cosine([1.0, 2.0, 3.0], [1.0, 2.0, 3.0]) == pytest.approx(1.0)
    assert cosine([1.0, 0.0], [-1.0, 0.0]) == pytest.approx(-1.0)


def test_cosine_is_scale_invariant():
    assert cosine([3.0, 4.0], [6.0, 8.0]) == pytest.approx(1.0)


def test_cosine_zero_vector_returns_zero():
    assert cosine([0.0, 0.0], [1.0, 1.0]) == 0.0


# --------------------------------------------------------------------------
# centroids
# --------------------------------------------------------------------------


def test_centroids_one_per_label_and_unit_norm():
    x = np.array([[1.0, 0.0], [1.0, 0.0], [0.0, 1.0]])
    labels = [0, 0, 1]
    cents = centroids(x, labels)

    assert set(cents) == {0, 1}
    assert cents[0] == pytest.approx(np.array([1.0, 0.0]))
    assert cents[1] == pytest.approx(np.array([0.0, 1.0]))
    for c in cents.values():
        assert np.linalg.norm(c) == pytest.approx(1.0)


def test_centroids_normalizes_rows_before_averaging():
    """Rows of different magnitude but the same direction average to that
    direction (length-normalization removes the magnitude bias)."""
    x = np.array([[3.0, 4.0], [30.0, 40.0]])  # both point at [0.6, 0.8]
    cents = centroids(x, [0, 0])
    assert cents[0] == pytest.approx(np.array([0.6, 0.8]))


def test_centroids_renormalizes_the_mean():
    """Two orthogonal unit rows average to [0.5, 0.5]; re-normalizing gives
    the unit diagonal, not the raw mean."""
    x = np.array([[1.0, 0.0], [0.0, 1.0]])
    cents = centroids(x, [0, 0])
    inv_sqrt2 = 1.0 / np.sqrt(2.0)
    assert cents[0] == pytest.approx(np.array([inv_sqrt2, inv_sqrt2]))
    assert np.linalg.norm(cents[0]) == pytest.approx(1.0)


# --------------------------------------------------------------------------
# Embedder seam
# --------------------------------------------------------------------------


class FakeEmbedder:
    """A dependency-free Embedder for tests: deterministic unit vectors.

    Demonstrates the seam — anything with an `embed_batch` returning
    unit-norm ``(N, D)`` rows plugs into the pipeline without touching a
    model.
    """

    def __init__(self, dim: int = 8):
        self.dim = dim
        self.batch_sizes: list[int] = []

    def embed_batch(self, chunks: list[np.ndarray]) -> np.ndarray:
        self.batch_sizes.append(len(chunks))
        rng = np.random.default_rng(0)
        x = rng.standard_normal((len(chunks), self.dim)).astype(np.float32)
        norms = np.linalg.norm(x, axis=1, keepdims=True)
        return x / norms


def test_fake_embedder_satisfies_protocol():
    """runtime_checkable Protocol accepts a structural match."""
    assert isinstance(FakeEmbedder(), Embedder)


def test_fake_embedder_shape_and_norm():
    fake = FakeEmbedder(dim=5)
    chunks = [np.zeros(100, dtype=np.float32) for _ in range(3)]
    x = fake.embed_batch(chunks)

    assert x.shape == (3, 5)
    assert np.allclose(np.linalg.norm(x, axis=1), 1.0)
    assert fake.batch_sizes == [3]


def test_seam_end_to_end_centroids_from_fake_embedder():
    """The whole vector path works off the fake: embed -> centroid."""
    fake = FakeEmbedder(dim=4)
    x = fake.embed_batch([np.zeros(10, dtype=np.float32) for _ in range(4)])
    cents = centroids(x, [0, 0, 1, 1])
    assert set(cents) == {0, 1}
    for c in cents.values():
        assert np.linalg.norm(c) == pytest.approx(1.0)


# --------------------------------------------------------------------------
# SherpaWeSpeakerEmbedder — lazy import seam via a fake sherpa_onnx
# --------------------------------------------------------------------------


class _FakeStream:
    def __init__(self, sink):
        self._sink = sink
        self.samples = None

    def accept_waveform(self, rate, samples):
        self._sink["rate"] = rate
        self.samples = np.asarray(samples)

    def input_finished(self):
        self._sink["input_finished"] = True


class _FakeExtractor:
    """Returns a fixed 3-D, deliberately non-unit vector per chunk so the
    test can prove embed_batch L2-normalizes its output."""

    def __init__(self, config):
        self.config = config
        self.sink: dict = {}

    def create_stream(self):
        return _FakeStream(self.sink)

    def compute(self, stream):
        # Encode the chunk length so distinct chunks get distinct rows,
        # scaled well away from unit norm to expose missing normalization.
        n = float(stream.samples.size)
        return [n, 2.0 * n, 2.0 * n]


class _FakeConfig:
    def __init__(self, model, num_threads):
        self.model = model
        self.num_threads = num_threads


class _FakeSherpaModule:
    SpeakerEmbeddingExtractor = _FakeExtractor
    SpeakerEmbeddingExtractorConfig = _FakeConfig


def test_sherpa_embedder_construct_does_not_import_sherpa(monkeypatch):
    """Constructing the embedder must not touch sherpa_onnx (lazy seam)."""
    monkeypatch.setitem(sys.modules, "sherpa_onnx", None)  # would fail if used
    emb = SherpaWeSpeakerEmbedder("/models/wespeaker.onnx", num_threads=2)
    assert emb.model_path == "/models/wespeaker.onnx"
    assert emb.num_threads == 2
    assert emb._ex is None


def test_sherpa_embedder_embed_batch_normalizes_rows(monkeypatch):
    monkeypatch.setitem(sys.modules, "sherpa_onnx", _FakeSherpaModule())
    emb = SherpaWeSpeakerEmbedder("/models/wespeaker.onnx", num_threads=3)

    chunks = [
        np.ones(SAMPLE_RATE, dtype=np.float32),
        np.ones(2 * SAMPLE_RATE, dtype=np.float32),
    ]
    x = emb.embed_batch(chunks)

    assert x.shape == (2, 3)
    assert np.allclose(np.linalg.norm(x, axis=1), 1.0)
    # The extractor was configured with the constructor's thread count and
    # fed the canonical sample rate through the stream API.
    assert emb._extractor().config.num_threads == 3
    assert emb._extractor().sink["rate"] == SAMPLE_RATE
    assert emb._extractor().sink["input_finished"] is True


def test_sherpa_embedder_empty_batch_returns_empty(monkeypatch):
    monkeypatch.setitem(sys.modules, "sherpa_onnx", _FakeSherpaModule())
    emb = SherpaWeSpeakerEmbedder("/models/wespeaker.onnx")
    x = emb.embed_batch([])
    assert x.shape == (0, 0)
