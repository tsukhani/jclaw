# API Contracts — Backend

Every HTTP surface the Play backend exposes, from `conf/routes` (the single source of truth) and the controllers in `app/controllers/`. **287 method-specific routes across 62 controllers** (130 GET, 96 POST, 38 DELETE, 21 PUT, 1 PATCH, 1 WS), plus two `*` wildcard fallbacks.

Play 1.x maps each route to a `public static void` method on a controller; the method signature *is* the request contract. Path params in `{braces}` bind by name; everything else arrives as query or form params. Responses are JSON via `renderJSON` unless noted. The backend serves `:9000` (and `:9443` when HTTPS is enabled); the frontend reaches it same-origin at `/api/*` in production and through the Nitro dev-proxy in development.

Two `*` (any-method) routes sit at the end of the file and matter for how misses behave:

| Route | Target | Effect |
|---|---|---|
| `* /api/{<.*>path}` | `ApiNotFoundController.handle` | Unknown `/api/*` paths return clean 404 JSON, so scanners never trigger an `ActionNotFound` stack trace |
| `* /{controller}/{action}` | `{controller}.{action}` | Play 1.x's classic catch-all convention |

## Authentication

Three gates, by route family:

| Gate | Applied to | Mechanism |
|---|---|---|
| `AuthCheck` (`@With`) | 50 controllers — effectively every `/api/**` business endpoint | **Bearer token** (`Authorization: Bearer <plaintext>`, resolved against `api_token`; the token owner becomes the session username) **or** Play's stateless **session cookie**. Bearer wins: a client sending a token expects it to be the identity, so a stale cookie is never silently honoured. The session bit alone is insufficient — Play 1.x sessions are client-held. |
| `LoadtestAuthCheck` | `ApiMetricsController` loadtest actions, `ApiEvalsController` | Loopback-only **plus** an `X-Loadtest-Auth` header — keeps model-spending and data-mutating harness endpoints off the network. |
| `WebhookIngressGate` + per-provider signature | `WebhookTelegram/Slack/WhatsApp` | Inbound webhooks authenticate by provider signature or secret token, not operator session. |

Open by design: `Api.status` (liveness/probe, used by the Docker healthcheck), the auth endpoints themselves, and the SPA/static routes (`Application.*`).

> **`/@api/*` is not an app route.** The play1 fork's OpenApiPlugin serves the OpenAPI spec and UI *outside* the controller stack, so `AuthCheck` never fires on it. `%prod.openapi.publicSpec=false` gates it off entirely in PROD; DEV keeps it open (JCLAW-810).

## Core & system

| Method | Path | Action |
|---|---|---|
| GET | `/api/status` | `Api.status` — `{status, application, mode, applicationVersion, frameworkVersion, expectedFrameworkVersion}` plus `spaBuildId`, which diagnoses a stale SPA behind a proxy |
| GET | `/api/workspace/stats` | `Api.workspaceStats` |
| GET · POST | `/api/system/restart` | `ApiSystem.restartPreflight` / `.restart` |
| GET · POST | `/api/tailscale` | `ApiTailscale.status` / `.toggle` |
| GET | `/api/logs` | `ApiLogs.list` |
| GET · POST | `/api/logging/levels` | `ApiLogging.list` / `.save` |
| DELETE | `/api/logging/levels/{logger}` | `ApiLogging.delete` — runtime per-logger level override |
| GET | `/api/events` | `ApiEvents.stream` — **SSE**, the single server-push channel |
| GET | `/api/ocr/status` | `ApiOcr.status` — whether the `tesseract` binary is present |

## Auth & onboarding

| Method | Path | Action |
|---|---|---|
| GET | `/api/auth/status` | `ApiAuth.status` — drives the SPA's `/login` vs `/setup-password` routing |
| POST | `/api/auth/setup` | `ApiAuth.setup` — first-boot admin password |
| POST | `/api/auth/login` · `/logout` · `/reset-password` | `ApiAuth.*`; login is rate-limited by `LoginRateLimiter` and sets `PLAY_SESSION` |
| GET · POST | `/api/onboarding/tour-status` · `/tour-progress` | `ApiOnboarding.status` / `.recordProgress` |

