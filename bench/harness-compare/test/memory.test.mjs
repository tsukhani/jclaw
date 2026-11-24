// The memory sampler on known /proc text and on this test's own process tree, and its summary and report.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { aggregate, descendants, nameOf, parseRollup, parseRoots, parseStat, snapshot } from '../src/memory.mjs'
import { analyze, summarizeMemory } from '../src/analyze.mjs'
import { compareMemory, render } from '../src/report.mjs'

const MiB = 1024
const H = ['jclaw', 'openclaw', 'hermes']

test('reads the name and parent from a stat line whose name holds spaces and parentheses', () => {
  assert.deepEqual(parseStat('4242 (python3 (side) x) S 4200 4242 4242 0 -1 4194560 120 0 0 0'), { name: 'python3 (side) x', ppid: 4200 })
})

test('names a process by the script an interpreter runs, else by its program or the title it gave itself', () => {
  assert.equal(nameOf(['/usr/bin/node', '/w/openclaw/dist/process/spawn-broker/worker.js']), 'spawn-broker/worker.js')
  assert.equal(nameOf(['node', '--enable-source-maps', '/w/infra/sqlite-readonly-location.worker.js', '--child', 'session']), 'infra/sqlite-readonly-location.worker.js')
  assert.equal(nameOf(['python3', '/w/hermes-agent/hermes', 'gateway', 'run']), 'hermes-agent/hermes')
  assert.equal(nameOf(['/j/sidecar/image/.venv/bin/python3.12', 'serve.py', '--port', '18421'], '/j/sidecar/image'), 'image/serve.py')
  assert.equal(nameOf(['python3', 'serve.py']), 'serve.py')
  assert.equal(nameOf(['openclaw-gateway']), 'openclaw-gateway')
  assert.equal(nameOf(['/home/u/jre/bin/java', '-Xms128m', '-Xmx2g', '-cp', 'x', 'play.server.Server']), 'java')
  assert.equal(nameOf(['uv', 'run', 'serve.py']), 'uv')
})

test('reads Pss and Rss from smaps_rollup, not the Pss_ breakdown lines, and null for one that is missing', () => {
  const text = '55d0-7ffd ---p 00000000 00:00 0 [rollup]\nRss:              812344 kB\nPss:              790112 kB\nPss_Anon:         700000 kB\nPss_File:          90112 kB\n'
  assert.deepEqual(parseRollup(text), { pssKb: 790112, rssKb: 812344 })
  assert.deepEqual(parseRollup('Rss:  10 kB\n'), { pssKb: null, rssKb: 10 })
})

test('a tree is its root and every descendant, never a sibling, and nothing for a root that is not running', () => {
  const table = new Map([[1, { ppid: 0 }], [10, { ppid: 1 }], [11, { ppid: 10 }], [12, { ppid: 11 }], [13, { ppid: 10 }], [20, { ppid: 1 }]])
  assert.deepEqual(descendants(table, 10).sort((a, b) => a - b), [10, 11, 12, 13])
  assert.deepEqual(descendants(table, 12), [12])
  assert.deepEqual(descendants(table, 99), [])
})

test('adds memory up by process name, and has no PSS total when any process lacks one', () => {
  assert.deepEqual(aggregate([{ name: 'java', pssKb: 400, rssKb: 420 }, { name: 'python3', pssKb: 30, rssKb: 50 }, { name: 'python3', pssKb: 20, rssKb: 40 }]), {
    processes: 3, pssKb: 450, rssKb: 510,
    byName: { java: { count: 1, pssKb: 400, rssKb: 420 }, python3: { count: 2, pssKb: 50, rssKb: 90 } },
  })
  const partial = aggregate([{ name: 'java', pssKb: 400, rssKb: 420 }, { name: 'bash', pssKb: null, rssKb: 3 }])
  assert.deepEqual({ pssKb: partial.pssKb, rssKb: partial.rssKb }, { pssKb: null, rssKb: 423 })
  assert.deepEqual(aggregate([]), { processes: 0, pssKb: null, rssKb: 0, byName: {} })
})

test('roots come from name=pid pairs, leaving out one with no pid', () => {
  assert.deepEqual(parseRoots('jclaw=123,openclaw=,hermes=789'), { jclaw: 123, hermes: 789 })
  assert.deepEqual(parseRoots(undefined), {})
})

