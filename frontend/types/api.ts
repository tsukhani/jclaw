import type { MessageUsage } from '~/utils/usage-cost'

/** An LLM agent configured in the system. */
export interface Agent {
  id: number
  name: string
  /** Operator-supplied short description of the agent's purpose (max 255 chars), or null when unset. */
  description?: string | null
  modelProvider: string
  modelId: string
  enabled: boolean
  isMain: boolean
  /** Persisted reasoning-effort level ("low" | "medium" | "high" | provider-specific), or null when reasoning is off. */
  thinkingMode: string | null
  /** True when the selected provider has an API key configured (populated by GET /api/agents). */
  providerConfigured?: boolean
  /** Whether vision input is enabled for this agent (null when not applicable to the model). */
  visionEnabled?: boolean | null
}

/**
 * One Telegram bot-agent-user binding, as surfaced by
 * {@code GET /api/channels/telegram/bindings}. The full bot token and webhook
 * secret are never returned by the API. {@link hasWebhookSecret} lets the UI
 * render a "leave blank to keep existing" hint when editing a webhook binding.
 */
export interface TelegramBindingSummary {
  id: number
  agentId: number | null
  agentName: string | null
  telegramUserId: string
  transport: string
  webhookUrl: string | null
  hasWebhookSecret: boolean
  enabled: boolean
  /**
   * ISO-8601 instant until which the binding's bot token is in post-unregister
   * cooldown (Telegram's long-poll takes up to ~30 s to drain server-side).
   * While this is set and in the future, the frontend locks the enable toggle
   * and shows a countdown so the operator knows why they can't re-enable yet.
   */
  cooldownUntil: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** A conversation between a user and an agent. */
export interface Conversation {
  id: number
  preview: string | null
  channelType: string
  agentName: string
  peerId: string | null
  messageCount: number
  createdAt: string
  updatedAt: string
  /** Conversation-scoped model override (JCLAW-108). Null when the conversation inherits the agent default. */
  modelProviderOverride?: string | null
  /** Companion to modelProviderOverride — see type docs above. */
  modelIdOverride?: string | null
}

/**
 * JCLAW-170: one structured search-result row, shipped by the backend
 * alongside the LLM-visible markdown so the chat UI can render clickable
 * chips with favicons. Every field is nullable since older search providers
 * may not always populate all of them and the UI has to be defensive.
 */
export interface ToolCallResultChip {
  title: string | null
  url: string | null
  snippet: string | null
  faviconUrl: string | null
}

/** JCLAW-170: structured result payload attached to a tool call. For
 *  {@code web_search}, carries a list of {@link ToolCallResultChip}s the UI
 *  renders as clickable result chips. Absent for tools that don't emit a
 *  structured view. */
export interface ToolCallResultStructured {
  provider?: string | null
  results?: ToolCallResultChip[]
}

/**
 * JCLAW-170: one tool invocation the assistant made during the turn.
 * Populated both live (via the {@code tool_call} SSE frame) and on
 * conversation reload (from persisted {@code Message.toolCalls}
 * plus the corresponding {@code tool_result_structured} row keyed by
 * {@code id}). {@code icon} is the registry's semantic icon key ({@code
 * "search"}, {@code "folder"}, etc.) that the chat UI maps to a Heroicon.
 */
export interface ToolCall {
  id: string
  name: string
  icon: string
  arguments: string
  resultText?: string | null
  resultStructured?: ToolCallResultStructured | null
  /** Client-only: whether this individual tool call's body (chip grid or
   *  result text) is expanded under the per-call header (JCLAW-170). */
  _expanded?: boolean
}

/** A single message within a conversation. */
export interface Message {
  /** Server-assigned id. Absent on optimistic/streaming placeholders until the backend persists the row. */
  id?: number
  role: 'user' | 'assistant' | 'tool'
  content: string | null
  reasoning?: string | null
  createdAt: string
  usage?: MessageUsage | null
  /** JCLAW-170: tool invocations on this assistant turn, hydrated from the
   *  persisted message thread on load and appended via SSE during streaming. */
  toolCalls?: ToolCall[]
  /** Frontend-only key assigned to optimistic/streaming placeholders. */
  _key?: string
  /** Client-only: whether the thinking/reasoning bubble is collapsed for this message. */
  thinkingCollapsed?: boolean
  /** Client-only: whether the tool-calls block is collapsed for this message (JCLAW-170). */
  toolCallsCollapsed?: boolean
  /** Client-only: elapsed stream thinking duration in ms, persisted only for the current render. */
  _thinkingDurationMs?: number | null
  /** Client-only: wall-clock ms when the current assistant stream began producing reasoning. */
  _thinkingStartedAt?: number
}

/** A config entry from /api/config. */
export interface ConfigEntry {
  key: string
  value: string
}

/** The shape returned by GET /api/config. */
export interface ConfigResponse {
  entries: ConfigEntry[]
}

/**
 * One OCR backend in the GET /api/ocr/status response.
 * `available` is the runtime probe (binary on PATH); `enabled` is the user
 * toggle in the Config DB. The Settings page renders the toggle as
 * uninteractive when `available=false`, so a host without the binary
 * installed cannot have the backend turned on by accident.
 */
export interface OcrBackend {
  name: string
  displayName: string
  available: boolean
  enabled: boolean
  version: string | null
  reason: string | null
  configKey: string
  description: string
  installHint: string
}

export interface OcrStatusResponse {
  providers: OcrBackend[]
}

/** A single histogram for a latency segment, as returned by /api/metrics/latency. */
export interface LatencyHistogram {
  count: number
  sum_ms: number
  min_ms: number
  max_ms: number
  p50_ms: number
  p90_ms: number
  p99_ms: number
  p999_ms: number
  buckets?: Array<{ le_ms: number, count: number }>
}

/**
 * Response shape from GET /api/metrics/latency.
 * Nested by channel (web, telegram, task, ...) so each transport's
 * distribution stays separable — see JCLAW-102.
 */
export type LatencyMetrics = Record<string, Record<string, LatencyHistogram>>

/** A skill definition (returned by /api/skills). */
export interface Skill {
  name: string
  description: string | null
  version: string | null
  /** Directory name on disk — typically the same as `name` but may differ for legacy skills. */
  folderName?: string
  author?: string
  isGlobal?: boolean
  location?: string
  /** Tool names this skill depends on (from SKILL.md frontmatter). */
  tools?: string[]
  /** Shell commands this skill contributes to an installing agent's allowlist. */
  commands?: string[]
  /** Optional emoji or symbol from the SKILL.md `icon:` frontmatter; empty string when not declared. */
  icon?: string
  [key: string]: unknown
}

/** A file in a skill's file tree. */
export interface SkillFile {
  path: string
  size: number
  isText: boolean
}

/** Minimal tool reference as returned inside a SkillFilesResponse.tools array. */
export interface SkillToolRef {
  name: string
  /** Optional short description provided by the backend for display. */
  description?: string
  [key: string]: unknown
}

/**
 * Shape returned by GET /api/skills/:name/files (and the matching agent-scoped
 * endpoint). Includes the file tree plus the tool dependencies and commands
 * declared in the skill's SKILL.md frontmatter.
 */
export interface SkillFilesResponse {
  files: SkillFile[]
  tools: SkillToolRef[]
  commands: string[]
  author?: string
}

/**
 * Shape returned by GET /api/skills/:name/files/:path (and the agent-scoped
 * variant) — a single text file's contents for the inline file viewer.
 */
export interface SkillFileContent {
  content: string
}

/** A channel configuration status. */
export interface ChannelStatus {
  channelType: string
  enabled: boolean
  config: Record<string, string>
}

/** A log event from /api/logs. */
export interface LogEvent {
  id: number
  level: 'ERROR' | 'WARN' | 'INFO'
  category: string
  message: string
  details: string | null
  timestamp: string
  agentId?: number | null
}

/** A tool binding for a specific agent, as returned by GET /api/agents/:id/tools. */
export interface AgentTool {
  name: string
  enabled: boolean
  [key: string]: unknown
}

/** A skill binding for a specific agent, as returned by GET /api/agents/:id/skills. */
export interface AgentSkill {
  name: string
  description?: string | null
  enabled: boolean
  isGlobal?: boolean
  /** Tool names this skill depends on. */
  tools?: string[]
  /** Shell commands this skill contributes to the effective allowlist. */
  commands?: string[]
  /** Optional emoji or symbol from the SKILL.md `icon:` frontmatter; empty string when not declared. */
  icon?: string
  [key: string]: unknown
}

/** Shape returned by GET /api/agents/:id/workspace/:file. */
export interface WorkspaceFileContent {
  content: string
}

/** Shape returned by GET /api/agents/:id/shell/effective-allowlist. */
export interface EffectiveAllowlist {
  global: string[]
  bySkill: Record<string, string[]>
}

/** Single entry in the prompt-breakdown arrays. */
export interface PromptBreakdownEntry {
  name: string
  chars: number
  tokens: number
}

/** Shape returned by GET /api/agents/:id/prompt-breakdown. */
export interface PromptBreakdown {
  totalChars: number
  totalTokenEstimate: number
  cacheBoundaryMarker: string
  cacheablePrefixChars: number
  variableSuffixChars: number
  sections: PromptBreakdownEntry[]
  skills: PromptBreakdownEntry[]
  tools: PromptBreakdownEntry[]
}

/** Shape returned by GET /api/config/:key — a single config entry. */
export interface ConfigValueResponse {
  value: string
}

/** A scheduled/run task row as returned by GET /api/tasks. */
export interface Task {
  id: number
  name: string
  type: string
  status: string
  agentName: string | null
  nextRunAt: string | null
  retryCount: number
  maxRetries: number
  [key: string]: unknown
}

/** A single persisted model on a provider (stored inside provider.{name}.models JSON). */
export interface ProviderModelDef {
  id: string
  name?: string
  contextWindow?: number
  maxTokens?: number
  supportsThinking?: boolean
  supportsVision?: boolean
  supportsAudio?: boolean
  promptPrice?: number | null
  completionPrice?: number | null
  cachedReadPrice?: number | null
  cacheWritePrice?: number | null
  thinkingLevels?: string[]
  [key: string]: unknown
}

/**
 * Shape returned by POST /api/providers/:name/discover-models — includes the
 * stored ProviderModelDef fields plus provider-detection hints and an
 * optional ranking from the leaderboard.
 */
export interface DiscoveredModel extends ProviderModelDef {
  isFree?: boolean
  leaderboardRank?: number | null
  thinkingDetectedFromProvider?: boolean
  visionDetectedFromProvider?: boolean
  audioDetectedFromProvider?: boolean
}

/** Shape returned by POST /api/providers/:name/discover-models. */
export interface DiscoverModelsResponse {
  models: DiscoveredModel[]
}