## Configuration

| Method | Path | Action |
|---|---|---|
| GET · POST | `/api/config` | `ApiConfig.list` / `.save` |
| GET · DELETE | `/api/config/{key}` | `ApiConfig.get` / `.delete` |

The **config store is the live source for operator settings** — not `conf/application.conf`, which holds boot-time and framework configuration. Keys are validated server-side on write; e.g. memory embedding and rerank providers are rejected unless local, so the constraint holds even for a direct `POST /api/config`.

## Agents

| Method | Path | Action |
|---|---|---|
| GET · POST | `/api/agents` | `ApiAgents.list` / `.create` |
| GET · PUT · DELETE | `/api/agents/{id}` | `.get` / `.update` / `.delete` |
| GET | `/api/agents/{id}/prompt-breakdown` | `.promptBreakdown` — per-section token accounting of the assembled system prompt |
| GET | `/api/agents/{id}/prompt-text` | `.promptText` |
| GET | `/api/agents/{id}/shell/effective-allowlist` | `.effectiveShellAllowlist` — global `shell.allowlist` merged with per-agent bypass |
| GET · PUT | `/api/agents/{id}/workspace/{filename}` | `.getWorkspaceFile` / `.saveWorkspaceFile` — the Standing Orders files (`SOUL.md`, `IDENTITY.md`, `AGENT.md`, …) |
| GET | `/api/agents/{id}/files/{filePath}` | `.serveWorkspaceFile` — serves an attachment back with the right Content-Type |

## Chat, conversations & attachments

| Method | Path | Action |
|---|---|---|
| POST | `/api/chat/send` | `ApiChat.send` — synchronous turn → final assistant content |
| POST | `/api/chat/stream` | `ApiChat.streamChat` — SSE stream |
| POST | `/api/chat/upload` | `ApiChat.uploadChatFiles` — multipart; size caps are per MIME bucket |
| GET · DELETE | `/api/attachments/{uuid}` | `ApiAttachments.download` / `.deleteAttachment` |
| GET | `/api/conversations` | `ApiConversations.listConversations` |
| GET | `/api/conversations/channels` | `.listConversationChannels` |
| GET · DELETE | `/api/conversations/{id}` | `.getConversation` / `.deleteConversation` |
| DELETE | `/api/conversations` | `.deleteConversations` (bulk) |
| GET | `/api/conversations/{id}/messages` | `.getMessages` |
| DELETE | `/api/conversations/{id}/messages/{mid}` | `.deleteMessage` |
| GET | `/api/conversations/{id}/queue` | `.getQueueStatus` |
| PUT · DELETE | `/api/conversations/{id}/model-override` | `.setModelOverride` / `.clearModelOverride` |

**Request body (chat):**

```json
{ "agentId": 12, "message": "Draft a release note.", "conversationId": 345 }
```

**Streaming event contract.** `/api/chat/stream` emits eight event types, consumed by `frontend/composables/useChatStream.ts`:

| Event | Meaning |
|---|---|
| `init` | Stream opened; carries conversation/message identifiers |
| `token` | Incremental assistant content |
| `reasoning` | Incremental thinking-mode content, rendered separately from `token` |
| `tool_call` | A tool invocation began |
| `status` | Progress line (e.g. a long-running tool) |
| `queued` | The turn is waiting behind another on the same conversation |
| `complete` | Terminal success |
| `error` | Terminal failure |

## Tasks, runs & scheduling

Tasks are user-facing scheduled work executed by **db-scheduler**; these endpoints manage definitions and inspect runs.

