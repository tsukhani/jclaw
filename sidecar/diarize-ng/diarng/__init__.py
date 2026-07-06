"""diarize-ng: cascade+LLM hybrid speaker diarization stack.

Importing this package must never pull heavy deps (torch, pyannote, ...):
only `diarng.types` is re-exported eagerly. Stage modules import their
backends lazily inside the classes that need them.
"""

from diarng.types import (  # noqa: F401
    SAMPLE_RATE,
    DiarizationResult,
    Segment,
    Span,
    Turn,
    Word,
)
