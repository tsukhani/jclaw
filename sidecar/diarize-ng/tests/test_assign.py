"""Tests for word->speaker assignment and turn building (assign.py).

Pure logic, tested for real: midpoint containment, max-overlap fallback,
nearest fallback, empty segments, no-mutation, and turn grouping including
honest handling of unattributed words.
"""

from __future__ import annotations

from diarng.assign import assign_speakers, build_turns
from diarng.types import Segment, Word


def test_midpoint_containment():
    segments = [Segment(0.0, 2.0, 0), Segment(2.0, 4.0, 1)]
    words = [
        Word(0.5, 1.0, "hi"),  # mid 0.75 -> seg 0
        Word(2.5, 3.0, "yo"),  # mid 2.75 -> seg 1
    ]
    out = assign_speakers(words, segments)
    assert [w.speaker for w in out] == [0, 1]


def test_boundary_goes_to_later_segment():
    # A midpoint exactly on a shared boundary is attributed to the later
    # (half-open) segment, deterministically.
    segments = [Segment(0.0, 1.0, 0), Segment(1.0, 2.0, 1)]
    word = Word(0.5, 1.5, "x")  # mid == 1.0
    out = assign_speakers([word], segments)
    assert out[0].speaker == 1


def test_max_overlap_fallback():
    # Midpoint lands in the silent gap [1, 3); span overlaps seg1 more than seg0.
    segments = [Segment(0.0, 1.0, 0), Segment(3.0, 5.0, 1)]
    word = Word(0.8, 3.5, "long")  # mid 2.15 in gap; overlap seg0=0.2, seg1=0.5
    out = assign_speakers([word], segments)
    assert out[0].speaker == 1


def test_nearest_fallback_zero_overlap():
    segments = [Segment(0.0, 1.0, 0), Segment(10.0, 11.0, 1)]
    word = Word(2.0, 3.0, "x")  # overlaps nothing; nearest is seg0 (gap 1 < 7)
    out = assign_speakers([word], segments)
    assert out[0].speaker == 0


def test_touching_segment_is_nearest_not_overlap():
    # Zero-overlap touching at a boundary must not count as overlap, but is the
    # nearest segment (gap 0).
    segments = [Segment(0.0, 2.0, 3)]
    word = Word(2.0, 3.0, "x")  # overlap == 0, gap == 0
    out = assign_speakers([word], segments)
    assert out[0].speaker == 3


def test_empty_segments_keep_none():
    words = [Word(0.0, 1.0, "a"), Word(1.0, 2.0, "b")]
    out = assign_speakers(words, [])
    assert [w.speaker for w in out] == [None, None]


def test_no_mutation_returns_new_objects():
    segments = [Segment(0.0, 2.0, 7)]
    word = Word(0.5, 1.0, "hi")  # speaker defaults to None
    out = assign_speakers([word], segments)
    assert out[0].speaker == 7
    assert out[0] is not word  # fresh object
    assert word.speaker is None  # input untouched


def test_build_turns_groups_consecutive_and_strips():
    words = [
        Word(0.0, 1.0, " Hello ", 0),
        Word(1.0, 2.0, "world", 0),
        Word(2.0, 3.0, "Hi", 1),
        Word(3.0, 4.0, "there", 1),
    ]
    turns = build_turns(words, names={0: "Alice", 1: "Bob"})
    assert len(turns) == 2
    assert turns[0].speaker == 0
    assert turns[0].text == "Hello world"
    assert turns[0].name == "Alice"
    assert (turns[0].start, turns[0].end) == (0.0, 2.0)
    assert len(turns[0].words) == 2
    assert turns[1].speaker == 1
    assert turns[1].text == "Hi there"
    assert turns[1].name == "Bob"


def test_build_turns_default_names_none():
    turns = build_turns([Word(0.0, 1.0, "a", 0)])
    assert turns[0].name is None


def test_build_turns_unattributed_opens_negative_one_turn():
    # A None-speaker word breaks the surrounding real-speaker run rather than
    # merging silently.
    words = [
        Word(0.0, 1.0, "a", 0),
        Word(1.0, 2.0, "b", None),
        Word(2.0, 3.0, "c", 0),
    ]
    turns = build_turns(words, names={0: "Alice"})
    assert [t.speaker for t in turns] == [0, -1, 0]
    assert turns[1].text == "b"
    assert turns[1].name is None
    assert turns[0].name == "Alice"


def test_build_turns_consecutive_unattributed_merge():
    words = [Word(0.0, 1.0, "a", None), Word(1.0, 2.0, "b", None)]
    turns = build_turns(words)
    assert len(turns) == 1
    assert turns[0].speaker == -1
    assert turns[0].text == "a b"


def test_build_turns_empty():
    assert build_turns([]) == []
