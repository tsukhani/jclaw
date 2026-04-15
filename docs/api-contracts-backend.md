# API Contracts — Backend

All endpoints defined in `conf/routes`. Unless noted, they are JSON-over-HTTP, hosted by the Play backend on `:9000`, and gated by `AuthCheck` (Play `@Before` interceptor that checks `session("authenticated") == "true"`). The frontend accesses them at same-origin `/api/*` in production and via Nitro dev-proxy in development.

**Auth exemptions:** `/api/auth/login`, `/api/auth/logout`, and `/api/webhooks/*` (webhooks verify their own signatures).

## Auth & status

| Method | Path | Controller | Notes |
|---|---|---|---|
| GET | `/api/status` | `ApiController.status` | Liveness probe. Returns `{status, application, mode, version}`. Public. |
| POST | `/api/auth/login` | `ApiAuthController.login` | Sets `session("authenticated")` cookie on success. |
| POST | `/api/auth/logout` | `ApiAuthController.logout` | Clears session. |

## Agents

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/agents` | List all agents. |
| POST | `/api/agents` | Create. |
| GET | `/api/agents/{id}` | Fetch one. |
| PUT | `/api/agents/{id}` | Update (name, model, thinkingMode, enabled). |
| DELETE | `/api/agents/{id}` | Delete. |
| GET | `/api/agents/{id}/prompt-breakdown` | Returns assembled system-prompt sections (for debugging). |
| GET | `/api/agents/{id}/shell/effective-allowlist` | Computes: `global shell.allowlist ∪ agent_skill_allowed_tool rows`. |
| GET | `/api/agents/{id}/workspace/{filename}` | Read workspace file. |
| GET | `/api/agents/{id}/files/{+filePath}` | Serve workspace file binary. |
| PUT | `/api/agents/{id}/workspace/{filename}` | Write workspace file. |
| GET | `/api/agents/{id}/tools` | Per-agent tool enable state. |
| PUT | `/api/agents/{id}/tools/{name}` | Toggle tool for agent. |

## Chat (dispatch)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/chat/send` | Synchronous send → returns final assistant content. |
| POST | `/api/chat/stream` | SSE stream: tokens, reasoning, status, complete, error. |
| POST | `/api/chat/upload` | Upload attachments; returns URLs referenceable in subsequent `send`. |

### Request body (chat)

```json
{
  "agentId": 12,
  "message": "Draft a release note.",
  "conversationId": 345  // optional; null starts a new web conversation
}
```

### SSE events (`/api/chat/stream`)

- `init` — `{conversationId}`
- `token` — partial content string
- `reasoning` — reasoning-trace token (when `thinkingMode` set)
- `status` — e.g. tool invocation notice
- `complete` — final assistant message (full)
- `error` — exception message

Client disconnects are detected in `AgentRunner.checkCancelled` via an `AtomicBoolean` and log `"Stream cancelled by client disconnect"`.

## Conversations

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/conversations` | List (supports channel filter). |
| GET | `/api/conversations/channels` | Distinct channel types with counts. |
| GET | `/api/conversations/{id}/messages` | Message history (ASC by `createdAt`). |
| GET | `/api/conversations/{id}/queue` | `ConversationQueue` state (processing/pending/mode). |
| POST | `/api/conversations/{id}/generate-title` | LLM-generated title. |
| DELETE | `/api/conversations/{id}` | Single delete. |
| DELETE | `/api/conversations` | Batch delete (body: `{ids:[…]}`). |

## Tasks

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/tasks` | List tasks (optionally filter by status). |
| POST | `/api/tasks/{id}/cancel` | Set status=CANCELLED. |
| POST | `/api/tasks/{id}/retry` | Reset retryCount & nextRunAt. |

`TaskPollerJob @Every("30s")` drains `PENDING` tasks due by `next_run_at`, executes on a `newVirtualThreadPerTaskExecutor`, re-schedules CRON tasks on completion, and applies exponential backoff `30s * 2^(retryCount-1)` capped at ~12 days (`MAX_BACKOFF_SHIFT=20`). Task creation for tasks with `agent != null` runs the task as a prompt through `AgentRunner.run` against a synthetic `"task"` channel.

