# Settings

The [Settings](/settings) page is the operator's control panel. It groups configuration into sections, scrollable on one long page so you can hit <kbd>Ctrl</kbd>+<kbd>F</kbd> and find anything.

This page summarizes each section. The settings page itself is the source of truth for the current defaults and available knobs.

## LLM Providers

The most important section. Each row is a model provider JClaw can talk to:

- **OpenAI, Anthropic, Google, Mistral, etc.** — first-party APIs. Paste in your API key and toggle **Enabled**.
- **Ollama (local or cloud)** — runs models on your hardware or on Ollama's cloud. Set the base URL; no API key needed for local.
- **OpenRouter, Groq, etc.** — aggregators that proxy many models. Same shape: base URL + API key.

For each provider you can:

- Set the **API key** (stored encrypted at rest).
- Pick a **Default model** — what new agents start with for that provider.
- Mark whole providers as **Enabled / disabled** to hide them from the agent picker.

If no provider is configured, no agent can answer — that's the most common cause of "the agent isn't replying." The [Agents](/agents) page shows a yellow **provider not configured** badge on rows whose provider is missing its key.

:::gotcha Keys are not portable
Each operator's API keys are scoped to their own login. Sharing keys with a teammate means giving each of them their own copy on their own [Settings](/settings) page.
:::

## OCR

Optical character recognition for image attachments. Pick a provider (e.g., Tesseract for local OCR, a cloud OCR provider for higher quality) and enable. With OCR on, image attachments get a text layer extracted before the prompt is built — useful when the model itself isn't vision-capable.

## Search Providers

Configure the search engines available to the web-search tool. Common options:

- **Tavily** — purpose-built for LLM web search; requires an API key.
- **Brave Search** — privacy-respecting; requires an API key.
- **DuckDuckGo** — no key needed; less rich results.
- **Serper, Bing, Google** — others.

You can enable multiple; the tool uses the one you mark as default unless an agent picks otherwise.

## Transcription

Speech-to-text provider for voice notes routed to non-audio models. OpenAI's Whisper API is the typical pick; local Whisper is also an option if you've set it up.

## Chat

Behavior settings for the in-app [Chat](/chat) surface:

- **Default streaming behavior** — whether new conversations start with streaming on or off.
- **Idle eviction** — when an inactive conversation's context gets compacted automatically.
- **Compose defaults** — input box sizing, hotkeys.

## Subagents

The two hard caps that govern [Subagents](/guide#subagents):

| Key                              | Default | Meaning                                                            |
|----------------------------------|---------|--------------------------------------------------------------------|
| `subagent.maxDepth`              | 1       | How deep the parent → child → grandchild chain can go.             |
| `subagent.maxChildrenPerParent`  | 5       | How many concurrently-running children a single parent can have.   |

Bump for explicit fan-in patterns; keep low for runaway protection.

## Performance

Caps and tuning knobs that affect how aggressively JClaw uses model providers — request concurrency, timeouts, retry policy, and the budgets used for streaming.

The defaults are tuned for typical use. Raise them only if you're hitting throughput limits and have already confirmed the provider isn't rate-limiting you.

## Uploads

What kinds of files agents may upload, how large, and where they live:

- **Max file size** — per attachment.
- **Allowed MIME types** — restrict what can be attached.
- **Storage backend** — local disk by default; some operators back this with object storage.

## Skills Promotion

Behavior for the **promote-to-global** drag-drop on the [Skills](/skills) page — whether promotion is gated behind a confirmation, what metadata is required, etc.

## Shell Execution

The system-wide allowlist for the shell tool. Each entry is a command (with optional argument pattern) the agent is permitted to run. The allowlist applies to every agent unless an agent has **Bypass allowlist** turned on in its [Agents](/agents) config.

:::gotcha
The allowlist is the safety floor for shell access. An empty allowlist means agents can't run anything via the shell tool unless they have **Bypass allowlist** on. Be deliberate about what you add here.
:::

## Malware and Virus Scanners

Configure scanning of file uploads against a local or cloud antivirus. Off by default; turn on for environments where users can upload arbitrary files.

## Password

Change your operator login password. JClaw requires you to enter the current password and pick a new one that meets the configured strength policy.

## Unmanaged keys

A read-only view of any provider/tool API keys present in the underlying configuration files that JClaw didn't write itself. Useful when you've layered config across environments and want to see what's coming from where.

---

## Tips

:::tip Add keys you actually need
The provider list is long. Don't enable a provider you're not using — every enabled provider with a missing key becomes a "configure me" badge somewhere in the UI. Start with one, get an agent working end-to-end, then add more.
:::

:::note Restart sensitivity
Most settings take effect immediately. A few performance and provider settings (those that affect connection pools) only take effect at the next server restart; JClaw flags those with an inline note.
:::

## Where to go next

- [Getting Started](/guide#getting-started) — the canonical first-time setup walkthrough.
- [Agents](/guide#agents) — once a provider is configured, the next stop.
- [Logs & Dashboard](/guide#logs-and-dashboard) — for operator visibility into what JClaw is doing.
