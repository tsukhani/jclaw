---
name: mcp
description: Connect JClaw to Model Context Protocol (MCP) servers for structured tool access and cross-platform integrations
version: 2.0.0
tools: []
icon: 🔌
---

# MCP - Model Context Protocol Integration

Connect JClaw to Model Context Protocol (MCP) servers so their tools become available to your agents alongside JClaw's native ones (filesystem, web fetch, shell, etc.).

## What is MCP?

Model Context Protocol is an open standard for how AI agents talk to external tools. An MCP **server** publishes a set of tools (e.g. `create_issue`, `search_messages`); an MCP **client** discovers and invokes them. JClaw is the client.

## How JClaw fits into the picture

> **JClaw is a CLIENT, not a hosting platform.** The Admin UI configures *connections* to MCP servers — it does not install or run them. The user provides the server; JClaw consumes it.

Concretely, the Admin UI's "Add server" button never:

- installs an npm/pip/cargo package
- provisions a remote service
- generates credentials

It only writes a row in the `mcp_server` table that says *"here's an MCP server I want to talk to."* The runtime then dials that server.

The two transports differ only in **who owns the server process**:

- **STDIO** — the server is a local subprocess. JClaw spawns it on demand (so the binary just has to be installed on the host) and pipes JSON-RPC over its stdin/stdout. When you disable or delete the row, JClaw kills the subprocess.
- **HTTP** — the server runs out-of-band, somewhere reachable over the network. JClaw POSTs JSON-RPC to its endpoint. JClaw never controls the server's lifecycle.

A useful gut-check before adding an STDIO row: **can the user run the same command in their terminal and see JSON-RPC responses appear?** If yes, JClaw will work too. If `npx -y @modelcontextprotocol/server-github` errors in a terminal, it'll error inside JClaw the same way — fix it at the terminal first.

## Adding your first MCP server (STDIO)

This is the most common path — most published MCP servers ship as Node packages run via npx.

1. **Pick a server.** GitHub is a good first one because the credentials are easy to obtain.
2. **Make sure the runtime exists on the host.** STDIO servers run as a child process of JClaw. For npx-based servers you need `node` and `npx` on `PATH`. Either pre-install the package (`npm install -g @modelcontextprotocol/server-github`) or trust npx's first-run download — JClaw doesn't care which.
3. **Get the credentials the server needs.** GitHub: a personal access token (`ghp_…`) with the scopes the tools you'll use need.
4. **In JClaw, navigate to /mcp-servers and click "+ Add server"** with these fields:
   - **Name**: `github` — becomes the prefix for every tool this server contributes (`mcp_github_create_issue`, `mcp_github_search_issues`, …).
   - **Transport**: `STDIO`
   - **Command**: `npx`
   - **Arguments** (one per line): `-y` then `@modelcontextprotocol/server-github`
   - **Environment variables**: `GITHUB_PERSONAL_ACCESS_TOKEN` → your token
   - **Enabled**: leave checked
5. **(Optional) Click Test connection.** JClaw spins up a throwaway client with the same config, runs `initialize` + `tools/list`, and renders the result inline — success with a tool count, or the error message in red. The Test button only works after the row is saved.
6. **Click Create.** JClaw spawns the subprocess, completes the MCP handshake, registers the discovered tools (`mcp_github_*`) into the `ToolRegistry`, and broadcasts per-agent allowlist grants. The status badge flips from CONNECTING to CONNECTED within a second or two for cached npm packages, longer the first time npx downloads the package.
7. **Use it.** Open any agent's chat. The agent's LLM now sees `mcp_github_create_issue` and friends in its tool catalog and can invoke them like any native tool.

## Adding an HTTP MCP server

For a remote MCP server (managed providers like Composio, or a server you self-host elsewhere):

