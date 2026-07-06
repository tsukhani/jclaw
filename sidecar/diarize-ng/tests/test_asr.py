"""Hermetic tests for the ASR stage.

No model downloads, no network, no GPU: the two model-backed transcribers are
exercised through injected fake backend modules, and the pure
``hallucination_gate`` logic is tested directly. Neither ``faster_whisper`` nor
``nemo`` is an installed extra, so the lazy-import seams are deterministic.
"""

from __future__ import annotations

import sys
import types as pytypes

import pytest

from diarng.asr import (
    FasterWhisperTranscriber,
    ParakeetTranscriber,
    Transcriber,
    hallucination_gate,
)
from diarng.types import Word


def w(nsp, prob, text="x", start=0.0, end=0.1):
    """Build a Word carrying the two confidence signals the gate reads."""
    return Word(start=start, end=end, text=text, probability=prob, no_speech_prob=nsp)


# --------------------------------------------------------------------------- #
# hallucination_gate matrix
# --------------------------------------------------------------------------- #


def test_confident_speech_is_kept():
    # Low no_speech_prob, high probability -> real speech.
    words = [w(0.02, 0.9), w(0.02, 0.85)]
    assert hallucination_gate(words) == words


def test_silence_hallucination_run_is_dropped():
    # High no_speech_prob + low mean probability -> the whole run goes.
    words = [w(0.95, 0.05), w(0.95, 0.04), w(0.95, 0.03)]
    assert hallucination_gate(words) == []


def test_only_the_hallucinated_run_is_dropped_order_preserved():
    good_a = w(0.02, 0.9, text="hello")
    good_b = w(0.02, 0.85, text="there")
    bad_a = w(0.95, 0.03, text="thanks")
    bad_b = w(0.95, 0.04, text="watching")
    good_c = w(0.01, 0.7, text="ok")
    kept = hallucination_gate([good_a, good_b, bad_a, bad_b, good_c])
    assert kept == [good_a, good_b, good_c]


def test_missing_no_speech_prob_is_kept():
    # No no_speech_prob at all -> run is never judged, even with low probability.
    words = [w(None, 0.01), w(None, 0.02)]
    assert hallucination_gate(words) == words


def test_run_with_a_missing_probability_is_kept_whole():
    # High no_speech_prob run that would otherwise drop, but one word lacks a
    # probability -> the whole run is kept (never drop a confidence-less word).
    words = [w(0.95, 0.03), w(0.95, None), w(0.95, 0.02)]
    assert hallucination_gate(words) == words


def test_high_no_speech_but_confident_is_kept():
    # AND semantics: silent-looking segment but a confident decode stays.
    words = [w(0.95, 0.9), w(0.95, 0.88)]
    assert hallucination_gate(words) == words


def test_no_speech_prob_on_boundary_is_kept():
    # Exactly at max_no_speech: "exceeds" is strict, so this is not silence.
    words = [w(0.6, 0.01)]
    assert hallucination_gate(words, max_no_speech=0.6, min_avg_prob=0.2) == words


def test_mean_probability_on_boundary_is_kept():
    # Mean probability exactly at min_avg_prob: "below" is strict -> kept.
    words = [w(0.9, 0.1), w(0.9, 0.3)]  # mean 0.2
    assert hallucination_gate(words, max_no_speech=0.6, min_avg_prob=0.2) == words


def test_empty_input_returns_empty():
    assert hallucination_gate([]) == []


def test_gate_does_not_mutate_or_copy_words():
    # Pure function: survivors are returned by reference, unchanged.
    words = [w(0.02, 0.9)]
    out = hallucination_gate(words)
    assert out[0] is words[0]


# --------------------------------------------------------------------------- #
# FasterWhisperTranscriber -- fake backend module
# --------------------------------------------------------------------------- #


class _FakeWWord:
    def __init__(self, start, end, word, probability):
        self.start = start
        self.end = end
        self.word = word
        self.probability = probability


class _FakeSegment:
    def __init__(self, no_speech_prob, words):
        self.no_speech_prob = no_speech_prob
        self.words = words


class _FakeWhisperModel:
    """Records construction args and transcribe kwargs; yields fixed segments."""

    instances: list["_FakeWhisperModel"] = []

    def __init__(self, model, device, compute_type):
        self.model = model
        self.device = device
        self.compute_type = compute_type
        self.transcribe_calls: list[tuple] = []
        _FakeWhisperModel.instances.append(self)

    def transcribe(self, audio, **kwargs):
        self.transcribe_calls.append((audio, kwargs))
        segments = [
            _FakeSegment(
                0.02,
                [
                    _FakeWWord(0.0, 0.5, " Hello", 0.9),
                    _FakeWWord(0.5, 0.9, " world", 0.8),
                ],
            ),
            _FakeSegment(0.91, [_FakeWWord(5.0, 5.4, " you", 0.05)]),
        ]
        return iter(segments), object()  # (segments, info)


