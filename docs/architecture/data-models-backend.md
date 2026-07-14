# Data Models — Backend

JPA entities under `app/models/` (~29), all extending `play.db.jpa.Model` (Play 1.x gives an auto-generated `Long id` plus static finders: `find`, `findById`, `count`, `delete`, `save`). Schema is applied by `jpa.ddl=update` in both dev and prod — a deliberate pre-1.0 tradeoff (additive only; renames/type changes need manual intervention).

**Dev DB:** H2 file at `./data/jclaw` (`MODE=MYSQL;AUTO_SERVER=TRUE`). **Prod DB:** PostgreSQL (commented template). **L2 cache:** read-mostly entities carry Hibernate `@Cache(READ_WRITE)` backed by Caffeine-JCache (JCLAW-205); a query cache is enabled globally (only `ApiToken` opts in today). **Enums** are string-backed (`@Enumerated(STRING)` or manual conversion) to survive Play 1.x hot-reload classloader identity.

## Agents, conversations & messages

### `agent` — `Agent.java` (L2-cached)
Key fields: `name` (unique, NOT NULL — `"main"` is the `AgentRouter` fallback), `model_provider`, `model_id`, `enabled`, `thinking_mode`, per-agent compression toggles, `parent_agent_id` (subagent hierarchy), `acp_allowed` (subagent-spawn privilege), `created_at`/`updated_at`. Has-many `bindings` (cascade ALL, orphanRemoval).

### `conversation` — `Conversation.java`
Per-(agent, channel, peer) thread. Indexed on `(agent_id, channel_type, peer_id)`. Notable fields: `message_count`, `preview`, `context_since` (`/reset` watermark), `compaction_since`, `model_provider_override`/`model_id_override`, `parent_conversation_id` + `parent_context` (subagent inheritance), active-stream checkpoints. Has-many `messages` (ASC by `createdAt`).

### `message` — `Message.java`
Indexes on `(conversation_id)`, `(conversation_id, created_at)`, `(subagent_run_id)`, `(role, created_at)`. Fields: `role` (`MessageRole` string), `content`, `tool_calls`/`tool_results`/`tool_result_structured` (JSON), `reasoning`, `message_kind`/`metadata`, `usage_json`, `truncated`, `subagent_run_id`. **Lucene-indexed** via `@PostPersist`/`@PostUpdate`/`@PostRemove`. Has-many `attachments`.

### `chat_message_attachment` — `MessageAttachment.java` (L2-cached)
Per-message media. `uuid` (unique, client-facing), `original_filename`, `storage_path`, `mime_type`, `size_bytes`, `kind` (IMAGE/AUDIO/VIDEO/FILE), `transcript` (Whisper), `caption` (non-vision fallback), `video_summary`, `generated` + `generation_metadata`/`generation_job_id`, `deleted`.

### `memory` — `Memory.java`
Per-agent long-term memory, scoped by `agent_id` (string), with `text` + `category`. **Lucene-indexed** (JCLAW-415) — full-text search, *not* `LIKE`.

### `session_compaction` — `SessionCompaction.java`
LLM-generated summary of a conversation prefix (JCLAW-38): `conversation_id`, `turn_count`, `summary_tokens`, `model`, `summary`, `compacted_at`.

## Tasks & scheduling

### `task` — `Task.java`
Indexed on `(status, next_run_at)`. Nested string-backed enums: `Type` (IMMEDIATE/SCHEDULED/INTERVAL/CRON), `Status` (PENDING/ACTIVE/RUNNING/LOST/COMPLETED/FAILED/CANCELLED). Fields include `agent_id` (nullable — run as a prompt when set; `no_agent`/`script` for script-only), `cron_expression`, `interval_seconds`, `scheduled_at`, `timezone` (IANA), `delivery`, `payload_type`, per-task `model_provider`/`model_id`, `enabled_tool_names`, `workdir`, `repeat_limit`, `auto_delete_on_complete` (reminders), retry bookkeeping, `next_run_at`. **Lucene-indexed** (name + description). Execution is owned by **db-scheduler**, not a custom poller.

### `task_run` — `TaskRun.java`
One row per fire. Nested enums `Status` (RUNNING/COMPLETED/FAILED/CANCELLED) and `DeliveryStatus`. Fields: `started_at`/`completed_at`, `duration_ms`, `error`, `output_summary`, `usage_json`, `trace_json`, delivery status/target/error. Indexed on `(task_id)`, `(started_at)`, `(status)`.

### `task_run_message` — `TaskRunMessage.java`
Per-turn transcript of a run (`turn_index`, `role`, `content`, tool calls/results, `usage_json`, `reasoning`). **Lucene-indexed** for transcript search.

## Channels & bindings (all L2-cached — inbound hot path)

