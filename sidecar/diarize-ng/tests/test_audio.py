"""Hermetic tests for diarng.audio.

Pure logic (chunk planning, loudness gain, wav roundtrip, slicing) is tested
for real with numpy. The one model-free external dependency is ffmpeg; the
tests that exercise ``load_pcm`` generate a WAV on the fly and decode it with a
real ffmpeg call, skipping cleanly when ffmpeg is absent (no network, no
downloads).
"""

from __future__ import annotations

import shutil
import wave

import numpy as np
import pytest

from diarng import audio
from diarng.types import SAMPLE_RATE, Span

_HAS_FFMPEG = shutil.which("ffmpeg") is not None
_ffmpeg = pytest.mark.skipif(not _HAS_FFMPEG, reason="ffmpeg not on PATH")


# --------------------------------------------------------------------------- #
# chunk_spans
# --------------------------------------------------------------------------- #

def test_chunk_spans_short_file_single_span():
    # Shorter than one chunk -> exactly one span covering the whole thing.
    spans = audio.chunk_spans(50.0, chunk_s=100.0, overlap_s=10.0)
    assert spans == [Span(0.0, 50.0)]


def test_chunk_spans_boundary_equal_chunk_is_single_span():
    # duration == chunk_s is still "not longer than a chunk" -> one span.
    spans = audio.chunk_spans(100.0, chunk_s=100.0, overlap_s=10.0)
    assert spans == [Span(0.0, 100.0)]


def test_chunk_spans_multichunk_overlap_and_coverage():
    spans = audio.chunk_spans(250.0, chunk_s=100.0, overlap_s=10.0)
    # step = 90 -> starts at 0, 90, 180; last clipped to 250.
    assert spans == [Span(0.0, 100.0), Span(90.0, 190.0), Span(180.0, 250.0)]
    # Interior neighbours overlap by exactly overlap_s.
    assert spans[0].overlap(spans[1]) == pytest.approx(10.0)
    assert spans[1].overlap(spans[2]) == pytest.approx(10.0)
    # Union covers [0, duration] with no gap.
    assert spans[0].start == 0.0
    assert spans[-1].end == 250.0
    for a, b in zip(spans, spans[1:]):
        assert b.start <= a.end  # contiguous / overlapping, never a gap


def test_chunk_spans_exact_multiple_of_chunk():
    # duration an exact multiple of chunk_s: last window is a real (>overlap)
    # chunk, so nothing is merged.
    spans = audio.chunk_spans(200.0, chunk_s=100.0, overlap_s=10.0)
    assert spans == [Span(0.0, 100.0), Span(90.0, 190.0), Span(180.0, 200.0)]
    assert spans[-1].duration == pytest.approx(20.0)


def test_chunk_spans_trailing_fragment_merged():
    # duration 185: naive stepping would emit a 5 s tail [180, 185] that is
    # already inside [90, 185]; it is < overlap_s (10) so it gets merged away.
    spans = audio.chunk_spans(185.0, chunk_s=100.0, overlap_s=10.0)
    assert spans == [Span(0.0, 100.0), Span(90.0, 185.0)]
    assert spans[-1].end == 185.0  # coverage preserved after the merge
    assert spans[-1].duration >= 10.0  # no sub-overlap sliver remains


def test_chunk_spans_covers_entire_duration_various():
    for duration in [30.0, 100.0, 101.0, 150.0, 185.0, 250.0, 613.0, 1234.5]:
        spans = audio.chunk_spans(duration, chunk_s=100.0, overlap_s=10.0)
        assert spans[0].start == 0.0
        assert spans[-1].end == pytest.approx(duration)
        for a, b in zip(spans, spans[1:]):
            assert b.start <= a.end  # no coverage gap between neighbours


def test_chunk_spans_rejects_bad_params():
    with pytest.raises(ValueError):
        audio.chunk_spans(100.0, chunk_s=0.0, overlap_s=0.0)
    with pytest.raises(ValueError):
        audio.chunk_spans(100.0, chunk_s=100.0, overlap_s=100.0)  # overlap>=chunk


# --------------------------------------------------------------------------- #
# loudness_normalize
# --------------------------------------------------------------------------- #

def _rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(x.astype(np.float64)))))


