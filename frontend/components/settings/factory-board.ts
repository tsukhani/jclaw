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
  /** A waiting story's open blockers; absent from an older factory, where Waiting stays flat. */
  blockedBy?: string[] | null
}

/** An open story the factory will not build that keeps a waiting story from starting. */
export interface BoardBlocker {
  key: string
  summary: string | null
  status: string | null
  /** Why the factory will not build it, e.g. "labelled no-afk". */
  reason: string | null
  blockedBy?: string[] | null
}

export interface Board {
  schema: number
  updatedAt: string
  harness: { pid: number, startedAt: string, main: string | null }
  settings: Record<string, string | number> | null
  stories: BoardStory[]
  blockers?: BoardBlocker[] | null
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

export interface FactoryStoryRow { kind: 'story', key: string, depth: number, story: BoardStory }
export interface OutsideStoryRow { kind: 'outside', key: string, depth: number, blocker: BoardBlocker }
export type StoryRow = FactoryStoryRow | OutsideStoryRow

export interface BoardGroup extends StoryGroup {
  rows: StoryRow[]
  /** Rows for stories outside the factory, which only Waiting has. */
  outside: number
}

const GROUPS: { id: string, title: string, states: StoryState[] }[] = [
  { id: 'running', title: 'Running', states: ['running'] },
  { id: 'waiting', title: 'Waiting', states: ['waiting'] },
  { id: 'review', title: 'In review', states: ['review'] },
  { id: 'blocked', title: 'Blocked or refused', states: ['blocked', 'refused'] },
  { id: 'merged', title: 'Merged', states: ['merged'] },
]

/** Every group in board order, empty ones included; stories keep the board's order within a group. */
export function groupStories(stories: BoardStory[]): StoryGroup[] {
  return GROUPS.map(g => ({ id: g.id, title: g.title, stories: stories.filter(s => g.states.includes(s.state)) }))
}

/**
 * The Waiting group as a tree. Outside blockers come first, then waiting stories, each in board order. A node sits under
 * its deepest blocker in the tree, the one drawn later when tied, and is drawn once, so a subtree two stories share
 * appears once. Depth is the longest chain of blockers; a link back into the chain being measured is ignored, so a cycle
 * ends.
 */
export function waitingRows(waiting: BoardStory[], blockers: BoardBlocker[]): StoryRow[] {
  interface TreeNode { key: string, rank: number, blockedBy: string[], row: (depth: number) => StoryRow }
  const nodes = new Map<string, TreeNode>()
  const add = (key: string, blockedBy: string[] | null | undefined, row: TreeNode['row']) => {
    if (!nodes.has(key)) nodes.set(key, { key, rank: nodes.size, blockedBy: blockedBy ?? [], row })
  }
  const waitingKeys = new Set(waiting.map(s => s.key))
  for (const b of blockers) {
    if (!waitingKeys.has(b.key)) add(b.key, b.blockedBy, depth => ({ kind: 'outside', key: b.key, depth, blocker: b }))
  }
  for (const s of waiting) add(s.key, s.blockedBy, depth => ({ kind: 'story', key: s.key, depth, story: s }))

  const depth = new Map<string, number>()
  const measuring = new Set<string>()
  const depthOf = (n: TreeNode): number => {
    const known = depth.get(n.key)
    if (known !== undefined) return known
    measuring.add(n.key)
    let d = 0
    for (const k of n.blockedBy) {
      const b = nodes.get(k)
      if (b && !measuring.has(k)) d = Math.max(d, depthOf(b) + 1)
    }
    measuring.delete(n.key)
    depth.set(n.key, d)
    return d
  }
  // In board order, so where a cycle is cut does not depend on the order a sort compares in.
  for (const n of nodes.values()) depthOf(n)

  // A node's board ranks from its root: comparing two of them orders nodes as the tree draws them. Placing shallowest
  // first is enough, because a node added under a parent never reorders the nodes already placed.
  const path = new Map<string, number[]>()
  const drawnLater = (a: number[], b: number[]) => {
    for (let i = 0; i < Math.min(a.length, b.length); i++) if (a[i] !== b[i]) return a[i]! > b[i]!
    return a.length > b.length
  }
  const children = new Map<string, TreeNode[]>()
  const roots: TreeNode[] = []
  const byDepth = [...nodes.values()].sort((a, b) => depthOf(a) - depthOf(b) || a.rank - b.rank)
  for (const n of byDepth) {
    let parent: TreeNode | undefined
    for (const k of n.blockedBy) {
      const b = nodes.get(k)
      if (!b || !path.has(k) || depthOf(b) >= depthOf(n)) continue
      if (!parent || depthOf(b) > depthOf(parent) || (depthOf(b) === depthOf(parent) && drawnLater(path.get(k)!, path.get(parent.key)!))) parent = b
    }
    if (parent) {
      children.set(parent.key, [...(children.get(parent.key) ?? []), n])
      path.set(n.key, [...path.get(parent.key)!, n.rank])
    }
    else {
      roots.push(n)
      path.set(n.key, [n.rank])
    }
  }

  const rows: StoryRow[] = []
  const place = (n: TreeNode, level: number) => {
    rows.push(n.row(level))
    for (const c of (children.get(n.key) ?? []).sort((a, b) => a.rank - b.rank)) place(c, level + 1)
  }
  for (const n of roots.sort((a, b) => a.rank - b.rank)) place(n, 0)
  return rows
}

/** {@link groupStories} with each group's rows: Waiting as its tree, every other group flat. */
export function boardGroups(stories: BoardStory[], blockers: BoardBlocker[]): BoardGroup[] {
  return groupStories(stories).map((g) => {
    const rows = g.id === 'waiting'
      ? waitingRows(g.stories, blockers)
      : g.stories.map((s): StoryRow => ({ kind: 'story', key: s.key, depth: 0, story: s }))
    return { ...g, rows, outside: rows.filter(r => r.kind === 'outside').length }
  })
}

/** Rows deeper than this are indented as this deep, so a long chain still wraps within a phone's width. */
export const MAX_INDENT_DEPTH = 6

/** A row's left padding: the list's 1rem, plus 0.75rem a level. */
export function rowIndent(depth: number): string {
  return `${1 + Math.min(depth, MAX_INDENT_DEPTH) * 0.75}rem`
}

/** Why the factory will not build it, its status unless that is the why, and what blocks it in turn. */
export function outsideDetail(b: BoardBlocker): string {
  const parts = b.reason ? [b.reason] : []
  if (b.status && b.status !== b.reason) parts.push(b.status)
  const by = b.blockedBy ?? []
  if (by.length > 0) parts.push(`blocked by ${by.join(', ')}`)
  return parts.join(' · ')
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
