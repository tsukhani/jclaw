"""Word -> speaker assignment and turn building.

After clustering/resegmentation we hold two independent timelines: the ASR
word timeline (`asr.Transcriber`) and the diarized speaker timeline
(`clustering`). This stage stitches them: every word inherits a speaker from
the segment it best lines up with, then contiguous same-speaker words are
folded into `Turn`s (the transcript unit consumed by refinement/identity).

Assignment policy (in priority order), the standard cascade-diarization
alignment used by pyannote/whisperX-style pipelines:

  1. Midpoint containment -- the segment whose half-open span [start, end)
     covers the word's midpoint. The midpoint is robust to the small timing
     disagreements between the ASR and diarization front-ends at word edges.
  2. Maximum overlap -- when the midpoint lands in a gap (silence between
     segments), the segment sharing the most time with the word's span.
  3. Temporal nearest -- when the word overlaps nothing at all, the segment
     with the smallest gap to the word. Guarantees every word gets a label
     as long as at least one segment exists.

With no segments at all, words keep `speaker=None`; `build_turns` then routes
them into honest `speaker=-1` turns rather than guessing.
"""

from __future__ import annotations

from dataclasses import replace

from diarng.types import Segment, Turn, Word


def assign_speakers(words: list[Word], segments: list[Segment]) -> list[Word]:
    """Attribute each word to a speaker from the diarized `segments`.

    Returns fresh `Word` objects (the inputs are never mutated). When
    `segments` is empty every returned word has `speaker=None`.
    """
    return [replace(word, speaker=_speaker_for(word, segments)) for word in words]


def _speaker_for(word: Word, segments: list[Segment]) -> int | None:
    """Resolve one word's speaker via containment -> overlap -> nearest."""
    if not segments:
        return None

    # 1. Midpoint containment (half-open [start, end) so adjacent segments
    #    that share a boundary attribute the boundary to the later segment).
    mid = word.mid
    for seg in segments:
        if seg.start <= mid < seg.end:
            return seg.speaker

    # 2. Maximum time overlap with the word's span. A zero overlap never wins
    #    (best_overlap starts at 0.0), so touching-but-disjoint segments fall
    #    through to the nearest rule instead of being treated as overlapping.
    best_overlap = 0.0
    best_seg: Segment | None = None
    for seg in segments:
        overlap = min(word.end, seg.end) - max(word.start, seg.start)
        if overlap > best_overlap:
            best_overlap = overlap
            best_seg = seg
    if best_seg is not None:
        return best_seg.speaker

    # 3. Temporally nearest segment (smallest edge-to-edge gap).
    best_dist = float("inf")
    for seg in segments:
        if word.end <= seg.start:
            dist = seg.start - word.end
        elif seg.end <= word.start:
            dist = word.start - seg.end
        else:  # unreachable: any real overlap was handled in step 2.
            dist = 0.0
        if dist < best_dist:
            best_dist = dist
            best_seg = seg
    return best_seg.speaker if best_seg is not None else None


def build_turns(words: list[Word], names: dict[int, str] | None = None) -> list[Turn]:
    """Fold consecutive same-speaker words into `Turn`s.

    A run of words sharing a speaker becomes one turn whose `text` is the
    space-joined, individually-stripped word texts. `names.get(speaker)` (if
    provided) is attached as the display name.

    Words whose speaker is `None` (unattributed by `assign_speakers`) open
    their own `speaker=-1` turn. They are grouped among themselves but never
    merged into a neighbouring real-speaker turn -- keeping the transcript
    honest about which words the pipeline could not attribute.
    """
    name_map = names or {}
    turns: list[Turn] = []
    group: list[Word] = []
    group_speaker: int | None = None

    def flush() -> None:
        if not group:
            return
        speaker = group_speaker if group_speaker is not None else -1
        text = " ".join(word.text.strip() for word in group)
        turns.append(
            Turn(
                start=group[0].start,
                end=group[-1].end,
                speaker=speaker,
                text=text,
                words=list(group),
                name=name_map.get(speaker),
            )
        )

    for word in words:
        # Normalise None -> -1 so consecutive unattributed words group together
        # while still breaking any surrounding real-speaker run.
        key = word.speaker if word.speaker is not None else -1
        prev_key = group_speaker if group_speaker is not None else -1
        if group and key != prev_key:
            flush()
            group = []
        group.append(word)
        group_speaker = word.speaker
    flush()
    return turns
