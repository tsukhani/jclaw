# Logs & Dashboard

Two read-only surfaces give you visibility into what JClaw is doing right now and what it's done lately.

- The [Dashboard](/) — the home page; stats and live panels covering agent/conversation/channel/task counts, chat performance, cost, compression, circuit breakers, and recent activity.
- The [Logs](/logs) page — a filterable, searchable event stream for everything happening server-side.

## Dashboard

The [Dashboard](/) is the first thing you see after signing in. Five stat cards at the top, then the panels below — Chat Cost, Circuit Breakers, Chat Performance, Chat Compression, and Recent Activity.

### Stat cards

| Card              | What it shows                                                                                          |
|-------------------|--------------------------------------------------------------------------------------------------------|
| **Agents**        | Two sub-stats: **Active** (enabled agents / total agents) and **Size** — the agents' shared workspace's footprint on disk, turning amber past 10 GB so a runaway file shows up without a shell. |
| **Conversations** | Cumulative count of all conversations. Labeled **Total**.                                               |
| **Channels**      | Number of currently-active external channels — Telegram bindings + Slack/WhatsApp configs. The in-app web chat is deliberately excluded so the number matches the cards on [Channels](/channels). |
| **Tasks**         | Three sub-stats side by side: **Active** (recurring `CRON` / `INTERVAL` in steady state), **Running** (currently firing), **Pending** (`SCHEDULED` / `IMMEDIATE` waiting). |
| **Reminders**     | Two sub-stats: **Active** (recurring reminders) and **Pending** (one-shot reminders waiting to fire). |

The Tasks card's three-way split is intentional — you want to see `RUNNING` tick up and back down during a fire without going to the Tasks page.

### Refresh cadence

The Tasks and Reminders sub-stats and three of the panels — Chat Cost, Chat Performance, and Recent Activity — refresh in lockstep on a **5-second tick**; Circuit Breakers polls on its own 10-second tick, Chat Compression is not on a tick at all, and the Agents, Conversations and Channels cards load once per visit. The page polls in the background as long as it's open, so an operator watching a task fire sees the numbers move without manual reload.

### Chat Cost

Persisted aggregated token usage and dollar cost across your conversations. Header controls:

- **Filters** — Agent (all / specific), Channel (all, or any channel that has cost data in the window — the list is built from the data, not fixed), Window (Last 7 days / Last 30 days / all-time).
- **View** — table or bar chart.
- **CSV** — download the per-model breakdown.

When you have a subscription provider configured (Anthropic Pro, OpenAI Plus, etc.), a **Subscription** subsection renders first with the pro-rated monthly fee for the selected window, and per-provider chips let you narrow to one. The **Per-token** subsection below covers everything billed on usage, with its own provider chips and a rollup card per provider (total spend and average $/1M tokens), and a **Combined Total** row sums the two.

### Circuit Breakers

Three groups — **LLM providers** first, then **MCP servers**, then **Decision providers** — with one
row per breaker that is not serving, showing the state it is in, why it is there, and how many of its
recent calls failed. Open breakers sit above probing ones. A breaker that is serving is not listed
here: it is shown beside what it guards, on the provider's card in
[Settings → LLM Providers](/settings?section=providers), beneath the server on the
[MCP Servers](/mcp-servers) page, and on JEV's card in
[Settings → Decision Providers](/settings?section=decision-providers), and each name here links to
that place. The panel is absent while every breaker is serving; breakers are created the first time a
subsystem is called.

It sits directly above Chat Performance because that is the blind spot it closes. A breaker that
has opened turns every call away in microseconds, so the latency percentiles below it *improve*
and the error rate stays flat while the work behind them is failing.

| State         | What it means                                                                        |
|---------------|---------------------------------------------------------------------------------------|
| **CLOSED**    | Serving normally. Calls go through and their outcomes feed the failure-rate window.    |
| **OPEN**      | Not serving. Every call fails immediately without reaching the provider, until the cooldown (`llm.breaker.wait-seconds`) elapses. |
| **HALF&nbsp;OPEN** | Probing. The next few calls decide whether it closes again or reopens for another cooldown. |

