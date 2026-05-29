# Subagents — User Guide

A subagent is a child agent that runs a focused task on behalf of the agent you're chatting with. The child has its own conversation, its own model context, and its own tool surface — when it finishes, its final reply comes back to the parent (and to you).

Use a subagent when:

- The task is well-scoped and benefits from an isolated context (long research, exploratory work, anything that would otherwise blow up the parent's context window).
- You want to fan out work in parallel without blocking the main conversation.
- You want a separate transcript that's easy to inspect on its own.

This guide covers the three spawn modes, the two context modes, the optional model override, the async/yield pattern, the limits, and the four ways to inspect what a child did.

---

## The simplest case

You don't have to think about any of the parameters. Just ask:

> "Spawn a subagent to research nose trimmers on Lazada and Shopee, then summarize."

The parent agent calls the `spawn_subagent` tool with sensible defaults: `mode=session`, `context=fresh`, synchronous (blocking), 300-second timeout. You'll see a sidebar conversation appear for the child, and when it finishes the parent reads its reply and continues.

The rest of this guide is what you reach for when the defaults aren't quite right.

---

## Spawn modes

Set with the `mode` parameter on `spawn_subagent`. Pick one based on how you want the child's work to be surfaced.

### `mode=session` (default)

The child runs in its own brand-new Conversation. It shows up as a separate row in the sidebar, with its own message history visible at `/chat?conversation=<id>`. The parent waits for it to finish and reads its final reply as the tool result.

**Use when**: you want the child's conversation to be a first-class, navigable artifact you can come back to later — long research, code generation, multi-step tool work.

### `mode=inline`

The child's messages get folded into the parent's conversation as a collapsible block. You see a "Subagent: <label>" pill where the child ran; click to expand and read the entire nested transcript inline.

**Use when**: the child's work is part of the conversational flow and you want to read everything in one place without navigating away. Best for short, focused subtasks.

### `mode=async`

The child runs in the background on its own virtual thread. The tool returns immediately with a run id; the parent agent gets control back and can keep responding to you. When the child finishes (success, failure, or timeout), an "announce card" lands in your conversation showing the child's status, label, and reply, with a "View full →" link to the child's full transcript.

**Use when**: the child's work is going to take a while and you want to keep talking to the parent, or you want to fan out multiple children at once. Only compatible with `mode=session` — the chat UI can't render an inline block whose end isn't yet known.

> **Default for "do this for me"**: session.
> **Default for "do this in the background while we keep talking"**: async.
> **Default for "show me what you did right here in the same chat"**: inline.

---

## Context modes

Set with the `context` parameter. Controls what the child knows about the parent's history and tools.

### `context=fresh` (default)

The child starts with an empty history and only its own configured tools. It knows what the `task` parameter tells it; nothing else from your conversation leaks in.

**Use when**: the task is self-contained and you don't want parent context to bias the child. This is also the cheapest option because no summarization runs.

### `context=inherit`

Before the child starts, JClaw runs a synchronous summarization pass over the parent's recent turns and injects the result into the child's system prompt. The child also gets the union of the parent's enabled tools and its own.

**Use when**: the child needs to pick up where the parent left off — for example, "spawn a subagent to keep working on the file we were editing" or "have a subagent extend the analysis we just did." The summary keeps the child's context window manageable while still carrying forward what's relevant.

If the summarization call fails, JClaw degrades to `fresh` and logs the reason — the spawn still succeeds.

---

## Per-spawn model override

By default the child runs on the same model as the parent. Override with `modelProvider` and `modelId`:

> "Spawn a subagent on `ollama-cloud` with model `qwen3-coder` to refactor this function."

**Use when**: the child's task is better served by a different model than the parent's — e.g., a large-context model for research, a code-tuned model for refactoring, a cheap model for bulk classification. The override lives only on the child Conversation; the parent's model stays the same.

The parent's behavior settings (system prompt assembly, tool grants when `context=inherit`) still apply; only the model identity changes.

---

## Async + yield (the two-stage pattern)

For long-running async work where the parent eventually needs the child's reply, pair `spawn_subagent` with the companion tool `yield_to_subagent`:

1. Parent calls `spawn_subagent` with `async=true, mode=session, task="..."`. Receives a `runId` immediately.
2. Parent does whatever else is useful — talks to you, calls other tools, summarizes the spawn intent.
3. When the parent needs the child's result, it calls `yield_to_subagent` with that `runId`. The parent's logical turn ends without emitting a final assistant reply.
4. When the child terminates, JClaw delivers the child's reply back as the parent's next user-role message and resumes the parent's loop. The parent picks up the conversation seamlessly with the child's output as fresh user input.

**Use when**: the parent has work to do in parallel with the child and only needs the child's reply at a specific later point. Without `yield_to_subagent`, the parent never gets to use the child's reply (the announce card surfaces it to you, not back into the parent's LLM context).

---

## Recursion limits

To stop a subagent from spawning grandchildren that spawn great-grandchildren, JClaw enforces two caps. Both are runtime-configurable in the Settings page's **Subagents** section, and both fail closed: a spawn that would breach the cap is refused with a clear error before any DB rows are written.

| Setting key                          | Default | Meaning                                                        |
|--------------------------------------|---------|----------------------------------------------------------------|
| `subagent.maxDepth`                  | 1       | How deep the parent → child → grandchild chain can go.         |
| `subagent.maxChildrenPerParent`      | 5       | How many concurrently-RUNNING children a single parent can have. |

A depth limit of `1` means the top-level agent can spawn children, but those children cannot spawn further children. Bump it for explicit fan-in patterns; keep it conservative for runaway protection.

---

## Inspecting what a child did

Four surfaces, each with its own audience.

### 1. The announce card (chat page)

Lands in your conversation when an `async` subagent terminates. Shows label, terminal status (COMPLETED / FAILED / TIMEOUT), and the child's reply rendered as markdown. Includes a "View full →" link that opens the child's full transcript in the standard chat viewer (read-only — the child is no longer accepting input).

If the child's reply was cut off by the model's max-tokens budget, the announce card shows a small amber "Reply was truncated by the model" marker so you know the summary isn't complete (JCLAW-291).

### 2. The Subagents admin page (`/subagents`)

Lists every run — RUNNING, COMPLETED, FAILED, KILLED, TIMEOUT — across all parent agents. Filters by parent agent, status, and start time. Each row links to the child's transcript. Use this when you want a fleet view across multiple parents and time ranges.

### 3. The `/subagent` slash command (in chat)

Operator surface for the parent agent's *own* runs. Five subcommands:

| Command                        | What it does                                                              |
|--------------------------------|---------------------------------------------------------------------------|
| `/subagent list`               | Show RUNNING + recently-terminal runs spawned by the current parent.      |
| `/subagent info <id>`          | Detail block for one run — status, mode, context, started/ended, outcome. |
| `/subagent log <id>`           | Last ~50 lifecycle events for a run (spawn, complete, error, kill).       |
| `/subagent kill <id>`          | Cooperatively cancel a RUNNING child via a checkpointed flag.             |
| `/subagent history <id>`       | Inline render of the child's transcript (capped to ~20 messages, 500 chars each). |

### 4. The `sessions_history` tool

For the parent agent itself to recall what a previous child did. Returns the full message list (role, content, tool calls/results, timestamps) for a child conversation given the run id. Useful when the parent wants to summarize across multiple historical runs, debug its own delegation pattern, or splice intermediate results into a follow-up turn. Permission-gated: the calling agent must be the run's parent.

---

## Quick reference

```
spawn_subagent
  task             string   required — instruction for the child
  label            string   short display name
  agentId          int      use an existing agent row instead of cloning current
  mode             string   "session" (default) | "inline" | (async via async=true)
  context          string   "fresh" (default) | "inherit"
  modelProvider    string   override child's provider
  modelId          string   override child's model
  async            bool     return run id immediately (session mode only)
  runTimeoutSeconds int     wall-clock budget, default 300

yield_to_subagent
  runId            string   required — the run id from a prior async spawn

sessions_history
  runId            string   required
  limit            int      1–200, default 100
  beforeMessageId  string   pagination cursor
```

---

## Tips and gotchas

- **`async=true` requires `mode=session`**. Inline mode embeds child messages directly; returning control to the LLM before the child finishes would leave a half-written nested block dangling. The runtime rejects the combination with a clear error.
- **A child gets a fresh clone of the parent's agent by default**. Pass `agentId` if you want to run on a specific pre-configured agent.
- **`context=inherit` costs an extra LLM call** for the summarization pass before the child even starts. Not free; use when you need it.
- **Truncation isn't always the model giving up**. If the child's reply ends abruptly with a "Reply was truncated by the model" marker, the prompt fed into that turn was so large it left the model with very little output budget. Try a model with a bigger context window via `modelProvider`/`modelId`, or break the task into smaller pieces.
- **Killed runs don't get an announce card**. The `/subagent kill` slash-command response *is* the operator's confirmation; suppressing the announce avoids double-rendering "Killed by operator" twice.
- **Subagents are scoped to your login**. You can't see or kill another user's runs from `/subagents` or `/subagent`.

---

## Where to read more

- `docs/architecture-backend.md` — how spawns are wired through the runner, the registry, and the lifecycle event log.
- Recent JCLAW tickets (264, 265, 266, 267, 268, 269, 270, 271, 272, 273, 274, 291) — design notes for each capability described above.
