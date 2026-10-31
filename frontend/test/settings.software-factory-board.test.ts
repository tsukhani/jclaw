import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import { createError } from 'h3'
import SettingsSoftwareFactoryPanel from '~/components/settings/SettingsSoftwareFactoryPanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'
import {
  boardGroups, elapsed, groupStories, mergedLabel, outsideBadge, outsideDetail, outsideSpoken, storyDetail, treeGutter, treeStem,
  waitingRows, type BoardBlocker, type BoardStory, type OutsideStoryRow, type StatusView, type StoryRow,
} from '~/components/settings/factory-board'

/**
 * The Software Factory panel past Setup (JCLAW-1394): status strip and its confirmations, the
 * settings form, the grouped story board with Waiting drawn as a tree of blockers, and the per-story log poll in its
 * peek panel.
 */

const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsSoftwareFactoryPanel), h(ConfirmDialog)])
  },
})

function story(over: Partial<BoardStory> & { key: string, state: BoardStory['state'] }): BoardStory {
  return {
    summary: `Summary of ${over.key}`, source: 'jira', autoMerge: false, since: new Date(Date.now() - 125_000).toISOString(),
    reason: null, phase: null, phaseStartedAt: null, sha: null, by: null, logs: [], ...over,
  }
}

function stories(): BoardStory[] {
  return [
    story({ key: 'JCLAW-1', state: 'waiting', reason: 'blocked by JCLAW-9' }),
    story({ key: 'JCLAW-2', state: 'running', phase: 'implement', autoMerge: true, logs: ['JCLAW-2.log', 'JCLAW-2-review.log'] }),
    story({ key: 'JCLAW-3', state: 'review' }),
    story({ key: 'JCLAW-4', state: 'blocked', reason: 'won\'t do', logs: ['JCLAW-4.log'] }),
    story({ key: 'JCLAW-5', state: 'refused', reason: 'gate failed', autoMerge: true }),
    story({ key: 'JCLAW-6', state: 'merged', by: 'factory', sha: '0123456789abcdef', autoMerge: true }),
    story({ key: 'JCLAW-7', state: 'merged', by: 'operator', sha: 'fedcba9876543210' }),
    story({ key: 'JCLAW-8', state: 'merged', by: 'factory', sha: null }),
  ]
}

function outside(key: string, over: Partial<BoardBlocker> = {}): BoardBlocker {
  return { key, summary: `Summary of ${key}`, status: 'To Do', reason: 'labelled no-afk', blockedBy: [], ...over }
}

function waitingOn(key: string, ...blockedBy: string[]): BoardStory {
  return story({ key, state: 'waiting', blockedBy, reason: `blocked by ${blockedBy.map(k => `${k} (To Do)`).join(', ')}` })
}

// Today's chain, as Jira links it: JCLAW-1377 blocks JCLAW-1378 and JCLAW-1380, JCLAW-1378 blocks JCLAW-1380 (all no-afk),
// and JCLAW-1380 holds five afk stories.
function chainStories(): BoardStory[] {
  return [
    waitingOn('JCLAW-1346', 'JCLAW-1380'),
    waitingOn('JCLAW-1347', 'JCLAW-1346'),
    waitingOn('JCLAW-1351', 'JCLAW-1347'),
    waitingOn('JCLAW-1381', 'JCLAW-1346'),
    waitingOn('JCLAW-1382', 'JCLAW-1381'),
  ]
}

function chainBlockers(): BoardBlocker[] {
  return [outside('JCLAW-1380', { blockedBy: ['JCLAW-1377', 'JCLAW-1378'] }), outside('JCLAW-1377'), outside('JCLAW-1378', { blockedBy: ['JCLAW-1377'] })]
}

const shape = (rows: StoryRow[]) => rows.map(r => [r.key, r.depth, r.kind])

let status: StatusView
let statusGets = 0
let logGets = 0
let posts: string[] = []
let puts: unknown[] = []
let statusFails = false
let statusGate: Promise<void> | null = null
let refuseSettings = false

function installed(over: Partial<StatusView> = {}): StatusView {
  return {
    installed: true, supported: true, reason: null,
    harness: { state: 'running', pid: 42 }, gateway: { state: 'running' },
    sandboxes: [{ name: 'sandcastle-0f8c2a8e-5b1d-4c3a-9e7f-2a6b8c4d1e90', story: 'JCLAW-2', upTime: '12 minutes' }],
    board: { schema: 1, updatedAt: '2026-10-05T10:00:00Z', harness: { pid: 42, startedAt: '2026-10-05T09:00:00Z', main: null }, settings: null, stories: stories() },
    boardReason: null,
    ...over,
  }
}

const SETTINGS = [
  { key: 'FACTORY_MAX_PARALLEL', value: '2', defaultValue: '2', set: false },
  { key: 'FACTORY_CPUS', value: '6', defaultValue: '6', set: false },
  { key: 'FACTORY_POLL_SECONDS', value: '120', defaultValue: '120', set: false },
  { key: 'FACTORY_MODEL', value: 'claude-opus-5-5', defaultValue: 'claude-opus-5-5', set: false },
]

