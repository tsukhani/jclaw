"""Global speaker clustering: NME-SC spectral clustering + VBx resegmentation.

This module turns per-window speaker embeddings (an ``(N, D)`` matrix, one row
per sliding window) into a global cluster label per window, then optionally
smooths that labeling with a lightweight HMM resegmentation pass.

Two algorithms live here, both cited to their source:

* :func:`nme_spectral_cluster` implements **NME-SC** (Normalized Maximum
  Eigengap Spectral Clustering), Park, Han, Georgiou & Narayanan (2020),
  "Auto-Tuning Spectral Clustering for Speaker Diarization Using Normalized
  Maximum Eigengap", IEEE Signal Processing Letters. NME-SC removes the two
  hand-tuned knobs of classic spectral diarization (the affinity binarization
  threshold and the speaker count) by scanning the row-wise ``p``-neighbor
  binarization and picking the ``p`` that minimizes ``p / g_p``, where ``g_p``
  is the maximum eigengap of that graph's Laplacian. The eigengap that wins
  also yields the speaker-count estimate.

* :class:`VBxReseg` is a deliberately simplified stand-in for **VBx**, Landini,
  Profant, Diez & Burget (2022), "Bayesian HMM clustering of x-vector sequences
  (VBx) in speaker diarization: theory, implementation and analysis on a wide
  variety of datasets", Computer Speech & Language. See the class docstring for
  exactly which parts of VBx are kept and which are dropped.

Only numpy/scipy are used; there are no heavy/optional dependencies here.
"""

from __future__ import annotations

import numpy as np
from scipy.cluster.vq import kmeans2
from scipy.linalg import eigh
from scipy.special import logsumexp

from diarng.types import Segment, Span, merge_adjacent

_VAR_FLOOR = 1e-6
"""Isotropic-variance floor. Identical embeddings would otherwise drive the
Gaussian variance to zero and make the log-likelihood diverge."""


# --------------------------------------------------------------------------- #
# NME-SC spectral clustering
# --------------------------------------------------------------------------- #
def nme_spectral_cluster(
    X: np.ndarray,
    num_speakers: int | None = None,
    max_speakers: int = 10,
) -> np.ndarray:
    """Cluster embedding rows into speakers via NME-SC (Park et al. 2020).

    Args:
        X: ``(N, D)`` array of per-window speaker embeddings.
        num_speakers: If given, forces the number of clusters; otherwise the
            count is estimated from the maximum eigengap.
        max_speakers: Upper bound on the auto-estimated speaker count.

    Returns:
        ``(N,)`` int array of zero-based cluster labels.

    Algorithm (per the paper):
        1. Cosine affinity ``A = X_hat @ X_hat.T`` on L2-normalized rows.
        2. For each candidate ``p``, keep each row's top-``p`` neighbors
           (self excluded), producing a binary graph, then symmetrize it.
        3. Build the *unnormalized* Laplacian ``L = D - A_p`` and take its
           eigenvalues; the max eigengap ``g_p`` gives a speaker-count guess.
        4. Choose ``p* = argmin_p (p / g_p)`` -- the NME criterion. Fewer
           connections per unit eigengap means a cleaner cluster structure.
        5. ``k`` = eigengap index at ``p*`` (capped at ``max_speakers``), or
           ``num_speakers`` when supplied.
        6. Spectral-embed with the ``k`` smallest eigenvectors of the ``p*``
           Laplacian and run deterministic k-means.
    """
    X = np.asarray(X, dtype=float)
    n = X.shape[0]

    # N < 3 has no meaningful graph structure: honor an explicit count by
    # round-robin, otherwise treat everything as one speaker.
    if n == 0:
        return np.zeros(0, dtype=int)
    if n < 3:
        if num_speakers is not None and num_speakers >= 1:
            return np.arange(n, dtype=int) % int(num_speakers)
        return np.zeros(n, dtype=int)

    affinity = _cosine_affinity(X)
    best_p, best_k, best_graph = _nme_select(affinity, _p_candidates(n), max_speakers)

    k = int(num_speakers) if num_speakers is not None else best_k
    k = max(1, min(k, n))
    if k == 1:
        return np.zeros(n, dtype=int)

    # Spectral embedding: the k eigenvectors of the smallest eigenvalues of the
    # unnormalized Laplacian. For a graph with k clean components these span the
    # component-indicator space, so k-means separates them exactly.
    degree = best_graph.sum(axis=1)
    laplacian = np.diag(degree) - best_graph
    _, eigvecs = eigh(laplacian)
    embedding = eigvecs[:, :k]
    return _kmeans(embedding, k)


