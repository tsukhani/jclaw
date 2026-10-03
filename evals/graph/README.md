# Graph-extraction cases (JCLAW-1356)

`cases.json` is the labelled set that certifies a local Ollama decision model for graph
extraction (`POST /api/graph/spike`, `./jclaw.sh graphspike run`). It is not an
`evals/suites/` dataset: those are offline and agent-turn shaped, while this one needs a
live decision provider. `GUIDE.md` is how to label; this file is the format and the scoring.

Only local Ollama models are measured, through `Decider.ollama`; the default list is the
models selected in Settings. A hosted model such as `jev-latest` is refused with a 400.

## Format

```json
{"cases": [
  {"id": "c042", "tags": ["role", "employer-tool"],
   "text": "The user is a data engineer at Harborlight Analytics, which runs Kestrel CI, the team's deployment platform, every Tuesday.",
   "entities": [{"id": "operator", "mention": "The user", "type": "Person"},
                {"id": "harborlight", "mention": "Harborlight Analytics", "type": "Organization"},
                {"id": "kestrel-ci", "mention": "Kestrel CI", "type": "System",
                 "aliases": ["the team's deployment platform"]}],
   "relations": [{"from": "operator", "type": "works_at", "to": "harborlight"},
                 {"from": "harborlight", "type": "uses", "to": "kestrel-ci"}],
   "negatives": ["data engineer", "Tuesday"]}
]}
```

- `id` is unique. `text` is stored verbatim as one memory of the requested agent for the run,
  snapshotted, checked and deleted afterwards.
- Every case has exactly one `operator` entity. A text that never says "the user" opens with
  a subjectless verb ("Prefers ...") and its operator is `{"id": "operator", "type": "Person",
  "implicit": true}`, with no mention.
- Every `mention` and alias appears verbatim in `text`. Its `type` is one of the seed
  ontology's term types (`conf/ontology/seed-schema.yaml`), and an entity id keeps one type
  across every case it appears in.
- A relation's `from` and `to` are entity ids of the same case, and the schema allows its
  `type` between their types. At most one relation per ordered pair.
- `negatives` are spans that must not become terms; none may be a labelled mention or alias.
- An entity or relation that is true but not worth a graph record carries `"noise": true`.
- `tags` are from `weekday-time`, `role`, `everyday-object`, `descriptive-phrase`,
  `reversed-direction`, `employer-tool` (the hard negatives) and `plain`.

`services.graphspike.GraphCases` refuses a set that breaks any of these, naming the case.
`GraphCasesConformanceTest` fails the build on a refusal or on a missed composition target:
at least 120 cases, at least 85% beginning "The user" and the rest subjectless, a mean
length of 17-23 words, at least 12 cases per hard-negative tag and at least half carrying
one, at least 420 non-noise gold records, every term type and relation used, and entity ids
recurring across cases.

## Pipeline

Decision-only: code finds every candidate and the decision model only chooses
(`CandidateGenerator`, `ExtractionPipeline`).

1. **Candidates**, all spans of the memory: the operator, the agent's known Term names
   (matched case-insensitively on word boundaries; a Term has no aliases), capitalized runs
   split at time words (weekdays, months, today, morning, weekend and the like, which never
   stand as candidates), URLs, file paths, ticket keys, the object of a stated preference,
   and the Topic frames "thinks that X" and "X is a kind of Y".
2. **Operator.** The operator's Person is written by rule at confidence 1 and never asked.
3. **Overlap.** Each set of candidates whose spans overlap gets one choice over its spans
   plus `neither`; only the chosen span is typed, and `neither` or a failure types none.
4. **Typing.** Each surviving candidate gets one choice over the term types plus
   `not_an_entity`.
5. **Relations.** For every ordered pair of typed terms and every relation the schema allows
   between them, one `noul` yes/no question: does the memory state the relation's sentence
   ("X is a kind of Y", "X is a family member of Y", ...)? The false criterion names the
   near-misses: the two only co-occur, share a topic, are related the other way round, or the
   relation is an inference. Per unordered pair only the highest-yes relation and direction
   is kept, so a pair never holds two relations or one written both ways.

A decision's confidence is the chosen option's probability, or the kept relation's yes
probability. A term chosen from an overlap set also needs that choice to reach the
threshold, and a relation needs both endpoints to: that requirement is the decision's
`floor`. The pipeline applies no threshold; `Records.at(run, t)` sorts every decision into
written, abstained (a choice below t or its floor), declined (`not_an_entity`, `neither`) or
failed.

