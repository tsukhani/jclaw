#!/usr/bin/env python3
# ===========================================================================
# RECIPE -- NOT EXECUTED IN CI / NOT PART OF THE HERMETIC TEST SUITE.
# A runnable-shaped LoRA/QLoRA SFT trainer for a DiarizationLM corrector.
# Requires a GPU, network model downloads, and the training data produced by
# prep_data.py. Nothing in diarize-ng's tests runs it.
# ===========================================================================
"""PEFT (LoRA) supervised fine-tune of a DiarizationLM speaker-correction LLM.

Trains an instruction-tuned causal LM to perform the DiarizationLM (Wang et
al., 2024, arXiv:2401.03506) "flip": given a hypothesis transcript serialized
as ``<speaker:K> words ... -->`` it emits the same words re-labelled with the
*reference* speakers. The paper fine-tuned PaLM 2; this recipe uses an open HF
causal LM with parameter-efficient LoRA so it fits commodity GPUs.

Input: the ``--out-pairs`` JSONL from ``prep_data.py`` -- one
``{"prompt", "completion"}`` object per line, where ``prompt`` ends with the
``" --> "`` suffix and ``completion`` is the corrected diarized text. Training
is *completion-only*: the prompt tokens are masked out of the loss (via TRL's
``DataCollatorForCompletionOnlyLM`` keyed on the ``" --> "`` response marker),
so the model is graded only on producing the corrected label stream, never on
reciting the prompt back.

What moves, and what does NOT
-----------------------------
DiarizationLM is a *word-level speaker-attribution* corrector. Fine-tuning it
improves **cpWER** and **WDER** (who-said-what) -- typically a 10-30% relative
cpWER reduction over the base cascade on meeting data in the paper. It does
**not** move the time-based **DER**: it never edits word timings or the speaker
timeline's boundaries, only which speaker id a word carries. If you need DER to
drop, fine-tune segmentation/embeddings instead (see ``../seg_finetune.py`` and
``../embed_finetune.md``). Order matters: run DiarizationLM *last*, on top of an
already domain-adapted cascade, so it corrects residual attribution errors
rather than fighting a weak base timeline.

Serving -> refine.py
--------------------
After training, merge or serve the LoRA adapter behind an OpenAI-compatible
endpoint (e.g. vLLM ``--enable-lora``). ``diarng.refine.LlmRefiner`` then calls
that endpoint. Caveat: the runtime refiner currently serializes turns as
``<spk:K>: text`` lines, whereas a model trained here expects the word-level
``<speaker:K> words --> `` format. To use a fine-tuned checkpoint you must
serialize in the training format (the ``prep_data.py`` serializer) rather than
refine.py's turn-line format -- keep the two in lockstep. The word-preserving
*verifier* in refine.py still applies and remains the safety net: any reply
that rewrites a word is discarded.

GPU expectations
----------------
* QLoRA (``--load-in-4bit``) on a 7-8B base: ~1x 24 GB GPU (RTX 4090 / A10G).
* bf16 LoRA on a 7-8B base: ~1x 40-80 GB GPU (A100 / H100).
* Meeting transcripts are long; ``--max-seq-len`` of 2-4k tokens dominates
  memory. ``prep_data.py --max-words`` bounds utterance length to keep chunks
  inside the context window.

All heavy imports (torch, transformers, peft, trl, datasets) are inside
``main`` so this file imports with the standard library alone.
"""

from __future__ import annotations

import argparse

# The response marker that separates prompt from completion. Must equal the
# PROMPT_SUFFIX prep_data.py appends, so the collator masks the prompt exactly.
RESPONSE_TEMPLATE = " --> "


def build_arg_parser() -> argparse.ArgumentParser:
    """Define the CLI. Kept separate so the args are documented in one place."""
    p = argparse.ArgumentParser(
        description="LoRA SFT of a DiarizationLM speaker-correction model.",
    )
    p.add_argument(
        "--train-file", required=True, help="JSONL from prep_data.py --out-pairs"
    )
    p.add_argument(
        "--eval-file", default=None, help="optional held-out JSONL for eval loss"
    )
    p.add_argument(
        "--base-model",
        default="meta-llama/Meta-Llama-3.1-8B-Instruct",
        help="HF causal LM to fine-tune",
    )
    p.add_argument("--output-dir", default="out/diarizationlm-lora")
    p.add_argument("--epochs", type=float, default=3.0)
    p.add_argument("--lr", type=float, default=1e-4)
    p.add_argument("--batch-size", type=int, default=1)
    p.add_argument("--grad-accum", type=int, default=16)
    p.add_argument("--max-seq-len", type=int, default=4096)
    p.add_argument("--lora-r", type=int, default=16)
    p.add_argument("--lora-alpha", type=int, default=32)
    p.add_argument("--lora-dropout", type=float, default=0.05)
    p.add_argument(
        "--load-in-4bit",
        action="store_true",
        help="QLoRA: 4-bit base weights to fit a single 24 GB GPU",
    )
    return p


