# Tasks

[Subagents](/guide#subagents) fan work out *now*. **Tasks** fan work out *later*. A task is a unit of background work the agent runs on a schedule — outside the flow of a live conversation. Tasks come in four flavors:

- **Immediate** — runs as soon as it's created.
- **Scheduled** — runs once, at a specific date and time.
- **Interval** — runs every N seconds, minutes, hours, or days, with no calendar awareness.
- **Cron** — runs on a Spring 6-field cron schedule, or one of the `@daily` / `@hourly` shortcuts.

The [Tasks](/tasks) page is where you observe and manage every task across all your agents. For schedule-and-nudge work that doesn't need an agent turn — "remind me in 30 minutes to take the laundry out" — reach for [Reminders](/guide#reminders) instead. If you're trying to choose between tasks, reminders, and subagents, the [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders) section lays out the three side by side.

## How tasks get created

You don't create tasks from the [Tasks](/tasks) page directly — it's a viewer, not an editor. Tasks are created by **agents** through the built-in `task_manager` tool. The natural way is to ask an agent in [Chat](/chat):

> "Every weekday at 9am, summarize my unread Slack DMs and send me the summary on Telegram."

The agent calls `task_manager` with a `createTask` action, translates your phrasing into a schedule string, and your task appears on [Tasks](/tasks) within seconds.

### The unified schedule string

There is one creation action — `createTask` — that takes a single `schedule` parameter. The agent picks one of four shapes:

| Shorthand                                       | Type      | Meaning                                                |
|-------------------------------------------------|-----------|--------------------------------------------------------|
| `now`                                           | IMMEDIATE | Run as soon as the row is saved.                       |
| `30m` / `2h` / `1d`                             | SCHEDULED | Run once at *now + duration*.                          |
| `2026-06-13T15:00` (ISO date-time)              | SCHEDULED | Run **once** at a specific calendar moment, in the task's timezone (an explicit offset like `+08:00` is honored as-is). |
| `every 30m` / `every 2h` / `every 1d`           | INTERVAL  | Recur every N (minimum 1 minute via shorthand).        |
| Spring 6-field cron (`0 0 9 * * *`) or `@daily` | CRON      | Calendar-aligned recurrence.                           |

Your original input is stored verbatim, so the **Schedule** column reads "every 30m" back to you — not the normalized "every 1800 seconds."

For something that should happen **once** on a given day, use the absolute date-time — *not* a cron. It produces a one-shot `SCHEDULED` task that shows `PENDING`, fires once, then `COMPLETED`. A cron with a fixed month and day (e.g. `0 0 15 13 6 *`) would silently repeat *every year* and show as recurring (`ACTIVE`).

### What `task_manager` exposes

| Action                | What it does                                                                                                |
|-----------------------|-------------------------------------------------------------------------------------------------------------|
| `createTask`          | Create a task with any of the schedule shapes above.                                                        |
| `updateTask`          | Partial update by name — fields you don't provide stay as-is.                                               |
| `pause`               | Stop a recurring task from firing while keeping its cadence. Resume later without re-typing the schedule.   |
| `resume`              | Re-enable a paused recurring task.                                                                          |
| `runNow`              | Fire a task immediately by name. Revives `CANCELLED` rows on the way through.                               |
| `cancelTask`          | Set status to `CANCELLED` — stops fires but keeps the row so `runNow` can revive it later.                  |
| `deleteTask`          | Permanently delete the task and its run history. Irreversible.                                              |
| `listRecurringTasks`  | List every recurring task currently configured for this agent.                                              |

For the agent to be able to use any of these actions, **Tools → task_manager** must be ticked for that agent on the [Agents](/agents) page.

### Cancel vs delete

Two ways to stop a task. They are not the same:

- **Cancel** keeps the row around. The task stops firing but you can revive it later with `runNow` (it pops back to `PENDING` or `ACTIVE`) or by editing the schedule. Use when you might want it back.
- **Delete** is permanent. Removes the `Task` row and every `TaskRun` underneath it. Use when you are done with it for good.

## The Tasks page

The [Tasks](/tasks) page shows every task you own, with three view modes — Table, Cards, and Calendar — switched from the tab strip on the right. The view selection persists in the URL (`?view=cards`), so refresh and shareable links survive.

### Dashboard stats

A KPI strip above the list shows **Runs today**, **Success rate**, **Avg duration**, and the live **Pending / Running / Failed** task counts. The first three are derived from your task **run history**. To clear them, click the **Reset stats** control (the circular-arrow icon in the page header, next to the retention label): it deletes completed/failed/cancelled run history — in-flight runs are kept — so the run-derived KPIs reset. The live task-status counts are unaffected, since they reflect current task state, not history.

### Filters

The top of the page is a filter bar accepting free-text keywords and typed keys:

| Key       | Example              | Matches                                                                          |
|-----------|----------------------|----------------------------------------------------------------------------------|
| `q:`      | `q:summary`          | Lucene full-text on task name + description.                                     |
| `status:` | `status:PENDING`     | One of `PENDING`, `ACTIVE`, `RUNNING`, `LOST`, `COMPLETED`, `FAILED`, `CANCELLED`. |
| `type:`   | `type:CRON`          | `IMMEDIATE`, `SCHEDULED`, `INTERVAL`, or `CRON`.                                 |
| `agent:`  | `agent:morning-bot`  | Tasks owned by an agent matching this string.                                    |

Tokens combine — `q:summary status:PENDING type:CRON` shows pending cron tasks containing "summary."

### Columns (Table view)

| Column        | Meaning                                                                                                 |
|---------------|---------------------------------------------------------------------------------------------------------|
| **Name**      | Short identifier the agent gave the task.                                                               |
| **Type**      | `IMMEDIATE`, `SCHEDULED`, `INTERVAL`, or `CRON`.                                                        |
| **Schedule**  | Human-readable form of the schedule (the operator's original input — "every 30m", "0 0 9 * * *").       |
| **Status**    | Color-coded; see *Status states* below.                                                                 |
| **Agent**     | Which agent owns the task.                                                                              |
| **Next Run**  | When the task will fire next, formatted in the task's effective timezone (see *Timezones*).             |
| **Retries**   | Current attempts / max attempts. Failed fires are retried up to this cap before marking `FAILED`.       |
| **Actions**   | Per-row controls (see *Per-row actions*).                                                               |

### Status states

| Status      | Meaning                                                                                                       |
|-------------|---------------------------------------------------------------------------------------------------------------|
| `PENDING`   | One-shot (`IMMEDIATE` / `SCHEDULED`) waiting to fire.                                                          |
| `ACTIVE`    | Recurring (`CRON` / `INTERVAL`) in steady state, waiting for its next cadence.                                |
| `RUNNING`   | Mid-fire right now.                                                                                            |
| `LOST`      | Was `RUNNING` but the scheduler's heartbeat went stale. JClaw auto-recovers the underlying job shortly.       |
| `COMPLETED` | Terminal for one-shot tasks. Recurring tasks never reach `COMPLETED` unless explicitly cancelled.              |
| `FAILED`    | Hit the retry cap. Click **Retry** to requeue.                                                                 |
| `CANCELLED` | `cancelTask` was called. Row is preserved; `runNow` revives it.                                                |

### Lifecycle

Those states aren't independent — a task moves between them on a fixed set of transitions. One-shot tasks (`IMMEDIATE` / `SCHEDULED`) begin at `PENDING`; recurring tasks (`CRON` / `INTERVAL`) begin at `ACTIVE`. Both flip to `RUNNING` for the duration of each fire and back out when it ends:

```text
one-shot     PENDING ──▶ RUNNING ──▶ COMPLETED        (done — a fired one-shot is terminal)
recurring    ACTIVE  ──▶ RUNNING ──▶ ACTIVE           (loops once per cadence)

failure      RUNNING ──▶ PENDING/ACTIVE               (transient: retry on backoff)
             RUNNING ──▶ FAILED ──▶ (Retry) ──▶ PENDING/ACTIVE   (permanent / retries used up)

crash        RUNNING ──▶ LOST ──▶ (auto re-fire) ──▶ RUNNING ──▶ …

operator     PENDING/ACTIVE ──▶ CANCELLED ──▶ (runNow / re-enable) ──▶ PENDING/ACTIVE
```

Walking the transitions:

- **Fire starts.** At the scheduled moment the task flips to `RUNNING` and a fresh run opens — you'll see the blue `RUNNING` pill and a live elapsed-time counter in the run history.
- **Success.** A one-shot ends `COMPLETED` (it has served its purpose); a recurring task drops back to `ACTIVE` to await its next cadence. If the task has a delivery target, the reply is pushed afterward.
- **Transient failure.** A recoverable error (network blip, rate-limit) bumps the **Retries** counter and reschedules on a backoff — `30s → 60s → 5m → 15m → 1h`. The task returns to its waiting state between attempts, then re-enters `RUNNING` on the retry.
- **Permanent failure.** A non-recoverable error, or the retry cap reached, ends the task `FAILED`. **Retry** requeues it.
- **Crash recovery.** If the server stops mid-fire, the task is left `RUNNING` with a stale scheduler heartbeat. JClaw marks it `LOST` after ~1 minute so you can see it stalled, then the scheduler automatically re-fires it (~2 minutes) — `LOST → RUNNING → COMPLETED/FAILED` — with no action from you. **Retry** skips the wait.
- **Operator stop.** *Cancel* moves a task to `CANCELLED` (the row is kept; `runNow` or **Re-enable** revives it). Cancelling a single in-flight **run** stops only that fire and returns the task to its waiting state — the recurring schedule is left intact.

:::note Reminders ride the same machine
[Reminders](/guide#reminders) move through these exact states; only the *body* of a fire differs — a verbatim nudge instead of an agent turn. So a one-off reminder goes `PENDING → RUNNING → COMPLETED` (and removes itself right after if auto-delete is on), while a recurring reminder cycles `ACTIVE → RUNNING → ACTIVE` like any recurring task.
:::

### Per-row actions

| Icon             | Appears when                       | Effect                                                                                |
|------------------|------------------------------------|---------------------------------------------------------------------------------------|
| 🚫 Cancel        | Status is `PENDING` or `ACTIVE`    | Stops the task firing. Recurring tasks keep their schedule config; `runNow` revives.   |
| ↻ Retry          | Status is `FAILED` or `LOST`       | Requeues for another attempt.                                                         |
| 🗑 Delete        | Always                              | Hard-delete the task and its run history. Confirms first.                              |

For bulk delete, click the trash icon in the page header to enter multi-select mode, tick rows, then click **Delete N**.

### Cards and Calendar views

- **Cards** view is the same data laid out as denser per-task cards — easier to scan on a wide screen.
- **Calendar** view places `SCHEDULED` and `CRON` next-fire times on a monthly grid, handy for spotting double-bookings before a task fires.

## Cron syntax

JClaw uses **Spring 6-field cron**, where the first field is seconds:

```text
second  minute  hour  day-of-month  month  day-of-week
```

A few examples:

| Expression       | Meaning                            |
|------------------|------------------------------------|
| `0 0 9 * * 1-5`  | 9:00 every weekday morning.         |
| `0 */15 * * * *` | Every 15 minutes, around the clock. |
| `0/30 * * * * *` | Every 30 seconds.                   |
| `0 0 0 1 * *`    | Midnight on the 1st of every month. |
| `0 30 18 * * 5`  | 6:30 PM every Friday.               |
| `@daily`         | Midnight every day.                 |
| `@hourly`        | Top of every hour.                  |

When in doubt, ask the agent — `task_manager` knows Spring cron and can translate a plain-English schedule into a valid expression for you.

## Timezones

`CRON` and `SCHEDULED` tasks need a wall-clock interpretation. The effective zone resolves through this chain:

1. Per-task `timezone` (set by the agent or `updateTask`, validated as IANA — `America/New_York`, `Asia/Tokyo`, etc.).
2. Operator default at **Settings → Tasks → Default timezone**.
3. The `tasks.defaultTimezone` line in `application.conf` (default: `UTC`).
4. The JVM default (`ZoneId.systemDefault()`).

`INTERVAL` and `IMMEDIATE` tasks ignore timezone — their schedule is duration-based, not wall-clock.

## Retention

Completed and cancelled tasks are kept for **N days**, then swept by a daily cleanup job. The retention TTL is configured at **Settings → Tasks → Retention** and shown next to the page title so you don't get surprised by auto-deletes. Setting retention to `0` disables the sweep.

Independently of that day-based TTL, JClaw keeps only the **10 most recent runs per task** — each new fire prunes older run history for that task. A frequently-recurring task (an every-30-minutes labeler, say) therefore never grows its run table without bound: you always have the latest ten fires, while the day-based sweep removes whole completed/cancelled tasks past the TTL.

## What a task run looks like

When a task fires, JClaw runs the owning agent with the task description as its input and lets it run until the task completes or fails. The turn-by-turn transcript is saved as the **run's trace** — not as a chat. A task fire does **not** create an entry on the [Conversations](/conversations) page: each fire appears in that task's **run history** (expand the task's row on the [Tasks](/tasks) page), and clicking a run there opens its full trace in a side panel. While a run is in flight you can watch it — see *Watching a fire live* below. Failed fires log the error against the run and trigger a retry up to the configured cap.

If you want the task to ping you when it's done, build that into the task description:

> "Every weekday at 9am, summarize my unread Slack DMs and send me the summary on Telegram."

For a **schedule-with-no-LLM** path — pure notifications, no agent turn — schedule a [reminder](/guide#reminders) instead.

## Tips and gotchas

:::tip Name tasks well
Names are how `cancelTask`, `pause`, `deleteTask`, and `runNow` find the right row. "morning-summary" is much easier to address than "task-1742816512". Within an agent, recurring task names are unique; one-shot task names can repeat, and the addressing actions fan out across matches.
:::

:::tip Pause beats cancel for "be quiet for a week"
`pause` keeps the schedule and just no-ops the next fires. `resume` later picks up where you left off without re-typing cron. `cancelTask` is the heavier hammer; reach for it when you're not sure you want the task back.
:::

:::gotcha Tasks survive restarts
A recurring task stays scheduled across server restarts. If you bind a noisy task to a flaky tool, it'll keep firing on its schedule. Pause or cancel it from the [Tasks](/tasks) page or ask the agent to delete it.
:::

:::gotcha Recurring tasks need unique names
The agent rejects a `createTask` call when an agent already has a non-cancelled recurring task with the same name. Use `updateTask` to change the existing row, or `cancelTask` it first.
:::

:::note Multi-tenancy
Tasks are scoped to the owning agent. One agent never sees, pauses, or cancels another agent's tasks of the same name. The `runNow` and `cancelTask` actions only operate on tasks the calling agent owns.
:::

:::note Watching a fire live
A task fire doesn't appear on the [Conversations](/conversations) page — its transcript is the **run's trace**, not a chat — but you *can* watch it as it runs. Expand the task's row on the [Tasks](/tasks) page: an in-flight fire shows up in the **run history** as a `RUNNING` entry with a live elapsed timer and a one-line preview of its latest step. Click that entry to open the **trace panel**, which fills in turn-by-turn as the fire proceeds (refreshed every couple of seconds), then settles on the complete trace when the run finishes.
:::

## Where to go next

Tasks fire an agent turn on a schedule. The third outside-the-turn flavor strips even the agent out of the loop:

- [Reminders](/guide#reminders) — schedule-with-no-LLM nudges that surface as toasts or Telegram messages.
- [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders) — side-by-side comparison.
- [Agents](/guide#agents) — to enable `task_manager` on the agents that should be able to schedule work.
