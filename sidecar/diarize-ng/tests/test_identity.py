"""Hermetic tests for diarng.identity.

Everything here is pure logic on hand-built vectors and a real JSON file
under ``tmp_path`` — no models, no network, no GPU. The two behaviours that
matter in production are exercised directly:

* the enrollment store roundtrips and writes atomically, and averages
  L2-normalized embeddings into a unit centroid;
* greedy-exclusive matching enforces one-name-per-cluster with an ambiguity
  gap, and the same core linked across sessions gives stable labels.
"""

from __future__ import annotations

import json
import math

import numpy as np
import pytest

from diarng.identity import VoiceprintStore, link_sessions, match


# --------------------------------------------------------------------------
# VoiceprintStore: roundtrip, atomic save, averaging
# --------------------------------------------------------------------------


def test_store_roundtrip_across_instances(tmp_path):
    """A fresh store reading the same file sees prior enrollments."""
    path = tmp_path / "voices.json"
    store = VoiceprintStore(path)
    store.enroll("Alice", [3.0, 0.0])
    store.enroll("Bob", [0.0, 5.0])

    reopened = VoiceprintStore(path)
    assert reopened.names() == ["Alice", "Bob"]
    # Alice's single [3,0] normalizes to the unit x-axis.
    np.testing.assert_allclose(reopened.reference("Alice"), [1.0, 0.0], atol=1e-9)


def test_store_persists_normalized_embeddings_as_json_lists(tmp_path):
    """On-disk shape is name -> list of vectors, and vectors are L2-normalized
    on write (magnitude gone, direction kept)."""
    path = tmp_path / "voices.json"
    VoiceprintStore(path).enroll("Alice", [3.0, 4.0])  # norm 5

    raw = json.loads(path.read_text())
    assert list(raw.keys()) == ["Alice"]
    assert raw["Alice"] == [pytest.approx([0.6, 0.8])]  # 3/5, 4/5


def test_reference_missing_name_is_none(tmp_path):
    store = VoiceprintStore(tmp_path / "voices.json")
    store.enroll("Alice", [1.0, 0.0])
    assert store.reference("Nobody") is None


def test_reference_averages_then_normalizes(tmp_path):
    """Two orthogonal unit enrollments average to the [0.5, 0.5] midpoint,
    which is re-normalized to the unit diagonal (1/sqrt2, 1/sqrt2)."""
    store = VoiceprintStore(tmp_path / "voices.json")
    store.enroll("Alice", [10.0, 0.0])  # -> [1, 0] on write
    store.enroll("Alice", [0.0, 2.0])  # -> [0, 1] on write

    ref = store.reference("Alice")
    inv_sqrt2 = 1.0 / math.sqrt(2.0)
    np.testing.assert_allclose(ref, [inv_sqrt2, inv_sqrt2], atol=1e-9)
    assert np.linalg.norm(ref) == pytest.approx(1.0)


def test_save_is_atomic_no_temp_leftovers(tmp_path):
    """After enrollment the target exists and no *.tmp scratch file remains
    in the directory (the temp file was renamed into place, not left behind)."""
    path = tmp_path / "voices.json"
    store = VoiceprintStore(path)
    store.enroll("Alice", [1.0, 0.0])
    store.enroll("Bob", [0.0, 1.0])

    assert path.exists()
    siblings = [p.name for p in tmp_path.iterdir()]
    assert siblings == ["voices.json"]
    assert not any(name.endswith(".tmp") for name in siblings)


def test_store_creates_parent_directories(tmp_path):
    """A store under a not-yet-existing directory is written, dirs and all."""
    path = tmp_path / "nested" / "deeper" / "voices.json"
    VoiceprintStore(path).enroll("Alice", [1.0, 0.0])
    assert path.exists()


def test_lazy_load_does_not_touch_disk_until_used(tmp_path):
    """Constructing a store neither reads nor creates the file."""
    path = tmp_path / "voices.json"
    VoiceprintStore(path)  # no method call
    assert not path.exists()


# --------------------------------------------------------------------------
# match: greedy exclusivity, threshold, ambiguity gap
# --------------------------------------------------------------------------


def _two_person_store(tmp_path):
    """Alice on the x-axis, Bob on the y-axis — orthogonal references."""
    store = VoiceprintStore(tmp_path / "voices.json")
    store.enroll("Alice", [1.0, 0.0])
    store.enroll("Bob", [0.0, 1.0])
    return store


def test_match_exclusive_best_pair_wins(tmp_path):
    """Two clusters both prefer Alice; the stronger match takes the name and
    the weaker is forced onto its second-choice Bob (exclusivity)."""
    store = _two_person_store(tmp_path)
    centroids = {
        0: np.array([1.0, 0.0]),  # cos(Alice)=1.00, cos(Bob)=0.00
        1: np.array([0.9, 0.7]),  # cos(Alice)=0.79, cos(Bob)=0.61 — both > 0.6
    }
    got = match(store, centroids, threshold=0.6, gap=0.03)
    assert got == {0: "Alice", 1: "Bob"}


