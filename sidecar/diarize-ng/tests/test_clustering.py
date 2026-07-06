"""Tests for global clustering (clustering.py): NME-SC + VBx resegmentation.

All pure-logic, tested for real on synthetic seeded data -- no models, no
network. Cluster labels are only defined up to a permutation, so results are
scored with a permutation-invariant accuracy helper (optimal label matching
via the Hungarian algorithm).
"""

from __future__ import annotations

import numpy as np
from scipy.optimize import linear_sum_assignment

from diarng.clustering import (
    VBxReseg,
    labels_to_segments,
    nme_spectral_cluster,
)
from diarng.types import Span


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def cluster_accuracy(true: np.ndarray, pred: np.ndarray) -> float:
    """Permutation-invariant clustering accuracy in [0, 1].

    Builds the label confusion matrix and finds the label permutation that
    maximizes agreement (Hungarian assignment), then returns the matched
    fraction. Works for unequal numbers of true/predicted labels.
    """
    true = np.asarray(true)
    pred = np.asarray(pred)
    t_labels = np.unique(true)
    p_labels = np.unique(pred)
    counts = np.zeros((len(t_labels), len(p_labels)), dtype=int)
    for i, tl in enumerate(t_labels):
        for j, pl in enumerate(p_labels):
            counts[i, j] = np.sum((true == tl) & (pred == pl))
    rows, cols = linear_sum_assignment(-counts)
    return counts[rows, cols].sum() / len(true)


def make_sphere_clusters(
    rng: np.random.Generator,
    k: int,
    n_per: int,
    dim: int = 64,
    spread: float = 0.1,
) -> tuple[np.ndarray, np.ndarray]:
    """`k` well-separated Gaussian blobs projected onto the unit sphere.

    Random high-dimensional centers are near-orthogonal (cross-cluster cosine
    ~ 0), while a small `spread` keeps within-cluster cosine ~ 1 -- i.e. the
    "well-separated" regime the diarizer is expected to nail. Rows are grouped
    by ground-truth label.
    """
    centers = rng.standard_normal((k, dim))
    centers /= np.linalg.norm(centers, axis=1, keepdims=True)
    blocks = []
    labels = []
    for i in range(k):
        pts = centers[i] + spread * rng.standard_normal((n_per, dim))
        pts /= np.linalg.norm(pts, axis=1, keepdims=True)
        blocks.append(pts)
        labels.extend([i] * n_per)
    return np.vstack(blocks), np.array(labels)


# --------------------------------------------------------------------------- #
# NME-SC
# --------------------------------------------------------------------------- #
def test_recovers_two_clusters():
    rng = np.random.default_rng(0)
    X, y = make_sphere_clusters(rng, k=2, n_per=20)
    labels = nme_spectral_cluster(X)
    assert len(np.unique(labels)) == 2
    assert cluster_accuracy(y, labels) >= 0.98


def test_recovers_three_clusters():
    rng = np.random.default_rng(1)
    X, y = make_sphere_clusters(rng, k=3, n_per=20)
    labels = nme_spectral_cluster(X)
    assert len(np.unique(labels)) == 3
    assert cluster_accuracy(y, labels) >= 0.98


def test_eigengap_auto_k_finds_k():
    # The auto (num_speakers=None) path must recover the true k from the
    # maximum eigengap on clean data, for several k.
    for k in (2, 3, 4):
        rng = np.random.default_rng(100 + k)
        X, y = make_sphere_clusters(rng, k=k, n_per=18)
        labels = nme_spectral_cluster(X)
        assert len(np.unique(labels)) == k
        assert cluster_accuracy(y, labels) >= 0.97


def test_num_speakers_override_respected():
    # 3 real clusters, but the caller demands 2 -> exactly 2 labels out.
    rng = np.random.default_rng(2)
    X, _ = make_sphere_clusters(rng, k=3, n_per=20)
    labels = nme_spectral_cluster(X, num_speakers=2)
    assert len(np.unique(labels)) == 2


