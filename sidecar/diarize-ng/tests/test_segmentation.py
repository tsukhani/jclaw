"""Hermetic tests for diarng.segmentation.

No pyannote / torch import here: the model-backed seams (``_pipeline``,
``_pick_device``) are exercised with fakes injected via monkeypatch, and the
pure conversion logic (``annotation_to_output`` / ``select_annotations``) is
driven with hand-written duck-typed annotation objects.
"""

from __future__ import annotations

import sys
import types as _types
from dataclasses import dataclass

import pytest

from diarng import segmentation
from diarng.segmentation import (
    PowersetSegmenter,
    annotation_to_output,
    select_annotations,
)
from diarng.types import Segment, SegmentationOutput, Span


# --------------------------------------------------------------------------
# Fake pyannote annotation objects (duck-typed to the two methods we use).
# --------------------------------------------------------------------------
@dataclass
class FakeSegment:
    """Stands in for pyannote's Segment — only .start / .end are read."""

    start: float
    end: float


class FakeAnnotation:
    """Minimal Annotation: yields tracks and (optionally) overlap spans."""

    def __init__(self, tracks, overlaps=None):
        # tracks: list of (start, end, label); overlaps: list of (start, end)
        self._tracks = tracks
        self._overlaps = overlaps

    def itertracks(self, yield_label=False):
        for start, end, label in self._tracks:
            if yield_label:
                yield FakeSegment(start, end), "_track", label
            else:
                yield FakeSegment(start, end), "_track"

    def get_overlap(self):
        return [FakeSegment(s, e) for s, e in (self._overlaps or [])]


class NoOverlapAnnotation:
    """An annotation with itertracks but NO get_overlap (e.g. exclusive)."""

    def __init__(self, tracks):
        self._tracks = tracks

    def itertracks(self, yield_label=False):
        for start, end, label in self._tracks:
            yield FakeSegment(start, end), "_track", label


class FakeResult:
    """A pyannote 4 result exposing the two named annotations."""

    def __init__(self, exclusive=None, plain=None):
        if exclusive is not None:
            self.exclusive_speaker_diarization = exclusive
        if plain is not None:
            self.speaker_diarization = plain


# --------------------------------------------------------------------------
# annotation_to_output — label mapping.
# --------------------------------------------------------------------------
def test_labels_mapped_zero_based_in_first_appearance_order():
    # Labels appear as B, A, B, C -> B=0, A=1, C=2 (repeat B keeps its index).
    ann = FakeAnnotation(
        [(0.0, 1.0, "B"), (1.0, 2.0, "A"), (2.0, 3.0, "B"), (3.0, 4.0, "C")]
    )
    out = annotation_to_output(ann, plain=None)

    assert isinstance(out, SegmentationOutput)
    assert out.segments == (
        Segment(0.0, 1.0, 0),
        Segment(1.0, 2.0, 1),
        Segment(2.0, 3.0, 0),
        Segment(3.0, 4.0, 2),
    )
    assert out.overlaps == ()


def test_segment_times_are_floats():
    # pyannote may hand back numpy/int-ish times; we coerce to float.
    ann = FakeAnnotation([(1, 2, "spk")])
    out = annotation_to_output(ann, plain=None)
    (seg,) = out.segments
    assert isinstance(seg.start, float) and isinstance(seg.end, float)
    assert seg.speaker == 0


def test_empty_annotation_yields_empty_output():
    out = annotation_to_output(FakeAnnotation([]), plain=FakeAnnotation([]))
    assert out.segments == ()
    assert out.overlaps == ()


# --------------------------------------------------------------------------
# annotation_to_output — overlap extraction.
# --------------------------------------------------------------------------
def test_overlaps_taken_from_plain_annotation():
    excl = NoOverlapAnnotation([(0.0, 2.0, "A"), (2.0, 4.0, "B")])
    plain = FakeAnnotation(
        [(0.0, 4.0, "A")], overlaps=[(1.0, 1.5), (3.2, 3.4)]
    )
    out = annotation_to_output(excl, plain)

    # Segments come from the exclusive annotation...
    assert [s.speaker for s in out.segments] == [0, 1]
    # ...overlaps only from the overlap-aware (plain) one.
    assert out.overlaps == (Span(1.0, 1.5), Span(3.2, 3.4))