def main() -> None:
    """Fine-tune the corrector with LoRA and save the adapter.

    Heavy deps are imported here (not at module top) so ``import peft_train``
    costs nothing and the file compiles without a GPU stack installed.
    """
    args = build_arg_parser().parse_args()

    import torch
    from datasets import load_dataset
    from peft import LoraConfig, prepare_model_for_kbit_training
    from transformers import (
        AutoModelForCausalLM,
        AutoTokenizer,
        BitsAndBytesConfig,
    )
    from trl import (
        DataCollatorForCompletionOnlyLM,
        SFTConfig,
        SFTTrainer,
    )

    # --- tokenizer ---------------------------------------------------------- #
    tokenizer = AutoTokenizer.from_pretrained(args.base_model)
    if tokenizer.pad_token is None:
        # Causal LMs often ship without a pad token; reuse EOS for padding.
        tokenizer.pad_token = tokenizer.eos_token

    # --- base model (optionally 4-bit for QLoRA) ---------------------------- #
    quant_config = None
    if args.load_in_4bit:
        quant_config = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_use_double_quant=True,
            bnb_4bit_compute_dtype=torch.bfloat16,
        )
    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        quantization_config=quant_config,
        torch_dtype=torch.bfloat16,
        device_map="auto",
    )
    if args.load_in_4bit:
        # Cast norms/embeddings and enable input grads for stable QLoRA.
        model = prepare_model_for_kbit_training(model)
    model.config.use_cache = False  # incompatible with gradient checkpointing

    # LoRA on the attention + MLP projections -- the standard target set for
    # Llama-family decoders; enough capacity for a label-rewrite task.
    lora_config = LoraConfig(
        r=args.lora_r,
        lora_alpha=args.lora_alpha,
        lora_dropout=args.lora_dropout,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=[
            "q_proj",
            "k_proj",
            "v_proj",
            "o_proj",
            "gate_proj",
            "up_proj",
            "down_proj",
        ],
    )

    # --- data --------------------------------------------------------------- #
    data_files = {"train": args.train_file}
    if args.eval_file:
        data_files["eval"] = args.eval_file
    dataset = load_dataset("json", data_files=data_files)

    def format_example(example: dict) -> str:
        """Concatenate prompt+completion; the collator masks the prompt half.

        The prompt already ends with RESPONSE_TEMPLATE (" --> "), so the joined
        string is exactly ``<hyp diarized text> --> <ref diarized text>``. EOS
        is appended so generation learns to stop.
        """
        return example["prompt"] + example["completion"] + tokenizer.eos_token

    # Completion-only loss: everything up to and including " --> " is masked,
    # so gradients flow only through the corrected label stream.
    collator = DataCollatorForCompletionOnlyLM(
        response_template=RESPONSE_TEMPLATE,
        tokenizer=tokenizer,
    )

    sft_config = SFTConfig(
        output_dir=args.output_dir,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=args.grad_accum,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_ratio=0.03,
        bf16=True,
        gradient_checkpointing=True,
        logging_steps=10,
        save_strategy="epoch",
        max_seq_length=args.max_seq_len,
        packing=False,  # never pack: it would merge two conversations' labels
    )

    trainer = SFTTrainer(
        model=model,
        args=sft_config,
        train_dataset=dataset["train"],
        eval_dataset=dataset.get("eval"),
        peft_config=lora_config,
        formatting_func=format_example,
        data_collator=collator,
    )

    trainer.train()
    trainer.save_model(args.output_dir)  # writes the LoRA adapter
    tokenizer.save_pretrained(args.output_dir)
    print(f"saved LoRA adapter -> {args.output_dir}")


if __name__ == "__main__":
    main()
