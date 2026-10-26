---
name: jclaw-api
description: >-
  Operate JClaw itself from chat — list/create/update agents, add or test MCP servers, read config. Use whenever the user asks to change JClaw's own configuration rather than perform an external task.
metadata:
  version: "0.1.1"
  author: Tarun Sukhani
  jclaw-icon: "⚙️"
  jclaw-tools: jclaw_api
---

# JClaw API

Use this skill when the user asks you to **change JClaw's own state** — adding an MCP server, creating an agent, etc.

The `jclaw_api` tool exposes JClaw's own HTTP API, and the list of callable endpoints is **discovered at runtime** — there is no fixed catalog to drift. Do not invent endpoints, and never call one that `discover` does not list. An endpoint is callable only when its backend action is declared agent-reachable (`@AgentAccess` level `OPEN`, or `OWN_ONLY`, which reaches only your own agent's rows — `main` reaches every agent's); an undeclared action is operator-only. So a new endpoint appears in `discover` once the backend opens it, with no skill edit, and if `discover` does not list something, it is deliberately off-limits (see below). Access is decided by that backend declaration, never by this skill.

## How to call

1. **Discover** — when you're unsure which endpoint or payload to use, call `jclaw_api` with `action="discover"` (optionally pass a `filter` substring like `"agent"` or `"mcp"` to narrow the list). It returns the live catalog: verb, path, a one-line summary, and a body hint for mutating verbs.
2. **Invoke** — call `jclaw_api` with:
   - `method`: one of `GET`, `POST`, `PUT`, `PATCH`, `DELETE`
   - `path`: starts with `/api/` exactly as discover reports it — never include the host or scheme
   - `body`: a JSON object matching the operation's request shape (POST/PUT/PATCH only)
   - `query`: optional, only when an operation documents query parameters

The tool returns `HTTP <status>\n<body>` so you can read both. A 4xx response usually carries a JSON `error` field that tells you what to correct.

## Common operations (quick reference)

These staples don't need a discover round-trip first; run `action="discover"` for anything else.

- **Agents** — `GET /api/agents` (list); `POST /api/agents` body `name`, `modelProvider`, `modelId` (create); `PUT /api/agents/{id}` (update).
- **MCP servers** — `POST /api/mcp-servers` body `name`, `enabled`, `transport` (`STDIO` or `HTTP`), then `command`/`args`/`env` or `url`/`headers`; confirm it boots with `POST /api/mcp-servers/{id}/test`.
- **Config** — `GET /api/config` (all, masked) or `GET /api/config/{key}`.

## Off-limits

The following endpoints exist in JClaw's API but are **deliberately not callable through `jclaw_api`**, and the tool itself will refuse them (they're also excluded from `discover`):

- `POST /api/chat/send` / `POST /api/chat/stream` / `POST /api/chat/upload` — these would create a recursion loop where this skill sends a chat that itself invokes this skill again. If the user wants to send a chat programmatically, suggest the per-channel webhook endpoints instead.
- `POST /api/auth/login` / `POST /api/auth/setup` / `POST /api/auth/reset-password` — credential operations belong in the Settings UI.
- `/api/webhooks/*` — verified by their own signature mechanisms; not for in-process use.
- `/api/events` — Server-Sent Events; the tool buffers full responses and isn't suited to streams.

- `/api/bindings`, `/api/channels/telegram/*` — channel routing and Telegram bot tokens; editing these could redirect or impersonate your messaging.
- `/api/tailscale` — network-funnel infrastructure config.
- `/api/logs` — raw application logs can leak secrets and PII.
- `/api/metrics/loadtest*` — the load-test harness; an agent must not spawn load.
- `/api/memories*` — the operator's cross-agent view of every agent's stored memories. An agent reaching it could read, edit or delete another agent's corpus. Use the `memory` tool instead, which is scoped to the calling agent's own memories.

Individual destructive or secret-bearing actions inside otherwise-callable controllers are operator-only the same way (no agent-reachable `@AgentAccess` level): bulk conversation deletes, channel-config writes, and the transcription model-download trigger. You won't see them in `discover`.

If the user requests an operation that resolves to one of these, refuse explicitly and explain the boundary. Do not try to compose multiple permitted endpoints to simulate a forbidden one.

## When in doubt

- Read before write. For "update X", first `GET` the resource, show the user the relevant before-state, then propose the change.
- Echo the result. After a successful mutation, read the response body and summarize what changed (the new id, the new connected-status) so the user has explicit confirmation.
- If JClaw returns 4xx, read the `error` field and either correct the request or report the constraint plainly. Never silently retry the same call.
