# Graph-extraction cases (JCLAW-1356, JCLAW-1358, JCLAW-1366, JCLAW-1367, JCLAW-1379)

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

The set is relabelled to GUIDE v3 (JCLAW-1373).

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

1. **Candidates**, all spans of the memory, each recording every source that proposed it: the
   operator, every known Term's name and aliases and the set's declared owner name (matched
   case-insensitively on word boundaries), capitalized runs split at time words (weekdays,
   months, today, morning, weekend and the like, which never stand as candidates), URLs, file
   paths, ticket keys, email addresses, @handles, phone numbers (never a date, year range, IP
   address, version, clock time, an amount with commas or digits inside a literal), the
   object of a stated preference, the Topic frames "thinks that X" and "X is a kind of Y",
   and the owner-possessive kin frame ("Avery Lin's son", "The user's younger sister"; GUIDE
   rule 2a) unless a capitalized name stands in apposition (right after the kin word, or set
   off by a comma and closing its clause), and never for a plural or a pronoun. A kin
   candidate does not overlap its own possessor, so both are typed.
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

Mentions are clustered by `ExactMatchResolver` on the canonical key (lower case, a leading
"the", "a" or "an" and a possessive stripped, punctuation removed, a Topic's last token made
singular), or on the identifier key for a URL, path or email of any type, or a ticket key typed
`Artifact` (`SHA-256`, `UTF-8` and `GPT-4` share a ticket key's shape). A Person whose
surface normalizes to the declared owner name joins the operator's cluster.

## Scoring

**Stages**, each with gold swapped in for the stages before it, so a stage's score is its own:

- candidate recall: gold mentions (or an alias) among the generated candidates;
- coverage (no model): candidate recall per source, before (the sources preceding JCLAW-1372,
  named explicitly: no email, handle, phone or kin) and after, and the candidates per memory
  with the overlap and typing questions they imply, before and after;
- overlap: overlap sets settled on a gold span, or on `neither` when no span is gold;
- typing: gold spans typed as labelled; rejection: negatives answered `not_an_entity`;
- relation: labelled pairs whose kept relation is the label, in its direction, at yes of at
  least 0.5; no-relation: unlabelled pairs, and pairs whose only label is denied, unasserted or
  an `ended` the type does not admit, whose kept relation is below 0.5;
- resolution: B-cubed and pairwise precision and recall over gold mentions, and false merges:
  attached mentions whose gold differs from that of their Term's first mention, with their rate
  over attachments and its Clopper-Pearson bound (reported, never gated);
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

Certification follows protocol v2 (JCLAW-1368, JCLAW-1369). Every bound is a one-sided
Clopper-Pearson bound and goes through one seam, `GateBounds`, which has two implementations:

- `MemoryBounds` serves Terms, the relation gates and `G_written`. The sampled unit is the
  memory, since one memory that confuses two entities writes several wrong records at once, so
  n is the writing memories (those that wrote at least one of the gate's records) and k the
  memories counted wrong. A memory counts wrong when it has an unmatched record judged wrong.
  Each sampled agreed record judged wrong in a memory not already counted adds 1 over its
  inclusion probability; that sum is rounded up and the total capped at n. Recall's lower bound
  is a cluster bootstrap over the memories with gold: sorted by memory id, resampled 10,000
  times with replacement from the fixed seed 1369, and read at the ascending resample ratio at
  index floor(tail × 10,000), never above the point estimate.
- `RecordBounds` pools records: n is the written records and k the wrong ones plus the agreed
  sample's weighted wrong, rounded up. It serves the rules sized in values: the qualifier
  classes, `G_trap` and lineage.

**Tails.** Terms and the 12 relation gates are one Bonferroni family over the three candidate
models: each is bounded, passed, walked and given its power at tail 0.05 / 39 (about 0.00128).
`G_written`, the classes, `G_trap` and the recall lower bound stay at 0.05. Two runs are read
gate by gate at the run whose bound at that gate's tail is highest.

**Certifying sets.** The live sample (`heldout`) certifies Terms, every relation, `G_written`
and recall; synthetic strata (`cases`) certify the status, time and negation classes and
`G_trap`; the sequences certify lineage and the timeline. In a certification run or a re-score a
gate reads counts only when the split's set is its own, else it is **not evaluable**: a Term or
relation gate is `off` with the reason `<gate> not evaluable: its certifying set <set> has no
data in this run` (as is one whose set is present but holds no writing memory), a class is
`disabled`, and a pooled gate fails the sequencing at once with that reason and no back-off. One
run reads one split, so no run certifies until a run reads both sets (JCLAW-1380): a `heldout`
run has no `G_trap` and a `cases` run no Terms. A development run routes nothing.

### Gates

| Gate | n | k | Passes when |
|---|---|---|---|
| Terms | memories writing a decided Term at t, noise and rule-written left out | those memories counted wrong (`match`, `type`, `duplicate`) | bound ≤ 5% at 0.05 / 39, then recall lower bound ≥ the floor at its final threshold |
| One per relation type | memories writing that type's relations at its threshold, both endpoints written at the Terms threshold | those memories counted wrong (`relation`, `polarity`, `unasserted`) | as Terms |
| status, time, negation | values written on base-right parents, each class at the higher of its parent's threshold and its own | wrong values | n ≥ 250 and bound ≤ 5% (`certified`); 29 ≤ n < 250, k ≤ 6, bound ≤ 10% (`provisional`) |
| `G_written` | memories writing any item but noise over the enabled configuration | memories with an item wrong at base or in a written qualifier | bound ≤ 5% |
| `G_trap` | every gold `ended`, `denied` and `unasserted` relation | violations over the enabled configuration | bound ≤ 5% |

Records written by rule (the owner's Person Term on "The user" or a subject-less memory) count
in no total; the report gives how many were left out. A memory that rightly writes nothing is
in no precision denominator and is scored only by the per-stratum false-positive rate.

At tail 0.05, with zero wrong 59 records (or writing memories) pass; with 1, 2, 3 and 5 wrong,
93, 124, 153 and 208. At 0.05 / 39 a Term or relation gate needs 130, 176, 215, 251 and 317
writing memories. The report gives each gate's and class's power: the probability it passes at
its n when 1%, 2% or 3% of its units are truly wrong (59 at 1% and 0.05: 0.553; 130 memories at
1% and 0.05 / 39: 0.271).

### Sequencing

Every walk starts at the split's starting threshold (0.95 until a development run fixes one)
and never evaluates a threshold above it.

1. **Terms** first. The walk goes down while the Term bound passes; the Term threshold is the
   lowest of that run. Recall is checked once, there: recall rises as the threshold falls, so a
   per-step check would stop a walk before it reached a usable threshold. A Term gate that fails
   either way ends the walk: no relation gate is evaluated, and nothing is enabled.
2. **Relations**, one gate per type, in the split's relation order. Each walks the same way but
   never below the Term threshold, and is recall-checked at its own final threshold. A relation
   that fails is off; the others are unchanged.
3. **Classes** over the enabled configuration: status, then time with status at its walked
   threshold, then negation. Each starts at the highest threshold at or below the start with n
   of at least 29. A disabled class writes nothing.
4. **Pooled gates**, `G_written` and `G_trap`, over the enabled configuration with the walked
   classes. An off relation writes nothing into the classes or the pooled gates.
5. **Back-off.** While a pooled gate fails and a relation is on, the relation latest in the
   split order among those on is switched off, and the classes and pooled gates are evaluated
   again. The report lists each step. Failing with no relation on is `not-certified`, naming the
   gate and its counts. A pooled gate that is not evaluable fails at once, with no back-off:
   switching relations off cannot give it data. If the other pooled gate has data and fails its
   bound, that failure is listed beside it.

`Certifier` is pure: it re-scores through an evaluator the harness builds over stored
decisions. With two runs each gate reads the run whose bound is highest, so both must pass.

### Splits and use-once

A certification run names a frozen split:

```bash
./jclaw.sh grapheval freeze-split --name cert-2026-10 --set cases --share 0.3 --seed 1356 --starting-threshold 0.95
./jclaw.sh grapheval run --split cert-2026-10 --agent main --decision-model tev1
./jclaw.sh grapheval rescore --split cert-2026-10 --decision-model tev1
```

The manifest, `data/graph-eval/splits/<name>.json`:

```json
{"split": "cert-2026-10", "set": "cases", "seed": 1356,
 "ids": ["c003", "c014", "c046"],
 "hashes": {"c003": "9f1c2a7d4e05", "c014": "03be77a1c9d2", "c046": "e4a0915b7c38"},
 "cases": "cases@1a2b3c4d5e6f", "sequences": "sequences@6c1d0e9f2b7a",
 "guide": "guide@0f9e8d7c6b5a", "schema": "v3@...",
 "startingThreshold": 0.95,
 "relationOrder": ["uses", "works_at", "..."]}
```

- `set` is `cases` or `heldout`; a held-out split names memory ids, and its share defaults to 1.
  Ids are given, or drawn by a seeded shuffle at the share.
- Each hash is the 12-hex SHA-256 prefix over one case's canonical JSON, or a held-out memory's
  text and labels; `cases@` is the same over the selected cases, `sequences@` over
  `sequences.json`, `guide@` over the bytes of `GUIDE.md`, `schema` the seed's fingerprint.
- The default relation order is each type's gold count in the set's cases outside the split,
  most first, ties in schema order.
- Freezing an existing name is refused. A run whose split hash or sequences fingerprint has
  drifted is refused, naming the id or `sequences`. A run with no `split` over a set with frozen
  splits leaves their ids out and reports how many.

A split is used once per model version, the Ollama digest. `data/graph-eval/split-uses.jsonl`
appends one `{split, model, digest, state}` line per run; the latest line is the state:

| State | Next run |
|---|---|
| `used` | refused, quoting the line |
| `needs-second-run` (a single run's spot-check differed, on either set) | only `--runs 2`, as the same use; it replaces the stored decisions and appends `used` |
| `void` (any failed decision) | a fresh run is accepted |

### One invocation

`run --split` runs the split's set (gold-fed stages, end to end, and a single run's spot-check
of every tenth case), then the sequence answers with theirs. It stores every decision under
`data/graph-eval/runs/<split>/<model>.json`, appends the ledger line, and scores the stored
run: the gates, classes and pooled gates with the back-off, then the sequences at the
configuration reached (the lineage class walked from at or below the start, the timeline with
lineage at its own threshold and state). A run with any failed decision is void: its report
gives the failure counts and questions per memory, and no gate result.

**Re-score** reads the stored run and the adjudications on file and scores again through the
same path, so it gives the report a full run would. It asks no model and redraws nothing, and
is refused when the stored schema or extraction fingerprint differs from the running code's or
a split hash no longer matches. Verdicts can move the configuration lower and bring new records
into scope; those are listed on the sheet as unjudged until adjudicated and re-scored.

A development run (no `--split`) uses the same sequencing from 0.95 as information under each
model's `v2`, beside the earlier walk, and never writes a ledger line or a certificate.

### Verdict

In order, the first that applies:

1. any case memory changed by the run → `not-certified`;
2. any failed decision → `not-certified`, `void run`;
3. a single run whose spot-check differed → `not-certified`, `needs second run`;
4. any `label-error` verdict on a record in scope → `not-certified`, `labels need fixing`;
5. the Term gate off, a pooled gate not evaluable, or a pooled gate failing with no relation on
   → `not-certified`;
6. the sequence timeline `failed` → `not-certified`;
7. second labels short of the blind subset of the split's ids → `pending-agreement`;
8. any unjudged record, or a marked model verdict without its check → `pending-adjudication`;
9. otherwise `certified`.

### Certificate

A certified model's certificate is written as canonical JSON (keys sorted, compact), one file
per model, at `data/graph-eval/certificates/<model>.json` (path-unsafe characters replaced):

```json
{"id": "cert@4d2c9a7b1e30", "model": "tev1", "digest": "sha256:...", "status": "certified",
 "schema": "v3@...", "extraction": "x@...", "split": "cert-2026-10", "cases": "cases@...",
 "sequences": "sequences@...", "guide": "guide@...", "startingThreshold": 0.95,
 "terms": {"state": "on", "threshold": 0.85, "n": 410, "k": 4, "bound": 0.022, "recallLower": 0.71},
 "relations": {"works_at": {"state": "on", "threshold": 0.90, "n": 71, "k": 0, "bound": 0.041, "recallLower": 0.58},
               "kind_of": {"state": "off", "threshold": null, "n": 6, "k": 0, "bound": 0.393, "recallLower": 0.12}},
 "classes": {"status": {"state": "certified", "threshold": 0.85, "n": 262, "k": 5, "bound": 0.040},
             "time": {"state": "provisional", "threshold": 0.90, "n": 84, "k": 2, "bound": 0.073},
             "negation": {"state": "disabled"},
             "lineage": {"state": "provisional", "threshold": 0.90, "n": 80, "k": 1, "bound": 0.058}},
 "pooled": {"G_written": {"n": 1650, "k": 41, "bound": 0.032}, "G_trap": {"n": 228, "k": 4, "bound": 0.040}},
 "timeline": {"probes": 240, "definite": 205, "wrong": 3, "bound": 0.041, "result": "reported"},
 "recall": {"floor": 0.50},
 "sets": {"terms": "heldout", "relations": "heldout", "G_written": "heldout", "recall": "heldout",
          "classes": "cases", "G_trap": "cases", "lineage": "sequences", "timeline": "sequences"}}
```

An optional `resolution` section carries the entity-resolution shortlist threshold
(JCLAW-1370), written only when a calibration walk reached one, from its step there:

```json
{"resolution": {"shortlist": {"threshold": 0.90, "n": 74, "k": 0, "bound": 0.040}}}
```

`n` is the attachments at the threshold, `k` the false merges among them and `bound` their
Clopper-Pearson bound. The walk (`ResolutionCalibration`) goes from the strictest threshold
down and stops at the first whose bound fails, so with no false merge it needs 59 attachments.
Without the section the resolver never asks the decision model. Its keys are strict like the
rest: `certificate resolution: unknown key '<k>'` and `certificate resolution shortlist:
unknown key '<k>'`.

`id` is `cert@` plus the 12-hex prefix over the canonical JSON without `id`. `sets` states which
set certified each part, so a certificate says its qualifier classes and `G_trap` were certified
on synthetic strata, not on data drawn as deployment is. Its configuration
enables Terms and Mappings, each `on` relation at its threshold, and each class at its state and
threshold; a disabled class writes null. `CertificateDocument.read` returns the certificate only
when its schema and extraction stamps and its digest match the running ones, and refuses an
unknown key; otherwise it names which differs. Only what passed is enabled.

Every report stamps `extraction: x@…` beside `schema`: a hash of every question text, lexicon
and temporal probe the pipeline renders, so a wording change voids a certificate as a schema
change does. The `schema` is the seed's fingerprint, `v<version>@<12 hex>`: a SHA-256 prefix
over each term type's name and covers text and each relation's name and endpoints, the parts of
the schema the questions are built from. A request that times out while the model is still
loading waits for the load and is sent again, three attempts in all; one that times out on a
loaded model is not, since it counts against the breaker the router's classifier shares.
Concurrency defaults to 1: a local Ollama answers one request at a time, so a second in flight
only waits behind the first, toward the timeout.

### The earlier walk

A development run's report still carries the v1 walk beside `v2`, as information: class walks
at base 0.50, then the base walk from 0.95 down passing t while `G_written`, `G_trap` (pooled
over every relation) and the recall point estimate hold, stopping at the first failure; its
`certification` applies the v1 preconditions and lists the wrong records at the threshold
reached (at 0.50 when nothing passed). Only a split run certifies.

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

Protocol v2 adds, to every report (a development run under each model's `v2`): n, k, the bound,
the state and the power at 1%, 2% and 3% beside every gate and class verdict; the back-off
steps; the failed-decision share overall and by questions per memory (1–10, 11–20, 21–40, >40)
beside the mean; the false-positive rate per stratum tag; the unjudged records per gate; the
agreed sample's seed and share; the check share and the disagreement rate; and the rule-written
records left out. A certification report adds the sequences report at the configuration reached
and a note that the sequences set also serves development, since no split keeps it apart.

A certification report also carries the recall bootstrap's `bootstrapSeed` (1369) and
`bootstrapResamples` (10,000), and each model its `requirements`, from which JCLAW-1375 sizes the
live sample. They list every gate, always in this order: Terms, each relation in the split's
order, status, time, negation, `G_written`, `G_trap` (empty only on a void run). Each gives:

- `name`, `set` (its certifying set) and `tail`;
- `writtenRecords`, `writingMemories` and `goldMemories`, observed where the gate reads its n and
  k: its last passing step when its walk passed anywhere, else its first step (a class's
  threshold, else its first evaluable step). A gate the sequencing never walked, which is every
  gate after Terms once the Term gate is off, is observed with Terms at the starting threshold:
  a relation with every relation at the starting threshold, a class with every relation and
  every class provisional at the starting threshold, `G_written` and `G_trap` with nothing else
  enabled;
- `goldMemories` is the gate's memories with gold. `G_written`'s are those with gold for any gate;
  `G_trap`'s are the memories holding a gold trap relation, since a trap entry's written records
  are its gold trap relations; a class's are the memories in its observed values with a gold
  value;
- `needed`, one row per 0, 1, 2, 3 and 5 wrong: `records`, the record-level minimum at the gate's
  tail, and `memories`, the writing memories its bound needs, null for a record-level gate;
- `reachable`: whether `goldMemories` reaches the zero-wrong minimum of memories (at zero wrong
  every writing memory holds a right record, so holds gold); null for a record-level gate. It
  means something only on a run of the gate's own certifying set: on the other set the gate's
  counts are empty and it reads false. On a `heldout` run, a relation with `reachable` false can
  never certify on the live sample as drawn.

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

Two GUIDE v3 labels are flagged for JCLAW-1378 to put before the second labeller beside the
blind subset. c061 labels `lantern part_of brightwell` ended: has the old floor's `part_of`
ended? c127 keeps `gala involves operator` holds: does the owner's involvement in an
unconfirmed gala hold?

Adjudication is two-sided. `evals/graph/adjudications.json` (the committed set) and
`data/graph-eval/adjudications.json` (the held-out set) record verdicts on the unmatched
written records and on a sample of the agreed ones (written and labelled):

```json
[{"caseId": "c042", "record": "rel:Avery Lin:uses:Kestrel CI", "side": "unmatched", "verdict": "wrong",
  "guide": "guide@0f9e8d7c6b5a", "adjudicator": "operator", "note": "the memory does not relate the pair"},
 {"caseId": "c018", "record": "term:Fenwick:System", "side": "agreed", "inclusion": 0.2, "verdict": "right",
  "guide": "guide@0f9e8d7c6b5a", "adjudicator": "model:<name>", "check": "agree"}]
```

- `record` is the stable key, `term:<span>:<type>` or `rel:<from>:<type>:<to>`; a held-out
  `caseId` is the memory id.
- `side` is optional. A verdict without one is blind, `right` or `wrong` judged from the sheet
  alone, and takes the side of the record it judges: `right` on an unmatched record is a
  `label-error`. With a side, `unmatched` takes `wrong` (the model erred) or `label-error` (the
  label did, which blocks certification until the labels are fixed); `agreed` takes `right` or
  `wrong`.
- `adjudicator` is `operator` or `model:<name>`; `check` is the operator's `agree` or
  `disagree` on a model's verdict. Any other key or value is refused with a 400.
- Only verdicts under the current `guide@` count.
- **The agreed sample.** A record is drawn when the first 8 bytes of SHA-256 of
  `seed:id:key`, read as a fraction of 2^64, fall below the share (default 0.2, seed the
  split's); the draw is over the agreed records written at 0.50, so the sample at any
  configuration is part of it. Each sampled record judged wrong adds 1 / share to its gate's
  wrong count: one wrong of 100 agreed records at 0.2 adds 5.
- **Unjudged** is an unmatched written record, or a sampled agreed one, at the configuration
  reached with no verdict; an unsampled agreed record never is. The report counts them per
  gate.
- **Model verdicts.** The same hash rule at the re-score's check share (default 0.2) marks a
  model's verdicts for an operator check. The report gives the disagreement rate; a marked
  verdict without `check` keeps the status pending.

Each certification run or re-score writes the blind sheet
`data/graph-eval/sheets/<split>-<model>.json`: every unmatched written record and every sampled
agreed record at the configuration reached, each with its memory's text, and the seed and the
share. Nothing on it says whether a model or a label produced a record, or which model, so it
carries no per-record inclusion probability: re-scoring takes each record's side and inclusion
from the stored run.

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
result. Without a split its walk is information. A held-out split runs like the committed set, but
no second-label source covers it yet, so it always stops at `pending-agreement`. Its progress lines and a
failure's message are held to the same rule.

## Sequences

`sequences.json` (JCLAW-1367, JCLAW-1379) measures supersession: whether the decision model's lineage
choice is right, and whether `GraphView` then answers "did this hold at date D, as the sources
said at instant s" the way a person reading the memories would. It needs no agent and writes
nothing — no Memory row, no graph file.

### Format

The root holds `userMd` (the owner is the name it declares) and `chains`. Each chain is a list
of memories that follow the v3 case rules above, plus probes labelled from the texts — never
read back from `GraphView`.

| Level | Key | Meaning |
| --- | --- | --- |
| chain | `id` | Unique in the file |
| chain | `tags` | Any of `update`, `restatement`, `correction`, `guest-about-owner` |
| chain | `memories`, `probes` | In order |
| memory | `id` | Unique in the file |
| memory | `capturedAt`, `text`, `entities`, `relations`, `dates` | As for a case; `capturedAt` never earlier than the previous memory's (equal is fine) |
| memory | `supersedes` | `{earlierId: "update" \| "restatement" \| "correction"}`, the gold lineage of each link |
| memory | `derivedFrom` | Earlier memory ids the text was derived from |
| memory | `message` | The source turn's text, when it differs from the memory's |
| memory | `authorType` | `human_turn` (default), `guest_turn` or `consolidation_derived` (read in the owner's voice; needs a non-empty `derivedFrom`); every memory of a `guest-about-owner` chain is `guest_turn`, and its relations reaching the operator are `unasserted` |
| probe | `from`, `type`, `to` | Entity ids labelled in the chain, a relation the schema allows between their types |
| probe | `d` | The valid date asked about |
| probe | `after` \| `before` | Exactly one: the memory the probe is read just after or just before |
| probe | `truth`, `assumed` | `YES`, `NO` or `UNKNOWN`; `assumed` (default false) when the answer rests on an open end |

Every link names an earlier memory of the same chain. Any break of these rules is refused with
a message naming the chain and the memory or probe. The fingerprint is `sequences@` plus the
first 12 hex of a SHA-256 over the document with its keys sorted.

### Harness

1. **Ask.** Each chain runs through the production pipeline memory by memory, every question
   asked once per run: the known names are the owner plus earlier memories' kept term spans,
   one lineage question per gold `supersedes` link, and each date found against `capturedAt`
   re-based through `AnchorResolver` over the chain.
2. **Stamp.** Memory i of a chain (from 0) has the synthetic id `(chain+1)*1000 + i` and the
   system time `capturedAt` at 12:00 in the app zone plus i minutes. A superseded memory is
   retired at its successor's stamp.
3. **Replay.** The record sets are rebuilt in memory, memory by memory, for three variants:
   - `endToEnd` — the model's claims at the configuration, and its lineage at the run's own walk threshold;
   - `goldClaimsModelLineage` — the labelled claims, with the model's lineage;
   - `goldClaimsGoldLineage` — the labelled claims and lineage; every probe answers its label here
     except the pinned disagreements under [The committed set](#the-committed-set).

   With no lineage threshold a superseded claim is a retraction. A memory with any failed
   decision adds no records and no lineage, though its predecessors are still retired.
4. **Probe.** Each probe reads the chain's final set with `GraphView`, at the probed memory's
   stamp plus 30 seconds (`after`) or minus 30 (`before`). A relation with no record is UNKNOWN.

No variant, configuration or threshold asks another question.

### Configuration

`configuration` sets what the end-to-end variant writes at; it never changes what is asked.

```json
{"terms": 0.85,
 "relations": {"uses": 0.85, "located_in": 0.85},
 "classes": {"status": {"state": "provisional", "threshold": 0.85},
             "negation": {"state": "disabled"}}}
```

`terms` is required. An absent relation writes nothing and an absent class is disabled; a
certified or provisional class needs a threshold, a disabled one takes none. `lineage` is not
configurable: it is the run's own walk. With no configuration the run uses the default —
terms, every schema relation and the three classes provisional, all at 0.85 — and the report
says `"configurationSource": "default"`.

### Scoring

**The lineage class** walks `Certifier.classWalk` over the run's lineage decisions: at each
threshold, n decisions that write and k that chose other than gold. It is evaluable from
n = 29 and gates from 250. A run with any failed decision is disabled. Several runs take the
highest threshold at the lowest state any run reached. A single run re-asks chains 0, 10,
20, ... and compares their decisions position by position; any difference makes the state
`unconfirmed`.

**The timeline** counts, per variant, the definite answers that differ from a definite label.
An UNKNOWN answer to a definite label is `incomplete`, not wrong; a definite answer to an
UNKNOWN label is counted apart; neither enters the wrong share. `assumedAgreement` counts the
probes whose `assumed` matches. Below 250 definite labels the end-to-end result is `reported`;
from there it is `failed` when the 95% upper bound exceeds 5%, else `passed` — `reported` if
the run had a failed decision.

The committed set clears the lineage floor but neither gate. Its 90 links give n = 90 lineage
decisions per run, so lineage is evaluable but ungated below 250. Its 192 definite probe labels
leave the timeline `reported` below 250.

### The committed set

`sequences.json` (JCLAW-1379) holds 60 chains (30 of two memories, 30 of three) and 150
memories, at a mean of 18.9 words per text. Its 90 links are 40 `update`, 25 `restatement` and
25 `correction`. Its 240 probes are 118 YES, 74 NO and 48 UNKNOWN: 192 are definite, 41 are
`assumed`, and 72 are read `before` a memory. Six chains are `guest-about-owner`; three memories
are `consolidation_derived`. The six JCLAW-1367 chains the harness-mechanics tests rely on moved
to `test/sequence-fixture.json`.

| Shape | Chains |
| --- | --- |
| Move | s01, s02, s14, s20, s33, s46 |
| Ended then restated | s03, s04, s15 |
| Denial then reassertion | s10, s18, s19, s50 |
| Rejoin | s16, s17, s45, s48 |
| Scheduled start | s27, s31, s40, s55 |
| Dates carried into a derived memory | s34, s44, s51 |
| Guest about the owner | s35, s39, s43, s47, s49, s54 |

**How it was written and labelled.** Every model came from the Anthropic family, since no
other family was available in the build sandbox. None was tev1, nimble or clef-flash.
- **Writing.** Four writer subagents on Claude Opus wrote the texts, entities, relations,
  dates, links and probe questions, each over a disjoint range of chain ids.
- **Labelling.** Three blind labellers worked from a sheet holding only each memory's id,
  `capturedAt`, author type and text, each link without its lineage, and each probe without
  its truth, plus GUIDE v3, the seed schema and the probe rules. The labellers were A on Claude
  Opus, B on Claude Sonnet and C on Claude Fable; each labelled both halves of the sheet.
- **Reconciling.** One reconciler on Claude Opus merged their answers from the same inputs,
  never seeing the writers' drafts.

Writer-drafted lineage and truth never reached the file. All three labellers agreed on all 90
lineages and on 230 of the 240 probes. The reconciler decided the other 10 under the probe
rules: s04#3, s10#3, s21#3, s23#0, s26#3, s30#3, s38#2, s44#3, s46#0 and s53#0. No label
changed after reconciliation.

Three texts were edited after labelling without changing their meaning:
- s07b gained commas around "since a bad chill last November";
- s53c gained commas around "after a shoulder injury in January";
- s41c's "much prefers rowing instead" became "now much prefers rowing".

The end-to-end candidate generator reads a preference object up to the next punctuation mark.
Unpunctuated, the object ran into the trailing clause and matched no labelled mention, so even
a gold decider wrote no relation and the validator check failed.

**Gold-fed disagreements.** With gold claims and gold lineage, `GraphView` answers 41 probes
differently from their labels. `SequenceHarnessTest` pins exactly these keys, each with the
answer `GraphView` gives. Every label stands: each answer follows the probe rules from the
texts, and none is changed to match `GraphView`.

*Stated NO marked assumed.* The text itself states the end, the start or the refusal. The probe
rules settle the NO without assuming ("a former home or employer is NO now", "a scheduled start
is NO before it begins", a stated start bounds the relation). `GraphView` marks every NO outside
a stated interval as assumed.

- `probe:operator:located_in:ashgrove:2025-10-01:after:s46b:NO`
- `probe:operator:located_in:cindervale:2024-12-01:after:s02b:NO`
- `probe:operator:located_in:dunmore-quay:2024-12-20:before:s14b:NO`
- `probe:operator:located_in:larchmere:2019-06-01:after:s30b:NO`
- `probe:operator:owns:orrin-lodge:2020-06-01:before:s32b:NO`
- `probe:operator:uses:corvid:2025-06-01:after:s34a:NO`
- `probe:operator:uses:glasswing:2024-04-01:after:s38b:NO`
- `probe:operator:uses:osprey:2026-01-05:after:s03b:NO`
- `probe:operator:uses:quillpad:2021-06-01:after:s21b:NO`
- `probe:operator:works_at:alderline-software:2024-06-01:after:s09b:NO`
- `probe:operator:works_at:brightwell:2021-06-01:after:s26b:NO`
- `probe:operator:works_at:brightwell:2024-11-08:after:s37b:NO`
- `probe:operator:works_at:harborlight:2023-09-01:after:s45c:NO`
- `probe:operator:works_at:harborlight:2024-09-01:after:s48c:NO`
- `probe:operator:works_at:harborlight:2024-10-01:after:s16b:NO`
- `probe:operator:works_at:harborlight:2025-09-02:before:s16c:NO`
- `probe:operator:works_at:kiln-street-studio:2025-06-01:after:s17c:NO`
- `probe:operator:works_at:ostrander:2024-05-01:before:s04b:NO`
- `probe:operator:works_at:ostrander:2024-10-01:after:s04b:NO`
- `probe:operator:works_at:vela:2025-04-01:after:s27b:NO`
- `probe:operator:works_at:vela:2026-09-20:after:s31c:NO`
- `probe:operator:works_on:atlas-migration:2024-07-03:before:s12c:NO`
- `probe:operator:works_on:atlas-migration:2025-09-10:after:s51b:NO`

*Stated YES marked assumed.* A stated start and a stated end, or an earlier statement and a
later "still", bracket `d`. `GraphView` marks the YES as assumed because the update superseded
the earlier holding and the later memory carries no start.

- `probe:operator:holds_view_on:bouldering:2024-01-01:after:s23c:YES`
- `probe:operator:owns:ferrule-cabin:2025-02-01:after:s24b:YES`
- `probe:operator:uses:quillpad:2025-10-01:before:s34c:YES`
- `probe:operator:works_at:lantern:2025-06-01:after:s27c:YES`
- `probe:operator:works_at:vela:2026-05-10:after:s31c:YES`

*Assumed YES read as stated.*
- `probe:operator:works_at:juniper:2026-09-01:after:s44c:YES` — under the derived-memory rule,
  s44c's "now" is carried from s44b and resolves to 2026-07-14, so the YES at 2026-09-01 rests on
  the consulting continuing. `GraphView` reads the derived memory's holding from its own stamp.
- `probe:operator:works_on:atlas-migration:2025-06-20:after:s36b:YES` — s36b states only that the
  January work was for the Atlas Migration, so the YES at its capture rests on the work
  continuing. The claim's open `2025-01/..` reads to `GraphView` as stated.

*An ended duration has no start.* "Left … after four years" places `d` inside the job.
GUIDE rule 13 gives an ended relation's duration no bound, so the graph has no start and
answers UNKNOWN.

- `probe:operator:works_at:ostrander:2021-06-01:after:s04b:YES`
- `probe:operator:works_at:harborlight:2022-06-01:after:s45a:YES`

*An approximate start.* "For three years" and "for two years" give an approximate start
(`2020~`, `2021~`) by GUIDE rule 13, and `d` falls after that whole year. `GraphView` still
answers UNKNOWN; that it does so because the start is approximate is inferred, not traced.

- `probe:operator:located_in:nettlefield:2021-06-01:after:s33a:YES`
- `probe:operator:works_at:alderline-software:2022-06-01:after:s09c:YES`

*A correcting denial covers the past.* A correction says the earlier memory was wrong when it
was written, so its denial answers NO before the correcting memory too ("a denial is NO").
None of these is a perfect "has never", so by GUIDE rule 13 the denial carries no `valid`, and
`GraphView` answers UNKNOWN before its stamp.

- `probe:operator:owns:fernlight:2025-05-06:after:s28b:NO`
- `probe:operator:uses:sorrel-drive:2024-10-06:after:s56b:NO`
- `probe:operator:works_on:bluefin:2024-04-15:after:s45c:NO`
- `probe:operator:works_on:meridian:2025-06-01:after:s06b:NO`

*A corrected update.* The correction withdraws the update's "left" or "sold" ("after a
correction it no longer counts"), and the later text says the relation still holds. `GraphView`
keeps the update's end on the earlier holding and answers NO.

- `probe:operator:owns:ferrule-cabin:2024-08-02:after:s57c:YES`
- `probe:operator:works_at:brightwell:2024-11-08:after:s37c:YES`

*Restated "still" without a start.* s11b corrected the office, and s11c says Avery Lin "still"
works at Brightwell Labs, which covers May. `GraphView` has only s11c's holding, with no start,
and answers UNKNOWN before its stamp.

- `probe:operator:works_at:brightwell:2025-05-01:after:s11c:YES`

**Operator spot-check.** `sequence-label-checks.json` holds 30 lineage records
(`lineage:<later>:<earlier>:<lineage>`) and 40 probe records (keyed like the disagreements
above, but drawn from every probe), each with verdict `pending`.
- **Sampling rule.** Within each kind, sort every record in `sequences.json` ascending by the
  SHA-256 hex of `"11:" + record` (the seed, a colon, the record) and take the first k.
- **Judging.** The operator replaces each `pending` with `agree` or `disagree`, or redraws at
  another size by the same rule.
- **Rates.** The two disagreement rates are reported apart, each as disagree over checked:
  lineage over the lineage records and probes over the probe records.

### Report

`set`, the `schema`, `extraction` and `sequences` fingerprints, `chains`, `probes`, `runs`,
`configurationSource`, the `configuration`, and per model: each run's `failedDecisions`,
lineage walk, three timelines and result; the single run's `spotCheck` (`chains`,
`decisions`, `differing`); the model's `lineage` walk and `lineageState`; and its overall
`timeline` — failed if any run failed, else reported if any did. Counts only: the same answers
serialize identically.

### Running it

```bash
./jclaw.sh grapheval run --set sequences --decision-model tev1
./jclaw.sh grapheval run --set sequences --decision-model tev1 --configuration config.json --runs 2
```

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