1. **The server has to be running already** — JClaw won't start it. You need a URL and any auth headers it expects.
2. **In /mcp-servers, click "+ Add server":**
   - **Name**: e.g. `composio`
   - **Transport**: `HTTP`
   - **Endpoint URL**: e.g. `https://mcp.composio.dev/v1/<account>`
   - **Headers**: e.g. `Authorization` → `Bearer <token>`
   - **Enabled**: checked
3. **Test → Create.** Same handshake, but over POST → JSON or POST → SSE depending on what the server returns.

## What JClaw takes care of automatically

Once a row is configured, the runtime handles:

- **Process supervision** (STDIO) — restart on crash, kill on disable, drain stderr to the SLF4J log under `[mcp:<server>:stderr]`
- **Reconnect with exponential backoff** — `min(2^attempts, 30) seconds`. Capped at 30s per the AC.
- **Tool discovery + re-registration** — handles `notifications/tools/list_changed` so server restarts pick up new tools without operator action
- **Per-agent allowlist** — a row in `agent_skill_allowed_tool` per (agent, advertised tool) on connect; cleared on disconnect; backfilled when a new agent is created
- **Audit logging** — `MCP_CONNECT`, `MCP_DISCONNECT`, `MCP_TOOL_INVOKE`, `MCP_TOOL_UNREGISTER` written to `event_log` and visible on the /logs page
- **Live status surfaced in the UI** — DISCONNECTED / CONNECTING / CONNECTED / ERROR badges, with `lastError` as a hover tooltip

So the user's job reduces to: **name + executable-or-URL + credentials**.

## Tool naming convention

`mcp_<server>_<tool>` — flat namespace, underscores only so the result is a valid identifier across all LLM tool-call schemas (some OpenAI-style schemas reject `.`). Example: a `github` server's `create_issue` tool registers as `mcp_github_create_issue`. The `github` part comes from your row's `name` field.

## Common MCP servers

| Server | Purpose | Run via |
|--------|---------|---------|
| `@modelcontextprotocol/server-github` | GitHub API access | `npx -y @modelcontextprotocol/server-github` |
| `@modelcontextprotocol/server-filesystem` | File operations | `npx -y @modelcontextprotocol/server-filesystem <path>` |
| `@modelcontextprotocol/server-postgres` | PostgreSQL queries | `npx -y @modelcontextprotocol/server-postgres <db_url>` |
| `@modelcontextprotocol/server-slack` | Slack integration | `npx -y @modelcontextprotocol/server-slack` |
| `@modelcontextprotocol/server-sqlite` | SQLite database access | `npx -y @modelcontextprotocol/server-sqlite <db_path>` |

The full official catalog is at https://github.com/modelcontextprotocol/servers.

## Writing your own MCP server

Out of scope for this skill — JClaw is the client. To build a custom server, use one of the official SDKs (Python, TypeScript, Go, Rust, Java) at https://modelcontextprotocol.io. Once the server runs and speaks JSON-RPC over stdio or HTTP, point JClaw at it via the Admin UI like any other server.

## Troubleshooting

- **"Failed to connect" on Test (STDIO)** — try the same command in a terminal first. Common culprits: `npx` not on PATH, package name typo, network blocked from running `npm install`, missing env var the server requires.
- **"Failed to connect" on Test (HTTP)** — verify the URL is reachable from the JClaw host (`curl -I <url>`). Check the auth header format the upstream service expects.
- **Server connects but no tools show up** — the server connected fine but advertises zero tools. Some servers gate their tool list on auth scopes (e.g. GitHub returns more tools when the PAT has more scopes). Check the server's docs.
- **Tool calls return "not on the allowlist"** — JCLAW-32's allowlist gate. New agents created BEFORE the server connected get backfilled automatically; agents created during a long disconnection might be missing rows. Toggle the server off and back on to re-broadcast.
- **stderr noise from a stdio server** — surfaced under `[mcp:<server>:stderr]` in the application log. Servers commonly use stderr for log output (the protocol is on stdout); this is informational, not an error.

## Configuration reference

