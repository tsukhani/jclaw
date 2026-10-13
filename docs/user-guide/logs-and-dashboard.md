# Logs & Dashboard

Two read-only views show what JClaw is doing now and what it did recently.

- The [Dashboard](/) is the home page. Its stats and live panels cover agent, conversation, channel, and task counts, plus chat performance, cost, compression, circuit breakers, and recent activity.
- The [Logs](/logs) page is a filterable, searchable event stream for everything that happens server-side.

## Dashboard

The [Dashboard](/) is the first thing you see after signing in. Five stat cards sit at the top. The panels sit below them: Chat Cost, Circuit Breakers, Chat Performance, Chat Compression, and Recent Activity.

### Stat cards

| Card | What it shows |
| --- | --- |
| **Agents** | Two sub-stats: **Active** (enabled agents / total agents) and **Size**. Size is the disk footprint of the agents' shared workspace. It turns amber past 10 GB, so a runaway file shows up without a shell. |
| **Conversations** | A cumulative count of all conversations. Labeled **Total**. |
| **Channels** | The number of external channels that are currently active: Telegram bindings plus Slack/WhatsApp configs. The in-app web chat is deliberately excluded, so the number matches the cards on [Channels](/channels). |
| **Tasks** | Three sub-stats side by side: **Active** (recurring `CRON` / `INTERVAL` in steady state), **Running** (currently firing), **Pending** (`SCHEDULED` / `IMMEDIATE` waiting). |
| **Reminders** | Two sub-stats: **Active** (recurring reminders) and **Pending** (one-shot reminders waiting to fire). |

The Tasks card splits three ways on purpose. You want to see `RUNNING` tick up and back down during a fire, without going to the Tasks page.

### Refresh cadence

The Tasks and Reminders sub-stats, plus three panels, refresh in lockstep on a **5-second tick**. The three panels are Chat Cost, Chat Performance, and Recent Activity. Circuit Breakers polls on its own 10-second tick. Chat Compression is not on a tick at all. The Agents, Conversations, and Channels cards load once per visit. The page polls in the background as long as it is open, so you can watch a task fire and see the numbers move without a manual reload.

### Chat Cost

This panel shows persisted token usage and dollar cost, aggregated across your conversations. Header controls:

- **Filters**: Agent (all / specific); Channel (all, or any channel that has cost data in the window. The list is built from the data, not fixed); Window (Last 7 days / Last 30 days / all-time).
- **View**: table or bar chart.
- **CSV**: download the per-model breakdown.

When you have a subscription provider configured (Anthropic Pro, OpenAI Plus, and similar), a **Subscription** subsection renders first. It shows the pro-rated monthly fee for the selected window, and per-provider chips narrow the view to one provider. The **Per-token** subsection below covers everything billed on usage. It has its own provider chips and a rollup card per provider (total spend and average $/1M tokens). A **Combined Total** row sums the two.

### Circuit Breakers

The panel has three groups, in order: **LLM providers**, then **MCP servers**, then **Decision providers**. It shows one row per breaker that is not serving. Each row shows the state the breaker is in, why it is there, and how many of its recent calls failed. Open breakers sit above probing ones. A breaker that is serving is not listed here. It shows beside what it guards: on the provider's card in [Settings → LLM Providers](/settings?section=providers), beneath the server on the [MCP Servers](/mcp-servers) page, and on JEV's card in [Settings → Decision Providers](/settings?section=decision-providers). Each name here links to that place. The panel is absent while every breaker is serving. Breakers are created the first time a subsystem is called.

It sits directly above Chat Performance, because that is the blind spot it closes. A breaker that has opened refuses every call in microseconds. So the latency percentiles below it *improve*, and the error rate stays flat, while the work behind them is failing.

| State | What it means |
| --- | --- |
| **CLOSED** | Serving normally. Calls go through, and their outcomes feed the failure-rate window. |
| **OPEN** | Not serving. Every call fails immediately without reaching the provider, until the cooldown (`llm.breaker.wait-seconds`) elapses. |
| **HALF&nbsp;OPEN** | Probing. The next few calls decide whether it closes again or reopens for another cooldown. |

