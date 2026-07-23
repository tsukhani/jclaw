# API Contracts — Backend

All endpoints are defined in `conf/routes`. Unless noted, they are JSON-over-HTTP, hosted by the Play backend on `:9000` (and `:9443` when HTTPS is enabled), and gated by `AuthCheck` (Play `@Before` interceptor: valid session cookie, or a Bearer `ApiToken` for the in-process `jclaw_api` tool). The frontend accesses them at same-origin `/api/*` in production and via the Nitro dev-proxy in development.

**Auth exemptions:** the auth endpoints, `/api/status`, and `/api/webhooks/*` (webhooks verify their own signatures). Unknown `/api/*` paths return a clean 404 JSON via `ApiNotFoundController` (so scanners don't trigger an `ActionNotFound` stack trace).

## Auth & status

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/status` | Liveness probe (`{status, application, mode, version}`). Public. |
| GET | `/api/auth/status` | Session/auth state probe (used by the client auth guard). |
| POST | `/api/auth/setup` | First-boot admin password setup. |
| POST | `/api/auth/login` | Sets the `PLAY_SESSION` cookie on success. |
| POST | `/api/auth/logout` | Clears session. |
| POST | `/api/auth/reset-password` | Reset the admin password. |
| GET | `/api/onboarding/tour-status` · POST `/api/onboarding/tour-progress` | Guided-tour state. |

## Agents

| Method | Path | Purpose |
|---|---|---|
| GET / POST | `/api/agents` | List / create. |
| GET / PUT / DELETE | `/api/agents/{id}` | Fetch / update / delete. |
| GET | `/api/agents/{id}/prompt-breakdown` | Assembled system-prompt sections (debugging). |
| GET | `/api/agents/{id}/shell/effective-allowlist` | `global shell.allowlist ∪ agent_skill_allowed_tool`. |
| GET / PUT | `/api/agents/{id}/workspace/{filename}` | Read / write a workspace file. |
| GET | `/api/agents/{id}/files/{+filePath}` | Serve a workspace file binary. |
| GET / PUT | `/api/agents/{id}/tools`, `/tools/{name}`, `/tool-groups/{group}` | Per-agent tool enablement. |
| GET / PUT / DELETE / POST | `/api/agents/{id}/skills…` | Per-agent skill enablement, file reads, copy, delete. |

## Chat

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/chat/send` | Synchronous send → final assistant content. |
| POST | `/api/chat/stream` | SSE stream: `init`, `token`, `reasoning`, `status`, `complete`, `error`. |
| POST | `/api/chat/upload` | Upload attachments, referenceable in a subsequent `send`. |

### Request body (chat)

```json
{ "agentId": 12, "message": "Draft a release note.", "conversationId": 345 }
```

`conversationId` is optional; null starts a new web conversation. Client disconnects are detected by `CancellationManager` (polled between tool rounds and inside `LlmProvider` streaming).

## Conversations

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/conversations` · `/channels` | List (channel filter) · distinct channels. |
| GET | `/api/conversations/{id}` · `/messages` · `/queue` | Conversation · message history (ASC) · `ConversationQueue` state. |
| DELETE | `/api/conversations/{id}` · `/api/conversations` · `/messages/{mid}` | Single / batch / single-message delete. |
| PUT / DELETE | `/api/conversations/{id}/model-override` | Set / clear per-conversation model override. |

## Tasks, notifications & reminders

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/tasks` · `/stats` · `/{id}/runs` · `/{id}/delivery-advisory` | List / stats / run history / delivery advisory. |
| POST | `/api/tasks` · `/{id}/run` · `/cancel` · `/retry` · `/pause` · `/resume` · `/reenable` | Create + lifecycle actions. |
| PATCH / DELETE | `/api/tasks/{id}` | Update / delete. |
| GET / POST | `/api/task-runs/search` · `/recent` · `/{id}/messages` · `/{runId}/cancel` · `/reset` | Transcript search (Lucene), recent runs, run transcript, cancel, stats reset. |
| GET | `/api/timezones` | IANA zone list for scheduling. |
| GET / POST / DELETE | `/api/notifications` · `/{id}/ack` · `/{id}` | Reminder/notification surface. |

Task **execution is owned by db-scheduler** (atomic row-claim, retries, heartbeat recovery) — not a custom `@Every` poller. A task with `agent != null` runs its description as a prompt through `AgentRunner` against a synthetic `"task"` channel; reminders are tasks with `auto_delete_on_complete`.

## Memory

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/memories` | List agent-scoped memories (filter / paginate). |
| PUT | `/api/memories/{memoryId}` | Update a memory row. |
| DELETE | `/api/memories` · `/{memoryId}` | Bulk delete · single delete. |

Backed by `JpaMemoryStore` — agent-scoped rows with hybrid keyword + vector recall (see [Backend Architecture](architecture-backend.md) → Memory).

## Prompts

| Method | Path | Purpose |
|---|---|---|
| GET / POST | `/api/prompts` | List (newest-edited first) / create. |
| PUT / DELETE | `/api/prompts/{id}` | Update (partial) / delete. |
| GET | `/api/prompts/categories` | Fixed category taxonomy (value + label). |
| POST | `/api/prompts/generate` | LLM-draft a prompt (title/category/content/tags) from a description; does not save (503 if no provider). |
| GET / POST | `/api/prompts/export` · `/api/prompts/import` | Portable JSON export · import (mode: `merge` \| `replace`). |

Prompts Library (JCLAW-813) — operator-level saved/reusable prompts (`ApiPromptsController` over the `prompt` table). "Run" hands `content` to the chat composer via `/chat?compose=`.

## Bindings, channels & webhooks

| Method | Path | Purpose |
|---|---|---|
| GET / POST / PUT / DELETE | `/api/bindings…` | Generic channel→agent routing bindings. |
| GET | `/api/channels` · `/active` · `/{channelType}` | Channel list · dashboard aggregate · one config. |
| PUT | `/api/channels/{channelType}` | Upsert channel config JSON + enabled. |
| GET / POST / PUT / DELETE | `/api/channels/{telegram,slack,whatsapp}/bindings…` | Per-transport binding CRUD + `/test`. |
| GET | `/api/channels/whatsapp/bindings/{id}/qr` | WhatsApp-Web (Cobalt) QR pairing status. |
| POST | `/api/webhooks/telegram/{bindingId}/{secret}` | Telegram webhook (path-secret verified). |
| POST | `/api/webhooks/slack/{bindingId}` · `/interactive` | Slack event + interactive (signature verified). |
| GET / POST | `/api/webhooks/whatsapp` | Cloud-API verify handshake (GET) + webhook (POST). |

Routing order in `AgentRouter.resolve`: (1) exact peer match → (2) channel-wide (peerId=NULL) → (3) main agent. Webhook inbounds flow `WebhookXController` → signature verify → `AgentRouter.resolve` → `AgentRunner` (queued via `ConversationQueue`) → matching `channels.Channel` adapter.

## Skills, tools & MCP

Skill authoring is **read-only** over HTTP — skills are created/updated via the skill-creator skill (filesystem tool) or promoted from an agent workspace.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/skills` · `/{name}` · `/{name}/files` · `/catalogs` · `/catalog/search` | List / metadata / files / importable catalogs. |
| POST | `/api/skills/promote` · `/catalog/refresh` · `/catalog/import` | Promote / refresh / import. |
| PUT / DELETE | `/api/skills/{name}/rename` · `/api/skills/{name}` | Rename / un-promote. |
| GET | `/api/tools` · `/api/tools/meta` | Tool catalog + UI metadata. |
| GET / POST / PUT / DELETE | `/api/mcp-servers…` · `/{id}/test` | MCP server CRUD + connection test. |
| GET / POST / DELETE | `/api/subagent-runs` · `/{id}/kill` · `/{id}` | Subagent run monitor. |

## Providers, models & media

| Method | Path | Purpose |
|---|---|---|
| GET / POST | `/api/providers` · `/{name}/discover-models` · `/reachable` · `/models` · `/video-models` · `/refresh-prices` | Provider catalog, model discovery, reachability, pricing. |
| GET | `/api/ocr/status` | OCR backend availability. |
| GET / POST | `/api/transcription/state` · `/models/{id}/download` | Whisper model state + download. |
| GET / POST | `/api/imagegen/{local/state,local/pull,models,progress,capability,capability/probe}` | Local + cloud image generation. |
| GET / POST | `/api/videogen/{jobs,jobs/recent,models,capability,capability/probe}` | Video generation jobs + capability. |
| GET / DELETE | `/api/attachments/{uuid}` | Download / delete a persisted attachment. |

## Config, logging, tailscale

| Method | Path | Purpose |
|---|---|---|
| GET / POST | `/api/config` · `/{key}` · DELETE `/{key}` | Runtime Config key/value (Settings UI). |
| GET / POST / DELETE | `/api/logging/levels…` | Per-logger runtime level overrides. |
| GET / POST | `/api/tailscale` | Tailscale Funnel public-URL toggle (for webhook channels). |

## Events, logs & metrics

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/events` | `text/event-stream` — broadcast bus (frontend singleton `EventSource`). |
| GET | `/api/logs` | Paged `EventLog` query (category/level/agent filters). |
| GET / DELETE | `/api/metrics/latency` · `/latency/rows` | In-memory HdrHistogram latency (JVM-local, reset on restart) + windowed rows. |
| GET | `/api/metrics/cost` · `/compression` | Durable cost (from `Message.usageJson`) + compression telemetry. |
| POST / DELETE | `/api/metrics/loadtest` · `/loadtest/data` | Drive / stop / clean the in-process load-test harness. |

## SPA serving

| Method | Path | Purpose |
|---|---|---|
| GET | `/_nuxt/` | Static dir `public/spa/_nuxt` (Nuxt build assets). |
| GET | `/{*path}` (non-api/public/nuxt) | `Application.spa` — serves `public/spa/index.html` for all frontend routes. |

## Authentication contract

- Session-cookie based (`PLAY_SESSION`), set by `POST /api/auth/login`; the in-process `jclaw_api` tool uses a Bearer `ApiToken` instead.
- `AuthCheck @Before` guards protected controllers; webhooks and the auth/status endpoints exempt themselves.
- Unauthenticated → **401** (`{"error":"Authentication required"}`); failed login → **401**; genuine authorization failures (e.g. disabling a system tool, deleting the built-in skill-creator skill) → **403**.
