# Evals

Datasets that measure agent behaviour, the offline runner that scores against
them (JCLAW-875), and the capture path that drives a live agent to produce a run
worth scoring (JCLAW-883). Epic JCLAW-833.

The point of this directory is to be a **ruler**. Every story in the agentic
harness epic — planning, critic, best-of-N, retries — is a claim about quality,
and claims need something to be measured against that does not move while the
thing under test changes. Suites live in git so the history is there, and every
run records a fingerprint of the suite content that scored it — so when the ruler
does move, nothing compares across the change without saying so.

```
evals/
  schema/eval-suite.schema.json   the format contract (what authors read)
  suites/<id>.json                the datasets (what the runner reads)
```

## Running it

```bash
./jclaw.sh evals                                              # validate the dataset
./jclaw.sh evals --responses run.json --out reports/now.json  # score a recorded run
./jclaw.sh evals --responses run.json --baseline reports/last.json   # catch regressions

# drive a live agent and write the run the scorer consumes
./jclaw.sh evals --capture run.json --agent __evaltest__ --suite tool-selection
```

Validating and scoring are **offline**: no backend, no database, no model call.
Running them costs nothing on the serving path, which is the epic's efficiency
NFR applied to its own measurement tooling. Capture is the one command that
spends model calls, and it is opt-in by construction — see below.

`play autotest` runs the same validation via `EvalSuiteConformanceTest`, so a
malformed suite fails the build rather than the next eval run.

## Dataset format

One suite per file, named `<id>.json`. The filename is the suite's identity and
nothing else; the loader refuses a file whose body id disagrees with its name.

```json
{
  "id": "tool-selection",
  "description": "Does the agent reach for the right tool?",
  "cases": [
    {
      "id": "wall-clock-datetime-optional",
      "input": "What time is it?",
      "rubric": "The harness already injects the current time, so the clock tool is allowed but not required.",
      "checks": [
        { "kind": "tools_called_within", "args": ["datetime"] },
        { "kind": "max_llm_calls", "limit": 2 }
      ]
    }
  ]
}
```

`rubric` is required: a case that cannot say in one line what it is testing
does not belong in the suite.

### Check kinds

Every kind is decidable from the response alone — no judge model.

| kind | payload | passes when |
| --- | --- | --- |
| `contains_all` | `args`: substrings | every substring appears (case-insensitive) |
| `not_contains_any` | `args`: substrings | none of them appears |
| `matches` | `args`: one regex | the regex finds a match |
| `json_schema` | `schema` | the response parses as JSON and validates |
| `tools_called_exactly` | `args`: every tool name allowed | the calls made equal that list as a multiset — no extras, no repeats. `[]` means no tool at all |
| `tools_called_within` | `args`: the tools permitted | every call is in the list, but none is required — the "or" form. Extras and repeats still fail |
| `tool_args_include` | `args`: one tool name, `schema`: expected arguments | a dispatched call to that tool carried every key in `schema` with an equal value |
| `tool_result_includes` | `args`: a tool name, then substrings | a dispatched call to that tool returned all of them (case-insensitive) |
| `max_llm_calls` | `limit` | the turn spent at most `limit` model calls |

`json_schema` implements a subset: `type`, `properties`, `required`, `items`,
`enum`, `additionalProperties`, `description`. JClaw carries no JSON Schema
validator dependency, and a hand-rolled subset that ignored the keywords it does
not understand would report passes it never checked — so the loader **rejects**
any other keyword instead. Add the keyword to `EvalScorer` if a suite needs it.

The response is parsed verbatim: no fence-stripping, no brace-hunting. Prose
around a JSON body is the defect a structured-output case exists to catch.

`max_llm_calls` is the epic's efficiency NFR written as an assertion. It scores
against the per-turn `llm_call_count` (JCLAW-882), so a story that buys quality
with an extra model call has to declare the purchase.

### Why tool checks are allowlists

There used to be a `tool_called` / `tool_not_called` pair. JCLAW-883 removed
them: they were a **denylist**, which only catches rogue behaviour someone
predicted, and is therefore structurally unable to express "only what was
necessary".

