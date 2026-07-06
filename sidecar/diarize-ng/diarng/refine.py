"""DiarizationLM-style constrained speaker-label refinement.

Implements the "prompt an LLM to re-diarize a transcript" idea from
DiarizationLM (Wang et al., Google, 2024, arXiv:2401.03506): the diarized
transcript is serialized as speaker-tagged lines, an instruction-tuned model
is asked to fix mislabeled turns using conversational semantics, and the
answer is parsed back into turns.

The paper's key safety property is *constrained transfer*: the model may only
move speaker labels, never edit words. We enforce it with an explicit
verifier -- the normalized word sequence of the reply must exactly equal the
input's, and every speaker in the reply must come from the input's speaker
set. On any violation (word rewrite, unknown speaker, malformed reply, or a
transport error) we discard the model's output and return the original turns
unchanged. Correction is therefore a pure relabeling or a no-op; it can never
corrupt the transcript.

The endpoint is any OpenAI-compatible `/chat/completions` server, reached
with stdlib `urllib` (no `requests` dependency). Tests inject a `transport`
callable in place of the network.
"""

from __future__ import annotations

import re

from diarng.assign import build_turns
from diarng.types import Turn, Word

# One serialized transcript line: "<spk:K>: some words". Speaker may be
# negative so a round-trip through an unattributed (-1) turn parses cleanly.
_LINE_RE = re.compile(r"^\s*<spk:(-?\d+)>:\s*(.*)$")

_SYSTEM_PROMPT = (
    "You are a speaker-diarization corrector (DiarizationLM, Wang et al., "
    "2024). You are given a transcript where each line is one speaker turn in "
    "the form '<spk:K>: text', with K an integer speaker id. Some turns are "
    "attributed to the wrong speaker. Using conversational semantics -- turn "
    "taking, questions and their answers, self-reference, addressee cues -- "
    "reassign words to the correct speaker.\n"
    "Hard constraints:\n"
    "1. Never add, delete, reorder, or rewrite any word. Reproduce every word "
    "exactly, in the same order.\n"
    "2. Only ever change which speaker a word is attributed to.\n"
    "3. Use only the speaker ids that already appear in the input; never "
    "invent a new speaker.\n"
    "4. Return the ENTIRE corrected transcript in the same '<spk:K>: text' "
    "line format and nothing else."
)


class LlmRefiner:
    """Post-corrects speaker labels via an OpenAI-compatible chat endpoint.

    Args:
        base_url: server root; '/chat/completions' is appended.
        model: model id passed through in the request body.
        api_key: optional bearer token.
        timeout: per-request timeout in seconds (urllib path only).
        transport: test seam -- a callable(payload: dict) -> response: dict
            substituted for the HTTP call. When None the real urllib POST is
            used. The response must follow the OpenAI chat schema
            (`choices[0].message.content`).
    """

    def __init__(
        self,
        base_url: str,
        model: str,
        api_key: str | None = None,
        timeout: float = 120,
        transport=None,
    ) -> None:
        self.base_url = base_url
        self.model = model
        self.api_key = api_key
        self.timeout = timeout
        self._transport = transport
        self._url = base_url.rstrip("/") + "/chat/completions"

    def refine(self, turns: list[Turn]) -> list[Turn]:
        """Return relabeled turns, or the original turns if refinement is unsafe.

        The output is guaranteed to contain exactly the input's words with
        their original timing; only speaker attribution (and the resulting
        turn grouping) may differ.
        """
        if not turns:
            return turns

        orig_words = [word for turn in turns for word in turn.words]
        if not orig_words:
            return turns

        input_speakers = {turn.speaker for turn in turns}
        # A speaker id denotes the same person regardless of which words land
        # on it, so display names follow the label, not the words.
        speaker_names: dict[int, str] = {}
        for turn in turns:
            if turn.name is not None:
                speaker_names.setdefault(turn.speaker, turn.name)

        # Per-word normalized token counts let us map reply tokens back onto
        # the original Word objects (and their timing) 1:1.
        input_tokens: list[str] = []
        word_token_counts: list[int] = []
        for word in orig_words:
            toks = _norm_tokens(word.text)
            word_token_counts.append(len(toks))
            input_tokens.extend(toks)

        try:
            reply_text = self._complete(_serialize(turns))
        except Exception:  # transport / decode / schema failure -> keep original.
            return turns

        parsed = _parse_reply(reply_text)
        if parsed is None:  # malformed reply.
            return turns

        # Flatten the reply into a token stream carrying a speaker per token,
        # rejecting any speaker id outside the input's set.
        reply_tokens: list[str] = []
        reply_token_speakers: list[int] = []
        for speaker, text in parsed:
            if speaker not in input_speakers:
                return turns
            for tok in _norm_tokens(text):
                reply_tokens.append(tok)
                reply_token_speakers.append(speaker)

        # Verifier: the word sequences must match exactly (constrained transfer).
        if reply_tokens != input_tokens:
            return turns

        # Re-attribute each original word from the aligned reply tokens. A word
        # that normalized to zero tokens (pure punctuation) carries the current
        # speaker forward so no word is ever dropped.
        new_words: list[Word] = []
        cursor = 0
        current_speaker = reply_token_speakers[0] if reply_token_speakers else None
        for word, count in zip(orig_words, word_token_counts):
            if count > 0:
                current_speaker = reply_token_speakers[cursor]
                cursor += count
            speaker = current_speaker if current_speaker is not None else word.speaker
            new_words.append(_with_speaker(word, speaker))

        return build_turns(new_words, names=speaker_names)

    def _complete(self, serialized: str) -> str:
        """Send the chat request and return the assistant message content."""
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": serialized},
            ],
            "temperature": 0,
        }
        response = (
            self._transport(payload)
            if self._transport is not None
            else self._http_post(payload)
        )
        return response["choices"][0]["message"]["content"]

    def _http_post(self, payload: dict) -> dict:
        """POST `payload` to the chat endpoint with stdlib urllib."""
        import json
        import urllib.request

        data = json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        request = urllib.request.Request(
            self._url, data=data, headers=headers, method="POST"
        )
        with urllib.request.urlopen(request, timeout=self.timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))


def _serialize(turns: list[Turn]) -> str:
    """Render turns as one '<spk:K>: text' line each."""
    return "\n".join(f"<spk:{turn.speaker}>: {turn.text}" for turn in turns)


def _parse_reply(text: str) -> list[tuple[int, str]] | None:
    """Parse '<spk:K>: text' lines; return None if any non-blank line fails."""
    parsed: list[tuple[int, str]] = []
    for line in text.splitlines():
        if not line.strip():
            continue
        match = _LINE_RE.match(line)
        if match is None:
            return None
        parsed.append((int(match.group(1)), match.group(2)))
    return parsed or None


def _norm_tokens(text: str) -> list[str]:
    """Lowercase, punctuation-stripped word tokens used by the verifier.

    Keeps only alphanumeric characters within each whitespace-delimited token
    and drops tokens that empty out, so the comparison ignores casing and
    punctuation while preserving the word sequence.
    """
    tokens: list[str] = []
    for raw in text.split():
        cleaned = "".join(ch for ch in raw.lower() if ch.isalnum())
        if cleaned:
            tokens.append(cleaned)
    return tokens


def _with_speaker(word: Word, speaker: int | None) -> Word:
    """Copy `word` with a new speaker, preserving its timing and confidences."""
    return Word(
        start=word.start,
        end=word.end,
        text=word.text,
        speaker=speaker,
        probability=word.probability,
        no_speech_prob=word.no_speech_prob,
    )