@pytest.fixture
def fake_faster_whisper(monkeypatch):
    _FakeWhisperModel.instances = []
    module = pytypes.ModuleType("faster_whisper")
    module.WhisperModel = _FakeWhisperModel
    monkeypatch.setitem(sys.modules, "faster_whisper", module)
    return module


def test_faster_whisper_passes_construction_and_flags(fake_faster_whisper):
    t = FasterWhisperTranscriber(model="small", device="cpu", compute_type="int8")
    t.transcribe("/audio.wav", language="en")

    assert len(_FakeWhisperModel.instances) == 1
    inst = _FakeWhisperModel.instances[0]
    assert (inst.model, inst.device, inst.compute_type) == ("small", "cpu", "int8")

    audio, kwargs = inst.transcribe_calls[0]
    assert audio == "/audio.wav"
    assert kwargs["language"] == "en"
    assert kwargs["word_timestamps"] is True
    assert kwargs["vad_filter"] is True
    assert kwargs["condition_on_previous_text"] is False


def test_faster_whisper_maps_words_and_confidence(fake_faster_whisper):
    words = FasterWhisperTranscriber().transcribe("/audio.wav")

    assert [x.text for x in words] == [" Hello", " world", " you"]
    assert (words[0].start, words[0].end) == (0.0, 0.5)
    assert words[0].probability == 0.9
    # no_speech_prob is inherited from the parent segment.
    assert words[0].no_speech_prob == 0.02
    assert words[1].no_speech_prob == 0.02
    assert words[2].no_speech_prob == 0.91
    assert words[2].probability == 0.05


def test_faster_whisper_model_is_cached(fake_faster_whisper):
    t = FasterWhisperTranscriber()
    t.transcribe("/a.wav")
    t.transcribe("/b.wav")
    # One model built, reused across calls; both calls recorded on it.
    assert len(_FakeWhisperModel.instances) == 1
    assert len(_FakeWhisperModel.instances[0].transcribe_calls) == 2


def test_faster_whisper_construction_is_lazy(monkeypatch):
    # Forcing the import to fail proves the constructor never imports it.
    monkeypatch.setitem(sys.modules, "faster_whisper", None)
    t = FasterWhisperTranscriber()
    assert t.model_name == "large-v3"


# --------------------------------------------------------------------------- #
# ParakeetTranscriber -- fake NeMo hierarchy + guarded ImportError
# --------------------------------------------------------------------------- #


class _FakeHyp:
    """A NeMo hypothesis shape: `.timestamp['word']` of {word,start,end} dicts."""

    def __init__(self):
        self.timestamp = {
            "word": [
                {"word": "hello", "start": 0.0, "end": 0.5},
                {"word": "world", "start": 0.5, "end": 1.0},
            ]
        }


def _install_fake_nemo(monkeypatch, hyp):
    """Inject nemo.collections.asr with a from_pretrained returning a fake model."""

    class _Model:
        def __init__(self):
            self.calls: list[tuple] = []
            self.name = None

        def transcribe(self, paths, **kwargs):
            self.calls.append((paths, kwargs))
            return [hyp]

    model = _Model()

    class _ASRModel:
        @staticmethod
        def from_pretrained(name):
            model.name = name
            return model

    nemo = pytypes.ModuleType("nemo")
    collections = pytypes.ModuleType("nemo.collections")
    asr = pytypes.ModuleType("nemo.collections.asr")
    asr.models = pytypes.SimpleNamespace(ASRModel=_ASRModel)
    nemo.collections = collections
    collections.asr = asr
    monkeypatch.setitem(sys.modules, "nemo", nemo)
    monkeypatch.setitem(sys.modules, "nemo.collections", collections)
    monkeypatch.setitem(sys.modules, "nemo.collections.asr", asr)
    return model


def test_parakeet_missing_nemo_raises_clear_error(monkeypatch):
    monkeypatch.setitem(sys.modules, "nemo", None)  # forces ImportError on import
    t = ParakeetTranscriber()
    with pytest.raises(ImportError, match="NeMo"):
        t.transcribe("/a.wav")


def test_parakeet_maps_native_word_timestamps(monkeypatch):
    model = _install_fake_nemo(monkeypatch, _FakeHyp())
    words = ParakeetTranscriber("nvidia/parakeet-tdt-1.1b").transcribe(
        "/a.wav", language="en"
    )

    assert model.name == "nvidia/parakeet-tdt-1.1b"
    paths, kwargs = model.calls[0]
    assert paths == ["/a.wav"]
    assert kwargs["timestamps"] is True

    assert [x.text for x in words] == ["hello", "world"]
    assert (words[0].start, words[0].end) == (0.0, 0.5)
    # Transducer decoding gives no calibrated confidence signals.
    assert words[0].probability is None
    assert words[0].no_speech_prob is None


# --------------------------------------------------------------------------- #
# Protocol conformance
# --------------------------------------------------------------------------- #


def test_backends_satisfy_transcriber_protocol():
    # runtime_checkable checks method presence; construction stays import-free.
    assert isinstance(FasterWhisperTranscriber(), Transcriber)
    assert isinstance(ParakeetTranscriber(), Transcriber)
