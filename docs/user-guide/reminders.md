# Reminders

[Tasks](/guide#tasks) fire an agent turn on a schedule. **Reminders** strip that down further: a scheduled message you've already written, delivered to you verbatim, with no LLM in the loop at fire time. They're the lightest of the three "outside-this-turn" abstractions — pure scheduling, no thinking.

Reach for a reminder when:

- You want a personal nudge with no agent reasoning in the loop ("remind me to pay salaries tomorrow at 9am").
- You want the nudge to land somewhere visible without polluting your chat scrollback or the agent's context.
- The schedule is what matters, not the work — the agent doesn't need to *do* anything when the timer fires.

The [Reminders](/reminders) page is the dashboard for everything you've scheduled. If you're choosing between reminders, [tasks](/guide#tasks), and [subagents](/guide#subagents), the [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders) section lays out the three side by side.

## How reminders get created

Reminders are created by **agents** through the same `task_manager` tool that creates regular [tasks](/guide#tasks) — they're tasks with `payloadType="reminder"`. Ask in [Chat](/chat):

> "Remind me to take the laundry out in 30 minutes."
> "Remind me tomorrow at 9am to pay salaries."
> "Every Monday at 10am, remind me to do the team retro."

The agent picks the right schedule shape and writes the *description* as the reminder text you want to see:

- A **duration** (`30m`, `2h`, `1d`) for "in N minutes/hours/days." This is a one-shot.
- An **absolute date-time** (e.g. `2026-06-13T15:00`, interpreted in the task's timezone) for a **one-time reminder on a specific day** — "remind me at 3pm on June 13." This is a one-shot.
- A **recurring interval** (`every 30m`, `every 2h`, `every 1d`) for a **repeating** reminder on a fixed cadence.
- A **cron expression** for a **repeating** reminder with calendar-aligned timing — "every Monday at 10am."

**A one-time reminder fires once and then completes** (status `PENDING` → `COMPLETED`, and by default it auto-deletes itself — see [Auto-delete after firing](/guide#reminders)). **A repeating reminder stays `ACTIVE`** and keeps firing on its cadence. Use an absolute date-time, not a cron, for something that should happen only once — a cron with a fixed month/day would silently repeat every year.

The agent should not phrase the description as instructions to itself — when a reminder fires, that text is delivered verbatim.

:::note The agent gets out of the way
At fire time, JClaw skips the LLM round-trip entirely. The fire path writes a notification (web) or sends a Telegram message with a 🔔 prefix and closes the run. No tokens are consumed, no agent context is touched, no conversation history is appended.
:::

## Where reminders show up

### In the app (web)

A reminder targeted at the web channel writes a row to your notifications feed, which surfaces two ways:

- **Top-right toast** — the global notification overlay polls for unread notifications every 10 seconds. New reminders fade in at the top of the stack and stay until you act on them.
- **The [Reminders](/reminders) page** — every reminder you've ever scheduled, with its status, schedule, and last fire time.

### Via Telegram

A reminder routed to `telegram:<chat-id>` sends a regular Bot API message in that chat, prefixed with `🔔 Reminder:` so you can distinguish it from agent replies in busy scrollback. The agent's Telegram binding (configured under **Settings → Channels → Telegram**, or inherited from an ancestor agent) supplies the bot token.

Other channels (Slack, WhatsApp) aren't supported for reminder delivery yet — both surfaces would need a dedicated "notification" rendering rather than the regular chat-append behavior.

### Auto-routing to the calling chat

If you ask for a reminder in a web chat, the agent will leave the `delivery` field unset and JClaw fills it in from the conversation you're in. The reminder lands as a toast in the same app session. The same applies in Telegram — ask the agent in your Telegram chat and the reminder fires back there. The agent only needs to set `delivery` explicitly when you want the reminder routed somewhere other than the conversation it's being created from.

## Toast actions

Each toast offers three buttons:

| Action            | Effect                                                                                                                                          |
|-------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| **Mark as seen**  | Acknowledges this single notification. The toast fades out; the underlying task is untouched. Recurring reminders will fire again on schedule.   |
| 🗑 (trash)        | **Deletes the underlying task** as well as the notification. Stops all future fires for a recurring reminder; for a one-shot it's just cleanup. |
| ✕ (close)        | Same as **Mark as seen** — acknowledges and dismisses the toast without touching the task.                                                       |

The distinction between **Mark as seen** and **trash** matters most for recurring reminders: *seen* ends just this nudge; *trash* ends the whole schedule.

## Auto-delete after firing

A **one-time reminder cleans itself up** once it has fired successfully — a reminder you asked for on a specific day has served its purpose the moment it nudges you, so JClaw removes the reminder (and its run history) automatically. This is **on by default** for one-off reminders.

- **The notification you received is kept.** Only the scheduled reminder behind it is removed; your toast / Telegram nudge stays put, and the [Reminders](/reminders) feed of past notifications is unaffected.
- **It applies only to one-time reminders.** A **recurring** reminder (cron/interval) never auto-deletes — it keeps firing on its cadence — and regular [tasks](/guide#tasks) are *never* auto-deleted, since their run history is your audit trail.
- **A reminder that fails to fire is always kept**, so you can see what went wrong.

To **keep** a particular one-off reminder after it fires, untick its **Auto-delete** checkbox on the [Reminders](/reminders) page (or tell the agent "keep this reminder after it fires"). Tick it back on to restore the default.

## The Reminders page

Lists every reminder you've ever scheduled, with the same row regardless of delivery channel. Above the list, three cards track the lifecycle states that matter for reminders — **Pending** (one-time reminders waiting to fire), **Active** (recurring reminders), and **Failed**. Reminders skip the LLM, so there are no run-rate or success-rate metrics here.

| Column        | Meaning                                                                                                       |
|---------------|---------------------------------------------------------------------------------------------------------------|
| **Reminder**  | The text you (or the agent) wrote. Falls back to the task name when the description is empty.                  |
| **When**      | Relative-time hint for the next fire — "in 5m", "in 3h", or an absolute date for far-future fires.             |
| **Schedule**  | The original schedule shorthand — "every Mon 10:00", "0 0 9 * * *", "30m".                                     |
| **Channel**   | `web` or `telegram`. `web (auto)` means the channel was inferred from the calling chat at creation time.       |
| **Status**    | Same enum as tasks — a one-time reminder is `PENDING` (waiting) then `COMPLETED`; a recurring one is `ACTIVE`. |
| **Fired**     | When the most recent fire happened. The truth-of-record for "did the reminder go off when I said?"             |
| **Auto-delete** | For a one-time reminder, a checkbox: ticked (the default) means the reminder removes itself after it fires; untick to keep it. Recurring reminders show `—` (not applicable). |
| **Actions**   | **Delete** — hard-deletes the reminder and any past notifications it produced.                                 |

There is no **Run now** affordance for reminders — by definition a reminder is "fire on a schedule"; running one manually is just sending yourself an immediate message.

## Persistence

**Recurring reminders persist until you remove them.** **One-time reminders auto-delete after they fire** (see *Auto-delete after firing* above) — unless you've unticked their **Auto-delete** box, in which case they persist too. Either way, the **notifications** written by past fires persist independently of the reminder that produced them, so your nudges stay visible even after a one-off reminder cleans itself up. Removing a reminder yourself is always an explicit action:

- **Trash from a toast** — deletes the task + notification, and cascades to past notifications for that task.
- **Delete from [Reminders](/reminders)** — same effect, surfaced as a confirm dialog.
- **`deleteTask` via the agent** — same effect, but through the tool.

If a reminder fires and you don't act on the toast, the notification stays in the unread feed until you do.

## Quick reference

To create a reminder from the API directly rather than via the agent:

```text
POST /api/tasks
{
  "name": "pay-salaries",
  "description": "Pay salaries — Stripe + WhatsApp confirmations",
  "schedule": "0 0 9 * * *",
  "payloadType": "reminder",
  "delivery": "web:<conversationId>"
}
```

- `delivery` omitted → auto-routes to the calling chat (when posted from a chat session).
- `delivery: "web"` or `"telegram"` (channel name only, no `:target`) → fills the target from the calling chat.
- `delivery: "telegram:<chatId>"` → explicit Telegram route, regardless of where the call originated.

You'll likely never touch this endpoint — say it out loud in [Chat](/chat) and the agent will do the right thing.

## Tips and gotchas

:::tip Phrasing matters
Reminders skip the LLM, so write the description as the message you want to see. "Brush your teeth" → you see "Brush your teeth." "Tell the user to brush their teeth" → you see exactly that, talking to yourself in third person. The agent knows this rule, but if it gets the phrasing wrong, tell it to rewrite the reminder as the message you'll receive.
:::

:::tip Short bodies
1–2 lines work best. The toast is roughly a paragraph wide; longer reminders wrap but lose the "glance and act" feel.
:::

:::gotcha Recurring trash kills the schedule
Hitting the trash on a toast from a recurring reminder stops all future fires. If you only want to dismiss this single nudge, use **Mark as seen** or the **✕** instead.
:::

:::gotcha Telegram requires a binding
Telegram delivery needs a configured Telegram binding for the owning agent (or an ancestor agent — agents inherit). Without one, the fire is recorded as a delivery failure on the underlying `TaskRun` and no message is sent. Configure the binding via **Settings → Channels → Telegram** before creating Telegram-routed reminders.
:::

:::note LLM context isolation
Reminders never write to the agent's conversation history. The LLM cannot see past reminders on the next turn — which is exactly the point. If you want the agent to *act* on a schedule (not just notify you), schedule a regular [task](/guide#tasks) instead.
:::

## Where to go next

You've now seen all three outside-the-turn flavors. The natural next step is the comparison, then on to giving your agents more powers:

- [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders) — side-by-side comparison.
- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) — extend what agents can do.
- [Tasks](/guide#tasks) — the broader scheduling subsystem reminders are built on.
