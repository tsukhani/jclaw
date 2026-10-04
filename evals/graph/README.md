# Graph-extraction cases (JCLAW-1356, JCLAW-1358, JCLAW-1366)

`cases.json` is the labelled set that certifies a local Ollama decision model for graph
extraction (`POST /api/graph/eval`, `./jclaw.sh grapheval run`). It is not an
`evals/suites/` dataset: those are offline and agent-turn shaped, while this one needs a
live decision provider. `GUIDE.md` is how to label; this file is the format and the scoring.

Only local Ollama models are measured, through `Decider.ollama`; the default list is the
models selected in Settings. A hosted model such as `jev-latest` is refused with a 400.

## Format

The set is format v3. Every level is strict: a key not listed here is refused, at the root as
`graph cases: unknown key '<k>'` and below it as `case <id>: unknown key '<k>'`.

An illustrative case, not one in the committed set:

```json
{"userMd": "Name: Avery Lin", "capturedAt": "2026-02-15",
 "cases": [
  {"id": "c181", "tags": ["ended", "dated"], "capturedAt": "2026-10-03",
   "text": "Avery Lin moved to Ashgrove in 2019 from Port Calloway, where Avery Lin had rented a flat by the harbour.",
   "dates": [{"span": "2019", "value": "2019"}],
   "entities": [{"id": "operator", "mention": "Avery Lin", "type": "Person"},
                {"id": "ashgrove", "mention": "Ashgrove", "type": "Place"},
                {"id": "port-calloway", "mention": "Port Calloway", "type": "Place"}],
   "relations": [
     {"from": "operator", "type": "located_in", "to": "ashgrove", "status": "holds", "valid": "2019/.."},
     {"from": "operator", "type": "located_in", "to": "port-calloway", "status": "ended", "valid": "/2019"}],
   "negatives": []}
]}
```

- The root holds `userMd`, `capturedAt` (required: the anchor every case inherits) and `cases`,
  nothing else; a second-label file has the same root.
- `userMd` is a USER.md header declaring the owner, read by the same `WorkspaceFiles` parse
  the live owner name uses. The committed owner is the synthetic Avery Lin.
