---
name: fable-mode
description: Enforces staged execution discipline on large tasks: a written stage plan, parallel delegation where the runtime supports it, a failable verification check at each stage, and a skeptical self-review before delivery. Trigger when the user explicitly asks for thorough/systematic/deep work or when the task spans multiple files, sources, or sessions.
version: 1.0.0
author: main
tools: [filesystem, exec, subagent_spawn, subagent_yield, conversation_send, conversation_history, web_search, web_fetch, datetime, checklist]
commands: []
icon: 🎭
---
# Fable Mode

This skill encodes execution discipline for complex work: decompose before acting, delegate where the runtime allows, verify with checks that can fail, self-critique before delivery.

A note on what this is. The skill shapes the *procedure* a model follows. It does not change the model's underlying capability. Coherence across long tasks and genuine self-correction live in the weights, not in a prompt. On a model that already does these well, the skill reinforces good habits. On a weaker model, it imposes structure the model would otherwise skip, but it cannot lift the model's reasoning ceiling. Treat this as a checklist, not a capability transplant.

## When NOT to use this

If a task has one obvious correct approach and fits in a single pass, do it directly and skip this loop. Staging a trivial task wastes effort and buries the answer under ceremony. This loop earns its cost only when a one-shot attempt would plausibly miss something.

Trigger when the user explicitly asks ("do this thoroughly", "be systematic", "deep work mode") OR when the task objectively spans multiple files, multiple sources, or multiple sessions. Do NOT trigger on ordinary multi-step requests that a direct attempt handles fine.

## Core Loop

The loop is constant across domains. Only the verification artifact in step 3 changes by domain (see Domain-specific patterns).

### 1. Stage Map (before touching anything)

Write out the full stage plan before starting. Number the stages. Include a brief expected output for each. This is how you avoid discovering at stage 7 that you made a wrong assumption at stage 2. Update the map when what you learn invalidates what you planned. The map is a living document, not a contract.

Each stage should produce one verifiable artifact. If a stage produces nothing checkable, merge it with the next.

Create a checklist using the `checklist` tool with each stage as an item. Mark the first stage as `in_progress`.

Example format:
```
Stage 1: [Name] → [Expected output]
Stage 2: [Name] → [Expected output]
```

### 2. Delegate Independent Work (if the runtime supports it)

First check whether subagent tooling exists in the current runtime. It does: `subagent_spawn` and `subagent_yield` are available.

If stage N and stage M don't depend on each other, spawn them concurrently using `subagent_spawn`. Each subagent should be briefed with: its specific task, what it should produce, where to save outputs, and any relevant context from prior stages.

Good delegation: "research X while I do Y", "process these 3 files", "verify this independently". Bad delegation: splitting a single coherent thought just to use subagents.

Use `conversation_send` to push status updates from subagents back to the main conversation.

**Collect before you respond — this is not optional.** A subagent's work only reaches your reply if you wait for it. Whenever you fan out — a `tasks[]` batch or several `async=true` spawns — end that stage with **one** `subagent_yield` call that awaits every child: pass `all=true` (collects all pending children) or the explicit `runIds` list the spawn returned. `subagent_yield` *suspends* your turn; you resume automatically once the children finish, with their results delivered back to you, and only **then** do you synthesize your response from them.

Do **not** write your final answer while children are still running. If you do, their output arrives later as separate announcements you can no longer fold in, and your reply silently omits the very work you delegated — which is worse than not delegating at all. The sequence is always: spawn the independent stages → `subagent_yield` (await all) → synthesize → respond. If you also did your own work alongside them, you still yield for the children before responding, then combine everything.

### 3. Verify with a Check That Can Fail

Each stage must define a pass condition that an external artifact satisfies. Acceptable checks:
- a test that runs and passes (use `exec`)
- a file or output that provably exists in the expected shape (use `filesystem`)
- a source actually fetched and read, not assumed (use `web_fetch` or `web_search`)
- an output diffed against the stated spec

"I reviewed it and it looks right" is not a check. A model that would skip verification will also pass its own introspection. If a stage genuinely has no failable check, say so explicitly and mark its output as unverified so the gap is visible downstream.

The cost of catching an error at stage 3 is trivial; at stage 8 it is catastrophic.

If a fix at stage N invalidates a prior stage's output, re-run that stage's check before continuing. The loop goes forward and backward.

After a stage passes its check, update the checklist — mark that stage `completed` and the next stage `in_progress`.

### 4. Self-Critique Before Delivery

Before presenting final output, read it as a skeptical reviewer would. Name at least one weakness or limitation. Either fix it or flag it to the user.

Step 3 is the check that can fail. Step 4 is the judgment call about what remains weak after the check passes.

After self-critique, mark all remaining checklist items as `completed`.

---

## Domain-Specific Patterns

Each domain below is an instance of step 3: it names the failable check that fits the work.

### Software Engineering
- Read the entire relevant codebase section before writing a line (use `filesystem`)
- Write tests before (or alongside) implementation, not after
- For large changes: plan the diff, then execute it
- Failable check: tests run (use `exec`); error paths exercised, not just the happy path

### Research / Knowledge Work
- Gather sources before synthesizing. Do not write as you search
- For each claim that matters: what's the evidence? what would falsify it?
- Distinguish confirmed facts from inferences; flag the latter explicitly
- Failable check: every load-bearing claim traces to a source actually read (use `web_fetch` or `web_search`)

### Data Analysis
- Understand the data shape before writing any analysis (use `filesystem` or `exec` to profile)
- State your hypothesis before computing, not after seeing the numbers
- Check for obvious data quality issues (nulls, duplicates, outliers) first
- Failable check: data quality assertions run against the actual data and pass (use `exec`)

### Long-Running / Multi-Session Tasks
- Maintain a work log: decisions made, why, what was tried and failed
- At the start of any continuation, re-read the work log before doing anything (use `filesystem`)
- Define done criteria upfront so you know when to stop
- Failable check: done criteria are written and testable, not vibes

---

## What This Skill Doesn't Do

It doesn't make the underlying model smarter. Complex reasoning, novel synthesis, and domain expertise still depend on the model. This skill shapes *how* a model works through a problem: the approach, the discipline, the verification habits. It does not change raw capability.

When a task is genuinely beyond the model's capability, flag it rather than producing plausible-sounding wrong output.

## Example

See the skill repository at https://github.com/mrtooher/fable-mode for worked examples showing the loop catching errors that one-shot attempts miss — including a null-pointer bug in an API endpoint (software engineering), a research claim attributed to the wrong source (knowledge work), a data analysis with hidden nulls (data analysis), and a multi-session task with no done criteria (long-running).

## Edge Cases

- **All stages independent:** fan out with `subagent_spawn` (`tasks[]` batch or `async=true` each), then **one** `subagent_yield` with `all=true` (or the returned `runIds`) to await ALL children before synthesizing — never respond while children still run (see §2)
- **Sequential dependency:** Run stages in order, using outputs from prior stages as context for the next
- **Stage verification fails:** Diagnose and fix before proceeding. If the fix invalidates prior stages, re-run their checks too
- **Long-running task:** Write a work log file to `fable-mode/worklog.md` at the root of the workspace using `filesystem`. Re-read it at the start of each continuation session via `datetime` to confirm session boundaries
- **User interrupts mid-loop:** Summarise current progress from the checklist and work log, present it to the user, and offer to resume