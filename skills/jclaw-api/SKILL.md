---
name: jclaw-api
description: Operate JClaw itself from chat — list/create/update agents, add or test MCP servers, toggle tools/skills on agents, read or write config. Use whenever the user asks to change JClaw's own configuration rather than perform an external task.
version: 0.1.0
tools: [jclaw_api]
icon: ⚙️
---

# JClaw API

Use this skill when the user asks you to **change JClaw's own state** — adding an MCP server, creating an agent, toggling a tool on an agent, editing a config value, etc. The catalog below is a curated subset of JClaw's HTTP API that is safe to call from chat. **Do not invent endpoints beyond this list**; if the user asks for something not covered here, say so plainly and offer to extend the skill via `skill-creator`.

## How to call

Use the `jclaw_api` tool. Always pass:
- `method`: one of `GET`, `POST`, `PUT`, `PATCH`, `DELETE`
- `path`: starts with `/api/` exactly as documented below — never include the host or scheme
- `body`: a JSON object matching the operation's request shape (POST/PUT/PATCH only)
- `query`: optional, only when an operation documents query parameters

The tool returns `HTTP <status>\n<body>` so you can read both. A 4xx response usually carries a JSON `error` field that tells you what to correct.

## Catalog

### Agents

- **List agents** — `GET /api/agents`. Returns each agent's id, name, modelProvider, modelId, enabled, isMain, providerConfigured.
- **Create agent** — `POST /api/agents` with body `{ "name": "<slug>", "modelProvider": "<provider>", "modelId": "<model>" }`. Provider must be one already configured on this JClaw (call `GET /api/providers` first if unsure). The new agent starts enabled.
- **Get agent** — `GET /api/agents/{id}` for full agent details.
- **Update agent** — `PUT /api/agents/{id}` with body containing any of `name`, `modelProvider`, `modelId`, `enabled`, `thinkingMode`, `description`. Only fields you include are changed.

### MCP Servers (the primary motivator for this skill)

- **List MCP servers** — `GET /api/mcp-servers`. Shows existing entries with status (CONNECTED / DISCONNECTED / CONNECTING / ERROR) and tool count.
- **Add MCP server** — `POST /api/mcp-servers`. STDIO transport:
  `{ "name": "<unique-name>", "enabled": true, "transport": "STDIO", "command": "<binary>", "args": ["<arg1>", "<arg2>"], "env": { "KEY": "value" } }`.
  HTTP transport:
  `{ "name": "<unique-name>", "enabled": true, "transport": "HTTP", "url": "<https-url>", "headers": { "Authorization": "Bearer ..." } }`.
  When the user pastes a Claude-Desktop-style JSON config, map the keys: `command`→`command`, `args`→`args`, `env`→`env`, and assign a descriptive `name`. Default `enabled` to `true` unless the user says otherwise.
- **Update MCP server** — `PUT /api/mcp-servers/{id}` with the fields to change. The server reconnects automatically if transport or config changes.
- **Test MCP server** — `POST /api/mcp-servers/{id}/test`. Synchronous probe: returns `success`, `toolCount`, `toolNames`, or `message` on failure. Use this right after adding a server to confirm it boots cleanly.

### Tools (per-agent gating)

- **List tools registered globally** — `GET /api/tools`. Returns the tool catalog with names, categories, descriptions.
- **Enable/disable a tool on an agent** — `PUT /api/agents/{id}/tools/{name}` with body `{ "enabled": true }` or `{ "enabled": false }`. Tools are enabled by default for new agents; this endpoint records explicit disables (or re-enables a previously-disabled tool).
- **Toggle a whole tool group on an agent** — `PUT /api/agents/{id}/tool-groups/{group}` with `{ "enabled": <bool> }`. Useful when the user says "give this agent all File tools" — pass `group=Files`.

### Skills (per-agent gating)

- **List skills** — `GET /api/skills`. Returns all skills available in the global registry.
- **Install a skill on an agent** — `POST /api/agents/{id}/skills/{name}/copy` with an empty body. Copies the global registry skill into `workspace/<agent>/skills/<name>/`, snapshots the skill's shell-allowlist contributions into `AgentSkillAllowedTool`, runs a malware scan on the source, and enables the resulting `AgentSkillConfig`. **This is the right call when the user asks to "add" a skill to an agent that doesn't have it yet** — including a freshly-created agent. The PUT endpoint below cannot install; it only toggles already-installed skills.
- **Enable/disable an already-installed skill on an agent** — `PUT /api/agents/{id}/skills/{name}` with `{ "enabled": <bool> }`. The skill must already exist in the agent's workspace (i.e. `POST .../copy` was called previously, or the agent is `main` whose workspace ships with the skill). Calling this on a skill that's only in the global registry returns `400` with a pointer to `POST .../copy`. If the user asks to add a skill that isn't anywhere yet (not even in the global registry), route them to `skill-creator` instead of trying to create one through this API.

**Common composition** — "create agent X with skill Y":
1. `POST /api/agents` with the new agent's details → capture the returned `id`.
2. `POST /api/agents/{id}/skills/Y/copy` with `{}` → installs Y into the new agent's workspace AND enables it.
3. Skip the PUT — the copy step already enabled the skill. Only call PUT if the user later wants to disable Y without uninstalling it.

### Config

- **Read config** — `GET /api/config` lists all config rows (with sensitive values masked) or `GET /api/config/{key}` for a single key. Use this to inspect the current state before making a change so you can confirm what you'll be overwriting.
- **Write config** — `POST /api/config` with `{ "key": "<key>", "value": "<value>" }`. Confirm with the user before changing anything in the `provider.*`, `auth.*`, `chat.*`, or `shell.*` namespaces — those affect the whole installation.

## Off-limits

The following endpoints exist in JClaw's API but are **deliberately not callable through `jclaw_api`**, and the tool itself will refuse them:

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
