"""Speaker identity: enrollment voiceprints, greedy-exclusive matching, and
cross-session speaker linking.

Two jobs live here, both built on one cosine-scored, greedy-exclusive
assignment core:

* ``VoiceprintStore`` persists enrolled reference voiceprints (a JSON file
  mapping a display name to a list of embedding vectors) and averages them
  into one unit-norm centroid per person.
* ``match`` attaches those names to the diarizer's anonymous speaker
  clusters; ``link_sessions`` carries cluster identities across recordings so
  a given speaker keeps a stable label between sessions.

Only ``numpy`` (a base dependency) is used — there are no heavy/optional
imports here, so this module is safe to import from ``import diarng``.
"""

from __future__ import annotations

import json
import os
import tempfile
from collections.abc import Hashable, Mapping
from pathlib import Path
from typing import TypeVar

import numpy as np

_Candidate = TypeVar("_Candidate", bound=Hashable)


# --------------------------------------------------------------------------
# vector helpers
# --------------------------------------------------------------------------


def _l2normalize(vec) -> np.ndarray:
    """Return ``vec`` scaled to unit L2 norm; a zero vector is returned as-is
    (there is no meaningful direction to normalize toward)."""
    arr = np.asarray(vec, dtype=np.float64)
    norm = float(np.linalg.norm(arr))
    if norm == 0.0:
        return arr
    return arr / norm


def _cosine(a, b) -> float:
    """Cosine similarity in [-1, 1]. Robust to un-normalized inputs; returns
    0.0 if either vector has zero magnitude (undefined direction)."""
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
    if denom == 0.0:
        return 0.0
    return float(np.dot(a, b) / denom)


# --------------------------------------------------------------------------
# enrollment store
# --------------------------------------------------------------------------


