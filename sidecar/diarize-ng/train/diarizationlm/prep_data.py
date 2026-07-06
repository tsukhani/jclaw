#!/usr/bin/env python3
# ===========================================================================
# RECIPE -- NOT EXECUTED IN CI / NOT PART OF THE HERMETIC TEST SUITE.
# This is a runnable-shaped data-prep script for the DiarizationLM fine-tune.
# It reads real evaluation files (RTTM + STM) and writes real training data,
# but nothing in diarize-ng's tests runs it: it is documentation you can run.
# ===========================================================================
"""Build DiarizationLM fine-tuning data (the "flipped" word/speaker format).

DiarizationLM (Wang et al., Google, 2024, arXiv:2401.03506) reframes speaker
diarization *correction* as a seq2seq problem. A base cascade produces a
hypothesis (words + a speaker per word); an LLM is fine-tuned to rewrite the
speaker labels -- and only the speaker labels -- to match a reference. This
script produces that (prompt, completion) supervision from the two artefacts a
diarization benchmark already ships:

    * an STM file -- the *reference* transcript. Each line is a scored segment
      carrying a reference speaker id and the words spoken (NIST "segment time
      mark" format). This gives us the word sequence and the per-word
      *reference* speaker ``ref_spk``.
    * an RTTM file -- a *hypothesis* speaker timeline from the base diarizer we
      want DiarizationLM to correct (the same RTTM your eval harness scores
      with ``diarng.evalkit``). Overlaying it on the STM words gives the
      per-word *hypothesis* speaker ``hyp_spk``.

Because both sides share the STM's word sequence, this is the paper's
"oracle text / hypothesis speaker" setting: the model only ever learns to move
labels, never to edit words. That matches the *constrained transfer* guarantee
already enforced at inference time by ``diarng.refine.LlmRefiner``.

Output format (canonical DiarizationLM -- verified against the public
``diarizationlm`` package, ``diarizationlm.utils``):

    * ``--out-utterances`` : one JSON object with an ``"utterances"`` list, each
      entry ``{utterance_id, hyp_text, hyp_spk, ref_text, ref_spk}`` -- the same
      dict the ``diarizationlm`` package consumes for scoring (cpWER / WDER).
    * ``--out-pairs`` : JSONL of ``{"prompt", "completion"}`` ready for an SFT
      trainer (see ``peft_train.py``). Each is a *diarized text* string --
      ``<speaker:1> word word <speaker:2> word ...`` -- with the prompt built
      from ``hyp_spk`` and the completion from ``ref_spk`` over identical words.

Serialization constants below mirror ``diarizationlm.utils.PromptOptions``
defaults (``speaker_prefix="<speaker:"``, ``speaker_suffix=">"``,
``prompt_suffix=" --> "``, ``completion_suffix=""``). Pass
``--use-diarizationlm-package`` to serialize via that package instead, for
byte-exact parity with published checkpoints.

NOTE on the format gap with ``diarng.refine``: the runtime refiner prompts a
general chat model with one *turn* per line, ``<spk:K>: text``. That is the
training-free variant. This recipe targets the paper's *word-level* flip
(``<speaker:K> words`` inline, no per-line split, a ``-->`` prompt suffix),
which is what a *fine-tuned* DiarizationLM model expects. A model trained here
must therefore be prompted in this word-level format, not refine.py's
turn-line format -- see ``peft_train.py`` and ``../README.md``.

Only the standard library is imported at module load; ``diarng.evalkit`` (for
the RTTM parser) and the optional ``diarizationlm`` package are imported lazily
inside ``main`` so this file stays importable with nothing installed.

Datasets this is meant to run on (reference RTTM+STM both available):
AMI (headset mix / SDM), DIHARD III, AliMeeting, MSDWILD, VoxConverse. Meeting
corpora with real overlap and many speakers are where DiarizationLM helps most.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

# --- serialization constants (mirror diarizationlm.utils.PromptOptions) ----- #
SPEAKER_PREFIX = "<speaker:"
SPEAKER_SUFFIX = ">"
PROMPT_SUFFIX = " --> "
COMPLETION_SUFFIX = ""  # the tokenizer EOS terminates the completion


@dataclass(frozen=True)
class StmSegment:
    """One scored STM segment: a reference speaker and the words they spoke."""

    file_id: str
    start: float
    end: float
    speaker: str
    words: tuple[str, ...]


# Non-lexical STM markers that must not become training words.
_STM_SKIP_TOKENS = frozenset(
    {"ignore_time_segment_in_scoring", "inter_segment_gap", "<>"}
)


def parse_stm(path: str | Path) -> dict[str, list[StmSegment]]:
    """Parse an STM reference transcript into per-file segment lists.

    STM lines are whitespace-delimited::

        <file> <channel> <speaker> <start> <end> [<attributes>] words...

    The optional ``<attributes>`` token (e.g. ``<o,f0,female>``) sits between
    the end time and the transcript and is dropped. Comment lines (``;``) and
    empty transcripts are ignored, as are the non-lexical scoring markers NIST
    uses to punch un-scored holes in a channel.

    Returns ``{file_id: [StmSegment, ...]}`` in file order of appearance.
    """
    out: dict[str, list[StmSegment]] = {}
    for raw in Path(path).read_text().splitlines():
        line = raw.strip()
        if not line or line[0] in ";#":
            continue
        fields = line.split()
        if len(fields) < 6:
            continue
        file_id, _chan, speaker = fields[0], fields[1], fields[2]
        start, end = float(fields[3]), float(fields[4])
        rest = fields[5:]
        # Drop a leading <...> attributes token if present.
        if rest and rest[0].startswith("<") and rest[0].endswith(">"):
            rest = rest[1:]
        words = tuple(w for w in rest if w.lower() not in _STM_SKIP_TOKENS)
        if not words:
            continue
        out.setdefault(file_id, []).append(
            StmSegment(file_id, start, end, speaker, words)
        )
    return out


def _overlap(a_start: float, a_end: float, b_start: float, b_end: float) -> float:
    """Overlapped duration of two intervals, clamped at zero."""
    return max(0.0, min(a_end, b_end) - max(a_start, b_start))


def assign_hyp_speaker(seg: StmSegment, hyp_segments) -> int | None:
    """Pick the hypothesis speaker for a whole STM segment by max overlap.

    All words in a reference segment are spoken by one person, so they inherit
    one hypothesis label: the speaker whose RTTM regions overlap this segment
    the most. Segment-level (not word-level) assignment avoids inventing fake
    per-word timings from a segment-level STM. Returns ``None`` when no
    hypothesis speech overlaps the segment (a pure miss by the base system),
    which the caller renders as a carried-forward or default label.

    ``hyp_segments`` items only need ``.start`` / ``.end`` / ``.speaker`` (the
    ``diarng.types.Segment`` shape produced by ``evalkit.load_rttm``).
    """
    totals: dict[int, float] = {}
    for hs in hyp_segments:
        ov = _overlap(seg.start, seg.end, hs.start, hs.end)
        if ov > 0.0:
            totals[hs.speaker] = totals.get(hs.speaker, 0.0) + ov
    if not totals:
        return None
    # Max overlap; ties broken by the lower speaker index for determinism.
    return min(totals, key=lambda spk: (-totals[spk], spk))


def create_diarized_text(words, speakers) -> str:
    """Serialize aligned (word, speaker) pairs as DiarizationLM diarized text.

    A ``<speaker:K>`` tag is emitted only when the speaker changes (and at the
    start), then the word. Matches ``diarizationlm.utils.create_diarized_text``
    with default ``PromptOptions``::

        <speaker:1> hello how are you <speaker:2> i am fine
    """
    parts: list[str] = []
    prev: object = None
    for word, spk in zip(words, speakers):
        if spk != prev:
            parts.append(f"{SPEAKER_PREFIX}{spk}{SPEAKER_SUFFIX}")
            prev = spk
        parts.append(word)
    return " ".join(parts)


@dataclass
class Utterance:
    """A (chunk of a) conversation as DiarizationLM's parallel string format."""

    utterance_id: str
    words: list[str]
    hyp_spk: list[int]
    ref_spk: list[int]

    def to_utt_dict(self) -> dict:
        """Render the canonical ``diarizationlm`` utterance dict.

        ``hyp_text`` and ``ref_text`` are identical (oracle text); the two
        speaker strings carry the correction supervision.
        """
        text = " ".join(self.words)
        return {
            "utterance_id": self.utterance_id,
            "hyp_text": text,
            "hyp_spk": " ".join(str(s) for s in self.hyp_spk),
            "ref_text": text,
            "ref_spk": " ".join(str(s) for s in self.ref_spk),
        }


