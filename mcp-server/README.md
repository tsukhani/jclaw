# JClaw MCP Server

A Model Context Protocol server that exposes JClaw's HTTP API as
agent-callable tools. Built for JCLAW-282.

External MCP clients — Claude Desktop, Cursor, Continue, or another
JClaw instance — can connect to this server and invoke any JClaw
operation (list agents, fetch conversations, etc.) without ever
opening a browser or maintaining a session cookie.

Each operation in JClaw's OpenAPI spec becomes one MCP tool. Tool
names are the operation's `operationId` with a `jclaw_` prefix
(e.g. `listAgents` becomes `jclaw_listAgents`).

## Quick start

### 1. Mint an API token

Sign in to JClaw, open **Settings → API Tokens**, and click
**Mint token**. Give it a name (e.g. "claude-desktop"), pick a scope:

- **Read-only** — `GET` operations only. Safer for an agent that
  only needs to read JClaw state.
- **Full** — every verb. Required if you want the agent to create
  agents, send chat messages, modify bindings, etc.

JClaw shows the plaintext token **exactly once**. Copy it and store
it in your OS keychain or a `.env` file you don't commit. If you
lose it, revoke + remint.

### 2. Build the server jar

From the repo root:

```bash
./gradlew :mcp-server:jar
```

The output jar lands at
`mcp-server/build/libs/jclaw-mcp-server.jar`. It's self-contained —
no dependency on the running JClaw process besides being able to
reach its HTTP API.

### 3. Configure your MCP host

#### Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json`
on macOS (or `%APPDATA%\Claude\claude_desktop_config.json` on Windows):

```json
{
  "mcpServers": {
    "jclaw": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/jclaw/mcp-server/build/libs/jclaw-mcp-server.jar",
        "--base-url=http://localhost:9000",
        "--scope=read-only"
      ],
      "env": {
        "JCLAW_API_TOKEN": "jcl_paste_your_token_here"
      }
    }
  }
}
```

Restart Claude Desktop. The JClaw tools appear in the model's tool
list on the next conversation. Test with:

> List my JClaw agents.

#### Cursor

In Cursor's MCP settings (`Cursor → Settings → MCP`), add:

```json
{
  "jclaw": {
    "command": "java",
    "args": [
      "-jar",
      "/path/to/jclaw/mcp-server/build/libs/jclaw-mcp-server.jar",
      "--base-url=http://localhost:9000",
      "--scope=read-only"
    ],
    "env": {
      "JCLAW_API_TOKEN": "jcl_paste_your_token_here"
    }
  }
}
```

#### JClaw-to-JClaw

To let agents on one JClaw instance call another instance's API,
go to **Settings → MCP Servers** on the calling instance and add a
new server:

- **Name**: e.g. `jclaw-prod`
- **Transport**: `STDIO`
- **Command**: `java`
- **Args**:
  - `-jar`
  - `/absolute/path/to/jclaw-mcp-server.jar`
  - `--base-url=https://prod.jclaw.example.com`
  - `--scope=read-only`
- **Env**:
  - `JCLAW_API_TOKEN`: the token minted on the *target* instance

The calling instance's MCP allowlist (JCLAW-32) decides which agents
can use these tools.

## Flags

| Flag | Env var | Default | Notes |
| --- | --- | --- | --- |
| `--base-url=<url>` | `JCLAW_BASE_URL` | (required) | JClaw API root. Example: `http://localhost:9000`. |
| `--token=<token>` | `JCLAW_API_TOKEN` | (required) | Bearer token minted from Settings. Prefer the env var so the token doesn't end up in process listings or shell history. |
| `--scope=<scope>` | `JCLAW_SCOPE` | `read-only` | `read-only` advertises only `GET` operations; `full` advertises every verb. Must match the scope baked into the token. |
| `--exclude=<pattern>` | — | (none) | Substring match against operationId or path. Repeatable. Use to hide operations the agent shouldn't see. |

## What gets exposed (and what doesn't)

**Exposed by default**:

- Every operation in `/@api/openapi.json` that the operator's scope
  permits.

**Always skipped**:

- **Streaming endpoints** (`text/event-stream` responses) — MCP v1
  doesn't carry SSE, and tunnelling streamed bytes through a tool
  result would deliver them all at once on stream close. The chat
  stream (`POST /api/chat/stream`) and event bus (`GET /api/events`)
  fall under this rule. Use the non-streaming equivalents
  (`POST /api/chat/send`, conversation transcript fetch) instead.
- **Token CRUD** (`GET/POST/DELETE /api/api-tokens`) — JClaw's
  backend refuses bearer-authenticated requests on those routes as
  a privilege-escalation guard. The MCP server still advertises
  them under `--scope=full`, but every call returns 403.

**Skipped under `--scope=read-only`**:

- Every non-`GET` operation. The JClaw backend enforces the same
  rule at the auth filter (a read-only-scoped token cannot call a
  mutating verb regardless of what the MCP server advertises) —
  belt-and-suspenders.

## Troubleshooting

**"No tools generated" on startup** — the OpenAPI spec at
`/@api/openapi.json` either isn't reachable or returned an empty
body. Verify:

```bash
curl -H "Authorization: Bearer $JCLAW_API_TOKEN" \
    http://localhost:9000/@api/openapi.json | jq '.paths | length'
```

If that returns 0, JClaw is up but the OpenAPI plugin isn't serving
the spec. Check `openapi.publicSpec=true` in `conf/application.conf`.

**MCP host shows "server crashed"** — check stderr in the host's
logs. Common causes:

- Missing `JCLAW_API_TOKEN` — the launch error names which env
  var or flag is missing.
- JClaw not running on the configured `--base-url`.
- Token revoked. Mint a fresh one and update the host config.

**Tool calls fail with 403** — the token is read-only-scoped but the
operation requires `full`. Mint a `full` token (acknowledging the
heightened blast radius) or constrain the agent to read operations.

**Tool calls succeed but the result is huge** — JClaw endpoints can
return long lists (e.g. `GET /api/conversations`). The agent sees
the entire response body as text content. Use the available
query/filter parameters (e.g. `?limit=`) on those operations so the
context doesn't fill up on a single tool call.

## Notes for developers

- Source layout: `mcp-server/src/main/java/jclaw/mcp/server/`.
- Build: `./gradlew :mcp-server:jar` for the runnable jar,
  `./gradlew :mcp-server:test` for the test suite.
- Tests stub the network with `MockWebServer` and the OpenAPI tree
  by hand; no live JClaw instance required.
- The module is intentionally Play-independent — it imports nothing
  from the main app. The on-disk wire (`/@api/openapi.json`) is the
  contract, not a shared Java type.
- HTTP transport for the MCP server itself is deferred (the ticket
  ships stdio for v1). The `Transport` interface exists so adding
  HTTP later doesn't require touching `McpServer`.