def test_loudness_normalize_hits_target_rms():
    t = np.arange(SAMPLE_RATE) / SAMPLE_RATE
    sine = (0.2 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
    out = audio.loudness_normalize(sine, target_dbfs=-23.0)

    target_rms = 10.0 ** (-23.0 / 20.0)
    # Gain is chosen to force RMS onto the target, independent of input level.
    assert _rms(out) == pytest.approx(target_rms, rel=1e-3)
    assert np.max(np.abs(out)) <= 0.99 + 1e-6


def test_loudness_normalize_scales_up_quiet_signal():
    t = np.arange(SAMPLE_RATE) / SAMPLE_RATE
    quiet = (0.001 * np.sin(2 * np.pi * 300 * t)).astype(np.float32)
    out = audio.loudness_normalize(quiet)
    # A quiet input must be amplified (gain > 1).
    assert _rms(out) > _rms(quiet)
    assert _rms(out) == pytest.approx(10.0 ** (-23.0 / 20.0), rel=1e-3)


def test_loudness_normalize_peak_clamped_at_099():
    # Near-silent floor plus one full-scale transient: the tiny RMS demands a
    # large gain, which without the clamp would drive the transient far past 1.
    pcm = np.full(SAMPLE_RATE, 0.001, dtype=np.float32)
    pcm[0] = 0.9
    out = audio.loudness_normalize(pcm)
    assert np.max(np.abs(out)) == pytest.approx(0.99, abs=1e-4)
    assert np.max(np.abs(out)) <= 0.99 + 1e-6


def test_loudness_normalize_silence_returned_unchanged():
    zeros = np.zeros(1000, dtype=np.float32)
    out = audio.loudness_normalize(zeros)
    assert np.array_equal(out, zeros)
    # A sub-epsilon signal is also treated as silence (no exploding gain).
    tiny = np.full(1000, 1e-12, dtype=np.float32)
    assert np.array_equal(audio.loudness_normalize(tiny), tiny)


def test_loudness_normalize_empty_ok():
    empty = np.zeros(0, dtype=np.float32)
    assert audio.loudness_normalize(empty).size == 0


# --------------------------------------------------------------------------- #
# write_wav_pcm16 / slice_pcm
# --------------------------------------------------------------------------- #

def test_wav_roundtrip_via_stdlib_wave(tmp_path):
    t = np.arange(2 * SAMPLE_RATE) / SAMPLE_RATE
    sine = (0.5 * np.sin(2 * np.pi * 220 * t)).astype(np.float32)
    path = tmp_path / "tone.wav"
    audio.write_wav_pcm16(sine, path)

    with wave.open(str(path), "rb") as w:
        assert w.getnchannels() == 1
        assert w.getsampwidth() == 2
        assert w.getframerate() == SAMPLE_RATE
        assert w.getnframes() == len(sine)
        raw = w.readframes(w.getnframes())

    back = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32767.0
    # Roundtrip is exact to 16-bit quantization.
    assert np.allclose(back, sine, atol=1.0 / 32767 + 1e-6)


def test_slice_pcm_returns_view_and_clamps():
    pcm = np.arange(SAMPLE_RATE, dtype=np.float32)  # 1 s ramp
    sl = audio.slice_pcm(pcm, Span(0.25, 0.75))
    assert len(sl) == SAMPLE_RATE // 2
    assert sl[0] == pytest.approx(0.25 * SAMPLE_RATE)
    # It is a view: mutating the slice mutates the parent buffer.
    assert np.shares_memory(sl, pcm)

    # Out-of-range spans clamp to available samples instead of raising.
    tail = audio.slice_pcm(pcm, Span(0.9, 10.0))
    assert len(tail) == SAMPLE_RATE - int(round(0.9 * SAMPLE_RATE))
    empty = audio.slice_pcm(pcm, Span(5.0, 6.0))
    assert empty.size == 0
    inverted = audio.slice_pcm(pcm, Span(0.5, 0.25))  # end < start -> empty
    assert inverted.size == 0


# --------------------------------------------------------------------------- #
# load_pcm (real ffmpeg; skipped when unavailable)
# --------------------------------------------------------------------------- #

@_ffmpeg
def test_load_pcm_decodes_wav(tmp_path):
    t = np.arange(2 * SAMPLE_RATE) / SAMPLE_RATE
    sine = (0.3 * np.sin(2 * np.pi * 220 * t)).astype(np.float32)
    path = tmp_path / "in.wav"
    audio.write_wav_pcm16(sine, path)

    pcm = audio.load_pcm(str(path))
    assert pcm.dtype == np.float32
    assert abs(len(pcm) - len(sine)) <= 2  # same rate -> same sample count
    n = min(len(pcm), len(sine))
    assert np.allclose(pcm[:n], sine[:n], atol=2e-3)


@_ffmpeg
def test_load_pcm_enforces_max_seconds(tmp_path):
    t = np.arange(SAMPLE_RATE) / SAMPLE_RATE  # 1 s
    sine = (0.2 * np.sin(2 * np.pi * 200 * t)).astype(np.float32)
    path = tmp_path / "in.wav"
    audio.write_wav_pcm16(sine, path)

    with pytest.raises(RuntimeError, match="ceiling|max_seconds"):
        audio.load_pcm(str(path), max_seconds=0.5)


@_ffmpeg
def test_load_pcm_raises_with_stderr_on_bad_input(tmp_path):
    bad = tmp_path / "bad.wav"
    bad.write_bytes(b"this is not audio data at all")
    with pytest.raises(RuntimeError) as excinfo:
        audio.load_pcm(str(bad))
    # The error carries ffmpeg's own diagnosis, not just a generic failure.
    assert "ffmpeg failed" in str(excinfo.value)
