# Chat

[Chat](/chat) is the core loop. You pick an agent, send messages, watch the agent think, call tools, and reply. Every other capability in JClaw — channels, scheduled work, subagents, external tools — layers on top of this page.

This section covers Chat on its own. The rest of the guide is how to bend it.

## The layout

The Chat page has three regions:

- **Left sidebar** — a list of conversations for your current agent, plus a switcher to change which agent you're chatting with. New conversations appear here as you create them.
- **Message rail** — the active conversation: your messages, the agent's replies, tool calls, and inline reasoning when the model supports it.
- **Composer** — at the bottom, where you type, attach, and send.

Closing or refreshing the page is safe. Your conversation history is server-side; you'll find the same thread on return.

## Picking an agent

The sidebar shows agents you've enabled on the [Agents](/agents) page. Click one to switch your active conversation list to that agent. The composer at the bottom always sends to the currently-selected agent.

If you don't have any agents yet, [Agents](/guide#agents) is the next stop — come back when you do.

## Sending a message

Type and press <kbd>Enter</kbd> to send. <kbd>Shift</kbd>+<kbd>Enter</kbd> inserts a newline without sending.

The reply streams in real time. While the model is generating, the **Send** button turns into a **Stop** button — click it to interrupt the current generation.

If you regret a message, hover over it: you'll see **Edit & resubmit** and **Delete message** controls. Editing rewinds the conversation to that point and re-runs from the edited text.

To hear a reply, hover it and click the **speaker icon** — text-to-speech streams the answer aloud sentence by sentence, using the engine you pick in [Settings → Speech](/guide#settings).

## Attachments

Click the paperclip in the composer to attach files. JClaw supports:

- **Images** — sent natively to vision-capable models. Models without vision get a brief textual description **if** an Image Captioning backend is configured (cloud or a local Ollama VLM — see [Settings → Image Captioning](/guide#settings)); otherwise they receive a "description unavailable" note.
- **Documents** — PDFs and other text-extractable formats are parsed and inlined into the prompt. Scanned PDFs get OCR'd first (see [Settings → OCR](/guide#settings)).
- **Voice notes** — recorded directly in the composer (microphone button) or attached. Audio-capable models receive the audio; other models receive a transcript (see [Settings → Transcription](/guide#settings)).
- **Video** — clips attached in the composer. Models that support video natively watch the clip directly; otherwise JClaw interprets it for them — a dedicated video-interpretation model summarizes the clip, or, failing that, frames are sampled and sent to a vision model as images, or captioned into a timestamped text summary for text-only models. Tune the sampling in [Settings → Video Interpretation](/guide#settings).

The image and audio icons in the composer light up green when the active model supports those inputs natively, and a video capability pill appears for video — so you can tell at a glance whether the model will see the file or just a transcript, description, or summary.

## Voice mode

For a hands-free spoken conversation, click **Voice mode** (the voice button near the composer — distinct from the "Record voice" mic, which just attaches a clip). JClaw listens continuously, detects when you've finished speaking, sends your turn to the agent, and **speaks the reply back** aloud sentence by sentence. Start talking again to interrupt — playback stops and your new turn takes over. It reads replies with your [Settings → Speech](/guide#settings) engine; a text-only model gets a local transcript of what you said, while an audio-capable model hears your voice directly.

## Slash commands

Type `/` at the start of a message to access these built-in commands. They work identically in the web composer and in any external channel ([Telegram](/guide#conversations-and-channels), Slack, WhatsApp) — Telegram even surfaces them in its native autocomplete dropdown.

| Command           | What it does                                                                                                  |
|-------------------|---------------------------------------------------------------------------------------------------------------|
| `/new`            | Start a fresh conversation in a new thread.                                                                   |
| `/reset`          | Clear the model's memory for the current conversation while keeping the thread.                                |
| `/compact`        | Summarize older turns to free context. Optional focus hint: `/compact focus on the auth refactor`.            |
| `/help`           | Show the list of available commands.                                                                          |
| `/model`          | Show the current model and its capabilities. `/model <provider>/<id>` sets a per-conversation override. `/model reset` clears the override. |
| `/usage`          | Show how much of the model's context window the current conversation occupies.                                |
| `/stop`           | Interrupt the current generation. (Same as clicking the Stop button.)                                          |
| `/subagent`       | Inspect, kill, or read transcripts of subagent runs spawned from this conversation. See [Subagents](/guide#subagents). |

:::tip /compact when context fills up
`/usage` will warn you when a conversation is pushing against the context window. `/compact` then summarizes the older parts and keeps the recent turns verbatim, so the agent can keep working without losing the thread. Pass a focus hint when you only care about a specific subtopic — the summarizer keeps that thread tight.
:::

## Tool calls and reasoning

When the agent decides to call a tool, you'll see a tool-call chip in its message. Click to expand and see the arguments and the tool's response. Click again to collapse.

Reasoning-capable models (the ones that surface their internal thought before answering) render their reasoning in a separate, distinctively-styled block above the final reply. Reasoning collapses by default; click to expand. You can copy the reasoning text on its own.

## Exporting a conversation

The composer has an **Export as Markdown** button. It downloads the full thread — messages, tool calls, and reasoning — as a markdown file you can share or archive.

## Subagent transcripts are read-only

If you arrive at a conversation that was created by a subagent run (for example, via the "View full →" link on an async announce card, or by clicking a row on the [Subagents](/subagents) page), the composer is disabled with the note **Subagent transcripts are read-only**. The conversation has already terminated; you can read but not extend it.

## Where to go next

You've got the base loop. The next layers are about *who* answers and *where* the conversation happens:

- [Agents](/guide#agents) — create and configure the agents that show up in your sidebar.
- [Conversations & Channels](/guide#conversations-and-channels) — manage prior threads and connect Slack / Telegram / WhatsApp.
- [Subagents](/guide#subagents) — fan out child agents from inside a conversation.
- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) — extend what your agents can do.