def build_utterances(
    stm: dict[str, list[StmSegment]],
    hyp: dict[str, list],
    max_words: int,
) -> list[Utterance]:
    """Turn per-file STM+RTTM into word-aligned, chunked utterances.

    For each file the segments are sorted by start time; every word gets its
    reference speaker (the STM segment's speaker, interned to a 1-based int per
    file) and its hypothesis speaker (max-overlap RTTM label, +1 so both sides
    use 1-based ids as the paper does). Long conversations are split into
    chunks of at most ``max_words`` words **at segment boundaries** so a turn
    is never cut in half; the same boundary applies to both speaker streams
    because they share the word sequence.
    """
    utterances: list[Utterance] = []
    for file_id in sorted(stm):
        segments = sorted(stm[file_id], key=lambda s: (s.start, s.end))
        hyp_segments = hyp.get(file_id, [])

        ref_ids: dict[str, int] = {}  # STM speaker name -> 1-based ref id
        chunk_words: list[str] = []
        chunk_hyp: list[int] = []
        chunk_ref: list[int] = []
        chunk_idx = 0
        last_hyp = 1  # carried forward when a segment has no hyp overlap

        def flush() -> None:
            nonlocal chunk_words, chunk_hyp, chunk_ref, chunk_idx
            if chunk_words:
                utterances.append(
                    Utterance(
                        utterance_id=f"{file_id}_{chunk_idx:04d}",
                        words=chunk_words,
                        hyp_spk=chunk_hyp,
                        ref_spk=chunk_ref,
                    )
                )
                chunk_idx += 1
            chunk_words, chunk_hyp, chunk_ref = [], [], []

        for seg in segments:
            ref_id = ref_ids.setdefault(seg.speaker, len(ref_ids) + 1)
            hyp_label = assign_hyp_speaker(seg, hyp_segments)
            hyp_id = last_hyp if hyp_label is None else hyp_label + 1
            last_hyp = hyp_id

            # Keep whole segments together; split before a segment that would
            # overflow the current chunk (unless the chunk is empty).
            if chunk_words and len(chunk_words) + len(seg.words) > max_words:
                flush()
            chunk_words.extend(seg.words)
            chunk_hyp.extend([hyp_id] * len(seg.words))
            chunk_ref.extend([ref_id] * len(seg.words))
        flush()
    return utterances


