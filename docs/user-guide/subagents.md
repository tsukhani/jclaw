# Subagents

Until now, every chat turn has been one user talking to one agent. **Subagents** are the first way to break that 1:1 pattern. The agent you are chatting with can spawn child agents that do focused work on its behalf. Each child has its own conversation, its own model context, and its own tools. When it finishes, its final reply comes back to the parent, and to you.

Use a subagent when:

- The task is well-scoped and benefits from an isolated context. Examples: long research, exploratory work, anything that would otherwise crowd the parent's context window.
- You want to fan out work in parallel without blocking the main conversation.
- You want a separate transcript that is easy to inspect on its own.

This section covers the three spawn modes, the two context modes, the optional model override, the async-plus-yield pattern, the limits, and the five ways to inspect what a child did. It also covers what changes when a scheduled task starts the subagent, and how to follow a coding run while it works. Subagents are one of three "outside-this-turn" abstractions. [Tasks](/guide#tasks) and [Reminders](/guide#reminders) are the other two. For the side-by-side comparison, jump to [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders).

## The simplest case

You do not have to think about any of the parameters. Just ask:

> "Spawn a subagent to research nose trimmers on Lazada and Shopee, then summarize."

The parent agent picks sensible defaults: a new conversation of its own, no inherited context, blocking until done, with a 5-minute idle budget (or whatever `subagent.defaultRunTimeoutSeconds` is set to in Settings → **Subagents**). You will see the child appear on the [Chat](/chat) page as a row in the subagent list under the chat header. When it finishes, the parent reads its reply and continues.

The rest of this section is what you reach for when the defaults are not quite right.

## Spawn modes

Pick a mode based on **how you want the child's work surfaced**.

### `mode=session` (default)

The child runs in its own brand-new conversation. It shows up as a row in the subagent list on the [Chat](/chat) page. The row expands to the child's own message history. The parent waits for it to finish and reads its final reply as the tool result.

**Use when:** you want the child's conversation to be a first-class, navigable artifact you can come back to later. Examples: long research, code generation, multi-step tool work.

### `mode=inline`

The child's messages get folded into the parent's conversation as a collapsible block. You see a `Subagent: <label>` pill where the child ran. Click it to expand and read the entire nested transcript inline.

**Use when:** the child's work is part of the conversational flow and you want to read everything in one place without navigating away. Best for short, focused subtasks.

Inline needs a conversation to fold the child into. A [task fire](/guide#subagents-task-fires) has none, so there an inline request runs as `session`.

### `mode=async`

The child runs in the background. The parent gets control back immediately and can keep responding to you. When the child finishes (success, failure, or timeout), its row in the chat's **subagent list** (the shade under the chat header) shows the outcome: status, how long ago it ended, and for a failed, timed-out, or killed run, the reason. No announce card lands in your conversation unless the parent ends its turn to wait for the run with `subagent_yield` (see [Async plus yield](/guide#subagents-async-yield)).

**Use when:** the child's work will take a while and you want to keep talking to the parent, or you want to fan out multiple children at once.

:::tip Quick defaults
- "Do this for me" → `session`
- "Do this in the background while we keep talking" → `async`
- "Show me what you did right here in the same chat" → `inline`
:::

## Context modes

Context modes control **what the child knows about the parent's history and tools**.

### `context=fresh` (default)

The child starts with an empty history and only its own configured tools. It knows what the `task` parameter tells it. Nothing else from your conversation leaks in.

**Use when:** the task is self-contained and you do not want parent context to bias the child. This is also the cheapest option because no summarization runs.

### `context=inherit`

Before the child starts, JClaw runs a brief summarization pass over the parent's recent turns. It injects the result into the child's system prompt. The child also gets the union of the parent's enabled tools and its own.

**Use when:** the child needs to pick up where the parent left off. For example: "spawn a subagent to keep working on the file we were editing", or "have a subagent extend the analysis we just did."

If the summarization call fails, JClaw falls back to `fresh` and notes the reason. The spawn still succeeds.

Inside a [task fire](/guide#subagents-task-fires) there is no conversation to summarize. The child still gets the union of tools, but no summary.

## Per-spawn model override

By default, the child runs on the same model as the parent. In a [task fire](/guide#subagents-task-fires), that is the model the task is pinned to, or the agent's model when the task has no pin. You can override the model for a single spawn:

> "Spawn a subagent on `ollama-cloud` with model `qwen3-coder` to refactor this function."

**Use when:** the child's task is better served by a different model than the parent's. Examples: a large-context model for research, a code-tuned model for refactoring, a cheap model for bulk classification. The override lives only on the child's conversation. The parent's model stays the same.

The parent's behavior settings (system prompt, granted tools when `context=inherit`) still apply. Only the model identity changes.

## Async plus yield (the two-stage pattern) {#async-yield}

For long-running async work where the parent eventually needs the child's reply, pair `subagent_spawn` with the companion tool `subagent_yield`:

1. Parent calls `subagent_spawn` with `async=true, mode=session, task="..."`. It receives a `runId` immediately.
2. Parent does whatever else is useful. For example, it can talk to you, call other tools, or summarize the spawn intent.
3. When the parent needs the child's result, it calls `subagent_yield` with that `runId`. The parent's turn ends without emitting a final assistant reply. A turn can wait only on a run started from its own conversation. A `runId` from another conversation is refused, and the turn carries on.
4. When the child terminates, JClaw delivers the child's reply back as the parent's next user-role message and resumes the parent's loop. The parent picks up the conversation seamlessly with the child's output as fresh user input.

:::note
Without `subagent_yield`, web chat shows the result in the subagent list. On Telegram, Slack or WhatsApp it also arrives as a message in the chat. The parent reads it as something it already said, so it can talk about the result later, but it does not act on it by itself. With yield, the announce card is the parent's next input. Use yield when you want the parent to keep working with the result.
:::

**Groups and guests.** A background result reaches the chat word for word, before the parent sees it. So `async=true` runs in the background only when the owner reads the chat alone: web chat, or the owner's private chat on a channel. In a group, or in a chat with anyone other than the owner, the child runs in the foreground instead. The parent gets the reply with a `note` and writes the answer itself. A subagent's own turn runs `async=true` in the foreground in the same way, because nothing would read a background result there. A `tasks` batch still runs in parallel there. If a background run fails anywhere except the owner's private chat, the chat gets "A background task did not finish. Ask me if you want the details." instead of the error.

**Batch fan-out.** `tasks` starts one async child per entry in a single call. An entry is a task string, or an object `{task, label, agentId}` that gives that child its own label and agent. Mode, context, runtime, model, and run timeout apply to the whole batch. The call returns `run_ids` at once, plus `skipped` for any child that failed to start. Collect the batch with one `subagent_yield` call: pass `runIds`, or pass `all: true` for every uncollected async child in this conversation, whether it came from `tasks` or from `async=true`. A batch collect does not suspend the turn. It waits for every child and returns `{results: [...]}` inline, with each child's `run_id`, `status`, and whole `reply`. A child it was already waiting for when it ended posts no announce card and sends nothing to the chat. A run id that was already collected returns its saved reply again when the run belongs to this conversation. Any other unknown id comes back as `UNKNOWN` without failing the rest. `all: true` with nothing left to collect says so in a `note`, and lists this conversation's ten newest finished runs under `finished` (id and status only), so the parent can read back a reply that never reached it. If the turn stops while the collect waits (you press Stop, or the chat page closes), the wait ends at once and every child stays collectable by the next `subagent_yield`. A `subagent_yield` with one batch child's `runId` waits for that child the same way and returns its result inline, without suspending the turn.

**Inside a subagent.** A subagent's own turn never ends to wait for a run, because nothing would resume it. There, `async=true` runs in the foreground (see **Groups and guests** above), and `subagent_yield` with a `runId` waits only for a `tasks` batch child and returns its result inline. For a run that has already finished it returns the saved result. For any other run it answers that a subagent cannot wait on that run from its own turn, and the run's announce card is left as it was. If a subagent's turn still ends waiting on a run, the subagent is recorded as failed with the reason "The subagent stopped its turn to wait for another run and gave no reply."

**Inside a task fire.** A fire has no conversation to resume, so an `async` spawn there is collected inline too. `subagent_yield` with its `runId` waits for the child and returns the same result a blocking spawn would, and the fire carries on without an announce card. A task fire needs the `runId` and refuses a `conversationId` alone, and `all: true` collects every uncollected async child of the fire. Neither inline collect uses yield's `timeoutSeconds`; each child is bounded by its own run timeout.

A run that a fire starts also has a different owner and a different lifetime. The next section covers both.

## Subagents inside a task fire {#task-fires}

A [task](/guide#tasks) can tell its agent to spawn subagents. When the task fires, the agent starts them the same way it does in a chat. A fire runs outside any conversation, though, and that changes four things.

### The run belongs to the task run

A run that a chat starts is filed under that chat. A run that a fire starts is filed under that fire's **task run**. It has no parent conversation, so no chat shows it: it gets no row in a subagent list, no **Coding run** panel, and no announce card.

You find it in these places:

- **The task run's trace panel.** Expand the task on the [Tasks](/tasks) page and click the run. The spawn call and its result are part of the trace. A coding run is also listed at the top under **Coding runs**, with its log.
- **The [Dashboard](/).** The **Recent Activity** panel has a **Coding runs** view. A run that a task started shows *task NAME* there. This view lists coding runs only.
- **The [Subagents](/subagents) page.** The run's row shows *task NAME* in the **Conversation** column.

### The run is deleted with the task run

A task run owns the runs it started, the same way a chat owns the runs it started. When the task run goes, JClaw deletes its subagent runs with it. A task run goes in four ways:

- You delete the task.
- You click **Reset stats** on the [Tasks](/tasks) page, which deletes finished run history.
- The task fires again and its oldest run drops out of the [10 most recent](/guide#tasks-retention).
- The retention sweep removes a finished task.

Deleting a run removes its record, its transcript, and the temporary agent that was made for it. JClaw first stops a run that is still going. Two things stay: the folder a coding run worked in, and any agent of your own that the run used through `agentId`.

:::note
This keeps task history bounded. A task that starts a subagent on every fire does not leave one run behind for each fire. It also means a run's log does not outlive its task run. To keep a result, have the task write it to a file or deliver it to a channel.
:::

### What behaves differently

| Setting | In a chat | In a task fire |
|---------|-----------|----------------|
| `mode=inline` | Folds the child into the conversation. | Runs as `session`, because there is no conversation to fold the child into. The agent gets the same reply, with a `note` that says the mode changed. |
| `context=inherit` | Summarizes the parent's recent turns and grants the union of tools. | Grants the union of tools only. There are no conversation turns to summarize. |
| Default model | The model the parent conversation is on. | The model the task is pinned to, or the agent's model when the task has no pin. |
| Tools | The child agent's own tools. | The same, cut down to the task's tool list when the task has one. The list follows a child of that child too. |
| `async=true` | The outcome shows in the chat's subagent list, or returns through `subagent_yield`. | `subagent_yield` waits and returns the result inline. See [Async plus yield](/guide#subagents-async-yield). |

### Trust follows the task

A task's tool list limits JClaw's own tools. It cannot limit what an [external coding harness](/guide#subagents-acp-harness) does once it runs, because the harness is a separate program. To stop a task from starting one, leave `subagent_spawn` out of its tool list.

A child that a fire starts is trusted as much as the fire is, and no more. The fire's trust comes from the task's **Origin**, the channel the task was created from. See [Editing a task's instructions](/guide#tasks-editing-a-task-s-instructions). A task created from Telegram therefore cannot gain your web chat's trust by spawning a subagent.

## External coding harness (`runtime=acp`) {#acp-harness}

By default, a child runs on JClaw's own native agent loop. You can instead delegate the child to an **external coding harness**. That is a standalone CLI agent such as [Pi](https://github.com/pi-labs/pi), Claude Code, or the Codex CLI. Pass `runtime:"acp"` to do this. JClaw launches the operator-configured harness command as a subprocess and captures its output as the child's reply. How the two talk is set by `subagent.acp.mode`: **batch** (the default) hands the `task` over on stdin and reads stdout when the harness exits. **json** streams the harness's line protocol as it runs. **rpc** opens a bidirectional session so the harness's mid-run permission prompts route through JClaw's approval gate (a harness that cannot do that falls back to one-way streaming). A harness that speaks ACP natively over stdio is driven over that protocol directly, whichever mode is set.

**Use when:** the child's job is better handled by a purpose-built coding agent with its own tools and sandbox than by JClaw's native loop. For example: a large refactor, or a repo-wide edit you would rather run through Pi.

### Operator setup

1. **Point JClaw at the harness binary.** Set `subagent.acp.command` (Settings → **Coding**, or a config key) to the harness command. Use an **absolute path** so it resolves regardless of the server's working directory:

   ```text
   subagent.acp.command = /usr/local/bin/pi
   ```

   The command is whitespace-split into an argv, so fixed flags are fine (`/usr/local/bin/pi --headless`). It is read from config **only**, never from the model. This is why a subagent cannot steer JClaw into running arbitrary shell.

   Alongside it, `subagent.acp.harness` names the adapter for that CLI: `pi`, `claude`, `codex`, `gemini`, `opencode`, `antigravity`, or `generic` (the default). And `subagent.acp.mode` picks `batch` (the default), `json`, or `rpc`. Both are checked up front when a spawn is attempted. A value outside those sets refuses the spawn with an error naming the allowed values, rather than silently falling back. Settings → **Coding** can also auto-detect the harnesses installed on the server and fill in the command and adapter for you in one click. Settings → **Coding** also has an **acp.mode** control for `subagent.acp.mode`, with the choices *batch (default)*, *json* and *rpc*.

   **Permission flags.** In `json` and `rpc` modes, JClaw appends the adapter's default permission flags to the command. Only Claude Code has defaults: `--allowedTools Read,Edit,Write,Glob,Grep`. These keep file editing and search but leave out the shell. `subagent.acp.permissionArgs` replaces the defaults with its own flags, split on whitespace. The value `none` sends no flags at all. `subagent.acp.permissionArgs` has no Settings field; set it with `POST /api/config`. `batch` mode and harnesses driven over native ACP get no permission flags.

   **Optionally, pick the model the harness runs with.** By default, the harness uses its own default model and its own login. The `acp.model` picker in Settings → **Coding** (`subagent.acp.modelProvider` / `subagent.acp.modelId`) pins it to one of your configured providers' models instead. A per-spawn `modelProvider` / `modelId` on `subagent_spawn` ("run this through Codex on `ollama` with `qwen3-coder`") overrides that for one run. How the override reaches the harness depends on the CLI: **Claude Code** gets `--model` plus the `ANTHROPIC_BASE_URL` / `ANTHROPIC_AUTH_TOKEN` environment pointed at the provider (so the endpoint must speak the Anthropic Messages API; Ollama and OpenRouter do). **Codex** gets `-m` plus an inline `model_providers` config block naming the provider's endpoint. **Pi** and **Gemini CLI** take the model only, so a provider override is refused for them. **opencode** and custom commands take neither and refuse any override. Pass `modelId` alone to change only the model and keep the harness's own endpoint and login. The run's log records the override as a **Step** line near the top. When you named a folder, the folder line comes first.

2. **Grant the spawning agent the `acp` capability.** The harness runs as an external process *outside* JClaw's tool gating and workspace confinement. For that reason it is a privileged capability. The **main agent** may always request it. A **custom agent** must have its **ACP External Harness** switch turned on, on its [Agents](/agents) page. Without the grant, an `acp` spawn is refused on permission. The gate is on the *spawning* agent, so a confined custom agent cannot break out by delegating to `acp`.

3. **Spawn with `runtime:"acp"`.** From chat, just ask the agent to delegate to the harness. For a background run, the agent emits:

   ```json
   subagent_spawn { "runtime": "acp", "async": true, "task": "..." }
   ```

   `async:true` returns a `runId` immediately, and the run's outcome shows in the chat's subagent list when the harness finishes. Drop it for a blocking run.

   To work in a folder you choose, or to continue in one from an earlier run, name the folder. See [Where coding output lands](/guide#subagents-where-coding-output-lands).

### Watching a coding run {#coding-run-log}

A coding run keeps a **log**: one line for each step, in order. You can open it in three places, and it reads the same in each.

- **In [Chat](/chat).** A **Coding run** panel docks above the composer of the conversation that started the run. It appears when the run records its first step. Only that conversation shows it. Another chat that is open at the same time does not.
- **On the [Tasks](/tasks) page.** For a run that a task started, open the task run's trace panel. The log is at the top under **Coding runs**.
- **On the [Dashboard](/).** In the **Recent Activity** panel, switch to the **Coding runs** view and click a run's name. The **Coding run log** panel opens.

The header shows `Coding run #ID` and the status: **Running**, **Completed**, **Failed**, **Timed out**, or **Killed**. Each line below it has a label:

| Label        | What it is                                                                                                   |
|--------------|--------------------------------------------------------------------------------------------------------------|
| **Step**     | Something JClaw did to set the run up: the folder it opened when you named one, a model override, or an approval. |
| **Tool**     | A tool the harness used, with the file it touched and how the call ended. For example: `Write notes.txt: /Users/you/coding/demo/notes.txt (completed)`. |
| **Message**  | Text the harness wrote back.                                                                                 |
| **Thinking** | The harness's reasoning, when it reports any.                                                                |

A harness can announce a tool call before it has the details, so one call can show twice: first as a short line marked `(pending)`, then as the full line when the call ends. A **Diff** or **Error** line appears when the harness reports one.

A run that did not complete shows the reason at the bottom. While a run is going, **Kill** stops it. JClaw asks you to confirm, then force-terminates the harness.

How much the log holds depends on how JClaw talks to the harness. Over ACP, and in `json` and `rpc` modes, the harness reports its steps as it works, and each one becomes a line. In `batch` mode the harness reports nothing until it exits. The log then holds only JClaw's own steps, and the reply comes back as the run's result.

### Bounds and failure

- **Wall-clock ceiling.** The harness is bounded by `subagent.maxWallClockSeconds` (default **1800**, which is 30 minutes). A harness that overruns is force-killed (`destroyForcibly`), and the run records `TIMEOUT`/`FAILED`. Set it to `0` to wait indefinitely. This is not recommended for an unattended instance.
- **Output cap.** Captured stdout is bounded. A harness that floods stdout is truncated rather than allowed to exhaust memory.
- **Non-zero exit.** If the harness exits non-zero, the spawn is `FAILED`, and the harness's stderr is the failure reason.
- **No folder.** If JClaw cannot create the run's folder, the run is `FAILED` before the harness starts. The reason names the folder. When JClaw was making a new folder, it also tells you to check **acp.workdir**. JClaw never runs the harness in another folder instead.
- **Approval.** Launching a coding harness is an action the approval gate holds. A run that starts from your own web chat or voice mode passes. A run from any other origin follows [Tool Approvals](/guide#settings-tool-approvals). If the gate refuses it, the run is `FAILED` before the harness starts.

A failed run gives its reason in three places: in the log, on the [Subagents](/subagents) page, and in the result the parent agent gets back, so the agent can tell you why. A run that fails before the harness starts is still a coding run. The **Coding runs** views list it, and its log holds the reason.

:::gotcha
`runtime:"acp"` needs `subagent.acp.command` configured *and* the spawning agent to hold the `acp` grant. A spawn that clears the permission gate but finds no configured command is refused with a message telling the operator to set the key.
:::

## Recursion limits

To stop a subagent from spawning grandchildren that spawn great-grandchildren, JClaw enforces two caps. Both are runtime-configurable in the **Subagents** section of the [Settings](/settings) page, and both fail closed. A spawn that would breach the cap is refused with a clear error before anything is created.

| Setting key                     | Default | Meaning                                                          |
|---------------------------------|---------|------------------------------------------------------------------|
| `subagent.maxDepth`             | 1       | How deep the parent → child → grandchild chain can go.           |
| `subagent.maxChildrenPerParent` | 5       | How many concurrently-running children a single parent can have. |

A depth limit of `1` means the top-level agent can spawn children, but those children cannot spawn further children. Bump it for explicit fan-in patterns. Keep it conservative for runaway protection.

The same Settings section also holds `subagent.defaultRunTimeoutSeconds` (default 300) and `subagent.defaultYieldTimeoutSeconds` (default 300). These are the fallbacks for a spawn or yield that omits its own timeout. The section also holds an optional global subagent model (`subagent.modelProvider` / `subagent.modelId`) that pins every child to one model instead of inheriting the parent's. The harness command and its model override live in Settings → **Coding**.

## Inspecting what a child did

Five surfaces exist, each with its own audience. A coding run has one more, its log. See [Watching a coding run](/guide#subagents-coding-run-log).

### 1. The announce card (in [Chat](/chat))

This lands in your conversation when an `async` subagent the parent ended its turn to wait for with `subagent_yield` terminates. A run nobody waited on reports in the chat's subagent list instead (surface 5). The card shows the label, the terminal status (`COMPLETED`, `FAILED`, or `TIMEOUT`), and the child's reply rendered as markdown. It includes a "View full →" link. That link opens the child's full transcript in the standard chat viewer, read-only, because the child is no longer accepting input.

If the child's reply was cut off by the model's output budget, the announce card shows a small amber *"Reply was truncated by the model"* marker. That marker tells you the summary is not complete.

### 2. The [Subagents](/subagents) page

This page lists every run (`RUNNING`, `COMPLETED`, `FAILED`, `KILLED`, `TIMEOUT`), across all parent agents. Filter by parent agent, status, and start time. Each row links to the child's transcript. Use this page when you want a fleet view across multiple parents and time ranges.

The **Conversation** column shows the conversation each run was spawned from. It is a link that opens that conversation in [Chat](/chat). By default, the list is sorted by that column, so runs from the same conversation sit together under a *Conversation #ID* header row, newest conversation first and newest run first within it. The order comes from the server, so the grouping holds across pages. A conversation whose runs spill onto the next page repeats its header at the top of that page. Clicking another column header sorts by that column instead and shows the plain, ungrouped list. Click **Conversation** to group again.

A run that a task fire started has no conversation. Its row shows *task NAME* instead, and these runs sit together under a *No parent conversation* header.

To see only one conversation's runs, either click the funnel button beside an id in the Conversation column, or type `parentConversation:ID` in the filter bar. A *Conversation #ID* chip beside the bar shows the active conversation filter, and its **×** clears it. Removing the `parentConversation` token from the bar clears it too. Opening the page from a chat's subagent link applies the same filter. **Delete all matching** honors it and deletes only that conversation's terminal runs.

Deleting a run, alone or in bulk, removes its record, its transcript, and the temporary agent that was made for it. A run that is still `RUNNING` is skipped, so kill it first. Three things are never deleted with a run:

- An agent of your own that the run used through `agentId`.
- A chat that an inline run wrote into.
- The folder a coding run worked in.

### 3. The `/subagent` slash command (in [Chat](/chat))

This is the operator surface for subagent runs. `list` covers the current conversation. `history` covers only runs the current agent spawned. `info`, `log`, and `kill` address any run on the instance. Five subcommands:

| Command                  | What it does                                                                   |
|--------------------------|--------------------------------------------------------------------------------|
| `/subagent list`         | Show running and recently-terminal runs spawned in the current conversation.   |
| `/subagent info <id>`    | Detail block for one run: status, mode, context, started/ended, outcome.       |
| `/subagent log <id>`     | First ~50 events for a run, oldest first (spawn, complete, error, kill).       |
| `/subagent kill <id>`    | Cooperatively cancel a running child.                                          |
| `/subagent history <id>` | Inline render of the child's transcript (capped to ~20 messages, 500 chars).   |

On a channel, `/subagent` answers *Only the operator can use this command.* to anyone the channel cannot prove is you: a Telegram group member who @mentions the bot, every WhatsApp sender, anyone but the owner on a Slack binding, and everyone (you included) on a Slack binding with no owner configured. Web chat is always you.

### 4. The `conversation_history` tool

The parent agent itself uses this to recall what a previous child did. Given the run id, this returns the full message list (role, content, tool calls and results, timestamps) for a child conversation. It is useful when the parent wants to summarize across multiple historical runs, debug its own delegation pattern, or splice intermediate results into a follow-up turn. The calling agent must be the run's parent.

### 5. The subagent list (in [Chat](/chat))

A shade under the chat header, headed *N subagents · N running*, lists the runs the current conversation spawned. Each row shows the run's label, its status, and how long it has been running or how long ago it ended. A failed, timed-out, or killed run gets a colored status pill with the reason as its tooltip. Expand a row to read its transcript (a failure reason also shows above it). Click the header to collapse the list, or click **View all →** to open this conversation's runs on the [Subagents](/subagents) page.

## Quick reference

```text
subagent_spawn
  task              string   instruction for the child (required unless tasks is given)
  tasks             array    batch fan-out — one async child per entry (a string or {task, label, agentId}), returns run_ids (session mode only)
  label             string   short display name
  agentId           int      use an existing agent row instead of cloning current
  mode              string   "session" (default) | "inline" | (async via async=true)
  context           string   "fresh" (default) | "inherit"
  runtime           string   "native" (default) | "acp"
  folder            string   acp only: the folder the harness works in; an existing name continues there, a new name creates it
  modelProvider     string   override child's provider (acp: points the harness at that endpoint — claude/codex only)
  modelId           string   override child's model (acp: replaces the harness's own default model)
  async             bool     return run id immediately (session mode only)
  runTimeoutSeconds int      idle budget (seconds of inactivity), default subagent.defaultRunTimeoutSeconds (300)

subagent_yield
  runId             string   the run id from a prior async spawn; a tasks batch child is waited for inline (in a subagent's own turn, the only kind waited for)
  runIds            string[] collect a whole batch — waits for all of them, returns results inline
  all               bool     collect every uncollected async child (tasks or async=true) of this conversation or task
  conversationId    string   alternative to runId — the child conversation id
  timeoutSeconds    int      resume budget when the yield ends the turn, default subagent.defaultYieldTimeoutSeconds (300); 0 disables

conversation_history
  runId             string   required
  limit             int      1–200, default 50
  beforeMessageId   string   pagination cursor
```

## Tips and gotchas

:::gotcha
`async=true` requires `mode=session`. Inline mode embeds child messages directly. If the runtime returned control before the child finished, it would leave a half-written nested block dangling. The runtime rejects the combination with a clear error.
:::

:::note
A child gets a fresh clone of the parent's agent by default. Pass `agentId` if you want to run on a specific pre-configured agent from your [Agents](/agents) page.
:::

:::gotcha
`context=inherit` costs an extra model call for the summarization pass before the child even starts. It is not free. Use it when you need it.
:::

:::gotcha Truncation
If the child's reply ends abruptly with a "Reply was truncated by the model" marker, the prompt fed into that turn was so large it left the model with very little output budget. Try a model with a bigger context window, or break the task into smaller pieces.
:::

:::note
Killed runs do not get an announce card. The `/subagent kill` response *is* your confirmation. Suppressing the announce avoids double-rendering "Killed by operator" twice.
:::

:::tip Scoping
Every subagent run belongs to you. JClaw Pro has a single **admin** operator, so the [Subagents](/subagents) page shows and controls every run on the instance. The same is true of `/subagent info`, `log`, and `kill`. `/subagent list` stays scoped to the current conversation, and `history` stays scoped to runs the current agent spawned.
:::

## Where coding output lands

Every `runtime=acp` coding run works inside its own folder. JClaw picks the folder in this order:

1. **The folder you name.** Name it in chat. For example, say "continue in create-fibonacci-program". The agent passes the name as the `folder` argument of `subagent_spawn`. An existing folder is reused, so a run can continue an earlier one. A new name creates that folder. `folder` is one folder name of at most 100 characters, never a path.
2. **A new folder named from the task.** The task "create fibonacci program" runs in `create-fibonacci-program/`. A repeated task never reuses an earlier run's folder. Collisions get `-2`, `-3`, and so on as suffixes.

Both sit under one parent folder. By default, the parent is in the child agent's workspace: `workspace/<agent>/coding/`. Because the files live in the workspace, the agent's filesystem and documents tools can read them directly in later turns. Setting `subagent.acp.workdir` (the **acp.workdir** field in Settings → **Coding**) moves the parent to the folder you name: `<workdir>/<folder>/`. The agent's tools can then read the output only if that folder is inside its workspace.

JClaw records the folder on the run. When you named the folder, it is the first line of the [log](/guide#subagents-coding-run-log): `new folder PATH` or `continuing in PATH`. A **Tool** line shows the full path of the file it touched.

Two rules protect your files:

- **JClaw never falls back to another folder.** If it cannot create the run's folder, the run fails and says why. A typo in **acp.workdir** or a drive that is not connected causes this.
- **JClaw never deletes a coding folder.** Deleting a run, a chat, or a task removes the run's record and log. The folder and the files in it stay until you remove them.

:::tip Running JClaw from a source checkout
The default parent, `workspace/<agent>/coding/`, is inside the folder JClaw runs from. If that folder is a Git checkout, a harness that looks upward for a repository finds JClaw's own, with its instruction files. Set **acp.workdir** to a folder outside the checkout, for example `~/coding-runs`, so that each run starts in a clean folder.
:::

## Sandboxing coding runs (opt-in)

By default, a coding harness runs with the operator's own account permissions. It is scoped only by its `coding/<slug>/` working directory (which organizes output but confines nothing) and by the harness's permission flags. For a real OS boundary, set `subagent.acp.sandbox` with the **acp.sandbox** control in [Settings → Coding](/guide#settings-coding). It takes three values: `false` (the default; never confine), `true` (confine every run), or `untrusted` (confine only runs whose origin channel is not your own web chat or voice mode, which means inbound Telegram or Slack, the prompt-injection surface; your own runs from web chat and voice mode stay unconfined):

* **macOS** wraps the harness in `sandbox-exec`. Writes are confined to the session directory, the system temp directories, `/dev`, and the harness's declared state paths (its own config, for example `~/.claude`). Reads of `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud`, `~/.kube`, and `~/.netrc` are denied. JClaw's own `data`, `certs`, `conf` and `logs` folders and its backup folder can be neither read nor written. Everything else stays readable.
* **Linux** wraps it in `bwrap`. The visible filesystem is built from nothing, so secrets are absent rather than merely denied. Only the session directory is writable, and only the harness's declared state paths (its own config, for example `~/.claude`) are bound back. JClaw's own folders are hidden too, even when JClaw is installed outside your home folder.
* **Windows** is supported through **WSL2** (it uses the Linux path). Native Windows and WSL1 have no sandbox.

The sandbox **fails closed**. If it is enabled where no mechanism is available (native Windows, WSL1, or a WSL2 kernel with unprivileged user namespaces disabled), the run is aborted with an actionable error rather than launched unsandboxed. The **acp.sandbox** control says when this host lacks `sandbox-exec` or `bwrap` (it cannot detect a `bwrap` whose namespaces are disabled). Network egress stays open; the harness needs its API. Off by default.

## Where to go next

Subagents fan out work *now*, in this turn. The next two sections cover fanning out *later*:

- [Tasks](/guide#tasks): scheduled work the agent figures out at fire time.
- [Reminders](/guide#reminders): scheduled pre-written nudges that skip the LLM entirely.
- [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders): side-by-side comparison.