---
description: Adversarially verified static audit of how app/ behaves when dependencies are slow or failing, messages arrive twice, writers race and load rises — timeouts, retries with jitter, breakers, bulkheads, backpressure, rate limits, pooling, idempotency, delivery guarantees, dead letters, dual writes, consistency, locking, query shape, cache correctness, tail latency, tracing and SLOs. Checks open Jira issues first, scores 30 concepts, and ends in a ranked, deduplicated proposal of remediation epics and stories. Files nothing without approval.
argument-hint: "[group-id ...] [--offline] [--no-backlog]   (default: all nine groups)"
---

# Resilience audit

Answer one question: **when a dependency is slow or down, a message is delivered twice, two writers race, or load rises, does JClaw's backend behave correctly — and what remediation work does that add to Jira?** The `resilience-audit` workflow (`.claude/workflows/resilience-audit.js`) reviews; its last step, the shared `audit-backlog` workflow, turns what survives into epics and stories. This command launches it, checks what comes back and presents the backlog. It reads code and never measures. Use `/usr/bin/git` for any git invocation.

**Arguments:** `$ARGUMENTS`. Empty runs all nine groups. Group ids from the table below narrow it; `--offline` skips the Jira lookup; `--no-backlog` stops at the scorecard and findings. Reject an unknown id with the list of valid ones.

## The concepts, translated to one node

The checklist assumes a distributed system. JClaw is one JVM with one embedded H2 file, no broker and no replicas, so each concept is reviewed where it actually lives here, or marked not applicable:

| Group id | Concepts | Where it lives in JClaw |
|---|---|---|
| `llm-calls` | timeouts, retry with backoff + jitter, circuit breakers, rate limiting, graceful degradation | LLM, embedding and decision-provider calls; provider fallback |
| `local-deps` | timeouts, retries, circuit breakers, bulkheads, graceful degradation | MCP servers, ACP harnesses, the seven sidecars, the browser |
| `external-apis` | timeouts, retries, rate limiting, circuit breakers, graceful degradation | Slack/Telegram/WhatsApp sends and downloads; search, scrape, scanners, image/video generation, printing |
| `load-isolation` | backpressure, bulkheads, rate limiting, connection pooling | webhook ingress, conversation queues, SSE, executors, OkHttp and Hikari pools |
| `delivery` | idempotency keys, at-least-once delivery, dead-letter queues | webhook redelivery, outbound delivery, task fires, recovery jobs; the dead-letter analog is a visible terminal state |
| `consistency` | outbox, change data capture, strong vs eventual, optimistic and pessimistic locking, hot partitions | DB + Lucene/file/in-process dual writes; derived-index sync; lost updates with no `@Version`; hot rows |
| `data-access` | query plans, index selectivity, write and read amplification | JPQL, `@Index`, LIKE scans, Lucene per-message writes, retention jobs |
| `caching` | cache invalidation, stampede protection | Caffeine caches, `ProviderRegistry`, catalogs, discovery, probes |
| `latency-observability` | p95/p99, tail latency amplification, correlation IDs + tracing, SLOs / error budgets | fan-out on a turn, `LatencyStats` coverage, OTel thread hops, event-row correlation |

Not applicable, reported as such with the reason: **consistent hashing** and **sharding keys** (nothing is partitioned across nodes) and **replication lag** (no replicas; its analog, a cache or index lagging the database, is reviewed under `consistency` and `caching`).

## What this audit owns

| Concern | Owner |
|---|---|
| Everything in the table above | **this command** |
| OOP/OOD, SOLID, GRASP, DRY, Java 25 structure; what one code path costs (N+1, unbounded state, leaks, hot-path blocking, allocation, algorithmic cost, missed memoization, JVM lock contention, virtual-thread hazards) | `/code-audit` |
| Mechanical residue and applying it | `/quality` |
| Security | `/vulnhunt` |
| Any number: latency percentiles, pool saturation, query plans | `/loadtest-audit`, or an `EXPLAIN`, after this report |
| Estimating the filed stories and placing them in sprints | `/backlog-grooming` |