def _cosine_affinity(X: np.ndarray) -> np.ndarray:
    """Cosine-similarity matrix of the rows of ``X`` (diagonal left at ~1)."""
    norms = np.linalg.norm(X, axis=1, keepdims=True)
    norms = np.where(norms == 0.0, 1.0, norms)
    unit = X / norms
    return unit @ unit.T


def _p_candidates(n: int) -> list[int]:
    """Candidate neighbor counts to scan.

    The upper bound follows the paper's practice of capping ``p`` at a small
    fraction of ``N`` (here 1/4): beyond that the graph starts fusing genuinely
    different speakers. For small ``N`` every integer in range is scanned; for
    large ``N`` the range is subsampled to keep the eigen-decompositions cheap.
    """
    p_max = max(2, min(n - 1, int(round(0.25 * n))))
    if p_max <= 30:
        return list(range(1, p_max + 1))
    return sorted({int(round(x)) for x in np.linspace(1, p_max, 30)})


def _binarize_symmetrize(affinity: np.ndarray, p: int) -> np.ndarray:
    """Keep each row's top-``p`` neighbors (excluding self), then symmetrize.

    Symmetrization averages the two directed adjacencies, so a mutual edge has
    weight 1.0 and a one-directional edge weight 0.5 -- the standard NME-SC
    construction that makes the Laplacian symmetric.
    """
    n = affinity.shape[0]
    work = affinity.copy()
    np.fill_diagonal(work, -np.inf)  # never pick self as a neighbor
    # argpartition on -work surfaces the p largest similarities per row.
    top = np.argpartition(-work, p - 1, axis=1)[:, :p]
    binary = np.zeros((n, n))
    rows = np.repeat(np.arange(n), p)
    binary[rows, top.ravel()] = 1.0
    return 0.5 * (binary + binary.T)


def _max_eigengap(eigvals: np.ndarray, max_speakers: int) -> tuple[int, float]:
    """Return ``(k, gap)`` for the largest gap among the low eigenvalues.

    Eigenvalues are ascending. A graph with ``k`` connected components has its
    ``k`` smallest eigenvalues near zero and a jump to the ``(k+1)``-th, so the
    index of the max gap is the component/speaker count. The search is capped at
    ``max_speakers`` so over-fragmentation into more pieces is not rewarded.
    """
    n = len(eigvals)
    k_max = min(max_speakers, n - 1)
    gaps = np.diff(eigvals[: k_max + 1])
    idx = int(np.argmax(gaps))
    return idx + 1, float(gaps[idx])


def _nme_select(
    affinity: np.ndarray, p_list: list[int], max_speakers: int
) -> tuple[int, int, np.ndarray]:
    """Pick ``p`` minimizing the NME ratio ``p / g_p``.

    Returns the winning ``(p, k, graph)`` where ``graph`` is the symmetrized
    adjacency used to build the final spectral embedding.
    """
    best: tuple[float, int, int, np.ndarray] | None = None
    for p in p_list:
        graph = _binarize_symmetrize(affinity, p)
        eigvals = eigh(np.diag(graph.sum(axis=1)) - graph, eigvals_only=True)
        k, gap = _max_eigengap(eigvals, max_speakers)
        if gap <= 0.0:  # degenerate graph (fully connected / empty); skip
            continue
        ratio = p / gap
        if best is None or ratio < best[0]:
            best = (ratio, p, k, graph)
    if best is None:
        # Every candidate was degenerate: fall back to a single speaker.
        p = p_list[0]
        return p, 1, _binarize_symmetrize(affinity, p)
    return best[1], best[2], best[3]


def _kmeans(embedding: np.ndarray, k: int) -> np.ndarray:
    """Deterministic k-means (kmeans++ seeding, fixed seed) on the embedding."""
    _, labels = kmeans2(embedding, k, minit="++", seed=0, iter=50)
    return labels.astype(int)


