---
description: Adversarially verified static audit of app/ Java on two axes — clean code (OOP, SOLID, GRASP, DRY, Java 25 type structure) and what each code path costs (N+1, unbounded state, leaks, hot-path blocking, allocation). Checks open Jira issues first so known defects are not re-found, reviews in lanes with a skeptic per unit, and ends in a ranked, deduplicated proposal of remediation epics and stories. Files nothing without approval.
argument-hint: "[pathspec ...] [max-loc-per-unit] [--lanes N] [--max-output TOKENS] [--offline] [--no-backlog]   (default: app 3000 --lanes 6)"
---

# Code audit

Answer one question: **where is `app/`'s Java structurally weak or needlessly expensive, and what remediation work does that add to Jira?** The `code-audit` workflow (`.claude/workflows/code-audit.js`) reviews; its last step, the shared `audit-backlog` workflow, turns what survives into epics and stories. This command plans the run, launches it, checks what comes back and presents the backlog. Use `/usr/bin/git` for any git invocation.

**Arguments:** `$ARGUMENTS`. Empty means all of `app/` at 3,000 lines per unit in 6 lanes. Pathspecs narrow the scope and may exclude (`app ':!:app/services/**'`); a bare number sets the unit size; `--lanes N` sets how many agents run at once; `--max-output TOKENS` (`400000` or `400k`) caps what the run's agents may write; `--offline` skips the Jira lookup; `--no-backlog` stops at the findings. Reject anything else with a clear message.

## What this audit owns

| Concern | Owner |
|---|---|
| OOP/OOD, SOLID, GRASP, DRY, encapsulation, naming, Java 25 type structure (records, sealed hierarchies, pattern switches) | **this command** |
| What one code path costs: N+1 and per-iteration queries, state that grows without bound, resource leaks, blocking on hot paths, redundant allocation, algorithmic cost, missed memoization, JVM lock contention, virtual-thread hazards | **this command** |
| Timeouts, retries, breakers, bulkheads, backpressure, rate limits, pool sizing, idempotency, delivery, dual writes, cache correctness, DB locking, query plans and indexes, tail latency, tracing | `/resilience-audit` |
| Comment, spelling, inline-name and one-line idiom residue; provably dead code; unused deps — and applying those fixes | `/quality` |
| Security | `/vulnhunt` |
| Measured heap and latency under load | `/loadtest-audit` |
| Estimating the filed stories and placing them in sprints | `/backlog-grooming` |

Every agent prompt carries the audit split as the workflow's `OUT_OF_SCOPE` text, and `resilience-audit.js` carries the mirror image: when the boundary moves, edit both.

## Jira first, so known work is not paid for twice

Before any agent runs, the plan builder reads every open JCLAW issue and attaches each one to the units whose files it names — by path, or by a class name with two or more capitals, since a bare `Agent` or `Task` would tie every issue to every unit. A unit lists at most twelve, those naming most of its files. Then:

- **Reviewers** see their unit's filed issues and write no finding a filed issue already plans to fix. Where one is still visibly present they add a `STILL OPEN: <key> — <file>:<lines>` line instead, which needs no skeptic; the report lists those confirmations, so the operator sees which filed stories still bite.
- **Skeptics** reject a finding a listed issue already covers.
- **The backlog step** reads the open epics before proposing any, gives each story writer the issues filed against its findings' files, and runs a last per-story Jira search for whatever the class-name match missed.

## Division of labor

Every subagent starts with about 27k tokens of injected instructions (`AGENTS.md` is 106 KB), so the plan spends agents only where judgment happens:

| Role | Does | Full `app/` (72 units) |
|---|---|---|
| This session | plans, launches, checks coverage, re-reads every critical and high, presents the backlog | 1 |
| Lanes | each takes the next unit from the queue and runs its reviewer, then its skeptic | 6 at once by default |
| Reviewer | reads one unit fully, writes finding cards | 72, session model |
| Skeptic | re-reads the cited code and tries to refute each card | ≤ 72 (skipped when the reviewer clears a unit), `opus` |
| Parser | turns adjudicated cards into JSON, in the workflow script | no agent |
| Extractor | only for a unit whose prose the parser cannot read | usually 0–2, `opus` low effort |
| Synthesizers | one per axis, over every surviving finding | 2, `opus` |
| Backlog | reads open epics, clusters findings into epics, writes stories and checks each against Jira — a writer per epic with Medium-or-up findings, a checker per epic, in lanes | 2 + up to 2 per epic, `opus` |

That is at most about 146 agents for the review and synthesis of the whole tree, plus the backlog's handful, never more than the lane count in flight. Lanes bound the load on the machine, not the total: every unit still costs what it costs, and only the agents removed above, and the filed defects reviewers no longer re-find, lower the bill.

Nothing inside a workflow can see the usage limit. The one lever that acts before it is an output cap — `--max-output`, or a `+500k` target on the turn, which wins when both are set: the run stops before starting a unit it cannot afford. It counts only what agents write, never the code they read, so set it from a measured cost, not a guess. Without a cap, the run stops only once agents start failing — as many in a row as there are lanes — which means the limit has already been hit; it then skips synthesis and the backlog and returns `remainingPlan`, so the failure does not run through the rest of the queue.

