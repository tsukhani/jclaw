import type { MessageUsage } from '~/utils/usage-cost'

/** An LLM agent configured in the system. */
export interface Agent {
  id: number
  name: string
  modelProvider: string
  modelId: string
  enabled: boolean
  isMain: boolean
  thinkingMode: string | null
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
}

/** A single message within a conversation. */
export interface Message {
  id: number
  role: 'user' | 'assistant' | 'tool'
  content: string | null
  reasoning: string | null
  createdAt: string
  usage: MessageUsage | null
  /** Frontend-only key assigned to optimistic/streaming placeholders. */
  _key?: string
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

/** A skill definition. */
export interface Skill {
  name: string
  description: string | null
  version: string | null
  [key: string]: unknown
}

/** A file in a skill's file tree. */
export interface SkillFile {
  path: string
  size: number
  isText: boolean
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
}
