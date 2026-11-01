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

export type TreeGuide = 'pipe' | 'blank'
export type TreeElbow = 'tee' | 'end'

/** Where a row sits in Waiting's tree; every other group's rows are roots with no lines. */
export interface TreePlace {
  /** One per ancestor column: `pipe` while that ancestor has a later sibling, so its line runs past this row. */
  guides: TreeGuide[]
  /** The row's own connector: `tee` when a sibling follows it, `end` for the last, null for a root. */
  elbow: TreeElbow | null
  /** The key of the story it is drawn under, null for a root. */
  parent: string | null
  hasChildren: boolean
}

export interface FactoryStoryRow extends TreePlace { kind: 'story', key: string, depth: number, story: BoardStory }
export interface OutsideStoryRow extends TreePlace { kind: 'outside', key: string, depth: number, blocker: BoardBlocker }
export type StoryRow = FactoryStoryRow | OutsideStoryRow

const ROOT: TreePlace = { guides: [], elbow: null, parent: null, hasChildren: false }

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

interface TreeNode { key: string, rank: number, blockedBy: string[], row: (depth: number, at: TreePlace) => StoryRow }

function blockerDepths(nodes: Map<string, TreeNode>): Map<string, number> {
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
  return depth
}

function drawnLater(a: number[], b: number[]): boolean {
  for (let i = 0; i < Math.min(a.length, b.length); i++) if (a[i] !== b[i]) return a[i]! > b[i]!
  return a.length > b.length
}

function placedParent(n: TreeNode, nodes: Map<string, TreeNode>, depth: Map<string, number>, path: Map<string, number[]>): TreeNode | undefined {
  const depthOf = (t: TreeNode) => depth.get(t.key)!
  let parent: TreeNode | undefined
  for (const k of n.blockedBy) {
    const b = nodes.get(k)
    if (!b || !path.has(k) || depthOf(b) >= depthOf(n)) continue
    if (!parent || depthOf(b) > depthOf(parent) || (depthOf(b) === depthOf(parent) && drawnLater(path.get(k)!, path.get(parent.key)!))) parent = b
  }
  return parent
}

function treeRows(roots: TreeNode[], children: Map<string, TreeNode[]>): StoryRow[] {
  const rows: StoryRow[] = []
  // `below` is the guides a node's children inherit: its own, plus whether its column runs on past its subtree.
  const place = (n: TreeNode, level: number, at: TreePlace, below: TreeGuide[]) => {
    const kids = (children.get(n.key) ?? []).sort((a, b) => a.rank - b.rank)
    rows.push(n.row(level, { ...at, hasChildren: kids.length > 0 }))
    kids.forEach((c, i) => {
      const last = i === kids.length - 1
      place(c, level + 1, { guides: below, elbow: last ? 'end' : 'tee', parent: n.key, hasChildren: false },
        [...below, last ? 'blank' : 'pipe'])
    })
  }
  for (const n of roots) place(n, 0, ROOT, [])
  return rows
}

/**
 * The Waiting group as a tree. Outside blockers come first, then waiting stories, each in board order. A node sits under
 * its deepest blocker in the tree, the one drawn later when tied, and is drawn once, so a subtree two stories share
 * appears once. Depth is the longest chain of blockers; a link back into the chain being measured is ignored, so a cycle
 * ends.
 */
export function waitingRows(waiting: BoardStory[], blockers: BoardBlocker[]): StoryRow[] {
  const nodes = new Map<string, TreeNode>()
  const add = (key: string, blockedBy: string[] | null | undefined, row: TreeNode['row']) => {
    if (!nodes.has(key)) nodes.set(key, { key, rank: nodes.size, blockedBy: blockedBy ?? [], row })
  }
  const waitingKeys = new Set(waiting.map(s => s.key))
  for (const b of blockers) {
    if (!waitingKeys.has(b.key)) add(b.key, b.blockedBy, (depth, at) => ({ kind: 'outside', key: b.key, depth, blocker: b, ...at }))
  }
  for (const s of waiting) add(s.key, s.blockedBy, (depth, at) => ({ kind: 'story', key: s.key, depth, story: s, ...at }))

  const depth = blockerDepths(nodes)

  // A node's board ranks from its root: comparing two of them orders nodes as the tree draws them. Placing shallowest
  // first is enough, because a node added under a parent never reorders the nodes already placed.
  const path = new Map<string, number[]>()
  const children = new Map<string, TreeNode[]>()
  const roots: TreeNode[] = []
  const byDepth = [...nodes.values()].sort((a, b) => depth.get(a.key)! - depth.get(b.key)! || a.rank - b.rank)
  for (const n of byDepth) {
    const parent = placedParent(n, nodes, depth, path)
    if (parent) {
      children.set(parent.key, [...(children.get(parent.key) ?? []), n])
      path.set(n.key, [...path.get(parent.key)!, n.rank])
    }
    else {
      roots.push(n)
      path.set(n.key, [n.rank])
    }
  }

  roots.sort((a, b) => a.rank - b.rank)
  return treeRows(roots, children)
}