class VoiceprintStore:
    """A JSON-backed store of enrolled speaker voiceprints.

    The on-disk shape is ``{name: [[float, ...], ...]}`` — one list of
    embedding vectors per enrolled person. Embeddings are L2-normalized on
    write so that ``reference`` can average them into a unit-norm centroid
    without a loud/long clip dominating the mean.

    The file is loaded lazily (first access) and written atomically (temp
    file + ``os.replace``) so a crash mid-write can never truncate an existing
    store.
    """

    def __init__(self, path):
        self._path = Path(path)
        self._data: dict[str, list[list[float]]] | None = None  # lazy

    # -- persistence -------------------------------------------------------

    def _load(self) -> dict[str, list[list[float]]]:
        """Read the file into memory on first use; subsequent calls reuse the
        in-memory copy. A missing file is an empty store."""
        if self._data is None:
            if self._path.exists():
                with open(self._path, encoding="utf-8") as f:
                    self._data = json.load(f)
            else:
                self._data = {}
        return self._data

    def _save(self) -> None:
        """Persist the in-memory store atomically.

        Write to a temp file in the *same directory* (so ``os.replace`` is a
        same-filesystem rename, i.e. atomic) then swap it into place. Readers
        see either the old file or the new file, never a partial write.
        """
        self._path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(
            dir=str(self._path.parent), prefix=self._path.name + ".", suffix=".tmp"
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(self._data, f)
            os.replace(tmp, self._path)
        except BaseException:
            # Don't leave a stray temp file behind if the write/rename failed.
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass
            raise

    # -- public API --------------------------------------------------------

    def enroll(self, name: str, embedding) -> None:
        """Append one reference embedding for ``name`` (L2-normalized on
        write) and persist. Creates the person on first enrollment."""
        data = self._load()
        vec = _l2normalize(embedding)
        data.setdefault(name, []).append(vec.tolist())
        self._save()

    def names(self) -> list[str]:
        """Enrolled display names, sorted for deterministic iteration."""
        return sorted(self._load().keys())

    def reference(self, name: str) -> np.ndarray | None:
        """The averaged, L2-normalized centroid of ``name``'s enrollments, or
        ``None`` if the person is not enrolled.

        Because each stored embedding is already unit-norm, the mean is an
        unweighted average of directions; re-normalizing yields a clean unit
        centroid to cosine-score against.
        """
        embs = self._load().get(name)
        if not embs:
            return None
        centroid = np.asarray(embs, dtype=np.float64).mean(axis=0)
        return _l2normalize(centroid)


# --------------------------------------------------------------------------
# greedy-exclusive assignment (shared core)
# --------------------------------------------------------------------------


def _greedy_exclusive(
    scores_by_cluster: Mapping[int, Mapping[_Candidate, float]],
    threshold: float,
    gap: float,
) -> dict[int, _Candidate]:
    """Assign each cluster at most one candidate, each candidate to at most
    one cluster, greedily best-first — the shared core of ``match`` and
    ``link_sessions``.

    Algorithm (production lesson from jclaw ``SpeakerNamer.assignExclusive``,
    JCLAW-606): score every (cluster, candidate) pair, then repeatedly take
    the single best remaining pair whose score clears ``threshold``, skipping
    any cluster or candidate already claimed. Two safeguards make this robust:

    * **Exclusivity.** Because each candidate is claimed at most once, the
      degenerate failure mode where *every* cluster independently matches the
      one enrolled voice is structurally impossible — the best cluster wins
      that name and the rest must look elsewhere or stay unassigned.
    * **Ambiguity gap.** A cluster whose top two candidate scores sit within
      ``gap`` of each other is dropped entirely (left unassigned) rather than
      guessed — the runner-up is too close to trust the winner.

    Ties in score fall back to the input's iteration order (stable sort),
    which callers make deterministic by feeding sorted candidate keys.

    Args:
        scores_by_cluster: cluster id -> {candidate -> cosine score}.
        threshold: minimum score for a pair to be eligible.
        gap: ambiguity margin; a cluster is dropped if its best two scores
            differ by strictly less than this. Pass 0.0 to disable.

    Returns:
        cluster id -> winning candidate, for assigned clusters only.
    """
    # (score, cluster, candidate) for every eligible, non-ambiguous pair.
    pairs: list[tuple[float, int, _Candidate]] = []
    for cluster, scores in scores_by_cluster.items():
        if not scores:
            continue
        ordered = sorted(scores.values(), reverse=True)
        if len(ordered) >= 2 and ordered[0] - ordered[1] < gap:
            continue  # runner-up too close to the winner — leave anonymous
        for candidate, score in scores.items():
            if score >= threshold:
                pairs.append((score, cluster, candidate))

    # Best score first; stable so equal scores keep input order.
    pairs.sort(key=lambda p: p[0], reverse=True)

    assignment: dict[int, _Candidate] = {}
    taken: set[_Candidate] = set()
    for _score, cluster, candidate in pairs:
        if cluster in assignment or candidate in taken:
            continue
        assignment[cluster] = candidate
        taken.add(candidate)
    return assignment


# --------------------------------------------------------------------------
# name matching and session linking
# --------------------------------------------------------------------------


def match(
    store: VoiceprintStore,
    centroids: Mapping[int, np.ndarray],
    threshold: float = 0.6,
    gap: float = 0.03,
) -> dict[int, str]:
    """Attach enrolled names to anonymous speaker clusters.

    Each cluster centroid is cosine-scored against every enrolled person's
    reference voiceprint, then names are assigned by the greedy-exclusive
    core (see ``_greedy_exclusive``): the best (cluster, name) pair above
    ``threshold`` wins, no name is used twice, and a cluster whose top two
    names are within ``gap`` is left anonymous. This greedy-exclusive +
    ambiguity-gap rule is what structurally prevents every cluster from
    matching a single enrolled voice (jclaw ``SpeakerNamer.assignExclusive``).

    Args:
        store: the enrollment store to match against.
        centroids: cluster id -> speaker centroid embedding (any scale; it is
            normalized here).
        threshold: minimum cosine similarity to accept a name.
        gap: ambiguity margin (see ``_greedy_exclusive``).

    Returns:
        cluster id -> display name, for matched clusters only (empty when
        there is no enrollment or no cluster clears the bar).
    """
    names = store.names()
    if not names or not centroids:
        return {}

    references = {name: store.reference(name) for name in names}
    references = {n: r for n, r in references.items() if r is not None}
    if not references:
        return {}

    scores_by_cluster: dict[int, dict[str, float]] = {}
    for cluster, centroid in centroids.items():
        vec = _l2normalize(centroid)
        scores_by_cluster[cluster] = {
            name: _cosine(vec, ref) for name, ref in references.items()
        }
    return _greedy_exclusive(scores_by_cluster, threshold, gap)


def link_sessions(
    prev: Mapping[int, np.ndarray],
    cur: Mapping[int, np.ndarray],
    threshold: float = 0.6,
) -> dict[int, int]:
    """Map this session's cluster ids onto the previous session's ids so a
    speaker keeps a stable label across recordings.

    Each current centroid is cosine-scored against every previous centroid
    and linked with the same greedy-exclusive core used by ``match`` — the
    best (current, previous) pair above ``threshold`` wins and no previous id
    is reused, so two current clusters can never collapse onto one prior
    speaker. Current clusters with no confident prior (a newly appearing
    speaker) are simply absent from the result and should get a fresh id.

    Ambiguity rejection is disabled here (gap 0.0): linking is a best-effort
    relabeling, and an unlinked cluster is handled gracefully by the caller
    minting a new id, so there is no need to abstain on close calls.

    Args:
        prev: previous session's cluster id -> centroid embedding.
        cur: current session's cluster id -> centroid embedding.
        threshold: minimum cosine similarity to link.

    Returns:
        current cluster id -> previous cluster id, for linked clusters only.
    """
    if not prev or not cur:
        return {}

    prev_refs = {pid: _l2normalize(vec) for pid, vec in prev.items()}
    scores_by_cluster: dict[int, dict[int, float]] = {}
    for cid, vec in cur.items():
        cvec = _l2normalize(vec)
        scores_by_cluster[cid] = {
            pid: _cosine(cvec, pref) for pid, pref in prev_refs.items()
        }
    return _greedy_exclusive(scores_by_cluster, threshold, gap=0.0)
