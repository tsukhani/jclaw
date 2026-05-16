# Conversations & Channels

JClaw separates **conversations** (threads of messages) from **channels** (the chat surface those messages came in on). [Chat](/chat) is the built-in surface; channels let agents reach you through Slack, Telegram, or WhatsApp.

This section covers both: how to browse your conversation history, and how to plug an agent into an external chat app.

## Conversations

The [Conversations](/conversations) page is a searchable archive of every top-level thread you've ever had — across [Chat](/chat), Slack, Telegram, and WhatsApp. Subagent-run transcripts are owned by their parent conversation and live on the [Subagents](/subagents) page instead, not here.

### Filtering and search

The filter bar above the table accepts:

- **Name** — partial match on the conversation preview (the first user message, usually).
- **Channel** — restrict to `web`, `slack`, `telegram`, `whatsapp`, etc.
- **Agent** — restrict to conversations served by a specific agent.
- **Peer** — when the channel is external, the external user id (phone number, Telegram handle, Slack user id).

Filters compound. Clear them by clicking the **×** on each chip, or by removing them in the filter bar.

### What each row shows

| Column      | Meaning                                                                              |
|-------------|--------------------------------------------------------------------------------------|
| **ID**      | The conversation id. Use this when reporting an issue or referencing in a tool call. |
| **Preview** | The conversation's first user message (truncated).                                   |
| **Channel** | Where it came in from.                                                               |
| **Agent**   | Which agent answered.                                                                |
| **Peer**    | External user id (blank for in-app web chat).                                        |
| **Messages**| How many messages are in the thread.                                                 |
| **Created / Updated** | Timestamps.                                                                |

Click any row to open the conversation in [Chat](/chat) (read-only if it came from a subagent run; fully editable if it's your own thread).

### Exporting

The **Export all** button downloads the current filtered view as a CSV — useful for audit, sharing, or feeding into another tool.

### Deleting

Select one or more rows and use the bulk action menu to delete. Deletion removes the thread and all its messages permanently; there's no undo.

:::gotcha
Deleting a conversation cascade-deletes every subagent run spawned from it — both the child transcripts and their audit rows on the [Subagents](/subagents) page. This is true for both the per-row delete and the bulk **Delete all matching** action. If you want to keep a child transcript, open it from the [Subagents](/subagents) page and use [Chat](/chat) → **Export as Markdown** *before* deleting the parent.
:::

## Channels

The [Channels](/channels) page lets you connect external chat surfaces so an agent can serve users outside the JClaw web app. JClaw currently supports:

- **Telegram** — per-bot bindings, each routed to its own agent.
- **Slack** — one workspace binding.
- **WhatsApp** — one Cloud-API binding.

Each surface shows its current status in the channel card: `active`, `inactive`, or `not configured`.

### Telegram

Click the **Telegram** card to open the per-bot binding list. Each binding is a Telegram bot token paired with the agent that bot should run as. Add as many bindings as you have bots; disable a binding to temporarily silence a bot without losing its config.

You'll need:

- A bot token from Telegram's BotFather.
- An [agent](/agents) you want this bot to run as.

The bot starts receiving messages as soon as you save and enable the binding.

### Slack

Configure a single workspace via:

- **botToken** — your Slack app's bot token (xoxb-…).
- **signingSecret** — the signing secret from your app's Basic Information page.

Save and toggle **Enabled** on. The Main Agent serves Slack traffic unless a specific agent is bound to the workspace.

### WhatsApp

Cloud-API integration. You'll need:

- **phoneNumberId** — from the WhatsApp Business Platform.
- **accessToken** — a long-lived access token for the same number.
- **appSecret** — your Meta app's secret.
- **verifyToken** — any string you pick; you'll paste the same string into Meta's webhook config so JClaw can verify incoming traffic.

Save, enable, and configure the webhook URL on Meta's side per their docs.

## How channels and conversations connect

When a message arrives over an external channel, JClaw:

1. Looks up the channel + peer (external user id) to find or create a conversation.
2. Routes the message to the bound agent (or to the Main Agent if no binding exists).
3. Streams the reply back over the same channel.

Every external message is also visible in [Chat](/chat) and [Conversations](/conversations), so you can read the full history in-app without bouncing between Slack and Telegram.

:::tip Test in the web app first
A new agent is much easier to iterate on inside [Chat](/chat) than over Telegram. Get the system prompt and tools right in-app, then bind a bot to it once you're happy.
:::

:::note Peer scoping
Each external user (peer) gets their own conversation thread with the bound agent. Two Telegram users talking to the same bot see independent threads; neither leaks into the other. The same isolation applies to Slack and WhatsApp.
:::

## Where to go next

- [Agents](/guide#agents) — configure the agent that serves your channel traffic.
- [Chat](/guide#chat) — verify your agent's behavior before binding it to a channel.
- [Settings](/guide#settings) — additional per-channel settings live under various Settings sections.