registerEndpoint('/api/factory', { method: 'GET', handler: async () => {
  statusGets++
  if (statusGate) await statusGate
  if (statusFails) throw createError({ statusCode: 500, statusMessage: 'probe failed' })
  return status
} })
registerEndpoint('/api/factory/setup', { method: 'GET', handler: () => ({
  installed: true, prerequisites: [], hasModelCredential: true, hasJira: true, hasGithub: false, installJobId: null, message: null,
}) })
let postGate: Promise<void> | null = null
let truncated = false
let logGate: Promise<void> | null = null
let logFails = false
registerEndpoint('/api/factory/settings', { method: 'GET', handler: () => ({ settings: SETTINGS, message: null }) })
registerEndpoint('/api/factory/settings', { method: 'PUT', handler: async (event) => {
  const { readBody } = await import('h3')
  puts.push(await readBody(event))
  if (refuseSettings) throw createError({ statusCode: 400, statusMessage: 'FACTORY_CPUS is more than the 4 CPUs Docker has.' })
  return { settings: SETTINGS, message: 'Saved; applies once the factory is idle.' }
} })
for (const path of ['harness/start', 'harness/stop', 'gateway/pause', 'gateway/resume', 'sandboxes/sandcastle-0f8c2a8e-5b1d-4c3a-9e7f-2a6b8c4d1e90/stop']) {
  registerEndpoint(`/api/factory/${path}`, { method: 'POST', handler: async () => {
    posts.push(path)
    if (postGate) await postGate
    if (path === 'harness/stop') return { exitCode: 1, timedOut: false, output: 'launchctl: no such service', message: 'Harness stop failed.' }
    // The server sends an empty message for a successful harness start.
    return { exitCode: 0, timedOut: false, output: '', message: path === 'harness/start' ? '' : `Done: ${path}.` }
  } })
}
registerEndpoint('/api/factory/stories/JCLAW-2/logs/JCLAW-2.log', { method: 'GET', handler: async () => {
  const n = ++logGets
  if (logGate) await logGate
  if (logFails) throw createError({ statusCode: 500, statusMessage: 'log read failed' })
  return { key: 'JCLAW-2', file: 'JCLAW-2.log', size: 10, modifiedAt: '2026-10-05T10:00:00Z', truncated, text: `tail ${n}` }
} })
registerEndpoint('/api/factory/stories/JCLAW-4/logs/JCLAW-4.log', { method: 'GET', handler: () => {
  logGets++
  return { key: 'JCLAW-4', file: 'JCLAW-4.log', size: 10, modifiedAt: '2026-10-05T10:00:00Z', truncated: false, text: 'blocked tail' }
} })

registerEndpoint('/api/factory/stories/JCLAW-2/logs/JCLAW-2-review.log', { method: 'GET', handler: () => {
  logGets++
  throw createError({ statusCode: 404, statusMessage: 'JCLAW-2-review.log is not on the board.' })
} })

function dialogButton(label: string): HTMLButtonElement | null {
  const buttons = [...document.querySelectorAll('[role="dialog"] button')]
  return (buttons.find(b => (b.textContent ?? '').trim() === label) ?? null) as HTMLButtonElement | null
}

async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

// The peek panel is a sheet portaled to <body>, outside the mounted wrapper.
function sheet(): HTMLElement | null {
  return document.body.querySelector('[data-slot="sheet-content"]')
}

function peek(testid: string): HTMLElement | null {
  return sheet()?.querySelector(`[data-testid="${testid}"]`) ?? null
}

function closePeek() {
  const close = [...sheet()!.querySelectorAll('button')].find(b => (b.textContent ?? '').trim() === 'Close')
  close!.click()
}

// Unmounted after each test: a live panel keeps its status interval and visibility listener.
let mounted: Array<{ unmount: () => void }> = []

async function mount() {
  const c = await mountSuspended(Harness)
  mounted.push(c)
  await settle()
  return c
}

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => {
  clearNuxtData()
  status = installed()
  statusGets = 0
  logGets = 0
  posts = []
  puts = []
  statusFails = false
  statusGate = null
  refuseSettings = false
  postGate = null
  truncated = false
  logGate = null
  logFails = false
})

afterEach(() => {
  for (const c of mounted) c.unmount()
  mounted = []
  document.body.querySelectorAll('[data-slot="sheet-content"]').forEach(el => el.remove())
  vi.useRealTimers()
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
})

