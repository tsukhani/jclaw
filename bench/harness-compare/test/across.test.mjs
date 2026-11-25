// The check across tasks (the rank test, the shift, the report lines and what the history keeps) and the host summary.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mannWhitney, quantile, summarizeHost } from '../src/analyze.mjs'
import { parseCpuLine, sample } from '../src/memory.mjs'
import { ACROSS_P, compareAcrossTasks, historyEntry, ownSamplesOf, render } from '../src/report.mjs'

const H = ['jclaw', 'openclaw', 'hermes']
const TASKS = ['question', 'followup', 'summary', 'reasoning', 'json', 'fiveturn', 'search1', 'search3']

// Each task's turns spread 14% around a level of their own; JClaw's own times are scaled by factor.
function runOf(factor, { turns = 15, level = i => 20 + i } = {}) {
  const rows = []
  for (const h of H) {
    TASKS.forEach((task, i) => {
      for (let k = 0; k < turns; k++) rows.push({ harness: h, task, timed: true, own: level(i) * (1 + 0.01 * k) * (h === 'jclaw' ? factor : 1) })
    })
  }
  const summary = {}
  for (const task of TASKS) {
    for (const h of H) {
      const own = rows.filter(r => r.task === task && r.harness === h).map(r => r.own)
      summary[`${task}|${h}`] = { n: own.length, ownP50: quantile(own, 0.5), firstReplyCalls: [], parallelRounds: 0 }
    }
  }
  return { rows, summary }
}
const entryOf = (run, extra = {}) => ({ at: '2026-11-24T00:00:00.000Z', host: 'testhost', model: 'm', summary: run.summary, ownSamples: ownSamplesOf(run.rows), ...extra })
const flagged = (base, cur) => compareAcrossTasks(base, cur.summary, ownSamplesOf(cur.rows))?.map(c => [c.harness, Math.round(c.shift * 100), c.tasks, c.n])

test('the rank test gives the two-sided normal-approximation p, ties corrected, that scipy gives', () => {
  // scipy.stats.mannwhitneyu(x, y, use_continuity=False, method='asymptotic').pvalue
  const near = (x, y, want) => assert.equal(Math.abs(mannWhitney(x, y) - want) < 1e-6, true, `${mannWhitney(x, y)} for ${want}`)
  near([1, 2, 3, 4, 5], [6, 7, 8, 9, 10], 0.009023438818080326)
  near([1, 2, 2, 3, 3, 3, 4], [2, 3, 4, 4, 5, 5, 6], 0.036754961054507304)
  near([1, 2, 3], [1, 2, 3], 1)
  assert.equal(mannWhitney([5, 5], [5, 5]), 1)
})

test('marks a harness whose own time moved 10% or more across its tasks, either way, only with p below 0.01', () => {
  const base = entryOf(runOf(1))
  assert.deepEqual(flagged(base, runOf(1)), [])
  assert.deepEqual(flagged(base, runOf(1.2)), [['jclaw', 20, 8, [120, 120]]])
  assert.deepEqual(flagged(base, runOf(1.11)), [['jclaw', 11, 8, [120, 120]]])
  assert.deepEqual(flagged(base, runOf(1.09)), [])
  assert.deepEqual(flagged(base, runOf(0.85)), [['jclaw', -15, 8, [120, 120]]])
  // One turn a task at levels far apart: a 30% move, but the turns of different tasks interleave, so the test cannot tell.
  const sparse = { turns: 1, level: i => 10 * 2 ** i }
  const cur = runOf(1.3, sparse)
  const base1 = entryOf(runOf(1, sparse))
  assert.equal(mannWhitney(base1.ownSamples.jclaw, ownSamplesOf(cur.rows).jclaw) >= ACROSS_P, true)
  assert.deepEqual(flagged(base1, cur), [])
})