`arithmetic-needs-no-tool` is the worked example. Its rubric says *"any tool call
here is pure overhead"*, but the denylist version of it could only forbid the two
tools the author happened to name — a stray `task_manager` or `generate_image`
call passed while breaking the case's stated intent. The same hole is why the
first live sweep's errant task creation was invisible to the suite: the case that
caused it asserted one `tool_called` and nothing else, so the agent could have
called that tool five times plus `exec` and still scored a pass. It now asserts
`tools_called_exactly: []`, which is what the rubric said all along.

`tools_called_exactly` is the **allowlist** counterpart, and it collapses three
concerns into one assertion:

- **Wrong tool** — anything outside the list is `unexpected`.
- **Superfluous repeat** — the comparison is a multiset, so calling a
  once-listed tool twice fails.
- **Rogue side effects** — an agent can only touch the world through tools, so
  an unsanctioned effect shows up as an unsanctioned call.

That last one is the important one and it has a boundary worth stating: this
detects the *call*, not the *effect*. A tool the case legitimately allows can
still do more than intended — `task_manager` called once, with wrong content.
Catching that needs assertions over resulting state, which nothing here does.

Order is not compared. Two tools the agent could equally have called in either
order are not a behaviour difference worth failing a suite over.

### When a tool is optional: `tools_called_within`

Sometimes two behaviours are both correct. Asked "what time is it?", an agent that
calls `datetime` is right, and so is one that answers straight from the timestamp
`CurrentTimeInjector` already stamped onto the user message — that injection
exists precisely to save the round-trip, so demanding the tool would reward a
wasted call.

`tools_called_within: ["datetime"]` accepts both. Every call must be in the
allowance, but none is required. Extras and repeats still fail, so the case keeps
its teeth: a web search fails it, and so does calling the clock twice.

Reach for `tools_called_exactly` when the tool genuinely is mandatory —
`date-difference-uses-datetime` in `tool-selection` is the contrast, because
the injected clock answers "now" and nothing else, so a span between two dates
has to come from the tool.

Prevention and measurement are different jobs, and the two mechanisms compose:
`__evaltest__`'s opt-in tool surface means an ungranted tool *cannot* fire, while
`tools_called_exactly` catches a granted tool used when it should not have been.

### Asserting on arguments: `tool_args_include`

The tools-called kinds see names only, so they cannot tell one use of a tool from
another. `datetime` answers "what time is it?" with `action=now` and "how many
days between two dates" with `action=calculate`. Against
`tools_called_exactly: ["datetime"]` both pass — so a case about date arithmetic
would score a clock reading as correct.

```json
{ "kind": "tool_args_include", "args": ["datetime"], "schema": {"action": "calculate"} }
```

`schema` here is **not** a JSON Schema despite sharing the field. It is a literal
subset of the arguments the call must have carried, so its keys are the tool's own
parameter names.

Subset, not equality, so a case pins the argument that carries its meaning without
pinning the ones that do not — a timezone the model is free to default is not what
the case is about, and pinning it would make the case brittle for no gain. It
passes if **any** dispatched call to that tool matches, because a turn may
legitimately use one tool several ways.

Only dispatched calls contribute arguments. A refused call carried its arguments
nowhere, so asserting on them would be asserting on an intention.

### Asserting what happened: `tool_result_includes`

Arguments say what the agent *asked for*. Only the result says what *happened* —
and without that, a case passes on a turn that accomplished nothing.

That is not hypothetical. A live sweep scored `named-url-uses-fetch-not-search` as
a **pass** while that same turn's log read:

```
Tool 'web_fetch' returned: Error fetching URL: HTTP 404 fetching https://example.com/pricing
```

The agent picked the right tool, passed the right arguments, and the tool failed
outright. Every check was green, because none of them looked at what came back.

```json
{ "kind": "tool_result_includes", "args": ["task_manager", "created", "0 0 9 * * 1-5"] }
```

First arg names the tool; the rest are substrings its result must carry. It also
closes the "right tool, wrong content" case — a `task_manager` call that creates a
task on the wrong schedule is indistinguishable from a correct one under
`tools_called_exactly` and `tool_args_include` alike, but its *result* says which
schedule was written.

