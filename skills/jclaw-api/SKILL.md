---
name: jclaw-api
description: >-
  Operate JClaw itself from chat — list/create/update agents, add or test MCP servers, change the model router's lists, read config. Use whenever the user asks to change JClaw's own configuration rather than perform an external task.
metadata:
  version: "1.1.11"
  author: Tarun Sukhani
  jclaw-icon: "⚙️"
  jclaw-tools: jclaw_api
---

# JClaw API

Use this skill when the user asks you to **change JClaw's own state** — adding an MCP server, creating an agent, etc. Only the `main` agent has the `jclaw_api` tool.

The `jclaw_api` tool exposes JClaw's own HTTP API, and the list of callable endpoints is **discovered at runtime** — there is no fixed catalog to drift. Do not invent endpoints, and never call one that `discover` does not list. An endpoint is callable only when its backend action is declared agent-reachable (`@AgentAccess` level `OPEN`, or `OWN_ONLY`, which reaches only your own agent's rows — `main` reaches every agent's); an undeclared action is operator-only. So a new endpoint appears in `discover` once the backend opens it, with no skill edit, and if `discover` does not list something, it is deliberately off-limits (see below). Access is decided by that backend declaration, never by this skill.

## How to call

1. **Discover** — when you're unsure which endpoint or payload to use, call `jclaw_api` with `action="discover"` (optionally pass a `filter` substring like `"agent"` or `"mcp"` to narrow the list). It returns the live catalog: verb, path, a one-line summary, and a body hint for mutating verbs.
2. **Invoke** — call `jclaw_api` with:
   - `method`: one of `GET`, `POST`, `PUT`, `PATCH`, `DELETE`
   - `path`: starts with `/api/` exactly as discover reports it — never include the host or scheme
   - `body`: a JSON object matching the operation's request shape (POST/PUT/PATCH only)
   - `query`: optional, only when an operation documents query parameters

The tool returns `HTTP <status>\n<body>` so you can read both. A 4xx response carries a JSON `code` and a `message` that tells you what to correct.

## Common operations (quick reference)

These staples don't need a discover round-trip first; run `action="discover"` for anything else.

- **Agents** — `GET /api/agents` lists your own agent, and `main` lists every agent; `POST /api/agents` body `name`, `modelProvider`, `modelId` (create: `main` only); `PUT /api/agents/{id}` (update, any agent's: `main` only; any other agent, even for its own row, gets 403 `agent_scope` and asks the user to make the change). An agent other than `main` reads only its own settings; another agent's get 403 `agent_scope`. A `modelProvider`/`modelId` you change must be a model the provider registers, or the update is refused: add it to the provider first.
- **Provider models** — `GET /api/providers/{name}/models` lists what a provider registers; `POST /api/providers/{name}/models` body `id` (and optionally `name`) adds one. Only `main` may add a model; any other agent asks the user, or `main`, to add it first.
- **MCP servers** (add, change or remove: `main` only; any other agent gets 403 `agent_scope` and asks the user) — `POST /api/mcp-servers` body `name`, `enabled`, `transport` (`STDIO` or `HTTP`), then `command`/`args`/`env` or `url`/`headers`; confirm it boots with `POST /api/mcp-servers/{id}/test`. `PUT /api/mcp-servers/{id}` changes only what it carries: send just the fields to change, and in `env`/`headers` just the keys to change, with `null` to remove one. A `url` on another scheme, host or port drops the saved `headers` unless the same request sends their values, and a saved header's mask sent with such a `url` is a 400 that names the header.
- **Config** — `GET /api/config` (all, masked) or `GET /api/config/{key}`.
- **Prompts** — a prompt you create is yours, and every row names its owner in `ownerAgentId` and `ownerAgentName` (both `null` on the operator's own). `main` lists, reads, edits and deletes every prompt; any other agent only the ones it created, and gets 403 `agent_scope` for the operator's or another agent's.
- **Your own rows** — an agent other than `main` lists and acknowledges only its own notifications, and reads only its own video jobs and its own cost, latency and compression rows, whichever `agentId` it asks for. `main` reads every agent's.
- **The instance** — its setup (config, logging levels, providers and their models, MCP servers, channel configurations, the model router's status, the coding-harness commands), its host and lifecycle status (metrics, circuit breakers, restart, upgrade and Java-runtime status), and the calls that dial out or spend (prompt generation and export, a provider probe or model discovery, the price refresh, the MCP server test, a printer's options) answer `main` only; any other agent gets 403 `agent_scope`.
- **Model router lists** — read a class's list with `GET /api/config/router.<class>.models`; replace it with `PUT /api/router/models/{class}` body `models` (`[{"provider": "…", "model": "…"}]`, tried in that order; `[]` clears it, and the class then uses the chat list). The classes are `chat`, `summarize`, `agentic`, `reasoning` and `coding`. To add, remove or reorder one model, read the list, change it, and send the whole list back. Only the `main` agent may write it (any other agent gets 403 `agent_scope`), and only a model registered under a configured provider is accepted (`GET /api/providers/{name}/models`). Every other router setting is operator-only.

## Lists, single reads and long responses

`jclaw_api` cuts any response at 50,000 characters, so the lists that hold long text return a short form of each row, and one item comes whole from its own read:

- **Messages** — `GET /api/conversations/{id}/messages` cuts a long field and names it in the row's `cut`; `GET /api/conversations/{id}/messages/{messageId}` returns that message whole.
- **Subagent runs** — the list's `outcome` is an excerpt that `outcomeTruncated` marks; `GET /api/subagent-runs/{id}` returns the whole outcome.
- **Prompts and tasks** — your list rows carry `contentPreview` or `descriptionPreview` (with `contentTruncated` or `descriptionTruncated`); `GET /api/prompts/{id}` or `GET /api/tasks/{id}` returns the whole text. Edit a prompt or task from its own read, never from a preview.
- **Transcript search** — `GET /api/task-runs/search` returns a `snippet` around the match; `GET /api/task-runs/{id}/messages` returns the run's messages.
- **Tools** — the catalogs list each tool's name, group and summary; `GET /api/tools/{name}` returns its description and actions.

When a response is still cut, its marker says how many characters it holds. For a list, narrow the request first: a smaller `limit`, a filter, or the one item's own read. When one item is itself longer than the cap, call the same GET again with the `charOffset` the marker names, and repeat until a part says it reaches the end. `charOffset` counts characters and works on a GET only; a list's row `offset` goes in `query`. `ccr_retrieve` cannot return a cut part.

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
- If JClaw returns 4xx, read the `message` field and either correct the request or report the constraint plainly. Never silently retry the same call.
