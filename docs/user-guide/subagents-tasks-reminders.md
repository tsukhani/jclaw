# Subagents, Tasks, or Reminders?

JClaw gives you three ways to make work happen outside the flow of a live conversation. They look superficially similar — all three "do something that isn't the message you just sent" — but they sit on three orthogonal axes: *what* runs, *when* it runs, and *what you see*.

## The mental model

- **Subagent** = "fan out *this* turn." Parallelism in the *now*.
- **Task** = "fan out *later*, and have the agent figure out what to do then." Parallelism across *time*.
- **Reminder** = "fan out *later*, but I already know what to say — just don't forget to tell me." Pure scheduling, no thinking.

That's the whole story. The rest of this section is the detail behind each axis.

## Side by side

|                                  | [Subagent](/guide#subagents)                                                       | [Task](/guide#tasks)                                                                  | [Reminder](/guide#reminders)                                              |
|----------------------------------|------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| **What's the work?**             | A second LLM turn in a child context — agent reasoning, tool calls, a real reply.   | An agent turn fired in the background — same LLM-driven work, just deferred.          | **No work.** A pre-written message delivered verbatim.                     |
| **When does it run?**            | Right now (or shortly, via `async`), inside the current conversation's "thinking." | On a schedule: immediate / once at a time / every N / cron.                            | On a schedule (same shapes as a task).                                     |
| **Who triggers it?**             | The parent agent during its own turn.                                              | The agent via `task_manager`, in response to "do X every morning."                     | The agent via `task_manager` with `payloadType="reminder"`, in response to "remind me to X." |
| **LLM at fire time?**            | Yes — that's the whole point.                                                      | Yes — runs the full agent loop in a fresh conversation.                                | **No** — fire path skips the LLM entirely.                                  |
| **Lives across server restarts?**| No (in-flight runs die with the JVM).                                              | Yes (recurring schedules survive).                                                     | Yes.                                                                       |
| **Where does the output land?**  | Inline block, sidebar conversation, or async announce card in the parent chat.     | Conversation transcript on the [Conversations](/conversations) page; optional `delivery` channel for the final message. | Top-right toast (web) or 🔔-prefixed Telegram message; never enters chat history. |
| **Visible to the LLM next turn?**| Yes — the reply comes back into the parent (or via `yield_to_subagent`).           | Yes — the run's transcript is part of conversation history.                            | **No** — invisible by design.                                              |

## The load-bearing distinction

"**LLM at fire time?**" is the row that does most of the work. Tasks and reminders look identical from the outside — both are scheduled, both persist across restarts, both are created through the `task_manager` tool. The difference is whether the firing event burns tokens to *decide what to do*:

- A **task** fires the agent loop. Use it when the schedule is "do this work later" and you want the agent to figure out *how* at fire time. The schedule is the trigger; the work is open-ended.
- A **reminder** skips the loop. Use it when the schedule is "tell me this later" and you already know the message. The schedule is the trigger; the message is fixed.

Every other difference between tasks and reminders (cascade-delete behavior, toast vs conversation surface, the 🔔 prefix on Telegram, the fact that reminders never enter LLM context on the next turn) falls out of that one choice.

The subagent's "axis" is different. It's not about *when* — subagents always fire as part of the current turn (the `async` variant just lets the parent keep working in parallel). It's about *what context the work runs in*: a child conversation, a child agent, a child tool set, a child reply that can either come back to the parent (`yield_to_subagent`) or land as a standalone announce card for *you*.

## Choosing in practice

| If you'd say…                                                                  | Reach for…                |
|--------------------------------------------------------------------------------|---------------------------|
| "Research this in the background while we keep talking"                        | **Subagent**, `async`     |
| "Spawn three helpers to compare these options in parallel"                     | **Subagent**, `async` × 3 |
| "Every morning, summarize my Slack DMs and message me the result"              | **Task** (CRON)           |
| "In 30 minutes, run a build and let me know if it broke"                       | **Task** (SCHEDULED)      |
| "Remind me to pay salaries on the 1st of every month"                          | **Reminder** (CRON)       |
| "Remind me in 5 minutes to take the laundry out"                               | **Reminder** (SCHEDULED)  |
| "Pull up everything you know about X and write a one-pager I can edit"         | **Subagent**, `session`   |
| "Every Friday at 6pm, generate the weekend on-call summary and post it"        | **Task** (CRON)           |

When in doubt: if the schedule is "tell me this," it's a reminder; if the schedule is "do this work," it's a task; if it's happening as part of this turn, it's a subagent.

## Nesting

The three layers compose cleanly because they don't overlap. A common pattern:

> "Every weekday at 9am, spawn three subagents to scan Slack/Email/Calendar and have them post a combined briefing to my Telegram."

That's one **task** (the daily 9am schedule) whose description tells the agent to **spawn three subagents** (the parallel scans), whose combined output is delivered to a channel via the task's `delivery` field. Reminders don't fit here because the work is open-ended — but if the *result* of that briefing produces a follow-up nudge ("at 11am, remind me to review the briefing"), the agent can schedule that as a reminder during the task's run.

## Where to go next

- [Subagents](/guide#subagents) — the three spawn modes, two context modes, and the `async` + `yield` pattern.
- [Tasks](/guide#tasks) — the four task types, eight `task_manager` actions, and Spring 6-field cron.
- [Reminders](/guide#reminders) — toast and Telegram delivery surfaces, cascade-delete semantics.
