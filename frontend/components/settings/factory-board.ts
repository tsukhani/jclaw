// Wire shapes of GET /api/factory (FactoryStatus.StatusView) and the board's grouping rules.

export type StoryState = 'waiting' | 'running' | 'review' | 'blocked' | 'refused' | 'merged'

export interface BoardStory {
  key: string
  summary: string
  source: string
  autoMerge: boolean
  state: StoryState
  since: string
  reason: string | null
  phase: string | null
  phaseStartedAt: string | null
  sha: string | null
  by: 'factory' | 'operator' | null
  logs: string[]
}

export interface Board {
  schema: number
  updatedAt: string
  harness: { pid: number, startedAt: string, main: string | null }
  settings: Record<string, string | number> | null
  stories: BoardStory[]
}

export interface SandboxView {
  name: string
  story: string | null
  upTime: string
}

export interface StatusView {
  installed: boolean
  supported: boolean
  reason: string | null
  harness: { state: string, pid: number | null }
  gateway: { state: string }
  sandboxes: SandboxView[] | null
  board: Board | null
  boardReason: string | null
}

export interface LogView {
  key: string
  file: string
  size: number
  modifiedAt: string
  truncated: boolean
  text: string
}

export interface CommandResult {
  exitCode: number
  timedOut: boolean
  output: string
  message: string
  story?: string | null
}

export interface SettingEntry {
  key: string
  value: string
  defaultValue: string
  set: boolean
}

export interface SettingsView {
  settings: SettingEntry[]
  message: string | null
}

export interface StoryGroup {
  id: string
  title: string
  stories: BoardStory[]
}

const GROUPS: { id: string, title: string, states: StoryState[] }[] = [
  { id: 'waiting', title: 'Waiting', states: ['waiting'] },
  { id: 'running', title: 'Running', states: ['running'] },
  { id: 'review', title: 'In review', states: ['review'] },
  { id: 'blocked', title: 'Blocked or refused', states: ['blocked', 'refused'] },
  { id: 'merged', title: 'Merged', states: ['merged'] },
]

/** Every group in board order, empty ones included; stories keep the board's order within a group. */
export function groupStories(stories: BoardStory[]): StoryGroup[] {
  return GROUPS.map(g => ({ id: g.id, title: g.title, stories: stories.filter(s => g.states.includes(s.state)) }))
}

export function mergedLabel(story: BoardStory): string {
  return story.by === 'factory' ? 'Auto-merged' : 'Merged by hand'
}

/** Short elapsed text such as "45s", "12m", "3h 5m" or "2d 4h"; empty when the stamp does not parse. */
export function elapsed(fromIso: string, now: number): string {
  const from = Date.parse(fromIso)
  if (Number.isNaN(from)) return ''
  const s = Math.max(0, Math.floor((now - from) / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ${m % 60}m`
  return `${Math.floor(h / 24)}d ${h % 24}h`
}

export const FACTORY_INPUT = 'w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong focus:outline-hidden'
export const FACTORY_BUTTON = 'px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50'