describe('factory-board helpers', () => {
  it('groups in board order, with refused under Blocked and empty groups kept', () => {
    const groups = groupStories(stories())
    expect(groups.map(g => [g.title, g.stories.map(s => s.key)])).toEqual([
      ['Running', ['JCLAW-2']],
      ['Waiting', ['JCLAW-1']],
      ['In review', ['JCLAW-3']],
      ['Blocked or refused', ['JCLAW-4', 'JCLAW-5']],
      ['Merged', ['JCLAW-6', 'JCLAW-7', 'JCLAW-8']],
    ])
    expect(groupStories([])[0]!.stories).toEqual([])
  })

  it('labels who merged and shortens elapsed time', () => {
    expect(mergedLabel(story({ key: 'A', state: 'merged', by: 'factory' }))).toBe('Auto-merged')
    expect(mergedLabel(story({ key: 'A', state: 'merged', by: 'operator' }))).toBe('Merged by hand')
    const t0 = Date.parse('2026-10-05T10:00:00Z')
    expect(elapsed('2026-10-05T10:00:00Z', t0 + 45_000)).toBe('45s')
    expect(elapsed('2026-10-05T10:00:00Z', t0 + 12 * 60_000)).toBe('12m')
    expect(elapsed('2026-10-05T10:00:00Z', t0 + 185 * 60_000)).toBe('3h 5m')
    expect(elapsed('2026-10-05T10:00:00Z', t0 + 52 * 3600_000)).toBe('2d 4h')
    expect(elapsed('not a date', t0)).toBe('')
  })

  it('draws today\'s chain as one line, each story once under its deepest blocker', () => {
    expect(shape(waitingRows(chainStories(), chainBlockers()))).toEqual([
      ['JCLAW-1377', 0, 'outside'],
      ['JCLAW-1378', 1, 'outside'],
      ['JCLAW-1380', 2, 'outside'],
      ['JCLAW-1346', 3, 'story'],
      ['JCLAW-1347', 4, 'story'],
      ['JCLAW-1351', 5, 'story'],
      ['JCLAW-1381', 4, 'story'],
      ['JCLAW-1382', 5, 'story'],
    ])
  })

  it('keeps board order among roots and among children', () => {
    const reversed = chainStories().reverse()
    expect(shape(waitingRows(reversed, chainBlockers())).map(([k, d]) => `${k}@${d}`)).toEqual([
      'JCLAW-1377@0', 'JCLAW-1378@1', 'JCLAW-1380@2', 'JCLAW-1346@3', 'JCLAW-1381@4', 'JCLAW-1382@5', 'JCLAW-1347@4', 'JCLAW-1351@5',
    ])
  })

  it('places a story with two equally deep blockers once, under the one drawn later, and its text names both', () => {
    const rows = waitingRows([story({ key: 'B', state: 'waiting' }), story({ key: 'C', state: 'waiting' }), waitingOn('A', 'B', 'C')], [])
    expect(shape(rows)).toEqual([['B', 0, 'story'], ['C', 0, 'story'], ['A', 1, 'story']])
    const a = rows.find(r => r.key === 'A')
    expect(a?.kind === 'story' && a.story.reason).toBe('blocked by B (To Do), C (To Do)')
  })

  it('places a story under its deeper blocker, whichever it lists first', () => {
    const tree = (...a: string[]) => shape(waitingRows([story({ key: 'B', state: 'waiting' }), story({ key: 'C', state: 'waiting' }),
      waitingOn('D', 'C'), waitingOn('A', ...a)], []))
    const expected = [['B', 0, 'story'], ['C', 0, 'story'], ['D', 1, 'story'], ['A', 2, 'story']]
    expect(tree('B', 'D')).toEqual(expected)
    expect(tree('D', 'B')).toEqual(expected)
  })

  it('makes a story whose blocker is running or in review a root that still names it', () => {
    const rows = waitingRows([waitingOn('A', 'B')], [])
    expect(shape(rows)).toEqual([['A', 0, 'story']])
    expect(rows[0]!.kind === 'story' && rows[0]!.story.reason).toBe('blocked by B (To Do)')
  })

  it('moves a story up once its outside blocker turned Done and left the list', () => {
    expect(shape(waitingRows([waitingOn('A', 'O')], [outside('O')]))).toEqual([['O', 0, 'outside'], ['A', 1, 'story']])
    expect(shape(waitingRows([story({ key: 'A', state: 'waiting', blockedBy: [] })], []))).toEqual([['A', 0, 'story']])
  })

  it('shows each story of a cycle once and ends', () => {
    expect(shape(waitingRows([waitingOn('A', 'B'), waitingOn('B', 'A')], []))).toEqual([['B', 0, 'story'], ['A', 1, 'story']])
    expect(shape(waitingRows([waitingOn('C', 'X')], [outside('X', { blockedBy: ['Y'] }), outside('Y', { blockedBy: ['X'] })])))
      .toEqual([['Y', 0, 'outside'], ['X', 1, 'outside'], ['C', 2, 'story']])
    expect(shape(waitingRows([waitingOn('S', 'S')], []))).toEqual([['S', 0, 'story']])
  })

  it('stays flat for a board from an older factory, without blockers or blockedBy', () => {
    const old = [story({ key: 'A', state: 'waiting', reason: 'blocked by B (To Do)' }), story({ key: 'B', state: 'waiting', blockedBy: null })]
    expect(shape(waitingRows(old, []))).toEqual([['A', 0, 'story'], ['B', 0, 'story']])
  })

  it('draws a key that is both a waiting story and an outside blocker once, as the story', () => {
    expect(shape(waitingRows([waitingOn('A', 'B'), story({ key: 'B', state: 'waiting' })], [outside('B'), outside('B')])))
      .toEqual([['B', 0, 'story'], ['A', 1, 'story']])
  })

  it('gives only Waiting a tree and counts its outside rows', () => {
    const groups = boardGroups([...stories(), ...chainStories()], chainBlockers())
    expect(groups.map(g => [g.id, g.outside])).toEqual([['running', 0], ['waiting', 3], ['review', 0], ['blocked', 0], ['merged', 0]])
    expect(groups.find(g => g.id === 'merged')!.rows.map(r => r.depth)).toEqual([0, 0, 0])
  })

  it('draws each row\'s lines: a guide per ancestor column still running, then its own elbow', () => {
    expect(Object.fromEntries(waitingRows(chainStories(), chainBlockers()).map(r => [r.key, treeGutter(r)]))).toEqual({
      'JCLAW-1377': [],
      'JCLAW-1378': ['end'],
      'JCLAW-1380': ['blank', 'end'],
      'JCLAW-1346': ['blank', 'blank', 'end'],
      'JCLAW-1347': ['blank', 'blank', 'blank', 'tee'],
      'JCLAW-1351': ['blank', 'blank', 'blank', 'pipe', 'end'],
      'JCLAW-1381': ['blank', 'blank', 'blank', 'end'],
      'JCLAW-1382': ['blank', 'blank', 'blank', 'blank', 'end'],
    })
  })

  it('runs a parent\'s line past a child that has a later sibling, and ends it at the last', () => {
    const rows = waitingRows([story({ key: 'A', state: 'waiting' }), waitingOn('B', 'A'), waitingOn('C', 'B'), waitingOn('D', 'A')], [])
    expect(rows.map(r => [r.key, treeGutter(r), r.parent, r.hasChildren])).toEqual([
      ['A', [], null, true], ['B', ['tee'], 'A', true], ['C', ['pipe', 'end'], 'B', false], ['D', ['end'], 'A', false],
    ])
  })

  it('drops a stem where a row\'s children\'s elbows sit, and draws a deep chain\'s nearest six columns with no stem', () => {
    const stems = Object.fromEntries(waitingRows(chainStories(), chainBlockers()).map(r => [r.key, treeStem(r)]))
    expect([stems['JCLAW-1377'], stems['JCLAW-1346'], stems['JCLAW-1382']]).toEqual(['0.75rem', '3.75rem', null])
    const deep = waitingRows(Array.from({ length: 9 }, (_, i) => (i === 0 ? story({ key: 'D-0', state: 'waiting' }) : waitingOn(`D-${i}`, `D-${i - 1}`))), [])
    expect(deep.map(r => treeGutter(r).length)).toEqual([0, 1, 2, 3, 4, 5, 6, 6, 6])
    expect(deep.map(r => treeStem(r))).toEqual(['0.75rem', '1.75rem', '2.75rem', '3.75rem', '4.75rem', '5.75rem', null, null, null])
  })

  it('names only the blockers besides the one a row hangs from, and every blocker where it hangs from none', () => {
    const rows = waitingRows([...chainStories(), waitingOn('JCLAW-1390', 'JCLAW-1346', 'JCLAW-1347'), waitingOn('JCLAW-1391', 'JCLAW-9')], chainBlockers())
    const detail = (key: string) => {
      const r = rows.find(x => x.key === key)!
      return r.kind === 'story' ? storyDetail(r) : outsideDetail(r)
    }
    expect(['JCLAW-1346', 'JCLAW-1390', 'JCLAW-1391', 'JCLAW-1380', 'JCLAW-1378', 'JCLAW-1377'].map(detail))
      .toEqual(['', 'also blocked by JCLAW-1346', 'blocked by JCLAW-9 (To Do)', 'also blocked by JCLAW-1377', '', ''])
    const refused = boardGroups(stories(), []).find(g => g.id === 'blocked')!.rows.find(r => r.key === 'JCLAW-5')!
    expect(refused.kind === 'story' && storyDetail(refused)).toBe('gate failed')
  })

  it('badges why an outside story is not built, shows its status only when that is news, and speaks it all', () => {
    expect(['labelled no-afk', 'labelled wont-do', 'not labelled afk', 'In Progress', null].map(reason => outsideBadge(outside('X', { reason }))))
      .toEqual(['no-afk', 'wont-do', 'not labelled afk', 'In Progress', 'outside the factory'])
    const root = (over: Partial<BoardBlocker>) => waitingRows([], [outside('X', over)])[0] as OutsideStoryRow
    expect([root({ status: 'In Progress' }), root({ reason: 'In Progress', status: 'In Progress' }), root({ blockedBy: ['Y'] }), root({ status: null, blockedBy: null })]
      .map(outsideDetail)).toEqual(['In Progress', '', 'blocked by Y', ''])
    expect(outsideSpoken(outside('X', { blockedBy: ['JCLAW-1377', 'JCLAW-1378'] })))
      .toBe('Outside the factory, labelled no-afk, To Do, blocked by JCLAW-1377, JCLAW-1378')
  })
})

