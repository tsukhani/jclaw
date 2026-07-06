"""Local speaker segmentation via pyannote 4 powerset diarization.

The segmentation stage turns raw audio into a *local* speaker timeline:
speech regions, speaker-change points and overlapped-speech regions, all
produced by a single sliding-window powerset model (Plaquet & Bredin,
pyannote 4). Downstream clustering maps the per-turn labels here to global
speaker clusters; this stage only needs to be internally consistent within
one recording.

Why a pipeline and not just the raw segmentation model: the community-1
`Pipeline` already runs local segmentation, embedding and clustering, and —
critically for our consumer — exposes two views of the same result:

  * ``exclusive_speaker_diarization`` — one speaker at a time, with the model
    itself picking the dominant/transcribable voice inside overlaps. This is
    the right timeline to reconcile against a mono ASR transcript, which is
    the only consumer downstream. On the JCLAW-565 debate benchmark (9.4%
    overlapped speech) it cut DER from 12.5% to 7.9% versus the overlap-aware
    output by eliminating double-claimed time, with identical attribution.
  * ``speaker_diarization`` — the overlap-aware annotation. Its
    ``get_overlap()`` is where 2+ speakers are simultaneously active, i.e. the
    only regions where downstream re-attribution is even possible. The
    exclusive annotation has no overlaps by construction, so overlaps MUST be
    taken from this one.

This mirrors the production lesson in ``sidecar/diarize/serve.py`` (JCLAW-565
/ 605 / 646); the conversion logic is factored into pure functions here so it
can be unit-tested without loading a gated multi-GB model.
"""

from __future__ import annotations

from diarng.types import Segment, SegmentationOutput, Span

DEFAULT_MODEL = "pyannote/speaker-diarization-community-1"


def select_annotations(result):
    """Choose the (segment-source, overlap-source) annotations from a result.

    Returns ``(segment_annotation, overlap_annotation)``:

    * ``segment_annotation`` is the exclusive diarization when the pipeline
      exposes it, else the plain (overlap-aware) diarization, else the raw
      result object itself (older pipelines return an ``Annotation`` directly).
    * ``overlap_annotation`` is the overlap-aware ``speaker_diarization`` when
      present, else ``None`` — the exclusive annotation carries no overlaps, so
      overlaps always come from the plain one.

    Attribute access is via ``getattr`` with a ``None`` default so both a
    missing attribute and an explicit ``None`` fall through the same way,
    matching the production sidecar's behaviour.
    """
    segment_annotation = getattr(result, "exclusive_speaker_diarization", None)
    if segment_annotation is None:
        segment_annotation = getattr(result, "speaker_diarization", result)
    overlap_annotation = getattr(result, "speaker_diarization", None)
    return segment_annotation, overlap_annotation


def annotation_to_output(annotation, plain) -> SegmentationOutput:
    """Convert pyannote annotations into a :class:`SegmentationOutput`.

    ``annotation`` supplies the speaker timeline: each ``itertracks`` turn
    becomes a :class:`Segment`, with pyannote's opaque speaker labels remapped
    to zero-based ints in first-appearance order (label -> 0, next new label
    -> 1, ...) so downstream stages see a stable local index space.

    ``plain`` is the overlap-aware annotation; its ``get_overlap()`` spans
    become the :class:`Span` overlaps. ``plain`` may be ``None`` (no
    overlap-aware view available), in which case there are no overlaps.

    Pure and side-effect-free: the arguments only need to duck-type
    ``itertracks(yield_label=True)`` and ``get_overlap()`` returning objects
    with ``.start`` / ``.end`` — which is exactly what the tests exploit.
    """
    labels: dict = {}
    segments: list[Segment] = []
    for turn, _track, speaker in annotation.itertracks(yield_label=True):
        idx = labels.setdefault(speaker, len(labels))
        segments.append(Segment(float(turn.start), float(turn.end), idx))

    overlaps: list[Span] = []
    if plain is not None and hasattr(plain, "get_overlap"):
        for seg in plain.get_overlap():
            overlaps.append(Span(float(seg.start), float(seg.end)))

    return SegmentationOutput(segments=tuple(segments), overlaps=tuple(overlaps))


class PowersetSegmenter:
    """pyannote community-1 diarization pipeline, held resident and lazy-loaded.

    The heavy imports (``torch``, ``pyannote.audio``) happen only inside
    :meth:`_pipeline`, so importing this module needs nothing but numpy/scipy.
    The pipeline is loaded once and cached; :meth:`segment` reuses it.
    """

    def __init__(self, model: str = DEFAULT_MODEL, device=None, hf_token=None):
        self.model = model
        self.device = device  # None -> auto (cuda > mps > cpu) at load time
        self.hf_token = hf_token
        self._pipeline_obj = None

    def _pick_device(self) -> str:
        """cuda > mps > cpu, resolved lazily so torch stays an optional dep."""
        import torch

        if torch.cuda.is_available():
            return "cuda"
        if torch.backends.mps.is_available():
            return "mps"
        return "cpu"

    def _pipeline(self):
        """Load (once) and return the pyannote Pipeline on the chosen device.

        Raises ``RuntimeError`` with a gated-model hint when
        ``from_pretrained`` returns ``None`` — pyannote's signal that the
        model is gated and the HF token is missing or hasn't accepted the
        model's licence.
        """
        if self._pipeline_obj is not None:
            return self._pipeline_obj

        import torch
        from pyannote.audio import Pipeline

        kwargs = {}
        if self.hf_token:
            kwargs["use_auth_token"] = self.hf_token
        pipeline = Pipeline.from_pretrained(self.model, **kwargs)
        if pipeline is None:
            raise RuntimeError(
                "Pipeline.from_pretrained returned None — the model is gated: "
                "accept the conditions at https://huggingface.co/%s and "
                "configure a valid Hugging Face token (hf_token)." % self.model
            )

        device = self.device or self._pick_device()
        if device != "cpu":
            pipeline.to(torch.device(device))
        self.device = device
        self._pipeline_obj = pipeline
        return pipeline

    def segment(self, audio_path, num_speakers=None) -> SegmentationOutput:
        """Run the pipeline on ``audio_path`` and return the local timeline.

        ``num_speakers`` is forwarded to pyannote only when it pins 2+
        speakers; a value below 2 (or ``None``) leaves the count automatic,
        since a "1 speaker" hint just disables diarization and pyannote's own
        estimate is what we want for the trivial-but-uncertain case.
        """
        pipeline = self._pipeline()
        kwargs = {}
        if num_speakers is not None and num_speakers >= 2:
            kwargs["num_speakers"] = int(num_speakers)
        result = pipeline(audio_path, **kwargs)
        segment_annotation, overlap_annotation = select_annotations(result)
        return annotation_to_output(segment_annotation, overlap_annotation)
