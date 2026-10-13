# Chat

[Chat](/chat) is the core loop. You pick an agent, send messages, and watch the agent think, call tools, and reply. Every other capability in JClaw layers on top of this page: channels, scheduled work, subagents, external tools.

This section covers Chat on its own. The rest of the guide is how to bend it.

## The layout

The Chat page has three regions:

- **Header**: the **Agent:** dropdown on the left, the model picker in the middle, and the context meter on the right.
- **Message rail**: the active conversation: your messages, the agent's replies, tool calls, and inline reasoning when the model supports it.
- **Composer**: at the bottom, where you type, attach, and send.

The context meter appears once a conversation has started. It reads `used / window`: the latest turn's prompt and completion tokens against the model's context window, over a bar that turns amber at 70% and red at 90%. Hover it for **Context usage** as a percentage, **Prompt tokens**, **Thinking tokens** and **Cached tokens** when there are any, **Completion** and **Current context**, then **Conversation total** (every turn's tokens added up), **Compactions** once the conversation has been compacted (each one lowers the current context), and the running **Turns** and **Cost** (when the model has pricing).

Closing or refreshing the page is safe. Your conversation history is server-side. To reopen an earlier thread, click its row on [Conversations](/conversations).

## Picking an agent

The **Agent:** dropdown in the header lists the agents on the [Agents](/agents) page (subagents aside), starting on the Main Agent. With only one agent, it shows that agent's name instead. The composer at the bottom always sends to the currently selected agent.

If you do not have any agents yet, [Agents](/guide#agents) is the next stop. Come back when you do.

## Sending a message

Type and press <kbd>Enter</kbd> to send. <kbd>Shift</kbd>+<kbd>Enter</kbd> inserts a newline without sending.

The reply streams in real time. While the model is generating, the **Send** button turns into a **Stop** button. Click it to interrupt the current generation.

Replies render as Markdown, and math the model writes in TeX is typeset with KaTeX: `$…$` and `\(…\)` inline, `$$…$$` and `\[…\]` as a display equation. The equation scrolls inside the bubble when it is too wide. A dollar amount in prose stays text: `$` opens math only when a non-space follows it, and closes only after a non-space and before anything but a digit. So "$5 and $10" is left alone. Malformed TeX shows as an inline error rather than breaking the reply.

Ask for a Lewis structure and the model writes a fenced code block with the language hint `lewis` holding one SMILES string. The chat draws it as an electron-dot structure, with every atom labeled and its lone pairs and radicals marked. Rings must be in Kekulé form (`C1=CC=CC=C1`, not `c1ccccc1`), and only main-group elements are drawn. The block shows as code until the drawing has loaded. A SMILES that cannot be drawn keeps its code block, with a one-line reason under it.

For a 3D model of a molecule or crystal, the model writes a fenced block with the language hint `structure`. The hint can optionally be followed by a format (`cif`, `pdb`, `sdf`, `mol`, `xyz`, or `mol2`) and a caption (`structure cif Sodium formate`). The body is either the structure text, up to 500,000 characters, or one path to a structure file in the agent's workspace, whose extension names the format. With no format named, the chat recognizes it from the text. The 3D view replaces the code block once the reply is complete. Drag to rotate, and switch between **Ball & stick** and **Space-filling**. A crystal adds **1×1×1** to **3×3×3** cell repeats (a repeat over 20,000 atoms is disabled) and a **Unit cell** box. **Coordination polyhedra** appears where the structure has them. A file-backed structure links the file for download. At most four views are live at once; an older one folds to a **Show 3D view** button. A block that cannot be read keeps its code block, with the reason under it.

If a turn fails, the reply says so in three parts: **What broke**, **What to check**, and **How to retry**. When the provider refused the call (a rejected key, an exhausted balance), the reply names the provider and model. The raw detail is in [Logs](/logs).

A reply the model cut off at its output-token limit ends with an amber **Reply was truncated by the model** marker, so the partial text is not mistaken for a complete answer. A subagent's announce card carries the same marker when the child's reply was cut off.

If you regret a message, hover over it. You will see **Copy to clipboard**, **Reply**, **Edit & resubmit**, and **Delete message** controls. Editing rewinds the conversation to that point and re-runs it from the edited text. A reply has **Copy to clipboard**, **Reply**, **Regenerate response**, and **Delete message** on hover. Regenerating removes that reply and the message it answered, then sends your message again for a fresh answer. **Reply** quotes that message above the composer for your next one. The agent receives the quoted text ahead of yours, so it knows what you are answering. A slash command is never quoted, and a quote keeps its first 16,000 characters.

Replying on a channel does the same. On Telegram, a reply to any message, or a quote of part of one, brings its text into the turn. For a media message with no caption, it brings the message's kind instead. In both cases, it names who wrote the message. Slack and WhatsApp send only the replied-to message's id, so there the quote carries text only for a message JClaw sent outside a conversation, such as a task result or a message the agent sent with the `message` tool. On WhatsApp, that means a reply that quotes one. On Slack, it means the first reply in its thread (later replies find it in the conversation already). A Slack thread reply under such a post reaches the bot without an @mention.

To start over, click **New conversation** (the pencil-and-square icon in the composer footer). The page clears for a fresh thread; the previous one stays saved.

To hear a reply, hover it and click the **speaker icon**. Text-to-speech streams the answer aloud sentence by sentence, using the engine you pick in [Settings → Speech](/guide#settings).

## Model and thinking for this conversation

The model picker in the header and the **Think** pill in the composer change the conversation you are in, never the agent. The agent's default provider, model, and thinking mode live on the [Agents](/guide#agents) page, and every new conversation starts from them. A pick here is an override scoped to one conversation, the same as `/model` and `/think` on Telegram, Slack, and WhatsApp. On those channels, only the binding's owner may change the model (see [Slash commands](/guide#chat-slash-commands)).

- On a fresh chat, the header shows the agent's defaults. Pick a different model or change Think, and the picks are held until your first message, which starts the conversation with them applied.
- With a conversation open, a pick takes effect on the next turn of that conversation.
- Nothing on the page flags that a conversation is off the agent's defaults; the header simply shows what is in force. To drop picks made on a fresh chat before sending, reload the page. To put an open conversation back on the defaults, use `/model reset` and `/think reset`.
- A pick the server refuses (a model the provider no longer lists, a level the model does not offer) shows its reason in the composer, where attachment errors appear, rather than failing silently.
- Once the [Model Router](/guide#settings-model-router) lists a model for its Chat class, the picker also offers **Auto (best value)**. Each turn goes to the model the router picks for that prompt, at a reasoning effort it picks too, unless you set a Think level on the conversation, which still wins. Every routed reply carries a badge such as `Auto · Coding → <model> · medium effort`, flagged **failover** when the first choice failed and its fallback answered, or **budget** when it moved to a lighter model to save subscription credit. Hover it for the router's reason. On Auto, the composer's capability pills, Think included, describe the model the latest routed reply named, so a fresh Auto conversation shows none until its first reply.

Switching to a model that does not offer the current thinking level turns thinking off for the conversation. It does not send a level the model would reject.

A model declared unable to call tools, whether by its provider or by clearing **Can call tools** on it in [Settings → LLM Providers](/settings), carries a **no tools** tag in the model picker. Pick one for an agent that has tools or skills, and a **No tools** notice above the conversation says the model cannot call tools, so the agent's tools and skills are unavailable in this conversation until you pick a tool-capable model. A model with no declared capability counts as tool-capable.

## Attachments

Click the paperclip in the composer to attach files. JClaw supports:

- **Images**: sent natively to vision-capable models. Models without vision get a brief textual description **if** an Image Captioning backend is configured (cloud, or a local Ollama VLM; see [Settings → Image Captioning](/guide#settings)). Otherwise they receive a "description unavailable" note.
- **Documents**: PDFs and other text-extractable formats are parsed and inlined into the prompt. Scanned PDFs get OCR'd first (see [Settings → OCR](/guide#settings)).
- **Voice notes**: recorded directly in the composer (microphone button) or attached. Audio-capable models receive the audio. Other models receive a transcript (see [Settings → Transcription](/guide#settings)).
- **Video**: clips attached in the composer. Models that support video natively watch the clip directly. Otherwise JClaw interprets it for them: a dedicated video-interpretation model summarizes the clip. Failing that, frames are sampled and sent to a vision model as images, or captioned into a timestamped text summary for text-only models. Tune the sampling in [Settings → Video Interpretation](/guide#settings).

**Vision**, **Audio**, and **Video** capability pills appear in the composer footer only when the active model advertises that input natively. They let you tell at a glance whether the model will see the file, or just a transcript, description, or summary. Beside them, on reasoning-capable models, the **Think** pill toggles thinking for this conversation and opens the reasoning-level picker. See [Model and thinking for this conversation](/guide#chat-model-and-thinking-for-this-conversation).

## Voice mode

For a hands-free spoken conversation, click **Voice mode** (the voice button near the composer; distinct from the "Record voice" mic, which just attaches a clip). JClaw listens continuously and detects when you have finished speaking. It sends your turn to the agent and **speaks the reply back** aloud, sentence by sentence. Start talking again to interrupt: playback stops, and your new turn takes over. It reads replies with your [Settings → Speech](/guide#settings) engine. A text-only model gets a local transcript of what you said, while an audio-capable model hears your voice directly.

## Slash commands

Type `/` at the start of a message to open the built-in commands. A menu opens listing every command with a one-line description. Keep typing to filter it, use the arrow keys to move, and press Enter or Tab to pick one. Then press Enter again to send. They work in the web composer and in any external channel ([Telegram](/guide#conversations-and-channels), Slack, WhatsApp). Telegram surfaces the same list in its native autocomplete dropdown. On Slack, type them with `!` instead of `/` (`!help`, `!model <provider>/<id>`), because Slack will not deliver `/` commands inside threads. `/prompt` is the one whose shape differs by channel. The web has a box to drop text into; other channels do not. So on those, it replies with the prompt for you to copy instead.

On an external channel, `/subagent`, `/prompt`, and the `/model` forms that change the model (`/model <provider>/<id>`, `/model reset`) answer only the binding's owner: the Telegram user id on the binding, or the Slack binding's owner user id. Anyone else gets "Only the operator can use this command.", and that includes every WhatsApp sender and everyone on a Slack binding with no owner set. The other commands, plain `/model` and `/model status` included, work for everyone.

| Command | What it does |
|-------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `/new` | Start a fresh conversation in a new thread. |
| `/reset` | Clear the model's memory for the current conversation while keeping the thread. |
| `/compact` | Summarize older turns to free context. Optional focus hint: `/compact focus on the auth refactor`. |
| `/help` | Show the list of available commands. |
| `/model` | Show the current model and its capabilities. `/model <provider>/<id>` sets a per-conversation override. `/model reset` clears the override. |
| `/think` | Show the reasoning effort in force for this conversation. `/think off`, `/think <level>` (one the model advertises) set a per-conversation override; `/think reset` clears it. The agent's default is untouched. |
| `/usage` | Show how much of the model's context window the current conversation occupies. |
| `/stop` | Interrupt the current generation. (Same as clicking the Stop button.) |
| `/subagent` | Inspect, kill, or read transcripts of subagent runs spawned from this conversation. See [Subagents](/guide#subagents). |
| `/prompt` | Search your [Prompts Library](/guide#prompts): `/prompt code review`. In the web composer the prompt's text drops straight into the box; on other channels JClaw replies with it so you can copy and edit it (for the binding's owner only). `/prompt` on its own lists what you have saved. |

:::tip /compact when context fills up
`/usage` shows how much of the context window a conversation is using. When it is near full, `/compact` summarizes the older parts and keeps the recent turns verbatim, so the agent can keep working without losing the thread. Pass a focus hint when you only care about a specific subtopic. The summarizer keeps that thread tight.
:::

## Tool calls and reasoning

When the agent decides to call a tool, you see a tool-call chip in its message. Click to expand and see the arguments and the tool's response. Click again to collapse.

The first `browser` call on an install that has not yet fetched the browser shows a **Setting up the browser** progress bar under the reply while the one-time download runs. Later browser calls start straight away. [Settings → Browser](/guide#settings-browser) can run the same download ahead of time.

Reasoning-capable models (the ones that surface their internal thought before answering) render their reasoning in a separate, distinctively styled block above the final reply. Reasoning collapses by default. Click to expand. You can copy the reasoning text on its own.

Tool results are kept in full in the conversation history. What the model sees is smaller, in two steps.

- **Within a turn.** The model reads each result whole once, in the round after the tool runs. From the next round on, an agent with [Content Compression](/guide#agents) on sees a shorter view of a large result, with a `ccr_retrieve` handle. A very large result (about 24,000 characters or more by default) is shortened from the start.
- **On later turns.** A long result from an earlier turn (4,000 characters or more by default) is replaced by a one-line stub naming the tool, the original size, and a `ccr_retrieve` handle.

The agent fetches the full text back with a handle when it needs it. A list of actions or endpoints that the agent asked for is never shortened. The thresholds are in [Settings → Chat](/guide#settings-chat).

## Per-message usage

Every assistant message carries a small **tok/s** badge. Hover it for the full accounting of that turn: prompt, thinking, and cached tokens, completion, speed, wall-clock time, and the computed dollar cost. It also names the **Model** that answered, and, on a turn the Auto router sent, its **Route** (`Auto · <class>`, marked `(failover)` when the fallback answered).

Below those, when the provider reports them, come **provider-specific metrics**: numbers a provider returns that the common OpenAI usage schema has no field for. So they differ by provider rather than being a fixed list. Hovering a row shows the provider's own field name for it.

The most useful today come from OpenRouter. It reports what your call cost *it* upstream, alongside what it charged you:

| Row | Meaning |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------|
| Upstream inference cost | What the underlying provider charged OpenRouter for the call. Compare against **Cost** to see the routing margin. |
| Upstream inference prompt cost | The input half of that upstream charge. |
| Upstream inference completions cost | The output half. |
| Audio / image / video tokens | Per-modality token breakdown, for multimodal turns. |

Ollama reports nothing beyond the standard schema over its OpenAI-compatible endpoint, so the section is absent there by default. Turn on **useNativeApi** for an Ollama provider in [Settings → LLM Providers](/settings), and its chat requests use the daemon's native API instead. That adds the daemon's own per-request timings, shown as time:

| Row | Meaning |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------|
| Total duration | Server-side wall clock for the request, from receipt to the last token. |
| Load duration | Time spent loading the model into memory. A large value means the model had been evicted since the previous turn (see **keepAlive**). |
| Prompt eval duration | Time spent evaluating the prompt. |
| Eval duration | Time spent generating the reply. Completion tokens divided by this is the daemon's own tokens-per-second. |

A local daemon reports all four. Ollama Cloud reports total duration only, since the other three are node-local timings a hosted service does not expose. The same four appear as `llm_*_duration` histograms on the Chat Performance dashboard.

Metrics are summed across every round of a turn. So a turn that made several tool calls shows the total rather than the last round.

## Exporting a conversation

The composer has an **Export as Markdown** button. It downloads the full thread (messages, tool calls, and reasoning) as a markdown file you can share or archive.

## Running subagents

Every subagent run the conversation spawns into its own child conversation gets a row in a list that hangs from the chat header. Inline runs write into this conversation itself, so they get no row, though the list's **N subagents · N running** header counts them. A row appears as soon as its run is spawned and stays after the run ends. The list shows about four rows before it scrolls. Click its header to collapse or reopen it.

Each row shows the run's label (or the subagent's name when the run has no label), its status, and a time: how long a running run has been going, or how long ago a finished one ended. **Running** shows a spinner and **Completed** a plain word. Only **Failed**, **Killed**, and **Timed out** get a colored pill, whose tooltip gives the reason. The status updates in place when the run ends. A background run posts no announce card in the transcript; its outcome is here instead. A run the agent waited on with `subagent_yield` still leaves its announce card.

Click a row to expand it and read that run's transcript in place, read-only. A failed, killed, or timed-out run shows its reason above the transcript. While the run is still going, new messages appear as the subagent writes them. Collapse the row and expand it again, and the transcript you had loaded shows straight away, then catches up with anything written since. If a transcript fails to load, **Retry** in the panel fetches it again. **Open full transcript** at the bottom of the panel opens the child conversation as a full page.

The list holds a conversation's newest 100 runs. **View all →** in its header opens the full list on the [Subagents](/subagents) page, filtered to that conversation.

## Coding runs

When the agent hands work to an [external coding harness](/guide#subagents-acp-harness), a **Coding run** panel docks above the composer. It shows the run's log as the harness works: the folder, each tool call with the file it touched, and the harness's replies. **Kill** stops the run. The panel shows the final status when the run ends, with the reason if the run did not complete.

Only the conversation that started the run shows the panel. A coding run that a [task](/guide#tasks) started has no conversation, so no chat shows it. See [Watching a coding run](/guide#subagents-coding-run-log) for what each line means, and for the other places the log opens.

## Subagent transcripts are read-only

If you arrive at a conversation that was created by a subagent run (for example, via **Open full transcript** in an expanded subagent row, the "View full →" link on an announce card, or by clicking a row on the [Subagents](/subagents) page), the composer is disabled with the note **Subagent transcripts are read-only**. You can read the transcript but not extend it. The full page loads the transcript once. So a running subagent's transcript shows the messages as of when you opened it. To follow a run live, expand its row in the conversation that spawned it. **← Back to conversation** in the banner returns you to the conversation that spawned it.

## Where to go next

You have the base loop. The next layers are about who answers and where the conversation happens:

- [Agents](/guide#agents): create and configure the agents that show up in the Agent dropdown.
- [Conversations & Channels](/guide#conversations-and-channels): manage prior threads and connect Slack / Telegram / WhatsApp.
- [Subagents](/guide#subagents): fan out child agents from inside a conversation.
- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp): extend what your agents can do.