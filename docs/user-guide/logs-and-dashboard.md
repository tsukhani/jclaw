# Logs & Dashboard

Two read-only surfaces give you visibility into what JClaw is doing right now and what it's done lately.

- The [Dashboard](/) — the home page; charts and counters covering chat performance, cost, and recent activity.
- The [Logs](/logs) page — a filterable, searchable event stream for everything happening server-side.

## Dashboard

The [Dashboard](/) is the first thing you see after signing in. Four card stats at the top:

| Stat                  | What it counts                                                 |
|-----------------------|----------------------------------------------------------------|
| **Agents enabled**    | Enabled agents / total agents you own.                          |
| **Conversations had** | Cumulative count of all conversations you own.                  |
| **Channels active**   | Channels (Slack / Telegram / WhatsApp) currently enabled.       |
| **Tasks pending**     | Scheduled tasks waiting to run.                                 |

Below the stats are live panels that refresh every five seconds.

### Chat Cost

Token usage and dollar cost across recent conversations, aggregated by week. You can:

- Switch between **per-week** and **combined-total** views.
- Drill into the **per-model breakdown** to see which model is driving spend.
- Toggle between subscription and pay-per-token billing modes (cost calc adjusts).

Weeks with zero usage are hidden to keep the view tight.

### Chat Performance

Latency and throughput percentiles for each model, optionally filtered by channel. Two views:

- **Table** — p50/p95/p99 latency and tokens/second by model.
- **Distribution chart** — histogram of recent request latencies.

Click any column header to switch the focused metric. Use this to spot a slow model or a slow channel before users complain.

### Recent log events

A live tail of the last few events, mirroring the [Logs](/logs) page but condensed. Click any entry to jump to the full Logs view filtered to that category.

## Logs

The [Logs](/logs) page is the operator's microscope. Every meaningful event — agent run, tool call, subagent spawn, task fire, channel webhook, configuration change — is logged with a category, a level, a message, and (for important ones) a JSON details blob.

### Filters

Three filters across the top:

- **Category** — restrict to one subsystem (e.g., `agent`, `tool`, `channel`). Subagent-related categories are grouped together in a sub-menu.
- **Level** — `ERROR`, `WARN`, or `INFO`.
- **Search** — free-text match on the message body.

Filters compound. Clear by setting each one back to "All".

### Auto-refresh

A checkbox in the top-right. Off by default — when you're investigating a problem you usually want a stable view. Toggle on to keep new events flowing in every few seconds.

### Reading an event

Each row shows:

- **Time** — local time (hover for the full ISO timestamp).
- **Level** — color-coded `ERROR` (red), `WARN` (yellow), `INFO` (gray).
- **Category** — the subsystem the event came from.
- **Message** — a single line describing what happened.

If an event has structured details (JSON payload, stack trace, request/response body), the row is clickable — click to expand the detail block inline. Click again to collapse.

### Use cases

- **"Why didn't my agent reply?"** → filter by category `agent` and level `ERROR`.
- **"What did my channel webhook receive?"** → filter by category `channel`.
- **"Did my scheduled task fire?"** → filter by category `task`.
- **"Why was a tool slow?"** → filter by category `tool` and search the tool's name.

:::tip Start with ERRORs
When something is broken, set the level filter to `ERROR` first. Most issues surface as a single explanatory error line; the rest is noise until you have a hypothesis.
:::

:::note Privacy
Logs include the metadata of what happened, not the full conversation content. Message bodies aren't logged; tool arguments and responses are summarized rather than echoed.
:::

## Sidebar status pip

In addition to these two pages, the small green/red pip at the bottom of the sidebar reports the live API status. Red means the backend is unreachable — most pages will fail until it recovers, and the **Retry** button at the top of the page lets you re-check on demand.

## Where to go next

- [Settings](/guide#settings) — to change the behavior of whichever subsystem you're investigating.
- [Chat](/guide#chat) — to reproduce a problem and watch it surface in the [Logs](/logs).
- [Subagents](/guide#subagents) — for run-level inspection of fan-out workflows.