| Entity | Table | Purpose / key fields |
|---|---|---|
| `AgentBinding` | `agent_binding` | Generic `(channel_type, peer_id?) → agent` routing + `priority`. |
| `ChannelConfig` | `channel_config` | Per-channel `config_json` + `enabled` (named-cache eviction). |
| `SlackBinding` | `slack_binding` | `bot_token` (unique), `signing_secret`, `app_token` (Socket Mode), 1:1 `agent_id`, `transport` (HTTP/SOCKET), team/bot IDs. |
| `TelegramBinding` | `telegram_binding` | `bot_token` (unique), 1:1 `agent_id`, `telegram_user_id`, `transport` (POLLING/HTTP), `webhook_secret`, reply/notifier policy. |
| `TelegramTopicBinding` | `telegram_topic_binding` | Per-forum-topic agent override (`binding_id`, `chat_id`, `thread_id`, `agent_id`). |
| `WhatsAppBinding` | `whatsapp_binding` | `transport` (CLOUD_API/WHATSAPP_WEB), Cloud-API creds (`phone_number_id`, `access_token`, …) or `owner_jid` for Web, 1:1 `agent_id`. |
| `WhatsAppConversationWindow` | `whatsapp_conversation_window` | Tracks the 24h Cloud-API free-form messaging window per peer. |

## Skills, tools & MCP (L2-cached)

| Entity | Table | Purpose |
|---|---|---|
| `McpServer` | `mcp_server` | MCP server config: `name` (unique), `transport` (STDIO/HTTP), `config_json`, `status`, `requires_approval`. |
| `AgentToolConfig` | `agent_tool_config` | Per-agent tool enable (absent row = enabled). |
| `AgentSkillConfig` | `agent_skill_config` | Per-agent skill enable. |
| `AgentSkillAllowedTool` | `agent_skill_allowed_tool` | **Live authority for shell-exec validation** — one row per `(agent, skill, tool)`, written at skill install. Immune to filesystem tampering; un-promotion does not retroactively revoke. |
| `SkillRegistryTool` | `skill_registry_tool` | Registry snapshot per `(skill, tool)`; install-time source only, not consulted at validation time. |
| `ToolApprovalGrant` | `tool_approval_grant` | Durable "APPROVED_ALWAYS" record per `(agent, tool)` for dangerous tools (JCLAW-385). |

Effective shell allowlist = `global shell.allowlist ∪ (AgentSkillAllowedTool WHERE agent=:agent AND skill ∈ enabled AgentSkillConfig)`.

## Config & auth

| Entity | Table | Purpose |
|---|---|---|
| `Config` | `config` | Key/value runtime config overriding `application.conf`; read via `ConfigService` (L2-cached). |
| `ApiToken` | `api_token` | Bearer credential for the in-process `jclaw_api` tool: `secret_hash` (SHA-256, unique), `owner_username` (`"system"`), throttled `last_used_at`. Query-cached. |

## Subagents, notifications & media

| Entity | Table | Purpose |
|---|---|---|
| `SubagentRun` | `subagent_run` | Parent/child agent + conversation IDs, `Status` (RUNNING/COMPLETED/FAILED/KILLED/TIMEOUT), `label`, `outcome`, `yielded`. **Lucene-indexed**. |
| `Notification` | `notification` | User-visible reminders (web channel): `agent_id`, `content`, source task/run IDs, `acknowledged_at`. |
| `VideoGenerationJob` | `video_generation_job` | Async video gen: `prompt`, `params`, `provider`, `State` (PENDING/RUNNING/SUCCEEDED/FAILED), `percent`, `result_attachment_id`. |

## Metrics & audit

| Entity | Table | Purpose |
|---|---|---|
| `EventLog` | `event_log` | Structured audit log (`timestamp`, `level`, `category`, `agent_id`, `channel`, `message`, `details`); trimmed by `EventLogCleanupJob`. |
| `LatencyMetric` | `latency_metrics` | Per-segment request latency for the Chat Performance dashboard (JCLAW-515). |
| `CompressionMetric` | `compression_metrics` | Compression/inflation/CCR telemetry (`Kind`, tokens before/after, algorithm). |

## Enums

String-backed (stored as VARCHAR): `MessageRole` (user/assistant/tool/system), `ChannelType` (web/slack/telegram/whatsapp), `ChannelTransport` (POLLING/HTTP/SOCKET), `WhatsAppTransport` (CLOUD_API/WHATSAPP_WEB), plus the nested entity enums on `Task`, `TaskRun`, `McpServer`, `SubagentRun`, `CompressionMetric`, and `VideoGenerationJob`. `ChannelType.resolve()` returns a `Channel` for outbound-push channels (web persists to `message` instead).

## Search-index integration (Lucene 10)

`Message`, `Task`, `TaskRunMessage`, `Memory`, and `SubagentRun` sync to per-scope Lucene indexes via JPA `@PostPersist`/`@PostUpdate`/`@PostRemove` hooks (failures are logged, never abort the JPA transaction). The index lives under `data/jclaw-lucene/<scope>/` and is re-backfilled from DB rows on boot. Status/type filters still use SQL `LIKE`.

## Migration policy

- **Pre-release:** `jclaw` has no legacy users to protect. When a one-shot migration helper is applied (e.g. `ConfigService.renameKeyIfPresent`), drop the call site afterward but retain the helper.
- NOT NULL columns added to populated tables carry `@ColumnDefault` so the `update` DDL backfills cleanly.
- No `db.evolution` / Flyway / Liquibase today — all schema flows through Hibernate auto-DDL.