The row's reason column says what opened it: **consecutive failures** (three exhausted calls in a
row, the rule that fires first on a low-traffic install), **failure rate** or **slow call rate**
(half of the recent window), or **isolated by you**. What each subsystem counts as a failure, and
the streaming budgets behind a slow call, are described under
[LLM Providers](/guide#settings-when-a-provider-misbehaves) and
[MCP Servers](/guide#skills-tools-mcp-when-a-server-stops-answering).

Each row here carries **Restore**. **Isolate**, which asks first, is beside the provider or
server, on its serving breaker. Isolating is bounded rather than latched — it restarts the ordinary cooldown, so a
provider that is actually healthy closes itself again rather than staying dark until you remember
it.

Together they are also the failover drill. Isolate an agent's provider, send it a turn, watch it
land on the fallback provider and model set on that agent, then restore — the fallback path
exercised end-to-end, on your schedule, without a background job spending tokens to rehearse the
same thing forever. An agent with no fallback set answers the drill with a fast, clean failure.

Every state change is written to the event log under the `CIRCUIT_BREAKER` category, and a
breaker you isolated says so in the log rather than reporting a failure rate — an operator's
decision never reads back as the provider having broken. With OpenTelemetry export on, the same
transitions arrive as the `jclaw.breaker.transitions` counter and a `circuit_breaker.transition`
span event; alarm on the transition to `OPEN`, not on the errors underneath it.

With `alerts.delivery` set under [Settings → Alerts](/guide#settings-alerts), JClaw also messages
you when a provider or MCP server breaker opens, saying why it tripped, and again when it
recovers. Each outage is announced once, a breaker that keeps reopening at most once every 15
minutes, and a breaker you isolate or restore by hand sends nothing. An alert that cannot be
delivered is written to the event log under `OPERATOR_ALERT`.

### Chat Performance

Latency percentiles per pipeline segment of a turn — queue wait, TTFT, the tool rounds or reasoning before the first text, stream body, tool execution, the voice group, total — rather than per model. Filters sit in the panel header: a **7d / 30d / All** window (default 30d), an agent select, and a channel select. Three views, toggled in the panel header:

- **Table** — per-segment latency: sample count (n), p50 / p90 / p99 / p999 percentiles, and min/max. Child segments are indented under their parent.
- **Distribution chart** — overlapping latency density curves so you can compare the segments' distributions at a glance.
- **Counts** — the per-turn call and round counts described below.

Use this to spot a slow segment or a slow channel before users complain.

The **browser** channel is different: it measures the JClaw web app itself rather than a chat turn. Each time an interaction in the app — a click, a keypress — becomes the slowest of your session, the page reports its **Interaction to Next Paint (INP)**, and selecting **browser** shows those as one INP row. "All channels" leaves it out, so it never mixes into the turn figures. An interaction slower than 200 ms (Google's "good" limit) also lands in [Logs](/logs) under category **browser**, naming the page, the element, and how the time split between waiting, running handlers, and repainting.

The **Counts** view holds three per-turn figures that are counts rather than durations, so they get their own view instead of rows in the latency table. Its columns are Metric / turns / total / p50 / p90 / p99 / max — **total** is the windowed sum, which has no meaning for a duration:

| Row                           | What it counts                                                                                          |
|-------------------------------|---------------------------------------------------------------------------------------------------------|
| **Tool rounds / turn**        | Tool-execution rounds. A round can carry several tool calls, and it is not a model call.                  |
| **LLM calls / turn**          | Chat requests dispatched to a provider during the turn — the first call plus every tool-loop continuation, retry-with-nudge, prologue summarization, and any call a tool makes on the turn's behalf. Transport retries behind one dispatch are not separate calls; a failover to a second provider is. |
| **Cache-served calls / turn** | How many of those had their prompt served from the provider's cache — a much cheaper call than an uncached one. |

Only turns with at least one cache-served call contribute to the last row, so the cache-served *share* is printed as a percentage line under the table — the ratio of the two rows' totals rather than a subtraction of percentiles (percentiles don't subtract).

Watch **LLM calls / turn** when you change agent configuration: it is what tells you whether a change bought its quality with extra model calls.

### Recent Activity

A live tail of the last 10 events. It shows the same message columns as the [Logs](/logs) page, but **without** the expand chevron — events can't be expanded inline here:

| Column      | Width     | Notes                                       |
|-------------|-----------|---------------------------------------------|
| **Level**   | narrow    | Color-coded: red `ERROR`, yellow `WARN`, muted `INFO`. |
| **Category**| medium (narrower on small screens) | The subsystem the event came from. |
| **Agent**   | narrow    | Owning agent id (or `—`).                    |
| **Message** | flex      | One-line description.                       |
| **Timestamp**| wide, right-aligned | Local date · time (matches Logs format). |

A segmented toggle in the panel header switches the table between three views:

- **All** (default) — the event tail described above.
- **Video** — recent video-generation jobs with their state (`PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED`), prompt, submitted time, and a *see in conversation* link to where each was requested. Use it to track `generate_video` jobs as they run, without leaving the dashboard.
- **Scrape jobs** — the 10 most recent [background scrapes](/guide#scrapes) with their state, site, progress and start time; the site links to the job's page on [Scrapes](/scrapes).

For full filtering and expansion, click through to the [Logs](/logs) page.

## Logs

The [Logs](/logs) page is the operator's microscope. Every meaningful event — agent run, tool call, subagent spawn, task fire, channel webhook, configuration change, notification — is logged with a category, a level, a message, and (for some) a JSON details blob.

### Filters

Three filters across the top:

- **Category** — restrict to one subsystem. The list is built from the categories already present in the event log, so every category that has written an event is selectable (e.g. `llm`, `channel`, `tool`, `CIRCUIT_BREAKER`, `router` for the Auto router's choices and failovers, `OPERATOR_ALERT` for an alert that could not be delivered). Per-event categories are grouped: **Subagents** (`SUBAGENT_*`), **Tasks** (`TASK_*`) and **MCP** (`MCP_*`); a group appears once one of its categories has an event. The list reloads each time you focus the filter, so a category first written while the page is open shows up without a reload.
- **Level** — `ERROR`, `WARN`, or `INFO`.
- **Search** — free-text match on the message body.

Filters compound. Clear by setting each one back to **All**.

### Auto-refresh

A checkbox in the top-right. **On by default**, refreshes every 5 seconds. The interval pauses while the tab is hidden, so you don't burn a backend query when nobody's watching. Untick it when you're investigating a frozen-in-time problem and want a stable view.

### Columns

| Column         | Notes                                                                                                       |
|----------------|-------------------------------------------------------------------------------------------------------------|
| (chevron / ◦)  | A `›` chevron marks rows with structured details — click to expand inline. Rows without details show a hollow `◦` placeholder so the column stays aligned and the "no details" state is unambiguous. |
| **Level**      | Color-coded `ERROR` / `WARN` / `INFO`.                                                                       |
| **Category**   | The subsystem the event came from.                                                                          |
| **Agent**      | Owning agent id (or `—` for system-scoped events).                                                          |
| **Message**    | One-line description of what happened.                                                                      |
| **Timestamp**  | Full local date and time, formatted the same way as the Dashboard's Recent Activity panel.                  |

### Expanding an event

Rows with a chevron are clickable. Click expands the details payload (typically JSON — request/response body, stack trace, structured outcome data) inline below the row, indented under the message column for alignment. Click again to collapse.

Rows with a hollow circle have no details — common for boot-time and lifecycle events where the message is the whole story.

### Use cases

- **"Why didn't my agent reply?"** → category `agent` and/or `llm`, level `ERROR`.
- **"What did my channel webhook receive?"** → category `channel`.
- **"Did my scheduled task fire?"** → category `task`.
- **"Did a subagent get killed or time out?"** → Subagents optgroup, then pick `SUBAGENT_KILL` / `SUBAGENT_TIMEOUT`.
- **"Why was a tool slow?"** → category `tool` and search the tool's name.

:::tip Start with ERRORs
When something is broken, set the level filter to `ERROR` first. Most issues surface as a single explanatory error line; the rest is noise until you have a hypothesis.
:::

:::note Privacy
Logs include the metadata of what happened, not the full conversation content. Message bodies aren't logged; tool arguments and responses are summarized rather than echoed.
:::

:::note Retention
Events older than the configured retention window (default 30 days, set under **Settings → Logging → Event Log Retention**) are swept by `EventLogCleanupJob` at startup and on a daily tick. The Logs page has no export, so copy out what an active investigation needs before the cutoff.
:::

## Sidebar status pip

In addition to these two pages, the small colored pip at the bottom of the collapsed sidebar (or the labeled row when expanded) reports the live API status. Red means the backend is unreachable — most pages will fail until it recovers, and a banner at the top of the page lets you re-check on demand. A second pip below it reports whether the running Play framework version matches the `.play-version` pin (green = match, amber = drift, hidden in dist installs where the file is absent).

## Where to go next

- [Settings](/guide#settings) — to change the behavior of whichever subsystem you're investigating.
- [Chat](/guide#chat) — to reproduce a problem and watch it surface in the [Logs](/logs).
- [Subagents](/guide#subagents) — for run-level inspection of fan-out workflows.
- [Tasks](/guide#tasks) — for run-level inspection of scheduled work.
