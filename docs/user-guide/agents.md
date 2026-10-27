# Agents

Chat works because an agent sits on the other end. The [Agents](/agents) page is where you configure those agents. There you set the model, the system prompt, the tools, the attached skills, and the MCP servers the agent can call.

Every agent appears in the **Agent** dropdown on [Chat](/chat). Once an agent is **enabled**, you can also bind it to external channels on [Channels](/channels).

## Main Agent vs custom agents

The page splits into two sections:

- **Main Agent**: the built-in singleton. It is always enabled. You cannot rename it or delete it. It is the agent [Chat](/chat) opens on. External [channels](/channels) reach it only through a binding, the same as any other agent.
- **Custom Agents**: every agent you create yourself. You can enable, disable, edit, or delete these freely. **Delete All** in the section header removes every custom agent at once. It asks for a typed confirmation first, and it leaves the Main Agent alone.

Both kinds use the same configuration surface, with three differences. You cannot change the Main Agent's **Name**. The Main Agent also gets a couple of extra shell privilege controls that custom agents do not need (see "Shell Exec Privileges" below). Only custom agents have **ACP External Harness**.

## Creating or editing an agent

Click **New Agent** at the top of the page, or click an existing agent's name to edit it. The edit view groups configuration into sections, scrollable on one long page.

### Basics