def test_single_cluster_stays_one():
    # One blob -> the max eigengap is at k=1; must not be over-split.
    rng = np.random.default_rng(3)
    base = rng.standard_normal(64)
    base /= np.linalg.norm(base)
    pts = base + 0.02 * rng.standard_normal((30, 64))
    pts /= np.linalg.norm(pts, axis=1, keepdims=True)
    labels = nme_spectral_cluster(pts)
    assert len(np.unique(labels)) == 1


def test_small_n_graceful():
    # N < 3 has no graph; behavior is defined and total.
    assert nme_spectral_cluster(np.zeros((0, 4))).shape == (0,)
    assert list(nme_spectral_cluster(np.ones((1, 4)))) == [0]

    two = np.random.default_rng(0).standard_normal((2, 4))
    assert len(np.unique(nme_spectral_cluster(two, num_speakers=2))) == 2
    assert len(np.unique(nme_spectral_cluster(two))) == 1


def test_labels_have_expected_shape_and_dtype():
    rng = np.random.default_rng(4)
    X, _ = make_sphere_clusters(rng, k=2, n_per=15)
    labels = nme_spectral_cluster(X)
    assert labels.shape == (X.shape[0],)
    assert np.issubdtype(labels.dtype, np.integer)


# --------------------------------------------------------------------------- #
# VBx resegmentation
# --------------------------------------------------------------------------- #
def _turn_sequence(rng, dim=32, noise=0.15):
    """Two speakers taking turns over time; returns (X, spans, true_labels)."""
    c0 = rng.standard_normal(dim)
    c0 /= np.linalg.norm(c0)
    c1 = rng.standard_normal(dim)
    c1 /= np.linalg.norm(c1)
    true = np.array([0] * 25 + [1] * 25 + [0] * 25 + [1] * 25)
    n = len(true)
    centers = np.where(true[:, None] == 0, c0, c1)
    X = centers + noise * rng.standard_normal((n, dim))
    X /= np.linalg.norm(X, axis=1, keepdims=True)
    spans = [Span(i * 0.75, i * 0.75 + 1.5) for i in range(n)]
    return X, spans, true