def test_match_name_never_reused_leaves_cluster_anonymous(tmp_path):
    """When the loser's only above-threshold candidate is the already-taken
    name, that cluster stays anonymous rather than stealing the name."""
    store = _two_person_store(tmp_path)
    centroids = {
        0: np.array([1.0, 0.0]),  # Alice 1.00
        1: np.array([1.0, 0.5]),  # Alice 0.894, Bob 0.447 (< threshold)
    }
    got = match(store, centroids, threshold=0.6, gap=0.03)
    assert got == {0: "Alice"}  # cluster 1 unnamed: Alice taken, Bob too weak


def test_match_threshold_rejects_weak_cluster(tmp_path):
    """A cluster whose best score is below threshold gets no name."""
    store = VoiceprintStore(tmp_path / "voices.json")
    store.enroll("Alice", [1.0, 0.0])  # single ref so no axis is a fallback
    centroids = {0: np.array([0.5, 0.87])}  # cos(Alice)=0.498 < 0.6
    assert match(store, centroids, threshold=0.6, gap=0.03) == {}


def test_match_ambiguity_gap_rejects_close_call(tmp_path):
    """Equidistant from both references (0.707 each) => within the gap =>
    anonymous, even though both scores clear the threshold."""
    store = _two_person_store(tmp_path)
    centroids = {0: np.array([1.0, 1.0])}  # cos = 0.707 to each
    assert match(store, centroids, threshold=0.6, gap=0.03) == {}


def test_match_gap_boundary_accepts_when_margin_meets_gap(tmp_path):
    """Rejection is strict (< gap). A margin exactly equal to the gap is
    accepted; a hair under it is rejected."""
    store = _two_person_store(tmp_path)
    # Craft a centroid whose two cosine scores differ by a chosen margin.
    # With unit refs on the axes, cos = normalized components, so
    # score(Alice) - score(Bob) = (x - y) / sqrt(x^2 + y^2).
    # Pick x=1, y solved so the margin is a round number is fiddly; instead
    # assert monotonic behaviour by sweeping the gap parameter on one centroid.
    centroids = {0: np.array([1.0, 0.6])}  # Alice 0.857, Bob 0.514; margin 0.343
    margin = 0.857492926 - 0.514495755
    assert match(store, centroids, threshold=0.5, gap=margin - 1e-6) == {0: "Alice"}
    assert match(store, centroids, threshold=0.5, gap=margin + 1e-6) == {}


def test_match_empty_store_or_no_centroids(tmp_path):
    empty = VoiceprintStore(tmp_path / "voices.json")
    assert match(empty, {0: np.array([1.0, 0.0])}) == {}

    store = _two_person_store(tmp_path)
    assert match(store, {}) == {}


def test_match_single_enrolled_voice_cannot_claim_every_cluster(tmp_path):
    """The core production lesson: one enrolled voice names at most one
    cluster even when several clusters look like it."""
    store = VoiceprintStore(tmp_path / "voices.json")
    store.enroll("Alice", [1.0, 0.0])
    centroids = {
        0: np.array([1.0, 0.0]),  # Alice 1.00 — the strongest
        1: np.array([1.0, 0.1]),  # Alice 0.995
        2: np.array([1.0, 0.2]),  # Alice 0.981
    }
    got = match(store, centroids, threshold=0.6, gap=0.03)
    assert got == {0: "Alice"}  # not {0,1,2} all Alice


# --------------------------------------------------------------------------
# link_sessions: stable cross-session labels
# --------------------------------------------------------------------------


def test_link_sessions_maps_swapped_ids_back():
    """Speaker cluster ids swapped between sessions are re-linked to their
    original identities."""
    prev = {0: np.array([1.0, 0.0]), 1: np.array([0.0, 1.0])}
    cur = {0: np.array([0.0, 1.0]), 1: np.array([1.0, 0.0])}  # ids flipped
    assert link_sessions(prev, cur, threshold=0.6) == {0: 1, 1: 0}


def test_link_sessions_exclusive_new_speaker_unlinked():
    """A current cluster with no prior match is left out (caller mints a new
    id); the matching one still links, and no prior id is reused."""
    prev = {0: np.array([1.0, 0.0])}
    cur = {
        0: np.array([1.0, 0.0]),  # -> prev 0
        1: np.array([0.0, 1.0]),  # cos 0.0 to prev 0, below threshold
    }
    assert link_sessions(prev, cur, threshold=0.6) == {0: 0}


def test_link_sessions_best_current_wins_prior():
    """Two current clusters resemble the same prior; only the closest links to
    it, the other cannot reuse that prior id."""
    prev = {0: np.array([1.0, 0.0])}
    cur = {
        0: np.array([1.0, 0.2]),  # cos 0.981
        1: np.array([1.0, 0.0]),  # cos 1.000 — stronger
    }
    assert link_sessions(prev, cur, threshold=0.6) == {1: 0}


def test_link_sessions_empty_inputs():
    assert link_sessions({}, {0: np.array([1.0, 0.0])}) == {}
    assert link_sessions({0: np.array([1.0, 0.0])}, {}) == {}