**What it does not cover.** This asserts what the tool *said* it did. A tool that
reports success while half-failing still passes. Catching that needs assertions
over resulting state — a database or filesystem check — which would put a backend
behind the scorer and cost the property that makes recordings re-scorable offline.
Deliberately out of scope; see JCLAW-891.

### What is deliberately missing

Semantic verification ("does this answer the question?") and free-form rubric
grading need a judge model. They are not here — that is JCLAW-836's critic,
which this story was descoped away from. The grounding suite approximates
hallucination detection deterministically instead: plant a fact and a
distractor, require the first and forbid the second. It is blunter than a judge
and it costs zero calls.

## Recording a run

The runner scores whatever produced the responses; the file is the contract.

```json
{
  "suite": "grounding",
  "fingerprint": "bf5ff8047a1d",
  "responses": {
    "carries-the-figure-from-source": {
      "output": "The transfer lasted 209 days.",
      "toolsCalled": ["web_fetch"],
      "toolsAttempted": ["web_fetch", "httpFetch"],
      "toolResults": { "web_fetch": ["The transfer lasted 209 days."] },
      "llmCalls": 2
    },
    "cites-the-chunk-it-used": {
      "error": "provider unreachable"
    }
  }
}
```

`error` marks a case the agent never answered. It is optional, and older
recordings without it read as answered.

### `toolsCalled` vs `toolsAttempted`

- **`toolsCalled`** — names where a tool actually ran. **The checks score against
  this**, because it is what produced side effects and did the work.
- **`toolsAttempted`** — every name the model emitted, including ones it invented
  and ones the agent was not granted.

They were one field until JCLAW-883, and it recorded attempts while being named
for calls. A live sweep made the cost concrete: a turn that executed *nothing*
recorded `toolsCalled: ["httpFetch", "http_fetch", "webSearch"]`, because the
model guessed three names that do not exist. Scored against that, the agent
looked like it had used three tools.

The difference between the lists is itself worth reading. An agent guessing tool
names is spending model calls to accomplish nothing — in that same sweep it
burned four calls to produce an apology. `max_llm_calls` catches the cost;
`toolsAttempted` says why.

Older recordings carry only `toolsCalled`, and at that time it meant *attempted*.
The loader defaults `toolsAttempted` to it rather than to empty, so those files
keep meaning what they recorded.

## Errored is not failed

"The agent answered wrongly" and "the agent never answered" are different
findings, and the report keeps them apart:

- A **failed** case was measured. It counts against the pass rate and, if it
  passed in the baseline, it is a regression.
- An **errored** case was not measured. It is excluded from the pass rate's
  denominator and is never a regression, because nothing about the agent's
  behaviour was observed. The reason is printed next to the case, and the totals
  line always states how many cases errored — a rate computed over four of ten
  cases would otherwise read like a rate over ten.

Both fail the run. A sweep that could not run is not a green sweep.

A case with no recorded response at all errors with `no response recorded`, so a
partial sweep reads as unmeasured cases rather than a suspiciously short suite.

## Capturing a run from a live agent

```bash
./jclaw.sh evals --capture run.json --agent __evaltest__ --suite tool-selection
```

This needs the backend running, because driving an agent needs what the offline
CLI deliberately does without: JPA, a configured provider, and the tool registry.
It POSTs to `/api/evals/capture`, gated the same way the loadtest endpoints are —
loopback origin plus `X-Loadtest-Auth` carrying the application secret.

Capture and scoring stay two steps on purpose: the recorded file is the boundary,
so a sweep can be scored now, re-scored later against a changed suite, or diffed
against a baseline, without paying for the model twice.

**`--agent` is required and never defaults.** Use `__evaltest__` (below), not an
agent you actually work with.

What a sweep does not leave behind: turns run through `AgentRunner.runForTask`,
which builds a transient conversation it never persists — so there are no history
rows to clean up, which is a stronger guarantee than creating throwaway
conversations and deleting them, since nothing survives a sweep that dies midway.
`MemoryAutoCapture` fires from the two chat entrypoints and not from this one, so
an eval cannot teach the agent about its own eval questions. The per-case
`LatencyTrace` exists only to count model calls and is never ended, so no eval
turn reaches `LatencyStats` — hundreds of them landing in the request-path
histograms would corrupt the baseline JCLAW-833 measures against.

