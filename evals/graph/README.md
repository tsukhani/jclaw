# Graph-extraction cases (JCLAW-1356, JCLAW-1358)

`cases.json` is the labelled set that certifies a local Ollama decision model for graph
extraction (`POST /api/graph/eval`, `./jclaw.sh grapheval run`). It is not an
`evals/suites/` dataset: those are offline and agent-turn shaped, while this one needs a
live decision provider. `GUIDE.md` is how to label; this file is the format and the scoring.

Only local Ollama models are measured, through `Decider.ollama`; the default list is the
models selected in Settings. A hosted model such as `jev-latest` is refused with a 400.

## Format

```json
{"userMd": "Name: Avery Lin",
 "cases": [
  {"id": "c042", "tags": ["role", "employer-tool"],
   "text": "Avery Lin is a data engineer at Harborlight Analytics, which runs Kestrel CI, the team's deployment platform, every Tuesday.",
   "entities": [{"id": "operator", "mention": "Avery Lin", "type": "Person"},
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
- `userMd` is a USER.md header declaring the owner, read by the same `WorkspaceFiles` parse
  the live owner name uses. The committed owner is the synthetic Avery Lin.
- Every case but a guest case has exactly one `operator` entity, mentioned by the declared
  name or, in a legacy case captured before the owner had a name, as "The user". A text that
  says neither opens with a subjectless verb ("Prefers ...") and its operator is
  `{"id": "operator", "type": "Person", "implicit": true}`, with no mention. When the set
  declares an owner, any other operator mention is refused.
- A `guest` case is about someone other than the owner: a named guest is an ordinary Person,
  "a guest" is a negative, and the case has no `operator` entity and no relation to one.
- Every `mention` and alias appears verbatim in `text`. Its `type` is one of the seed
  ontology's term types (`conf/ontology/seed-schema.yaml`), and an entity id keeps one type
  across every case it appears in.
- A relation's `from` and `to` are entity ids of the same case, and the schema allows its
  `type` between their types. At most one relation per ordered pair.
- `negatives` are spans that must not become terms; none may be a labelled mention or alias.
- An entity or relation that is true but not worth a graph record carries `"noise": true`.
- `tags` are from `weekday-time`, `role`, `everyday-object`, `descriptive-phrase`,
  `reversed-direction`, `employer-tool` (the hard negatives), `plain` and `guest`.

`services.grapheval.GraphCases` refuses a set that breaks the mention, type, relation,
negative, tag or operator-mention rules, naming the case; `GraphCasesConformanceTest` checks
the operator count and the guest rules, and fails the build on a refusal or on a missed
composition target: at least 120 cases, 12-17% beginning "The user", at least 60% beginning the
owner's name, every case owner-voiced (one of those two or subjectless) or `guest`, at least 6
guest cases with one opening "A guest", a mean length of 17-23 words, at least 12 cases per
hard-negative tag and at least half carrying one, at least 420 non-noise gold records, every
term type and relation used, and entity ids recurring across cases.

## Pipeline

Decision-only: code finds every candidate and the decision model only chooses
(`CandidateGenerator`, `ExtractionPipeline`).

1. **Candidates**, all spans of the memory: the operator, the agent's known Term names and
   the set's declared owner name
   (matched case-insensitively on word boundaries; a Term has no aliases), capitalized runs
   split at time words (weekdays, months, today, morning, weekend and the like, which never
   stand as candidates), URLs, file paths, ticket keys, the object of a stated preference,
   and the Topic frames "thinks that X" and "X is a kind of Y".
2. **Operator.** An implicit operator or "the user" is written by rule as a Person at
   confidence 1 and never asked. The owner's name is an ordinary candidate, decided and typed.
3. **Overlap.** Each set of candidates whose spans overlap gets one choice over its spans
   plus `neither`; only the chosen span is typed, and `neither` or a failure types none.
4. **Typing.** Each surviving candidate gets one choice over the term types plus
   `not_an_entity`.
5. **Relations.** For every ordered pair of typed terms and every relation the schema allows
   between them, one `noul` yes/no question: does the memory state the relation's gloss
   (`RelationType.reads`, "X works at Y")? The wording has four variants: timeable, timeless,
   `holds_view_on` and a guest turn. The false criterion names the near-misses: the two only
   co-occur, share a topic, are related the other way round (except for symmetric relations,
   asked once per pair), the relation is an inference or is about something else, or it is
   only planned, wished, guessed, possible, asked about, a belief, or reported by someone
   other than the owner. On a guest turn no pair with the owner or the operator is asked.
   Per unordered pair only the highest-yes relation and direction is kept, so a pair never
   holds two relations or one written both ways.
6. **Negation, tense, occurs.** In a memory with a negation cue, each pair is asked whether
   the memory denies a relation whose statuses include `denied`; each date with two readings
   (a year-less month) is asked `past` or `upcoming`, in the type request; each Event and
   date is asked whether the Event happens then. Negation and occurs ride the relate request.
7. **Qualify.** Each relation kept at 0.50 is asked its status (`holds`, `ended`, `denied`,
   `unstated`, as the schema allows) and, per date, which bound it marks (`from`, `to`,
   `during` or `neither`, as the date's kind allows), in a fourth request.
8. **Lineage.** Each predecessor the memory may supersede gets its own request, sent beside
   the overlap request: `restatement`, `update` or `correction`; an unwritten one reads as
   retracted.

`Statements.at` and `Lineage.at` turn those decisions into claims at a threshold. The pair
filter (`pairFilter`, off by default) asks only pairs inside one clause, split at
`. ; : ! ?` or a comma before but/while/whereas/although/though/because, or with the owner
as an endpoint.

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
possessive stripped). A Person whose surface normalizes to the declared owner name joins the
operator's cluster.

## Scoring

**Stages**, each with gold swapped in for the stages before it, so a stage's score is its own:

- candidate recall: gold mentions (or an alias) among the generated candidates;
- overlap: overlap sets settled on a gold span, or on `neither` when no span is gold;
- typing: gold spans typed as labelled; rejection: negatives answered `not_an_entity`;
- relation: labelled pairs whose kept relation is the label, in its direction, at yes of at
  least 0.5; no-relation: unlabelled pairs whose kept relation is below 0.5;
- resolution: B-cubed and pairwise precision and recall, and false merges, over gold mentions.

**End to end**, strictly. At a threshold t a decision is written only when its probability and
its floor are at least t; a relation also needs both endpoint terms written at t. An implicit
operator or "The user" is written by rule, not decided, so it counts in neither written nor
gold; the grid's `ruleWritten` reports how many were left out, and relations to the operator
still count. The owner named in the text is decided, so it counts in both. Each written record
is right, noise or wrong:

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
208. Rule-written operator terms (implicit or "The user") are in neither count; the
named owner is in both. Recall must also meet the floor
(default 0.50). The walk runs from 0.95 down and stops
at the first threshold that fails; the model certifies at the lowest threshold reached. So a
model whose recall at 0.95 is under the floor certifies at nothing, by design.

A run with any failed decision (a timeout, a request too large for the model's context)
certifies nowhere: what it would have written is unknown. Every report stamps `extraction: x@…`
beside `schema`: a hash of every question text, lexicon and temporal probe the pipeline
renders, so a wording change voids a certificate as a schema change does. A request that times out while the
model is still loading waits for the load and is sent again, three attempts in all; one that
times out on a loaded model is not, since it counts against the breaker the router's
classifier shares. Concurrency defaults to 1: a local Ollama answers one request at a time,
so a second in flight only waits behind the first, toward the timeout.

One run is the default. Local decision models answer the same question the same way, so a
second full run repeats the first; instead every tenth case, from the first, is asked again,
and every decision must come back identical. A difference refuses the model and says so;
certify it with `--runs 2`, where every run must certify and the certified threshold is the
higher of the runs (`run N did not certify`; for run 2, `second run did not certify`). Then, in
order:

1. any case memory changed by the run → `not-certified`;
2. any record adjudicated `label-error` → `not-certified`, `labels need fixing`;
3. second labels missing or short of the blind subset → `pending-agreement`;
4. a wrong record at the certified threshold with no `wrong` verdict → `pending-adjudication`;
5. otherwise `certified`, with the noise rate at that threshold.

The report lists the wrong records at the certified threshold (at 0.50 when nothing
certified). A written set only grows as t falls, so adjudicating those covers every
threshold above it.

The report's `schema` is the seed's fingerprint, `v<version>@<12 hex>`: a SHA-256 prefix over
each term type's name and covers text and each relation's name and endpoints, the parts of
the schema the questions are built from. A certificate holds only while the loaded seed has
that fingerprint; any edit to those parts means certifying again.

## Second labels and adjudications

`./jclaw.sh grapheval blind-sheet` writes `data/graph-eval/blind-sheet.json`: the ids and
text of the blind subset, ceil(15%) of the cases chosen by SHA-256 of `"jclaw-1356:" + id`,
and the set's `userMd`, which says whose name stands for the operator.
A second labeller works from that sheet and `GUIDE.md` alone and commits
`evals/graph/second-labels.json` in the same format as `cases.json`. The report gives entity
F1, Cohen's kappa on matched entity types and relation F1.

`evals/graph/adjudications.json` records verdicts on the report's wrong records:

```json
[{"caseId": "c042", "record": "term:Kestrel:System", "verdict": "wrong", "note": "partial span"},
 {"caseId": "c018", "record": "rel:Avery Lin:uses:Fenwick", "verdict": "label-error", "note": "missing alias"}]
