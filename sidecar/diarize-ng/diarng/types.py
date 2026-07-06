"""The inter-module contract for diarize-ng.

Every stage communicates through these types plus numpy arrays. Times are
float seconds from the start of the recording; speakers are zero-based int
cluster indices (a display name is attached only at the Turn level, by the
identity stage). All audio everywhere is SAMPLE_RATE mono float32.

Stage interfaces (implemented in the named modules; summarized here so a
reader of this one file knows the whole dataflow):

    audio.load_pcm(path) -> np.ndarray                       # float32 16k mono
    audio.chunk_spans(duration_s, chunk_s, overlap_s) -> list[Span]
    segmentation.PowersetSegmenter().segment(path) -> SegmentationOutput
    embeddings.window_spans(segments, win_s, hop_s) -> list[tuple[Span, int]]
    embeddings.Embedder.embed_batch(list[np.ndarray]) -> np.ndarray  # (N, D)
    clustering.nme_spectral_cluster(X, num_speakers=None) -> np.ndarray  # (N,)
    clustering.VBxReseg().resegment(X, spans, init_labels) -> np.ndarray
    asr.Transcriber.transcribe(path, language=None) -> list[Word]
    assign.assign_speakers(words, segments) -> list[Word]
    assign.build_turns(words, names={}) -> list[Turn]
    refine.LlmRefiner.refine(turns) -> list[Turn]            # word-preserving
    identity.match(store, centroids, threshold=0.6, gap=0.03) -> dict[int, str]
    pipeline.Diarizer.diarize(path, num_speakers=None) -> DiarizationResult
"""

from __future__ import annotations

from dataclasses import dataclass, field

SAMPLE_RATE = 16_000
"""Canonical sample rate (Hz). Every PCM array in the system is 16 kHz."""


@dataclass(frozen=True)
class Span:
    """A half-open time interval [start, end) in seconds."""

    start: float
    end: float

    @property
    def duration(self) -> float:
        return self.end - self.start

    def overlap(self, other: "Span") -> float:
        """Overlapped duration with `other`, >= 0."""
        return max(0.0, min(self.end, other.end) - max(self.start, other.start))


@dataclass(frozen=True)
class Segment:
    """A diarized speech region attributed to one speaker cluster."""

    start: float
    end: float
    speaker: int

    @property
    def duration(self) -> float:
        return self.end - self.start


@dataclass(frozen=True)
class SegmentationOutput:
    """Local segmentation result, before global clustering.

    `segments` carry *local* (per-window) speaker indices that clustering
    later maps to global clusters; `overlaps` are regions where 2+ speakers
    are simultaneously active — where downstream re-attribution is possible.
    """

    segments: tuple[Segment, ...]
    overlaps: tuple[Span, ...]


@dataclass
class Word:
    """One ASR word with timing, confidence and (eventually) a speaker."""

    start: float
    end: float
    text: str
    speaker: int | None = None
    # faster-whisper style confidence signals; None when the backend
    # doesn't provide them. Used by the hallucination gate.
    probability: float | None = None
    no_speech_prob: float | None = None

    @property
    def mid(self) -> float:
        return (self.start + self.end) / 2.0


@dataclass
class Turn:
    """A contiguous run of words by one speaker — the transcript unit."""

    start: float
    end: float
    speaker: int
    text: str
    words: list[Word] = field(default_factory=list)
    name: str | None = None  # enrolled display name, when identity matched


@dataclass
class DiarizationResult:
    """The pipeline's final product."""

    turns: list[Turn]
    segments: list[Segment]  # the post-resegmentation speaker timeline
    overlaps: list[Span]
    num_speakers: int
    meta: dict = field(default_factory=dict)  # device, timings, model ids


def merge_adjacent(segments: list[Segment], gap: float = 0.0) -> list[Segment]:
    """Merge same-speaker segments separated by <= `gap` seconds.

    Lives in the shared contract module so every stage that collapses a
    segment timeline reuses one implementation (currently clustering's
    ``labels_to_segments``; available to any future consumer).
    """
    out: list[Segment] = []
    for seg in sorted(segments, key=lambda s: (s.start, s.end)):
        if out and out[-1].speaker == seg.speaker and seg.start - out[-1].end <= gap:
            out[-1] = Segment(out[-1].start, max(out[-1].end, seg.end), seg.speaker)
        else:
            out.append(seg)
    return out