test('a live sample counts this process and its child, and nothing for a pid that cannot exist', async () => {
  const child = spawn('sleep', ['30'])
  try {
    await new Promise(r => child.once('spawn', r))
    await new Promise(r => setTimeout(r, 200))
    const s = snapshot({ me: process.pid, gone: 99999999 })
    assert.equal(s.me.processes >= 2, true, JSON.stringify(s.me))
    assert.equal(s.me.byName.sleep?.count, 1, JSON.stringify(s.me.byName))
    assert.equal(s.me.rssKb > 10 * MiB, true, `rss ${s.me.rssKb} kB`)
    if (process.platform === 'linux') assert.equal(s.me.pssKb > 0 && s.me.pssKb <= s.me.rssKb, true, JSON.stringify(s.me))
    else assert.equal(s.me.pssKb, null)
    assert.deepEqual(s.gone, { processes: 0, pssKb: null, rssKb: 0, byName: {} })
  } finally {
    child.kill()
  }
})

// One sample per phase plus three before turns; Hermes's tree has RSS only, OpenClaw's was never seen.
const tree = (procs) => aggregate(procs)
const sample = (phase, jclawMiB, hermesMiB) => ({
  kind: 'memory', phase, at: 0, trees: {
    jclaw: tree([{ name: 'java', pssKb: (jclawMiB - 80) * MiB, rssKb: jclawMiB * MiB }, { name: 'python3', pssKb: 50 * MiB, rssKb: 60 * MiB }, { name: 'python3', pssKb: 30 * MiB, rssKb: 40 * MiB }]),
    openclaw: tree([]),
    hermes: tree([{ name: 'python3', pssKb: null, rssKb: hermesMiB * MiB }]),
  },
})
const samples = [sample('start', 400, 200), sample('run', 420, 210), sample('run', 480, 260), sample('run', 450, 240), sample('end', 470, 250)]

test('summarizes memory after the warm-up, before turns, at the peak and at the end', () => {
  assert.deepEqual(summarizeMemory(samples, H), {
    jclaw: { measure: 'pss', samples: 5, startKb: 400 * MiB, medianKb: 450 * MiB, peakKb: 480 * MiB, endKb: 470 * MiB,
      atPeak: [{ name: 'java', count: 1, kb: 400 * MiB }, { name: 'python3', count: 2, kb: 80 * MiB }], runtime: null },
    openclaw: null,
    hermes: { measure: 'rss', samples: 5, startKb: 200 * MiB, medianKb: 240 * MiB, peakKb: 260 * MiB, endKb: 250 * MiB,
      atPeak: [{ name: 'python3', count: 1, kb: 260 * MiB }], runtime: null },
  })
  assert.equal(summarizeMemory([{ kind: 'turn' }], H), null)
})

// JClaw's JVM as it reports itself before three turns, one read failing; the warm-up and end reads are left out.
const jvm = (used, committed) => ({ kind: 'jvm', heapUsedKb: used * MiB, heapCommittedKb: committed * MiB, heapMaxKb: 2048 * MiB, nonHeapCommittedKb: 150 * MiB })
const withJvm = samples.map((s, i) => ({ ...s, runtime: { jclaw: [jvm(10, 128), jvm(90, 600), jvm(140, 700), { error: 'jclaw: /api/metrics/jvm -> 500' }, jvm(999, 999)][i] } }))

test('summarizes the heap the JVM reports in use and committed before turns, and counts a failed read', () => {
  assert.deepEqual(summarizeMemory(withJvm, H).jclaw.runtime, {
    kind: 'jvm', samples: 2, failed: 1,
    heapUsedMedianKb: 115 * MiB, heapUsedPeakKb: 140 * MiB, heapCommittedMedianKb: 650 * MiB, heapCommittedPeakKb: 700 * MiB,
    heapMaxKb: 2048 * MiB, nonHeapCommittedMedianKb: 150 * MiB,
  })
})

test('a memory change is marked from 20% either way, and only between runs that used the same measure', () => {
  const was = { jclaw: { measure: 'pss', medianKb: 100000, peakKb: 200000 }, hermes: { measure: 'rss', medianKb: 100000, peakKb: 100000 } }
  const now = (median, peak, measure = 'pss') => ({ jclaw: { measure, medianKb: median, peakKb: peak }, hermes: { measure: 'pss', medianKb: 500000, peakKb: 500000 } })
  assert.deepEqual(compareMemory(was, now(119000, 239000)), [])
  assert.deepEqual(compareMemory(was, now(120000, 160000)), [
    { harness: 'jclaw', metric: 'median', was: 100000, now: 120000, share: 0.2 },
    { harness: 'jclaw', metric: 'peak', was: 200000, now: 160000, share: -0.2 },
  ])
  assert.deepEqual(compareMemory(was, now(300000, 300000, 'rss')), [])
  assert.deepEqual(compareMemory(undefined, now(1, 1)), [])
})

