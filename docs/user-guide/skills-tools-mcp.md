# Skills, Tools & MCP Servers

Out of the box, an agent can read your messages, reason, and reply. To do anything else — search the web, run a shell command, query Postgres, write a file, talk to your team's Jira — it needs **capabilities**. This section covers the three ways JClaw lets you add capabilities to an agent.

| Page                             | What it provides                                                  |
|----------------------------------|-------------------------------------------------------------------|
| [Skills](/skills)                | Reusable instruction bundles you attach to agents (markdown that becomes part of the system prompt). |
| [Tools](/tools)                  | First-party capabilities baked into JClaw (web fetch, shell, file system, search, etc.). |
| [MCP Servers](/mcp-servers)      | External servers that expose additional tools via the Model Context Protocol. |

Each page is a catalog. The actual binding of "which agent gets which capability" happens on the [Agents](/agents) page.

---

## Skills

A skill is a chunk of markdown — instructions, examples, a methodology — that gets injected into an agent's system prompt. Use skills for capabilities you want to reuse across multiple agents:

- A coding style guide.
- A research methodology ("always cite, always cross-check").
- An output template ("respond in JSON with these fields").
- A persona ("you are a careful, thorough technical reviewer").

Skills can also contribute shell commands to an agent's effective allowlist (visible on the [Agents](/guide#agents) edit form under *Shell Allowlist*) — so a "git wrangler" skill can grant the commands it needs without each operator hand-editing the global allowlist.

### Global skills vs Agent skills

The [Skills](/skills) page splits into two columns:

- **Global Skills** (left) — the library you've built. Drag any global skill onto an agent on the right to assign it.
- **Agents** (right) — every agent you own, with the skills currently attached to each.

You can also **promote** an agent-specific skill back into the global library by dragging it from the right column to the left. This is the workflow for refining a skill on one agent first, then making it reusable everywhere. Promotion routes the skill through an LLM sanitization pass — see [Settings → Skills Promotion](/guide#settings) for the provider/model/timeout knobs.

### Creating a skill

Click **New skill** to open the editor. A skill has:

- **Name** — short identifier.
- **Description** — what the skill does (shown in the library).
- **Content** — the markdown body that becomes part of the system prompt.

You can also drop a `.md` file directly onto the Global Skills column to import it as a new skill.

### Editing

Click any skill row to open the editor. Changes apply to **every agent the skill is attached to** the next time they generate. Past conversations keep the prompt they were created with.

:::tip Start broad, then split
A skill that's too narrow gets duplicated. A skill that's too broad gets attached to agents that don't need most of it. When in doubt, start broad and split when you notice an agent ignoring half the skill's content.
:::

---

## Tools

The [Tools](/tools) page is a catalog of every built-in capability JClaw ships with. Each card shows:

- **Icon and name.**
- **Category** — `System`, `Web`, `Files`, `Utilities`, or `MCP` (for tools served by [MCP Servers](#mcp-servers) below).
- **Description** — what the tool does in one or two sentences.
- **Functions** — the individual actions the tool exposes. Expand the card to see the per-function detail.

Use the **category pills** at the top of the page to filter, and **Expand all** / **Collapse all** to control density.

### How tools become available to an agent

Tools listed here are *available*. To make a tool *active* for a specific agent:

1. Open [Agents](/agents).
2. Click the agent.
3. Scroll to **Tools** in the edit form.
4. Tick the box next to the tool.

A few tools require extra setup (an API key, a workspace path, a shell allowlist entry). The tool card flags those with an inline hint; the actual config lives under the matching [Settings](/guide#settings) section.

:::note Why isn't this just one page?
Tools are a *catalog* and binding tools to agents is an *agent* concern. Keeping them on separate pages means the catalog stays clean as your roster of agents grows.
:::

---

## MCP Servers

The Model Context Protocol (MCP) is an open standard that lets external programs expose tools to LLM apps like JClaw. Examples: a server that wraps your team's Jira instance, one that talks to Postgres, one that drives a browser. Anything an MCP server exposes shows up on the [Tools](/tools) page in the `MCP` category, alongside JClaw's first-party tools.

The [MCP Servers](/mcp-servers) page is where you register and configure those servers. Once registered, an MCP server's tools become available to any agent that has the server ticked in its config.

### Two transport flavors

When you click **Add server**, you pick one:

- **STDIO** — JClaw launches the server as a local subprocess and talks to it over stdin/stdout. Best for servers distributed as command-line tools (npm packages, Python scripts).
- **HTTP (Streamable)** — JClaw connects to a running server over HTTP using MCP's streamable-HTTP transport. Best for hosted servers, or anything you'd rather run independently.

### STDIO configuration

| Field         | What to fill in                                                                    |
|---------------|------------------------------------------------------------------------------------|
| **Name**      | Anything memorable; this is the label across the UI.                                |
| **Command**   | Executable path. Example: `npx`.                                                    |
| **Args**      | One argument per line. Example: `-y` then `@modelcontextprotocol/server-postgres`.  |
| **Env**       | Key/value pairs added to the subprocess environment (for API keys, DB URLs, etc.).  |

### HTTP configuration

| Field          | What to fill in                                                                   |
|----------------|-----------------------------------------------------------------------------------|
| **Name**       | Memorable label.                                                                  |
| **URL**        | Full URL to the server's MCP endpoint.                                            |
| **Headers**    | Auth headers, etc. Common keys: `Authorization`, `X-Api-Key`.                     |

### Testing a server

Each row has a **Test** button. JClaw connects, lists the server's tools, and reports back. A successful test means the server is reachable and speaks MCP; if it fails, the error message is surfaced inline so you can fix the config.

A successful test doesn't guarantee a server's tools will work end-to-end — you still need to attach it to an agent and try one.

### Binding a server to an agent

Same flow as tools: open [Agents](/agents), open the agent, scroll to **MCP Servers**, tick the box. The agent now sees that server's tools the next time it generates.

### Enabling and disabling

Each server has an **enabled** toggle. A disabled server keeps its config saved, but agents can't use its tools. Convenient when you want to temporarily silence a noisy server without losing how you set it up.

:::gotcha STDIO servers run with your user's permissions
A STDIO server is just a subprocess. It inherits the JClaw server's environment and process privileges. Only register servers you trust to run code on your behalf — the same care you'd take installing a CLI from npm.
:::

:::tip Start with the official servers
The MCP project's GitHub org publishes a growing list of well-tested servers (filesystem, git, postgres, sqlite, fetch, time). Start there before reaching for community servers; they'll surface most of the productive workflows with the least setup overhead.
:::

---

## Where to go next

You've covered the full layered model: base chat, the agents behind it, where conversations live, three flavors of "outside-the-turn" work, and three ways to extend what agents can do. The last two sections of the guide are about operating the platform:

- [Settings](/guide#settings) — the platform control panel where API keys and per-feature knobs live.
- [Logs & Dashboard](/guide#logs-and-dashboard) — operator visibility into what JClaw is doing.
