# Speaker-embedding fine-tune recipe (WeSpeaker / 3D-Speaker)

> **RECIPE — NOT EXECUTED IN CI.** This is a documented, runnable procedure, not
> a script in this repo. Speaker-embedding training is **repo-driven, not
> pip-driven**: WeSpeaker and 3D-Speaker ship their own training entrypoints,
> Kaldi-style data layout and YAML configs. You clone the toolkit, edit a
> config, and launch *its* `run.sh` / `train.py` — there is no clean Python API
> to wrap the way `seg_finetune.py` wraps pyannote. Hence a Markdown recipe.

## Why fine-tune the embedder, and what it moves

`diarng.clustering` groups short windows into speakers by cosine distance
between **speaker embeddings**, and `diarng.identity` matches those same
embeddings against enrolled voiceprints. Both are only as good as the embedding
model's ability to separate *these* speakers in *this* acoustic domain.

Fine-tuning the embedder moves:

- **DER — the speaker-confusion component.** Better-separated embeddings mean
  fewer windows clustered to the wrong speaker. (It does not move missed/false-
  alarm, which are set by segmentation, nor cpWER, which is downstream of ASR.)
- **Identity accuracy.** Tighter within-speaker / looser between-speaker cosine
  distributions widen the margin the `identity` stage's ambiguity gap relies
  on, so enrollment matching gets more reliable at a fixed threshold.

Because `diarng.embeddings.SherpaWeSpeakerEmbedder` reuses the production
JCLAW-565 WeSpeaker ONNX family, the cosine thresholds tuned there (~0.3
cluster / ~0.6 match) are the starting point. **Re-measure them after
fine-tuning** — the whole point is that the distance distribution shifts.

## Datasets

| Role | Corpora |
| --- | --- |
| Base pretraining (do **not** redo) | VoxCeleb2 dev (already in the released checkpoints) |
| Domain adaptation (the fine-tune) | AMI, AliMeeting, DIHARD III, MSDWILD, VoxConverse — cut to single-speaker windows from the reference RTTM |
| Augmentation | **MUSAN** (noise / music / babble) + **RIR** (simulated room reverb), the standard WeSpeaker/Kaldi augmentation; add **simulated LibriSpeech mixtures** for controlled multi-speaker/overlap variety |

Fine-tune from a released checkpoint on domain speakers rather than training
from scratch — you want the VoxCeleb2-scale speaker manifold preserved and only
the domain channel/room adapted.

## GPU expectations

- Fine-tune (from a pretrained checkpoint, a few epochs, ArcMargin warm start):
  **1–2× 24 GB GPUs**, hours.
- From scratch on VoxCeleb2 (**not** what this recipe does): 4–8× high-memory
  GPUs, days. Avoid unless you have a reason the released checkpoint can't meet.

---

## Path A — WeSpeaker (default; matches the sherpa-onnx ONNX backend)

WeSpeaker is the family behind `SherpaWeSpeakerEmbedder`, so its exported ONNX
drops straight into `diarng.embeddings` with no threshold surprises.

### 1. Clone and install

```bash
git clone https://github.com/wenet-e2e/wespeaker.git
cd wespeaker && pip install -r requirements.txt && pip install -e .
```

### 2. Prepare data (Kaldi-style)

Build, for the domain train set, the standard files WeSpeaker expects:

```
data/domain_train/
  wav.scp     # <utt-id> <path-to-16k-mono-wav>   (one single-speaker window each)
  utt2spk     # <utt-id> <speaker-id>
  spk2utt     # <speaker-id> <utt-id> <utt-id> ...
```

Cut single-speaker windows from each recording using its reference RTTM (the
same `diarng.evalkit.load_rttm` segments), resampled to 16 kHz mono to match
`types.SAMPLE_RATE`. Prepare MUSAN and RIR once as noise sources:

```bash
local/prepare_data.sh          # or hand-build wav.scp/utt2spk as above
tools/make_shard_list.py ...   # WeSpeaker shards for fast IO (optional)
```

### 3. Config (fine-tune from a pretrained checkpoint)

