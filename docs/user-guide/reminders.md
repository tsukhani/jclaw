# Reminders

[Tasks](/guide#tasks) fire an agent turn on a schedule. **Reminders** are simpler: a scheduled message you already wrote, delivered to you word for word, with no LLM in the loop at fire time. They are the lightest of the three "outside this turn" abstractions. Pure scheduling, no thinking.

Use a reminder when:

- You want a personal nudge with no agent reasoning ("remind me to pay salaries tomorrow at 9am").
- You want the nudge to land somewhere visible without filling your chat scrollback or the agent's context.
- The schedule matters, not the work. The agent does not need to do anything when the timer fires.

The [Reminders](/reminders) page is the dashboard for everything you scheduled. To compare reminders, [tasks](/guide#tasks), and [subagents](/guide#subagents), see [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders).

## How reminders get created

Agents create reminders through the same `task_manager` tool that creates regular [tasks](/guide#tasks). A reminder is a task with `payloadType="reminder"`. Ask in [Chat](/chat):

> "Remind me to take the laundry out in 30 minutes."
> "Remind me tomorrow at 9am to pay salaries."
> "Every Monday at 10am, remind me to do the team retro."

The agent picks the right schedule shape and writes the description as the reminder text you want to see:

- A **duration** (`30m`, `2h`, `1d`) for "in N minutes/hours/days". One-shot.
- An **absolute date-time** (for example `2026-06-13T15:00`, read in the task's timezone) for a one-time reminder on a specific day. "Remind me at 3pm on June 13." One-shot.
- A **recurring interval** (`every 30m`, `every 2h`, `every 1d`) for a repeating reminder on a fixed cadence.
- A **cron expression** for a repeating reminder with calendar-aligned timing. "Every Monday at 10am."

A one-time reminder fires once, then completes (status `PENDING` changes to `COMPLETED`, and by default it deletes itself; see [Auto-delete after firing](/guide#reminders-auto-delete-after-firing)). A repeating reminder stays `ACTIVE` and keeps firing on its cadence. Use an absolute date-time, not a cron, for something that happens only once. A cron with a fixed month and day would silently repeat every year.

The agent should not phrase the description as instructions to itself. When the reminder fires, the system delivers that text word for word.

:::note The agent gets out of the way
At fire time, JClaw skips the LLM round trip. The fire path writes a notification (web) or sends a Telegram message with a 🔔 prefix, then closes the run. No tokens are used, no agent context is touched, and no conversation history is written.
:::

## Where reminders show up

### In the app (web)

A reminder targeted at the web channel writes a row to your notifications feed. You see it two ways:

- **Top-right toast.** The global notification overlay polls for unread notifications every 10 seconds. New reminders appear at the top of the stack and stay until you act on them.
- **The [Reminders](/reminders) page.** Every reminder still scheduled or kept, with its status, schedule, and last fire time.

### Via Telegram

A reminder routed to `telegram:<chat-id>` sends a normal Bot API message in that chat, prefixed with `🔔 Reminder:` so you can tell it apart from agent replies in busy scrollback. The bot token comes from the agent's Telegram binding (configured on the [Channels](/channels) page → Telegram card, or inherited from an ancestor agent).

JClaw does not yet deliver reminders to other channels (Slack, WhatsApp). Both surfaces would need a dedicated notification view rather than the normal chat-append behavior.

### Auto-routing to the calling chat

If you ask for a reminder in a web chat, the agent leaves the `delivery` field unset and JClaw fills it in from the conversation you are in. The reminder arrives as a toast in the same app session. The same applies in Telegram: ask in your Telegram chat and the reminder fires back there. The agent needs to set `delivery` explicitly only when you want the reminder routed somewhere other than the conversation it was created from.

## Toast actions

Each toast has three buttons:

| Action | Effect |
| --- | --- |
| **Mark as seen** | Acknowledges this one notification. The toast disappears; the task is untouched. Recurring reminders fire again on schedule. |
| 🗑 (trash) | **Deletes the underlying task** and the notification. Stops all future fires for a recurring reminder; for a one-shot it is just cleanup. |
| ✕ (close) | Same as **Mark as seen**. Acknowledges and dismisses the toast without touching the task. |

The difference between **Mark as seen** and **trash** matters most for recurring reminders: *seen* ends only this nudge; *trash* ends the whole schedule.

## Auto-delete after firing

A one-time reminder cleans itself up after it fires successfully. A reminder you asked for on a specific day has served its purpose once it nudges you, so JClaw removes the reminder (and its run history). This is on by default for one-off reminders.

- **The notification you received is kept.** Only the scheduled reminder behind it is removed, so it leaves the [Reminders](/reminders) page. Your toast or Telegram nudge stays.
- **It applies only to one-time reminders.** A recurring reminder (cron or interval) never auto-deletes; it keeps firing on its cadence. Regular [tasks](/guide#tasks) are never auto-deleted, because their run history is your audit trail.
- **A reminder that fails to fire is always kept**, so you can see what went wrong.

To keep a one-off reminder after it fires, untick its **Auto-delete** checkbox on the [Reminders](/reminders) page, or tell the agent "keep this reminder after it fires". Tick it again to restore the default.

## The Reminders page

The page lists every reminder still scheduled or kept. Auto-deleted one-offs and reminders past task retention are gone. Each reminder shows as the same row regardless of delivery channel. Above the list, four cards track the lifecycle states that matter for reminders: **Active** (recurring reminders), **Pending** (one-time reminders waiting to fire), **Paused**, and **Failed**. Reminders skip the LLM, so there are no run-rate or success-rate metrics here.

You can pause a reminder like any task. Ask the agent (`task_manager`'s `pause` and `resume`) or call `POST /api/tasks/{id}/pause`. This page has no pause control of its own. A paused reminder skips its fires until it is resumed, counts under **Paused** rather than **Active** or **Pending**, and keeps showing its underlying status in the table. The filter key `status:PAUSED` lists them.

| Column | Meaning |
| --- | --- |
| **Reminder** | The reminder's name. Expand the row (chevron) to read the text you or the agent wrote. It falls back to the name when the description is empty. |
| **Schedule** | How it is scheduled, in human form. A recurring reminder shows its cadence ("weekly on Tue at 5 PM", "every 30 min"); a one-shot shows a live countdown ("in 3 hours"). Never a raw cron or ISO value. |
| **Status** | The same status values as tasks. A one-time reminder is `PENDING` (waiting), then `COMPLETED`. A recurring one is `ACTIVE`. |
| **Channel** | `web` or `telegram`, including when the agent inferred the route from the calling chat. `web (auto)` means no delivery was stored at all; such a reminder is delivered nowhere when it fires. A `tool:` delivery shows the bare tool name (for example `send_gmail_message`), but `tool:` delivery does not apply to reminders: the fire skips the agent, so no tool runs and nothing is delivered. |
| **When** | The exact wall-clock date and time of the next fire, in the effective timezone ("10 Jun 2026 · 1:15 pm"). Shows `—` once a one-time reminder has `COMPLETED`. |
| **Fired** | When the most recent fire happened. This is the record of truth for "did the reminder go off when I said?" |
| **Auto-delete** | For a one-time reminder, a checkbox. Ticked (the default) means the reminder removes itself after it fires. Untick to keep it. Recurring reminders show `—` (not applicable). |
| **Actions** | **Delete**: hard-deletes the reminder and the past notifications it produced. |

The page header also has a **Table / Calendar** switcher (Calendar is kept as `?view=calendar`), a filter bar that accepts free text or `q:` / `status:` / `type:` keys, an **Export** action, and a multi-select mode (the trash icon) for deleting several reminders at once with **Delete N**.

There is no **Run now** action for reminders. By definition, a reminder fires on a schedule. Running one manually is just sending yourself an immediate message.

## Persistence

Recurring reminders persist until you remove them. One-time reminders delete themselves after they fire (see *Auto-delete after firing* above), unless you unticked their **Auto-delete** box, in which case they persist too. Either way, the notifications written by past fires persist independently of the reminder that produced them, so your nudges stay visible after a one-off reminder cleans itself up. Removing a reminder yourself is always an explicit action:

- **Trash from a toast**: deletes the task and the notification, and also deletes the past notifications for that task.
- **Delete from [Reminders](/reminders)**: same effect, shown as a confirm dialog.
- **`deleteTask` via the agent**: same effect, through the tool.

If a reminder fires and you do not act on the toast, the notification stays in the unread feed until you do.

## Quick reference

To create a reminder through the API directly, instead of through the agent:

```text
POST /api/tasks
{
  "agentId": 1,
  "name": "pay-salaries",
  "description": "Pay salaries — Stripe + WhatsApp confirmations",
  "schedule": "0 0 9 * * *",
  "payloadType": "reminder",
  "delivery": "web:<conversationId>"
}
```

- `agentId` is required. It is the agent that owns the reminder; the request is refused without it.
- `delivery` is stored exactly as sent. This endpoint has no calling chat, so it fills in nothing. If you omit it, the reminder fires but is delivered nowhere. `"telegram"` with no chat id fails at fire time. Only an agent creating the reminder through `task_manager` gets the auto-routing described above.
- `delivery: "telegram:<chatId>"` sets an explicit Telegram route.

You will probably never touch this endpoint. Say it out loud in [Chat](/chat) and the agent does the right thing.

## Tips and gotchas

:::tip Phrasing matters
Reminders skip the LLM, so write the description as the message you want to see. "Brush your teeth" means you see "Brush your teeth." "Tell the user to brush their teeth" means you see exactly that, talking to yourself in third person. The agent knows this rule, but if it gets the phrasing wrong, tell it to rewrite the reminder as the message you will receive.
:::

:::tip Short bodies
One or two lines work best. The toast is roughly a paragraph wide. Longer reminders wrap but lose the quick "glance and act" feel.
:::

:::gotcha Recurring trash kills the schedule
The trash button on a toast from a recurring reminder stops all future fires. To dismiss only this one nudge, use **Mark as seen** or the **✕** instead.
:::

:::gotcha Telegram requires a binding
Telegram delivery needs a configured Telegram binding for the owning agent (or an ancestor agent; agents inherit). Without one, the system records the fire as a delivery failure on the underlying `TaskRun` and sends no message. Configure the binding on the [Channels](/channels) page, Telegram card, before you create Telegram-routed reminders.
:::

:::note LLM context isolation
Reminders never write to the agent's conversation history. The LLM cannot see past reminders on the next turn, and that is the point. If you want the agent to act on a schedule, not just notify you, schedule a regular [task](/guide#tasks) instead.
:::

## Where to go next

You have now seen all three outside-the-turn abstractions. The next step is the comparison, then giving your agents more abilities:

- [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders): side-by-side comparison.
- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp): extend what agents can do.
- [Tasks](/guide#tasks): the broader scheduling subsystem that reminders are built on.