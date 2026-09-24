# Chat

[Chat](/chat) is the core loop. You pick an agent, send messages, watch the agent think, call tools, and reply. Every other capability in JClaw — channels, scheduled work, subagents, external tools — layers on top of this page.

This section covers Chat on its own. The rest of the guide is how to bend it.

## The layout

The Chat page has three regions:

- **Header** — the **Agent:** dropdown on the left, the model picker in the middle, and the context meter on the right.
- **Message rail** — the active conversation: your messages, the agent's replies, tool calls, and inline reasoning when the model supports it.
- **Composer** — at the bottom, where you type, attach, and send.

Closing or refreshing the page is safe. Your conversation history is server-side; to reopen an earlier thread, click its row on [Conversations](/conversations).

## Picking an agent

The **Agent:** dropdown in the header lists the agents on the [Agents](/agents) page (subagents aside), starting on the Main Agent; with only one agent it shows that agent's name instead. The composer at the bottom always sends to the currently-selected agent.

If you don't have any agents yet, [Agents](/guide#agents) is the next stop — come back when you do.

## Sending a message

Type and press <kbd>Enter</kbd> to send. <kbd>Shift</kbd>+<kbd>Enter</kbd> inserts a newline without sending.

The reply streams in real time. While the model is generating, the **Send** button turns into a **Stop** button — click it to interrupt the current generation.

Replies render as Markdown, and math the model writes in TeX is typeset with KaTeX: `$…$` and `\(…\)` inline, `$$…$$` and `\[…\]` as a display equation, which scrolls inside the bubble when it is too wide. A dollar amount in prose stays text — `$` opens math only when a non-space follows it and closes only after a non-space and before anything but a digit, so "$5 and $10" is left alone. Malformed TeX shows as an inline error rather than breaking the reply.

If a turn fails, the reply says so in three parts — **What broke**, **What to check** and **How to retry** — and when the provider refused the call (a rejected key, an exhausted balance) it names the provider and model. The raw detail is in [Logs](/logs).

If you regret a message, hover over it: you'll see **Copy to clipboard**, **Edit & resubmit** and **Delete message** controls. Editing rewinds the conversation to that point and re-runs from the edited text. A reply has **Copy to clipboard**, **Regenerate response** and **Delete message** on hover; regenerating removes that reply and the message it answered, then sends your message again for a fresh answer.

To start over, click **New conversation** (the pencil-and-square icon in the composer footer). The page clears for a fresh thread; the previous one stays saved.