test('leaves the check out when the earlier run kept no per-turn own times, and skips a harness with none', () => {
  const run = runOf(1.2)
  assert.equal(compareAcrossTasks({ summary: run.summary }, run.summary, ownSamplesOf(run.rows)), null)
  const base = entryOf(runOf(1))
  base.ownSamples = { ...base.ownSamples, jclaw: [] }
  assert.deepEqual(flagged(base, run), [])
})

function reportOf(run, history, host) {
  const meta = { kind: 'meta', at: '2026-11-25T00:00:00.000Z', runs: 15, tasks: TASKS, harnesses: H, model: 'm', user: 'harnessbench' }
  const analysis = { meta, rows: run.rows, summary: run.summary, background: {}, memory: null, host }
  const versions = Object.fromEntries(H.map(h => [h, { tag: 'v1' }]))
  return render({ analysis, conformance: { pass: true, results: [] }, versions, turns: [], history: history.map(e => ({ versions, ...e })), host: 'testhost' })
}
const changes = (text) => text.slice(text.indexOf('**Against the previous'), text.indexOf('## Per-task')).split('\n').filter(Boolean)
const busyAt = (otherCoresMedian) => ({ cpus: 48, load1Median: 2.4, load1Peak: 3.1, otherCoresMedian, otherCoresPeak: 12, users: [] })

test('the report names a harness worth a look with the host then and now, and says when none moved or it was left out', () => {
  const base = entryOf(runOf(1), { hostLoad: busyAt(0.4) })
  assert.deepEqual(changes(reportOf(runOf(1.2), [base], busyAt(9.6))).slice(2), [
    "- **Worth a look:** JClaw's own time moved +20% across its 8 tasks, the median of their changes (Mann–Whitney p below 0.0001 over 120 turns then and 120 now). Outside the harnesses the host used a median of 0.4 cores then and 9.6 now, of 48.",
    'A move across tasks is a first sign, not proof of a slowdown: its 10% line was set on five identical runs from one quiet night. Across those and three more in the daytime it moved by 6.8% at most.',
  ])
  assert.equal(changes(reportOf(runOf(1.2), [{ ...base, hostLoad: undefined }], busyAt(9.6)))[2].endsWith(' Outside the harnesses the host used a median of 9.6 of its 48 cores; that run did not record it.'), true)
  assert.deepEqual(changes(reportOf(runOf(1), [base], busyAt(0.4))).slice(2), ["No harness's own time moved by 10% or more across its tasks with a rank test p below 0.01."])
  assert.deepEqual(changes(reportOf(runOf(1.2), [{ ...base, ownSamples: undefined }], busyAt(0.4))).slice(2), ["The check across tasks was left out: that run's history line keeps no per-turn own times."])
})

test('the history keeps each harness\'s own time per timed turn to 0.01 ms, and the host\'s load', () => {
  const rows = [
    { harness: 'jclaw', timed: true, own: 12.3456 }, { harness: 'jclaw', timed: false, own: 99 },
    { harness: 'jclaw', timed: true, bad: 'no timing' }, { harness: 'hermes', timed: true, own: 205.004 },
  ]
  const meta = { at: '2026-11-25T00:00:00.000Z', model: 'm' }
  const entry = historyEntry({ analysis: { meta, rows, summary: {}, memory: null, host: { ...busyAt(0.4), users: [{ user: 'root', cores: 0.1 }] } }, versions: {}, host: 'testhost', runDir: '/r' })
  assert.deepEqual(entry.ownSamples, { jclaw: [12.35], openclaw: [], hermes: [205] })
  assert.deepEqual(entry.hostLoad, { cpus: 48, load1Median: 2.4, load1Peak: 3.1, otherCoresMedian: 0.4, otherCoresPeak: 12 })
  assert.equal(historyEntry({ analysis: { meta, rows, summary: {}, memory: null, host: null }, versions: {}, host: 'testhost', runDir: '/r' }).hostLoad, undefined)
})

test('reads the host\'s total and busy ticks from the cpu line, idle and iowait being the only idle time', () => {
  assert.deepEqual(parseCpuLine('cpu  100 5 50 800 20 3 2 1 9 0\ncpu0 50 2 25 400 10 1 1 0 4 0\n'), { totalTicks: 981, busyTicks: 161 })
})