| Method | Path | Action |
|---|---|---|
| GET · POST | `/api/tasks` | `ApiTasks.list` / `.create` |
| PATCH · DELETE | `/api/tasks/{id}` | `.update` / `.delete` |
| POST | `/api/tasks/{id}/`&#8203;`cancel` · `retry` · `run` · `pause` · `resume` · `reenable` | `ApiTasks.*` — full lifecycle control |
| GET | `/api/tasks/stats` | `ApiTaskStats.stats` |
| POST | `/api/task-runs/reset` | `ApiTaskStats.resetStats` |
| GET | `/api/tasks/{id}/runs` | `ApiTaskRuns.runs` |
| GET | `/api/task-runs/recent` | `.recentRuns` |
| GET | `/api/task-runs/{id}/messages` | `.runMessages` — the run transcript |
| POST | `/api/task-runs/{runId}/cancel` | `.cancelRun` |
| GET | `/api/task-runs/search` | `ApiTaskSearch.searchTranscripts` — Lucene-backed |
| GET | `/api/tasks/{id}/delivery-advisory` | `ApiSchedulingMeta.deliveryAdvisory` |
| GET | `/api/timezones` | `ApiSchedulingMeta.timezones` — IANA list for the scheduler UI |

## Memory

| Method | Path | Action |
|---|---|---|
| GET | `/api/memories` | `ApiMemory.list` |
| POST | `/api/memories/recall` | `.recall` — hybrid keyword + vector, blended by reciprocal-rank fusion |
| PUT · DELETE | `/api/memories/{memoryId}` | `.update` / `.delete` |
| DELETE | `/api/memories` | `.bulkDelete` — filtered bulk delete |
| GET · POST | `/api/memories/reembed` | `.reembedStatus` / `.reembedStart` — rewrite the corpus against a new embedding model |
| POST | `/api/memories/evals/generate` · `/run` | `.evalGenerate` / `.evalRun` — memory-retrieval evaluation |
| GET · POST | `/api/agents/{agentId}/core-migration` | `.coreMigrationStatus` / `.coreMigrationStart` |

Memory rows are agent-scoped; recall never crosses agents.

## Providers & models

| Method | Path | Action |
|---|---|---|
| GET | `/api/providers` | `ApiProviders.list` |
| GET · POST | `/api/providers/{name}/models` | `.models` / `.addModel` |
| POST | `/api/providers/{name}/discover-models` | `.discoverModels` — live catalog pull |
| GET | `/api/providers/{name}/reachable` | `.reachable` |
| GET | `/api/providers/{name}/embedding-models` | `.embeddingModels` — deliberately **not** `discover-models`, which filters embedding and TTS models out |
| POST | `/api/providers/{name}/embedding-probe` | `.embeddingProbe` — confirms a model embeds and records its dimension; save is gated on it |
| GET | `/api/providers/{name}/video-models` | `.videoModels` |
| POST | `/api/providers/refresh-prices` | `.refreshPrices` — opt-in fetch of community price data |

## Tools, skills & MCP

| Method | Path | Action |
|---|---|---|
| GET | `/api/tools` · `/api/tools/meta` | `ApiTools.list` / `.meta` |
| GET · PUT | `/api/agents/{id}/tools` · `/tools/{name}` | `.listForAgent` / `.updateForAgent` |
| PUT | `/api/agents/{id}/tool-groups/{group}` | `.updateGroupForAgent` |
| GET | `/api/skills` · `/api/skills/{name}` | `ApiSkills.list` / `.get` |
| GET | `/api/skills/catalogs` · `/catalog/search` | `.catalogs` / `.catalogSearch` |
| POST | `/api/skills/catalog/refresh` · `/catalog/import` | `.catalogRefresh` / `.catalogImport` |
| POST | `/api/skills/promote` | `.promote` — LLM sanitization pass before a skill goes global |
| PUT · DELETE | `/api/skills/{name}/rename` · `/api/skills/{name}` | `.rename` / `.delete` |
| GET | `/api/skills/{name}/files[/{filePath}]` | `.listFiles` / `.readFile` |
| GET · PUT | `/api/agents/{id}/skills` · `/skills/{name}` | `.listForAgent` / `.updateForAgent` |
| GET | `/api/agents/{id}/skills/{name}/files[/{filePath}]` | `.listAgentSkillFiles` / `.readAgentSkillFile` |
| POST · DELETE | `/api/agents/{id}/skills/{name}/copy` · `/delete` | `.copyToAgent` / `.deleteAgentSkill` |
| GET · POST | `/api/mcp-servers` | `ApiMcpServers.list` / `.create` |
| GET · PUT · DELETE | `/api/mcp-servers/{id}` | `.get` / `.update` / `.delete` |
| POST | `/api/mcp-servers/{id}/test` | `.test` — connection probe |