| Field | What it controls |
|----------------------|-----------------------------------------------------------------------------------------------------------------|
| **Name** | How the agent appears in the Chat Agent dropdown and breadcrumbs. |
| **Description** | A short blurb shown under the name. Optional, but useful when you have many agents. |
| **Agent type** | **Personal** or **Service**. A personal agent works for one person. A service agent works for whoever is talking to it, such as the members of a shared channel. The difference is two workspace files (see "Workspace file contents" below). Changing the type later changes them: a change to **Service** deletes them, after a confirmation; a change to **Personal** adds blank ones. |
| **Default Provider** | The model provider to use. You must configure it first in [Settings → LLM Providers](/guide#settings). |
| **Default Model** | The specific model id within that provider. The capability pills (thinking / vision / audio / video / no tools) update to show what that model supports. Once the [Model Router](/guide#settings-model-router) lists a model for its Chat class, provider **router** with model **Auto (best value)** is offered too, and picks a model per prompt. |
| **Fallback Provider** | Optional. When the default provider's circuit breaker refuses a turn, or the default provider is no longer configured, the turn goes here. Pick any configured provider other than the default. Leave it at **None**, and such a turn fails instead. See [When a provider misbehaves](/guide#settings-when-a-provider-misbehaves). |
| **Fallback Model** | The model to use on the fallback provider. You choose it. It is not the default model's id, because the fallback need not offer the same models. Set it together with the provider. |

### System prompt

There is no free-form prompt field. The platform assembles what the model sees before every turn from this agent's workspace files (see "Workspace file contents" below), its skills, its core memories, and the platform's own standing context. Set the agent's voice, role, constraints, and any context that does not change between conversations in those files. `AGENT.md` and `SOUL.md` are the usual places.

You can preview exactly what the agent will receive, including any standing context the platform adds from workspace files and skills. Click **Inspect prompt** at the top of the edit form. The breakdown shows each section with character and token counts.

### Queue Mode

This controls what happens when a new message arrives while the agent is already busy on this conversation:

| Mode | Effect |
|-------------------|-----------------------------------------------------------------------------------------------------|
| **Queue (FIFO)** | Queue the new message; the agent processes it after the current turn finishes. The default. |
| **Collect (batch)** | Hold the new message until the current turn finishes, then process all queued messages as a single batched turn. Best for noisy channels where rapid follow-ups read as one thought. |
| **Interrupt** | Cancel the in-flight generation and start over with the latest message. |

Use **Queue (FIFO)** by default. Use **Collect** when users tend to send a burst of related messages. Use **Interrupt** for live-feeling chat where the latest message always wins.

### Tools

Every tool available to the agent appears here, grouped by category: **System**, **Files**, **Web**, **Utilities**. Each tool has its own toggle. A master toggle in the section header enables or disables all of them at once. Tools are first-party capabilities (web fetch, file system, code execution, search, and others). Tools from [MCP Servers](/mcp-servers) are not listed here. Switch a server on in the **MCP Servers** section below.

Tools that need extra setup (an API key, a workspace path, a shell allowlist entry) are configured under the matching [Settings](/guide#settings) section.

See [Skills, Tools & MCP Servers](/guide#skills-tools-mcp) for the full catalog.

### Skills

Skills are reusable instruction bundles you published on the [Skills](/skills) page. Attaching a skill to an agent lists its name and description in the agent's system prompt. The agent reads the skill itself when a task calls for it. Use skills for capabilities you want to reuse across multiple agents: a coding style guide, a research methodology, an output format, a persona.

Skills can also contribute to the agent's effective shell allowlist (see "Shell Allowlist" below).

### MCP Servers

Servers you connected on [MCP Servers](/mcp-servers) get their own **MCP Servers** sub-section below Tools. It appears once the agent has been saved and at least one server is connected. It shows one row per server. The row has a single server-level toggle and a chevron. The chevron expands a read-only list of the actions the server exposes. There is no per-action toggling. The agent gets the whole server or nothing.

### Workspace file contents

The platform reads a small workspace of named markdown files into every turn's system prompt. There are five canonical files, switchable via the tab strip:

| File | Conventional use |
|-----------------|----------------------------------------------------------------------------------------|
| `SOUL.md` | Long-running identity and values material: the "who is this agent" bedrock. |
| `IDENTITY.md` | Self-description, voice, mannerisms. |
| `USER.md` | What the agent knows about you, the operator. Its `Name:` line is how the agent and its memories refer to you. |
| `BOOTSTRAP.md` | First-run scaffolding the agent re-reads at the start of every fresh conversation. Until `USER.md` has your name, it asks for it once, in a direct chat. |
| `AGENT.md` | Project, repo, and workspace notes you want the agent to carry into every turn. |

A **Service** agent has no `USER.md` or `BOOTSTRAP.md`. Its tab strip shows only the other three. Nothing tells it who it works for, it is not prompted to ask anyone's name, and a name someone gives it is never recorded as its owner's. Changing a personal agent to Service deletes the two files but keeps the memories it already holds. Review them on [Memories](/memories) if they should not carry over.

The platform reads these files on every turn when the system prompt is assembled. A 30-second cache holds them, and saving invalidates it at the moment you save. So an edit takes effect on the agent's next turn, without a conversation reset or a restart.

The agent can change these files itself, so the prompt carries only a version you approved. What you save here is approved. A change the agent makes takes effect at once only when all of these are true: it came from your own chat (the web chat, or your own direct messages), that conversation has read nothing from outside (no web pages, search results, command output, files people sent, messages from other people, or memories of what other people said), and the change passes the prompt-injection check. Any other change waits for you. Until you decide, the agent keeps the approved version, with one line in its prompt saying a change is waiting, and [Alerts](/guide#settings-alerts) tell you about it. A change made outside JClaw, such as in a text editor or by copying files back from a backup, waits in the same way.

Waiting changes show above the editor, for example "SOUL.md has changes that wait for your approval. Until then the agent keeps the approved version." The file's tab has an amber dot. Click **Show changes** to see the lines that changed, then **Approve** or **Reject**. **Reject** writes the approved version back. A change the agent made that took effect on its own shows as "The agent changed USER.md, and the change applied on its own." with an **Undo** button, which puts back the version before it. Your name in `USER.md` is recorded by JClaw itself when you tell the agent, so it never waits.

JClaw also checks these files for prompt injection each time you open the agent. If one matches, a panel above the editor lists each match with its file, line and rule, and a tip on how to reword it: "Check each match before you approve a change. Reword approved text that was not meant as an instruction." The tab of a file with a match shows how many it has. The matched text is marked in red in both the editor and the preview, and a hidden character shows as a thin red bar where it sits. Click a match to open its file with the text selected. **Remove hidden characters** deletes the hidden characters from the open file; save the file to keep the change. When you save a file, the files are checked again. To check them again at any time, click **Check files**. If every file is clean, it says "No prompt-injection matches in these files."

### Workspace manager

Below the editor, once the agent has been saved, the **Workspace** panel lists everything on disk under this agent's workspace. That includes whatever the agent has written, not only the five files above:

- **Tree and sizes**: folders expand in place, and every row shows its size (a folder's is everything under it). The header shows the workspace **Total**. Folders, text files, and other files each get their own color and icon.
- **Filter**: the filter bar narrows the tree to the entries whose name matches, and keeps open the folders that lead to them. A very large tree shows its first 1,000 rows.
- **Auto-refresh**: the tree reloads every 10 seconds while the tab is visible, so a file a running agent writes shows up on its own.
- **Download**: a file downloads as itself; a folder downloads as a zip.
- **Back up**: in the panel header, downloads the whole workspace as one zip, Standing Orders included.
- **Delete**: the trash icon asks for an inline **Confirm**. A folder goes with everything in it. A symlink is removed without touching what it points to. The five files above are the Standing Orders. They are marked **protected** and cannot be deleted. Edit them in the tabs instead.

### Other sections

Five more sections sit on the same page, one line each:

- **ACP External Harness** (custom agents only): sets `acpAllowed`, letting this agent spawn [`runtime=acp` subagents](/guide#subagents-acp-harness) under an external coding harness, outside JClaw's tool and workspace confinement.
- **Memory Autocapture**: automatically capture durable facts from this agent's conversations into long-term memory.
- **Core memories**: the memories always loaded into this agent's prompt, independent of autocapture, with a cap and a **Migrate excess** action that refiles anything over it.
- **Content Compression**: shrink large tool output (JSON arrays, code, prose) after the model has read it once: a master toggle, per-type sub-toggles, and an aggressiveness slider. A result of up to 6,000 tokens reaches the model whole the first time, and a larger one is shortened from the start.
- **Standing Tool Approvals**: the tools this agent may run without being asked, each granted by an "always allow" tap, with **Revoke** on every row. A grant counts only on a turn you started: your web chat, or a channel turn the binding proved is yours. A guest's turn in a group chat still goes through the approval prompt or the off-channel policy.

:::note Tool Approvals
Whether a dangerous action (such as `exec`) runs at all when nobody can be asked is decided platform-wide under [Settings → Security → Tool Approvals](/guide#settings) (`tool.approval.offChannelPolicy`: `allow`, `deny`, or `ask`). The two per-agent toggles below only widen what the Main Agent's shell may reach.
:::

### Shell Exec Privileges (Main Agent only)

Two toggles govern how strictly the Main Agent's shell tools enforce safety. Custom agents do not see this section. They inherit the standard policy.

| Setting | Default | Effect |
|------------------------|---------|--------------------------------------------------------------------------------------------|
| **Bypass allowlist** | off | When on, the Main Agent can run any shell command, not just the operator-curated allowlist. |
| **Allow global paths** | off | When on, the Main Agent can read and write outside its workspace directory. |

:::gotcha
**Bypass allowlist** removes the safety floor. Enable it only on a Main Agent you trust, on a machine where you are comfortable letting the model run arbitrary commands. The system-wide allowlist itself is edited in [Settings → Shell Execution](/guide#settings).
:::

Neither toggle loosens the OS sandbox. With `shell.sandbox` set by the **sandbox** control in [Settings → Security → Shell Execution](/guide#settings-shell-execution), a confined `exec` run can write only inside the agent's workspace, whatever these privileges say.

### Shell Allowlist (effective view)

This is a derived, read-only view of every shell command this agent can actually run. It expands inline on the edit page. It aggregates:

- The **global** allowlist, edited in [Settings → Shell Execution](/guide#settings).
- Per-skill grants: each enabled skill can contribute commands at install time. The view groups grants by the skill that contributed them.

To remove a per-skill grant, disable or remove the skill. To change the global allowlist, edit [Settings](/settings).

## Enabling and disabling

Each Custom Agent has a toggle on its row. Disabled agents:

- Cannot be picked as a new [channel binding](/channels) target. Existing bindings keep working until you remove them.
- Still exist. Toggle back on to restore.

The Main Agent cannot be disabled.

## Capability pills

Each agent row shows a strip of capability pills derived from the chosen model. A pill only appears when the model supports it:

- **thinking**: the model surfaces its internal thought process.
- **vision**: the model accepts image inputs natively.
- **audio**: the model handles voice notes directly (no transcript pre-step).
- **video**: the model accepts video inputs natively. Other models fall back to the dedicated video model or frame extraction.
- **no tools**: the model cannot call tools, so this agent's tools and skills will stay silent.

Only the **thinking** pill is clickable. Clicking it toggles the agent's reasoning mode on or off and saves immediately. The other pills are informational. A model that always thinks shows a locked-on thinking pill that you cannot toggle.

This page is the only place the defaults change. The model picker and Think pill in [Chat](/guide#chat-model-and-thinking-for-this-conversation), and `/model` and `/think` on the other channels (the `/model` switch only from the binding's owner, see [Slash commands](/guide#chat-slash-commands)), override them for one conversation. The agent stays as you set it here.

## Tips and gotchas

:::tip Start with one agent
You do not need many agents to be productive. A single well-tuned agent with the right tools and skills usually beats five overlapping ones. Add specialized agents only when you find yourself wishing for a different tool surface or system prompt for a recurring kind of work.
:::

:::gotcha "provider not configured"
A small amber **provider not configured** pill on an agent row means the agent's selected provider does not have an API key, or its local provider is not reachable. Visit [Settings → LLM Providers](/guide#settings-llm-providers) to fix it. Until you do, the status dot on [Chat](/chat)'s model picker turns gray (**Model offline**) for that agent.
:::

:::note Editing does not rewrite history
Editing an agent does not retroactively change past replies. They keep the model and prompt they were answered with. The next turn in any conversation, old or new, uses the updated config. The exception is a conversation that carries its own model or thinking override.
:::

## Where to go next

You have now shaped an agent. The next questions are where the agent can be reached and what extra power you can give it:

- [Chat](/guide#chat): use the agent you just created.
- [Conversations & Channels](/guide#conversations-and-channels): connect your agent to Slack, Telegram, or WhatsApp.
- [Skills, Tools & MCP Servers](/guide#skills-tools-mcp): extend what your agents can do.
- [Settings](/guide#settings): configure providers, API keys, and platform-wide caps.