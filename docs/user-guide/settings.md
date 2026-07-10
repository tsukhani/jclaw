# Settings

The [Settings](/settings) page is the operator's control panel. Configuration is split into sections you reach from a grouped table-of-contents rail — **System**, **Providers**, **Media**, **Agents & Automation**, and **Security** — and selecting a section shows just that panel. Most knobs apply live — sections that need a JVM restart say so inline.

This page summarizes each section. The settings page itself is the source of truth for current defaults and available knobs; hover any field's info icon for an inline tooltip.

## Timezone

Operator-wide settings.

| Key | Default | Meaning |
|-----|---------|---------|
| `app.timezone` | server JVM zone | The IANA timezone the assistant treats as the current wall-clock time ("now") in its system prompt. This is separate from the Tasks `defaultTimezone` (which governs `CRON`/`SCHEDULED` task scheduling). |

## Auto-update model prices

A standalone opt-in toggle hoisted above LLM Providers. When on, JClaw fetches the community-maintained `model_prices_and_context_window.json` from `github.com/BerriAI/litellm` nightly and fills in missing prices on your configured models. Prices you've set manually are never overwritten. Off by default — the toggle is explicit so the outbound GitHub call is a deliberate opt-in. A **Refresh now** button forces an immediate fetch.

## LLM Providers

The most important section. Each row is a model provider JClaw can talk to:

- **OpenAI, Anthropic, Google, Mistral, etc.** — first-party APIs. Paste in your API key and toggle **Enabled**.
- **Ollama (local or cloud)** — runs models on your hardware or on Ollama's cloud. Set the base URL; no API key needed for local.
- **LM Studio, vLLM** — other self-hosted, OpenAI-compatible servers running on your own hardware. Set the base URL (vLLM defaults to `http://localhost:8000/v1`); no API key needed. With Ollama Local these make up the **Local** group of the provider list.
- **OpenRouter, Groq, DeepSeek, Z.AI, Kimi, etc.** — aggregators and frontier-model providers. Same shape: base URL + API key.

For each provider you can:

- Set the **API key** (stored encrypted at rest).
- Set the **base URL** (most providers ship with a sensible default).
- Mark **Enabled / disabled** to hide the provider from the agent picker.
- **Manage models** — expand the row to see every model you've registered for the provider, with its prompt/completion/cached/cache-write prices, thinking-mode classification (always-thinks / capable / off), and capability badges (vision, audio, video, thinking) confirmed by the provider or guessed from the model name (a trailing `?`, e.g. `video?`, marks a guess).
- **Discover models** — pull the provider's live model catalog and pick which to register, with the provider's own price hints filled in. Filter the catalog by capability (vision / audio / video / thinking), by cost (free / paid, offered when the provider has free models), and by leaderboard rank.

If no provider is configured, no agent can answer — that's the most common cause of "the agent isn't replying." The [Agents](/agents) page shows a yellow **provider not configured** badge on rows whose provider is missing its key.

## Search Providers

Web search engines available to the `web_search` tool. Drag rows to **reorder priority** — providers are tried in order, and the next one is tried automatically if the first fails. Each row shows three states:

- **active** — enabled *and* API key configured.
- **needs API key** — enabled but the key is missing.
- **disabled** — turned off.

Typical providers: **Tavily**, **Brave Search**, **DuckDuckGo** (no key needed), **Serper**, **Bing**, **Google**, **Perplexity**. Each row links to that provider's signup page. Perplexity additionally exposes a `recencyFilter` (hour / day / week / month / year / none) so the LLM doesn't echo stale snippets.

## OCR

Optical character recognition for image and scanned-PDF attachments via the `documents` tool. Each backend (e.g. Tesseract) shows its detection status:

- **active** — binary detected on PATH *and* enabled.
- **disabled** — detected but turned off.
- **not detected** — binary missing on PATH; install hint shown inline.

Backends can only be toggled when their system dependency is present; install the missing binary and restart the JVM to enable. With OCR on, images and scanned PDFs get a text layer extracted before the prompt is built — useful when the model itself isn't vision-capable.

## Transcription

Pairs every audio attachment with a text transcript before it reaches the LLM. Audio-capable models still receive native audio; text-only models receive the transcript as text.