Per-agent tool config is enforced **at execution**, not only in the schema presented to the model: `ToolRegistry.execute`/`executeRich` guards native tools and MCP tools gate through `AgentSkillAllowedTool` (JCLAW-883). A model that guesses a real tool name cannot bypass operator configuration.

## Subagents

| Method | Path | Action |
|---|---|---|
| GET | `/api/subagent-runs` | `ApiSubagentRuns.list` |
| GET | `/api/subagent-runs/{id}/steps` | `.steps` |
| POST | `/api/subagent-runs/{id}/kill` | `.kill` |
| DELETE | `/api/subagent-runs` · `/{id}` | `.deleteBulk` / `.delete` |
| GET · POST · DELETE | `/api/subagents/acp-harnesses` | `ApiAcpHarness.list` / `.add` / `.remove` — external coding harnesses over ACP |

## Channels & bindings

A **binding** attaches an agent to an external chat surface. Generic binding endpoints sit alongside per-channel ones.

| Method | Path | Action |
|---|---|---|
| GET · POST | `/api/bindings` | `ApiBindings.list` / `.create` |
| PUT · DELETE | `/api/bindings/{id}` | `.update` / `.delete` |
| GET | `/api/channels` · `/api/channels/active` | `ApiChannels.list` / `.active` |
| GET · PUT | `/api/channels/{channelType}` | `.get` / `.save` |
| GET · POST | `/api/channels/telegram/bindings` | `ApiTelegramBindings.list` / `.create` |
| PUT · DELETE · POST | `…/telegram/bindings/{id}[/test]` | `.update` / `.delete` / `.test` |
| GET · POST | `/api/channels/slack/bindings` | `ApiSlackBindings.list` / `.create` |
| PUT · DELETE · POST | `…/slack/bindings/{id}[/test]` | `.update` / `.delete` / `.test` |
| GET · POST | `/api/channels/whatsapp/bindings` | `ApiWhatsAppBindings.list` / `.create` |
| PUT · DELETE | `…/whatsapp/bindings/{id}` | `.update` / `.delete` |
| GET | `…/whatsapp/bindings/{id}/qr` | `WhatsAppQr.status` — pairing QR for the WhatsApp-Web path |

### Inbound webhooks

| Method | Path | Action |
|---|---|---|
| POST | `/api/webhooks/telegram/{bindingId}` | `WebhookTelegram.webhook` — authenticated by the `X-Telegram-Bot-Api-Secret-Token` header, per-binding rate limited |
| POST | `/api/webhooks/slack/{bindingId}` · `/interactive` | `WebhookSlack.webhook` / `.interactive` — request-signature verified against the binding secret |
| GET · POST | `/api/webhooks/whatsapp` | `WebhookWhatsApp.verify` / `.webhook` — GET is the Cloud API verification handshake |

## Media

### Transcription & diarization

| Method | Path | Action |
|---|---|---|
| GET | `/api/transcription/state` | `ApiTranscription.state` |
| POST | `/api/transcription/models/{id}/download` | `.download` — ASR weight provisioning with live progress |
| GET | `/api/transcription/diarization/models` | `.diarizationModels` — on-device diarizer + SER weight status (`role` is `diarizer` or `emotion`) |
| POST | `/api/transcription/diarization/download` | `.diarizationDownload` |

### Speech (TTS) & voice

| Method | Path | Action |
|---|---|---|
| GET | `/api/tts/state` | `ApiTts.state` |
| POST | `/api/tts/synthesize` · `/stream` | `.synthesize` / `.stream` |
| POST | `/api/tts/models/{id}/download` | `.download` |
| POST · DELETE | `/api/tts/reference-voice` | `.uploadReferenceVoice` / `.clearReferenceVoice` — zero-shot voice-cloning input |
| **WS** | `/api/voice` | `Voice.socket` — the duplex voice-mode WebSocket |

### Image generation

