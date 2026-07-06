"""Diarization/transcription evaluation: RTTM/UEM IO, DER, WER, cpWER.

This module is the scoring harness for diarize-ng. It depends only on the
base deps (numpy + scipy); pyannote.metrics is imported lazily in
``crosscheck_der`` purely for optional cross-validation of our own DER.

Metric semantics implemented here:

* **DER** follows NIST ``md-eval`` (Rich Transcription): the timeline is cut
  into atomic intervals at every reference/hypothesis boundary, a single
  *global* optimal speaker mapping is found (maximizing overlapped time), a
  no-score collar is removed around every reference boundary, and the error
  decomposes into missed speech, false alarm and speaker confusion, all as
  rates over the scored reference speaker time (overlap-aware: a region with
  two simultaneous reference speakers contributes twice to the denominator).
* **WER** is a plain Levenshtein alignment on token lists with S/I/D counts.
* **cpWER** follows meeteval's concatenated minimum-permutation WER: each
  speaker's words are concatenated, a cost matrix of pairwise word-error
  *counts* is built, and a Hungarian assignment (padding the smaller speaker
  set with empty streams, so unmatched speakers become full insertions or
  deletions) minimizes total errors over total reference words.

Simplifications stated honestly:

* DER uses the interval-midpoint to decide speaker activity inside an atomic
  interval. Because atomic intervals contain no interior boundary by
  construction, the midpoint membership equals the whole-interval membership;
  this is exact, not an approximation.
* The optimal mapping maximizes overlapped time over the *scored* region
  (collar/UEM already applied), matching md-eval. Ties in the assignment are
  broken by scipy and do not change the DER decomposition (both tied
  permutations yield the same total confusion).
* ``score`` aggregates DER across files by summing raw seconds then dividing
  once, which is the correct pooled DER (not a mean of per-file rates).
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment

from diarng.types import Segment, Span

# --------------------------------------------------------------------------- #
# RTTM / UEM IO
# --------------------------------------------------------------------------- #


@dataclass(frozen=True)
class RttmDoc:
    """Parsed RTTM content.

    ``segments`` maps ``file_id -> list[Segment]`` with speaker *names*
    replaced by zero-based ints assigned in first-appearance order per file.
    ``names`` maps ``file_id -> {int_speaker: original_name}`` so the mapping
    is reversible for reporting/writing.
    """

    segments: dict[str, list[Segment]]
    names: dict[str, dict[int, str]]


def load_rttm(path: str | Path) -> RttmDoc:
    """Parse an RTTM file into per-file :class:`Segment` lists.

    RTTM ``SPEAKER`` lines are whitespace-delimited::

        SPEAKER <file> <chan> <start> <dur> <NA> <NA> <name> <NA> <NA>

    Only ``SPEAKER`` records are read; other record types and comment lines
    (``;``/``#``) are ignored. Speaker names are interned to ints per file in
    the order they first appear (the same convention clustering uses for
    cluster indices), so a name map is returned alongside the segments.
    """
    segments: dict[str, list[Segment]] = {}
    name_to_id: dict[str, dict[str, int]] = {}
    names: dict[str, dict[int, str]] = {}

    for raw in Path(path).read_text().splitlines():
        line = raw.strip()
        if not line or line[0] in ";#":
            continue
        fields = line.split()
        if fields[0] != "SPEAKER" or len(fields) < 8:
            continue
        file_id = fields[1]
        start = float(fields[3])
        dur = float(fields[4])
        name = fields[7]

        per_file = name_to_id.setdefault(file_id, {})
        if name not in per_file:
            spk = len(per_file)
            per_file[name] = spk
            names.setdefault(file_id, {})[spk] = name
        spk = per_file[name]
        segments.setdefault(file_id, []).append(Segment(start, start + dur, spk))

    return RttmDoc(segments=segments, names=names)


def save_rttm(
    segments: Sequence[Segment],
    file_id: str,
    path: str | Path,
    names: Mapping[int, str] | None = None,
) -> None:
    """Write ``segments`` for a single ``file_id`` as RTTM ``SPEAKER`` lines.

    ``names`` optionally maps the int speaker back to a display name; without
    it, speakers are written as ``spk<n>``. Times/durations use 3 decimals.
    """
    lines = []
    for seg in sorted(segments, key=lambda s: (s.start, s.end)):
        name = (names or {}).get(seg.speaker, f"spk{seg.speaker}")
        lines.append(
            f"SPEAKER {file_id} 1 {seg.start:.3f} {seg.duration:.3f} "
            f"<NA> <NA> {name} <NA> <NA>"
        )
    Path(path).write_text("\n".join(lines) + ("\n" if lines else ""))


def load_uem(path: str | Path) -> dict[str, list[Span]]:
    """Parse a UEM (un-partitioned evaluation map) into per-file scored spans.

    UEM lines are ``<file> <chan> <start> <end>`` — note UEM uses an *end*
    time, unlike RTTM's duration. The returned spans restrict DER scoring to
    those regions.
    """
    out: dict[str, list[Span]] = {}
    for raw in Path(path).read_text().splitlines():
        line = raw.strip()
        if not line or line[0] in ";#":
            continue
        fields = line.split()
        if len(fields) < 4:
            continue
        file_id = fields[0]
        start = float(fields[2])
        end = float(fields[3])
        out.setdefault(file_id, []).append(Span(start, end))
    return out


# --------------------------------------------------------------------------- #
# Diarization Error Rate (NIST md-eval semantics)
# --------------------------------------------------------------------------- #


def _der_raw(
    ref: Sequence[Segment],
    hyp: Sequence[Segment],
    collar: float,
    uem: Sequence[Span] | None,
) -> tuple[float, float, float, float]:
    """Raw DER components in seconds: (missed, false_alarm, confusion, total).

    ``total`` is the scored reference speaker time (the DER denominator). Kept
    separate from :func:`der` so :func:`score` can pool seconds across files
    before dividing.
    """
    ref_labels = sorted({s.speaker for s in ref})
    hyp_labels = sorted({s.speaker for s in hyp})

    # Scoring extent: the UEM if given, else the union span of ref+hyp so that
    # hypothesis speech beyond the reference still counts as false alarm.
    if uem:
        extent = [(sp.start, sp.end) for sp in uem]
    else:
        starts = [s.start for s in ref] + [s.start for s in hyp]
        ends = [s.end for s in ref] + [s.end for s in hyp]
        if not starts:
            return (0.0, 0.0, 0.0, 0.0)
        extent = [(min(starts), max(ends))]

    # No-score collar: half-width `collar` removed on EACH side of every
    # reference boundary (NIST `-c` convention). collar=0.25 => 0.25 s per side.
    collar_zones: list[tuple[float, float]] = []
    if collar > 0:
        for s in ref:
            collar_zones.append((s.start - collar, s.start + collar))
            collar_zones.append((s.end - collar, s.end + collar))

    # Atomic-interval boundaries: every event time from ref, hyp, the extent
    # and the collar zones. Between two consecutive times nothing changes.
    times: set[float] = set()
    for a, b in extent:
        times.add(a)
        times.add(b)
    for s in ref:
        times.add(s.start)
        times.add(s.end)
    for s in hyp:
        times.add(s.start)
        times.add(s.end)
    for a, b in collar_zones:
        times.add(a)
        times.add(b)
    ordered = sorted(times)

    def scored_point(m: float) -> bool:
        inside = any(a <= m < b for a, b in extent)
        collared = any(a <= m < b for a, b in collar_zones)
        return inside and not collared

    # Collect scored atomic intervals as (duration, ref speakers, hyp speakers).
    scored: list[tuple[float, frozenset[int], frozenset[int]]] = []
    for a, b in zip(ordered, ordered[1:]):
        if b <= a:
            continue
        mid = (a + b) / 2.0
        if not scored_point(mid):
            continue
        rset = frozenset(s.speaker for s in ref if s.start <= mid < s.end)
        hset = frozenset(s.speaker for s in hyp if s.start <= mid < s.end)
        scored.append((b - a, rset, hset))

    # Global optimal ref->hyp mapping maximizing overlapped scored time.
    mapping: dict[int, int] = {}
    if ref_labels and hyp_labels:
        ri = {lab: i for i, lab in enumerate(ref_labels)}
        hi = {lab: j for j, lab in enumerate(hyp_labels)}
        overlap = np.zeros((len(ref_labels), len(hyp_labels)))
        for dur, rset, hset in scored:
            for r in rset:
                for h in hset:
                    overlap[ri[r], hi[h]] += dur
        rows, cols = linear_sum_assignment(overlap, maximize=True)
        for row, col in zip(rows, cols):
            mapping[ref_labels[row]] = hyp_labels[col]

    # md-eval decomposition per interval.
    missed = false_alarm = confusion = total = 0.0
    for dur, rset, hset in scored:
        nref = len(rset)
        nsys = len(hset)
        ncorrect = sum(1 for r in rset if mapping.get(r) in hset)
        missed += dur * max(0, nref - nsys)
        false_alarm += dur * max(0, nsys - nref)
        confusion += dur * (min(nref, nsys) - ncorrect)
        total += dur * nref

    return (missed, false_alarm, confusion, total)


def _rates(missed: float, fa: float, conf: float, total: float) -> dict:
    """Convert raw DER seconds to the rate dict, guarding the empty-ref case."""
    if total <= 0:
        return {"missed": 0.0, "false_alarm": 0.0, "confusion": 0.0, "der": 0.0}
    return {
        "missed": missed / total,
        "false_alarm": fa / total,
        "confusion": conf / total,
        "der": (missed + fa + conf) / total,
    }


def der(
    ref: Sequence[Segment],
    hyp: Sequence[Segment],
    collar: float = 0.25,
    uem: Sequence[Span] | None = None,
) -> dict:
    """Diarization Error Rate with NIST md-eval semantics.

    Returns ``{missed, false_alarm, confusion, der}`` as rates over the scored
    reference speaker time. ``collar`` is the half-width removed on each side
    of every reference boundary (0.25 s default). ``uem`` optionally restricts
    scoring to the given spans. Overlapping reference speech is supported: an
    interval with N simultaneous reference speakers needs N hypothesis
    speakers to avoid missed speech.
    """
    return _rates(*_der_raw(ref, hyp, collar, uem))


# --------------------------------------------------------------------------- #
# Word Error Rate + concatenated-permutation WER
# --------------------------------------------------------------------------- #


def wer(ref_words: Sequence[str], hyp_words: Sequence[str]) -> dict:
    """Word Error Rate via Levenshtein alignment on token lists.

    Returns S/I/D counts (relative to the reference), hits, total errors,
    reference length and the WER rate. Deletions are reference words missing
    from the hypothesis; insertions are extra hypothesis words. The backtrace
    prefers substitutions/matches (diagonal) over indels on ties, giving the
    conventional minimal-edit alignment.
    """
    r = list(ref_words)
    h = list(hyp_words)
    n, m = len(r), len(h)

    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        dp[i][0] = i
    for j in range(1, m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            cost = 0 if r[i - 1] == h[j - 1] else 1
            dp[i][j] = min(
                dp[i - 1][j - 1] + cost,  # substitution / match
                dp[i - 1][j] + 1,  # deletion
                dp[i][j - 1] + 1,  # insertion
            )

    subs = ins = dels = hits = 0
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0:
            cost = 0 if r[i - 1] == h[j - 1] else 1
            if dp[i][j] == dp[i - 1][j - 1] + cost:
                if cost == 0:
                    hits += 1
                else:
                    subs += 1
                i -= 1
                j -= 1
                continue
        if i > 0 and dp[i][j] == dp[i - 1][j] + 1:
            dels += 1
            i -= 1
        else:
            ins += 1
            j -= 1

    errors = subs + ins + dels
    if n:
        rate = errors / n
    else:
        rate = 0.0 if errors == 0 else math.inf
    return {
        "substitutions": subs,
        "insertions": ins,
        "deletions": dels,
        "hits": hits,
        "errors": errors,
        "ref_len": n,
        "wer": rate,
    }


def _as_token_lists(x: Mapping | Sequence) -> list[list[str]]:
    """Normalize a speaker->tokens map OR a sequence of token lists to a list."""
    if isinstance(x, Mapping):
        return [list(v) for v in x.values()]
    return [list(v) for v in x]


def cpwer(
    ref_speaker_texts: Mapping | Sequence,
    hyp_speaker_texts: Mapping | Sequence,
) -> dict:
    """Concatenated minimum-permutation WER (meeteval semantics).

    Each speaker's tokens are treated as one concatenated stream. A cost
    matrix of pairwise WER error *counts* is built and the reference/hypothesis
    speaker sets are matched by Hungarian assignment to minimize total errors.
    The smaller set is padded with empty streams, so a speaker with no
    counterpart contributes all of its words as insertions (extra hyp speaker)
    or deletions (unmatched ref speaker). cpWER = total errors / total
    reference words.
    """
    ref_lists = _as_token_lists(ref_speaker_texts)
    hyp_lists = _as_token_lists(hyp_speaker_texts)
    ref_words = sum(len(t) for t in ref_lists)

    # Pad both sides to a common size with empty streams. wer(x, []) yields
    # len(x) deletions and wer([], y) yields len(y) insertions, which is
    # exactly "unmatched speaker = full indels".
    size = max(len(ref_lists), len(hyp_lists))
    ref_pad = ref_lists + [[] for _ in range(size - len(ref_lists))]
    hyp_pad = hyp_lists + [[] for _ in range(size - len(hyp_lists))]

    if size == 0:
        return {
            "cpwer": 0.0,
            "errors": 0,
            "ref_words": 0,
            "substitutions": 0,
            "insertions": 0,
            "deletions": 0,
        }

    cells = [[wer(ref_pad[i], hyp_pad[j]) for j in range(size)] for i in range(size)]
    cost = np.array([[cells[i][j]["errors"] for j in range(size)] for i in range(size)])
    rows, cols = linear_sum_assignment(cost)

    errors = subs = ins = dels = 0
    for i, j in zip(rows, cols):
        cell = cells[i][j]
        errors += cell["errors"]
        subs += cell["substitutions"]
        ins += cell["insertions"]
        dels += cell["deletions"]

    if ref_words:
        rate = errors / ref_words
    else:
        rate = 0.0 if errors == 0 else math.inf
    return {
        "cpwer": rate,
        "errors": errors,
        "ref_words": ref_words,
        "substitutions": subs,
        "insertions": ins,
        "deletions": dels,
    }


# --------------------------------------------------------------------------- #
# CLI convenience + optional cross-check
# --------------------------------------------------------------------------- #


def score(
    ref_rttm: str | Path,
    hyp_rttm: str | Path,
    uem: str | Path | None = None,
) -> dict:
    """Pooled DER over all files in a reference RTTM vs a hypothesis RTTM.

    Convenience wrapper for the eval CLI. Reference files with no hypothesis
    counterpart are scored against an empty hypothesis (all missed). The
    overall DER pools raw seconds across files, and per-file rates are returned
    under ``per_file``.
    """
    ref_doc = load_rttm(ref_rttm)
    hyp_doc = load_rttm(hyp_rttm)
    uem_map = load_uem(uem) if uem else {}

    tot = [0.0, 0.0, 0.0, 0.0]
    per_file: dict[str, dict] = {}
    for file_id, ref_segs in ref_doc.segments.items():
        hyp_segs = hyp_doc.segments.get(file_id, [])
        raw = _der_raw(ref_segs, hyp_segs, 0.25, uem_map.get(file_id))
        for k in range(4):
            tot[k] += raw[k]
        per_file[file_id] = _rates(*raw)

    result = _rates(*tot)
    result["per_file"] = per_file
    return result


def crosscheck_der(
    ref: Sequence[Segment],
    hyp: Sequence[Segment],
    collar: float = 0.25,
) -> dict:
    """Cross-validate our DER against ``pyannote.metrics`` (lazy, optional).

    Harness-only; not exercised by the hermetic tests. pyannote's ``collar`` is
    the *total* width split across both sides, so we pass ``2*collar`` to match
    our half-width convention. Raises ImportError if pyannote is absent.
    """
    from pyannote.core import Annotation  # lazy — optional extra
    from pyannote.core import Segment as PSegment
    from pyannote.metrics.diarization import DiarizationErrorRate

    def to_annotation(segs: Sequence[Segment]) -> Annotation:
        ann = Annotation()
        for track, s in enumerate(segs):
            ann[PSegment(s.start, s.end), track] = s.speaker
        return ann

    metric = DiarizationErrorRate(collar=collar * 2, skip_overlap=False)
    detail = metric(to_annotation(ref), to_annotation(hyp), detailed=True)
    total = detail.get("total", 0.0) or 0.0
    if total <= 0:
        return {"missed": 0.0, "false_alarm": 0.0, "confusion": 0.0, "der": 0.0}
    return {
        "missed": detail["missed detection"] / total,
        "false_alarm": detail["false alarm"] / total,
        "confusion": detail["confusion"] / total,
        "der": detail["diarization error rate"],
    }
