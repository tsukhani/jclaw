# diarize-ng training recipes

> **These are RECIPES — none of them run in CI.** They are runnable-shaped
> scripts written against the real training APIs (pyannote.audio + Lightning,
> WeSpeaker/3D-Speaker, transformers + PEFT + TRL), but the hermetic test suite
> never executes them: they need GPUs, gated model downloads, network access
> and multi-GB corpora. Treat this directory as *documentation you can run*
> once you have the hardware, data and tokens. Nothing here is imported by the
> `diarng` package; the heavy deps are all guarded behind `main()` so the
> scripts still `py_compile` and `import` with only the standard library.

## Build order (why the recipes exist in this sequence)

Fine-tuning is worthless without a way to measure it, and the stages must be
adapted in dependency order — a later stage assumes the earlier one is already
good. Follow this sequence:

```
0. EVAL HARNESS      diarng.evalkit          establish DER / cpWER / WDER first
        │                                    (no training — you cannot improve
        │                                     what you cannot measure)
        ▼
1. PRETRAINED CASCADE  diarng.pipeline       run the stock cascade end to end;
        │                                    record baseline metrics per domain
        ▼
2. DOMAIN FINE-TUNE   seg_finetune.py        adapt the acoustic front end to the
        │             embed_finetune.md      target domain  → moves DER
        ▼
3. DiarizationLM      diarizationlm/          correct residual who-said-what on
                      prep_data.py            top of the adapted cascade
                      peft_train.py           → moves cpWER / WDER (not DER)
```

Rationale for the ordering:

- **Eval first.** Every recipe below is justified only by a metric delta on a
  held-out set. `diarng.evalkit` (DER with a 0.25 s collar, cpWER, WDER) is the
  referee; wire it up and record baselines before touching a single weight.
- **Fine-tune the front end before the corrector.** DiarizationLM corrects
  *residual* attribution errors. Run it on a weak base timeline and it fights
  the acoustics instead of polishing them. Adapt segmentation and embeddings
  first so the transcript it sees is already close.
- **DiarizationLM last, and only for cpWER/WDER.** It never edits timings, so
  it cannot move DER. If DER is your problem, the lever is stage 2.

## Which stage moves which metric

| Recipe | Stage | Primary metric it moves | Leaves ~unchanged | Mechanism |
| --- | --- | --- | --- | --- |
| `seg_finetune.py` | segmentation | **DER** (missed, false-alarm, local confusion) | cpWER | sharper speech/overlap boundaries on the target domain |
| `embed_finetune.md` | embeddings | **DER** (speaker-confusion component) + identity accuracy | missed/false-alarm, cpWER | better speaker separation → fewer mis-clustered windows |
| `diarizationlm/*` | LLM refinement | **cpWER / WDER** (who-said-what) | **DER** | word-preserving speaker relabelling; no timing edits |

The split is the whole point: **timeline quality is an acoustic problem (DER,
stage 2); attribution polish is a language problem (cpWER, stage 3).** Reaching
for the wrong stage wastes GPU time.

## Datasets

| Corpus | Domain | Used by |
| --- | --- | --- |
| **AMI** (IHM / SDM) | meetings, headset + far-field | seg, embed, DiarizationLM |
| **DIHARD III** | hard multi-domain (clinical, web, restaurant…) | seg, embed |
| **VoxConverse** | in-the-wild conversational video | seg, embed |
| **MSDWILD** | multi-modal in-the-wild, dense speakers | seg, embed, DiarizationLM |
| **AliMeeting** | Mandarin far-field meetings, high overlap | seg, embed, DiarizationLM |
| **Simulated LibriSpeech mixtures** | controlled #speakers / overlap rate | seg, embed (augmentation) |
| **MUSAN + RIR** | noise/music/babble + room reverb | embed (on-the-fly augmentation) |

Notes:

- Stages 2–3 need **reference RTTM** for every file; DiarizationLM additionally
  needs a **reference STM** (transcript with reference speakers). Meeting
  corpora with real overlap (AMI, AliMeeting, MSDWILD) are where both fine-tunes
  pay off most.
- **Simulated LibriSpeech mixtures** (à la the pyannote/BUT simulation recipes)
  plus **MUSAN/RIR** augmentation give controlled overlap and channel variety —
  cheap robustness the real corpora can't cover exhaustively. They augment the
  real data; they do not replace it.

## GPU expectations

| Recipe | Minimum | Comfortable | Wall-clock (fine-tune) |
| --- | --- | --- | --- |
| `seg_finetune.py` | 1× 24 GB | 1× 24 GB | hours (small model, 10 s chunks) |
| `embed_finetune.md` (fine-tune from ckpt) | 1× 24 GB | 2× 24 GB | hours |
| `diarizationlm/peft_train.py` (QLoRA 7–8B) | 1× 24 GB | 1× 40–80 GB (bf16 LoRA) | hours |

None of these require from-scratch pretraining. Warm-start from released
checkpoints and adapt — from-scratch embedding pretraining (4–8× GPUs, days) is
explicitly out of scope.

## Running (once you have GPU + data + tokens)

```bash
# 0/1. Baseline: run the cascade, then score with the harness.
uv run --extra eval -m diarng.cli eval --ref gold.rttm --hyp baseline.rttm

# 2a. Segmentation fine-tune (needs a registered pyannote.database protocol
#     and a gated HF token for the base checkpoint).
uv run --extra seg python train/seg_finetune.py \
    --protocol AMI-SDM.SpeakerDiarization.only_words --hf-token "$HF_TOKEN"

# 2b. Embedding fine-tune — see train/embed_finetune.md (WeSpeaker/3D-Speaker,
#     repo-driven; export ONNX back into diarng.embeddings).

# 3a. DiarizationLM data prep: reference STM + hypothesis RTTM -> SFT pairs.
python train/diarizationlm/prep_data.py \
    --stm gold.stm --rttm baseline.rttm \
    --out-utterances data/utterances.json --out-pairs data/train.jsonl

# 3b. LoRA SFT of the corrector (QLoRA fits a single 24 GB GPU).
python train/diarizationlm/peft_train.py \
    --train-file data/train.jsonl --load-in-4bit \
    --output-dir out/diarizationlm-lora
```

The `--extra seg` / `--extra eval` groups are declared in `pyproject.toml`.
`peft_train.py` and the embedding recipe pull deps that are **not** repo extras
(transformers/peft/trl/datasets; wespeaker) — install them into the training
environment yourself; they are deliberately kept out of the inference package.

## Serving a fine-tuned corrector back into the pipeline

A trained DiarizationLM adapter is served behind an OpenAI-compatible endpoint
(e.g. vLLM with `--enable-lora`) and consumed by `diarng.refine.LlmRefiner`.
**Format caveat:** this recipe trains on the paper's *word-level* flip
(`<speaker:K> words … -->`), whereas the runtime refiner currently serializes
*turn* lines (`<spk:K>: text`). To use a fine-tuned checkpoint, serialize
prompts in the training format (the `prep_data.py` serializer), not refine.py's
turn-line format. Either way, refine.py's word-preserving **verifier** stays in
force: any reply that adds, drops or edits a word is discarded and the original
turns are kept — training can improve the corrector, it can never let it corrupt
the transcript.