| Method | Path | Action |
|---|---|---|
| GET | `/api/imagegen/local/state` · `/api/imagegen/models` · `/progress` | `ApiImagegen.state` / `.models` / `.progress` |
| POST | `/api/imagegen/local/pull` | `.pull` — weight download |
| GET · POST | `/api/imagegen/capability[/probe]` | `.capability` / `.probeCapability` — free-VRAM tiering |

### Video generation

| Method | Path | Action |
|---|---|---|
| GET | `/api/videogen/jobs` · `/jobs/recent` | `ApiVideogen.jobs` / `.recent` |
| GET | `/api/videogen/models` | `.models` |
| GET · POST | `/api/videogen/capability[/probe]` | `.capability` / `.probeCapability` |

Video generation is **asynchronous** — jobs persist in `video_generation_job` and are polled — unlike image generation, which returns in-band.

## Printers

| Method | Path | Action |
|---|---|---|
| GET | `/api/printers` | `ApiPrinters.discover` — mDNS browse of the local network |
| GET | `/api/printers/options` | `.options` — job options read from the selected device, never a built-in list |
| GET · PUT | `/api/printers/default` | `.getDefault` / `.saveDefault` |
| GET | `/api/printers/default/status` | `.defaultStatus` — reachability probe; a saved default outlives the DHCP lease it was saved under |

## Prompts

| Method | Path | Action |
|---|---|---|
| GET · POST | `/api/prompts` | `ApiPrompts.list` / `.create` |
| PUT · DELETE | `/api/prompts/{id}` | `.update` / `.delete` |
| GET | `/api/prompts/categories` | `.categories` |
| POST | `/api/prompts/generate` | `.generate` — LLM-assisted prompt authoring |
| GET · POST | `/api/prompts/export` · `/import` | `.export` / `.importPrompts` |

## Apps

Hosted static mini-apps served from `public/apps/<slug>/`.

| Method | Path | Action |
|---|---|---|
| GET | `/api/apps` | `ApiApps.list` — filesystem scan |
| DELETE | `/api/apps/{slug}` | `ApiApps.delete` |
| POST | `/api/apps/{slug}/invoke` | `ApiAppInvoke.invoke` — scoped agent invocation from an app, behind `AppOriginGate` |
| GET | `/api/apps/{slug}/files/{uuid}` | `ApiAppInvoke.file` |

## Notifications

| Method | Path | Action |
|---|---|---|
| GET | `/api/notifications` | `ApiNotifications.list` — `status=unread` returns only un-acknowledged rows |
| POST | `/api/notifications/{id}/ack` | `.ack` |
| DELETE | `/api/notifications/{id}` | `.delete` |

## Metrics & harnesses

Behind `LoadtestAuthCheck` where they spend money or mutate data.

| Method | Path | Action |
|---|---|---|
| GET · DELETE | `/api/metrics/latency` | `ApiMetrics.latency` / `.resetLatency` |
| GET · DELETE | `/api/metrics/latency/rows` | `.latencyRows` / `.clearLatencyRows` |
| GET | `/api/metrics/cost` | `.cost` |
| GET · DELETE | `/api/metrics/compression` | `.compression` / `.resetCompression` |
| POST · DELETE | `/api/metrics/loadtest` | `.loadtest` / `.stopLoadtest` |
| DELETE | `/api/metrics/loadtest/data` | `.cleanLoadtest` |
| POST | `/api/evals/capture` | `ApiEvals.capture` — drives real agent turns for an eval sweep. Requires an explicit `--agent`; leaves no conversation, history or memories behind and is excluded from the Chat Performance histograms. Tool **side effects are not isolated** — a case provoking `task_manager` creates a real scheduled task, which is why sweeps target `__evaltest__`. |

## SPA & static serving

| Method | Path | Action |
|---|---|---|
| GET | `/` | `Application.index` |
| GET | `/public/` | `staticDir:public` |
| GET | `/_nuxt/{path}` | `Application.nuxtAsset` — content-hashed chunks, `immutable` cache headers |
| GET | `/apps/{path}` | `Application.appAsset` |
| GET | `/{path}` | `Application.spa` — the SPA shell, `no-cache` so it always revalidates |

The catch-all carries a negative lookahead excluding `api/`, `public/`, `_nuxt/` and `apps/`, so SPA routing can never shadow a real endpoint.
