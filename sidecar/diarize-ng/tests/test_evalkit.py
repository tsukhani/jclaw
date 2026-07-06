"""Hand-computed tests for the evaluation harness.

Every DER/WER/cpWER expectation below is derived by hand in the comments so
the numbers stay auditable; no model, network or GPU is touched.
"""

from __future__ import annotations

import math

import pytest

from diarng import evalkit
from diarng.types import Segment, Span


# --------------------------------------------------------------------------- #
# RTTM / UEM IO
# --------------------------------------------------------------------------- #


def test_load_rttm_maps_names_to_ints_in_first_appearance_order(tmp_path):
    rttm = tmp_path / "ref.rttm"
    rttm.write_text(
        "SPEAKER file1 1 0.000 2.000 <NA> <NA> Bob <NA> <NA>\n"
        "SPEAKER file1 1 2.000 2.000 <NA> <NA> Alice <NA> <NA>\n"
        "SPEAKER file1 1 4.000 1.000 <NA> <NA> Bob <NA> <NA>\n"
        "SPEAKER file2 1 0.000 3.000 <NA> <NA> Carol <NA> <NA>\n"
        "; a comment line is ignored\n"
    )
    doc = evalkit.load_rttm(rttm)

    # Bob appears first -> 0, Alice second -> 1, per file.
    assert doc.names["file1"] == {0: "Bob", 1: "Alice"}
    assert doc.names["file2"] == {0: "Carol"}
    f1 = doc.segments["file1"]
    assert [(s.start, s.end, s.speaker) for s in f1] == [
        (0.0, 2.0, 0),
        (2.0, 4.0, 1),
        (4.0, 5.0, 0),
    ]


def test_save_rttm_roundtrips(tmp_path):
    segs = [Segment(0.0, 1.5, 0), Segment(1.5, 3.0, 1)]
    out = tmp_path / "hyp.rttm"
    evalkit.save_rttm(segs, "meeting", out, names={0: "Bob", 1: "Alice"})

    doc = evalkit.load_rttm(out)
    assert doc.names["meeting"] == {0: "Bob", 1: "Alice"}
    got = [(s.start, s.end, s.speaker) for s in doc.segments["meeting"]]
    assert got == [(0.0, 1.5, 0), (1.5, 3.0, 1)]


def test_load_uem(tmp_path):
    uem = tmp_path / "scored.uem"
    uem.write_text(
        "file1 1 1.00 9.00\n"
        "file1 1 12.00 20.00\n"
        "file2 1 0.00 5.00\n"
    )
    spans = evalkit.load_uem(uem)
    assert spans["file1"] == [Span(1.0, 9.0), Span(12.0, 20.0)]
    assert spans["file2"] == [Span(0.0, 5.0)]


# --------------------------------------------------------------------------- #
# DER
# --------------------------------------------------------------------------- #


def test_der_zero_on_identical():
    ref = [Segment(0, 5, 0), Segment(5, 10, 1)]
    hyp = [Segment(0, 5, 0), Segment(5, 10, 1)]
    out = evalkit.der(ref, hyp)  # default collar 0.25
    assert out["der"] == pytest.approx(0.0)
    assert out["missed"] == pytest.approx(0.0)
    assert out["false_alarm"] == pytest.approx(0.0)
    assert out["confusion"] == pytest.approx(0.0)


def test_der_pure_confusion_known_rate():
    # One ref speaker for 10 s. Hyp splits it into two speakers of 5 s each.
    # The optimal mapping can claim only one hyp speaker => 5 s correct, 5 s
    # confused, regardless of which hyp speaker is matched. 5/10 = 0.5.
    ref = [Segment(0, 10, 0)]
    hyp = [Segment(0, 5, 0), Segment(5, 10, 1)]
    out = evalkit.der(ref, hyp, collar=0.0)
    assert out["confusion"] == pytest.approx(0.5)
    assert out["missed"] == pytest.approx(0.0)
    assert out["false_alarm"] == pytest.approx(0.0)
    assert out["der"] == pytest.approx(0.5)


