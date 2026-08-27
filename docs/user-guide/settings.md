# Settings

The [Settings](/settings) page is the operator's control panel. Configuration is split into sections you reach from a grouped table-of-contents rail — **System**, **Providers**, **Audio**, **Image**, **Video**, **Agents & Automation**, **Memory**, and **Security** — and selecting a section shows just that panel. Most knobs apply live — sections that need a JVM restart say so inline.

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
- Set **local** — the provider's Remote/Local classification. It decides which section the card appears under, and it is what lets a provider serve memory embeddings and reranking. Seeded `true` for Ollama Local, LM Studio, vLLM and llama.cpp; absent means remote. Declare a provider local only when you host it yourself: memory text is sent there whenever it serves those features.
- **Manage models** — expand the row to see every model you've registered for the provider, with its prompt/completion/cached/cache-write prices, thinking-mode classification (always-thinks / capable / off), and capability badges (vision, audio, video, thinking) confirmed by the provider or guessed from the model name (a trailing `?`, e.g. `video?`, marks a guess).
- **Discover models** — pull the provider's live model catalog and pick which to register, with the provider's own price hints filled in. Filter the catalog by capability (vision / audio / video / thinking), by cost (free / paid, offered when the provider has free models), and by leaderboard rank.

If no provider is configured, no agent can answer — that's the most common cause of "the agent isn't replying." The [Agents](/agents) page shows a yellow **provider not configured** badge on rows whose provider is missing its key.

## Search Providers

Web search engines available to the `web_search` tool. Drag rows to **reorder priority** — providers are tried in order, and the next one is tried automatically if the first fails. Each row shows three states:

- **active** — enabled *and* API key configured.
- **needs API key** — enabled but the key is missing.
- **disabled** — turned off.

Available providers: **Exa**, **Brave**, **Tavily**, **Perplexity**, **Ollama**, and **Felo**. Each row links to that provider's signup page. Perplexity additionally exposes a `recencyFilter` (hour / day / week / month / year / none) so the LLM doesn't echo stale snippets.

## Transcription

Pairs every audio attachment with a text transcript before it reaches the LLM. Audio-capable models still receive native audio; text-only models receive the transcript as text.

Master toggle, then a backend radio group:

- **OpenRouter** — reuses your OpenRouter API key from LLM Providers.
- **OpenAI** — reuses your OpenAI API key.
- **Self-Hosted Whisper** — runs `whisper.cpp` locally; the chosen model file (tiny / base / small / medium / large variants, ~75 MB to ~3 GB) downloads from Hugging Face on first use with a progress bar. Requires `ffmpeg` on PATH; the page warns inline if it's missing.

Cloud backends are disabled in the radio group until their underlying provider key is configured in LLM Providers. An **Active:** status line above the toggle shows the current backend (cloud provider, or Self-Hosted Whisper with the chosen model), or that transcription is off.

Below the backend picker, a **Diarization** subsection covers the who-spoke-when pipeline (independent of the master transcription toggle, since the `diarize_audio` tool runs its own local pipeline):

- **Diarization** — who-said-what transcripts come from one of two providers you pick here. Ordinary voice-note transcription stays local (whisper) and works without any of this.
  - **Audio-capable cloud chat model** — pick a provider (OpenAI or OpenRouter, using the API keys from LLM Providers) and one of its audio-capable models (the picker lists only models that accept audio input — the same ones showing an "Audio" badge in the chat model picker). The recording is sent to that model with a verbatim-diarization prompt; tell the agent who the speakers are ("the host is Anthony") and the transcript uses real names.
  - **On-device (`pyannote-local`)** — fully offline. `pyannote/speaker-diarization-community-1` produces speaker turns, which are fused with the local ASR transcript; **no audio leaves the host**. It labels speakers by voice within the recording (Speaker 1, Speaker 2) rather than by name, so the cloud path remains the option when you need named speakers. The gated weights need a Hugging Face token — the same one Image Generation uses. The panel shows download status and live progress for the diarizer and emotion weights, and only contacts the sidecar when this path is active.