describe('Settings — Software Factory panel', () => {
  it('shows only Setup when the factory is not installed', async () => {
    status = installed({ installed: false })
    const c = await mount()
    expect(c.find('[data-testid="factory-setup"]').exists()).toBe(true)
    expect(c.find('[data-testid="factory-status"]').exists()).toBe(false)
    expect(c.find('[data-testid="factory-settings"]').exists()).toBe(false)
    expect(c.find('[data-testid="factory-board"]').exists()).toBe(false)
  })

  it('says why in place of the strip when unsupported, and still renders settings and board', async () => {
    status = installed({ supported: false, reason: 'The factory runs only on macOS.' })
    const c = await mount()
    expect(c.find('[data-testid="factory-unsupported"]').text()).toBe('The factory runs only on macOS.')
    expect(c.find('[data-testid="factory-status"]').exists()).toBe(false)
    expect(c.find('[data-testid="factory-settings"]').exists()).toBe(true)
    expect(c.find('[data-testid="factory-board"]').exists()).toBe(true)
  })

  it('shows the board reason when there is no board, and Docker\'s silence for null sandboxes', async () => {
    status = installed({ board: null, boardReason: 'board.json is absent.', sandboxes: null })
    const c = await mount()
    expect(c.find('[data-testid="factory-board-reason"]').text()).toBe('board.json is absent.')
    expect(c.find('[data-testid="factory-sandboxes"]').text()).toContain('Docker could not be asked')
  })

  it('groups the board with reasons, phase, elapsed time and merge labels', async () => {
    const c = await mount()
    const text = (id: string) => c.find(`[data-testid="factory-group-${id}"]`).text()
    expect(text('waiting')).toContain('Waiting (1)')
    expect(text('waiting')).toContain('blocked by JCLAW-9')
    expect(text('running')).toContain('implement · 2m')
    expect(text('review')).toContain('JCLAW-3')
    expect(text('blocked')).toContain('Blocked or refused (2)')
    expect(text('blocked')).toContain('won\'t do')
    expect(text('blocked')).toContain('gate failed')
    const merged = c.find('[data-testid="factory-group-merged"]')
    expect(merged.find('[data-testid="factory-story-JCLAW-6"]').text()).toContain('Auto-merged · 01234567')
    expect(merged.find('[data-testid="factory-story-JCLAW-6"] [title="0123456789abcdef"]').exists()).toBe(true)
    expect(merged.find('[data-testid="factory-story-JCLAW-7"]').text()).toContain('Merged by hand · fedcba98')
    expect(merged.find('[data-testid="factory-story-JCLAW-8"]').text()).toContain('Auto-merged')
    expect(merged.find('[data-testid="factory-story-JCLAW-8"] .font-mono[title]').exists()).toBe(false)
  })

  it('draws Waiting as a tree with the outside stories as their own rows, and counts them in the heading', async () => {
    status = installed({ board: { ...installed().board!, stories: [...stories(), ...chainStories()], blockers: chainBlockers() } })
    const c = await mount()
    const waiting = c.find('[data-testid="factory-group-waiting"]')
    expect(c.find('[data-testid="factory-toggle-waiting"]').text()).toBe('Waiting (6) · 3 outside the factory')
    expect(waiting.findAll('li').map(li => `${li.find('[data-testid^="factory-"]').attributes('data-testid')}@${li.attributes('data-depth')}`)).toEqual([
      'factory-outside-JCLAW-1377@0', 'factory-outside-JCLAW-1378@1', 'factory-outside-JCLAW-1380@2', 'factory-story-JCLAW-1346@3',
      'factory-story-JCLAW-1347@4', 'factory-story-JCLAW-1351@5', 'factory-story-JCLAW-1381@4', 'factory-story-JCLAW-1382@5',
      'factory-story-JCLAW-1@0',
    ])
    const row1380 = waiting.find('[data-testid="factory-outside-JCLAW-1380"]')
    expect(row1380.element.tagName).toBe('DIV')
    expect(row1380.text()).toBe(
      'JCLAW-1380Summary of JCLAW-1380no-afkalso blocked by JCLAW-1377Outside the factory, labelled no-afk, To Do, blocked by JCLAW-1377, JCLAW-1378')
    expect(waiting.find('[data-testid="factory-outside-JCLAW-1377"] [data-testid="factory-outside-detail"]').exists()).toBe(false)
    const row1346 = waiting.find('[data-testid="factory-story-JCLAW-1346"]')
    expect(row1346.text()).toBe('JCLAW-1346Summary of JCLAW-1346blocked by JCLAW-1380 (To Do)')
    expect(row1346.find('[data-testid="factory-story-detail"]').exists()).toBe(false)
    expect(row1346.find('.sr-only').text()).toBe('blocked by JCLAW-1380 (To Do)')
    expect(row1346.find('[data-testid="factory-tree-stem"]').attributes('style')).toBe('left: 3.75rem;')
    expect(waiting.findAll('[data-testid="factory-story-JCLAW-1351"] [data-testid="factory-tree-cell"]').map(cell => cell.attributes('data-cell')))
      .toEqual(['blank', 'blank', 'blank', 'pipe', 'end'])
    expect(waiting.find('ul').classes()).toEqual(['bg-surface-elevated', 'border', 'border-border'])
    expect(waiting.findAll('li').map(li => li.classes())).toEqual([...Array(8).fill([]), ['border-t', 'border-border']])
    expect(waiting.findAll('button').map(b => b.attributes('data-testid'))).not.toContain('factory-outside-JCLAW-1380')
  })

  it('opens nothing for an outside row, and still opens a factory story\'s logs', async () => {
    status = installed({ board: { ...installed().board!, stories: [...stories(), ...chainStories()], blockers: chainBlockers() } })
    const c = await mount()
    await c.find('[data-testid="factory-outside-JCLAW-1380"]').trigger('click')
    await settle()
    expect(sheet()).toBeNull()
    expect(c.findAll('[aria-pressed="true"]')).toHaveLength(0)

    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    expect(peek('factory-log-text')!.textContent).toBe('tail 1')
  })

  it('draws Waiting flat, with no outside count, for a board from an older factory', async () => {
    const c = await mount()
    expect(c.find('[data-testid="factory-toggle-waiting"]').text()).toBe('Waiting (1)')
    expect(c.findAll('[data-testid="factory-story-JCLAW-1"] [data-testid="factory-tree-cell"]')).toHaveLength(0)
    expect(c.find('[data-testid="factory-group-merged"] ul').classes()).toEqual(['bg-surface-elevated', 'border', 'border-border', 'divide-y', 'divide-border'])
  })

  it('draws a deep chain no more than six columns wide, so its rows wrap at phone width', async () => {
    const deep = Array.from({ length: 9 }, (_, i) => (i === 0 ? story({ key: 'D-0', state: 'waiting' }) : waitingOn(`D-${i}`, `D-${i - 1}`)))
    status = installed({ board: { ...installed().board!, stories: deep } })
    const c = await mount()
    const cells = (k: string) => c.findAll(`[data-testid="factory-story-${k}"] [data-testid="factory-tree-cell"]`).length
    expect(['D-0', 'D-3', 'D-6', 'D-8'].map(cells)).toEqual([0, 3, 6, 6])
    expect(c.find('[data-testid="factory-story-D-8"] > span:last-child').classes()).toContain('flex-wrap')
  })

  it('lets a deep row\'s summary shrink below 12rem rather than scroll sideways, on both kinds of row', async () => {
    status = installed({ board: { ...installed().board!, stories: [...stories(), ...chainStories()], blockers: chainBlockers() } })
    const c = await mount()
    for (const row of ['factory-story-JCLAW-1351', 'factory-outside-JCLAW-1380']) {
      const summary = c.find(`[data-testid="${row}"] > span:last-child > span:nth-child(2)`)
      expect(summary.text(), row).toMatch(/^Summary of /)
      expect(summary.classes().filter(k => k.startsWith('min-w-')), row).toEqual(['min-w-[min(12rem,100%)]'])
    }
  })

  it('lists Running first, then Waiting, and Merged last', async () => {
    const c = await mount()
    expect(c.findAll('[data-testid^="factory-group-"]').map(g => g.attributes('data-testid')))
      .toEqual(['running', 'waiting', 'review', 'blocked', 'merged'].map(id => `factory-group-${id}`))
  })

  it('folds one group, leaves the others open, and keeps it folded across a status refresh', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const c = await mount()
    const toggle = c.find('[data-testid="factory-toggle-merged"]')
    expect(toggle.attributes('aria-expanded')).toBe('true')

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.text()).toBe('Merged (3)')
    expect(c.find('[data-testid="factory-story-JCLAW-6"]').exists()).toBe(false)
    expect(c.find('[data-testid="factory-toggle-running"]').attributes('aria-expanded')).toBe('true')
    expect(c.find('[data-testid="factory-story-JCLAW-2"]').exists()).toBe(true)

    const before = statusGets
    await vi.advanceTimersByTimeAsync(5_000)
    await settle()
    expect(statusGets).toBeGreaterThan(before)
    expect(c.find('[data-testid="factory-story-JCLAW-6"]').exists()).toBe(false)

    await c.find('[data-testid="factory-toggle-merged"]').trigger('click')
    expect(c.find('[data-testid="factory-story-JCLAW-6"]').exists()).toBe(true)
  })

  it('badges Auto-merge on exactly the stories that carry it', async () => {
    const c = await mount()
    const badged = stories().map(s => s.key)
      .filter(k => c.find(`[data-testid="factory-story-${k}"] [data-testid="factory-automerge-badge"]`).exists())
    expect(badged).toEqual(['JCLAW-2', 'JCLAW-5', 'JCLAW-6'])
  })

  it.each([
    ['factory-harness-stop', 'Stop', 'harness/stop', 'Stories running now will fail'],
    ['factory-gateway-pause', 'Pause', 'gateway/pause', 'stories running now will fail'],
    ['factory-sandbox-stop-sandcastle-0f8c2a8e-5b1d-4c3a-9e7f-2a6b8c4d1e90', 'Stop', 'sandboxes/sandcastle-0f8c2a8e-5b1d-4c3a-9e7f-2a6b8c4d1e90/stop', 'JCLAW-2? The story running in it will fail'],
  ])('%s asks first: cancel sends nothing, confirm posts', async (testid, confirmLabel, path, warning) => {
    const c = await mount()
    await c.find(`[data-testid="${testid}"]`).trigger('click')
    await settle()
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(warning)
    dialogButton('Cancel')!.click()
    await settle()
    expect(posts).toEqual([])

    const before = statusGets
    await c.find(`[data-testid="${testid}"]`).trigger('click')
    await settle()
    dialogButton(confirmLabel)!.click()
    await settle()
    expect(posts).toEqual([path])
    await vi.waitFor(() => expect(statusGets).toBeGreaterThan(before))
  })

  it('shows the message and output tail of a failed control action', async () => {
    const c = await mount()
    await c.find('[data-testid="factory-harness-stop"]').trigger('click')
    await settle()
    dialogButton('Stop')!.click()
    await settle()
    const result = c.find('[data-testid="factory-action-result"]')
    expect(result.text()).toContain('Harness stop failed.')
    expect(result.text()).toContain('launchctl: no such service')
  })

  it('starts the harness and resumes the gateway without a dialog', async () => {
    status = installed({ harness: { state: 'stopped', pid: null }, gateway: { state: 'exited' } })
    const c = await mount()
    expect(c.find('[data-testid="factory-gateway"]').text()).toContain('paused')
    await c.find('[data-testid="factory-harness-start"]').trigger('click')
    await settle()
    await c.find('[data-testid="factory-gateway-resume"]').trigger('click')
    await settle()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(posts).toEqual(['harness/start', 'gateway/resume'])
    expect(c.text()).toContain('Done: gateway/resume.')
  })

  it('says Done when a successful action returns an empty message', async () => {
    status = installed({ harness: { state: 'stopped', pid: null } })
    const c = await mount()
    await c.find('[data-testid="factory-harness-start"]').trigger('click')
    await settle()
    expect(c.find('[data-testid="factory-action-result"] [role="status"]').text()).toBe('Done.')
  })

  it('offers no control for an unknown harness or an absent gateway', async () => {
    status = installed({ harness: { state: 'unknown', pid: null }, gateway: { state: 'absent' } })
    const c = await mount()
    expect(c.find('[data-testid="factory-harness"] button').exists()).toBe(false)
    expect(c.find('[data-testid="factory-gateway"] button').exists()).toBe(false)
    expect(c.find('[data-testid="factory-gateway"]').text()).toContain('absent')
  })

  it('validates settings, accepting 1 beside 0, and PUTs only the changed keys as strings', async () => {
    const c = await mount()
    expect(c.find('[data-testid="factory-settings"]').text()).toContain('Changes apply once the factory is idle.')
    const save = c.find('[data-testid="factory-settings-save"]')
    const cpus = c.find('[data-testid="factory-setting-FACTORY_CPUS"]')
    expect(save.attributes('disabled')).toBeDefined()

    for (const bad of ['0', 'x', '']) {
      await cpus.setValue(bad)
      expect(c.find('[data-testid="factory-setting-error-FACTORY_CPUS"]').exists(), bad).toBe(true)
      expect(save.attributes('disabled'), bad).toBeDefined()
    }
    await cpus.setValue('1')
    expect(c.find('[data-testid="factory-setting-error-FACTORY_CPUS"]').exists()).toBe(false)
    expect(save.attributes('disabled')).toBeUndefined()

    const model = c.find('[data-testid="factory-setting-FACTORY_MODEL"]')
    for (const bad of ['a b', ' ']) {
      await model.setValue(bad)
      expect(c.find('[data-testid="factory-setting-error-FACTORY_MODEL"]').exists(), bad).toBe(true)
      expect(save.attributes('disabled'), bad).toBeDefined()
    }
    await model.setValue('claude-opus-5-5')
    await cpus.setValue('6')
    expect(save.attributes('disabled')).toBeDefined()

    await cpus.setValue('4')
    await c.find('[data-testid="factory-settings"]').trigger('submit')
    await vi.waitFor(() => expect(puts).toEqual([{ FACTORY_CPUS: '4' }]))
    expect(c.find('[data-testid="factory-settings"]').text()).toContain('Saved; applies once the factory is idle.')
  })

  it('opens a story in the resizable peek panel, not inline, titled with its key and summary', async () => {
    const c = await mount()
    expect(sheet()).toBeNull()
    await c.find('[data-testid="factory-story-JCLAW-3"]').trigger('click')
    await settle()
    expect(c.find('[data-testid="factory-story-JCLAW-3"]').attributes('aria-pressed')).toBe('true')
    expect(c.find('[data-testid="factory-story-view"]').exists()).toBe(false)
    expect(peek('factory-story-view')).not.toBeNull()
    expect(sheet()!.querySelector('[role="separator"]')).not.toBeNull()
    expect(sheet()!.textContent).toContain('JCLAW-3')
    expect(sheet()!.textContent).toContain('Summary of JCLAW-3')
  })

  it('shows No logs yet without a request for a story with no logs', async () => {
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-3"]').trigger('click')
    await settle()
    expect(peek('factory-story-view')!.textContent).toContain('No logs yet.')
    expect(logGets).toBe(0)
  })

  it('polls a running story\'s log every 3 s and stops once the board shows it not running', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    expect([...sheet()!.querySelectorAll('[data-testid^="factory-log-tab-"]')].map(t => t.textContent!.trim()))
      .toEqual(['JCLAW-2.log', 'JCLAW-2-review.log'])
    expect(peek('factory-log-tab-JCLAW-2.log')!.getAttribute('aria-pressed')).toBe('true')
    expect(logGets).toBe(1)
    expect(peek('factory-log-text')!.textContent).toBe('tail 1')

    await vi.advanceTimersByTimeAsync(3_000)
    await settle()
    expect(logGets).toBe(2)
    expect(peek('factory-log-text')!.textContent).toBe('tail 2')

    status = installed({ board: { ...installed().board!, stories: stories().map(s => s.key === 'JCLAW-2' ? { ...s, state: 'review' as const } : s) } })
    await vi.advanceTimersByTimeAsync(2_000)
    await settle()
    expect(c.find('[data-testid="factory-group-review"]').text()).toContain('JCLAW-2')
    await vi.advanceTimersByTimeAsync(10_000)
    await settle()
    expect(logGets).toBe(2)
    expect(peek('factory-story-view')).not.toBeNull()
  })

  it('stops polling the status while the document is hidden and refreshes on return', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    await mount()
    const afterMount = statusGets
    await vi.advanceTimersByTimeAsync(5_000)
    await settle()
    expect(statusGets).toBe(afterMount + 1)

    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(20_000)
    await settle()
    expect(statusGets).toBe(afterMount + 1)

    setVisibility('visible')
    await settle()
    expect(statusGets).toBe(afterMount + 2)
    await vi.advanceTimersByTimeAsync(5_000)
    await settle()
    expect(statusGets).toBe(afterMount + 3)
  })

  it('shows Setup and a loading line until the status arrives, with no strip, form or board', async () => {
    let open!: () => void
    statusGate = new Promise(r => (open = r))
    const c = await mount()
    expect(c.find('[data-testid="factory-status-pending"]').text()).toBe('Loading…')
    expect(c.text()).toContain('Setup')
    for (const id of ['factory-status', 'factory-settings', 'factory-board']) {
      expect(c.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
    }
    open()
    await settle()
    expect(c.find('[data-testid="factory-status"]').exists()).toBe(true)
  })

  it('says it could not read the status when the request fails, and shows Setup alone', async () => {
    statusFails = true
    const c = await mount()
    expect(c.find('[data-testid="factory-status-pending"]').text()).toBe('Could not read the factory status.')
    expect(c.text()).toContain('Setup')
    for (const id of ['factory-status', 'factory-settings', 'factory-board']) {
      expect(c.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
    }
  })

  it.each(['paused', 'created'])('offers Resume for a %s gateway', async (state) => {
    status = installed({ gateway: { state } })
    const c = await mount()
    expect(c.find('[data-testid="factory-gateway-resume"]').exists()).toBe(true)
    expect(c.find('[data-testid="factory-gateway-pause"]').exists()).toBe(false)
  })

  it('shows an unknown gateway\'s state with no control', async () => {
    status = installed({ gateway: { state: 'unknown' } })
    const c = await mount()
    expect(c.find('[data-testid="factory-gateway"] button').exists()).toBe(false)
    expect(c.find('[data-testid="factory-gateway"]').text()).toContain('unknown')
  })

  it('accepts 6 CPUs and shows the server\'s refusal of a saved value', async () => {
    refuseSettings = true
    const c = await mount()
    const cpus = c.find('[data-testid="factory-setting-FACTORY_CPUS"]')
    await cpus.setValue('6')
    expect(c.find('[data-testid="factory-setting-error-FACTORY_CPUS"]').exists()).toBe(false)
    await cpus.setValue('8')
    await c.find('[data-testid="factory-settings"]').trigger('submit')
    await vi.waitFor(() => expect(puts).toEqual([{ FACTORY_CPUS: '8' }]))
    await settle()
    expect(c.find('[data-testid="factory-settings"]').text()).toContain('FACTORY_CPUS is more than the 4 CPUs Docker has.')
  })

  it('shows a rotated log\'s 404 in its tab and keeps polling while the story runs', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    peek('factory-log-tab-JCLAW-2-review.log')!.click()
    await settle()
    expect(peek('factory-story-view')!.textContent).toContain('JCLAW-2-review.log is not on the board.')
    expect(peek('factory-log-text')).toBeNull()
    const after = logGets
    await vi.advanceTimersByTimeAsync(3_000)
    await settle()
    expect(logGets).toBe(after + 1)
  })

  it('notes a truncated log tail, and only then', async () => {
    truncated = true
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    expect(peek('factory-story-view')!.textContent).toContain('Showing the end of the log only.')
    closePeek()
    await settle()
    truncated = false
    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    expect(peek('factory-log-text')).not.toBeNull()
    expect(peek('factory-story-view')!.textContent).not.toContain('Showing the end of the log only.')
  })

  it('starts the log poll when a selected story begins running', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-4"]').trigger('click')
    await settle()
    expect(logGets).toBe(1)
    await vi.advanceTimersByTimeAsync(3_000)
    await settle()
    expect(logGets).toBe(1)

    status = installed({ board: { ...installed().board!, stories: stories().map(s => s.key === 'JCLAW-4' ? { ...s, state: 'running' as const, phase: 'implement' } : s) } })
    await vi.advanceTimersByTimeAsync(2_000)
    await settle()
    await vi.advanceTimersByTimeAsync(3_000)
    await settle()
    expect(logGets).toBe(2)
  })

  it('drops a late log response for a story no longer selected', async () => {
    let release!: () => void
    logGate = new Promise(r => (release = r))
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    await c.find('[data-testid="factory-story-JCLAW-4"]').trigger('click')
    await settle()
    expect(peek('factory-log-text')!.textContent).toBe('blocked tail')
    release()
    await settle()
    expect(peek('factory-log-text')!.textContent).toBe('blocked tail')
  })

  it('disables every control while one is in flight', async () => {
    let release!: () => void
    postGate = new Promise(r => (release = r))
    status = installed({ harness: { state: 'stopped', pid: null }, gateway: { state: 'exited' } })
    const c = await mount()
    await c.find('[data-testid="factory-harness-start"]').trigger('click')
    await settle()
    for (const id of ['factory-harness-start', 'factory-gateway-resume', 'factory-sandbox-stop-sandcastle-0f8c2a8e-5b1d-4c3a-9e7f-2a6b8c4d1e90']) {
      expect(c.find(`[data-testid="${id}"]`).attributes('disabled'), id).toBeDefined()
    }
    await c.find('[data-testid="factory-gateway-resume"]').trigger('click')
    release()
    await settle()
    expect(posts).toEqual(['harness/start'])
    expect(c.find('[data-testid="factory-gateway-resume"]').attributes('disabled')).toBeUndefined()
  })

  it('closes the peek panel on Close, and when the story leaves the board without reopening on its return', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-3"]').trigger('click')
    await settle()
    closePeek()
    await settle()
    expect(sheet()).toBeNull()
    expect(c.find('[data-testid="factory-story-JCLAW-3"]').attributes('aria-pressed')).toBe('false')

    await c.find('[data-testid="factory-story-JCLAW-3"]').trigger('click')
    await settle()
    expect(sheet()).not.toBeNull()
    status = installed({ board: { ...installed().board!, stories: stories().filter(s => s.key !== 'JCLAW-3') } })
    await vi.advanceTimersByTimeAsync(5_000)
    await settle()
    expect(sheet()).toBeNull()

    status = installed()
    await vi.advanceTimersByTimeAsync(5_000)
    await settle()
    expect(c.find('[data-testid="factory-story-JCLAW-3"]').exists()).toBe(true)
    expect(c.find('[data-testid="factory-story-JCLAW-3"]').attributes('aria-pressed')).toBe('false')
    expect(sheet()).toBeNull()
  })

  it('pauses the log poll while the document is hidden and resumes on return', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    expect(logGets).toBe(1)
    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(10_000)
    await settle()
    expect(logGets).toBe(1)
    setVisibility('visible')
    await settle()
    expect(logGets).toBe(2)
    await vi.advanceTimersByTimeAsync(3_000)
    await settle()
    expect(logGets).toBe(3)
  })

  it('does not poll the status when mounted in a hidden document', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    await mount()
    const afterMount = statusGets
    await vi.advanceTimersByTimeAsync(15_000)
    await settle()
    expect(statusGets).toBe(afterMount)
  })

  it('clears the saved note once a field is edited again', async () => {
    const c = await mount()
    await c.find('[data-testid="factory-setting-FACTORY_CPUS"]').setValue('4')
    await c.find('[data-testid="factory-settings"]').trigger('submit')
    await vi.waitFor(() => expect(c.text()).toContain('Saved; applies once the factory is idle.'))
    await c.find('[data-testid="factory-setting-FACTORY_CPUS"]').setValue('3')
    expect(c.text()).not.toContain('Saved; applies once the factory is idle.')
  })

  it('keeps the last log tail on screen when one poll fails', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    expect(peek('factory-log-text')!.textContent).toBe('tail 1')
    statusFails = false
    logFails = true
    await vi.advanceTimersByTimeAsync(3_000)
    await settle()
    expect(peek('factory-log-text')!.textContent).toBe('tail 1')
    expect(peek('factory-story-view')!.textContent).toContain('log read failed')
  })
})
