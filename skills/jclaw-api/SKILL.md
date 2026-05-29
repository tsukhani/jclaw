---
name: jclaw-api
description: Operate JClaw itself from chat — list/create/update agents, add or test MCP servers, toggle tools/skills on agents, read or write config. Use whenever the user asks to change JClaw's own configuration rather than perform an external task.
version: 0.1.0
tools: [jclaw_api]
icon: ⚙️
---

# JClaw API

Use this skill when the user asks you to **change JClaw's own state** — adding an MCP server, creating an agent, toggling a tool on an agent, editing a config value, etc.

The `jclaw_api` tool exposes a curated, **chat-safe** subset of JClaw's HTTP API, and the list of callable endpoints is **discovered at runtime** — there is no fixed catalog to drift. Do not invent endpoints, and never call one that `discover` does not list. If the user wants something genuinely outside the catalog, say so plainly: extending coverage means marking that controller action `@ChatSafe` in the backend (a code change), not editing this skill.

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
- **Toggle a tool on an agent** — `PUT /api/agents/{id}/tools/{name}` with `{ "enabled": <bool> }`.
- **Install a skill on an agent** — `POST /api/agents/{id}/skills/{name}/copy` with an empty body (installs **and** enables); `PUT /api/agents/{id}/skills/{name}` only toggles an already-installed skill.
- **Config** — `GET /api/config` (all, masked) or `GET /api/config/{key}`; `POST /api/config` body `{ "key": ..., "value": ... }`. Confirm with the user before touching `provider.*`, `auth.*`, `chat.*`, or `shell.*`.

**Common composition** — "create agent X with skill Y":
1. `POST /api/agents` → capture the returned `id`.
2. `POST /api/agents/{id}/skills/Y/copy` with `{}` → installs Y into the new agent's workspace **and** enables it.
3. Skip the PUT — the copy step already enabled the skill. Only call PUT if the user later wants to disable Y without uninstalling it.

## Off-limits

The following endpoints exist in JClaw's API but are **deliberately not callable through `jclaw_api`**, and the tool itself will refuse them (they're also excluded from `discover`):

- `POST /api/chat/send` / `POST /api/chat/stream` / `POST /api/chat/upload` — these would create a recursion loop where this skill sends a chat that itself invokes this skill again. If the user wants to send a chat programmatically, suggest the per-channel webhook endpoints instead.
- `POST /api/auth/login` / `POST /api/auth/setup` / `POST /api/auth/reset-password` — credential operations belong in the Settings UI.
- All `/api/api-tokens` routes — minting or revoking API tokens through chat would be a privilege-escalation surface.
- `/api/webhooks/*` — verified by their own signature mechanisms; not for in-process use.
- `/api/events` — Server-Sent Events; the tool buffers full responses and isn't suited to streams.

If the user requests an operation that resolves to one of these, refuse explicitly and explain the boundary. Do not try to compose multiple permitted endpoints to simulate a forbidden one.

## When in doubt

- Read before write. For "update X", first `GET` the resource, show the user the relevant before-state, then propose the change.
- Echo the result. After a successful mutation, read the response body and summarize what changed (the new id, the new connected-status, the new config value) so the user has explicit confirmation.
- If JClaw returns 4xx, read the `error` field and either correct the request or report the constraint plainly. Never silently retry the same call.