def test_der_missed_speech():
    # Ref speaks 0..10; hyp only covers 0..5. Second half (5 s) is missed.
    ref = [Segment(0, 10, 0)]
    hyp = [Segment(0, 5, 0)]
    out = evalkit.der(ref, hyp, collar=0.0)
    assert out["missed"] == pytest.approx(0.5)  # 5 / 10
    assert out["false_alarm"] == pytest.approx(0.0)
    assert out["confusion"] == pytest.approx(0.0)
    assert out["der"] == pytest.approx(0.5)


def test_der_false_alarm():
    # Ref speaks 0..5; hyp speaks 0..10. Extra 5 s is false alarm. The
    # denominator is scored REF speaker time = 5 s, so FA rate = 5/5 = 1.0.
    ref = [Segment(0, 5, 0)]
    hyp = [Segment(0, 10, 0)]
    out = evalkit.der(ref, hyp, collar=0.0)
    assert out["false_alarm"] == pytest.approx(1.0)
    assert out["missed"] == pytest.approx(0.0)
    assert out["confusion"] == pytest.approx(0.0)
    assert out["der"] == pytest.approx(1.0)


def test_der_collar_excludes_boundary_error():
    # Hyp boundary is shifted from 5.0 to 5.2, so [5.0, 5.2] (0.2 s) is
    # attributed to the wrong speaker. That error sits inside the collar
    # [4.75, 5.25] around the ref boundary at 5.0.
    ref = [Segment(0, 5, 0), Segment(5, 10, 1)]
    hyp = [Segment(0, 5.2, 0), Segment(5.2, 10, 1)]

    # collar=0.25: the 0.2 s error is fully inside the no-score zone -> DER 0.
    assert evalkit.der(ref, hyp, collar=0.25)["der"] == pytest.approx(0.0)

    # collar=0: the error is scored. 0.2 s confusion / 10 s ref = 0.02.
    scored = evalkit.der(ref, hyp, collar=0.0)
    assert scored["der"] == pytest.approx(0.02)
    assert scored["confusion"] == pytest.approx(0.02)


def test_der_optimal_mapping_finds_label_swap():
    # Ref labels {0,1} correspond to hyp labels {1,0} (swapped). A naive
    # identity mapping (0->0, 1->1) would score everything as confusion
    # (DER 1.0); the global optimal assignment recovers the swap -> DER 0.
    ref = [Segment(0, 6, 0), Segment(6, 10, 1)]
    hyp = [Segment(0, 6, 1), Segment(6, 10, 0)]
    out = evalkit.der(ref, hyp, collar=0.0)
    assert out["der"] == pytest.approx(0.0)
    assert out["confusion"] == pytest.approx(0.0)


def test_der_respects_uem():
    # Ref/hyp disagree over 0..2 (confusion), agree over 2..10. A UEM that
    # only scores 2..10 excludes the disagreement -> DER 0.
    ref = [Segment(0, 2, 0), Segment(2, 10, 0)]
    hyp = [Segment(0, 2, 1), Segment(2, 10, 0)]
    uem = [Span(2.0, 10.0)]
    assert evalkit.der(ref, hyp, collar=0.0, uem=uem)["der"] == pytest.approx(0.0)
    # Without the UEM the 2 s confusion is scored: 2 / 10 = 0.2.
    assert evalkit.der(ref, hyp, collar=0.0)["der"] == pytest.approx(0.2)


def test_der_overlapping_reference_needs_two_hyp_speakers():
    # Both ref speakers active over 0..10 (overlap). Hyp offers only one
    # speaker -> exactly one of the two overlapping speakers is missed the
    # whole time. Denominator = 2 * 10 = 20 s; missed = 10 s -> 0.5.
    ref = [Segment(0, 10, 0), Segment(0, 10, 1)]
    hyp = [Segment(0, 10, 0)]
    out = evalkit.der(ref, hyp, collar=0.0)
    assert out["missed"] == pytest.approx(0.5)
    assert out["false_alarm"] == pytest.approx(0.0)
    assert out["der"] == pytest.approx(0.5)


# --------------------------------------------------------------------------- #
# WER
# --------------------------------------------------------------------------- #