To hear a reply, hover it and click the **speaker icon** — text-to-speech streams the answer aloud sentence by sentence, using the engine you pick in [Settings → Speech](/guide#settings).

## Model and thinking for this conversation

The model picker in the header and the **Think** pill in the composer change the conversation you are in, never the agent. The agent's default provider, model and thinking mode live on the [Agents](/guide#agents) page and are what every new conversation starts from; a pick here is an override scoped to one conversation, the same as `/model` and `/think` on Telegram, Slack and WhatsApp (where only the binding's owner may change the model — see [Slash commands](/guide#chat-slash-commands)).

- On a fresh chat, the header shows the agent's defaults. Pick a different model or change Think and the picks are held until your first message, which starts the conversation with them applied.
- With a conversation open, a pick takes effect on the next turn of that conversation.
- Nothing in the page flags that a conversation is off the agent's defaults; the header simply shows what is in force. To drop picks made on a fresh chat before sending, reload the page. To put an open conversation back on the defaults, use `/model reset` and `/think reset`.
- A pick the server refuses (a model the provider no longer lists, a level the model does not offer) shows its reason in the composer, where attachment errors appear, rather than failing silently.
- Once the [Model Router](/guide#settings-model-router) lists a model for its Chat class, the picker also offers **Auto (best value)**: each turn goes to the model the router picks for that prompt, at a reasoning effort it picks too, unless you set a Think level on the conversation, which still wins. Every routed reply carries a badge such as `Auto · Coding → <model> · medium effort`, flagged **failover** when the first choice failed and its fallback answered, or **budget** when it moved to a lighter model to save subscription credit; hover it for the router's reason.

Switching to a model that does not offer the current thinking level turns thinking off for the conversation rather than sending a level the model would reject.

## Attachments

Click the paperclip in the composer to attach files. JClaw supports:

- **Images** — sent natively to vision-capable models. Models without vision get a brief textual description **if** an Image Captioning backend is configured (cloud or a local Ollama VLM — see [Settings → Image Captioning](/guide#settings)); otherwise they receive a "description unavailable" note.
- **Documents** — PDFs and other text-extractable formats are parsed and inlined into the prompt. Scanned PDFs get OCR'd first (see [Settings → OCR](/guide#settings)).
- **Voice notes** — recorded directly in the composer (microphone button) or attached. Audio-capable models receive the audio; other models receive a transcript (see [Settings → Transcription](/guide#settings)).
- **Video** — clips attached in the composer. Models that support video natively watch the clip directly; otherwise JClaw interprets it for them — a dedicated video-interpretation model summarizes the clip, or, failing that, frames are sampled and sent to a vision model as images, or captioned into a timestamped text summary for text-only models. Tune the sampling in [Settings → Video Interpretation](/guide#settings).

**Vision**, **Audio** and **Video** capability pills appear in the composer footer only when the active model advertises that input natively — so you can tell at a glance whether the model will see the file or just a transcript, description, or summary. Beside them, for reasoning-capable models, the **Think** pill toggles thinking for this conversation and opens the reasoning-level picker — see [Model and thinking for this conversation](/guide#chat-model-and-thinking-for-this-conversation).

## Voice mode

For a hands-free spoken conversation, click **Voice mode** (the voice button near the composer — distinct from the "Record voice" mic, which just attaches a clip). JClaw listens continuously, detects when you've finished speaking, sends your turn to the agent, and **speaks the reply back** aloud sentence by sentence. Start talking again to interrupt — playback stops and your new turn takes over. It reads replies with your [Settings → Speech](/guide#settings) engine; a text-only model gets a local transcript of what you said, while an audio-capable model hears your voice directly.

## Slash commands

Type `/` at the start of a message to access these built-in commands. A menu opens listing every command with a one-line description; keep typing to filter it, use the arrow keys to move, and press Enter or Tab to pick one — then Enter again to send. They work in the web composer and in any external channel ([Telegram](/guide#conversations-and-channels), Slack, WhatsApp); Telegram surfaces the same list in its native autocomplete dropdown. On Slack, type them with `!` instead of `/` (`!help`, `!model <provider>/<id>`), because Slack won't deliver `/` commands inside threads. `/prompt` is the one whose shape differs by channel — the web has a box to drop text into, other channels don't — so there it replies with the prompt for you to copy instead.

On an external channel, `/subagent`, `/prompt` and the `/model` forms that change the model (`/model <provider>/<id>`, `/model reset`) answer only the binding's owner — the Telegram user id on the binding, or the Slack binding's owner user id. Anyone else gets *Only the operator can use this command.*, and that includes every WhatsApp sender and everyone on a Slack binding with no owner set. The other commands, plain `/model` and `/model status` included, work for everyone.

| Command           | What it does                                                                                                  |
|-------------------|---------------------------------------------------------------------------------------------------------------|
| `/new`            | Start a fresh conversation in a new thread.                                                                   |
| `/reset`          | Clear the model's memory for the current conversation while keeping the thread.                                |
| `/compact`        | Summarize older turns to free context. Optional focus hint: `/compact focus on the auth refactor`.            |
| `/help`           | Show the list of available commands.                                                                          |
| `/model`          | Show the current model and its capabilities. `/model <provider>/<id>` sets a per-conversation override. `/model reset` clears the override. |
| `/think`          | Show the reasoning effort in force for this conversation. `/think off`, `/think <level>` (one the model advertises) set a per-conversation override; `/think reset` clears it. The agent's default is untouched. |
| `/usage`          | Show how much of the model's context window the current conversation occupies.                                |
| `/stop`           | Interrupt the current generation. (Same as clicking the Stop button.)                                          |
| `/subagent`       | Inspect, kill, or read transcripts of subagent runs spawned from this conversation. See [Subagents](/guide#subagents). |
| `/prompt`         | Search your [Prompts Library](/guide#prompts): `/prompt code review`. In the web composer the prompt's text drops straight into the box; on other channels JClaw replies with it so you can copy and edit it (for the binding's owner only). `/prompt` on its own lists what you have saved. |

:::tip /compact when context fills up
`/usage` will warn you when a conversation is pushing against the context window. `/compact` then summarizes the older parts and keeps the recent turns verbatim, so the agent can keep working without losing the thread. Pass a focus hint when you only care about a specific subtopic — the summarizer keeps that thread tight.
:::

## Tool calls and reasoning

When the agent decides to call a tool, you'll see a tool-call chip in its message. Click to expand and see the arguments and the tool's response. Click again to collapse.

The first `browser` call on an install that has not yet fetched the browser shows a **Setting up the browser** progress bar under the reply while the one-time download runs; later browser calls start straight away. [Settings → Browser](/guide#settings-browser) can run the same download ahead of time.

Reasoning-capable models (the ones that surface their internal thought before answering) render their reasoning in a separate, distinctively-styled block above the final reply. Reasoning collapses by default; click to expand. You can copy the reasoning text on its own.

Tool results are kept in full in the conversation history, but what the model sees on later turns is trimmed: a long result from an earlier turn (4,000 characters or more by default) is replaced by a one-line stub naming the tool, the original size and a `ccr_retrieve` handle, and the agent fetches the full text back with that handle when it needs it. The current turn's results are always sent whole. The thresholds are in [Settings → Chat](/guide#settings-chat).

## Per-message usage

Every assistant message carries a small **tok/s** badge. Hover it for the full accounting of that turn: prompt, thinking and cached tokens, completion, speed, wall-clock time, and the computed dollar cost. It also names the **Model** that answered and, on a turn the Auto router sent, its **Route** (`Auto · <class>`, marked `(failover)` when the fallback answered).

Below those, when the provider reports them, come **provider-specific metrics** — numbers a provider returns that the common OpenAI usage schema has no field for, so they differ by provider rather than being a fixed list. Hovering a row shows the provider's own field name for it.

The most useful today come from OpenRouter, which reports what your call cost *it* upstream alongside what it charged you:

| Row | Meaning |
|---|---|
| Upstream inference cost | What the underlying provider charged OpenRouter for the call. Compare against **Cost** to see the routing margin. |
| Upstream inference prompt cost | The input half of that upstream charge. |
| Upstream inference completions cost | The output half. |
| Audio / image / video tokens | Per-modality token breakdown, for multimodal turns. |

Ollama reports nothing beyond the standard schema over its OpenAI-compatible endpoint, so the section is absent there by default. Turn on **useNativeApi** for an Ollama provider in [Settings → LLM Providers](/settings) and its chat requests use the daemon's native API instead, which adds the daemon's own per-request timings, shown as time:

| Row | Meaning |
|---|---|
| Total duration | Server-side wall clock for the request, from receipt to the last token. |
| Load duration | Time spent loading the model into memory. A large value means the model had been evicted since the previous turn — see **keepAlive**. |
| Prompt eval duration | Time spent evaluating the prompt. |
| Eval duration | Time spent generating the reply. Completion tokens divided by this is the daemon's own tokens-per-second. |

A local daemon reports all four; Ollama Cloud reports total duration only, since the other three are node-local timings a hosted service does not expose. The same four appear as `llm_*_duration` histograms on the Chat Performance dashboard.

Metrics are summed across every round of a turn, so a turn that made several tool calls shows the total rather than the last round.

## Exporting a conversation

The composer has an **Export as Markdown** button. It downloads the full thread — messages, tool calls, and reasoning — as a markdown file you can share or archive.

## Running subagents

Every subagent run the conversation spawns into its own child conversation gets a row in a list that hangs from the chat header. Inline runs write into this conversation itself, so they get no row, though the list's **N subagents · N running** header counts them. A row appears as soon as its run is spawned and stays after the run ends. The list shows about four rows before it scrolls; click its header to collapse or reopen it.

Each row shows the run's label, or the subagent's name when the run has no label, its status, and a time: how long a running run has been going, or how long ago a finished one ended. **Running** shows a spinner and **Completed** a plain word; only **Failed**, **Killed** and **Timed out** get a coloured pill, whose tooltip gives the reason. The status updates in place when the run ends. A background run posts no announce card in the transcript — its outcome is here instead. A run the agent waited on with `subagent_yield` still leaves its announce card.

Click a row to expand it and read that run's transcript in place, read-only; a failed, killed or timed-out run shows its reason above the transcript. While the run is still going, new messages appear as the subagent writes them. Collapse the row and expand it again and the transcript you had loaded shows straight away, then catches up with anything written since. If a transcript fails to load, **Retry** in the panel fetches it again. **Open full transcript** at the bottom of the panel opens the child conversation as a full page.

The list holds a conversation's newest 100 runs. **View all →** in its header opens the full list on the [Subagents](/subagents) page, filtered to that conversation.

## Subagent transcripts are read-only

If you arrive at a conversation that was created by a subagent run (for example, via **Open full transcript** in an expanded subagent row, the "View full →" link on an announce card, or by clicking a row on the [Subagents](/subagents) page), the composer is disabled with the note **Subagent transcripts are read-only**. You can read the transcript but not extend it. The full page loads the transcript once, so a running subagent's transcript shows the messages as of when you opened it; to follow a run live, expand its row in the conversation that spawned it. **← Back to conversation** in the banner returns you to the conversation that spawned it.

## Where to go next

You've got the base loop. The next layers are about *who* answers and *where* the conversation happens:

- [Agents](/guide#agents) — create and configure the agents that show up in the Agent dropdown.
- [Conversations & Channels](/guide#conversations-and-channels) — manage prior threads and connect Slack / Telegram / WhatsApp.
- [Subagents](/guide#subagents) — fan out child agents from inside a conversation.
- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) — extend what your agents can do.
