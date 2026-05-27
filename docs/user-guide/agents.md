# Agents

Chat works because an agent is on the other end. The [Agents](/agents) page is where you configure those agents — which model they speak to, what system prompt they have, what tools they can use, what skills are attached, and which MCP servers they can call.

Once an agent is **enabled**, it appears in the [Chat](/chat) sidebar and can be bound to external channels on [Channels](/channels).

## Main Agent vs Custom Agents

The page splits into two sections:

- **Main Agent** — the built-in singleton. Always enabled, can't be renamed or deleted. It handles admin-style chat and acts as the fallback route for any [channel](/channels) without an explicit binding.
- **Custom Agents** — every agent you create yourself. You can enable or disable, edit, or delete these freely.

Both kinds use the same configuration surface, with a single difference: you can't change the Main Agent's **Name**, and the Main Agent gets a couple of extra knobs that custom agents don't need (see *Shell Exec Privileges* below).

## Creating or editing an agent

Click **New Agent** at the top of the page, or click any existing row to edit it. The edit view groups configuration into sections, scrollable on one long page.

### Basics

| Field                | What it controls                                                                                              |
|----------------------|---------------------------------------------------------------------------------------------------------------|
| **Name**             | How the agent appears in the sidebar and breadcrumbs.                                                          |
| **Description**      | A short blurb shown under the name. Optional but useful when you have many agents.                             |
| **Default Provider** | Which model provider to use. Must be configured in [Settings → LLM Providers](/guide#settings) first.          |
| **Default Model**    | The specific model id within that provider. The capability pills (text / image / audio / reasoning) update to reflect what that model supports. |

### System prompt

A free-form text field. This is what the model sees before every turn. Use it to set the agent's voice, role, constraints, and any context that doesn't change between conversations.

You can preview exactly what the agent will receive — including any standing context the platform adds from workspace files and skills — by clicking **Inspect prompt** at the top of the edit form. The breakdown shows each section with character and token counts.

### Queue Mode

Controls what happens when a new message arrives while the agent is already busy on this conversation:

| Mode              | Effect                                                                                            |
|-------------------|---------------------------------------------------------------------------------------------------|
| **Queue (FIFO)**  | Queue the new message; the agent processes it after the current turn finishes. The default.       |
| **Collect (batch)** | Hold the new message until the current turn finishes, then process all queued messages as a single batched turn. Best for noisy channels where rapid follow-ups read as one thought. |
| **Interrupt**     | Cancel the in-flight generation and start over with the latest message.                            |

Use **Queue (FIFO)** by default; **Collect** when users tend to send a burst of related messages; **Interrupt** for live-feeling chat where the latest message always wins.

### Tools

A checklist of every tool available to the agent. Tools are first-party capabilities (web fetch, file system, code execution, search, etc.) and any third-party tools enabled via [MCP Servers](/mcp-servers) ticked on this agent. Untick to disable; tick to enable.

If a tool requires extra configuration (an API key, a workspace path, a shell allowlist entry), JClaw shows an inline hint pointing at the right setting.

See [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) for the full catalog.

### Skills

Skills are reusable instruction bundles you've published on the [Skills](/skills) page. Attaching a skill to an agent injects its content into the agent's system prompt. Use skills for capabilities you want to reuse across multiple agents — a coding style guide, a research methodology, an output format, a persona.

Skills can also contribute to the agent's effective shell allowlist (see *Shell Allowlist* below).

### MCP Servers

Servers you've connected on [MCP Servers](/mcp-servers) appear here as a checklist. Ticking a server makes that server's tools available to the agent, alongside built-in tools. The agent's tool list grows automatically as you tick servers — you don't have to enable each tool individually.

### Workspace file contents

A small workspace of named markdown files the platform reads into every turn's system prompt. Five canonical files, switchable via the tab strip:

| File              | Conventional use                                                          |
|-------------------|---------------------------------------------------------------------------|
| `SOUL.md`         | Long-running identity / values material — the "who is this agent" bedrock. |
| `IDENTITY.md`     | Self-description, voice, mannerisms.                                       |
| `USER.md`         | What the agent knows about *you*, the operator.                             |
| `BOOTSTRAP.md`    | First-run scaffolding the agent re-reads at the start of every fresh conversation. |
| `AGENT.md`        | Project / repo / workspace notes you want the agent to carry into every turn. |

Slash commands like `/new`, `/reset`, and `/compact` re-read these on entry, so you can edit a workspace file mid-session and have the agent pick it up on the next conversation reset without restarting anything.

### Shell Exec Privileges (Main Agent only)

Two toggles that govern how strictly the Main Agent's shell tools enforce safety. Custom agents don't see this section — they inherit the standard policy.

| Setting              | Default | Effect                                                                                       |
|----------------------|---------|----------------------------------------------------------------------------------------------|
| **Bypass allowlist** | off     | When on, the Main Agent can run any shell command, not just the operator-curated allowlist.   |
| **Allow global paths** | off   | When on, the Main Agent can read/write outside its workspace directory.                       |

:::gotcha
**Bypass allowlist** removes the safety floor. Only enable it on a Main Agent you trust on a machine where you're comfortable letting the model run arbitrary commands. The system-wide allowlist itself is edited in [Settings → Shell Execution](/guide#settings).
:::

### Shell Allowlist (effective view)

A derived, read-only view of every shell command this agent can actually run, expandable inline on the edit page. It aggregates:

- The **global** allowlist edited in [Settings → Shell Execution](/guide#settings).
- Per-skill grants — each enabled skill can contribute commands at install time. The view groups grants by the skill that contributed them.

To remove a per-skill grant, disable or remove the skill. To change the global allowlist, edit [Settings](/settings).

## Enabling and disabling

Each Custom Agent has a toggle on its row. Disabled agents:

- Don't appear in the [Chat](/chat) sidebar.
- Can't be picked as a new [channel binding](/channels) target (existing bindings keep working until you remove them).
- Still exist — toggle back on to restore.

The Main Agent can't be disabled; it's the always-on fallback.

## Capability pills

Each agent row shows a strip of capability pills derived from the chosen model:

- **Text** — the baseline; every model has this.
- **Image** — the model accepts image inputs natively.
- **Audio** — the model handles voice notes directly (no transcript pre-step).
- **Reasoning** — the model surfaces its internal thought process.

Click a pill to toggle a per-listing capability filter — useful when you want to find "every agent that can see images" at a glance.

## Tips and gotchas

:::tip Start with one agent
You don't need many agents to be productive. A single well-tuned agent with the right tools and skills usually beats five overlapping ones. Add specialized agents only when you find yourself wishing for a different tool surface or system prompt for a recurring kind of work.
:::

:::gotcha "provider not configured"
A small amber **provider not configured** pill on an agent row means the agent's selected provider doesn't have an API key (or its local provider isn't reachable). Visit [Settings → LLM Providers](/guide#settings) to fix it; the agent will continue to be disabled in chat until you do.
:::

:::note Editing doesn't rewrite history
Editing an agent doesn't retroactively change past conversations — they keep the model and prompt they were created with. Only new turns use the updated config.
:::

## Where to go next

Now that you've shaped an agent, the next questions are *where* it can be reached and *what extra power* you can give it:

- [Chat](/guide#chat) — use the agent you just created.
- [Conversations & Channels](/guide#conversations-and-channels) — connect your agent to Slack, Telegram, or WhatsApp.
- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) — extend what your agents can do.
- [Settings](/guide#settings) — configure providers, API keys, and platform-wide caps.