# --------------------------------------------------------------------------- #
# VBx-style HMM resegmentation
# --------------------------------------------------------------------------- #
class VBxReseg:
    """HMM resegmentation of an initial clustering, inspired by VBx.

    Honest statement of the simplification (VBx = Landini et al. 2022):

    * **Kept.** The sequence model: a per-window HMM whose hidden states are the
      speakers, with *sticky* self-transitions (``loop_prob`` on the diagonal)
      that penalize implausibly rapid speaker switching, and an
      expectation-maximization loop that re-estimates each speaker from the
      soft frame responsibilities until convergence, dropping speakers whose
      occupancy collapses.
    * **Dropped.** The full PLDA/x-vector Bayesian backend. VBx models each
      speaker with a PLDA latent variable and a per-speaker posterior; here each
      speaker is just a Gaussian in the L2-normalized embedding space with a
      **single shared isotropic covariance** estimated from the data. This is
      the cheap "spherical-Gaussian emissions" approximation, adequate for
      cleaning up an already-good spectral clustering but not a replacement for
      the PLDA scoring that makes full VBx a clustering method in its own right.

    Responsibilities are computed with the forward-backward algorithm (soft
    posteriors) rather than a hard Viterbi path. Soft posteriors match VBx's
    variational spirit and give smoother, more stable mean re-estimates when a
    window sits between two speakers.
    """

    def __init__(self, loop_prob: float = 0.99, max_iters: int = 10):
        self.loop_prob = float(loop_prob)
        self.max_iters = int(max_iters)
        # Occupancy (summed soft responsibility) below this drops a speaker.
        self.min_occupancy = 1.0

    def resegment(
        self, X: np.ndarray, spans: list[Span], init_labels: np.ndarray
    ) -> np.ndarray:
        """Refine ``init_labels`` with the HMM pass; ``len`` is preserved.

        ``spans`` are the (possibly unordered) window spans. The HMM assumes a
        time-ordered frame sequence, so windows are sorted by ``span.start``
        before the pass and the input order is restored on return.
        """
        X = np.asarray(X, dtype=float)
        init_labels = np.asarray(init_labels, dtype=int)
        n = X.shape[0]
        if n == 0:
            return init_labels.copy()

        order = np.argsort([s.start for s in spans], kind="stable")
        x_ord = X[order]
        labels_ord = init_labels[order]

        # L2-normalize into the space the Gaussian emissions live in.
        norms = np.linalg.norm(x_ord, axis=1, keepdims=True)
        norms = np.where(norms == 0.0, 1.0, norms)
        x_ord = x_ord / norms

        refined_ord = self._run_hmm(x_ord, labels_ord)

        out = np.empty(n, dtype=int)
        out[order] = refined_ord
        return out

    def _run_hmm(self, X: np.ndarray, init_labels: np.ndarray) -> np.ndarray:
        """EM over the sticky HMM. Input rows are already time-ordered."""
        n_frames = X.shape[0]
        state_ids = np.unique(init_labels)  # state s -> original label id
        if len(state_ids) <= 1 or n_frames < 2:
            return init_labels.copy()

        # Initialize means from the initial hard assignment.
        z = np.searchsorted(state_ids, init_labels)
        means = np.stack([X[z == s].mean(axis=0) for s in range(len(state_ids))])
        onehot = np.zeros((n_frames, len(state_ids)))
        onehot[np.arange(n_frames), z] = 1.0
        var = self._shared_variance(X, means, onehot)

        gamma = onehot
        for _ in range(self.max_iters):
            log_emit = self._log_emissions(X, means, var)
            log_pi, log_trans = self._hmm_params(len(state_ids))
            gamma = _forward_backward(log_emit, log_pi, log_trans)

            # Drop any speaker whose soft occupancy has collapsed.
            occ = gamma.sum(axis=0)
            keep = occ >= self.min_occupancy
            if keep.sum() < len(state_ids) and keep.any():
                state_ids = state_ids[keep]
                means = means[keep]
                gamma = gamma[:, keep]
                # A frame whose entire soft mass sat on the just-dropped states
                # now has a zero row-sum. Renormalizing it directly is 0/0 ->
                # NaN, and because the next M-step forms every surviving mean via
                # `gamma.T @ X`, that one NaN row poisons *all* means, turns the
                # whole EM loop to NaN, and collapses the resegmentation to a
                # single label -- silently merging genuinely distinct speakers.
                # Reassign each orphaned frame to its most likely surviving state
                # (using the emissions already computed this iteration) so gamma
                # stays a valid per-frame distribution and the means stay finite.
                row_sum = gamma.sum(axis=1, keepdims=True)
                orphaned = row_sum[:, 0] == 0.0
                if orphaned.any():
                    nearest = np.argmax(log_emit[orphaned][:, keep], axis=1)
                    gamma[orphaned] = 0.0
                    gamma[orphaned, nearest] = 1.0
                    row_sum[orphaned] = 1.0
                gamma /= row_sum
                occ = gamma.sum(axis=0)

            # M-step: responsibility-weighted means and shared variance.
            new_means = (gamma.T @ X) / occ[:, None]
            var = self._shared_variance(X, new_means, gamma)
            shift = float(np.linalg.norm(new_means - means))
            means = new_means
            if shift < 1e-4:
                break

        return state_ids[np.argmax(gamma, axis=1)]

    def _log_emissions(
        self, X: np.ndarray, means: np.ndarray, var: float
    ) -> np.ndarray:
        """Log N(x | mu_s, var * I) for every frame/state -> ``(T, K)``."""
        dim = X.shape[1]
        sq_dist = ((X[:, None, :] - means[None, :, :]) ** 2).sum(axis=-1)
        return -0.5 * sq_dist / var - 0.5 * dim * np.log(2.0 * np.pi * var)

    def _shared_variance(
        self, X: np.ndarray, means: np.ndarray, gamma: np.ndarray
    ) -> float:
        """Pooled isotropic variance from soft (or one-hot) responsibilities."""
        dim = X.shape[1]
        sq_dist = ((X[:, None, :] - means[None, :, :]) ** 2).sum(axis=-1)
        numer = float((gamma * sq_dist).sum())
        denom = dim * float(gamma.sum())
        return max(numer / denom, _VAR_FLOOR)

    def _hmm_params(self, k: int) -> tuple[np.ndarray, np.ndarray]:
        """Uniform initial-state log-probs and the sticky log-transition matrix."""
        log_pi = np.full(k, -np.log(k))
        if k == 1:
            return log_pi, np.zeros((1, 1))
        trans = np.full((k, k), (1.0 - self.loop_prob) / (k - 1))
        np.fill_diagonal(trans, self.loop_prob)
        return log_pi, np.log(trans)


