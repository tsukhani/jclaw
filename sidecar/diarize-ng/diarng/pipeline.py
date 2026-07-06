"""The Diarizer facade — the one place the stage order lives.

Every other module in ``diarng`` is a single stage with a narrow contract
(see ``diarng/types.py``). This module is the *only* sequencer: it wires the
stages together in exactly one order and does nothing clever of its own. That
is deliberate — the production lesson (jclaw JCLAW-628) is that a diarization
pipeline drifts into bugs when stage order is duplicated across call sites, so
here it is written down once and only once.

Stage order (each arrow is a hand-off through a ``types`` dataclass or a numpy
array, never a private field of another stage):

    segment            PowersetSegmenter.segment -> SegmentationOutput
    window_spans       -> [(Span, local_speaker), ...]
    embed              slice PCM per window -> Embedder.embed_batch -> (N, D)
    cluster            nme_spectral_cluster -> (N,) global labels
    resegment          VBxReseg.resegment   -> (N,) smoothed labels
    labels_to_segments -> the post-resegmentation speaker timeline
    transcribe         Transcriber.transcribe -> [Word]
    hallucination_gate -> [Word] (silence-driven runs dropped)
    assign_speakers    -> [Word] with a speaker each
    centroids + match  -> {cluster: enrolled name}
    build_turns        -> [Turn]
    refine (optional)  LlmRefiner.refine -> [Turn] (word-preserving)

The model-backed stages are injectable seams (``segmenter``, ``embedder``,
``transcriber``, ``refiner``, ``store``); when not supplied they default to the
real backends, which load their heavy dependencies lazily. So constructing a
``Diarizer`` is cheap and importing this module needs only numpy/scipy.
"""

from __future__ import annotations

import time
from contextlib import contextmanager
from dataclasses import dataclass

import numpy as np

from diarng import audio
from diarng.asr import FasterWhisperTranscriber, hallucination_gate
from diarng.assign import assign_speakers, build_turns
from diarng.clustering import VBxReseg, labels_to_segments, nme_spectral_cluster
from diarng.embeddings import SherpaWeSpeakerEmbedder, centroids, window_spans
from diarng.identity import VoiceprintStore
from diarng.identity import match as identity_match
from diarng.refine import LlmRefiner
from diarng.segmentation import DEFAULT_MODEL as DEFAULT_SEGMENTATION_MODEL
from diarng.segmentation import PowersetSegmenter
from diarng.types import DiarizationResult


@dataclass
class DiarizerConfig:
    """Static configuration for a :class:`Diarizer` run.

    Only model ids, file paths and stage tunables live here; the runtime
    objects (loaded models, HTTP clients) are the ``Diarizer``'s injectable
    seams. Defaults reproduce the stack described in the README.
    """

    # model identity
    segmentation_model: str = DEFAULT_SEGMENTATION_MODEL
    asr_model: str = "large-v3"
    embedding_model_path: str | None = None  # sherpa WeSpeaker ONNX file

    # sliding-window embedding geometry (see embeddings.window_spans)
    win_s: float = 1.5
    hop_s: float = 0.75
    min_window_s: float = 0.5

    # global clustering
    max_speakers: int = 10
    merge_gap: float = 0.1  # labels_to_segments same-speaker merge gap

    # enrolled-speaker identification (identity.match)
    voiceprint_store_path: str | None = None
    match_threshold: float = 0.6
    match_gap: float = 0.03

    # optional DiarizationLM refinement over an OpenAI-compatible endpoint
    refine_enabled: bool = False
    llm_base_url: str | None = None
    llm_model: str | None = None
    llm_api_key: str | None = None

    # transcription / device
    language: str | None = None
    device: str | None = None  # None -> segmenter auto-picks (cuda>mps>cpu)
    hf_token: str | None = None