### What it does leave behind: tool side effects

The isolation above covers the turn's bookkeeping. It does not cover what the
agent *does*. Tools execute for real. A case that induces a `task_manager` call
creates a real scheduled task; a case that induces a write tool writes.

This is not theoretical. The first live run of `tool-selection` against the
operator's `main` agent created a recurring task (`check-deploy-queue-weekday`,
cron `0 0 9 * * 1-5`) that had to be deleted by hand — the suite contains a case
designed to make the agent reach for the task manager, and it did.

So the agent you point capture at is a safety boundary, not just a label.

## The `__evaltest__` agent

`__evaltest__` is the agent sweeps are meant to run against — the eval sibling of
`__loadtest__`. Capture provisions it on first use, copying provider and model
from `main` as a starting point.

**Every tool is opt-in for it.** An ordinary agent starts with the full tool set
and the operator switches things off; `__evaltest__` starts with nothing and
capture switches on what each suite declares. That inversion
lives in `ToolRegistry.computeDisabledTools` alongside the same rule that makes
`generate_image` and `generate_video` opt-in, and it holds for tools added to the
registry later — which seeding disabled rows at provisioning would not.

It is enforced at execution, not only in the schema. That distinction cost a
round: the first `__evaltest__` sweep was offered no tools, guessed `web_search`
anyway, and it *ran* — because per-agent tool config was consulted only when
building the tool list sent to the model. `ToolRegistry.execute` now refuses a
native tool the agent has not been granted (JCLAW-883). MCP tools keep their own
execution gate, the `AgentSkillAllowedTool` allowlist, and are not double-gated.

### Calibration comes from the suite

A suite declares what it needs, and capture grants exactly that before the sweep,
revoking anything else:

```json
{
  "id": "tool-selection",
  "requiredTools": ["web_search", "web_fetch", "datetime", "task_manager"],
  ...
}
```

It lives with the suite because it is a property of what the suite measures —
`tool-selection` is not a measurement of anything if the agent cannot reach the
tools it is being scored on selecting. Putting it here also makes a sweep
reproducible from the repository, rather than depending on whatever was last
clicked in the agent editor. `requiredTools` is part of the fingerprint for the
same reason a check is: granting a different tool set changes what the pass rate
means.

Suites whose cases are self-contained prompts declare nothing and get nothing —
`grounding` and `structured-output` plant their sources in the prompt itself.

**Only `__evaltest__` is ever calibrated this way.** Point capture at an agent you
configured yourself and your configuration is left alone; the cases then fail
naming the tool that was missing, which is the right outcome for an agent this
code does not own.

You can still adjust it by hand through the agents API, but the next sweep will
reset the tool grants to what the suite declares. Unlike `__loadtest__` it is
deliberately **not** in `ApiAgentsController.isReservedName`: reserved rows are
hidden from every user-facing API, and deleting this one is how a sweep is cleaned
up. It is hidden only from the agent list.

Granting `task_manager` means the reminder case creates a real scheduled task on
every sweep. That is the suite doing its job — and it is why the cleanup below
matters.

To clean up everything a sweep created, delete the agent — `AgentDeletionCascade`
takes its tasks, runs, and messages with it.

`--concurrency` (default 4, max 16) bounds how many cases are in front of the
model at once. Unbounded fan-out would contradict the NFR the suite exists to
police, and a rate-limited provider's retries would score as agent failures.

## Changing a suite

**Edit it in place.** Git holds the history, and `git diff` shows exactly which
cases changed — which a new file beside the old one does not.

Adding a case is safe: a case absent from the baseline is not a regression.

Changing or removing a case redefines what the pass rate means, and that is what
the fingerprint is for. Every run records a short hash of the suite content that
scored it, printed beside the id as `tool-selection@6e7927aeefe4`. Compare two
runs whose fingerprints differ and the CLI says so before it prints anything
else, because case ids can match across two rulers while meaning different
things.