## Bindings (channel → agent routing)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/bindings` | List. |
| POST | `/api/bindings` | Create (channelType + optional peerId + agentId + priority). |
| PUT | `/api/bindings/{id}` | Update. |
| DELETE | `/api/bindings/{id}` | Delete. |

Routing order in `AgentRouter.resolve`: (1) exact peer match, (2) channel-wide (peerId=NULL), (3) main agent (`name="main"`).

## Skills

Read-only from the HTTP API — skills are authored only via the skill-creator skill (using the filesystem tool) or promoted from an agent workspace. No `POST /api/skills`, no PUT for file content.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/skills` | List global skills. |
| POST | `/api/skills/promote` | Promote an agent-workspace skill into the global registry. |
| GET | `/api/skills/{name}` | Skill metadata + file listing. |
| PUT | `/api/skills/{name}/rename` | Rename. |
| GET | `/api/skills/{name}/files/{+filePath}` | Read file content. |
| GET | `/api/skills/{name}/files` | List files. |
| DELETE | `/api/skills/{name}` | Un-promote. |
| GET | `/api/agents/{id}/skills` | Agent's enabled skills. |
| PUT | `/api/agents/{id}/skills/{name}` | Toggle. |
| GET | `/api/agents/{id}/skills/{name}/files/{+filePath}` | Read skill file (agent-scoped). |
| DELETE | `/api/agents/{id}/skills/{name}/delete` | Remove skill from agent workspace. |
| POST | `/api/agents/{id}/skills/{name}/copy` | Copy global skill into agent workspace. |

## Tools

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/tools` | Tool catalog (name, description, schema). |

## Events (SSE)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/events` | `text/event-stream` — broadcast channel. Frontend singleton `EventSource`. |

## Providers

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/providers/{name}/discover-models` | Call provider's model-list endpoint, cache into `ProviderRegistry`. |

## Config

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/config` | All key/value overrides. Also used by `useAuth.checkAuth` as a session probe. |
| POST | `/api/config` | Upsert batch. |
| GET | `/api/config/{key}` | Single value. |
| DELETE | `/api/config/{key}` | Revert to default. |

## Channels

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/channels` | List all `ChannelConfig` rows. |
| GET | `/api/channels/{channelType}` | Fetch one. |
| PUT | `/api/channels/{channelType}` | Upsert config JSON + enabled flag. |

## Webhooks (auth-exempt)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/webhooks/telegram/{secret}` | Path-secret verified. |
| POST | `/api/webhooks/slack` | Slack signature verified in-controller. |
| GET | `/api/webhooks/whatsapp` | WA verify handshake. |
| POST | `/api/webhooks/whatsapp` | WA webhook. |

All webhook inbounds resolve an agent through `AgentRouter.resolve(channelType, peerId)` and feed the message into `AgentRunner`.

## Logs & metrics

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/logs` | Paged `EventLog` query (filters on `category`, `level`, agent). |
| GET | `/api/metrics/latency` | In-memory `HdrHistogram` latency per segment (JVM-local, reset on restart). |
| DELETE | `/api/metrics/latency` | Reset histograms. |
| POST | `/api/metrics/loadtest` | Drive `LoadTestHarness`. |
| DELETE | `/api/metrics/loadtest` | Stop harness. |

## SPA serving

| Method | Path | Purpose |
|---|---|---|
| GET | `/_nuxt/` | Static dir `public/spa/_nuxt` (Nuxt build assets). |
| GET | `/{*path}` (non-api/public/nuxt) | `Application.spa` — serves `public/spa/index.html` for all frontend routes. |

## Authentication contract

- Session-cookie based, set by `/api/auth/login` (body: `{username, password}`).
- `AuthCheck.@Before` guards every protected controller; webhooks whitelist themselves by path prefix.
- Unauthenticated responses are `401` with `{"error":"Authentication required"}`. Failed login returns `401` with `{"error":"Invalid credentials"}`. Genuine authorization failures (e.g. trying to disable a system tool, deleting the built-in skill-creator skill, rejected config writes) still return `403`.
