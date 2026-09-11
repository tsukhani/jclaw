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

Skills can also contribute shell commands to an agent's effective allowlist (visible on the [Agents](/guide#agents) edit form under *Shell Allowlist*) — so a "git wrangler" skill can grant the commands it needs without you hand-editing the global allowlist.

### Global skills vs Agent skills

The [Skills](/skills) page splits into two columns:

- **Global Skills** (left) — the library you've built. Drag any global skill onto an agent on the right to assign it.
- **Agents** (right) — every agent, with the skills currently attached to each.

You can also **promote** an agent-specific skill back into the global library by dragging it from the right column to the left. This is the workflow for refining a skill on one agent first, then making it reusable everywhere. Promotion routes the skill through an LLM sanitization pass — see [Settings → Skills Promotion](/guide#settings) for the provider/model/timeout knobs.

### Creating a skill

Skills aren't written on this page. Two ways to get one:

- **Browse catalog** — the button above the Global Skills column opens the importable-skills catalogs: static ones (the GitHub/Mastra index, with search, sort and category facets) and the dynamic ClawHub registry (search plus paging). **Import** pulls a skill straight into **Global Skills** in one click.
- **Author it** with the built-in **skill-creator** skill: ask an agent (in [Chat](/chat)) to make a skill and it writes one into that agent's workspace. A skill has a **Name** (short identifier), a **Description** (shown in the library), and **Content** (the markdown body that becomes part of the system prompt). Once it's good, drag it from the agent's column to **Global Skills** to promote it for reuse.

### Viewing

Click any skill row to open a **read-only** viewer of its files. A global skill can be **renamed** inline (double-click its name in the Global Skills column) and **deleted** from its row; to change its content, edit it via the skill-creator skill in an agent's workspace (then re-promote if it's a global skill). Updates apply to every agent using the skill the next time it generates; past conversations keep the prompt they were created with.

:::tip Start broad, then split
A skill that's too narrow gets duplicated. A skill that's too broad gets attached to agents that don't need most of it. When in doubt, start broad and split when you notice an agent ignoring half the skill's content.
:::

---

## Tools

The [Tools](/tools) page is a catalog of every built-in capability JClaw ships with. Each card shows:

- **Icon and name.**
- **Category** — `System`, `Web`, `Files`, or `Utilities`. (MCP-server tools aren't listed here — they live on the [MCP Servers](#skills-tools-mcp-mcp-servers) page.)
- **Description** — what the tool does in one or two sentences.
- **Functions** — the individual actions the tool exposes. Expand the card to see the per-function detail.

Use the **category pills** at the top of the page to filter, and **Expand all** / **Collapse all** to control density.

### How tools become available to an agent

Tools listed here are *available*. To make a tool *active* for a specific agent:

1. Open [Agents](/agents).
2. Click the agent.
3. Scroll to **Tools** in the edit form.
4. Tick the box next to the tool.

A few tools require extra setup (an API key, a workspace path, a shell allowlist entry); that config lives under the matching [Settings](/guide#settings) section.

:::note Why isn't this just one page?
Tools are a *catalog* and binding tools to agents is an *agent* concern. Keeping them on separate pages means the catalog stays clean as your roster of agents grows.
:::

### Printing

The `printer` tool discovers printers on the local network and sends jobs to them, with no CUPS, no `lp`, and no OS printing subsystem — it is JVM-native end to end, so it behaves identically on macOS, Linux and Windows, and works inside a container that has no print stack at all.

Four actions: `discover` (mDNS/Bonjour scan), `print` (a workspace file by path, or literal text), `status`, and `cancel`.

Three backends are tried in order, and the order is about **how much each can tell you**, not speed:

| Backend | Port | Reports back? |
|---|---|---|
| IPP | 631 | Yes — job id and printer-reported state |
| Raw socket (JetDirect) | 9100 | No — a successful write only proves the bytes left this machine |
| LPD (RFC 1179) | 515 | Barely — a one-byte acknowledgement of receipt |

That difference is surfaced, not hidden. When a job goes out over raw socket or LPD, the tool's reply says explicitly that the backend cannot confirm the document printed. An agent reporting "printed successfully" off a blind write would be stating something it has no way to know.

**Job options.** `sides` (one-sided, two-sided-long-edge, two-sided-short-edge), `color` (color, monochrome, auto) and `media` (a paper size like `iso_a4_210x297mm`, or a tray name the printer advertises). Omit any of them to use the printer's own default.

These are **IPP-only** — they travel as RFC 8011 job-template attributes, and the byte-stream backends have nowhere to put them. If a job asks for double-sided and then falls back to raw socket or LPD, it prints single-sided; the tool says so explicitly rather than letting you discover it from the paper.

:::caution Printing is physical and irreversible
Paper comes out of a device in someone's room and there is no undo. The tool never guesses a target — `print` requires a printer you named — and it is **off by default for every agent**. Turn it on deliberately, per agent, on the [Agents](/agents) page.
:::

If `discover` returns nothing, that is often the network rather than the printer: mDNS is link-local, so it is routinely blocked on VPNs and in containers without a multicast route. Pass the printer's address as `host` to bypass discovery.

---

## MCP Servers

The Model Context Protocol (MCP) is an open standard that lets external programs expose tools to LLM apps like JClaw. Examples: a server that wraps your team's Jira instance, one that talks to Postgres, one that drives a browser. An MCP server's tools are managed on the [MCP Servers](/mcp-servers) page — not the Tools page, which lists only JClaw's first-party tools.

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

### When a server stops answering

Two independent guards keep a misbehaving server from taking a whole turn down with it:

- **The connection watchdog** reacts to a transport that died. A STDIO process that exits or an HTTP server that closes the connection is reconnected with a backoff, and the server's row shows the disconnect and the last error.
- **The circuit breaker** reacts to a server that is still connected but not answering — the case the watchdog cannot see. Every tool call has a 30 s timeout; only the connection handshake, which has to wait for a cold server to install and start, gets two minutes. Three timeouts (or other server-side failures) in a row, or half of the last ten calls, open the server's breaker; for the next 30 s every call to it fails in microseconds with an error the agent can read — `MCP server 'name' is failing fast: too many recent tool-call failures` — instead of costing another 30 s each. Two probe calls then decide whether it closes again. Each server has its own breaker, so one hung server never slows a healthy one.

A tool that runs and reports its own error (a file that does not exist, a query that fails) does not count against the breaker: the server answered, and that is tool-level semantics. A successful reconnect clears a breaker that opened on its own, since the dead client's failures say nothing about the fresh one.

The [Dashboard](/)'s **Circuit Breakers** panel shows every server's breaker and lets you **Isolate** one by hand — useful to take a flaky server out of an agent's reach for a cooldown without disabling it. An isolated server's tool calls fail with `isolated by the operator`, so neither the agent nor the log reads your decision as the server having broken, and a reconnect does not undo it. **Restore** lifts it early.

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