What the hash covers: each case's id and input, and each check's kind, args,
schema and limit, in order. What it deliberately ignores: `description` and
`rubric`. Both are prose explaining why a case exists and neither changes a
verdict, so sharpening a rubric must not invalidate a baseline — a fingerprint
that moved on cosmetic edits would produce warnings people learn to click past.

### Why not a version number in the filename

That is what this directory used to do (`<id>.v<N>.json`, the version repeated in
the body, a new file for every change). JCLAW-883 removed it, for three reasons
found by trying to use it:

- **It was never enforced.** Nothing detected an edit made in place under an
  unchanged version, so the scheme protected against the author who remembered to
  bump and was blind to the one who did not — which is the failure that actually
  happens. The fingerprint is computed, not asserted.
- **It made the documented workflow impossible.** The loader deduplicated on id
  alone, so publishing the `v2` this README told you to publish was rejected as a
  duplicate. The first person to follow the instructions was the one who found out.
- **Git already did the versioning half.** History, diffs, and recovering a
  deleted suite were all available; the filename convention added duplication,
  highest-version resolution, and files that accumulate with no defined end.

## Memory-recall suites

A different question from everything above. The behaviour suites ask *what did the
agent do*; these ask *did retrieval find the memory at all*, scored against a ranked
list of memory ids with no model call in the loop. `MemoryEvalSuite` is a sibling of
`EvalSuite` rather than a mode of it, because a recall case carries a query and gold
ids where a behaviour case carries a turn and checks — see the class note.

```bash
POST /api/memories/evals/generate   # build a suite from an agent's own corpus
POST /api/memories/evals/run        # score it against live recall
```

Scoring runs each case through `SystemPromptAssembler.recall`, the same pipeline the
system prompt uses, so a number describes production rather than a reimplementation.
`scope` picks the ranking: `selected` is what the model actually sees (truncated to
the recall budget), `candidates` is the ranking before that cut. Comparing the two
separates *retrieval* failure from *budget* truncation — if a case misses at both, a
larger `memory.recall.limit` would not have saved it.

**These suites are personal data and live only in `evals/local/`**, which is
gitignored and additionally guarded by `MemoryEvalPaths`. A generated case is not a
pointer to a secret, it *is* the secret: the query "what is my NAS IP" is the thing
worth protecting, and the gold answers are the memories themselves. This repository
is mirrored publicly.

### Bridge cases

`generate()` writes each question *from the gold memory's own text*. That is
self-referential by construction, so it can only produce cases where the query and the
answer share vocabulary — and it therefore cannot express the failure mode operators
actually report, where a question asks through a **relation** and the memory holding
the answer names only the **entity**:

```
relation row:  "The user has a son named Zephyrin."
fact row:      "Zephyrin goes by Zeph."
question:      "what is my kid's alias?"
```

No single embedding is near both "my kid's alias" and "Zephyrin goes by Zeph", and
the two facts live in different rows, so neither retrieval leg crosses the gap in one
shot.

`mode: "bridge"` generates these: it pairs a memory carrying a relation word with one
that shares a rare content token and carries no relation word of its own, and asks for
a question phrased through the relation. Gold is the *target* row — the one the
question cannot reach — not the relation row, which the question's own words already
find.

The pairing keys on rare shared tokens rather than on capitalisation on purpose.
Retrieval-key generation seeds on `JpaMemoryStore.entityNames`, a capitalisation rule,
so generating gold that way would select for pairs the keys already link and
report the keys' own heuristic back as a score — the same trap the coverage mode's
note describes for lexical clustering.

Expect bridge numbers well below generic ones, and read them separately. Measured on
one 89-memory corpus: the generic suite scored R@1 0.625 / R@10 0.925 while the bridge
suite over the same store scored R@1 0.40 / R@10 0.80. A generic suite alone would
have reported that recall was healthy.

Two things worth knowing before reading a bridge result:

- **A small corpus hides the defect.** Both legs are capped at the recall limit, so on
  a corpus smaller than that cap the vector leg returns *everything* and the gold
  arrives without any bridging.