**Roll out in two runs.** The first full run on a changed tree should calibrate before it commits:

1. `/code-audit app/services` — 25 units, at most 52 review agents. Read `stats.outputTokensThisTurn / stats.unitsAudited` for the cost per unit, and judge whether the findings are worth it. File its approved backlog before step 2, so step 2's Jira lookup sees those stories.
2. `/code-audit app ':!:app/services/**' --max-output <48 × step 1's output per unit × 1.2>` — the other 48 units, at most 98 review agents — once that cost fits what is left of the usage window. If it does not, split the remainder again by package.

A narrow scope (one package, one file) needs no calibration.

## 1. Plan the units

The workflow cannot read the filesystem or Jira, so it takes the unit plan, with the open issues attached, as `args`. Build it with the tracked builder, never by hand:

```bash
node .claude/audit/plan.mjs <pathspec ...> <max-loc> [--offline] > <scratchpad>/code-audit-plan.json
```

Each unit is a list of slices — a line range of one package's `git ls-files` listing — packed in package order up to the size cap, so the reviewer lists exactly the files the plan counted. The vendored `app/com/aspose/**` is excluded. The builder reads Jira with the token the `jira-confluence` MCP server uses and never prints it; if that read fails, rerun with `--offline` and say that the run is not checking filed work up front (the backlog's per-story search still runs).

State the plan in one line before launching: units, files, lines, open issues checked and attached, the review agent ceiling (two per unit plus two) and the lane count. On 2026-10-17 all of `app/` was 72 units / 899 files / 175,878 lines, with 111 of 260 open issues naming its files.

## 2. Run it

Call the Workflow tool with `scriptPath: ".claude/workflows/code-audit.js"` and `args` set to the plan object exactly as the builder printed it, plus `date` (today, `YYYY-MM-DD`) and `head` (`/usr/bin/git rev-parse --short HEAD`) for the stories' provenance, `lanes` when `--lanes` was given, `maxOutputTokens` (an integer) when `--max-output` was, and `backlog: false` for `--no-backlog` — a JSON value, not a string. Invoking this command is the opt-in the Workflow tool requires. The workflow refuses a plan whose units do not add up to its own totals, which is how a copy that dropped or altered a unit is caught.

**If it halts** (`stats.halted` is set) or stops partway:
- **Same session:** relaunch with `resumeFromRunId` and the same `scriptPath` and `args`. Completed agents return from cache, the failed and unstarted units run, and synthesis and the backlog then cover the whole plan. Do not edit the scripts between a run and its resume unless you mean the remaining work to see the change.
- **A later session:** run the workflow with `remainingPlan` as `args` — it is a complete plan with its own totals and known issues. Its backlog covers only those units, and its Jira check sees whatever the first part filed.

## 3. Check before presenting

- **Coverage.** Name every unit in `stats.unitsFailed` and the count in `stats.unitsNotStarted` — an unaudited unit is not clean. A non-zero `stats.parsedBy.agent` is fine; a large one means the cards drifted from the format, so look at why.
- **Every critical and high.** Open the cited lines yourself before presenting the story that carries it. A finding whose file, lines or quoted evidence does not match the code is corrected or dropped, and the report says which.
- **The backlog's own gaps.** `backlog.gaps` names any finding the clustering lost or doubled and `backlog.failedEpics` any epic whose stories were not written; place those by hand or say they are unfiled.

## 4. Present the backlog

Save `backlog` as JSON to `docs/spikes/audits/code-audit-<date>-backlog.json` (gitignored, so it outlives the session) and give its path. Then show, in this order:

1. One coverage line: scope, units audited of planned, lines reviewed, findings surviving and rejected, open issues checked, still-open confirmations, and output tokens per unit.
2. **Epics, ranked:** `rank | epic (key, or NEW) | stories | top priority | why this epic`.
3. **Stories, ranked:** `rank | priority | summary | epic | Jira check (new / extends KEY) | findings | quick win | AFK candidate`. Low findings never get stories of their own: each epic's are folded into one `cleanup` story listing every finding with its own fix, unless a writer attached one to the Medium-or-up change it belongs to. A **defect** — a finding whose card says the code misbehaves today — never folds, whatever its severity, and the coverage line counts them (`stats.defects`). Severity is the consequence of a real finding, never the skeptic's confidence in it: doubt rejects a finding, it does not shrink one. An AFK mark survives only on a quick win touching at most two files that is not a structural refactor; it is a shortlist to pick from, never a default.
4. **Already filed:** the `covered` stories, each as the open issue it will comment on, and the `STILL OPEN` confirmations grouped by issue — filed work the code still shows.
5. Unfiled findings with their reasons, then the two axis headlines from the synthesis in one line each.

Then ask what to file: everything, a rank cut, or named refs, and which AFK candidates get the `afk` label. On approval, follow `.claude/commands/audit-file.md` with the saved path. This command never edits `app/` and never writes to Jira itself.
