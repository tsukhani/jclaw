"""Orchestration contract for the Diarizer.

The production diarization pipeline shipped with *zero* tests over stage
wiring; the diarize-ng contract is that this file pins it. Every model-backed
seam (segmenter, embedder, transcriber, refiner, store) is faked; the pure
numpy stages (window planning, spectral clustering, VBx, assignment, centroid
matching, turn building) run for real, so the test exercises the actual
dataflow rather than a mock of it.

The scenario: three local segments A / B / A (speakers 0, 1, 0), embeddings in
two orthogonal directions, and one enrolled voiceprint for the A direction.
A correctly wired pipeline must (a) call the stages in order, (b) cluster into
two speakers, (c) identify the enrolled speaker on the A turns, and (d) land
the transcriber's words on the right speakers.
"""

from __future__ import annotations

import numpy as np
import pytest

from diarng import pipeline
from diarng.identity import VoiceprintStore
from diarng.pipeline import Diarizer, DiarizerConfig, result_to_dict
from diarng.types import Segment, SegmentationOutput, Span, Word

_D = 32  # embedding dimensionality for the fakes
# Window speaker order produced by window_spans over the fake segmentation
# (three 3s segments -> three 1.5s/0.75s-hop windows each): A A A B B B A A A.
_WINDOW_SPEAKERS = [0, 0, 0, 1, 1, 1, 0, 0, 0]


# --------------------------------------------------------------------------- #
# fakes
# --------------------------------------------------------------------------- #
class FakeSegmenter:
    def __init__(self, output: SegmentationOutput, calls: list[str]):
        self._output = output
        self._calls = calls
        self.device = "cpu"
        self.last_num_speakers = "unset"

    def segment(self, path, num_speakers=None) -> SegmentationOutput:
        self._calls.append("segment")
        self.last_num_speakers = num_speakers
        return self._output


class FakeEmbedder:
    """Returns a pre-seeded (N, D) matrix, asserting it got one chunk per row."""

    def __init__(self, matrix: np.ndarray, calls: list[str]):
        self.matrix = np.asarray(matrix, dtype=float)
        self._calls = calls
        self.batch_sizes: list[int] = []

    def embed_batch(self, chunks):
        self._calls.append("embed")
        self.batch_sizes.append(len(chunks))
        assert len(chunks) == self.matrix.shape[0], (
            len(chunks),
            self.matrix.shape,
        )
        return self.matrix


class FakeTranscriber:
    def __init__(self, words: list[Word], calls: list[str]):
        self._words = words
        self._calls = calls
        self.last_language = "unset"

    def transcribe(self, path, language=None) -> list[Word]:
        self._calls.append("transcribe")
        self.last_language = language
        # Fresh copies, as a real backend would hand back.
        return [
            Word(
                w.start,
                w.end,
                w.text,
                probability=w.probability,
                no_speech_prob=w.no_speech_prob,
            )
            for w in self._words
        ]


class FakeRefiner:
    def __init__(self, calls: list[str], out=None):
        self._calls = calls
        self._out = out
        self.received = None

    def refine(self, turns):
        self._calls.append("refine")
        self.received = turns
        return self._out if self._out is not None else turns


# --------------------------------------------------------------------------- #
# fixtures
# --------------------------------------------------------------------------- #
def _embeddings() -> np.ndarray:
    """Nine rows: A-windows on axis 0, B-windows on axis 1, tiny fixed noise."""
    rng = np.random.default_rng(0)
    a = np.zeros(_D)
    a[0] = 1.0
    b = np.zeros(_D)
    b[1] = 1.0
    rows = [
        (a if spk == 0 else b) + 0.01 * rng.standard_normal(_D)
        for spk in _WINDOW_SPEAKERS
    ]
    return np.array(rows)


def _segmentation() -> SegmentationOutput:
    return SegmentationOutput(
        segments=(
            Segment(0.0, 3.0, 0),
            Segment(3.0, 6.0, 1),
            Segment(6.0, 9.0, 0),
        ),
        overlaps=(Span(4.0, 4.5),),
    )


def _words() -> list[Word]:
    # Mids at 0.75, 1.5, 3.75, 4.5, 6.75 -> segments A, A, B, B, A.
    return [
        Word(0.5, 1.0, "hello", probability=0.9, no_speech_prob=0.1),
        Word(1.2, 1.8, "there", probability=0.9, no_speech_prob=0.1),
        Word(3.5, 4.0, "hi", probability=0.9, no_speech_prob=0.1),
        Word(4.2, 4.8, "friend", probability=0.9, no_speech_prob=0.1),
        Word(6.5, 7.0, "bye", probability=0.9, no_speech_prob=0.1),
    ]


# Stage functions spied (delegating to the real implementation) so the test
# can assert the *entire* stage order, not just the seam calls.
_SPY_FUNCS = [
    "window_spans",
    "nme_spectral_cluster",
    "labels_to_segments",
    "hallucination_gate",
    "assign_speakers",
    "centroids",
    "identity_match",
    "build_turns",
]


def _install_spies(monkeypatch, calls: list[str]) -> None:
    for name in _SPY_FUNCS:
        original = getattr(pipeline, name)

        def make(recorded_name, func):
            def wrapper(*args, **kwargs):
                calls.append(recorded_name)
                return func(*args, **kwargs)

            return wrapper

        monkeypatch.setattr(pipeline, name, make(name, original))