Start from a released `conf/*.yaml` (e.g. ResNet34 or the newer **ReDimNet**)
and override for a short domain fine-tune:

```yaml
# conf/domain_finetune.yaml  (excerpt — merge into the model's shipped config)
model: ReDimNet            # or ResNet34; must match --backend used in diarng
model_init: exp/pretrained/voxceleb2/avg_model.pt   # warm start, DON'T train from scratch
num_avg: 1

dataset_args:
  aug_prob: 0.6            # MUSAN + RIR on-the-fly augmentation
  speed_perturb: true
  fbank_args: { num_mel_bins: 80 }

margin_scheduler:          # ArcMargin: start near 0, anneal up so warm start is stable
  initial_margin: 0.0
  final_margin: 0.2
  increase_start_epoch: 1
  fix_start_epoch: 3

loss: arc_margin           # additive-angular-margin softmax (ArcFace)
optimizer: SGD
scheduler: ExponentialDecrease
lr: 0.001                  # small: fine-tune, not pretrain
num_epochs: 6
```

Keep the **margin annealed from ~0** on a warm start: a full ArcMargin from
epoch 0 destabilizes a pretrained embedding space.

### 4. Train

```bash
bash run.sh --stage 3 --stop_stage 5 --config conf/domain_finetune.yaml \
            --exp_dir exp/redimnet_domain
```

### 5. Export ONNX for `diarng.embeddings`

`SherpaWeSpeakerEmbedder` loads an ONNX model via `sherpa_onnx`. Export the
fine-tuned checkpoint to the sherpa-onnx WeSpeaker ONNX layout:

```bash
python wespeaker/bin/export_onnx.py \
    --config exp/redimnet_domain/config.yaml \
    --checkpoint exp/redimnet_domain/models/avg_model.pt \
    --output_model exp/redimnet_domain/domain_wespeaker.onnx
```

Then point the embedder at it:

```python
from diarng.embeddings import SherpaWeSpeakerEmbedder
embedder = SherpaWeSpeakerEmbedder("exp/redimnet_domain/domain_wespeaker.onnx")
```

Re-derive the cluster/match cosine thresholds on a dev set before shipping.

---

## Path B — 3D-Speaker (ERes2NetV2 / CAM++ alternative)

3D-Speaker (Alibaba DAMO) offers strong architectures (**ERes2NetV2**, **CAM++**)
and the same fine-tune shape. Use it when its checkpoints beat WeSpeaker on your
domain in the eval harness.

```bash
git clone https://github.com/modelscope/3D-Speaker.git
cd 3D-Speaker && pip install -r requirements.txt
# egs/voxceleb/sv-eres2netv2/ has run.sh + conf/*.yaml; mirror the fine-tune
# overrides from Path A: warm start from released ckpt, MUSAN+RIR aug, annealed
# ArcMargin, small LR, few epochs.
bash egs/voxceleb/sv-eres2netv2/run.sh --stage 3
```

3D-Speaker exports ONNX too (`speakerlab/bin/export_onnx.py`); the same
`SherpaWeSpeakerEmbedder(onnx_path)` seam consumes it **provided the ONNX
exposes the sherpa-onnx WeSpeaker extractor interface**. If it does not, add a
torch-backed embedder that satisfies the `diarng.embeddings.Embedder` protocol
(`embed_batch(list[np.ndarray]) -> (N, D)` unit-norm) and keep the ONNX export
for the default sherpa path. Do not modify `diarng/embeddings.py` as part of
*this* recipe — that is a code change, tracked separately.

---

## Validation loop (always)

1. Export ONNX; wire into `SherpaWeSpeakerEmbedder`.
2. Re-tune cluster/match cosine thresholds on the domain **dev** set.
3. Score DER on the domain **test** set with `diarng.evalkit` — confirm the
   **confusion** component dropped (missed/false-alarm should be ~flat; those
   belong to segmentation).
4. Re-check identity matching precision/recall at the new match threshold.

If confusion did not move, the embedder was not the bottleneck for this domain —
revisit segmentation (`seg_finetune.py`) before spending more on embeddings.