- **Relative scores cannot say "nothing here is relevant."** `ReciprocalRankFusion.fuse`
  divides every fused score by the top one, so the best hit is `1.0` however far away it
  is. A memory scoring relevance 1.0 is the *least bad* candidate, not a good one.
  `memory.recall.minCosine` is the absolute floor underneath that, and it is a property
  of the embedding model — re-sweep it after changing models, and re-sweep
  `memory.recall.rrfK` after a large change in corpus size. Both carry their measurement
  basis in their Javadoc.

### Why the bridge gap existed at all

The obvious reading of a missed bridge case is that the vector leg is weak. On this
corpus it was not: the leg was returning its true nearest neighbours, and the gold
memory was the **54th of 89** by cosine. No KNN capped at the recall limit returns a
54th neighbour.

The cause was that queries and documents were embedded the same way, against a model
that is asymmetric — `snowflake-arctic-embed` wants an instruction prefix on queries and
documents bare. Prefixing moved that gold from 54th to 16th, and to **2nd** once
retrieval keys were indexed. Unprefixed, the whole corpus bunched into 0.65–0.69 against
that query with an unrelated preference about em dashes on top: near-uniform similarity,
almost no ranking signal.

`memory.jpa.vector.queryPrefix` carries the string and is empty by default, because the
right one is a property of the model. **Re-sweep `memory.recall.minCosine` whenever it
changes** — the prefix moves the absolute scale as well as the order (this corpus tops
out near 0.35 prefixed against 0.69 bare), so a floor written for the bare scale rejects
every vector leg on every query and leaves recall keyword-only without failing.

A third "second hop" leg once sat here, re-searching the entity names found in hop 1. It
was built while the vector leg was mis-queried and was removed once that was fixed: with
the prefix in place it cost more than it bought, pooling R@1 0.705 without it against
0.639 with it across 61 cases, while R@10 and coverage stayed at 1.00 either way. It is
worth re-testing on a much larger corpus, where a bridge has more room to matter — the
measurement above is one 89-memory store.

### What a bridge suite is not measuring

Two things sit *outside* retrieval and will move these numbers without the ranker
changing, so read a bridge score knowing where each fact actually lives.

**Identity facts are not retrieved at all.** Category `core` renders into every system
prompt, so a promoted memory reaches the model whether or not recall finds it. Capture
assigns it on content, not on the extractor's say-so — `MemoryIdentityClass` is a closed
pattern list, because core sits above the prompt-cache boundary and each admission
invalidates the cacheable prefix. Consequence for measurement: a suite case whose gold is
a core memory is scoring a path the agent does not depend on. On the corpus this was
built against, 8 of 89 memories are core, and the questions people actually ask most —
family, home, employer — land there rather than here.

**Memories are indexed by the questions they answer, not only by their text.**
`Memory.retrievalKey` holds two or three questions written at capture time and is
concatenated with the statement for both the vector and the keyword leg, never rendered
into a prompt. Retrieval otherwise matches a question against a statement, and those are
further apart than the statement is from unrelated memories: on this corpus "the user's
son Mateo has the nickname Ziggy" scored 0.588 against "what do I call my children?"
while "the user's name is Tarun" scored 0.656. Keying it turns the comparison into
question-to-question, which the same model scores at 0.830.

Keys are combined with the statement rather than replacing it, from a measurement worth
repeating before anyone "simplifies" it: keys alone win on relation-phrased questions but
**lose to an unrelated memory** on a direct one ("what's Mateo's nickname?" 0.715
against the distractor's 0.763). The concatenation beat the distractor on every query
tried, and beating the distractor is what decides ranking.

`MemoryKeyBackfillService` applies both to a corpus stored before either existed, which
is the usual reason an old suite and a new one disagree. Its keys are generated with each
memory's entity neighbours in view — without them a model can only write questions naming
the entity, which is the vocabulary that already worked.

A stale suite fails quietly rather than loudly, so check the fingerprint before
trusting a number: gold ids that no longer exist score as clean misses, and a suite
written before `goldGroups` replaced `goldMemoryIds` deserialises to *no* gold at all
and reports 0% recall for a retrieval path that may be working fine.
