# Logs & Dashboard

Two read-only surfaces give you visibility into what JClaw is doing right now and what it's done lately.

- The [Dashboard](/) — the home page; stats and live panels covering agent/conversation/channel/task counts, chat performance, cost, and recent activity.
- The [Logs](/logs) page — a filterable, searchable event stream for everything happening server-side.

## Dashboard

The [Dashboard](/) is the first thing you see after signing in. Five stat cards at the top, then three live panels below.

### Stat cards

| Card              | What it shows                                                                                          |
|-------------------|--------------------------------------------------------------------------------------------------------|
| **Agents**        | Enabled agents / total agents. Labeled **Active**.                                                      |
| **Conversations** | Cumulative count of all conversations. Labeled **Total**.                                               |
| **Channels**      | Number of currently-active external channels — web + Telegram bindings + Slack/WhatsApp configs.       |
| **Tasks**         | Three sub-stats side by side: **Active** (recurring `CRON` / `INTERVAL` in steady state), **Running** (currently firing), **Pending** (`SCHEDULED` / `IMMEDIATE` waiting). |
| **Reminders**     | Two sub-stats: **Active** (recurring reminders) and **Pending** (one-shot reminders waiting to fire). |

The Tasks card's three-way split is intentional — you want to see `RUNNING` tick up and back down during a fire without going to the Tasks page.

### Refresh cadence

All four stats and all three panels refresh in lockstep on a **5-second tick**. The page polls in the background as long as it's open, so an operator watching a task fire sees the numbers move without manual reload.

### Chat Cost

Persisted aggregated token usage and dollar cost across your conversations. Header controls:

- **Filters** — Agent (all / specific), Channel (all / web / telegram / slack / whatsapp), Window (Last 7 days / Last 30 days / all-time).
- **View** — table or bar chart.
- **CSV** — download the per-model breakdown.

When you have a subscription provider configured (Anthropic Pro, OpenAI Plus, etc.), a **Subscription** subsection renders first with the pro-rated monthly fee for the selected window, and per-provider chips let you narrow to one. The **Per-token** subsection below covers everything billed on usage, and a **Combined Total** row sums the two.

### Chat Performance

Latency percentiles for each model, optionally filtered by channel via the in-panel dropdown (web first when present). Two views, toggled in the panel header:

- **Table** — per-model latency: sample count (n), p50 / p90 / p99 / p999 percentiles, and min/max.
- **Overlay chart** — overlapping latency density curves so you can compare distributions across models at a glance.

Use this to spot a slow model or a slow channel before users complain.

### Recent Activity

A live tail of the last 10 events. It shows the same message columns as the [Logs](/logs) page, but **without** the expand chevron — events can't be expanded inline here:

| Column      | Width     | Notes                                       |
|-------------|-----------|---------------------------------------------|
| **Level**   | 10ch      | Color-coded: red `ERROR`, yellow `WARN`, muted `INFO`. |
| **Category**| 44ch      | The subsystem the event came from.          |
| **Agent**   | 16ch      | Owning agent id (or `—`).                    |
| **Message** | flex      | One-line description.                       |
| **Timestamp**| 48ch     | Local date · time (matches Logs format).     |

A segmented toggle in the panel header switches the table between two views:

- **All** (default) — the event tail described above.
- **Video** — recent video-generation jobs with their state (`PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED`), prompt, submitted time, and a *see in conversation* link to where each was requested. Use it to track `generate_video` jobs as they run, without leaving the dashboard.

For full filtering and expansion, click through to the [Logs](/logs) page.

## Logs

The [Logs](/logs) page is the operator's microscope. Every meaningful event — agent run, tool call, subagent spawn, task fire, channel webhook, configuration change, notification — is logged with a category, a level, a message, and (for some) a JSON details blob.

### Filters

Three filters across the top:

- **Category** — restrict to one subsystem. The standard set is `llm`, `channel`, `tool`, `task`, `agent`, `auth`, `system`. A **Subagents** optgroup adds the per-event subagent categories (`SUBAGENT_SPAWN`, `SUBAGENT_COMPLETE`, `SUBAGENT_ERROR`, `SUBAGENT_KILL`, `SUBAGENT_LIMIT_EXCEEDED`, `SUBAGENT_TIMEOUT`).
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
Events older than the configured retention window (default 30 days, see `jclaw.logs.retention.days` in `application.conf`) are swept by `EventLogCleanupJob` on a daily tick. Active investigations should be exported before the cutoff.
:::

## Sidebar status pip

In addition to these two pages, the small colored pip at the bottom of the collapsed sidebar (or the labeled row when expanded) reports the live API status. Red means the backend is unreachable — most pages will fail until it recovers, and a banner at the top of the page lets you re-check on demand. A second pip below it reports whether the running Play framework version matches the `.play-version` pin (green = match, amber = drift, hidden in dist installs where the file is absent).

## Where to go next

- [Settings](/guide#settings) — to change the behavior of whichever subsystem you're investigating.
- [Chat](/guide#chat) — to reproduce a problem and watch it surface in the [Logs](/logs).
- [Subagents](/guide#subagents) — for run-level inspection of fan-out workflows.
- [Tasks](/guide#tasks) — for run-level inspection of scheduled work.