def test_no_overlaps_when_plain_is_none():
    out = annotation_to_output(FakeAnnotation([(0.0, 1.0, "A")]), plain=None)
    assert out.overlaps == ()


def test_no_overlaps_when_plain_lacks_get_overlap():
    # A plain annotation object without get_overlap must not crash.
    plain = NoOverlapAnnotation([(0.0, 1.0, "A")])
    out = annotation_to_output(FakeAnnotation([(0.0, 1.0, "A")]), plain)
    assert out.overlaps == ()


# --------------------------------------------------------------------------
# select_annotations — the exclusive-preferred fallback chain.
# --------------------------------------------------------------------------
def test_select_prefers_exclusive_overlap_from_plain():
    excl = NoOverlapAnnotation([(0.0, 1.0, "A")])
    plain = FakeAnnotation([(0.0, 1.0, "A")], overlaps=[(0.3, 0.6)])
    result = FakeResult(exclusive=excl, plain=plain)

    seg_ann, overlap_ann = select_annotations(result)
    assert seg_ann is excl
    assert overlap_ann is plain


def test_select_falls_back_to_plain_for_segments_when_exclusive_absent():
    plain = FakeAnnotation([(0.0, 1.0, "A")], overlaps=[(0.3, 0.6)])
    result = FakeResult(exclusive=None, plain=plain)  # attr not set at all

    seg_ann, overlap_ann = select_annotations(result)
    assert seg_ann is plain  # segments fall back to the plain annotation
    assert overlap_ann is plain


def test_select_falls_back_to_raw_result_when_both_absent():
    # Older pipelines return a bare Annotation with neither named attribute.
    raw = FakeAnnotation([(0.0, 1.0, "A")])
    seg_ann, overlap_ann = select_annotations(raw)
    assert seg_ann is raw
    assert overlap_ann is None


def test_select_treats_explicit_none_exclusive_like_missing():
    plain = FakeAnnotation([(0.0, 1.0, "A")])
    result = FakeResult(plain=plain)
    result.exclusive_speaker_diarization = None  # explicit None, not absent

    seg_ann, overlap_ann = select_annotations(result)
    assert seg_ann is plain
    assert overlap_ann is plain


# --------------------------------------------------------------------------
# PowersetSegmenter.segment — delegation + num_speakers gating.
# --------------------------------------------------------------------------
def _fake_result():
    excl = NoOverlapAnnotation([(0.0, 1.0, "A"), (1.0, 2.0, "B")])
    plain = FakeAnnotation([(0.0, 2.0, "A")], overlaps=[(0.5, 0.7)])
    return FakeResult(exclusive=excl, plain=plain)


class RecordingPipeline:
    """A callable pipeline that records its invocation and returns a result."""

    def __init__(self, result):
        self.result = result
        self.calls = []

    def __call__(self, audio_path, **kwargs):
        self.calls.append((audio_path, kwargs))
        return self.result


@pytest.mark.parametrize(
    ("num_speakers", "expected_kwargs"),
    [
        (None, {}),
        (1, {}),  # a "1 speaker" hint is dropped — leave the count automatic
        (2, {"num_speakers": 2}),
        (5, {"num_speakers": 5}),
    ],
)
def test_segment_gates_num_speakers(monkeypatch, num_speakers, expected_kwargs):
    pipe = RecordingPipeline(_fake_result())
    seg = PowersetSegmenter()
    monkeypatch.setattr(seg, "_pipeline", lambda: pipe)

    seg.segment("audio.wav", num_speakers=num_speakers)

    assert pipe.calls == [("audio.wav", expected_kwargs)]


def test_segment_delegates_to_conversion(monkeypatch):
    pipe = RecordingPipeline(_fake_result())
    seg = PowersetSegmenter()
    monkeypatch.setattr(seg, "_pipeline", lambda: pipe)

    out = seg.segment("audio.wav")

    # Segments from the exclusive annotation, remapped 0/1.
    assert out.segments == (Segment(0.0, 1.0, 0), Segment(1.0, 2.0, 1))
    # Overlaps from the plain (overlap-aware) annotation.
    assert out.overlaps == (Span(0.5, 0.7),)


