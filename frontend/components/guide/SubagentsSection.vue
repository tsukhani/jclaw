<script setup lang="ts">
/**
 * JCLAW-292 first-section content. Mirrors docs/subagents-user-guide.md
 * in structure and tone but renders through Vue + JClaw design tokens
 * for in-app polish (cards, callouts, tables, the same prose-chat class
 * that chat.vue uses).
 *
 * Anchor ids are namespaced with `subagents-` so deep links survive
 * across deploys regardless of section order changes elsewhere.
 *
 * The component is split into discrete top-level cards rather than one
 * long prose blob: each spawn mode / context mode gets its own card so
 * the operator can scan the page like a reference, and each card carries
 * its own deep-linkable id.
 */
import GuideCallout from './GuideCallout.vue'
import {
  ChatBubbleLeftRightIcon,
  ListBulletIcon,
  RectangleGroupIcon,
} from '@heroicons/vue/24/outline'
</script>

<template>
  <article class="prose-chat text-fg-primary">
    <header>
      <h2
        id="subagents-overview"
        class="text-xl font-semibold text-fg-strong mb-2"
      >
        Subagents
      </h2>
      <p class="text-sm text-fg-muted">
        A subagent is a child agent that runs a focused task on behalf of the agent you're chatting with. The child has its own conversation, its own model context, and its own tool surface. When it finishes, its final reply comes back to the parent and to you.
      </p>
    </header>

    <p class="mt-4">
      Reach for a subagent when:
    </p>
    <ul class="mt-2 space-y-1 text-sm">
      <li>The task is well-scoped and benefits from an isolated context (long research, exploratory work, anything that would otherwise blow up the parent's context window).</li>
      <li>You want to fan out work in parallel without blocking the main conversation.</li>
      <li>You want a separate transcript that's easy to inspect on its own.</li>
    </ul>

    <!-- The simplest case ─────────────────────────────────────────── -->
    <section
      id="subagents-simplest"
      class="mt-8"
    >
      <h3 class="text-base font-semibold text-fg-strong">
        The simplest case
      </h3>
      <p class="mt-2 text-sm">
        You don't have to think about any of the parameters. Just ask:
      </p>
      <blockquote class="my-3 px-3 py-2 border-l-2 border-emerald-400/60 bg-emerald-50/40 dark:bg-emerald-950/20 text-sm italic text-fg-strong">
        "Spawn a subagent to research nose trimmers on Lazada and Shopee, then summarize."
      </blockquote>
      <p class="text-sm">
        The parent agent calls the <code>spawn_subagent</code> tool with sensible defaults: <code>mode=session</code>, <code>context=fresh</code>, synchronous (blocking), 300-second timeout. You'll see a sidebar conversation appear for the child, and when it finishes the parent reads its reply and continues.
      </p>
      <p class="mt-2 text-sm text-fg-muted">
        The rest of this guide is what you reach for when the defaults aren't quite right.
      </p>
    </section>

    <!-- Spawn modes ──────────────────────────────────────────────── -->
    <section
      id="subagents-spawn-modes"
      class="mt-10"
    >
      <h3 class="text-base font-semibold text-fg-strong">
        Spawn modes
      </h3>
      <p class="mt-2 text-sm">
        Set with the <code>mode</code> parameter on <code>spawn_subagent</code>. Pick one based on how you want the child's work surfaced.
      </p>

      <div class="mt-4 space-y-3">
        <div
          id="subagents-spawn-modes-session"
          class="border border-border rounded-lg p-4"
        >
          <div class="flex items-center gap-2 mb-2">
            <RectangleGroupIcon
              class="w-3.5 h-3.5 text-fg-muted"
              aria-hidden="true"
            />
            <span class="text-sm font-medium text-fg-strong">
              <code>mode=session</code> <span class="text-fg-muted font-normal">(default)</span>
            </span>
          </div>
          <p class="text-sm">
            The child runs in its own brand-new Conversation. It shows up as a separate row in the sidebar, with its own message history visible at <code>/chat?conversation=&lt;id&gt;</code>. The parent waits for it to finish and reads its final reply as the tool result.
          </p>
          <p class="mt-2 text-sm text-fg-muted">
            <span class="font-medium text-fg-strong">Use when:</span> you want the child's conversation to be a first-class, navigable artifact you can come back to later — long research, code generation, multi-step tool work.
          </p>
        </div>

        <div
          id="subagents-spawn-modes-inline"
          class="border border-border rounded-lg p-4"
        >
          <div class="flex items-center gap-2 mb-2">
            <ChatBubbleLeftRightIcon
              class="w-3.5 h-3.5 text-fg-muted"
              aria-hidden="true"
            />
            <span class="text-sm font-medium text-fg-strong">
              <code>mode=inline</code>
            </span>
          </div>
          <p class="text-sm">
            The child's messages get folded into the parent's conversation as a collapsible block. You see a "Subagent: &lt;label&gt;" pill where the child ran; click to expand and read the entire nested transcript inline.
          </p>
          <p class="mt-2 text-sm text-fg-muted">
            <span class="font-medium text-fg-strong">Use when:</span> the child's work is part of the conversational flow and you want to read everything in one place without navigating away. Best for short, focused subtasks.
          </p>
        </div>

        <div
          id="subagents-spawn-modes-async"
          class="border border-border rounded-lg p-4"
        >
          <div class="flex items-center gap-2 mb-2">
            <ListBulletIcon
              class="w-3.5 h-3.5 text-fg-muted"
              aria-hidden="true"
            />
            <span class="text-sm font-medium text-fg-strong">
              <code>mode=async</code>
            </span>
          </div>
          <p class="text-sm">
            The child runs in the background on its own virtual thread. The tool returns immediately with a run id; the parent agent gets control back and can keep responding to you. When the child finishes (success, failure, or timeout), an "announce card" lands in your conversation showing the child's status, label, and reply, with a "View full →" link to the child's full transcript.
          </p>
          <p class="mt-2 text-sm text-fg-muted">
            <span class="font-medium text-fg-strong">Use when:</span> the child's work is going to take a while and you want to keep talking to the parent, or you want to fan out multiple children at once. Only compatible with <code>mode=session</code> — the chat UI can't render an inline block whose end isn't yet known.
          </p>
        </div>
      </div>

      <GuideCallout
        variant="tip"
        title="Quick defaults"
      >
        <ul class="space-y-1 text-sm">
          <li>"Do this for me" → <code>session</code></li>
          <li>"Do this in the background while we keep talking" → <code>async</code></li>
          <li>"Show me what you did right here in the same chat" → <code>inline</code></li>
        </ul>
      </GuideCallout>
    </section>

    <!-- Context modes ────────────────────────────────────────────── -->
    <section
      id="subagents-context-modes"
      class="mt-10"
    >
      <h3 class="text-base font-semibold text-fg-strong">
        Context modes
      </h3>
      <p class="mt-2 text-sm">
        Set with the <code>context</code> parameter. Controls what the child knows about the parent's history and tools.
      </p>

      <div class="mt-4 space-y-3">
        <div class="border border-border rounded-lg p-4">
          <div class="text-sm font-medium text-fg-strong mb-2">
            <code>context=fresh</code> <span class="text-fg-muted font-normal">(default)</span>
          </div>
          <p class="text-sm">
            The child starts with an empty history and only its own configured tools. It knows what the <code>task</code> parameter tells it; nothing else from your conversation leaks in.
          </p>
          <p class="mt-2 text-sm text-fg-muted">
            <span class="font-medium text-fg-strong">Use when:</span> the task is self-contained and you don't want parent context to bias the child. This is also the cheapest option because no summarization runs.
          </p>
        </div>

        <div class="border border-border rounded-lg p-4">
          <div class="text-sm font-medium text-fg-strong mb-2">
            <code>context=inherit</code>
          </div>
          <p class="text-sm">
            Before the child starts, JClaw runs a synchronous summarization pass over the parent's recent turns and injects the result into the child's system prompt. The child also gets the union of the parent's enabled tools and its own.
          </p>
          <p class="mt-2 text-sm text-fg-muted">
            <span class="font-medium text-fg-strong">Use when:</span> the child needs to pick up where the parent left off — for example, "spawn a subagent to keep working on the file we were editing" or "have a subagent extend the analysis we just did."
          </p>
          <p class="mt-2 text-sm">
            If the summarization call fails, JClaw degrades to <code>fresh</code> and logs the reason. The spawn still succeeds.
          </p>
        </div>
      </div>
    </section>

    <!-- Model override ───────────────────────────────────────────── -->
    <section
      id="subagents-model-override"
      class="mt-10"
    >
      <h3 class="text-base font-semibold text-fg-strong">
        Per-spawn model override
      </h3>
      <p class="mt-2 text-sm">
        By default the child runs on the same model as the parent. Override with <code>modelProvider</code> and <code>modelId</code>:
      </p>
      <blockquote class="my-3 px-3 py-2 border-l-2 border-emerald-400/60 bg-emerald-50/40 dark:bg-emerald-950/20 text-sm italic text-fg-strong">
        "Spawn a subagent on <code>ollama-cloud</code> with model <code>qwen3-coder</code> to refactor this function."
      </blockquote>
      <p class="text-sm">
        <span class="font-medium text-fg-strong">Use when:</span> the child's task is better served by a different model than the parent's — a large-context model for research, a code-tuned model for refactoring, a cheap model for bulk classification. The override lives only on the child Conversation; the parent's model stays the same.
      </p>
      <p class="mt-2 text-sm text-fg-muted">
        The parent's behavior settings (system prompt assembly, tool grants when <code>context=inherit</code>) still apply; only the model identity changes.
      </p>
    </section>

    <!-- Async + yield ────────────────────────────────────────────── -->
    <section
      id="subagents-async-yield"
      class="mt-10"
    >
      <h3 class="text-base font-semibold text-fg-strong">
        Async plus yield (the two-stage pattern)
      </h3>
      <p class="mt-2 text-sm">
        For long-running async work where the parent eventually needs the child's reply, pair <code>spawn_subagent</code> with the companion tool <code>yield_to_subagent</code>:
      </p>
      <ol class="mt-3 space-y-2 text-sm list-decimal pl-5">
        <li>
          Parent calls <code>spawn_subagent</code> with <code>async=true, mode=session, task="..."</code>. Receives a <code>runId</code> immediately.
        </li>
        <li>Parent does whatever else is useful — talks to you, calls other tools, summarizes the spawn intent.</li>
        <li>
          When the parent needs the child's result, it calls <code>yield_to_subagent</code> with that <code>runId</code>. The parent's logical turn ends without emitting a final assistant reply.
        </li>
        <li>
          When the child terminates, JClaw delivers the child's reply back as the parent's next user-role message and resumes the parent's loop. The parent picks up the conversation seamlessly with the child's output as fresh user input.
        </li>
      </ol>
      <GuideCallout variant="note">
        <p class="text-sm">
          Without <code>yield_to_subagent</code>, the parent never gets to use the child's reply. The announce card surfaces it to <em>you</em>, not back into the parent's LLM context. Use yield when you want the parent to keep working with the result.
        </p>
      </GuideCallout>
    </section>

    <!-- Recursion limits ─────────────────────────────────────────── -->
    <section
      id="subagents-limits"
      class="mt-10"
    >
      <h3 class="text-base font-semibold text-fg-strong">
        Recursion limits
      </h3>
      <p class="mt-2 text-sm">
        To stop a subagent from spawning grandchildren that spawn great-grandchildren, JClaw enforces two caps. Both are runtime-configurable in the Settings page's <strong>Subagents</strong> section, and both fail closed: a spawn that would breach the cap is refused with a clear error before any DB rows are written.
      </p>
      <table class="mt-3 text-sm w-full">
        <thead>
          <tr class="border-b border-border">
            <th class="text-left py-2 pr-3 font-medium text-fg-strong">
              Setting key
            </th>
            <th class="text-left py-2 pr-3 font-medium text-fg-strong">
              Default
            </th>
            <th class="text-left py-2 font-medium text-fg-strong">
              Meaning
            </th>
          </tr>
        </thead>
        <tbody>
          <tr class="border-b border-border/50">
            <td class="py-2 pr-3">
              <code>subagent.maxDepth</code>
            </td>
            <td class="py-2 pr-3">
              1
            </td>
            <td class="py-2">
              How deep the parent → child → grandchild chain can go.
            </td>
          </tr>
          <tr>
            <td class="py-2 pr-3">
              <code>subagent.maxChildrenPerParent</code>
            </td>
            <td class="py-2 pr-3">
              5
            </td>
            <td class="py-2">
              How many concurrently-RUNNING children a single parent can have.
            </td>
          </tr>
        </tbody>
      </table>
      <p class="mt-3 text-sm">
        A depth limit of <code>1</code> means the top-level agent can spawn children, but those children cannot spawn further children. Bump it for explicit fan-in patterns; keep it conservative for runaway protection.
      </p>
    </section>

    <!-- Inspecting ───────────────────────────────────────────────── -->
    <section
      id="subagents-inspecting"
      class="mt-10"
    >
      <h3 class="text-base font-semibold text-fg-strong">
        Inspecting what a child did
      </h3>
      <p class="mt-2 text-sm">
        Four surfaces, each with its own audience.
      </p>

      <h4
        id="subagents-inspecting-announce"
        class="mt-4 text-sm font-semibold text-fg-strong"
      >
        1. The announce card (chat page)
      </h4>
      <p class="mt-1 text-sm">
        Lands in your conversation when an <code>async</code> subagent terminates. Shows label, terminal status (COMPLETED / FAILED / TIMEOUT), and the child's reply rendered as markdown. Includes a "View full →" link that opens the child's full transcript in the standard chat viewer (read-only — the child is no longer accepting input).
      </p>
      <p class="mt-2 text-sm">
        If the child's reply was cut off by the model's max-tokens budget, the announce card shows a small amber <em>"Reply was truncated by the model"</em> marker so you know the summary isn't complete.
      </p>

      <h4
        id="subagents-inspecting-admin"
        class="mt-4 text-sm font-semibold text-fg-strong"
      >
        2. The Subagents admin page (<code>/subagents</code>)
      </h4>
      <p class="mt-1 text-sm">
        Lists every run — RUNNING, COMPLETED, FAILED, KILLED, TIMEOUT — across all parent agents. Filters by parent agent, status, and start time. Each row links to the child's transcript. Use this when you want a fleet view across multiple parents and time ranges.
      </p>

      <h4
        id="subagents-inspecting-slash"
        class="mt-4 text-sm font-semibold text-fg-strong"
      >
        3. The <code>/subagent</code> slash command (in chat)
      </h4>
      <p class="mt-1 text-sm">
        Operator surface for the parent agent's <em>own</em> runs. Five subcommands:
      </p>
      <table class="mt-2 text-sm w-full">
        <thead>
          <tr class="border-b border-border">
            <th class="text-left py-2 pr-3 font-medium text-fg-strong">
              Command
            </th>
            <th class="text-left py-2 font-medium text-fg-strong">
              What it does
            </th>
          </tr>
        </thead>
        <tbody>
          <tr class="border-b border-border/50">
            <td class="py-2 pr-3">
              <code>/subagent list</code>
            </td>
            <td class="py-2">
              Show RUNNING and recently-terminal runs spawned by the current parent.
            </td>
          </tr>
          <tr class="border-b border-border/50">
            <td class="py-2 pr-3">
              <code>/subagent info &lt;id&gt;</code>
            </td>
            <td class="py-2">
              Detail block for one run: status, mode, context, started/ended, outcome.
            </td>
          </tr>
          <tr class="border-b border-border/50">
            <td class="py-2 pr-3">
              <code>/subagent log &lt;id&gt;</code>
            </td>
            <td class="py-2">
              Last ~50 lifecycle events for a run (spawn, complete, error, kill).
            </td>
          </tr>
          <tr class="border-b border-border/50">
            <td class="py-2 pr-3">
              <code>/subagent kill &lt;id&gt;</code>
            </td>
            <td class="py-2">
              Cooperatively cancel a RUNNING child via a checkpointed flag.
            </td>
          </tr>
          <tr>
            <td class="py-2 pr-3">
              <code>/subagent history &lt;id&gt;</code>
            </td>
            <td class="py-2">
              Inline render of the child's transcript (capped to ~20 messages, 500 chars each).
            </td>
          </tr>
        </tbody>
      </table>

      <h4
        id="subagents-inspecting-history"
        class="mt-4 text-sm font-semibold text-fg-strong"
      >
        4. The <code>sessions_history</code> tool
      </h4>
      <p class="mt-1 text-sm">
        For the parent agent itself to recall what a previous child did. Returns the full message list (role, content, tool calls/results, timestamps) for a child conversation given the run id. Useful when the parent wants to summarize across multiple historical runs, debug its own delegation pattern, or splice intermediate results into a follow-up turn. Permission-gated: the calling agent must be the run's parent.
      </p>
    </section>

    <!-- Quick reference ──────────────────────────────────────────── -->
    <section
      id="subagents-quick-reference"
      class="mt-10"
    >
      <h3 class="text-base font-semibold text-fg-strong">
        Quick reference
      </h3>
      <p class="mt-2 text-sm">
        The full parameter surface for the three subagent tools:
      </p>
      <pre class="mt-3 text-xs overflow-x-auto"><code>spawn_subagent
  task              string   required &mdash; instruction for the child
  label             string   short display name
  agentId           int      use an existing agent row instead of cloning current
  mode              string   "session" (default) | "inline" | (async via async=true)
  context           string   "fresh" (default) | "inherit"
  modelProvider     string   override child's provider
  modelId           string   override child's model
  async             bool     return run id immediately (session mode only)
  runTimeoutSeconds int      wall-clock budget, default 300

yield_to_subagent
  runId             string   required &mdash; the run id from a prior async spawn

sessions_history
  runId             string   required
  limit             int      1&ndash;200, default 100
  beforeMessageId   string   pagination cursor</code></pre>
    </section>

    <!-- Tips and gotchas ─────────────────────────────────────────── -->
    <section
      id="subagents-tips"
      class="mt-10"
    >
      <h3 class="text-base font-semibold text-fg-strong">
        Tips and gotchas
      </h3>

      <GuideCallout variant="gotcha">
        <p class="text-sm">
          <code>async=true</code> requires <code>mode=session</code>. Inline mode embeds child messages directly; returning control to the LLM before the child finishes would leave a half-written nested block dangling. The runtime rejects the combination with a clear error.
        </p>
      </GuideCallout>

      <GuideCallout variant="note">
        <p class="text-sm">
          A child gets a fresh clone of the parent's agent by default. Pass <code>agentId</code> if you want to run on a specific pre-configured agent.
        </p>
      </GuideCallout>

      <GuideCallout variant="gotcha">
        <p class="text-sm">
          <code>context=inherit</code> costs an extra LLM call for the summarization pass before the child even starts. Not free; use when you need it.
        </p>
      </GuideCallout>

      <GuideCallout
        variant="gotcha"
        title="Truncation"
      >
        <p class="text-sm">
          If the child's reply ends abruptly with a "Reply was truncated by the model" marker, the prompt fed into that turn was so large it left the model with very little output budget. Try a model with a bigger context window via <code>modelProvider</code>/<code>modelId</code>, or break the task into smaller pieces.
        </p>
      </GuideCallout>

      <GuideCallout variant="note">
        <p class="text-sm">
          Killed runs don't get an announce card. The <code>/subagent kill</code> slash-command response <em>is</em> the operator's confirmation; suppressing the announce avoids double-rendering "Killed by operator" twice.
        </p>
      </GuideCallout>

      <GuideCallout
        variant="tip"
        title="Scoping"
      >
        <p class="text-sm">
          Subagents are scoped to your login. You can't see or kill another user's runs from <code>/subagents</code> or <code>/subagent</code>.
        </p>
      </GuideCallout>
    </section>

    <!-- Pointer for power users ───────────────────────────────────── -->
    <p class="mt-10 text-xs text-fg-muted">
      For implementation details, see <code>docs/architecture-backend.md</code> in the repo, or the recent JCLAW tickets (264, 265, 266, 267, 268, 269, 270, 271, 272, 273, 274, 291) for the design notes on each capability described above.
    </p>
  </article>
</template>
