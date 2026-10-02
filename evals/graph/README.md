# Graph-extraction cases (JCLAW-1344)

`cases.json` is the labelled set the graph-extraction spike runs through every proposer x
decision-model pairing (`POST /api/graph/spike`, `./jclaw.sh graphspike`). It is not an
`evals/suites/` dataset: those are offline and agent-turn shaped, while this one needs live
chat and decision providers.

## Format

```json
{"cases": [
  {"id": "rel-works-at",
   "text": "Dana Reyes works at Harborlight Analytics as a data engineer.",
   "entities": [{"mention": "Dana Reyes", "type": "Person"},
                {"mention": "Harborlight Analytics", "type": "Organization"}],
   "relations": [{"from": "Dana Reyes", "type": "works_at", "to": "Harborlight Analytics"}]}
]}
```

- `id` is unique; `text` is stored verbatim as one memory of the requested agent for the run
  and deleted afterwards.
- Every `mention` appears verbatim in `text`, at most once per case, and its `type` is one of
  the seed ontology's term types (`memory.ontology.OntologySchema`).
- A relation's `from` and `to` are labelled mentions, and the schema allows its `type` between
  their labelled types.
- `entities` lists everything worth a graph node and nothing else: a case with only generic
  nouns ("coffee", "the meeting") has its named terms labelled and the distractors left out,
  and a case with no entity has an empty list.

`services.graphspike.GraphCases` refuses a set that breaks any of these, naming the case, and
`GraphCasesConformanceTest` fails the build on it.

## Scoring

Every written record is checked against its case's labels:

- **match** — a term whose mention is no labelled entity is wrong.
- **type** — a matched term typed differently from its label is wrong.
- **relation** — a relation is wrong when its `(from, type, to)` triple is not labelled, or
  either endpoint term is wrong. `same_as` and `family_of` are symmetric and match a label in
  either direction; every other relation matches only as labelled.

At a threshold t a decision is written only when its choice's probability is at least t; a
relation additionally needs both endpoint terms written at t. `not_an_entity` and `none` write
nothing. Below t the decision is an abstention; an error or invalid answer is a failure,
reported separately.

- wrong share = wrong written records / written records
- abstention rate = abstentions / decisions asked
- missed labels = labelled entities and relations not written correctly

A decision model is allowed at t only when every proposer's pairing with it wrote at least one
record and is at most 5% wrong (exactly 5% passes). The report gives the gate at the requested
threshold (default 0.50), the curve at 0.30 to 0.95 by 0.05, and each model's lowest allowed
threshold. Any case memory changed by the run empties the allow-list.

## Synthetic only

The GitHub mirror is public. Every name, organization, place and file here is invented; URLs
use `example.com`. Never add a real person, company or customer detail, and never copy text
from a real memory.