# --------------------------------------------------------------------------
# _pipeline / _pick_device — model-load seam with fake torch + pyannote.
# --------------------------------------------------------------------------
def _install_fake_torch(monkeypatch, *, cuda=False, mps=False):
    fake_torch = _types.ModuleType("torch")
    fake_torch.cuda = _types.SimpleNamespace(is_available=lambda: cuda)
    fake_torch.backends = _types.SimpleNamespace(
        mps=_types.SimpleNamespace(is_available=lambda: mps)
    )
    fake_torch.device = lambda d: ("device", d)
    monkeypatch.setitem(sys.modules, "torch", fake_torch)
    return fake_torch


def _install_fake_pyannote(monkeypatch, pipeline_factory):
    fake_pkg = _types.ModuleType("pyannote")
    fake_audio = _types.ModuleType("pyannote.audio")

    class FakePipelineClass:
        @staticmethod
        def from_pretrained(model, **kwargs):
            return pipeline_factory(model, kwargs)

    fake_audio.Pipeline = FakePipelineClass
    monkeypatch.setitem(sys.modules, "pyannote", fake_pkg)
    monkeypatch.setitem(sys.modules, "pyannote.audio", fake_audio)


@pytest.mark.parametrize(
    ("cuda", "mps", "expected"),
    [(True, False, "cuda"), (False, True, "mps"), (False, False, "cpu")],
)
def test_pick_device_priority(monkeypatch, cuda, mps, expected):
    _install_fake_torch(monkeypatch, cuda=cuda, mps=mps)
    assert PowersetSegmenter()._pick_device() == expected


def test_pipeline_passes_auth_token_moves_to_device_and_caches(monkeypatch):
    _install_fake_torch(monkeypatch, cuda=True)  # -> device "cuda"
    captured = {}

    class FakePipe:
        def to(self, device):
            captured["to"] = device
            return self

    def factory(model, kwargs):
        captured["model"] = model
        captured["kwargs"] = kwargs
        return FakePipe()

    _install_fake_pyannote(monkeypatch, factory)

    seg = PowersetSegmenter(hf_token="secret-token")
    pipe = seg._pipeline()

    assert captured["model"] == segmentation.DEFAULT_MODEL
    assert captured["kwargs"] == {"use_auth_token": "secret-token"}
    assert captured["to"] == ("device", "cuda")  # torch.device("cuda")
    assert seg.device == "cuda"
    # Cached: a second call returns the same object without reloading.
    assert seg._pipeline() is pipe


def test_pipeline_cpu_skips_device_move(monkeypatch):
    _install_fake_torch(monkeypatch, cuda=False, mps=False)  # -> "cpu"
    moved = {"to": False}

    class FakePipe:
        def to(self, device):  # pragma: no cover - must not be called
            moved["to"] = True
            return self

    _install_fake_pyannote(monkeypatch, lambda model, kwargs: FakePipe())

    seg = PowersetSegmenter()
    seg._pipeline()

    assert seg.device == "cpu"
    assert moved["to"] is False


def test_pipeline_no_auth_token_omits_kwarg(monkeypatch):
    _install_fake_torch(monkeypatch, cuda=False, mps=False)
    captured = {}

    def factory(model, kwargs):
        captured["kwargs"] = kwargs
        return _types.SimpleNamespace(to=lambda d: None)

    _install_fake_pyannote(monkeypatch, factory)

    PowersetSegmenter()._pipeline()
    assert captured["kwargs"] == {}


def test_gated_model_raises_runtimeerror(monkeypatch):
    _install_fake_torch(monkeypatch, cuda=False, mps=False)
    _install_fake_pyannote(monkeypatch, lambda model, kwargs: None)  # gated

    seg = PowersetSegmenter()
    with pytest.raises(RuntimeError, match="gated"):
        seg._pipeline()