Questions are packed greedily into `/v1/systemone` requests that fit the model's context
(`DecisionContext`: tev1 2050, nimble 8194, clef-flash 16384 tokens, any other model 2050;
the cl100k estimate is inflated by 1.25 since it is not the models' tokenizer), and are
never truncated. A request refused with HTTP 400 is split in half and retried; a question
that cannot fit alone fails with `exceeds context`.

Mentions are clustered by `ExactMatchResolver` (case-folded, a leading "the" and a
possessive stripped).

## Scoring

**Stages**, each with gold swapped in for the stages before it, so a stage's score is its own:

- candidate recall: gold mentions (or an alias) among the generated candidates;
- overlap: overlap sets settled on a gold span, or on `neither` when no span is gold;
- typing: gold spans typed as labelled; rejection: negatives answered `not_an_entity`;
- relation: labelled pairs whose kept relation is the label, in its direction, at yes of at
  least 0.5; no-relation: unlabelled pairs whose kept relation is below 0.5;
- resolution: B-cubed and pairwise precision and recall, and false merges, over gold mentions.

**End to end**, strictly. At a threshold t a decision is written only when its probability
and its floor are at least t; a relation also needs both endpoint terms written at t. The
operator's Person is written by rule, not decided, so it counts in neither written nor gold;
the grid's `ruleWritten` reports how many were left out, and relations to the operator
still count. Each written record is right, noise or wrong:

- **match** — the span is no labelled mention or alias (a partial span is wrong);
- **type** — a matched span typed differently from its label;
- **duplicate** — a second record for an entity already written, such as its alias;
- **relation** — a relation whose triple is not labelled. `same_as` and `family_of` match
  either direction, and written both ways are one record.

A record labelled noise counts in neither the wrong count nor the denominator:

- wrong share = wrong / (written - noise)
- recall = right / non-noise gold records

The grid scores every threshold from 0.95 to 0.50 by 0.05.

## Certification

At each threshold the one-sided 95% Clopper-Pearson upper bound on the wrong share must be at
most 5%: with no wrong record that takes 59 written, with one 93, two 124, three 153, five
208. Rule-written operator terms are in neither count. Recall must also meet the floor
(default 0.50). The walk runs from 0.95 down and stops
at the first threshold that fails; the model certifies at the lowest threshold reached. So a
model whose recall at 0.95 is under the floor certifies at nothing, by design.

Certification needs two runs, the default; with `--runs 1` the report is information only and
reads `certification needs two runs`. The certified threshold is the higher of the runs, and a
run that certifies nowhere fails the model with `run N did not certify` (for run 2, `second run
did not certify`). Then, in order:

1. any case memory changed by the run → `not-certified`;
2. any record adjudicated `label-error` → `not-certified`, `labels need fixing`;
3. second labels missing or short of the blind subset → `pending-agreement`;
4. a wrong record at the certified threshold with no `wrong` verdict → `pending-adjudication`;
5. otherwise `certified`, with the noise rate at that threshold.

The report lists the wrong records at the certified threshold (at 0.50 when nothing
certified). A written set only grows as t falls, so adjudicating those covers every
threshold above it.

## Second labels and adjudications

`./jclaw.sh graphspike blind-sheet` writes `data/graph-eval/blind-sheet.json`: the ids and
text of the blind subset, ceil(15%) of the cases chosen by SHA-256 of `"jclaw-1356:" + id`.
A second labeller works from that sheet and `GUIDE.md` alone and commits
`evals/graph/second-labels.json` in the same format as `cases.json`. The report gives entity
F1, Cohen's kappa on matched entity types and relation F1.

`evals/graph/adjudications.json` records verdicts on the report's wrong records:

```json
[{"caseId": "c042", "record": "term:Kestrel:System", "verdict": "wrong", "note": "partial span"},
 {"caseId": "c017", "record": "rel:The user:uses:Fenwick", "verdict": "label-error", "note": "missing alias"}]
```

`record` is the report's stable key, `term:<span>:<type>` or `rel:<from>:<type>:<to>`.
`wrong` confirms the model erred; `label-error` says the label did, and blocks
certification until `cases.json` is fixed.

## Held-out set

The held-out set measures a model on real memories and runs only on the operator's Mac. Its
files live under `data/graph-eval/`, which is gitignored, and never leave that machine.

1. `./jclaw.sh graphspike heldout-sample --agent NAME --count 100 [--seed S]` reads that
   many of the agent's active memories, read-only, into `data/graph-eval/heldout.json`,
   each with `labelled: false` and its generated candidates. It refuses to overwrite an
   existing file.
2. Label each case in place under `GUIDE.md` (tags optional) and set `labelled: true`.
3. `./jclaw.sh graphspike run --agent NAME --set heldout` reads the memories where they live:
   nothing is stored, edited or deleted, and the report checks every row is unchanged and
   still present. Unlabelled cases are skipped and counted.

The held-out report carries aggregate counts only: no memory id, text, span or per-case
result. Its walk is information; only the committed set certifies.

## Synthetic only

The GitHub mirror is public. Every name, organization, place and file in `evals/graph/` is
invented; URLs use `example.com`. Never add a real person, company or customer detail, and
never copy text from a real memory.