def _make_diarizer(monkeypatch, tmp_path, calls, *, refiner=None, enroll=True):
    # Audio decode is not a seam — monkeypatch the module function to hand back
    # a silent buffer long enough to slice every window from.
    monkeypatch.setattr(
        pipeline.audio,
        "load_pcm",
        lambda path, **_: np.zeros(16_000 * 12, dtype=np.float32),
    )
    _install_spies(monkeypatch, calls)

    store = VoiceprintStore(str(tmp_path / "voiceprints.json"))
    if enroll:
        alice = np.zeros(_D)
        alice[0] = 1.0  # the A direction
        store.enroll("Alice", alice)

    config = DiarizerConfig(
        embedding_model_path="unused-fake.onnx",
        language="en",
    )
    return Diarizer(
        config,
        segmenter=FakeSegmenter(_segmentation(), calls),
        embedder=FakeEmbedder(_embeddings(), calls),
        transcriber=FakeTranscriber(_words(), calls),
        refiner=refiner,
        store=store,
    )


# --------------------------------------------------------------------------- #
# tests
# --------------------------------------------------------------------------- #
def test_stage_order_without_refiner(monkeypatch, tmp_path):
    calls: list[str] = []
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls)

    diarizer.diarize("audio.wav", num_speakers=2)

    assert calls == [
        "segment",
        "window_spans",
        "embed",
        "nme_spectral_cluster",
        "labels_to_segments",
        "transcribe",
        "hallucination_gate",
        "assign_speakers",
        "centroids",
        "identity_match",
        "build_turns",
    ]


def test_stage_order_with_refiner_appends_refine_last(monkeypatch, tmp_path):
    calls: list[str] = []
    refiner = FakeRefiner(calls)
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls, refiner=refiner)

    diarizer.diarize("audio.wav", num_speakers=2)

    assert calls[-1] == "refine"
    assert calls.count("refine") == 1


def test_num_speakers_hint_is_forwarded(monkeypatch, tmp_path):
    calls: list[str] = []
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls)

    diarizer.diarize("audio.wav", num_speakers=2)

    assert diarizer.segmenter.last_num_speakers == 2


def test_dataflow_shapes(monkeypatch, tmp_path):
    calls: list[str] = []
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls)

    result = diarizer.diarize("audio.wav", num_speakers=2)

    # One embed_batch of nine windows (three per 3s segment).
    assert diarizer.embedder.batch_sizes == [9]
    # Two clusters found; overlaps passed straight through from segmentation.
    assert result.num_speakers == 2
    assert result.overlaps == [Span(4.0, 4.5)]
    # meta records the per-stage timings and the model ids.
    assert set(result.meta["timings"]) >= {"segment", "embed", "cluster"}
    assert result.meta["num_windows"] == 9
    assert result.meta["refined"] is False


def test_language_forwarded_to_transcriber(monkeypatch, tmp_path):
    calls: list[str] = []
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls)

    diarizer.diarize("audio.wav", num_speakers=2)

    assert diarizer.transcriber.last_language == "en"


def test_enrolled_speaker_is_identified_and_words_land_correctly(
    monkeypatch, tmp_path
):
    calls: list[str] = []
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls)

    result = diarizer.diarize("audio.wav", num_speakers=2)

    # The enrolled A-direction speaker is named on at least one turn.
    named = [t for t in result.turns if t.name == "Alice"]
    assert named, "enrolled speaker was not identified"
    alice_speaker = named[0].speaker

    # Every "Alice" turn shares one cluster id, and its words come only from
    # the A-region utterances.
    alice_words = {w.text for t in named for w in t.words}
    assert alice_words <= {"hello", "there", "bye"}
    assert all(t.speaker == alice_speaker for t in named)

    # The B-region words form a distinct, unnamed speaker turn.
    other = [t for t in result.turns if t.speaker != alice_speaker]
    other_words = {w.text for t in other for w in t.words}
    assert {"hi", "friend"} <= other_words
    assert alice_speaker not in {t.speaker for t in other}


def test_transcriber_words_survive_into_turns(monkeypatch, tmp_path):
    calls: list[str] = []
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls)

    result = diarizer.diarize("audio.wav", num_speakers=2)

    all_words = [w.text for t in result.turns for w in t.words]
    assert all_words == ["hello", "there", "hi", "friend", "bye"]
    # Every word carries a concrete speaker (none left as None).
    assert all(
        w.speaker is not None for t in result.turns for w in t.words
    )


def test_refiner_output_is_returned(monkeypatch, tmp_path):
    calls: list[str] = []
    sentinel = [
        __import__("diarng.types", fromlist=["Turn"]).Turn(
            0.0, 1.0, 0, "sentinel", words=[]
        )
    ]
    refiner = FakeRefiner(calls, out=sentinel)
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls, refiner=refiner)

    result = diarizer.diarize("audio.wav", num_speakers=2)

    assert result.turns is sentinel
    assert result.meta["refined"] is True
    # The refiner received the pre-refinement turns (word-preserving contract).
    assert refiner.received is not None


def test_result_serializes_to_json_safe_dict(monkeypatch, tmp_path):
    import json

    calls: list[str] = []
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls)

    result = diarizer.diarize("audio.wav", num_speakers=2)
    payload = result_to_dict(result)

    # Round-trips through json without a custom encoder.
    text = json.dumps(payload)
    back = json.loads(text)
    assert back["num_speakers"] == 2
    assert back["overlaps"] == [{"start": 4.0, "end": 4.5}]
    assert {w["text"] for turn in back["turns"] for w in turn["words"]} == {
        "hello",
        "there",
        "hi",
        "friend",
        "bye",
    }


def test_empty_transcript_yields_no_turns(monkeypatch, tmp_path):
    calls: list[str] = []
    diarizer = _make_diarizer(monkeypatch, tmp_path, calls)
    diarizer.transcriber = FakeTranscriber([], calls)

    result = diarizer.diarize("audio.wav", num_speakers=2)

    assert result.turns == []
    # Clustering still ran over the nine windows.
    assert result.num_speakers == 2