def test_vbx_repairs_planted_flips():
    rng = np.random.default_rng(10)
    X, spans, true = _turn_sequence(rng)
    n = len(true)

    # Plant 10% random label flips in the temporally-ordered sequence.
    flip_idx = rng.choice(n, size=n // 10, replace=False)
    init = true.copy()
    init[flip_idx] = 1 - init[flip_idx]
    wrong_before = int((init != true).sum())
    assert wrong_before == n // 10  # sanity: the flips landed

    out = VBxReseg().resegment(X, spans, init)
    assert len(out) == n
    # Labels are id-preserving ({0,1}), so a direct compare is meaningful.
    wrong_after = int((out != true).sum())

    # Most flips repaired, and no worse than before.
    assert wrong_after < wrong_before
    assert wrong_after <= 0.3 * wrong_before
    assert cluster_accuracy(true, out) >= 0.98


def test_vbx_restores_input_order():
    # Feeding shuffled rows must yield the same per-window labels as the
    # in-order call, just permuted back to the shuffled input order.
    rng = np.random.default_rng(11)
    X, spans, true = _turn_sequence(rng)
    init = true.copy()
    init[rng.choice(len(true), size=8, replace=False)] ^= 1

    reseg = VBxReseg()
    ordered = reseg.resegment(X, spans, init)

    perm = rng.permutation(len(true))
    shuffled = reseg.resegment(
        X[perm], [spans[i] for i in perm], init[perm]
    )
    assert np.array_equal(shuffled, ordered[perm])


def test_vbx_single_cluster_is_noop():
    rng = np.random.default_rng(12)
    init = np.zeros(10, dtype=int)
    X = rng.standard_normal((10, 8))
    spans = [Span(float(i), float(i) + 1.0) for i in range(10)]
    out = VBxReseg().resegment(X, spans, init)
    assert np.array_equal(out, init)


def test_vbx_drops_collapsed_cluster():
    # A speaker present in the init labeling but with no supporting embeddings
    # (all rows sit on speaker 0's center) should be dropped: the output uses a
    # single surviving speaker.
    rng = np.random.default_rng(13)
    dim = 16
    center = rng.standard_normal(dim)
    center /= np.linalg.norm(center)
    n = 30
    X = center + 0.05 * rng.standard_normal((n, dim))
    X /= np.linalg.norm(X, axis=1, keepdims=True)
    spans = [Span(float(i), float(i) + 1.0) for i in range(n)]
    # Spurious second label on a handful of frames that really belong to 0.
    init = np.zeros(n, dtype=int)
    init[[3, 17, 24]] = 1
    out = VBxReseg().resegment(X, spans, init)
    assert len(np.unique(out)) == 1


def test_vbx_simultaneous_drops_preserve_distinct_speakers():
    # Regression: when two or more low-occupancy states drop in the SAME
    # iteration and a frame's entire soft mass sat on those dropped states, the
    # occupancy-collapse renormalization used to compute 0/0 -> NaN, which then
    # poisoned every surviving speaker's mean and collapsed the whole
    # resegmentation to a single label -- silently merging distinct speakers.
    # Here speakers 0 and 1 are two tight, well-separated blocks; 2 and 3 are
    # singleton clusters placed at the same point (ambiguous between each other
    # and far from 0/1), so both drop at once and the shared frames zero out.
    rng = np.random.default_rng(3)
    dim = 24
    c0 = rng.standard_normal(dim); c0 /= np.linalg.norm(c0)
    c1 = rng.standard_normal(dim); c1 /= np.linalg.norm(c1)
    v = rng.standard_normal(dim); v /= np.linalg.norm(v)

    rows, init = [], []
    for _ in range(15):
        rows.append(c0 + 0.005 * rng.standard_normal(dim)); init.append(0)
    for _ in range(15):
        rows.append(c1 + 0.005 * rng.standard_normal(dim)); init.append(1)
    rows.append(v.copy()); init.append(2)
    rows.append(v.copy()); init.append(3)
    X = np.array(rows); X /= np.linalg.norm(X, axis=1, keepdims=True)
    init = np.array(init)
    spans = [Span(i * 0.75, i * 0.75 + 1.5) for i in range(len(init))]

    # Under the bug, the occupancy-collapse renormalization does a 0/0 divide;
    # trapping "invalid" turns that into a hard failure here.
    with np.errstate(invalid="raise", divide="raise"):
        out = VBxReseg().resegment(X, spans, init)

    # The two genuinely distinct speakers must NOT be merged into one label.
    assert out[0] != out[15]
    assert len(np.unique(out[:30])) == 2


# --------------------------------------------------------------------------- #
# labels_to_segments
# --------------------------------------------------------------------------- #
def test_labels_to_segments_collapses_runs():
    spans = [Span(0, 1), Span(1, 2), Span(2, 3), Span(3, 4)]
    labels = [0, 0, 1, 1]
    segs = labels_to_segments(labels, spans)
    assert len(segs) == 2
    assert (segs[0].start, segs[0].end, segs[0].speaker) == (0, 2, 0)
    assert (segs[1].start, segs[1].end, segs[1].speaker) == (2, 4, 1)


def test_labels_to_segments_overlapping_windows_merge():
    # Overlapping same-speaker windows (hop < win) collapse to one segment.
    spans = [Span(0.0, 1.5), Span(0.75, 2.25), Span(1.5, 3.0)]
    labels = [0, 0, 0]
    segs = labels_to_segments(labels, spans)
    assert len(segs) == 1
    assert (segs[0].start, segs[0].end, segs[0].speaker) == (0.0, 3.0, 0)


def test_labels_to_segments_gap_splits_same_speaker():
    # A silence longer than the merge gap keeps same-speaker windows separate.
    spans = [Span(0.0, 1.0), Span(5.0, 6.0)]
    labels = [0, 0]
    segs = labels_to_segments(labels, spans)
    assert len(segs) == 2
