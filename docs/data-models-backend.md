# Data Models — Backend

JPA entities under `app/models/`, all extending `play.db.jpa.Model` (Play 1.x gives you an auto-generated `Long id` plus static finders: `find`, `findById`, `delete(...)`, `save()`). Schema is applied by `jpa.ddl=update` in both dev and prod (explicitly chosen in `application.conf` — the comment notes this is a pre-1.0 tradeoff and expects to move to a real migration tool once stable).

**Dev DB:** H2 file-based at `./data/jclaw` (`MODE=MYSQL;AUTO_SERVER=TRUE`).
**Prod DB:** PostgreSQL (commented template in `application.conf`).

## Entity reference

### `agent` — `Agent.java`

| Column | Type | Notes |
|---|---|---|
| `name` | VARCHAR unique, NOT NULL | `"main"` is the fallback for `AgentRouter`. |
| `model_provider` | VARCHAR NOT NULL | e.g. `"openrouter"`. |
| `model_id` | VARCHAR NOT NULL | e.g. `"anthropic/claude-opus-4-6"`. |
| `enabled` | BOOL NOT NULL | Disabled agents are skipped by `AgentRouter`. |
| `thinking_mode` | VARCHAR nullable | Reasoning-effort level; null = reasoning off. Validated at API layer against the model's advertised `thinkingLevels`. |
| `created_at` / `updated_at` | TIMESTAMP | Set by `@PrePersist` / `@PreUpdate`. |

Has-many: `bindings` (cascade ALL, orphanRemoval).

### `agent_binding` — `AgentBinding.java`

Maps `(channelType, peerId?) → agent`. Used by `AgentRouter.resolve` in tier-1 (exact peer) and tier-2 (peer NULL) matching. Indexed on `(channel_type, peer_id)` and `agent_id`.

| Column | Type | Notes |
|---|---|---|
| `agent_id` | FK→agent, NOT NULL | |
| `channel_type` | VARCHAR NOT NULL | matches `ChannelType.value` |
| `peer_id` | VARCHAR nullable | NULL = channel-wide binding |
| `priority` | INT NOT NULL, default 0 | |

### `conversation` — `Conversation.java`

Per-(agent, channel, peer) conversation thread. Indexed on `(agent_id, channel_type, peer_id)`.

| Column | Type | Notes |
|---|---|---|
| `agent_id` | FK→agent, NOT NULL | |
| `channel_type` | VARCHAR NOT NULL | `ChannelType.fromValue`-compatible string |
| `peer_id` | VARCHAR nullable | |
| `message_count` | INT NOT NULL | Maintained by `ConversationService`. |
| `preview` | VARCHAR(100) | Short excerpt for list views. |
| `created_at` / `updated_at` | TIMESTAMP | |

Has-many: `messages` ordered by `createdAt ASC`.

### `message` — `Message.java`

Indexes: `(conversation_id)`, `(conversation_id, created_at)`.

| Column | Type | Notes |
|---|---|---|
| `conversation_id` | FK→conversation, NOT NULL | |
| `role` | VARCHAR NOT NULL | `MessageRole`: `user`/`assistant`/`tool`/`system` (stored as strings, not JPA enum). |
| `content` | TEXT | |
| `tool_calls` | TEXT | JSON blob (OpenAI-compatible tool_calls). |
| `tool_results` | TEXT | JSON blob of tool execution output. |
| `created_at` | TIMESTAMP | |

### `task` — `Task.java`

Background/scheduled task unit. Indexed on `(status, next_run_at)` — the hot query for the poller.

| Column | Type | Notes |
|---|---|---|
| `agent_id` | FK→agent, nullable | When set, task is run as a prompt through `AgentRunner`. |
| `name` | VARCHAR NOT NULL | |
| `description` | TEXT | |
| `type` | ENUM(`IMMEDIATE`/`SCHEDULED`/`CRON`) | JPA `@Enumerated(STRING)`. |
| `cron_expression` | VARCHAR nullable | Parsed by `jobs.CronParser`. |
| `scheduled_at` | TIMESTAMP nullable | |
| `status` | ENUM(`PENDING`/`RUNNING`/`COMPLETED`/`FAILED`/`CANCELLED`) | default PENDING. |
| `retry_count` / `max_retries` | INT NOT NULL | defaults 0 / 3. |
| `last_error` | TEXT | Exception message on failure. |
| `next_run_at` | TIMESTAMP nullable | Drives poller claim. |
| `created_at` / `updated_at` | TIMESTAMP | |