/** {@link groupStories} with each group's rows: Waiting as its tree, every other group flat. */
export function boardGroups(stories: BoardStory[], blockers: BoardBlocker[]): BoardGroup[] {
  return groupStories(stories).map((g) => {
    const rows = g.id === 'waiting'
      ? waitingRows(g.stories, blockers)
      : g.stories.map((s): StoryRow => ({ kind: 'story', key: s.key, depth: 0, story: s, ...ROOT }))
    return { ...g, rows, outside: rows.filter(r => r.kind === 'outside').length }
  })
}

/**
 * Rows deeper than this draw only their nearest this-many columns, so a long chain still wraps within a phone's width;
 * their lines then no longer meet their parent's.
 */
export const MAX_INDENT_DEPTH = 6

/** The cells of a row's gutter, 1rem each: its ancestors' guides, then its own elbow. */
export function treeGutter(row: TreePlace): (TreeGuide | TreeElbow)[] {
  const cells: (TreeGuide | TreeElbow)[] = row.elbow ? [...row.guides, row.elbow] : [...row.guides]
  return cells.slice(-MAX_INDENT_DEPTH)
}

/**
 * Where the line down to a row's first child runs: the centre of the column its children's elbows sit in, past the
 * row's 0.25rem padding. Null when it has no children, or sits too deep for its children's columns to line up.
 */
export function treeStem(row: TreePlace & { depth: number }): string | null {
  if (!row.hasChildren || row.depth >= MAX_INDENT_DEPTH) return null
  return `${0.75 + row.depth}rem`
}

/** The keys a row names beside its tree parent; a root has no parent, so it names them all. */
function besidesParent(blockedBy: string[] | null | undefined, parent: string | null): { keys: string[], also: boolean } {
  const keys = (blockedBy ?? []).filter(k => k !== parent)
  return { keys, also: parent !== null && keys.length < (blockedBy ?? []).length }
}

/**
 * What a factory story's row says: under its blocker in the tree it names only its other blockers, since the line
 * shows the one it hangs from; elsewhere its reason, as before. The full reason stays for screen readers.
 */
export function storyDetail(row: FactoryStoryRow): string {
  const s = row.story
  if (s.state !== 'waiting' || row.parent === null || !(s.blockedBy ?? []).includes(row.parent)) return s.reason ?? ''
  const { keys } = besidesParent(s.blockedBy, row.parent)
  return keys.length > 0 ? `also blocked by ${keys.join(', ')}` : ''
}

/** The outside row's badge: why the factory will not build it, without the word "labelled". */
export function outsideBadge(b: BoardBlocker): string {
  return (b.reason ?? 'outside the factory').replace(/^labelled /, '')
}

/** Its status when that is news (not To Do, and not already its badge), then what else blocks it. */
export function outsideDetail(row: OutsideStoryRow): string {
  const b = row.blocker
  const parts = b.status && b.status !== 'To Do' && b.status !== b.reason ? [b.status] : []
  const { keys, also } = besidesParent(b.blockedBy, row.parent)
  if (keys.length > 0) parts.push(`${also ? 'also blocked by' : 'blocked by'} ${keys.join(', ')}`)
  return parts.join(' · ')
}

/** The whole of an outside row for a screen reader, which sees no badge colour or lines. */
export function outsideSpoken(b: BoardBlocker): string {
  const parts = ['Outside the factory', b.reason, b.status].filter((p): p is string => Boolean(p))
  const by = b.blockedBy ?? []
  if (by.length > 0) parts.push(`blocked by ${by.join(', ')}`)
  return parts.join(', ')
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
