# Chat

[Chat](/chat) is JClaw's primary work surface. You pick an agent, send messages, attach files or voice notes, and watch the agent think, call tools, and reply. Every conversation is persisted and reachable later from [Conversations](/conversations) or the [Chat](/chat) sidebar.

## Layout

The Chat page has three regions:

- **Left sidebar** — a list of conversations for your current agent, plus a switcher to change which agent you're chatting with. New conversations appear here as you create them.
- **Message rail** — the active conversation: your messages, the agent's replies, tool calls, and inline reasoning (when the model supports it).
- **Composer** — at the bottom, where you type, attach, and send.

Closing or refreshing the page is safe. Your conversation history is server-side; you'll find the same thread on return.

## Picking an agent

The sidebar shows agents you've enabled on the [Agents](/agents) page. Click one to switch your active conversation list to that agent. The composer at the bottom always sends to the currently-selected agent.

If you don't have any agents yet, [Agents](/agents) is where you create the first one.

## Sending a message

Type and press <kbd>Enter</kbd> to send. <kbd>Shift</kbd>+<kbd>Enter</kbd> inserts a newline without sending.

The reply streams in real time. While the model is generating, the **Send** button turns into a **Stop** button — click it to interrupt the current generation.

If you regret a message, hover over it: you'll see **Edit & resubmit** and **Delete message** controls. Editing rewinds the conversation to that point and re-runs from the edited text.

## Attachments

Click the paperclip in the composer to attach files. JClaw supports:

- **Images** — sent natively to vision-capable models. Models without vision get a brief textual description.
- **Documents** — PDFs and other text-extractable formats are parsed and inlined into the prompt.
- **Voice notes** — recorded directly in the composer (microphone button) or attached. Audio-capable models receive the audio; other models receive a transcript.

The image and audio icons in the composer light up green when the active model supports those inputs natively.

## Slash commands

Type `/` at the start of a message to access these built-in commands:

| Command           | What it does                                                                       |
|-------------------|------------------------------------------------------------------------------------|
| `/new`            | Start a fresh conversation in a new thread.                                        |
| `/reset`          | Clear the model's memory for the current conversation while keeping the thread.    |
| `/compact`        | Summarize older turns to free context. Optionally: `/compact focus-hint`.          |
| `/help`           | Show the list of available commands.                                               |
| `/model`          | Show the current model and its capabilities (context window, image/audio, etc.).   |
| `/usage`          | Show how much of the model's context window the current conversation occupies.     |
| `/stop`           | Interrupt the current generation.                                                  |
| `/subagent`       | Inspect, kill, or read transcripts of subagent runs. See [Subagents](/guide#subagents).           |

:::tip
`/compact` is your friend when a long conversation starts pushing against the context window. It summarizes the older parts and keeps the recent turns verbatim, so the agent can keep working without losing the thread.
:::

## Tool calls and reasoning

When the agent decides to call a tool, you'll see a tool-call chip in its message. Click to expand and see the arguments and the tool's response. You can collapse the chip again to clean up the view.

Reasoning-capable models (the ones that surface their internal thought before answering) render their reasoning in a separate, distinctively-styled block above the final reply. Reasoning collapses by default; click to expand. You can copy the reasoning text on its own.

## Exporting a conversation

The composer has an **Export as Markdown** button. It downloads the full thread — messages, tool calls, and reasoning — as a markdown file you can share or archive.

## Subagent transcripts are read-only

If you arrive at a conversation that was created by a subagent run (for example, via the "View full →" link on an announce card, or by clicking a row on the [Subagents](/subagents) page), the composer is disabled with the note **Subagent transcripts are read-only**. The conversation has already terminated; you can read but not extend it.

## Where to go next

- [Agents](/guide#agents) — create and configure the agents that show up in your sidebar.
- [Subagents](/guide#subagents) — fan out child agents from inside a conversation.
- [Conversations & Channels](/guide#conversations-and-channels) — manage prior conversations and connect external chat surfaces.
- [Settings](/guide#settings) — configure model providers and per-feature behavior.