The Admin UI is the canonical interface, but the underlying schema is simple enough to seed directly via SQL for tests or scripted setups.

### Schema

The `mcp_server` table is the source of truth. Each row:

- `name` (unique) — display + tool-prefix
- `enabled` — connection manager dials enabled rows at startup
- `transport` — `STDIO` or `HTTP`
- `configJson` — transport-specific JSON (see below)
- runtime: `status`, `lastError`, `lastConnectedAt`, `lastDisconnectedAt`, `createdAt`, `updatedAt`

### configJson shape

**STDIO transport:**

```json
{
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-github"],
  "env": {
    "GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_xxx"
  }
}
```

**HTTP transport** — Streamable HTTP per MCP 2025-06-18 spec; the server may reply `application/json` (immediate) or `text/event-stream` (streaming) per request:

```json
{
  "url": "https://mcp.example.com/v1/mcp",
  "headers": {
    "Authorization": "Bearer abc123"
  }
}
```

## Internal architecture

For contributors. End users can skip this section.

### Lifecycle

1. **Boot**: `McpStartupJob` (`@OnApplicationStart`) calls `McpConnectionManager.startAll()` which loads every enabled `mcp_server` row and dials each in parallel.
2. **Connect**: per-server virtual thread runs the JSON-RPC `initialize` handshake, sends `notifications/initialized`, fetches `tools/list`, and registers the tools as `McpToolAdapter` instances under the server's group in `ToolRegistry`.
3. **Allowlist broadcast**: same transaction writes one `agent_skill_allowed_tool` row per (existing agent, advertised tool) under `skill_name = "mcp:<server>"`. New agents created later get backfilled by a hook in `AgentService.create`.
4. **Reconnect**: a transport error flips status back to DISCONNECTED, emits `MCP_DISCONNECT`, clears the allowlist (audited as `MCP_TOOL_UNREGISTER`), and an exponential backoff retry kicks off (1s, 2s, 4s, …, capped at 30s). Backoff timers run on a small ScheduledExecutorService of *platform* threads to avoid JDK-8373224 VT Thread.sleep starvation.
5. **Tool invocation**: agent loop calls `McpToolAdapter.execute(args, agent)`. The adapter opens a Tx, checks `McpAllowlist.isAllowed(...)`, writes an `MCP_TOOL_INVOKE` audit row in the same Tx, commits, then (if allowed) calls the MCP server out-of-band.
6. **Shutdown**: `ShutdownJob` calls `McpConnectionManager.shutdown()` to close every connection cleanly.

### Capabilities

JClaw declares **no client capabilities** during `initialize` — no sampling, no elicitation, no roots. A spec-compliant server skips server→client requests for those features. If one slips through anyway, the client replies with JSON-RPC error `-32601 Method not found`. Adding any of these later is additive (100–300 LOC per feature in `McpClient`).

### Observability

- **event_log** categories: `MCP_CONNECT`, `MCP_DISCONNECT`, `MCP_TOOL_INVOKE` (allowed=INFO, denied=WARN), `MCP_TOOL_UNREGISTER`. Every entry carries the server name in the message; `MCP_TOOL_INVOKE` rows carry the args JSON in the `details` column.
- **Status fields** on `mcp_server`: `status`, `lastError`, `lastConnectedAt`, `lastDisconnectedAt` — mutated only by `McpConnectionManager`.
- **Stdio stderr** drains to SLF4J under `[mcp:<server>:stderr]` so a misbehaving server's log output shows up next to the application log instead of polluting the JSON-RPC stream.

## References

- MCP Specification: https://modelcontextprotocol.io
- MCP Servers GitHub: https://github.com/modelcontextprotocol/servers
- JCLAW-29 (epic): MCP Integration
- JCLAW-31: MCP client with stdio/HTTP/SSE transports
- JCLAW-32: Allowlist integration (Confused-Deputy-Proof)
- JCLAW-33: MCP server admin UI