def _forward_backward(
    log_emit: np.ndarray, log_pi: np.ndarray, log_trans: np.ndarray
) -> np.ndarray:
    """Forward-backward posteriors ``gamma[t, s] = P(state_t = s | X)``.

    Computed entirely in log-space with ``logsumexp`` for numerical stability;
    ``O(T * K^2)``.
    """
    n_frames, k = log_emit.shape
    log_alpha = np.empty((n_frames, k))
    log_alpha[0] = log_pi + log_emit[0]
    for t in range(1, n_frames):
        log_alpha[t] = log_emit[t] + logsumexp(
            log_alpha[t - 1][:, None] + log_trans, axis=0
        )

    log_beta = np.zeros((n_frames, k))
    for t in range(n_frames - 2, -1, -1):
        log_beta[t] = logsumexp(
            log_trans + (log_emit[t + 1] + log_beta[t + 1])[None, :], axis=1
        )

    log_gamma = log_alpha + log_beta
    log_gamma -= logsumexp(log_gamma, axis=1, keepdims=True)
    return np.exp(log_gamma)


# --------------------------------------------------------------------------- #
# Windows -> segments
# --------------------------------------------------------------------------- #
def labels_to_segments(
    labels: np.ndarray, spans: list[Span], gap: float = 0.1
) -> list[Segment]:
    """Collapse consecutive same-label windows into speaker segments.

    Each window becomes a one-window :class:`Segment`; adjacent same-speaker
    windows separated by no more than ``gap`` seconds (they usually touch or
    overlap) are merged via :func:`diarng.types.merge_adjacent`, so the two
    stages never disagree on merge semantics.
    """
    segments = [
        Segment(span.start, span.end, int(label))
        for label, span in zip(labels, spans)
    ]
    return merge_adjacent(segments, gap=gap)