The row's reason column says what opened it:

- **consecutive failures**: three exhausted calls in a row. This rule fires first on a low-traffic install.
- **failure rate** or **slow call rate**: half of the recent window.
- **probe failed** or **probe slow**: a probe reopened it.
- **isolated by you**: you isolated it.

A half-open breaker reads **probing** instead. What each subsystem counts as a failure, and the streaming budgets behind a slow call, are described under [LLM Providers](/guide#settings-when-a-provider-misbehaves) and [MCP Servers](/guide#skills-tools-mcp-when-a-server-stops-answering).

Each row here carries **Restore**. **Isolate** asks first. It sits beside the provider or server, on its serving breaker. Isolating is bounded rather than latched. It restarts the ordinary cooldown, so a provider that is actually healthy closes itself again. The provider does not stay dark until you remember it.

Together, these two actions are also the failover drill. Isolate an agent's provider, send it a turn, and watch it land on the fallback provider and model set on that agent. Then restore. This exercises the fallback path end-to-end, on your schedule, and no background job spends tokens to rehearse the same thing forever. An agent with no fallback set answers the drill with a fast, clean failure.

Every state change is written to the event log under the `CIRCUIT_BREAKER` category. A breaker you isolated says so in the log, rather than reporting a failure rate. An operator's decision never reads back as the provider having broken. With OpenTelemetry export on, the same transitions arrive as the `jclaw.breaker.transitions` counter and a `circuit_breaker.transition` span event. Alarm on the transition to `OPEN`, not on the errors underneath it.

With `alerts.delivery` set under [Settings → Alerts](/guide#settings-alerts), JClaw also messages you when a provider or MCP server breaker opens. That message says why it tripped. JClaw messages you again when the breaker recovers. Each outage is announced once. A breaker that keeps reopening announces at most once every 15 minutes. A breaker you isolate or restore by hand sends nothing. An alert that cannot be delivered is written to the event log under `OPERATOR_ALERT`.

### Chat Performance

This panel shows latency percentiles per pipeline segment of a turn, not per model. The segments are: queue wait, TTFT, the tool rounds or reasoning before the first text, stream body, tool execution, the voice group, and total. Filters sit in the panel header: a **7d / 30d / All** window (default 30d), an agent select, and a channel select. Three views are toggled in the panel header:

- **Table**: per-segment latency. Columns: sample count (n), p50 / p90 / p99 / p999 percentiles, and min/max. Child segments are indented under their parent.
- **Distribution chart**: overlapping latency density curves, so you can compare the segments' distributions at a glance.
- **Counts**: the per-turn call and round counts described below.

Use this panel to spot a slow segment or a slow channel before users complain.

The trash icon (**Clear latency metrics**) deletes every recorded latency sample. This includes browser INP rows, all windows, all agents, and all channels, not just the current filter. It asks no confirmation.

The **browser** channel is different. It measures the JClaw web app itself, rather than a chat turn. Each time an interaction in the app (a click, a keypress) becomes the slowest of your session, the page reports its **Interaction to Next Paint (INP)**. Selecting **browser** shows those as one INP row. "All channels" leaves them out, so they never mix into the turn figures. An interaction slower than 200 ms (Google's "good" limit) also lands in [Logs](/logs) under category **browser**. The log names the page and the element, and how the time split between waiting, running handlers, and repainting.

The **Counts** view holds up to six per-turn figures. They are counts, not durations, so they get their own view instead of rows in the latency table. Its columns are Metric / turns / total / p50 / p90 / p99 / max. **total** is the windowed sum, which has no meaning for a duration:

| Row | What it counts |
| --- | --- |
| **LLM calls / turn** | Chat requests dispatched to a provider during the turn. That is the first call, plus every tool-loop continuation, retry-with-nudge, prologue summarization, and any call a tool makes on the turn's behalf. Transport retries behind one dispatch are not separate calls. A failover to a second provider is. |
| **Cache-served calls / turn** | How many of those calls had their prompt served from the provider's cache. This is much cheaper than an uncached call. |
| **Tool rounds / turn** | Tool-execution rounds. A round can carry several tool calls, and it is not a model call. |
| **Tool results checked / turn** | Tool results checked before the model reads them. The check only records a verdict. The model still sees the result unchanged. |
| **Tool results flagged / turn** | How many of those failed the check: blank, an error, or a failure the tool itself reports, such as a non-zero exit code. Only turns with a flagged result contribute. |
| **Memory recalls / turn** | Memory recalls during the turn: the one made while assembling the prompt, plus any the `memory` tool makes. |

Only turns with at least one cache-served call contribute to the Cache-served row. So the cache-served share is printed as a percentage line under the table. It is the ratio of the two rows' totals, not a subtraction of percentiles. Percentiles do not subtract.

Watch **LLM calls / turn** when you change agent configuration. It tells you whether a change bought its quality with extra model calls.

### Chat Compression

This panel shows token savings from [Content Compression](/guide#agents) of large tool results. It has the same **7d / 30d / All** window (default 30d), agent select, and channel select as Chat Performance. Four tiles head the panel:

- **Tokens saved**: tokens before minus after, over the window.
- **Reduction**: the saved share, with the before → after totals.
- **CCR hit rate**: how many `ccr_retrieve` calls found the original they asked for. Retrievals carry no agent or channel, so this tile ignores those filters.
- **Inflation guards**: compressions discarded because the result was not fewer tokens than the original, beside the count of compressions kept.

Below them are **Saved by content type** and **Algorithm usage**. The table and chart icons in the header switch both between bar charts (the default) and tables. The table columns are *Type / Saved / Reduction* and *Algorithm / Events / Saved*. Warnings appear at the foot when inflation guards exceed 5% of attempts, when the CCR hit rate falls below 50%, or when a content type keeps under 10% of its tokens while the hit rate is below 90%.

The trash icon (**Reset compression metrics**) deletes every recorded compression event, not just the current window, without confirmation.

### Recent Activity

This panel is a live tail of the last 10 events. It shows the same message columns as the [Logs](/logs) page, but **without** the expand chevron. Events cannot be expanded inline here:

| Column | Width | Notes |
| --- | --- | --- |
| **Level** | narrow | Color-coded: red `ERROR`, yellow `WARN`, muted `INFO`. |
| **Category** | medium (narrower on small screens) | The subsystem the event came from. |
| **Agent** | narrow | Owning agent id (or `—` placeholder for none). |
| **Message** | flex | One-line description. |
| **Timestamp** | wide, right-aligned | Local date · time (matches Logs format). |

A segmented toggle in the panel header switches the table between four views:

- **All** (default): the event tail described above.
- **Video**: recent video-generation jobs with their state (`PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED`), prompt, submitted time, and a "see in conversation" link to where each was requested. Use it to track `generate_video` jobs as they run, without leaving the dashboard.
- **Scrape jobs**: the 10 most recent [background scrapes](/guide#scrapes) with their state, site, progress, and start time. The site links to the job's page on [Scrapes](/scrapes).
- **Coding runs**: the 10 most recent [coding runs](/guide#subagents-acp-harness) with their status, name, origin, and start time. A run is named by its label, or by its folder when it has no label. A run that failed before it got a folder is listed too, as *Run #ID*, and its log gives the reason. Click the name to open the **Coding run log** panel. See [Watching a coding run](/guide#subagents-coding-run-log) for what each line means. A run started from a chat has a "see in conversation" link. A run started by a task shows *task NAME*.

For full filtering and expansion, click through to the [Logs](/logs) page.

## Logs

The [Logs](/logs) page is the operator's microscope. Every meaningful event is logged with a category, a level, a message, and, for some events, a JSON details blob. These events are agent runs, tool calls, subagent spawns, task fires, channel webhooks, configuration changes, and notifications.

JClaw masks credentials before it writes an event. An agent often puts a key on a command line or in a tool call, for example `curl -H "X-Api-Key: ..."` or `?apikey=...`. The event keeps the name of the header, parameter or variable and shows `[redacted]` in place of the value. The log file `logs/application.log` gets the same masked text. Masking covers key headers, bearer and basic credentials, keys and tokens in a URL, `user:password` pairs, and common token formats. It does not change the conversation itself: a command an agent ran is still stored in full in that conversation's history. Older events, written before masking was added, are not masked.

### Filters

Three filters sit across the top:

- **Category**: restricts to one subsystem. The list is built from the categories already present in the event log, so every category that has written an event is selectable. Examples: `llm`, `channel`, `tool`, `CIRCUIT_BREAKER`, `router` (the Auto router's choices and failovers), and `OPERATOR_ALERT` (an alert that could not be delivered). Per-event categories are grouped: **Subagents** (`SUBAGENT_*`), **Tasks** (`TASK_*`), and **MCP** (`MCP_*`). A group appears once one of its categories has an event. The list reloads each time you focus the filter, so a category first written while the page is open shows up without a reload.
- **Level**: `ERROR`, `WARN`, or `INFO`.
- **Search**: free-text match on the message body.

Filters compound. Clear them by setting each one back to **All**.

### Auto-refresh

A checkbox sits in the top-right. It is **on by default** and refreshes every 5 seconds. The interval pauses while the tab is hidden, so you do not burn a backend query when nobody is watching. Untick it when you are investigating a frozen-in-time problem and want a stable view.

### Columns

| Column | Notes |
| --- | --- |
| (chevron / ◦) | A `›` chevron marks rows with structured details. Click to expand inline. Rows without details show a hollow `◦` placeholder. This keeps the column aligned and makes the "no details" state unambiguous. |
| **Level** | Color-coded `ERROR` / `WARN` / `INFO`. |
| **Category** | The subsystem the event came from. |
| **Agent** | Owning agent id (or `—` for none, on system-scoped events). |
| **Message** | One-line description of what happened. |
| **Timestamp** | Full local date and time, formatted the same way as the Dashboard's Recent Activity panel. |

### Expanding an event

Rows with a chevron are clickable. A click expands the details payload inline below the row. The payload is typically JSON: a request or response body, a stack trace, or structured outcome data. It is indented under the message column for alignment. Click again to collapse.

Rows with a hollow circle have no details. This is common for boot-time and lifecycle events, where the message is the whole story.

### Use cases

- **"Why didn't my agent reply?"** → category `agent` and/or `llm`, level `ERROR`.
- **"What did my channel webhook receive?"** → category `channel`.
- **"Did my scheduled task fire?"** → category `task`.
- **"Did a subagent get killed or time out?"** → Subagents optgroup, then pick `SUBAGENT_KILL` / `SUBAGENT_TIMEOUT`.
- **"Why was a tool slow?"** → category `tool`, then search the tool's name.

:::tip Start with ERRORs
When something is broken, set the level filter to `ERROR` first. Most issues surface as a single explanatory error line. The rest is noise until you have a hypothesis.
:::

:::note Privacy
Logs include the metadata of what happened, not the full conversation content. Message bodies are not logged. Tool arguments and responses are summarized rather than echoed.
:::

:::note Retention
Events older than the configured retention window (default 30 days, set under **Settings → Logging → Event Log Retention**) are swept by `EventLogCleanupJob` at startup and on a daily tick. The Logs page has no export. Copy out what an active investigation needs before the cutoff.
:::

## Sidebar status pip

Besides these two pages, the small colored pip at the bottom of the collapsed sidebar (or the labeled row when expanded) reports the live API status. Red means the backend is unreachable. Most pages fail until it recovers, and a banner at the top of the page lets you re-check on demand. A second pip below it reports whether the running Play framework version matches the `.play-version` pin (green = match, amber = drift, hidden in dist installs where the file is absent).

## Where to go next

- [Settings](/guide#settings): change the behavior of whichever subsystem you are investigating.
- [Chat](/guide#chat): reproduce a problem and watch it surface in the [Logs](/logs).
- [Subagents](/guide#subagents): run-level inspection of fan-out workflows.
- [Tasks](/guide#tasks): run-level inspection of scheduled work.