// Four CPUs at 100 ticks a second: 400 ticks of the host per second between samples.
const tick = (s, busy, harness, byUser) => ({ kind: 'memory', host: { load1: s, cpus: 4, totalTicks: s * 400, busyTicks: busy, harnessTicks: harness, byUser } })

test('the host summary turns ticks into the cores used outside the harnesses, by user, never below zero', () => {
  const lines = [
    tick(1, 100, 50, { jenkins: 0, root: 40 }),
    tick(2, 300, 100, { jenkins: 100, root: 50 }), // 150 busy outside the trees: 1.5 cores
    tick(3, 380, 180, { jenkins: 100, root: 30 }), // none outside; root's sum fell as another user reaped its process
    tick(4, 780, 200, { jenkins: 400, root: 40 }), // 380 outside: 3.8 cores
  ]
  assert.deepEqual(summarizeHost(lines), {
    cpus: 4, load1Median: 2.5, load1Peak: 4, otherCoresMedian: 1.5, otherCoresPeak: 3.8,
    users: [{ user: 'jenkins', cores: 400 / 1200 * 4 }, { user: 'root', cores: 20 / 1200 * 4 }],
  })
  assert.deepEqual(summarizeHost([{ kind: 'memory', host: { load1: 1.5, cpus: 8 } }, { kind: 'memory', host: { load1: 2.5, cpus: 8 } }]),
    { cpus: 8, load1Median: 2, load1Peak: 2.5, otherCoresMedian: null, otherCoresPeak: null, users: [] })
  assert.equal(summarizeHost([{ kind: 'memory', trees: {} }]), null)
})

const hostSection = (text) => text.slice(text.indexOf('## Host'), text.indexOf('## Wrong')).split('\n').filter(Boolean)

test('the report shows the host\'s load and who else used its CPU, naming the benchmark\'s own user, and what it could not read', () => {
  const users = [{ user: 'jenkins', cores: 6.04 }, { user: 'harnessbench', cores: 0.21 }, { user: 'root', cores: 0.05 }, { user: 'nobody', cores: 0.04 }]
  assert.deepEqual(hostSection(reportOf(runOf(1), [], { ...busyAt(0.4), users })), ['## Host during the run',
    "Sampled with memory, when no request was in flight. The 1-minute load average was 2.4 at the median and 3.1 at the most, on 48 cores. Processes outside the three harnesses' trees used 0.4 cores at the median between consecutive samples and 12.0 at the most. Averaged over the run, by user: jenkins 6.0, harnessbench (this benchmark's proxy and driver) 0.2, root 0.1."])
  assert.deepEqual(hostSection(reportOf(runOf(1), [], { cpus: 8, load1Median: 2, load1Peak: 2.5, otherCoresMedian: null, otherCoresPeak: null, users: [] })), ['## Host during the run',
    "Sampled with memory, when no request was in flight. The 1-minute load average was 2.0 at the median and 2.5 at the most, on 8 cores. Which processes kept the host busy is read from Linux's /proc, so it is not known here."])
  assert.deepEqual(hostSection(reportOf(runOf(1), [], null)), ['## Host during the run', 'Not measured in this run.'])
})

test('a live sample reads the host\'s load and CPU count, and on Linux its ticks, the trees\' and each other user\'s', () => {
  const { host } = sample({ me: process.pid })
  assert.equal(host.load1 >= 0 && host.cpus >= 1, true, JSON.stringify(host))
  if (process.platform === 'linux') {
    assert.equal(host.totalTicks > host.busyTicks && host.busyTicks > 0 && host.harnessTicks > 0, true, JSON.stringify(host))
    assert.equal(Object.keys(host.byUser).length > 0, true, JSON.stringify(host.byUser))
  } else {
    assert.deepEqual(Object.keys(host), ['load1', 'cpus'])
  }
})