```

`record` is the report's stable key, `term:<span>:<type>` or `rel:<from>:<type>:<to>`.
`wrong` confirms the model erred; `label-error` says the label did, and blocks
certification until `cases.json` is fixed.

## Held-out set

The held-out set measures a model on real memories and runs only on the operator's Mac. Its
files live under `data/graph-eval/`, which is gitignored, and never leave that machine.

1. `./jclaw.sh grapheval heldout-sample --agent NAME --count 100 [--seed S]` reads that
   many of the agent's active memories, read-only, into `data/graph-eval/heldout.json`,
   each with `labelled: false` and its generated candidates. It refuses to overwrite an
   existing file.
2. Label each case in place under `GUIDE.md` (tags optional) and set `labelled: true`.
3. `./jclaw.sh grapheval run --agent NAME --set heldout` reads the memories where they live:
   nothing is stored, edited or deleted, and the report checks every row is unchanged and
   still present. Unlabelled cases are skipped and counted. The owner is the one named in
   the USER.md of the agent the sampled memories belong to; label their mentions of that name
   as the operator. A file holding memories of two agents is refused.

The held-out report carries aggregate counts only: no memory id, text, span or per-case
result. Its walk is information; only the committed set certifies. Its progress lines and a
failure's message are held to the same rule.

## Progress

An accepted run answers newline-delimited JSON: `pass` and `heartbeat` events as they happen,
then the report as the last line. A refused request is still a plain 400. `pass` comes when a
model finishes one run over the set (`model`, `run`, `runs`, `done`, `cases`, `failed`
decisions, `seconds`); `heartbeat` names every pass under way every 30 seconds. A failure
after the first line arrives as an `error` event, and the command exits 1. The report never
sees any of it, so it still carries no timings.

## Synthetic only

The GitHub mirror is public. Every name, organization, place and file in `evals/graph/` is
invented; URLs use `example.com`. Never add a real person, company or customer detail, and
never copy text from a real memory.