function reportOf(lines, history = [], node) {
  const proxy = Object.fromEntries(H.map(h => [h, []]))
  let t = 100000
  for (const h of H) {
    lines.push({ kind: 'turn', harness: h, task: 'question', run: 1, turn: 1, timed: true, correct: true, tSend: t, tFirstText: t + 300, tLast: t + 510 })
    proxy[h].push({ id: t, method: 'POST', path: '/v1/chat/completions', tRecv: t + 20, tLastByte: t + 500, model: 'm', tools: ['web_search'], reasoning_effort: 'none' })
    t += 10000
  }
  const analysis = analyze(lines, proxy)
  const versions = { ...Object.fromEntries(H.map(h => [h, { tag: 'v1' }])), ...(node ? { node } : {}) }
  return { analysis, text: render({ analysis, conformance: { pass: true, results: [] }, versions, turns: lines, history, host: 'testhost' }) }
}
const meta = () => ({ kind: 'meta', at: '2026-11-25T00:00:00.000Z', runs: 1, tasks: ['question'], harnesses: H, model: 'm' })
const section = (text) => text.slice(text.indexOf('## Memory'), text.indexOf('## Wrong'))

test('the report shows each harness\'s memory, names a harness measured in RSS, and says when none was measured', () => {
  const { text } = reportOf([meta(), ...samples])
  assert.equal(section(text), [
    '## Memory', '',
    "Each harness's whole process tree, so JClaw's sidecars count with its JVM, sampled when no request was in flight: after the warm-up, before every turn and at the end. In MiB of PSS (proportional set size: memory shared between processes is split among them), and in RSS for Hermes, whose PSS could not be read.",
    '',
    '| Harness | After the warm-up | Before each turn, median | Peak | At the end | Processes at the peak |',
    '|---|---|---|---|---|---|',
    '| JClaw | 400 | 450 | 480 | 470 | java (400), python3 ×2 (80) |',
    '| OpenClaw | not measured | | | | |',
    '| Hermes | 200 | 240 | 260 | 250 | python3 (260) |',
    '', '',
  ].join('\n'))
  assert.equal(section(reportOf([meta()]).text), '## Memory\n\nNot measured in this run.\n\n')
  assert.equal(section(reportOf([meta(), ...withJvm]).text).split('\n').at(-3),
    "JClaw's JVM, as it reports itself before each turn: 115 MiB of heap in use at the median and 140 at the most, in a heap of 650 MiB committed at the median and 700 at the most, which may grow to 2,048. Outside the heap, 150 MiB is committed for classes and compiled code. The process memory in the table counts the heap pages the JVM holds, whether or not they are in use. 1 of 3 reads failed.")
})

test('the versions table names the Node.js the run used, and leaves the row out when it was not recorded', () => {
  const row = '| Node.js, for the proxy, the driver and OpenClaw | v26.13.1 | – |'
  assert.equal(reportOf([meta()], [], '26.13.1').text.split('\n').includes(row), true)
  assert.equal(reportOf([meta()]).text.includes('| Node.js'), false)
})

test('a run on a new Node.js major starts a new baseline, and a patch release within one is named as a change', () => {
  const { analysis } = reportOf([meta(), ...samples])
  const run = (node) => ({ at: '2026-11-24T15:02:14.405Z', host: 'testhost', model: 'm', summary: analysis.summary,
    versions: { ...Object.fromEntries(H.map(h => [h, { tag: 'v1' }])), node } })
  const changes = (text) => text.slice(text.indexOf('## Changes'), text.indexOf('## Per-task')).split('\n').filter(Boolean)
  assert.deepEqual(changes(reportOf([meta(), ...samples], [run('24.22.3')], '26.13.1').text), [
    '## Changes since earlier runs',
    'This is the first run on testhost with `m` on Node.js 26, so it is the baseline for later runs.',
    'Runs on Node.js 24 stay in the history but are not compared with this one.',
  ])
  assert.equal(changes(reportOf([meta(), ...samples], [run('26.12.0')], '26.13.1').text)[1],
    '**Against the previous run** (2026-11-24 15:02 UTC): versions changed: Node.js 26.12.0 → 26.13.1.')
})

test('the report lists a marked memory change against the previous run, and says when there is none', () => {
  const { analysis } = reportOf([meta(), ...samples])
  const base = (medianKb) => [{ at: '2026-11-24T00:00:00.000Z', host: 'testhost', model: 'm', versions: { jclaw: { tag: 'v0' }, openclaw: { tag: 'v1' }, hermes: { tag: 'v1' } }, summary: analysis.summary,
    memory: { jclaw: { measure: 'pss', medianKb, peakKb: 480 * MiB }, openclaw: null, hermes: { measure: 'rss', medianKb: 240 * MiB, peakKb: 260 * MiB } } }]
  assert.equal(reportOf([meta(), ...samples], base(360 * MiB)).text.includes("\n- JClaw's median memory: 360 → 450 MiB (+25%)\n"), true)
  assert.equal(reportOf([meta(), ...samples], base(450 * MiB)).text.includes("\nNo marked change in memory: no harness's median or peak moved by 20% or more.\n"), true)
})