def _serialize_pair_builtin(utt: Utterance) -> dict:
    """Build the {prompt, completion} pair with the in-file serializer."""
    prompt = create_diarized_text(utt.words, utt.hyp_spk) + PROMPT_SUFFIX
    completion = create_diarized_text(utt.words, utt.ref_spk) + COMPLETION_SUFFIX
    return {"prompt": prompt, "completion": completion}


def _serialize_pair_package(utt: Utterance) -> dict:
    """Build the pair via the official ``diarizationlm`` package (lazy import).

    Guarantees byte-exact parity with published DiarizationLM checkpoints by
    reusing ``diarizationlm.utils`` for the diarized-text serialization.
    Requires ``pip install diarizationlm`` (reported in deps_needed, not an
    extra of this repo).
    """
    from diarizationlm import utils as dlm  # type: ignore

    po = dlm.PromptOptions()
    ud = utt.to_utt_dict()
    prompt = (
        po.prompt_prefix
        + dlm.create_diarized_text(
            ud["hyp_text"].split(), ud["hyp_spk"].split(), po=po
        )
        + po.prompt_suffix
    )
    completion = (
        dlm.create_diarized_text(ud["ref_text"].split(), ud["ref_spk"].split(), po=po)
        + po.completion_suffix
    )
    return {"prompt": prompt, "completion": completion}


def main() -> None:
    """Parse args, read RTTM+STM, write utterances JSON and SFT pairs JSONL."""
    parser = argparse.ArgumentParser(
        description="Prepare DiarizationLM fine-tuning data from RTTM + STM.",
    )
    parser.add_argument(
        "--stm", required=True, help="reference transcript (STM) with ref speakers"
    )
    parser.add_argument(
        "--rttm", required=True, help="hypothesis diarization (RTTM) to be corrected"
    )
    parser.add_argument(
        "--out-utterances",
        required=True,
        help="output path for the {'utterances': [...]} JSON",
    )
    parser.add_argument(
        "--out-pairs",
        required=True,
        help="output path for the {prompt, completion} JSONL",
    )
    parser.add_argument(
        "--max-words",
        type=int,
        default=896,
        help="max words per utterance chunk (DiarizationLM emit length default)",
    )
    parser.add_argument(
        "--use-diarizationlm-package",
        action="store_true",
        help="serialize via the official `diarizationlm` package for exact parity",
    )
    args = parser.parse_args()

    # Lazy: the RTTM parser lives in diarng.evalkit (numpy/scipy only). Imported
    # here so this recipe stays importable without diarng on the path.
    from diarng.evalkit import load_rttm

    stm = parse_stm(args.stm)
    hyp = load_rttm(args.rttm).segments
    utterances = build_utterances(stm, hyp, args.max_words)

    serialize = (
        _serialize_pair_package
        if args.use_diarizationlm_package
        else _serialize_pair_builtin
    )

    utt_doc = {"utterances": [u.to_utt_dict() for u in utterances]}
    Path(args.out_utterances).write_text(json.dumps(utt_doc, indent=2) + "\n")

    with Path(args.out_pairs).open("w") as fh:
        for utt in utterances:
            fh.write(json.dumps(serialize(utt)) + "\n")

    words = sum(len(u.words) for u in utterances)
    print(
        f"wrote {len(utterances)} utterances ({words} words) "
        f"from {len(stm)} files -> {args.out_utterances}, {args.out_pairs}"
    )


if __name__ == "__main__":
    main()
