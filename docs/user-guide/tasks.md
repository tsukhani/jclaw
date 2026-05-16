# Tasks

A **task** is a unit of work an agent runs in the background, outside the flow of a live conversation. Tasks come in three flavors:

- **Immediate** — runs as soon as it's created.
- **Scheduled** — runs once, at a specific date and time.
- **Cron** — runs on a recurring schedule.

The [Tasks](/tasks) page is where you observe and manage every task across all your agents.

## How tasks get created

You don't create tasks from the [Tasks](/tasks) page directly — it's a viewer, not an editor. Tasks are created by **agents** through the built-in `task_manager` tool. The natural way to schedule one is to ask an agent in [Chat](/chat):

> "Every weekday at 9am, summarize my unread Slack DMs and send me the summary."

The agent calls `task_manager` with `scheduleRecurringTask` and your task appears on [Tasks](/tasks) within seconds.

The actions the tool exposes:

| Action                   | What it does                                                            |
|--------------------------|-------------------------------------------------------------------------|
| `createTask`             | Run a one-off background task immediately.                              |
| `scheduleTask`           | Run a one-off task at a specific date and time.                         |
| `scheduleRecurringTask`  | Run a task on a recurring cron schedule.                                |
| `deleteRecurringTask`    | Cancel a recurring task by name.                                        |
| `listRecurringTasks`     | List every recurring task currently active for the agent.               |

For the agent to be able to schedule tasks, **Tools → task_manager** must be ticked on the [Agents](/agents) page for that agent.

## The Tasks page

The [Tasks](/tasks) page shows every task you own, with two filters:

- **Status filter** — `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, or `CANCELLED`.
- **Type filter** — `IMMEDIATE`, `SCHEDULED`, or `CRON`.

### Columns

| Column      | Meaning                                                                            |
|-------------|------------------------------------------------------------------------------------|
| **Name**    | The short identifier the agent gave the task.                                       |
| **Type**    | `IMMEDIATE`, `SCHEDULED`, or `CRON`.                                                |
| **Status**  | Color-coded: yellow `PENDING`, blue `RUNNING`, green `COMPLETED`, red `FAILED`.     |
| **Agent**   | Which agent owns the task.                                                          |
| **Next Run**| When the task will run next (blank for immediate tasks that have already started).  |
| **Retries** | Current attempts / max attempts. JClaw retries failed tasks up to the configured limit before marking them `FAILED`. |
| **Actions** | Per-row controls (see below).                                                       |

### Per-row actions

- **Cancel** — appears on `PENDING` rows. Prevents the task from running. Already-running tasks ignore cancel; use **/stop** in [Chat](/chat) on the owning agent to interrupt instead.
- **Retry** — appears on `FAILED` rows. Requeues the task for another attempt.

`COMPLETED` and `CANCELLED` rows have no actions; they're historical records.

## Cron syntax

Cron expressions follow the standard 5-field format:

```text
minute  hour  day-of-month  month  day-of-week
```

A few examples:

| Expression       | Meaning                                          |
|------------------|--------------------------------------------------|
| `0 9 * * 1-5`    | 9:00 every weekday morning.                       |
| `*/15 * * * *`   | Every 15 minutes, around the clock.               |
| `0 0 1 * *`      | Midnight on the 1st of every month.               |
| `30 18 * * 5`    | 6:30 PM every Friday.                             |

When in doubt, ask the agent — `task_manager` knows cron and can translate a plain-English description into a valid expression for you.

## What a task run looks like

When a task fires, JClaw spins up the owning agent on its own conversation, gives it the task description as its input, and lets it run until the task completes or fails. You'll see the resulting conversation appear in [Conversations](/conversations) with the channel marked as `task`.

The agent doesn't talk to you directly when a task runs — it just produces a conversation record. If you want the task to ping you when it's done, build that into the task description:

> "Every weekday at 9am, summarize my unread Slack DMs and send the summary to my Telegram bot."

## Tips and gotchas

:::tip Name tasks well
Names are how `deleteRecurringTask` finds the right schedule. "morning-summary" is much easier to cancel later than "task-1742816512".
:::

:::gotcha Tasks survive restarts
A recurring task stays scheduled across server restarts. If you bind a noisy task to a flaky tool, it'll keep firing on its schedule. Cancel it from the [Tasks](/tasks) page or ask the agent to delete it.
:::

:::note No live progress
[Tasks](/tasks) doesn't stream the in-progress conversation. To see what the agent is doing right now, open the associated conversation from [Conversations](/conversations).
:::

## Where to go next

- [Agents](/guide#agents) — enable the `task_manager` tool on the agents that should be able to schedule work.
- [Chat](/guide#chat) — ask an agent to schedule a task for you.
- [Subagents](/guide#subagents) — for parallel work within a single conversation rather than scheduled work over time.
