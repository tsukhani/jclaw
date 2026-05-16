# Agents

An **agent** is a configuration: which model it speaks to, what system prompt it has, what tools it can use, what skills are attached, and which MCP servers it can call. The [Agents](/agents) page is where you create and edit them.

Once an agent is **enabled**, it appears in the [Chat](/chat) sidebar and can be bound to external channels on [Channels](/channels).

## Main Agent vs Custom Agents

The [Agents](/agents) page has two sections:

- **Main Agent** — the built-in singleton. Always enabled, can't be renamed or deleted. It's the fallback agent for any [channel](/channels) that doesn't have an explicit binding, and it handles admin-style chat where no other agent fits.
- **Custom Agents** — every agent you create yourself. You can enable or disable, edit, or delete these freely.

Both kinds use the same configuration surface, with a single difference: you can't change the Main Agent's **Name**.

## Creating or editing an agent

Click **New Agent** at the top of the page, or click any existing row to edit it. The form is grouped into sections you can expand:

### Basics

| Field             | What it controls                                                                                              |
|-------------------|---------------------------------------------------------------------------------------------------------------|
| **Name**          | How the agent appears in the sidebar and breadcrumbs.                                                          |
| **Description**   | A short blurb shown under the name. Optional but useful when you have many agents.                             |
| **Default Provider** | Which model provider to use. Must be configured under [Settings](/settings) → LLM Providers first.          |
| **Default Model** | The specific model id within that provider. The capability pills (text / image / audio / reasoning) update to reflect what that model supports. |

### System prompt

A free-form text field. This is what the model sees before every turn. Use it to set the agent's voice, role, constraints, and any context that doesn't change between conversations.

You can preview exactly what the agent will receive — including any standing context the platform adds — by clicking **Inspect prompt** at the top of the edit form. The breakdown shows each section with character and token counts.

### Tools

A checklist of every tool available to the agent. Tools are first-party capabilities (web fetch, file system, code execution, etc.) and any third-party tools you've enabled. Untick to disable; tick to enable.

If a tool requires extra configuration (e.g., a search provider needs an API key), JClaw shows an inline hint pointing at the right setting.

See [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) for the full surface.

### Skills

Skills are reusable instruction bundles you've published on the [Skills](/skills) page. Attaching a skill to an agent injects its content into the agent's system prompt. Use skills for capabilities you want to reuse across multiple agents (e.g., a coding style guide, a research methodology, an output format).

### MCP Servers

Servers you've connected on [MCP Servers](/mcp-servers) appear here as a checklist. Ticking a server makes that server's tools available to the agent, alongside built-in tools. The agent's tool list grows automatically as you tick servers — you don't have to enable each tool individually.

### Workspace file contents

Files you've uploaded for this agent. They're automatically read into the agent's context, so the model "knows about" them without you having to attach them every conversation.

### Shell Exec Privileges

Controls what shell commands the agent can run via its shell tools.

| Setting              | Default | Effect                                                                                       |
|----------------------|---------|----------------------------------------------------------------------------------------------|
| **Allow global paths** | off   | When on, the agent can read/write outside its workspace directory.                            |
| **Bypass allowlist** | off     | When on, the agent can run any shell command, not just the operator-curated allowlist.        |

:::gotcha
**Bypass allowlist** removes a safety net. Only enable it for trusted agents on machines where you're comfortable letting the model run arbitrary commands. The system-wide allowlist itself is managed in [Settings](/settings) → Shell Execution.
:::

### Queue mode

Controls what happens when a new message arrives while the agent is already busy on this conversation:

- **Wait** — queue the new message; the agent processes it after the current turn finishes.
- **Interrupt** — cancel the in-flight generation and start over with the latest message.
- **Drop** — discard the new message until the current turn finishes.

Choose **Wait** by default. **Interrupt** is useful for live-feeling chat. **Drop** suits bots that should never queue work.

## Enabling and disabling

Each Custom Agent has a toggle on its row. Disabled agents:

- Don't appear in the [Chat](/chat) sidebar.
- Can't be picked as a [channel](/channels) binding target (existing bindings keep working, but new ones can't point to them).
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
A small amber **provider not configured** pill on an agent row means the agent's selected provider doesn't have an API key (or its local provider isn't reachable). Visit [Settings](/settings) → LLM Providers to fix it; the agent will continue to be disabled in chat until you do.
:::

:::note
Editing an agent doesn't retroactively change past conversations — they keep the model and prompt they were created with. Only new turns use the updated config.
:::

## Where to go next

- [Chat](/guide#chat) — use the agent you just created.
- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) — extend what your agents can do.
- [Conversations & Channels](/guide#conversations-and-channels) — connect your agent to Slack, Telegram, or WhatsApp.
- [Settings](/guide#settings) — configure providers, API keys, and platform-wide caps.