Every agent prompt carries this split as the workflow's `OUT_OF_SCOPE` text, and `code-audit.js` carries the mirror image: when the boundary moves, edit both. The pairs that sit closest are split this way — cache size and eviction belong to `/code-audit`, cache correctness here; N+1 there, plans and indexes here; JVM monitor contention there, row locks and lost updates here; an unclosed response there, pool sizing and a client built outside `HttpFactories` here; blocking as CPU cost there, a wait with no timeout here.

## Jira first, so known work is not paid for twice

The groups are concerns, not files, so every reviewer sees every open JCLAW issue (`summary`, status, epic) before it starts. A defect a filed issue already plans to fix still gets a finding card, marked `covered_by: <key>`, and the skeptic judges it like any other — a title that merely sounds related is no reason to drop a bug. Covered findings never fold into a cleanup story; the writer groups them by issue, and the backlog's Jira check reads that issue's description: `covered` stands only when the issue plans this fix, and records whether the description lists these sites. A confirmed cover is filed as evidence on the issue ("still present", or "sites to add to this issue's scope"); a cover that does not hold becomes `extends` or `new`. The backlog step also reads the open epics before proposing any, gives each story writer the open issues, and runs a last per-story Jira search.

## 1. Run it

Read the open issues with the tracked builder — never by hand; it uses the token the `jira-confluence` MCP server uses and never prints it:

```bash
node .claude/audit/plan.mjs --known-only > <scratchpad>/resilience-known.json
```

Call the Workflow tool with `scriptPath: ".claude/workflows/resilience-audit.js"` and `args` an object: `groups` (the named group ids, when any), `known` and `openIssuesChecked` from that file, `date` (today, `YYYY-MM-DD`), `head` (`/usr/bin/git rev-parse --short HEAD`), and `backlog: false` for `--no-backlog` — a JSON value, not a string. With `--offline`, or if the builder's Jira read fails, leave `known` out and say that the run is not checking filed work up front. Invoking this command is the opt-in the Workflow tool requires.

A full run is 28 agents — three per group plus one synthesizer — plus the backlog's two and two per epic; at most nine run at once, one per group. Review agents inherit the session model; every other stage is pinned to `opus`. If the run stops partway, relaunch with `resumeFromRunId` and the same `scriptPath` and `args`: completed agents return from cache.

## 2. Check before presenting

- **Coverage.** A group in `stats.groupsDropped` was not reviewed, and its concepts are listed in `stats.conceptsUnreviewed`; say so and offer to rerun just those groups.
- **Every critical and high.** Open the cited lines yourself and follow the path far enough to confirm the scenario before presenting the story that carries it. A finding whose file, lines or evidence does not match the code is corrected or dropped, and the report says which.
- **The backlog's own gaps.** `backlog.gaps` and `backlog.failedEpics` name findings the backlog did not place; place them by hand or say they are unfiled.

## 3. Present the backlog

Save `backlog` as JSON to `docs/spikes/audits/resilience-audit-<date>-backlog.json` (gitignored, so it outlives the session) and give its path. Then show, in this order:

1. The headline, then the scorecard: one row per concept, all 30, with its status (`sound`, `gaps`, `needs-measurement`, `not-applicable`) and its one-line summary.
2. One coverage line: groups run and dropped, findings surviving and rejected, open issues checked, findings claimed as covered.
3. **Epics, ranked:** `rank | epic (key, or NEW) | stories | top priority | why this epic`.
4. **Stories, ranked:** `rank | priority | summary | epic | Jira check (new / extends KEY) | findings | quick win | AFK candidate`. Each epic's Low findings are folded into one `cleanup` story, and the AFK mark is narrowed the same way as in `/code-audit`.
5. **Already filed:** the `covered` stories, each as the open issue it will comment on and whether its sites are listed there.
6. The `needsMeasurement` list, each with how to settle it. Offer to run the `EXPLAIN`s read-only against the live H2 and to hand the load questions to `/loadtest-audit`. Measurements are not filed as stories unless the operator asks.

Then ask what to file: everything, a rank cut, or named refs, and which AFK candidates get the `afk` label. On approval, follow `.claude/commands/audit-file.md` with the saved path. This command never edits `app/` and never writes to Jira itself.