- A case holds `id`, `tags`, `text`, `entities`, `relations`, `negatives`, and optionally
  `capturedAt` (overrides the root's) and `dates`. `id` is unique. `text` is stored verbatim as
  one memory of the requested agent for the run, snapshotted, checked and deleted afterwards.
- `dates` lists `{span, value}`: `span` verbatim in the text, `value` an EDTF string, or `null`
  for a span the graph should not date (recurring, vague, or inside a file name, URL or
  ticket). A case without `dates` lists no date spans.
- Every case but a guest case has exactly one `operator` entity, mentioned by the declared
  name or, in a legacy case captured before the owner had a name, as "The user". A text that
  says neither opens with a subjectless verb ("Prefers ...") and its operator is
  `{"id": "operator", "type": "Person", "implicit": true}`, with no mention. When the set
  declares an owner, any other operator mention is refused.
- A `guest` case is about someone other than the owner: a named guest is an ordinary Person,
  "a guest" is a negative, and the case has no `operator` entity and no relation to one. A
  case also tagged `guest-about-owner` is a guest talking about the owner: it may name the
  operator, and every relation touching the operator is `unasserted`, since a guest's word is
  no assertion by the owner.
- An entity holds `id`, `mention`, `type`, `aliases`, `implicit`, `noise` and `occurs`. Every
  `mention` and alias appears verbatim in `text`. Its `type` is one of the seed ontology's
  term types (`conf/ontology/seed-schema.yaml`), and an entity id keeps one type across every
  case it appears in. `occurs` is only on an Event, only when the text asserts the date, and
  is an EDTF date or a closed interval.
- A relation holds `from`, `type`, `to`, `status`, `valid`, `valence` and `noise`. `from` and
  `to` are entity ids of the same case, and the schema allows `type` between their types.
  - `status` is required: `holds`, `ended` (it held and has stopped), `denied` (the text says
    it does not hold) or `unasserted` (planned, wished, guessed, asked about or reported by a
    guest: true or not, the owner did not assert it). `ended` is legal on any type; on one
    whose statuses exclude it, it scores as `unasserted`.
  - `valid` is the EDTF interval the relation holds over, on `holds` or `ended` where the
    schema allows valid time for the type and its `from` type. On `denied` it may only be the
    never form `../<capturedAt>`, the case's effective anchor; never on `unasserted`.
  - `valence` is `favorable` or `unfavorable`, only on `holds_view_on`, labelled from the
    text's meaning for that holder.
  - `noise` only with `holds` or `ended`.
- Pair rule: at most one positive (`holds`, `ended` or `unasserted`) and one `denied` relation
  per ordered pair; a symmetric relation's two directions are one pair. A positive `uses` and
  a denied `owns` on one pair is fine.
- EDTF is the subset `EdtfInterval.parse` accepts: `YYYY`, `YYYY-MM`, `YYYY-MM-DD`, a season
  `YYYY-21..24` or a quarter `YYYY-33..36`, each optionally ending `~`; an interval joins two
  with `/`, either end unknown (empty) or open (`..`), an open start only in the never form.
- `negatives` are spans that must not become terms; none may be a labelled mention or alias.
- An entity or relation that is true but not worth a graph record carries `"noise": true`.
- `tags` are from `weekday-time`, `role`, `everyday-object`, `descriptive-phrase`,
  `reversed-direction`, `employer-tool` (the hard negatives), `plain`, `guest`, and the v3 tags
  `ended`, `negated`, `unasserted`, `dated` (a stratum) and `guest-about-owner` (always beside
  `guest`). The v3 tags are not hard negatives yet, so the per-tag floor does not cover them.

Labels are checked for syntax and verbatim spans only, never against `TemporalExpressions` or
the valence lexicon: a date value the normalizer would not produce, or a valence the lexicon
would not give, parses, so finder, normalizer and valence errors count against the model.

The v2 set was migrated mechanically: the root `capturedAt` `2026-02-15` and `"status":
"holds"` on every relation. It has no `ended`, `denied` or `unasserted` label yet, so its trap
set is empty and it certifies nothing until JCLAW-1373 relabels it.

`services.grapheval.GraphCases` refuses a set that breaks the key, mention, type, relation,
status, qualifier, date, negative, tag or operator-mention rules, naming the case; `GraphCasesConformanceTest` checks
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
   other than the owner. On a guest turn no pair with the owner or the operator is asked; a
   committed case tagged `guest` runs as a guest turn, every other as the owner's.
   Per unordered pair only the highest-yes relation and direction is kept, so a pair never
   holds two relations or one written both ways.
6. **Negation, tense, occurs.** In a memory with a negation cue, each pair is asked whether
   the memory denies a relation whose statuses include `denied`; each date with two readings
   (a year-less month) is asked `past` or `upcoming`, in the type request; each Event and
   date is asked whether the Event happens then. Negation and occurs ride the relate request.
7. **Qualify.** Each relation kept at 0.50 whose statuses include `ended` is asked its
   status (`holds`, `ended` or `unstated`) and, per date, which bound it marks (`from`, `to`,
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
  least 0.5; no-relation: unlabelled pairs, and pairs whose only label is denied, unasserted or
  an `ended` the type does not admit, whose kept relation is below 0.5;
- resolution: B-cubed and pairwise precision and recall, and false merges, over gold mentions;
- dates (no model): date-finder recall over gold `dates` with a value, against
  `TemporalExpressions.find(text, capturedAt)`, and normalizer accuracy, the found spans with
  a reading equal to the label;
- tense: two-reading gold dates answered with the label's reading (`past` or `upcoming`);
- negation: the yes-rate on gold denied pairs and the no-rate on gold positives, asked only
  where the text has a negation cue;
- occurs: each gold Event and gold date answered yes exactly when the date is its `occurs`;
- status: gold relations whose type admits `ended`, answered with the gold status, or
  `unstated` for `unasserted` and `denied`; `unstated` on gold `holds` or admissible `ended` is
  the veto rate;
- slot: each relation allowing valid time and each gold date, answered `from`, `to`, `during`
  or `neither` by where the date sits in the gold `valid`.

**End to end**, strictly, from the claims `Statements.at(run, t, classes)` writes. At a
threshold t a decision is written only when its probability and its floor are at least t; a
relation also needs both endpoint terms written at t, and a status decision of `unstated`
leaves it unwritten. Only model decisions count: an implicit operator or "The user" is
written by rule, and so is `holds` on a relation that cannot end; both are in no count, class
or gate, and the grid's `ruleWritten` and `ruleStatus` report how many were left out.
Relations to the operator still count, and the owner named in the text is decided, so it
counts. The symmetric relations (`same_as`, `family_of`) come from the schema's symmetric
set: they match either direction, and written both ways are one record.

Five classes are scored. Qualifier values are scored only on a parent right at base, and are
compared as `EdtfInterval.parse(...).toString()` for dates and in lower case for valence.

| Class | Adjudication key | Right | Wrong kinds |
|---|---|---|---|
| base | `term:<span>:<type>`, `rel:<from>:<type>:<to>` | a term on a labelled span and type; a relation on gold `holds` or an admissible `ended` | `match`, `type`, `duplicate`, `relation`, `polarity`, `unasserted` |
| status | `status:<from>:<type>:<to>:<value>` | the gold status | `status` |
| time | `valid:<from>:<type>:<to>:<edtf>`, `occurs:<span>:<edtf>` | the gold value | `time` |
| negation | `neg:<from>:<type>:<to>` | gold `denied` on that triple | `negative` |
| valence | `valence:<from>:<to>:<value>` | the gold valence | `valence` |

- **match** — the span is no labelled mention or alias (a partial span is wrong);
- **type** — a matched span typed differently from its label;
- **duplicate** — a second record for an entity already written, such as its alias;
- a written relation is checked against the pair's positive label of that type first:
  right on `holds` or an admissible `ended`, **unasserted** on `unasserted` or an `ended` the
  type does not admit; then against the pair's denial of that type, **polarity**; otherwise
  **relation**. A positive `uses` and a denied `owns` on one pair score the same in either
  file order.
- **time** is a wrong value, a value where gold has none, or a date the finder or normalizer
  got wrong; **valence** likewise counts a value where gold has none. Status and `valid` come
  only from a status decision.
- **Incomplete:** a null qualifier where gold has a value (no status, a missing bound or
  `occurs`, no valence, a date the finder missed) is right for every wrong share and a miss
  for that class's recall.

A record labelled noise counts in neither the wrong count nor the denominator:

- wrong share = wrong / (written - noise)
- base recall = right / non-noise gold (entities, `holds` and admissible `ended` relations)

The grid scores every threshold from 0.95 to 0.50 by 0.05.

## Certification

Certification runs in two walks, both on the one-sided 95% Clopper-Pearson upper bound.

**Class walks** come first: status, then time with status at its walked threshold, then
negation. Their items are the qualifier values written on base-right parents in
`Statements.at(run, 0.50, classes)`, with the class under test set to each t. At t, n is
those values and k the wrong ones:

- n of at least 250 with a bound of at most 5% is `certified`;
- n of 29 to 249 with a bound of at most 10% and k of at most 6 is `provisional`
  (29 values with none wrong just pass; 28 are not evaluable);
- the walk starts at the highest evaluable t and goes down while the state holds; the class
  threshold is the lowest t of that run. No evaluable t, or a failure at the first, leaves the
  class `disabled`: it writes no value and no denial.

Across runs the highest class threshold wins and a class disabled in any run is disabled.
Valence is scored but not walked; the walk takes a fourth class when lineage joins it.

**The base walk** then scores the grid with those class thresholds (`Statements` applies the
higher of t and the class's own) and passes t when every gate holds:

- `G_written`: the bound on wrong / (written - noise) is at most 5%: with no wrong record that
  takes 59 written, with one 93, two 124, three 153, five 208. A term is wrong if its base or
  `occurs` is; a positive relation if its base or any written status, `valid` or valence is;
  a written denial unless gold is `denied`.
- `G_trap`: the bound on violations / trap gold is at most 5%. The trap set is every gold
  `ended`, `denied` and `unasserted` relation; a violation is an `ended` written as `holds`, an
  inadmissible `ended` written at all, a `denied` triple written positive, or an `unasserted`
  triple written at all. A null status on gold `ended` is none. An empty trap set bounds at
  1.0, so the committed set, which has none yet, certifies nothing.
- recall at least the floor (default 0.50), unchanged from v2.

The walk runs from 0.95 down and stops at the first threshold that fails; `t*` is the lowest
reached. So a model whose recall at 0.95 is under the floor certifies at nothing, by design.

Every model carries a `certificate`: `t*`, each class's threshold, state, n, k and bound, and
the `schema` and `extraction` stamps. The run itself checks neither: a consumer of the
certificate calls `Certifier.check`, which voids it when either stamp differs from the running
one (`schema stamp v3@… differs from running v3@…`).

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

The report lists the wrong records of the enabled classes at the certified configuration (at
0.50 when nothing certified), keyed as in the scoring table. A written set only grows as t falls, so adjudicating those covers every
threshold above it.

The report's `schema` is the seed's fingerprint, `v<version>@<12 hex>`: a SHA-256 prefix over
each term type's name and covers text and each relation's name and endpoints, the parts of
the schema the questions are built from. A certificate holds only while the loaded seed has
that fingerprint; any edit to those parts means certifying again.

## Report

Counts only, and identical on a rerun with the same answers. Per run, at the certified
configuration: questions per stage and per memory, the share of memories that sent the
qualify request, conflicts and the veto rate, each class's n, k, bound and state, status
coverage (gold timeable relations written with a status), date-finder recall, normalizer and
valence accuracy, the noise rate, the rule-written records left out, and, as information, the
base wrong share reweighted by stratum (`negated` 11.5%, `unasserted` 10.7%, `dated` 11%, the
rest 66.8%, each case in the first it carries; an empty stratum drops out and the rest are
rescaled), the time error and the valence error. Every report also carries cue recall, a
property of the labels rather than the run: the share of gold denials whose memory has a
negation cue.

## Second labels and adjudications

`./jclaw.sh grapheval blind-sheet` writes `data/graph-eval/blind-sheet.json`: the ids and
text of the blind subset, ceil(15%) of the cases chosen by SHA-256 of `"jclaw-1356:" + id`,
and the set's `userMd`, which says whose name stands for the operator.
A second labeller works from that sheet and `GUIDE.md` alone and commits
`evals/graph/second-labels.json` in the same format as `cases.json`. The sheet also carries the
root `capturedAt` and each case's, so dates resolve against the same anchor. The report gives
entity F1, Cohen's kappa on matched entity types, relation F1, Cohen's kappa on status over
matched triples, and exact agreement on `valid`, `occurs`, `valence` and `dates` over matched
items where either side has a value.

Second labels that predate v3 (the committed file is v2 until JCLAW-1378) fail the v3 parse.
That is no 400: the run completes with no second labels, the agreement carries the reason
`second labels predate v3: <message>`, and certification stops at `pending-agreement`. A file
of adjudications that does not parse is still a 400.

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
   each with `labelled: false`, its generated candidates, `capturedAt` (the memory's
   production anchor, an ISO date) and `authorType` (`human_turn`, `guest_turn`,
   `agent_synthesized`, `consolidation_derived`, or `unattributed` when the memory records
   none). It refuses to overwrite an existing file.
2. Label each case in place under `GUIDE.md` (tags optional) and set `labelled: true`. A
   held-out case is a case without `id` (cases are named h0, h1, ... by position) plus
   `memoryId`, `labelled`, `candidates`, `capturedAt` and `authorType`; the file's root holds
   `cases` only. A labelled case without `capturedAt` or a valid `authorType` was sampled
   before v3 and is refused (`case h<N>: sampled before v3 ...; move the file aside and
   resample`); a label error reads `case h<N>: labels break the v3 rules in
   evals/graph/README.md`, quoting no text.
3. `./jclaw.sh grapheval run --agent NAME --set heldout` reads the memories where they live:
   nothing is stored, edited or deleted, and the report checks every row is unchanged and
   still present. Unlabelled cases are skipped and counted. The owner is the one named in
   the USER.md of the agent the sampled memories belong to; label their mentions of that name
   as the operator. Each case runs with its own anchor and author type, `unattributed` read in
   the owner's voice. A file holding memories of two agents is refused.

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
