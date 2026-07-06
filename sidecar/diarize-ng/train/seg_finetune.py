#!/usr/bin/env python3
# ===========================================================================
# RECIPE -- NOT EXECUTED IN CI / NOT PART OF THE HERMETIC TEST SUITE.
# A runnable-shaped fine-tune of pyannote's powerset segmentation model.
# Requires a GPU, a gated HF checkpoint, and a registered pyannote.database
# protocol with audio + reference RTTM. Nothing in diarize-ng's tests runs it.
# ===========================================================================
"""Domain fine-tune of the pyannote 4 powerset local-segmentation model.

The segmentation stage (``diarng.segmentation.PowersetSegmenter``) runs a
single sliding-window powerset model (Plaquet & Bredin, pyannote 4) that emits,
per frame, *which subset* of speakers is active -- jointly modelling speech,
speaker change and overlapped speech. This is the component most sensitive to
acoustic domain (mic type, room, overlap rate), so it is the highest-leverage
thing to adapt when moving to a new domain (e.g. far-field meetings).

Powerset recap: with ``max_speakers_per_chunk=K`` and
``max_speakers_per_frame=P`` the model classifies each frame into one of the
``sum_{i=0..P} C(K, i)`` speaker subsets. segmentation-3.0 ships K=3, P=2
(<=2 simultaneous speakers per frame). Keep P matching your domain's real
overlap depth; raising it enlarges the class space and needs more overlap data.

What moves
----------
Fine-tuning segmentation moves **DER** -- specifically its missed-speech,
false-alarm and local-confusion components -- because it sharpens speech
boundaries and overlap detection on the target domain. It does *not* directly
touch cpWER (that is downstream of ASR + assignment). Expect the biggest DER
wins on far-field / high-overlap corpora (AMI SDM, AliMeeting) where the
generic checkpoint under-detects overlap. Clustering/embedding hyper-parameters
are unchanged here; re-tune the *pipeline* thresholds afterwards (see the note
at the end of ``main``) for the full domain gain.

Datasets (registered as pyannote.database protocols; each needs audio +
reference RTTM, and ideally a UEM):
    AMI-SDM.SpeakerDiarization.only_words, DIHARD III, VoxConverse, MSDWILD,
    AliMeeting. For robustness, train on a *mixture* protocol and/or augment
    with simulated conversations from LibriSpeech + MUSAN (noise/babble) + RIR
    (reverb) so the model sees controlled overlap and channel variety.

GPU expectations
----------------
The segmentation model is small (a few M params). 10 s chunks at batch_size 32
fit comfortably on a single 24 GB GPU; an A100 mainly speeds up epochs. A few
tens of epochs with early-stopping on validation DER is typical -- hours, not
days.

Heavy imports (torch, pyannote.audio, pyannote.database, lightning) live inside
``main`` so this file imports with the standard library alone.
"""

from __future__ import annotations

import argparse


def build_arg_parser() -> argparse.ArgumentParser:
    """Define the CLI in one place (documented defaults for the recipe)."""
    p = argparse.ArgumentParser(
        description="Fine-tune pyannote powerset segmentation on a domain.",
    )
    p.add_argument(
        "--protocol",
        default="AMI-SDM.SpeakerDiarization.only_words",
        help="registered pyannote.database protocol (audio + reference RTTM)",
    )
    p.add_argument(
        "--base-model",
        default="pyannote/segmentation-3.0",
        help="pretrained powerset segmentation checkpoint to adapt (gated)",
    )
    p.add_argument(
        "--hf-token",
        default=None,
        help="Hugging Face token with access to the gated base model",
    )
    p.add_argument("--duration", type=float, default=10.0, help="chunk length (s)")
    p.add_argument("--max-speakers-per-chunk", type=int, default=3)
    p.add_argument("--max-speakers-per-frame", type=int, default=2)
    p.add_argument("--batch-size", type=int, default=32)
    p.add_argument("--num-workers", type=int, default=8)
    p.add_argument("--lr", type=float, default=1e-4)
    p.add_argument("--epochs", type=int, default=20)
    p.add_argument(
        "--devices", type=int, default=1, help="number of GPUs for the Trainer"
    )
    p.add_argument("--output-dir", default="out/seg-finetune")
    return p


def main() -> None:
    """Adapt the powerset segmentation model to a domain protocol.

    Heavy deps are imported here so ``import seg_finetune`` needs nothing and
    the file compiles without torch/pyannote/lightning present.
    """
    from types import MethodType

    args = build_arg_parser().parse_args()

    import torch
    from lightning.pytorch import Trainer
    from lightning.pytorch.callbacks import EarlyStopping, ModelCheckpoint
    from pyannote.audio import Model
    from pyannote.audio.tasks import SpeakerDiarization
    from pyannote.database import FileFinder, registry

    # --- data: a registered protocol supplies train/dev/test with reference -- #
    # FileFinder resolves the {uri} audio paths declared in database.yml.
    protocol = registry.get_protocol(
        args.protocol, preprocessors={"audio": FileFinder()}
    )

    # --- start from the pretrained powerset checkpoint ---------------------- #
    # from_pretrained returns None on a gated model without a valid token.
    model = Model.from_pretrained(args.base_model, use_auth_token=args.hf_token)
    if model is None:
        raise RuntimeError(
            f"Model.from_pretrained returned None for {args.base_model!r}: "
            "accept the model conditions on Hugging Face and pass --hf-token."
        )

    # --- powerset segmentation task on the domain data ---------------------- #
    # max_speakers_per_frame>=1 selects powerset (multi-class) over multi-label;
    # keep it matched to the base checkpoint's cardinality unless you have the
    # overlap data to justify enlarging the class space.
    task = SpeakerDiarization(
        protocol,
        duration=args.duration,
        max_speakers_per_chunk=args.max_speakers_per_chunk,
        max_speakers_per_frame=args.max_speakers_per_frame,
        batch_size=args.batch_size,
        num_workers=args.num_workers,
    )

    # Attaching the task re-heads the model for this task's specifications and
    # puts it in "transfer learning" mode (pretrained body, adapted classifier).
    model.task = task

    # Fine-tune with a small constant LR; overriding configure_optimizers is the
    # pyannote-documented way to set the transfer-learning optimizer.
    def configure_optimizers(self):
        return torch.optim.Adam(self.parameters(), lr=args.lr)

    model.configure_optimizers = MethodType(configure_optimizers, model)

    # Checkpoint + early-stop on the task's monitored metric (validation DER).
    monitor, direction = task.val_monitor  # e.g. ("DiarizationErrorRate", "min")
    callbacks = [
        ModelCheckpoint(
            dirpath=args.output_dir,
            filename="{epoch}-{DiarizationErrorRate:.4f}",
            monitor=monitor,
            mode=direction,
            save_top_k=3,
        ),
        EarlyStopping(monitor=monitor, mode=direction, patience=5),
    ]

    trainer = Trainer(
        accelerator="gpu",
        devices=args.devices,
        max_epochs=args.epochs,
        gradient_clip_val=0.5,
        callbacks=callbacks,
    )
    trainer.fit(model)
    print(f"best checkpoints under {args.output_dir} (monitor={monitor})")

    # NEXT STEP (not automated here): plug the fine-tuned segmentation model
    # into the community-1 diarization Pipeline and re-optimize its clustering
    # thresholds on the domain dev set with pyannote.pipeline.Optimizer, then
    # point diarng.segmentation.DEFAULT_MODEL / PowersetSegmenter at the result.
    # Segmentation fine-tune alone lowers DER; retuned clustering captures the
    # rest of the domain gain.


if __name__ == "__main__":
    main()