- **Emotion labels on diarized transcripts** — when the `diarize_audio` tool asks for them, each turn on the on-device path is tagged with how it was said (7 categories plus valence / arousal / dominance), classified locally from the voice's tone. The model is operator-selectable (`transcription.diarization.emotionModel`) from a fixed set: **MERaLiON-SER v1** (the default — multilingual, covering English, Chinese, Malay, Tamil and Indonesian), or one of two English-trained wav2vec2 alternatives. Match the model to your audio: the English models load fine on other languages but misclassify them. Best-effort — a failure returns turns without labels. Ordinary voice-note transcription is unaffected.

## Speech

Text-to-speech for reading replies aloud (the **speaker icon** on a message) and for real-time [Voice mode](/guide#chat). Pick an **engine**, a **model**, and — where the model offers presets — a **voice**. Changes apply on the next read-aloud, no restart.

Two engines:

- **Sidecar** — quality-first; runs a local Python process (needs `uv` on PATH), and weights download from Hugging Face on first use. Models: **Qwen3-TTS 0.6B** (plus a 4-bit variant), **Kokoro-82M**, and **Chatterbox** (a PyTorch model on Apple Silicon MPS or NVIDIA CUDA — the most natural voice, but noticeably slower than the others).
- **JVM-native** — runs in-process via sherpa-onnx, no Python or sidecar. Models: **Piper Amy** (tiny, fast, English) and **Kokoro-82M multilingual**; the chosen voice downloads once (a button in the panel) then synthesizes on CPU.

**Voice** — models with named speakers show a voice dropdown under the model. **Kokoro** offers American and British, male and female voices; **Qwen3-TTS** offers a few numbered speakers. Single-voice models (Piper) hide the control, and **Default** keeps the model's own voice. Your choice is remembered per engine.

## OCR

Optical character recognition for image and scanned-PDF attachments via the `documents` tool. Each backend (e.g. Tesseract) shows its detection status:

- **active** — binary detected on PATH *and* enabled.
- **disabled** — detected but turned off.
- **not detected** — binary missing on PATH; install hint shown inline.

Backends can only be toggled when their system dependency is present; install the missing binary and restart the JVM to enable. With OCR on, images and scanned PDFs get a text layer extracted before the prompt is built — useful when the model itself isn't vision-capable.

A missing backend prints the install command for the operating system JClaw is running on, so you don't have to pick your line out of the table below — the same words the server logs at startup.

Installing Tesseract:

| | |
|---|---|
| macOS | `brew install tesseract` |
| Debian/Ubuntu | `apt-get install tesseract-ocr` |
| Windows | `winget install -e --id UB-Mannheim.TesseractOCR` |

**On Windows the installer does not add Tesseract to your PATH.** Either add its folder yourself and open a new terminal, or — simpler — set `ocr.tesseract.path` in `conf/application.conf` to the install directory (`C:\Program Files\Tesseract-OCR`) and restart. Point it at the folder, not the `.exe`. A path that isn't a directory is refused at startup rather than ignored, so a typo tells you instead of silently leaving OCR off.

Extra languages install separately (`tesseract-ocr-fra`, `tesseract-ocr-jpn`, …); list them in `ocr.tesseract.languages` as `eng+fra+jpn`.

## Image Captioning

The vision analogue of Transcription: non-vision chat models get a short **text description** of an uploaded image before it reaches the LLM. Vision-capable models still receive the image natively. WebP and other formats are transcoded to PNG first so every backend can read them.

Master toggle, then a backend radio group:

- **OpenRouter** — reuses your OpenRouter API key from LLM Providers; captions with a vision model on OpenRouter.
- **OpenAI** — reuses your OpenAI API key.
- **Local VLM (Ollama)** — captions with a vision model you run in your own local Ollama (at `localhost:11434/v1`). There is **no bundled model**: pull a vision model in Ollama (e.g. `ollama pull moondream` or `llava`), add it under [LLM Providers](#settings-llm-providers) and mark it **supports vision**, then pick it here.

The model picker is a dropdown of that backend's **vision-tagged** models — cloud backends are disabled until their key is set. An **Active:** status line shows the current backend and model. With captioning off, non-vision models receive a "description unavailable" note for images.

## Image Generation

Backend for the agent's `generate_image` tool (off per-agent by default; toggle it on the [Tools](/tools) page). The tool stays hidden from agents until you pick a backend here — the master switch is the `imagegen.provider` key, unset by default.

Master toggle, then a backend radio group:

- **BFL (Black Forest Labs)** — the Flux image API. Paste the BFL key **in this panel** — it's image-generation-only, separate from [LLM Providers](#settings-llm-providers).
- **OpenAI** — reuses your OpenAI key from LLM Providers; renders with `gpt-image-1`.
- **Replicate** — hosted models. Set the Replicate key **in this panel** (it's shared with [Video Generation](#settings-video-generation)). The model dropdown is live-discovered from Replicate's curated text-to-image collection, split into **text-to-image** and **image-to-image (Kontext style transfer)** groups; leave it on *Provider default* (`black-forest-labs/flux-schnell`) to let Replicate pick.
- **Self-Hosted (Flux 2 Klein)** — runs the local image sidecar on your own GPU, no API key. Three runtime gates must clear, each shown inline:
  1. **uv on PATH** — the sidecar runs under [uv](https://astral.sh/uv); install it and restart if the panel reports it missing.
  2. **GPU capability** — click **detect GPU** to probe VRAM. The verdict (runnable, free/total VRAM, and a reason) decides whether the radio is selectable.
  3. **Model download** — pull `black-forest-labs/FLUX.2-klein-4B` (~13 GB, Apache-2.0) with a progress bar; weights land under `data/image-models/` and are recognized across restarts.

Cloud radios are disabled until their key is set (amber **no API key** badge). Saved keys are masked, so editing one means retyping the whole value. Changes apply live.

## Video Interpretation

How JClaw makes a video attachment legible to your chat model. The strategy is chosen automatically, in this order:

1. **The chat model handles video natively** — it watches the clip directly; nothing else runs.
2. **A dedicated video-interpretation model is configured** — when the chat model can't do video, this model interprets the clip and its description is spliced into the conversation as text.
3. **The chat model has vision** — JClaw samples still frames from the clip and sends them as images.
4. **Text-only chat model with [Image Captioning](#settings-image-captioning) on** — sampled frames are captioned into a timestamped text summary.

The **dedicated video-interpretation model** has a master toggle, then a provider and a model picker; the picker is live-discovered from the provider and filtered to its **video-capable** models — the same shape as Image Captioning above.

Two knobs govern the frame-sampling fallbacks (strategies 3 and 4):

| Key             | Default | Meaning                                                                                                 |
|-----------------|---------|---------------------------------------------------------------------------------------------------------|
| `secondsPerFrame` | 10    | Sampling density — one frame is grabbed per this many seconds of video (1–60). Lower = denser sampling, more detail, higher cost. |
| `sampleFrames`    | 8     | Hard ceiling on frames extracted from a single clip (2–32), regardless of length.                        |

The effective frame count is `clamp(round(duration ÷ secondsPerFrame), 2, sampleFrames)`. An **Active:** status line shows which strategy your current main-agent model would use — watch, summarize, sample, or caption. If none apply, the video comes through with a note telling you to enable one of the above.

## Video Generation

Backend for the agent's `generate_video` tool (off per-agent by default). Like image generation, the tool is hidden from agents until you pick a backend — `videogen.provider` is unset by default. Video generation is **asynchronous**: the chat shows a placeholder and polls job status until the clip is ready.

Master toggle, then a backend radio group:

- **Replicate** — hosted text-to-video models, live-discovered from Replicate's curated collection (leave on *Provider default*, `wan-video/wan-2.2-t2v-fast`, to let Replicate pick). The Replicate key is **not** set here — it reuses the one from [Image Generation](#settings-image-generation), so this radio is disabled until that key is configured.
- **Self-Hosted (on this machine)** — runs the local video sidecar. Gated on **uv on PATH**; click **detect GPU** to probe. The probe returns the WAN / LTX engine variants your hardware can run, each tagged **ready** (green), **runs slow** (amber, still selectable), or unavailable (disabled, with the reason). Picking Self-Hosted auto-selects the best runnable engine; the per-engine radios refine the variant.

| Key                      | Default | Meaning                                                                                        |
|--------------------------|---------|------------------------------------------------------------------------------------------------|
| `videogen.maxJobMinutes` | 30      | Wall-clock ceiling for a single generation job; a run that overruns is failed. Minimum 1 minute. |

Changes apply live.

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

The spawning agent must also hold the `acp` grant (`acpAllowed` on its [Agents](/agents) page; the main agent always may), and each run is bounded by `subagent.maxWallClockSeconds` (default 1800). See [External coding harness](/guide#subagents-acp-harness) for the full setup.

## Tasks

Two knobs for the [Tasks](/guide#tasks) subsystem:

| Key                       | Default | Meaning                                                                                                |
|---------------------------|---------|--------------------------------------------------------------------------------------------------------|
| `retentionDays`           | 30      | Days a terminal task (`COMPLETED` / `FAILED` / `CANCELLED` / `LOST`) stays in the DB before `TaskCleanupJob` hard-deletes it along with its run history. `0` disables auto-cleanup entirely. Active tasks (`PENDING` / `ACTIVE` / `RUNNING`) are never touched. Max: 3650 (≈10 years). |
| `defaultTimezone`         | `UTC`   | IANA timezone applied to `CRON` / `SCHEDULED` tasks that don't specify their own. Per-task `timezone` overrides this. `INTERVAL` / `IMMEDIATE` ignore timezone entirely.                |

The retention TTL is also displayed next to the [Tasks](/tasks) page title so you don't get surprised by auto-deletes.

## Logging

Per-logger log-level overrides for the running JVM — the operator counterpart to the [Logs](/logs) page. Add a row naming a logger and the level you want; the change applies **live** (no restart) and persists across restarts.

- **Logger** — any dotted name: a single class (`controllers.ApiChatController`) or a whole subtree (`play`). The alias **`root`** targets the root logger (the global floor). An autocomplete list suggests loggers that have already emitted a line; a name that hasn't logged yet is accepted with a soft amber hint, not rejected.
- **Level** — one of `OFF`, `FATAL`, `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE`, `ALL` (least to most verbose).

Overrides are applied through log4j2 *after* Play's own logging init, so a row here **wins over both `conf/log4j2.xml` and `application.conf`**. Deleting a row reverts that logger to its inherited (parent) level; deleting the `root` override restores the baseline captured before you first changed it (falling back to `INFO`). They're stored under reserved `logging.level.<logger>` config keys, so they never show up in the [Unmanaged keys](#settings-unmanaged-keys) list.

### Disk used by logs

Below the level table, the same section reports what the levels above are costing you on disk: the current log file, how many archives exist and what they occupy, and a total for the whole directory. The total covers every file in `logs/`, not just those two categories, so an ad-hoc file left behind by a test run can't hide from it.

The current file is capped and rolled over automatically, so it is never the thing that grows — the archives are. They're deleted once they pass 30 days, at the next daily rollover, which means an instance that built up a backlog carries it until each file individually ages out. **Delete archives** clears them now.

That button removes only the rolled-over `.gz` archives. The file currently being written is kept: log4j2 holds it open, and deleting it would leave the instance logging into a file nothing can read until the next rollover. The confirmation says so, because "delete logs" is otherwise ambiguous about exactly that file. Nothing here is required maintenance — it only reclaims disk sooner than retention would.

## Performance

### Runtime

Live state of the JVM serving the page, refreshed every few seconds while the section is open, above the caps you'd tune against it.

Memory appears as three separate figures, because they measure different things and only one of them answers "how much RAM is this using?":

- **Heap** — used, currently held, and the ceiling. What the JVM allocates inside its own arena.
- **Non-heap** — metaspace, code cache and direct buffers. Invisible to every heap figure, and where the outbound HTTP stack's buffers live.
- **Process memory** — what the operating system charges JClaw, and the figure to compare against the machine's RAM. It sits **well above** the heap, because the JVM also holds non-heap memory and reserves address space the heap hasn't filled. A large gap is normal and is not a leak. On a platform with no supported way to read it, this shows a dash rather than a substituted heap number.

Alongside those: processor share and core count, garbage collections (both the running total and how many happened since the last sample — the total alone says little), uptime, and **platform threads** with their high-water mark. That last label is deliberate: it excludes virtual threads, which is where chat turns and tool calls actually run, so a low flat count here does not mean the instance is idle.

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

## Printers

The default printer the `printer` tool targets, and the job options that default carries. Per-agent enable/disable lives on the [Agents](/agents) page — printing is off for every agent until you turn it on. See [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) for the tool itself.

**Find printers** runs an mDNS browse of the local network and lists what answers; pick one to make it the default. Discovery is an explicit action rather than something the page does on open — a browse takes seconds, and opening Settings to change the timezone shouldn't pay for it. You can also enter a host and port directly, which is the fallback when mDNS is blocked (it's link-local, so VPNs and containers without a multicast route routinely drop it).

A reachability badge probes the saved default and reports whether it still answers. This matters because a default outlives the DHCP lease it was saved under; without the probe the only symptom of a moved printer is a print that times out with no hint why.

**Job options** are read from the selected printer, not from a list JClaw carries — the page offers `sides`, `media`, `copies`, quality and whatever else the device announces, with its own default preselected. Leave any of them blank to use the printer's default. Options are re-queried whenever you change the selected printer, so a device that reports one-sided-only never lets you save a duplex default it would have to reject.

## Skills Promotion

LLM sanitization for the **promote-to-global** flow on the [Skills](/skills) page. Promoted skills run an LLM pass that strips installation scripts and external network calls.

| Key                                | Default                    | Meaning                                                                       |
|------------------------------------|----------------------------|-------------------------------------------------------------------------------|
| `skillsPromotion.provider`         | (main agent's provider)    | LLM provider for the sanitization pass. Defaults to the main agent's.         |
| `skillsPromotion.model`            | (main agent's model)       | Model id paired with the above.                                               |
| `skillsPromotion.timeoutSeconds`   | 180                        | Hard timeout for one sanitization pass (30–900 s).                            |
| `skillsPromotion.batchSizeKb`      | 200                        | Source-text batch size sent to the LLM in one pass (10–1000 KB).              |

## Memory: Limits

How many memories reach the prompt. These two counts are the *only* bound on their blocks, so between them they decide memory's entire footprint in a turn — which is why they sit here rather than in the config table alone. Both count whole memories; a value below 1 disables that block.

| Key | Default | Meaning |
|-----|---------|---------|
| `memory.coreload.maxCount` | 20 | Core memories auto-loaded at session start. |
| `memory.recall.limit` | 10 | Memories recalled per turn. |

## Memory: Embeddings

Vector memory — recall finds a memory by meaning rather than wording, and capture recognizes a fact you already stored even when you phrase it differently. Off by default; with it off memory still works, falling back to keyword matching for both recall and duplicate detection.

Enable the toggle, then pick a provider and model. **Only providers in the Local section of [LLM Providers](#llm-providers) are offered.** Embedding a memory sends its full text to the provider, so it has to run on hardware you control for memory text not to leave it; the backend rejects any other provider even if the key is set directly through `POST /api/config`. If no local provider is configured the panel says so instead of listing models.

The classification is yours to make, not something read off the base URL. An address cannot answer it in either direction: a server on a VPN or tailnet looks remote (Tailscale's `100.64.0.0/10` is shared carrier-NAT space, indistinguishable from another subscriber's), while a cloud API behind a local proxy looks local but runs someone else's model. You say which providers you host.

Models are discovered live from the provider rather than read from the stored catalog, because embedding models generally aren't registered there. Saving is gated on a probe that confirms the model actually embeds and records its dimension. Changing the model later marks the corpus **needs re-embedding** and offers a re-embed action that rewrites existing vectors against the new model.

## Memory: Reranker

An optional second pass that re-orders the shortlist recall produced, before it reaches the prompt. Off by default.

Restricted to the same providers as embeddings, for the same reason: the reranker renders the whole candidate shortlist into its prompt, so whatever serves it sees memory text.

| Key | Default | Meaning |
|-----|---------|---------|
| `memory.rerank.enabled` | `false` | Turn the rerank pass on. |
| `memory.rerank.provider` | (unset) | Local provider serving the rerank call. |
| `memory.rerank.model` | (unset) | Model id paired with the above. |

## Tool Approvals

What a dangerous action does when it can't reach you for approval. It covers the shell tool and the launch of a coding-harness subagent, and it is instance-wide — there is no per-agent override.

| Key | Default | Meaning |
|-----|---------|---------|
| `tool.approval.offChannelPolicy` | `allow` | `allow`, `ask`, or `deny` — see below. |

This setting is a **fallback, not a replacement for the approval prompt**. When *someone else* messages the agent on Telegram or Slack and that agent has a working binding, you are asked in that chat regardless of what is set here. Your own messages are not prompted — the channel already established that the sender was you — so this policy is what decides them:

| Who sent the turn | `allow` | `ask` | `deny` |
|--------|---------|-------|--------|
| You — the web UI, or a Telegram/Slack message the channel proves is yours | Runs. | Sends a confirmation to the agent's bound Telegram DM. | Refused. |
| Someone else, on a channel that can reach you | Prompted in that chat. | Prompted in that chat. | Prompted in that chat. |
| Someone else with no way to ask — WhatsApp, a binding that cannot prompt, or a task whose origin was never recorded | Refused. | Sends a confirmation to the bound Telegram DM; refused if there is none. | Refused. |

:::gotcha
`allow` only ever loosens **your own** turns. Someone else on a channel that can reach you is prompted either way, and one that cannot fails closed under both `allow` and `deny` — so raising the setting cannot weaken it. Only `ask` gives an unaskable origin any route through, and only by confirming with you first.

Separately, an agent you have granted **always allow** for a tool runs it with no prompt on any origin. That standing grant is checked before this policy and overrides it — so if an agent stopped asking, a grant is why, not this setting.

Note that a standing grant is currently one-way: it is created when you tap **always allow** on an approval prompt, and there is no interface to list or withdraw it. Tap it only for a tool you are content for that agent to run unattended from any channel, indefinitely.
:::

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

## Maintenance

The operator actions that change this instance, ordered by how much they disrupt it: resetting the password costs nothing, an upgrade downloads a release and ends in a restart, and a restart cuts everything in flight.

This section was previously three — **Password**, **Upgrade** and **Restart**. Links to the old `?section=password`, `?section=upgrade` and `?section=restart` addresses all still resolve here.

### Password

The admin password is stored as a PBKDF2-SHA256 hash in the Config DB. The **Reset** button wipes the stored hash and signs you out — on the next access you'll be routed to the setup screen to choose a new password.

When you choose a password it must be **at least 12 characters** (longer passphrases beat added symbols — length matters most), and the setup screen shows a live strength meter as you type. Passwords found in a known public breach are rejected: the check uses [Have I Been Pwned](https://haveibeenpwned.com/) via k-anonymity — only a short prefix of the password's hash leaves the host, never the password itself — and falls back to a bundled common-password list when that lookup is unavailable. Repeated failed logins from the same source are temporarily throttled.

### Upgrade and restart

Installs the newest JClaw release over this one, without a shell. The button hands off to `jclaw.sh upgrade` — the same command you'd run by hand — so the CLI and the UI take exactly the same path.

The panel shows the version you're running and the newest published release. **Check again** forces a fresh lookup; otherwise the answer is cached for an hour, because GitHub allows only 60 unauthenticated API calls an hour per address and this panel is polled on every visit.

When JClaw is served from a git checkout, the panel also names the commit it's running, marked when the working tree has uncommitted changes. A checkout keeps the same version number across many commits, so the version alone can't tell you which build is live. A packaged install has no repository and shows nothing here.

**The download happens while JClaw keeps serving.** The release (~400 MB for a bundle install) is fetched, checksum-verified and unpacked before anything is stopped, so a network failure, a bad download or a full disk costs no downtime at all — you're told about it with the instance still running. Only once the new version is staged and verified is the instance stopped, the tree replaced, and JClaw started again. You can navigate away during the download and come back.

### What is kept

Everything the release doesn't ship is carried across, so an upgrade never resets your instance:

| Kept | What's in it |
|------|--------------|
| `data/` | the database, uploaded attachments, the search index |
| `workspace/` | the agent workspace, including per-skill credentials |
| `certs/` | the application secret (your sessions survive) and any TLS cert+key |
| `public/apps/` | apps you've installed |
| `logs/` | the existing application, GC and upgrade logs |
| `sidecar/*/.venv` | Python environments and downloaded models |
| skills you installed | bundled skills are updated; yours are left alone |

The rule is inverted on purpose: rather than listing directories to preserve — a list that goes stale the moment a release adds one — the upgrade keeps *anything the new release does not ship*. The exceptions are build outputs (`precompiled/`, `lib/`, `framework/`, `public/spa/`), which must come from the release verbatim: merging those would leave a deleted class on the classpath or two versions of a jar side by side.

`conf/application.conf` is handled separately. If you never edited it, the release's copy is installed so new settings take effect. If you did, **your file is kept** and the release's copy is written beside it as `conf/application.conf.new-<version>` so you can see what changed. The panel tells you when this happens.

### If it goes wrong

The database is copied to `data/backups/` before the swap — this matters because the new version migrates the schema on first boot, and that isn't undone by putting the old files back. The three most recent backups are kept.

If the new version doesn't answer within four minutes of starting, the upgrade **rolls itself back**: the old tree is restored, the pre-upgrade database is restored over it, and the previous version is started again. The panel then reports the rollback rather than showing an unchanged version number with no explanation. Helper output goes to `logs/upgrade.log`.

### When the button isn't there

Upgrade only applies to installs made by the one-line installer or from an unzipped release archive. In two cases the panel explains itself instead of offering a button:

- **A source checkout** — update it with `git pull`.
- **A container** — the image is the upgrade unit; use `docker compose pull && docker compose up -d`. A tree swap inside the container would be thrown away on the next start.

Either way the release check still runs, and the explanation only appears when there is a newer release to explain. An install already on the newest release reports just that — **up to date** — with nothing to do.

### From the command line

```bash
jclaw upgrade --check                    # report versions, change nothing
jclaw upgrade                            # install the newest release
jclaw upgrade --version v0.17.48 --yes   # pin a release (also how you step back)
```

Re-running the one-line installer over an existing install now delegates here too, so it upgrades rather than replacing your data.

### Restart

Reboots this JClaw instance without a shell. The **Restart** button hands off to `jclaw.sh restart` — the same command you'd run by hand — so the stop/start sequencing, stale-lock cleanup and port checks are identical either way.

Before it acts, the panel shows what the reboot will interrupt: task runs and subagent runs currently in flight. Restart is deliberately **not** blocked when work is running — the moment you most want to reboot is usually the moment something is stuck — so the counts are there to inform the confirmation, not to veto it. In-flight chat streams are cut as well.

The page reconnects on its own: it waits for the backend to go down, then polls until it answers again, then reloads. Two details worth knowing:

- **In dev mode** only the Play backend is restarted. The Nuxt dev server on port 3000 keeps running — bouncing it would kill the very server that rendered the page you clicked from.
- **In a source checkout** a production restart may recompile Java sources and rebuild the SPA. Both steps are gated on staleness and skipped when nothing changed, so this is usually quick. Two timed restarts on a developer clone: **48 s** with both steps skipped, **58 s** with a full SPA rebuild — the SPA is worth about ten seconds, not minutes. A cold Java recompile is the step that can take substantially longer, and it wasn't exercised in either measurement. The panel errs long when sizing how long it waits for the backend to return (15 minutes for a source checkout), so a genuinely slow restart is still survivable.

If the instance wasn't started by `jclaw.sh`, the button is disabled and says so — there's nothing to hand off to. Helper output goes to `logs/restart.log`, which is the first place to look if the app doesn't come back.

## Source-only controls

Two operator controls have no panel on this page. They're set and read through the API rather than the UI, so they're listed here to keep them findable.

| Key / endpoint            | Default        | Meaning                                                                                       |
|---------------------------|----------------|-----------------------------------------------------------------------------------------------|
| `web_fetch.allowlist`     | (empty)        | Comma-separated hosts the `web_fetch` tool may reach. Empty means unrestricted.                |
| `GET /api/metrics/db-pool`| —              | Current HikariCP occupancy: `active`, `idle`, `total`, `awaiting`, `max`. Admin session required. |

Set the allowlist with `POST /api/config`:

```bash
curl -X POST http://localhost:9000/api/config \
  -H 'Content-Type: application/json' \
  --cookie 'PLAY_SESSION=…' \
  -d '{"key": "web_fetch.allowlist", "value": "example.com, docs.example.org"}'
```

An entry matches that exact host and any subdomain of it, so `example.com` covers `docs.example.com` but not `notexample.com`. The check runs on the initial URL **and on every redirect hop**, so an allowed host can't bounce a fetch onward to one you never listed. Delete the key (or set it blank) to go back to unrestricted.

:::gotcha
The allowlist is off by default, and that default is the shipped state on every install. `SsrfGuard` already blocks `web_fetch` from reaching loopback, link-local and private ranges — but nothing constrains what *leaves*. An agent following a prompt injection can encode conversation content into a URL on any public host it's allowed to reach. If that matters for your install, set the allowlist; leaving it empty is a choice, not a safe default.
:::

For the pool endpoint, `awaiting` is the number that signals trouble: a sustained non-zero value means callers are blocking on `db.pool.timeout` waiting for a connection. `active` sitting near `max` is normal on a busy system.

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
