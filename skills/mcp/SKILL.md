---
name: mcp
description: Connect JClaw to Model Context Protocol (MCP) servers for structured tool access and cross-platform integrations
version: 2.0.0
tools: []
icon: 🔌
---

# MCP - Model Context Protocol Integration

Integrate JClaw with MCP (Model Context Protocol) servers to access external tools, APIs, and services through a standardized interface. Discovered MCP tools appear in the agent tool loop alongside native tools.

## What is MCP?

Model Context Protocol is an open protocol that standardizes how AI agents connect to external data sources and tools. MCP servers expose tools that JClaw discovers at connect time and invokes through the same machinery as native tools.

## How JClaw stores MCP server config

The `mcp_server` table is the source of truth. Each row is one configured server, operator-level (not per-agent). Fields:

- `name` (unique) — the server identifier; tools register as `mcp_<name>_<tool>`
- `enabled` — when true, the connection manager dials this server at startup
- `transport` — `STDIO` or `HTTP`
- `configJson` — transport-specific config (see below)
- runtime: `status`, `lastError`, `lastConnectedAt`, `lastDisconnectedAt`

The admin UI for adding/editing/testing/toggling rows ships with JCLAW-33. Until then, rows can be seeded directly via the database for local development.

### configJson shape

**Stdio transport** — local subprocess speaking JSON-RPC over stdin/stdout:

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

## Lifecycle

1. **Boot**: `McpStartupJob` runs once at application start and asks `McpConnectionManager` to dial every enabled server in parallel.
2. **Connect**: each server gets one virtual thread that runs the JSON-RPC `initialize` handshake, sends `notifications/initialized`, and fetches `tools/list`.
3. **Register**: discovered tools are wrapped as `McpToolAdapter` and published into `ToolRegistry` under the server's group. They become invokable from any agent the moment they land.
4. **Reconnect**: a transport error (process crash, network blip) flips the connection back to `DISCONNECTED`, an `MCP_DISCONNECT` event is logged, and an exponential backoff retry kicks off (1s, 2s, 4s, …, capped at 30s).
5. **Shutdown**: `ShutdownJob` calls `McpConnectionManager.shutdown()` to close every connection cleanly.

## Tool naming

`mcp_<server>_<tool>` — flat namespace, underscore-only so the result is a valid identifier across LLM tool-call schemas (some OpenAI-style schemas reject `.`). Example: a `github` server's `create_issue` tool registers as `mcp_github_create_issue`.

## Observability

- **event_log**: `MCP_CONNECT` on successful connect, `MCP_DISCONNECT` on transport error (categories used by `EventLogger`). Each entry carries the server name in the message.
- **mcp_server.status**: `DISCONNECTED` / `CONNECTING` / `CONNECTED` / `ERROR` — read this for current state.
- **mcp_server.lastError**: most recent failure reason. The admin UI surfaces this as a hover tooltip on the status badge.
- **stderr from stdio servers** is forwarded to the SLF4J log under the `[mcp:<server>:stderr]` prefix — useful for diagnosing badly-misconfigured server commands.

## Capabilities and what JClaw exposes

JClaw declares **no client capabilities** during `initialize` — no sampling, no elicitation, no roots. A spec-compliant server will skip server→client requests for those features, and we reply `-32601 Method not found` if one slips through anyway. If a future story needs sampling/elicitation for a specific server class, those become additive 100-300 LOC extensions to `McpClient`.

## Common MCP servers

| Server | Purpose | Install |
|--------|---------|---------|
| `@modelcontextprotocol/server-github` | GitHub API access | `npx -y @modelcontextprotocol/server-github` |
| `@modelcontextprotocol/server-filesystem` | File operations | `npx -y @modelcontextprotocol/server-filesystem <path>` |
| `@modelcontextprotocol/server-postgres` | PostgreSQL queries | `npx -y @modelcontextprotocol/server-postgres <db_url>` |
| `@modelcontextprotocol/server-slack` | Slack integration | `npx -y @modelcontextprotocol/server-slack` |
| `@modelcontextprotocol/server-sqlite` | SQLite database access | `npx -y @modelcontextprotocol/server-sqlite <db_path>` |

## Per-agent gating

JCLAW-31 makes MCP tools globally available to every agent. JCLAW-32 layers per-agent allowlist enforcement on top — until then, an enabled MCP server's tools are visible to every agent in the tool catalog (mediated through the existing `agent_tool_config` per-agent disable mechanism, same as native tools).

## References

- MCP Specification: https://modelcontextprotocol.io
- MCP Servers GitHub: https://github.com/modelcontextprotocol/servers
- JCLAW-29 (epic): MCP Integration
- JCLAW-31 (this story): MCP client with stdio/HTTP/SSE transports
- JCLAW-32: Allowlist integration (Confused-Deputy-Proof)
- JCLAW-33: MCP server admin UI