class Diarizer:
    """Orchestrates the diarize-ng stages into a single :meth:`diarize` call.

    Args:
        config: the static :class:`DiarizerConfig`.
        segmenter / embedder / transcriber / refiner / store: injectable
            seams. Each defaults to the real backend built from ``config``
            (lazy model load), except ``refiner`` which is only built when
            ``config.refine_enabled`` and an endpoint is configured. Tests
            pass fakes for every seam.
    """

    def __init__(
        self,
        config: DiarizerConfig,
        segmenter=None,
        embedder=None,
        transcriber=None,
        refiner=None,
        store=None,
    ) -> None:
        self.config = config
        self.segmenter = segmenter or PowersetSegmenter(
            model=config.segmentation_model,
            device=config.device,
            hf_token=config.hf_token,
        )
        self.embedder = embedder or SherpaWeSpeakerEmbedder(
            config.embedding_model_path
        )
        self.transcriber = transcriber or FasterWhisperTranscriber(
            model=config.asr_model,
            device=config.device or "auto",
        )
        self.store = store or VoiceprintStore(config.voiceprint_store_path)

        self.refiner = refiner
        if (
            self.refiner is None
            and config.refine_enabled
            and config.llm_base_url
            and config.llm_model
        ):
            self.refiner = LlmRefiner(
                config.llm_base_url, config.llm_model, config.llm_api_key
            )

    def diarize(self, path: str, num_speakers: int | None = None) -> DiarizationResult:
        """Run the full cascade on ``path`` and return a :class:`DiarizationResult`.

        ``num_speakers`` is an optional hint: it is forwarded to the segmenter
        and to spectral clustering (both treat a value < 2 as "decide for me").
        The returned ``meta`` carries per-stage wall-times and the model ids.
        """
        cfg = self.config
        timings: dict[str, float] = {}

        @contextmanager
        def stage(name: str):
            t0 = time.perf_counter()
            yield
            timings[name] = time.perf_counter() - t0

        # 1. Local segmentation -> per-window speaker timeline + overlaps.
        with stage("segment"):
            seg_out = self.segmenter.segment(path, num_speakers=num_speakers)
        overlaps = list(seg_out.overlaps)

        # 2. Plan sliding windows inside each local segment.
        with stage("window_spans"):
            windowed = window_spans(
                seg_out.segments,
                win_s=cfg.win_s,
                hop_s=cfg.hop_s,
                min_s=cfg.min_window_s,
            )
        spans = [span for span, _local in windowed]

        # 3. Slice the decoded PCM per window and embed. Skip the ffmpeg decode
        #    entirely when there is nothing to embed (e.g. all-silence input).
        with stage("embed"):
            if spans:
                pcm = audio.load_pcm(path)
                chunks = [audio.slice_pcm(pcm, span) for span in spans]
                embeddings = self.embedder.embed_batch(chunks)
            else:
                embeddings = np.empty((0, 0), dtype="float32")

        # 4. Global spectral clustering of the window embeddings.
        with stage("cluster"):
            labels = nme_spectral_cluster(
                embeddings,
                num_speakers=num_speakers,
                max_speakers=cfg.max_speakers,
            )

        # 5. VBx-style HMM resegmentation smooths rapid label flips.
        with stage("resegment"):
            labels = VBxReseg().resegment(embeddings, spans, labels)

        # 6. Collapse windows into the post-resegmentation speaker timeline.
        with stage("labels_to_segments"):
            segments = labels_to_segments(labels, spans, gap=cfg.merge_gap)

        # 7. Word-timestamped ASR (independent timeline).
        with stage("transcribe"):
            words = self.transcriber.transcribe(path, language=cfg.language)

        # 8. Drop silence-driven ASR hallucination runs.
        with stage("hallucination_gate"):
            words = hallucination_gate(words)

        # 9. Attribute each surviving word to a diarized speaker.
        with stage("assign_speakers"):
            words = assign_speakers(words, segments)

        # 10. Cluster centroids -> match enrolled voiceprints -> names.
        with stage("identity"):
            cluster_centroids = centroids(embeddings, labels)
            names = identity_match(
                self.store,
                cluster_centroids,
                threshold=cfg.match_threshold,
                gap=cfg.match_gap,
            )

        # 11. Fold words into speaker turns, attaching any matched names.
        with stage("build_turns"):
            turns = build_turns(words, names=names)

        # 12. Optional constrained LLM relabeling (word-preserving, safe no-op
        #     on any failure — see refine.LlmRefiner).
        if self.refiner is not None:
            with stage("refine"):
                turns = self.refiner.refine(turns)

        distinct = {int(label) for label in labels}
        meta = {
            "device": getattr(self.segmenter, "device", None) or cfg.device,
            "num_windows": len(spans),
            "models": {
                "segmentation": cfg.segmentation_model,
                "asr": cfg.asr_model,
                "embedding": cfg.embedding_model_path,
            },
            "refined": self.refiner is not None,
            "timings": timings,
        }
        return DiarizationResult(
            turns=turns,
            segments=segments,
            overlaps=overlaps,
            num_speakers=len(distinct),
            meta=meta,
        )


def result_to_dict(result: DiarizationResult) -> dict:
    """Serialize a :class:`DiarizationResult` to JSON-safe plain dicts.

    Shared by :mod:`diarng.serve` (HTTP response) and :mod:`diarng.cli`
    (``--format json``) so the wire shape is defined once.
    """
    return {
        "turns": [
            {
                "start": turn.start,
                "end": turn.end,
                "speaker": turn.speaker,
                "name": turn.name,
                "text": turn.text,
                "words": [
                    {
                        "start": w.start,
                        "end": w.end,
                        "text": w.text,
                        "speaker": w.speaker,
                        "probability": w.probability,
                        "no_speech_prob": w.no_speech_prob,
                    }
                    for w in turn.words
                ],
            }
            for turn in result.turns
        ],
        "segments": [
            {"start": s.start, "end": s.end, "speaker": s.speaker}
            for s in result.segments
        ],
        "overlaps": [{"start": o.start, "end": o.end} for o in result.overlaps],
        "num_speakers": result.num_speakers,
        "meta": result.meta,
    }