def test_wer_substitution_and_insertion():
    # ref: a b c d   hyp: a x c d e
    # b->x substitution, trailing e inserted. 2 errors / 4 = 0.5.
    out = evalkit.wer(["a", "b", "c", "d"], ["a", "x", "c", "d", "e"])
    assert out["substitutions"] == 1
    assert out["insertions"] == 1
    assert out["deletions"] == 0
    assert out["errors"] == 2
    assert out["wer"] == pytest.approx(0.5)


def test_wer_deletion():
    # ref: a b c d   hyp: a c d  -> b deleted. 1 error / 4 = 0.25.
    out = evalkit.wer(["a", "b", "c", "d"], ["a", "c", "d"])
    assert out["substitutions"] == 0
    assert out["insertions"] == 0
    assert out["deletions"] == 1
    assert out["errors"] == 1
    assert out["wer"] == pytest.approx(0.25)


def test_wer_empty_reference():
    out = evalkit.wer([], ["a", "b"])
    assert out["insertions"] == 2
    assert out["errors"] == 2
    assert out["wer"] == math.inf


# --------------------------------------------------------------------------- #
# cpWER
# --------------------------------------------------------------------------- #


def test_cpwer_permutation_matters():
    # The correct pairing is the cross one (0<->B, 1<->A). Naive same-index
    # pairing would give 6 errors; the Hungarian assignment finds 0 errors.
    ref = {0: ["the", "cat", "sat"], 1: ["a", "dog", "ran"]}
    hyp = {0: ["a", "dog", "ran"], 1: ["the", "cat", "sat"]}

    # Sanity: the naive diagonal pairing really is wrong.
    naive = (
        evalkit.wer(ref[0], hyp[0])["errors"] + evalkit.wer(ref[1], hyp[1])["errors"]
    )
    assert naive == 6

    out = evalkit.cpwer(ref, hyp)
    assert out["errors"] == 0
    assert out["cpwer"] == pytest.approx(0.0)


def test_cpwer_extra_hyp_speaker_counts_as_insertions():
    # 1 ref speaker, 2 hyp speakers. The unmatched hyp speaker's 2 words are
    # pure insertions. 2 errors / 3 ref words = 2/3.
    ref = {0: ["a", "b", "c"]}
    hyp = {0: ["a", "b", "c"], 1: ["x", "y"]}
    out = evalkit.cpwer(ref, hyp)
    assert out["errors"] == 2
    assert out["insertions"] == 2
    assert out["deletions"] == 0
    assert out["ref_words"] == 3
    assert out["cpwer"] == pytest.approx(2 / 3)


def test_cpwer_missing_hyp_speaker_counts_as_deletions():
    # 2 ref speakers, 1 hyp speaker. The unmatched ref speaker's 3 words are
    # pure deletions. 3 errors / 5 ref words = 0.6.
    ref = {0: ["a", "b"], 1: ["c", "d", "e"]}
    hyp = {0: ["a", "b"]}
    out = evalkit.cpwer(ref, hyp)
    assert out["errors"] == 3
    assert out["deletions"] == 3
    assert out["insertions"] == 0
    assert out["ref_words"] == 5
    assert out["cpwer"] == pytest.approx(0.6)


# --------------------------------------------------------------------------- #
# score() convenience wrapper
# --------------------------------------------------------------------------- #


def test_score_pools_der_across_files(tmp_path):
    ref = tmp_path / "ref.rttm"
    hyp = tmp_path / "hyp.rttm"
    # file1: perfect. file2: hyp misses the whole 10 s (no hyp segment).
    ref.write_text(
        "SPEAKER file1 1 0.000 10.000 <NA> <NA> A <NA> <NA>\n"
        "SPEAKER file2 1 0.000 10.000 <NA> <NA> A <NA> <NA>\n"
    )
    hyp.write_text("SPEAKER file1 1 0.000 10.000 <NA> <NA> A <NA> <NA>\n")

    out = evalkit.score(ref, hyp)
    # file1 fully correct (0 missed), file2 fully missed (10 s). Pooled over
    # 20 s ref speaker time -> 10/20 = 0.5 missed.
    assert out["missed"] == pytest.approx(0.5)
    assert out["der"] == pytest.approx(0.5)
    assert out["per_file"]["file1"]["der"] == pytest.approx(0.0)
    assert out["per_file"]["file2"]["der"] == pytest.approx(1.0)
