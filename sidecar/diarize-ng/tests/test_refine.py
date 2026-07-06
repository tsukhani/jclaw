"""Tests for DiarizationLM-style constrained refinement (refine.py).

The refiner is exercised through an injected fake transport (no network). We
verify that a pure relabel is applied word-exactly, and that every unsafe
outcome -- word rewrite, unknown speaker, malformed reply, transport error --
falls back to the original turns unchanged.
"""

from __future__ import annotations

import pytest

from diarng.assign import build_turns
from diarng.refine import LlmRefiner
from diarng.types import Word


def _words():
    return [
        Word(0.0, 1.0, "Hello", 0, probability=0.9),
        Word(1.0, 2.0, "there", 0),
        Word(2.0, 3.0, "Hi", 1),
        Word(3.0, 4.0, "friend", 1),
    ]


def _transport_returning(reply_text):
    def _t(payload):
        # Sanity-check the request shape the refiner sends.
        assert payload["messages"][0]["role"] == "system"
        assert payload["messages"][1]["content"].startswith("<spk:0>:")
        return {"choices": [{"message": {"content": reply_text}}]}

    return _t


def _refiner(reply_text):
    return LlmRefiner("http://x", "m", transport=_transport_returning(reply_text))


def test_accepts_pure_relabel_word_exactly():
    turns = build_turns(_words())
    reply = "<spk:0>: Hello\n<spk:1>: there Hi friend"
    out = _refiner(reply).refine(turns)

    assert [t.speaker for t in out] == [0, 1]
    assert out[0].text == "Hello"
    assert out[1].text == "there Hi friend"
    # The moved word keeps its original timing and text.
    moved = out[1].words[0]
    assert moved.text == "there"
    assert (moved.start, moved.end) == (1.0, 2.0)
    # Confidence fields survive the copy.
    assert out[0].words[0].probability == 0.9


def test_verifier_is_case_and_punctuation_insensitive():
    # Same words, different casing/punctuation, everything relabeled to spk 1.
    turns = build_turns(_words())
    reply = "<spk:1>: hello, there hi friend!"
    out = _refiner(reply).refine(turns)
    assert len(out) == 1
    assert out[0].speaker == 1
    # Rebuilt from the ORIGINAL word texts, so casing is preserved.
    assert out[0].text == "Hello there Hi friend"


def test_names_follow_the_label():
    turns = build_turns(_words(), names={0: "Alice", 1: "Bob"})
    reply = "<spk:0>: Hello\n<spk:1>: there Hi friend"
    out = _refiner(reply).refine(turns)
    assert out[0].name == "Alice"
    assert out[1].name == "Bob"


def test_rejects_word_rewrite():
    turns = build_turns(_words())
    reply = "<spk:0>: Hello everyone\n<spk:1>: Hi friend"  # 'there' -> 'everyone'
    out = _refiner(reply).refine(turns)
    assert out is turns  # unchanged original


def test_rejects_dropped_word():
    turns = build_turns(_words())
    reply = "<spk:0>: Hello\n<spk:1>: Hi friend"  # 'there' vanished
    out = _refiner(reply).refine(turns)
    assert out is turns


def test_rejects_speaker_set_violation():
    turns = build_turns(_words())
    reply = "<spk:0>: Hello there\n<spk:2>: Hi friend"  # speaker 2 not in {0,1}
    out = _refiner(reply).refine(turns)
    assert out is turns


def test_rejects_malformed_reply():
    turns = build_turns(_words())
    out = _refiner("this is not a labeled transcript").refine(turns)
    assert out is turns


def test_rejects_empty_reply():
    turns = build_turns(_words())
    out = _refiner("").refine(turns)
    assert out is turns


def test_transport_exception_returns_original():
    def boom(payload):
        raise RuntimeError("network down")

    turns = build_turns(_words())
    out = LlmRefiner("http://x", "m", transport=boom).refine(turns)
    assert out is turns


def test_malformed_response_schema_returns_original():
    # Transport returns a dict missing choices/message -> schema access raises,
    # caught and treated as a transport failure.
    def bad_schema(payload):
        return {"unexpected": True}

    turns = build_turns(_words())
    out = LlmRefiner("http://x", "m", transport=bad_schema).refine(turns)
    assert out is turns


def test_empty_turns_passthrough():
    r = _refiner("<spk:0>: anything")
    assert r.refine([]) == []


def test_url_is_chat_completions():
    r = LlmRefiner("http://host:9000/v1/", "m")
    assert r._url == "http://host:9000/v1/chat/completions"


def test_no_op_relabel_still_verifies_and_applies():
    # Identity reply (no change) must pass the verifier and round-trip.
    turns = build_turns(_words())
    reply = "<spk:0>: Hello there\n<spk:1>: Hi friend"
    out = _refiner(reply).refine(turns)
    assert [t.speaker for t in out] == [0, 1]
    assert out[0].text == "Hello there"
    assert out[1].text == "Hi friend"


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