Note: `findPendingDue()` and `findByStatus()` use native SQL with string comparison to avoid Play 1.x classloader enum mismatches during hot-reload.

### `memory` — `Memory.java`

Per-agent free-form long-term memory. Indexed on `agent_id`.

| Column | Type | Notes |
|---|---|---|
| `agent_id` | VARCHAR NOT NULL | String FK, not a JPA relation. |
| `text` | TEXT NOT NULL | |
| `category` | VARCHAR(50) | |
| `created_at` / `updated_at` | TIMESTAMP | |

Search is `LOWER(text) LIKE %q%` — explicitly documented in memory/user-guidance: **no full-text index** today; the plan if scale hurts is Postgres `tsvector`, not H2 `FT_*`.

### `config` — `Config.java`

Key/value runtime config; overrides `application.conf`. Read via `ConfigService.getString/getInt/getBool` with a fallback default.

| Column | Type | Notes |
|---|---|---|
| `config_key` | VARCHAR NOT NULL UNIQUE | |
| `config_value` | TEXT | |
| `updated_at` | TIMESTAMP | |

### `event_log` — `EventLog.java`

Structured audit log. Indexes: `(timestamp)`, `(category, level)`.

| Column | Type | Notes |
|---|---|---|
| `timestamp` | TIMESTAMP NOT NULL | |
| `level` | VARCHAR(10) NOT NULL | `INFO`/`WARN`/`ERROR`/`DEBUG`. |
| `category` | VARCHAR(50) NOT NULL | e.g. `agent`, `llm`, `task`, `channel`. |
| `agent_id` | VARCHAR nullable | |
| `channel` | VARCHAR(50) nullable | |
| `message` | VARCHAR(500) NOT NULL | |
| `details` | TEXT | Freeform. |
| `created_at` | TIMESTAMP | |

Trimmed by `EventLogCleanupJob` (`deleteOlderThan`).

### `channel_config` — `ChannelConfig.java`

Per-channel configuration JSON (tokens, bot IDs, webhook secrets).

| Column | Type | Notes |
|---|---|---|
| `channel_type` | VARCHAR NOT NULL UNIQUE | |
| `config_json` | TEXT NOT NULL | Channel-specific shape. |
| `enabled` | BOOL NOT NULL, default false | |

### Skill allowlist tables

Three related tables implement the "shell-exec allowlist from promoted skills" design (see `openspec/specs/shell-exec/`):

#### `skill_registry_tool` — `SkillRegistryTool.java`

Registry snapshot: one row per `(skill_name, tool_name)`. Rewritten on every promotion. Used only as the install-time source; NOT consulted at shell-exec validation time.

#### `agent_skill_allowed_tool` — `AgentSkillAllowedTool.java`

**Live authority for shell-exec validation.** One row per `(agent, skill, tool)`. Written when an agent installs a registered skill; deleted only when the agent removes the skill from its workspace. Unique index on `(agent_id, skill_name, tool_name)`.

Effective allowlist = `global shell.allowlist ∪ (rows WHERE agent=:agent AND skill_name IN enabled AgentSkillConfig.skill_name)`.

Design rationale (from javadoc): persist-at-install makes the allowlist immune to filesystem tampering — an agent with filesystem-write cannot expand its own allowlist by editing its workspace skill manifest. Registry un-promotion does not retroactively revoke granted commands.

#### `agent_skill_config` — `AgentSkillConfig.java`

Per-agent skill enable/disable. Absent row = enabled by default.

#### `agent_tool_config` — `AgentToolConfig.java`

Per-agent tool enable/disable. Absent row = enabled by default.

## Enums

### `ChannelType` (string-backed, NOT JPA `@Enumerated`)

`WEB("web")`, `SLACK("slack")`, `TELEGRAM("telegram")`, `WHATSAPP("whatsapp")`. `resolve()` returns a `Channel` for outbound-push channels (null for `WEB` — web responses are DB-persisted and read by the frontend). `fromValue(String)` returns null for unknown values so callers can fall through.

### `MessageRole` (string-backed)

`user`/`assistant`/`tool`/`system`. Chosen OpenAI-compatible wire format; NOT JPA-enum because Play 1.x's hot-reload clashes with enum classloader identity.

## Migration policy

From feedback memory:
- **Pre-release:** `jclaw` has no legacy users to protect. When a migration helper is applied once (e.g. `ConfigService.renameKeyIfPresent`), **drop the call site afterward but retain the helper** for possible reuse.
- **No `db.evolution`** in use today; column-type changes and renames still require manual intervention.