Master toggle, then a backend radio group:

- **OpenRouter** — reuses your OpenRouter API key from LLM Providers.
- **OpenAI** — reuses your OpenAI API key.
- **Self-Hosted Whisper** — runs `whisper.cpp` locally; the chosen model file (tiny / base / small / medium / large variants, ~75 MB to ~3 GB) downloads from Hugging Face on first use with a progress bar. Requires `ffmpeg` on PATH; the page warns inline if it's missing.

Cloud backends are disabled in the radio group until their underlying provider key is configured in LLM Providers. An **Active:** status line above the toggle shows the current backend (cloud provider, or Self-Hosted Whisper with the chosen model), or that transcription is off.

Below the backend picker, a **Diarization** subsection covers the who-spoke-when pipeline (independent of the master transcription toggle, since the diarize-audio tool runs its own local pipeline):

- **Diarization** — who-said-what transcripts are produced by an **audio-capable cloud chat model**: turn the subsection on, pick a provider (OpenAI or OpenRouter, using the API keys from LLM Providers) and one of its audio-capable models (the picker lists only models that accept audio input — the same ones showing an "Audio" badge in the chat model picker). The recording is sent to that model with a verbatim-diarization prompt; tell the agent who the speakers are ("the host is Anthony") and the transcript uses real names. Ordinary voice-note transcription stays local (whisper) and works without any of this. A greyed-out **Local audio model (on-device)** option marks planned functionality: fully offline diarization will return once local audio models mature (JCLAW-656 tracks the survey and revival criteria).
- **Emotion labels on diarized transcripts** — each diarized turn is tagged with how it was said (happy, sad, angry, disgust, fear, surprised or neutral), classified locally from the voice's tone by a multilingual emotion2vec+ model (~360 MB, downloads from Hugging Face on first use; holds up on non-English speech). Ordinary voice-note transcription is unaffected.

## Image Captioning

The vision analogue of Transcription: non-vision chat models get a short **text description** of an uploaded image before it reaches the LLM. Vision-capable models still receive the image natively. WebP and other formats are transcoded to PNG first so every backend can read them.

Master toggle, then a backend radio group:

