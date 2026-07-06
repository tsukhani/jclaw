"""Command-line entry point: ``python -m diarng.cli {diarize,eval,enroll,serve}``.

Thin argparse wrapper over the library. Each subcommand builds the relevant
objects and delegates:

  * ``diarize`` — run the full pipeline on one file, emit JSON / plain text /
    SRT.
  * ``eval``    — pooled DER over a reference vs hypothesis RTTM
    (:func:`diarng.evalkit.score`).
  * ``enroll``  — embed a voice sample and add it to a
    :class:`~diarng.identity.VoiceprintStore`.
  * ``serve``   — start the localhost daemon (:func:`diarng.serve.serve`).

Heavy backends load lazily inside the stages, so ``import diarng.cli`` (and
``--help``) stay dependency-free.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from diarng.pipeline import DiarizerConfig, result_to_dict


# --------------------------------------------------------------------------- #
# shared config
# --------------------------------------------------------------------------- #
def _add_config_args(parser: argparse.ArgumentParser) -> None:
    """Attach the DiarizerConfig-shaping flags shared by diarize/serve."""
    parser.add_argument(
        "--segmentation-model", default=DiarizerConfig.segmentation_model
    )
    parser.add_argument("--asr-model", default=DiarizerConfig.asr_model)
    parser.add_argument(
        "--embedding-model", default=None, help="sherpa WeSpeaker ONNX path"
    )
    parser.add_argument("--voiceprint-store", default=None)
    parser.add_argument("--language", default=None, help="ISO code, e.g. en")
    parser.add_argument("--device", default=None, help="cuda|mps|cpu (auto if unset)")
    parser.add_argument("--hf-token", default=os.environ.get("HF_TOKEN"))
    parser.add_argument("--refine", action="store_true", help="enable LLM refinement")
    parser.add_argument("--llm-base-url", default=None)
    parser.add_argument("--llm-model", default=None)
    parser.add_argument("--llm-api-key", default=os.environ.get("OPENAI_API_KEY"))


def _config_from_args(args: argparse.Namespace) -> DiarizerConfig:
    return DiarizerConfig(
        segmentation_model=args.segmentation_model,
        asr_model=args.asr_model,
        embedding_model_path=args.embedding_model,
        voiceprint_store_path=args.voiceprint_store,
        language=args.language,
        device=args.device,
        hf_token=args.hf_token,
        refine_enabled=args.refine,
        llm_base_url=args.llm_base_url,
        llm_model=args.llm_model,
        llm_api_key=args.llm_api_key,
    )


# --------------------------------------------------------------------------- #
# output formatting
# --------------------------------------------------------------------------- #
def _speaker_label(turn) -> str:
    """Human label for a turn: enrolled name, else Speaker N, else Unknown."""
    if turn.name:
        return turn.name
    if turn.speaker is None or turn.speaker < 0:
        return "Unknown"
    return f"Speaker {turn.speaker}"


def _to_txt(result) -> str:
    """One line per turn: ``[start - end] Label: text``."""
    lines = []
    for turn in result.turns:
        lines.append(
            f"[{turn.start:.2f} - {turn.end:.2f}] {_speaker_label(turn)}: {turn.text}"
        )
    return "\n".join(lines)


def _srt_timestamp(seconds: float) -> str:
    """Seconds -> ``HH:MM:SS,mmm`` SRT timecode."""
    if seconds < 0:
        seconds = 0.0
    millis = int(round(seconds * 1000))
    hours, millis = divmod(millis, 3_600_000)
    minutes, millis = divmod(millis, 60_000)
    secs, millis = divmod(millis, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d},{millis:03d}"


def _to_srt(result) -> str:
    """Standard SRT: numbered blocks, ``Label: text`` as the caption body."""
    blocks = []
    for index, turn in enumerate(result.turns, start=1):
        blocks.append(
            f"{index}\n"
            f"{_srt_timestamp(turn.start)} --> {_srt_timestamp(turn.end)}\n"
            f"{_speaker_label(turn)}: {turn.text}\n"
        )
    return "\n".join(blocks)


def _format_result(result, fmt: str) -> str:
    if fmt == "json":
        return json.dumps(result_to_dict(result), indent=2)
    if fmt == "srt":
        return _to_srt(result)
    return _to_txt(result)


# --------------------------------------------------------------------------- #
# subcommands
# --------------------------------------------------------------------------- #
def _cmd_diarize(args: argparse.Namespace) -> int:
    from diarng.pipeline import Diarizer

    diarizer = Diarizer(_config_from_args(args))
    result = diarizer.diarize(args.audio, num_speakers=args.num_speakers)
    text = _format_result(result, args.format)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    else:
        print(text)
    return 0


def _cmd_eval(args: argparse.Namespace) -> int:
    from diarng import evalkit

    scores = evalkit.score(args.ref, args.hyp, args.uem)
    print(json.dumps(scores, indent=2))
    return 0


def _cmd_enroll(args: argparse.Namespace) -> int:
    from diarng import audio
    from diarng.embeddings import SherpaWeSpeakerEmbedder
    from diarng.identity import VoiceprintStore

    if not args.embedding_model:
        print("enroll requires --embedding-model", file=sys.stderr)
        return 2

    pcm = audio.load_pcm(args.wav)
    embedder = SherpaWeSpeakerEmbedder(args.embedding_model)
    # One clip -> one whole-clip embedding row.
    matrix = embedder.embed_batch([pcm])
    if matrix.shape[0] == 0:
        print("no embedding produced (empty/silent clip?)", file=sys.stderr)
        return 1
    store = VoiceprintStore(args.store)
    store.enroll(args.name, matrix[0])
    print(f"enrolled {args.name!r} into {args.store} ({len(store.names())} known)")
    return 0


def _cmd_serve(args: argparse.Namespace) -> int:
    from diarng import serve

    serve.serve(
        _config_from_args(args),
        host=args.host,
        port=args.port,
        idle_timeout_min=args.idle_timeout_min,
    )
    return 0


# --------------------------------------------------------------------------- #
# parser
# --------------------------------------------------------------------------- #
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="diarng", description="diarize-ng command line"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # diarize
    p_diar = sub.add_parser("diarize", help="diarize + transcribe one audio file")
    p_diar.add_argument("audio", help="path to any ffmpeg-decodable audio file")
    p_diar.add_argument("--num-speakers", type=int, default=None)
    p_diar.add_argument(
        "--format", choices=["json", "txt", "srt"], default="txt"
    )
    p_diar.add_argument("--output", default=None, help="write here instead of stdout")
    _add_config_args(p_diar)
    p_diar.set_defaults(func=_cmd_diarize)

    # eval
    p_eval = sub.add_parser("eval", help="pooled DER of a hypothesis vs reference RTTM")
    p_eval.add_argument("--ref", required=True, help="reference RTTM")
    p_eval.add_argument("--hyp", required=True, help="hypothesis RTTM")
    p_eval.add_argument("--uem", default=None, help="optional UEM scoring map")
    p_eval.set_defaults(func=_cmd_eval)

    # enroll
    p_enr = sub.add_parser("enroll", help="add a voice sample to a voiceprint store")
    p_enr.add_argument("name", help="display name to enroll")
    p_enr.add_argument("wav", help="audio sample of that single speaker")
    p_enr.add_argument("--store", required=True, help="voiceprint store JSON path")
    p_enr.add_argument(
        "--embedding-model", required=True, help="sherpa WeSpeaker ONNX path"
    )
    p_enr.set_defaults(func=_cmd_enroll)

    # serve
    p_srv = sub.add_parser("serve", help="run the localhost diarization daemon")
    p_srv.add_argument("--host", default="127.0.0.1")
    p_srv.add_argument("--port", type=int, default=9531)
    p_srv.add_argument("--idle-timeout-min", type=float, default=15.0)
    _add_config_args(p_srv)
    p_srv.set_defaults(func=_cmd_serve)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
