"""Word-timestamped ASR for diarize-ng.

This stage turns an audio file into a flat list of :class:`~diarng.types.Word`,
each carrying the timing and the confidence signals (per-word ``probability``
and the parent segment's ``no_speech_prob``) that the downstream hallucination
gate and word->speaker assignment depend on.

Two backends, both satisfying the :class:`Transcriber` protocol:

  * :class:`FasterWhisperTranscriber` -- faster-whisper (CTranslate2), the
    default. Word timestamps come from Whisper's cross-attention alignment.
  * :class:`ParakeetTranscriber` -- NVIDIA NeMo Parakeet-TDT, optional and
    best-effort (NeMo is not in this repo's extras yet).

Heavy backends are imported lazily inside the class that needs them, so
``import diarng.asr`` costs only numpy/scipy.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from diarng.types import Word


@runtime_checkable
class Transcriber(Protocol):
    """A backend that turns an audio file into timed, confidence-tagged words.

    Runtime-checkable so callers (and tests) can assert an object is a
    transcriber by the presence of ``transcribe``; the protocol only fixes
    the shape, not the model behind it.
    """

    def transcribe(self, audio_path: str, language: str | None = None) -> list[Word]:
        """Transcribe ``audio_path`` into words. ``language`` is an ISO code
        (e.g. ``"en"``) or ``None`` to let the backend auto-detect."""
        ...


class FasterWhisperTranscriber:
    """faster-whisper (CTranslate2) word-timestamped ASR -- the default backend.

    faster-whisper reimplements Whisper on CTranslate2, so transcript quality
    matches OpenAI Whisper while running several times faster. Word timestamps
    are obtained from Whisper's cross-attention (``word_timestamps=True``), and
    each word inherits its parent segment's ``no_speech_prob`` so the
    hallucination gate can reason about silence-driven decoder loops.
    """

    def __init__(
        self,
        model: str = "large-v3",
        device: str = "auto",
        compute_type: str = "auto",
    ) -> None:
        self.model_name = model
        self.device = device
        self.compute_type = compute_type
        self._cached_model = None

    def _model(self):
        """Build and cache the CTranslate2 Whisper model on first use.

        The ``faster_whisper`` import lives here, not at module top, so that
        ``import diarng.asr`` never pulls the CTranslate2 native runtime.
        CTranslate2 itself resolves ``device="auto"`` (cuda when present, else
        cpu) and ``compute_type="auto"`` (fastest type valid for that device),
        so the constructor strings pass straight through -- no manual
        cuda/cpu probing needed.
        """
        if self._cached_model is None:
            from faster_whisper import WhisperModel

            self._cached_model = WhisperModel(
                self.model_name,
                device=self.device,
                compute_type=self.compute_type,
            )
        return self._cached_model

    def transcribe(self, audio_path: str, language: str | None = None) -> list[Word]:
        """Transcribe with word timestamps, VAD pre-filtering and the
        anti-hallucination decode setting; return a flat ``list[Word]``."""
        model = self._model()
        segments, _info = model.transcribe(
            audio_path,
            language=language,
            word_timestamps=True,
            # VAD pre-filter drops non-speech regions before decoding, which
            # is the first line of defence against Whisper inventing text over
            # silence or music.
            vad_filter=True,
            # The single most effective switch against Whisper's decoder-loop
            # hallucinations: with conditioning off, each window is decoded
            # without being primed by the previous window's text, so one
            # spurious phrase cannot snowball into a repeated loop that
            # poisons the rest of the file. (faster-whisper / openai-whisper
            # long-form guidance -- the standard anti-hallucination default.)
            condition_on_previous_text=False,
        )

        words: list[Word] = []
        for seg in segments:
            no_speech = seg.no_speech_prob
            # `seg.words` is populated only because word_timestamps=True; guard
            # against a None so a stray non-speech segment can't crash mapping.
            for w in seg.words or ():
                words.append(
                    Word(
                        start=float(w.start),
                        end=float(w.end),
                        text=w.word,
                        probability=(
                            float(w.probability) if w.probability is not None else None
                        ),
                        no_speech_prob=(
                            float(no_speech) if no_speech is not None else None
                        ),
                    )
                )
        return words


class ParakeetTranscriber:
    """NVIDIA NeMo Parakeet-TDT word-timestamped ASR (optional, best-effort).

    Parakeet-TDT's Token-and-Duration Transducer decoder emits *native token
    timestamps* as part of decoding, so there is no separate forced-alignment
    pass (unlike Whisper, whose word times are recovered from cross-attention).
    On English it reports lower WER than whisper-large at roughly an order of
    magnitude higher throughput (NVIDIA Parakeet-TDT-1.1b model card / HF Open
    ASR Leaderboard) -- which is why it is worth carrying as an alternative
    backend even though it is English-only.

    NeMo is intentionally *not* in diarize-ng's extras yet, so this backend is
    best-effort: construction is cheap (no import), and the first transcribe
    raises a clear :class:`ImportError` telling the operator to install
    ``nemo_toolkit[asr]``. Parakeet does not expose per-word probabilities or a
    no-speech probability, so those confidence fields are left ``None`` (which
    the hallucination gate treats as "keep").
    """

    def __init__(self, model: str = "nvidia/parakeet-tdt-1.1b") -> None:
        self.model_name = model
        self._cached_model = None

    def _model(self):
        """Load and cache the NeMo ASR model on first use.

        The ``nemo`` import is lazy and guarded: NeMo is not an installed
        extra, so a missing package surfaces as an actionable ImportError
        rather than an opaque ModuleNotFoundError from deep in the call stack.
        """
        try:
            import nemo.collections.asr as nemo_asr
        except ImportError as exc:
            raise ImportError(
                "ParakeetTranscriber requires NeMo, which is not part of "
                "diarize-ng's extras. Install it with: "
                "uv pip install 'nemo_toolkit[asr]'"
            ) from exc

        if self._cached_model is None:
            self._cached_model = nemo_asr.models.ASRModel.from_pretrained(
                self.model_name
            )
        return self._cached_model

    def transcribe(self, audio_path: str, language: str | None = None) -> list[Word]:
        """Transcribe via NeMo's high-level API with native timestamps.

        ``language`` is accepted for protocol parity but ignored: Parakeet-TDT
        -1.1b is English-only. ``timestamps=True`` attaches char/word/segment
        timestamps to each hypothesis; we read the word-level list, whose
        entries carry ``start``/``end`` in seconds plus the token ``word``.
        """
        model = self._model()
        hypotheses = model.transcribe([audio_path], timestamps=True)
        hyp = hypotheses[0]

        words: list[Word] = []
        for entry in hyp.timestamp["word"]:
            words.append(
                Word(
                    start=float(entry["start"]),
                    end=float(entry["end"]),
                    text=entry["word"],
                    # Transducer decoding gives no calibrated per-word
                    # probability or no-speech probability; leave both unset.
                    probability=None,
                    no_speech_prob=None,
                )
            )
        return words


def _contiguous_runs(words: list[Word]) -> list[list[Word]]:
    """Partition ``words`` into maximal contiguous runs of equal
    ``no_speech_prob``.

    Words emitted from the same Whisper segment all carry that segment's exact
    ``no_speech_prob`` float, so grouping adjacent words by an equal value
    reconstructs the original segment boundaries from the flat word list --
    which is the natural unit for the hallucination gate to act on. Words that
    lack a ``no_speech_prob`` (``None``) group with their identical-``None``
    neighbours and are never judged (see :func:`hallucination_gate`).
    """
    runs: list[list[Word]] = []
    current: list[Word] = []
    for w in words:
        if current and w.no_speech_prob == current[-1].no_speech_prob:
            current.append(w)
        else:
            if current:
                runs.append(current)
            current = [w]
    if current:
        runs.append(current)
    return runs


def _run_is_hallucination(
    run: list[Word], max_no_speech: float, min_avg_prob: float
) -> bool:
    """True when a whole run looks like a silence-driven hallucination.

    A run qualifies only when it is fully judgeable -- every word carries both
    confidence signals -- AND its ``no_speech_prob`` strictly exceeds
    ``max_no_speech`` AND its mean word ``probability`` is strictly below
    ``min_avg_prob``. Requiring every word to have a ``probability`` is how the
    "never drop words lacking confidence data" guarantee is enforced at the
    run level: a single confidence-less word makes its run unjudgeable, so the
    run is kept intact.
    """
    nsp = run[0].no_speech_prob  # equal for every word in the run by construction
    if nsp is None:
        return False
    if any(w.probability is None for w in run):
        return False
    if nsp <= max_no_speech:
        return False
    mean_prob = sum(w.probability for w in run) / len(run)
    return mean_prob < min_avg_prob


def hallucination_gate(
    words: list[Word],
    max_no_speech: float = 0.6,
    min_avg_prob: float = 0.2,
) -> list[Word]:
    """Drop contiguous runs of words that look like Whisper hallucinations.

    Whisper hallucinations are typically whole spurious phrases decoded over
    silence: a run of words sharing a high segment ``no_speech_prob`` while
    each word is decoded with low ``probability``. This gate reconstructs those
    runs (adjacent words with equal parent ``no_speech_prob``) and drops a run
    only when *both*:

      * the run's ``no_speech_prob`` exceeds ``max_no_speech`` (the segment
        was probably silence), and
      * the run's mean word ``probability`` is below ``min_avg_prob`` (the
        decoder was not confident).

    Both conditions are required, so confident speech over a noisy segment and
    uncertain-but-real speech both survive. Thresholds are strict (``>`` and
    ``<``), so a value exactly on the boundary is kept.

    Words lacking confidence data are never dropped: a run with no
    ``no_speech_prob`` is never judged, and a run containing any word without a
    ``probability`` is kept whole. The function is pure -- input words are
    returned by reference, in order, with no mutation.
    """
    kept: list[Word] = []
    for run in _contiguous_runs(words):
        if _run_is_hallucination(run, max_no_speech, min_avg_prob):
            continue
        kept.extend(run)
    return kept