- **OpenRouter** — reuses your OpenRouter API key from LLM Providers; captions with a vision model on OpenRouter.
- **OpenAI** — reuses your OpenAI API key.
- **Local VLM (Ollama)** — captions with a vision model you run in your own local Ollama (at `localhost:11434/v1`). There is **no bundled model**: pull a vision model in Ollama (e.g. `ollama pull moondream` or `llava`), add it under [LLM Providers](#llm-providers) and mark it **supports vision**, then pick it here.

The model picker is a dropdown of that backend's **vision-tagged** models — cloud backends are disabled until their key is set. An **Active:** status line shows the current backend and model. With captioning off, non-vision models receive a "description unavailable" note for images.

## Video Interpretation

How JClaw makes a video attachment legible to your chat model. The strategy is chosen automatically, in this order:

1. **The chat model handles video natively** — it watches the clip directly; nothing else runs.
2. **A dedicated video-interpretation model is configured** — when the chat model can't do video, this model interprets the clip and its description is spliced into the conversation as text.
3. **The chat model has vision** — JClaw samples still frames from the clip and sends them as images.
4. **Text-only chat model with [Image Captioning](#image-captioning) on** — sampled frames are captioned into a timestamped text summary.

The **dedicated video-interpretation model** has a master toggle, then a provider and a model picker; the picker is live-discovered from the provider and filtered to its **video-capable** models — the same shape as Image Captioning above.

Two knobs govern the frame-sampling fallbacks (strategies 3 and 4):

| Key             | Default | Meaning                                                                                                 |
|-----------------|---------|---------------------------------------------------------------------------------------------------------|
| `secondsPerFrame` | 10    | Sampling density — one frame is grabbed per this many seconds of video (1–60). Lower = denser sampling, more detail, higher cost. |
| `sampleFrames`    | 8     | Hard ceiling on frames extracted from a single clip (2–32), regardless of length.                        |

The effective frame count is `clamp(round(duration ÷ secondsPerFrame), 2, sampleFrames)`. An **Active:** status line shows which strategy your current main-agent model would use — watch, summarize, sample, or caption. If none apply, the video comes through with a note telling you to enable one of the above.

## Chat

Behavior limits for the in-app [Chat](/chat) surface:

| Key                    | Default | Meaning                                                                                          |
|------------------------|---------|--------------------------------------------------------------------------------------------------|
| `maxToolRounds`        | 100     | Maximum tool calls the agent can make per turn before it must give a final answer.               |
| `maxContextMessages`   | 50      | How many recent messages get sent with each LLM request. Older messages are dropped to stay in the context window. |

An **Advanced — context window & compaction** collapsible reveals four lower-level knobs:

| Key                          | Default | Meaning                                                                                                 |
|------------------------------|---------|---------------------------------------------------------------------------------------------------------|
| `compactionReserveTokens`    | 15000   | Tokens reserved at the end of the context window for the assistant reply. Auto-compaction triggers when the next prompt would exceed `contextWindow − reserve`. Larger reserve = compaction fires sooner. |
| `compactionMinTurns`         | 10      | Minimum messages in the to-summarize prefix before auto-compaction will run. Below this, the gate skips and trim drops oldest instead. Manual `/compact` uses a relaxed threshold (2). |
| `compactionKeepMessages`     | 10      | Minimum messages kept verbatim at the end of the conversation after compaction. Smaller keep = more aggressive summarization. |
| `jtokkit safety multiplier`  | 1.4×    | Fudge factor applied to jtokkit's token estimate when the model uses a fallback encoding (Kimi, DeepSeek, Gemma, Qwen, GLM, Mistral, Llama). Higher = trim/compact earlier, safer. OpenAI-family models use 1.0× regardless. |

## Subagents

Two hard caps that govern [Subagents](/guide#subagents):

| Key                               | Default | Meaning                                                                                |
|-----------------------------------|---------|----------------------------------------------------------------------------------------|
| `subagent.maxDepth`               | 1       | How deep the parent → child → grandchild chain can go (1 = no grandchildren).          |
| `subagent.maxChildrenPerParent`   | 5       | How many concurrently `RUNNING` children a single parent can have in flight.           |

Violations emit `SUBAGENT_LIMIT_EXCEEDED` on the [Logs](/logs) page and return a plain-text refusal to the model. Changes apply live; no restart needed.

To delegate a subagent to an **external coding harness** (Pi / Claude Code / Codex CLI) instead of JClaw's native loop, set:

| Key                    | Default    | Meaning                                                                                             |
|------------------------|------------|-----------------------------------------------------------------------------------------------------|
| `subagent.acp.command` | *(unset)*  | Absolute path to the harness command run for `subagent_spawn { runtime:"acp" }` (e.g. `/usr/local/bin/pi`). Read from config only, never the model. |

The spawning agent must also hold the `acp` grant (`acpAllowed` on its [Agents](/agents) page; the main agent always may), and each run is bounded by `subagent.maxWallClockSeconds` (default 1800). See [External coding harness](/guide#acp-harness) for the full setup.

## Tasks

Two knobs for the [Tasks](/guide#tasks) subsystem:

| Key                       | Default | Meaning                                                                                                |
|---------------------------|---------|--------------------------------------------------------------------------------------------------------|
| `retentionDays`           | 30      | Days a terminal task (`COMPLETED` / `FAILED` / `CANCELLED` / `LOST`) stays in the DB before `TaskCleanupJob` hard-deletes it along with its run history. `0` disables auto-cleanup entirely. Active tasks (`PENDING` / `ACTIVE` / `RUNNING`) are never touched. Max: 3650 (≈10 years). |
| `defaultTimezone`         | `UTC`   | IANA timezone applied to `CRON` / `SCHEDULED` tasks that don't specify their own. Per-task `timezone` overrides this. `INTERVAL` / `IMMEDIATE` ignore timezone entirely.                |

The retention TTL is also displayed next to the [Tasks](/tasks) page title so you don't get surprised by auto-deletes.

## Performance

OkHttp dispatcher concurrency caps for outbound LLM calls:

| Key                                  | Default                | Meaning                                                              |
|--------------------------------------|------------------------|----------------------------------------------------------------------|
| `dispatcher.llm.maxRequestsPerHost`  | `clamp(8 × cores, 64, 256)` | In-flight calls allowed to a single provider.                   |
| `dispatcher.llm.maxRequests`         | `2 × maxRequestsPerHost`    | Total in-flight calls across all providers.                     |

Auto-tuned at first start; transiently bumped during loadtest if `--concurrency` would otherwise saturate. Changes apply live.

## Uploads

Per-MIME-bucket attachment size caps and per-message file count. The sniffed MIME decides which limit applies — images, audio, or everything else.

| Key                | Default | Bound                                                          |
|--------------------|---------|----------------------------------------------------------------|
| `maxImageBytes`    | 20 MB   | Image uploads (most vision models accept up to 20 MB).         |
| `maxAudioBytes`    | 100 MB  | Audio uploads (~1 hour at 128 kbps).                           |
| `maxFileBytes`     | 100 MB  | Every other attachment type (PDFs, text, archives, etc.).      |
| `maxFiles`         | 5       | Max files per chat message. System-wide ceiling is 5.          |

Takes effect without a restart; raise `play.netty.maxContentLength` in `conf/application.conf` if you need over the bundled 512 MB transport-layer ceiling.

## Skills Promotion

LLM sanitization for the **promote-to-global** flow on the [Skills](/skills) page. Promoted skills run an LLM pass that strips installation scripts and external network calls.

| Key                                | Default                    | Meaning                                                                       |
|------------------------------------|----------------------------|-------------------------------------------------------------------------------|
| `skillsPromotion.provider`         | (main agent's provider)    | LLM provider for the sanitization pass. Defaults to the main agent's.         |
| `skillsPromotion.model`            | (main agent's model)       | Model id paired with the above.                                               |
| `skillsPromotion.timeoutSeconds`   | 180                        | Hard timeout for one sanitization pass (30–900 s).                            |
| `skillsPromotion.batchSizeKb`      | 200                        | Source-text batch size sent to the LLM in one pass (10–1000 KB).              |

## Shell Execution

Allowlist and timeout for the shell tool. Per-agent enable/disable lives on the [Tools](/tools) page; this section configures the shared execution policy.

| Key                            | Default | Meaning                                                                                              |
|--------------------------------|---------|------------------------------------------------------------------------------------------------------|
| `shell.allowlist`              | (empty) | Newline-separated list of commands (with optional argument patterns) the agent may run.              |
| `shell.defaultTimeoutSeconds`  | 30      | Per-command wall-clock budget (1–300 s).                                                              |

:::gotcha
The allowlist is the safety floor for shell access. An empty allowlist plus no per-agent **Bypass allowlist** means agents can't run anything via the shell tool. Be deliberate about what you add here.
:::

## Malware and Virus Scanners

Hash-based reputation lookups that scan every binary inside a skill before it's installed. Each scanner hashes the file with SHA-256 and asks an external service whether that hash appears in its known-malware catalog — **file bytes never leave the host**.

Multiple scanners run independently and compose under OR: a skill is rejected if any enabled scanner flags any binary. A scanner is only active when both **enabled** is on *and* its API key is configured. Each row links to the provider's signup page.

Off by default; turn on for environments where users can upload arbitrary skill bundles.

## Password

The admin password is stored as a PBKDF2-SHA256 hash in the Config DB. The **Reset** button wipes the stored hash and signs you out — on the next access you'll be routed to the setup screen to choose a new password.

## Unmanaged keys

A read-only diagnostic list that appears only when the Config DB contains keys not owned by any section above. Usually stale rows from a prior schema or mid-migration state — a signal that something needs cleanup, not a place to add new config.

---

## Tips

:::tip Add keys you actually need
The provider list is long. Don't enable a provider you're not using — every enabled provider with a missing key becomes a "configure me" badge somewhere in the UI. Start with one, get an agent working end-to-end, then add more.
:::

:::note Restart sensitivity
Most settings take effect immediately. A handful (some OCR backends, a few provider settings that affect connection pools) only take effect at the next server restart; the UI flags those with an inline note.
:::

## Where to go next

- [Getting Started](/guide#getting-started) — the canonical first-time setup walkthrough.
- [Agents](/guide#agents) — once a provider is configured, the next stop.
- [Logs & Dashboard](/guide#logs-and-dashboard) — for operator visibility into what JClaw is doing.
