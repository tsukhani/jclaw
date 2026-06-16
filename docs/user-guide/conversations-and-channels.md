# Conversations & Channels

So far this guide has been about chatting *inside the JClaw web app*. This section covers two things that extend the base loop:

- **Conversations** — every thread the platform has ever had, searchable and exportable.
- **Channels** — how to make an agent reachable outside the web app, over Slack, Telegram, or WhatsApp.

JClaw separates these two concepts on purpose: a **conversation** is a thread of messages; a **channel** is the surface that thread came in on. [Chat](/chat) is the built-in surface, and external channels are the extra layer.

## Conversations

The [Conversations](/conversations) page is a searchable archive of every top-level thread you've ever had — across [Chat](/chat), Slack, Telegram, and WhatsApp. Subagent-run transcripts are owned by their parent conversation and live on the [Subagents](/subagents) page instead, not here.

### Filtering and search

The filter bar at the top of the page accepts free-text keywords and typed keys:

| Key          | Example                | Matches                                                            |
|--------------|------------------------|--------------------------------------------------------------------|
| `q:`         | `q:morning`            | Lucene full-text on the conversation's messages.                    |
| `name:`      | `name:planning`        | Substring match on the conversation preview (the first user message). |
| `channel:`   | `channel:slack`        | Restrict to one of `web`, `slack`, `telegram`, `whatsapp`.          |
| `agent:`     | `agent:main-bot`       | Conversations served by a specific agent.                           |
| `peer:`      | `peer:+15551234567`    | The external user id (Telegram handle, Slack user id, phone number). Blank for web chat. |

Tokens combine — `q:retro agent:scrum-bot channel:slack` shows Slack conversations from the scrum-bot agent whose messages mention "retro." Clear a filter chip with the **×** on it, or remove the token from the bar.

### What each row shows

| Column                | Meaning                                                                              |
|-----------------------|--------------------------------------------------------------------------------------|
| **ID**                | The conversation id. Use this when reporting an issue or referencing in a tool call. |
| **Preview**           | The conversation's first user message (truncated).                                   |
| **Channel**           | Where it came in from.                                                               |
| **Agent**             | Which agent answered.                                                                |
| **Peer**              | External user id (blank for in-app web chat).                                        |
| **Messages**          | How many messages are in the thread.                                                 |
| **Created / Updated** | Timestamps.                                                                          |

Click any row to open the conversation in [Chat](/chat) (read-only if it came from a subagent run; fully editable if it's your own thread).

### Exporting

The **Export all** button downloads the current filtered view as a CSV — useful for audit, sharing, or feeding into another tool.

### Deleting

Select one or more rows and use the bulk action menu to delete. Deletion removes the thread and all its messages permanently; there's no undo.

:::gotcha Cascade
Deleting a conversation cascade-deletes every subagent run spawned from it — both the child transcripts and their audit rows on the [Subagents](/subagents) page. If you want to keep a child transcript, open it from [Subagents](/subagents) and use [Chat](/chat) → **Export as Markdown** *before* deleting the parent.
:::

## Channels

[Channels](/channels) lets you reach the same agents from outside JClaw. The page shows three cards, one per supported external surface:

- **Telegram** — per-bot bindings, each routed to its own agent.
- **Slack** — per-app bindings (one Slack app per agent), each routed to its own agent.
- **WhatsApp** — per-number bindings (one WhatsApp number per agent), each routed to its own agent.

Each card shows its current status: `<N> active` (one or more enabled bindings) or `not configured` (no bindings yet).

The shared mental model is the same across all three:

1. On the channel's card you create one or more **bindings**. Each binding holds that channel's credentials (bot tokens, signing secrets, webhook strings) **and** the [agent](/guide#agents) it routes to — agent selection is required (there is no "unbound → Main Agent" default for external channels).
2. Add as many bindings as you like — one per agent — so multiple bots/numbers can coexist, each running a different agent.
3. JClaw routes incoming messages to the binding's agent and streams replies back over the same channel.

External messages flow into the same [Conversations](/conversations) page as web chat, so you can read the full history in-app without bouncing between Slack and Telegram.

### Telegram

Click the **Telegram** card to open the per-bot binding list. Each binding is a Telegram bot token paired with the agent that bot should run as. Add as many bindings as you have bots; disable a binding to temporarily silence a bot without losing its config.

You'll need:

- A bot token from Telegram's BotFather.
- An [agent](/agents) you want this bot to run as.

The bot starts receiving messages as soon as you save and enable the binding. Telegram surfaces JClaw's [slash commands](/guide#chat) (`/new`, `/reset`, `/compact`, …) in its native autocomplete dropdown automatically.

### Slack

Click the **Slack** card to open its per-app binding list, then **+ New binding**. Each binding pairs one Slack app with the agent it runs as, so multiple Slack apps can coexist (one per agent):

- **botToken** — your Slack app's bot token (`xoxb-…`).
- **signingSecret** — the signing secret from your Slack app's Basic Information page.
- **agent** — the [agent](/agents) this Slack app routes to (required).

Save and toggle **Enabled** on; the bot starts serving immediately.

### WhatsApp

Cloud-API integration via Meta's WhatsApp Business Platform. Like Slack and Telegram it's per-binding — click the **WhatsApp** card, then **+ New binding** (one number per agent; multiple numbers can coexist). Each binding needs:

- **phoneNumberId** — from the WhatsApp Business Platform.
- **accessToken** — a long-lived access token for the same number.
- **appSecret** — your Meta app's secret.
- **verifyToken** — any string you choose; you paste the same string into Meta's webhook config so JClaw can verify incoming traffic.
- **agent** — the [agent](/agents) this number routes to (required).

Save, enable, and point Meta's webhook at JClaw per the WhatsApp Cloud API docs.

## How channels and conversations connect

When a message arrives over an external channel, JClaw:

1. Looks up the channel + peer (external user id) to find or create a conversation.
2. Routes the message to the binding's agent.
3. Streams the reply back over the same channel.

The end result: each external user sees a private, persistent thread with the agent, and you see all of it consolidated in [Conversations](/conversations) and [Chat](/chat).

:::tip Test in the web app first
A new agent is much easier to iterate on inside [Chat](/chat) than over Telegram or Slack. Get the system prompt and tools right in-app, then bind a bot to it once you're happy.
:::

:::note Peer scoping
Each external user (peer) gets their own conversation thread with the bound agent. Two Telegram users talking to the same bot see independent threads; neither leaks into the other. The same isolation applies to Slack and WhatsApp.
:::

## Where to go next

Now that you've covered live and external chat, the next layers are about *what happens outside of a chat turn* — work that runs in parallel, on a schedule, or as a pure nudge:

- [Subagents](/guide#subagents) — fan out child agents from inside a conversation.
- [Tasks](/guide#tasks) — schedule background work an agent figures out at fire time.
- [Reminders](/guide#reminders) — schedule pre-written nudges that skip the LLM.
- [Subagents, Tasks, or Reminders?](/guide#subagents-tasks-reminders) — side-by-side comparison.
