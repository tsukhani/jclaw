// The analysis and the conformance checks on a recorded run built by hand, with timings whose split is known.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { analyze, unionMs } from '../src/analyze.mjs'
import { check } from '../src/conformance.mjs'
import { compare } from '../src/report.mjs'

const MODEL = 'deepseek-v4.1-flash'
const H = ['jclaw', 'openclaw', 'hermes']
let id = 0
const round = (h, tRecv, tLastByte, extra = {}) => ({ id: ++id, harness: h, method: 'POST', path: '/v1/chat/completions', tRecv, tReqEnd: tRecv + 1, tFirstByte: tRecv + 50, tLastByte, model: MODEL, tools: ['web_search'], reasoning_effort: 'none', promptTokens: 3000, cachedTokens: 2900, completionTokens: 20, ...extra })
const search = (h, tRecv, tLastByte) => ({ id: ++id, harness: h, method: 'POST', path: '/api/web_search', tRecv, tReqEnd: tRecv + 1, tFirstByte: tRecv + 100, tLastByte })

// Two runs of `question` (one round) and `search1` (a round, a search, a round) in each harness, spaced 10 s apart.
function cleanRun() {
  const lines = [{ kind: 'meta', at: '2026-11-24T00:00:00.000Z', runs: 2, tasks: ['question', 'search1'], harnesses: H, model: MODEL }]
  const proxy = Object.fromEntries(H.map(h => [h, []]))
  let t = 100000
  for (let run = 1; run <= 2; run++) {
    for (const h of H) {
      lines.push({ kind: 'turn', harness: h, task: 'question', run, turn: 1, timed: true, correct: true, tSend: t, tFirstText: t + 300, tLast: t + 510 })
      proxy[h].push(round(h, t + 20, t + 500))
      t += 10000
      lines.push({ kind: 'turn', harness: h, task: 'search1', run, turn: 1, timed: true, correct: true, tSend: t, tFirstText: t + 1900, tLast: t + 2105 })
      proxy[h].push(round(h, t + 20, t + 300, { called: ['web_search'] }), search(h, t + 310, t + 800), round(h, t + 810, t + 2100))
      t += 10000
    }
  }
  return { lines, proxy }
}
const failing = (lines, proxy, limits) => check(analyze(lines, proxy), { model: MODEL, ...(limits ? { limits } : {}) }).results.filter(r => !r.pass).map(r => `${r.harness}:${r.name}`)

test('splits a one-round turn into before, after and provider time', () => {
  const { lines, proxy } = cleanRun()
  const r = analyze(lines, proxy).rows.find(x => x.harness === 'jclaw' && x.task === 'question' && x.run === 1)
  assert.deepEqual({ before: r.before, between: r.between, after: r.after, own: r.own, providerMs: r.providerMs, rounds: r.rounds, searches: r.searches, ttft: r.ttft },
    { before: 20, between: 0, after: 10, own: 30, providerMs: 480, rounds: 1, searches: 0, ttft: 300 })
})

test('takes the search out of the time between rounds', () => {
  const { lines, proxy } = cleanRun()
  const r = analyze(lines, proxy).rows.find(x => x.harness === 'hermes' && x.task === 'search1' && x.run === 2)
  assert.deepEqual({ before: r.before, between: r.between, searchMs: r.searchMs, after: r.after, own: r.own, rounds: r.rounds, searches: r.searches, calledPerReply: r.calledPerReply },
    { before: 20, between: 20, searchMs: 490, after: 5, own: 45, rounds: 2, searches: 1, calledPerReply: [1, 0] })
})

test('counts a round as parallel only when its searches overlap', () => {
  assert.equal(unionMs([[0, 100], [50, 150]]), 150)
  assert.equal(unionMs([[0, 100], [200, 300]]), 200)
  assert.equal(unionMs([]), 0)
  const { lines, proxy } = cleanRun()
  const t = lines.find(l => l.harness === 'jclaw' && l.task === 'search1' && l.run === 1).tSend
  proxy.jclaw.push(search('jclaw', t + 320, t + 790))
  const rows = analyze(lines, proxy).rows
  assert.equal(rows.find(x => x.harness === 'jclaw' && x.task === 'search1' && x.run === 1).parallelRounds, 1)
  assert.equal(rows.find(x => x.harness === 'jclaw' && x.task === 'search1' && x.run === 2).parallelRounds, 0)
})

test('counts a no-tool call in a turn as auxiliary and one after it as background', () => {
  const { lines, proxy } = cleanRun()
  const t = lines.find(l => l.harness === 'hermes' && l.task === 'question' && l.run === 1).tSend
  proxy.hermes.push(round('hermes', t + 21, t + 400, { tools: undefined }), round('hermes', t + 600, t + 1300, { tools: undefined }))
  const a = analyze(lines, proxy)
  const r = a.rows.find(x => x.harness === 'hermes' && x.task === 'question' && x.run === 1)
  assert.deepEqual({ rounds: r.rounds, auxCalls: r.auxCalls, own: r.own }, { rounds: 1, auxCalls: 1, own: 30 })
  assert.deepEqual(a.background.hermes.map(b => b.ms), [700])
})

test('a clean run passes every check', () => {
  const { lines, proxy } = cleanRun()
  const v = check(analyze(lines, proxy), { model: MODEL })
  assert.deepEqual({ pass: v.pass, checks: v.results.length, failing: v.results.filter(r => !r.pass).length }, { pass: true, checks: 30, failing: 0 })
})

const faults = [
  ['another model', p => { p.openclaw[0].model = 'kimi-k3' }, ['openclaw:model']],
  ['a background call to another model', p => { p.jclaw.push(round('jclaw', 999000, 999500, { tools: undefined, model: 'kimi-k3' })) }, ['jclaw:model']],
  ['a round without thinking off', p => { delete p.hermes[0].reasoning_effort }, ['hermes:thinking setting']],
  ['reasoning streamed back', p => { p.jclaw[1].reasoningChunks = 4 }, ['jclaw:no reasoning returned']],
  ['a second tool offered', p => { p.openclaw[0].tools = ['web_search', 'exec'] }, ['openclaw:tools offered']],
  ['another tool called', p => { p.hermes[1].called = ['web_fetch'] }, ['hermes:tools called']],
  ['a search served from a cache', p => { const s = p.jclaw.find(x => x.path === '/api/web_search'); p.jclaw = p.jclaw.filter(x => x !== s) }, ['jclaw:searches reach the proxy']],
  ['a search on a tool-free task', p => { const q = p.openclaw[0]; p.openclaw.push(search('openclaw', q.tRecv + 100, q.tRecv + 200)) }, ['openclaw:no searches on tool-free tasks']],
]
for (const [name, mutate, expected] of faults) {
  test(`fails only its own check on ${name}`, () => {
    const { lines, proxy } = cleanRun()
    mutate(proxy)
    assert.deepEqual(failing(lines, proxy), expected)
  })
}

test('passes a stray search at its limit, naming the turn, and fails one over it', () => {
  const { lines, proxy } = cleanRun()
  const q = proxy.openclaw[0]
  proxy.openclaw.push(search('openclaw', q.tRecv + 100, q.tRecv + 200))
  const at = check(analyze(lines, proxy), { model: MODEL, limits: { maxErrorShare: 0.05, minCorrectShare: 0.8, maxStraySearchShare: 0.5 } })
  const r = at.results.find(x => x.harness === 'openclaw' && x.name === 'no searches on tool-free tasks')
  assert.deepEqual({ pass: r.pass, detail: r.detail, notes: r.notes }, { pass: true, detail: '1 of 2 tool-free turns searched (limit 50%)', notes: ['question#1 run 1: 1 searches'] })
  const over = check(analyze(lines, proxy), { model: MODEL, limits: { maxErrorShare: 0.05, minCorrectShare: 0.8, maxStraySearchShare: 0.49 } })
  assert.equal(over.results.find(x => x.harness === 'openclaw' && x.name === 'no searches on tool-free tasks').pass, false)
})

test('fails correctness below the floor and passes at it', () => {
  const { lines, proxy } = cleanRun()
  const turns = lines.filter(l => l.kind === 'turn' && l.harness === 'jclaw')
  const limits = { maxErrorShare: 0.05, minCorrectShare: 0.75 }
  turns[0].correct = false
  assert.deepEqual(failing(lines, proxy, limits), [])
  turns[1].correct = false
  assert.deepEqual(failing(lines, proxy, limits), ['jclaw:correctness'])
})

test('fails a harness with a missing run, and the turns it lost count as errors', () => {
  const { lines, proxy } = cleanRun()
  const kept = lines.filter(l => !(l.kind === 'turn' && l.harness === 'hermes' && l.task === 'search1' && l.run === 2))
  assert.deepEqual(failing(kept, proxy), ['hermes:complete', 'hermes:errors'])
})

test('fails the error check above its limit and passes at it', () => {
  const { lines, proxy } = cleanRun()
  const limits = { maxErrorShare: 0.25, minCorrectShare: 0.5 }
  const turns = lines.filter(l => l.kind === 'turn' && l.harness === 'openclaw')
  Object.assign(turns[0], { error: 'openclaw: 500', correct: false })
  assert.deepEqual(failing(lines, proxy, limits), [])
  Object.assign(turns[1], { error: 'openclaw: 500', correct: false })
  assert.deepEqual(failing(lines, proxy, limits), ['openclaw:errors'])
})

test('marks a change only when it is 20% or more and outside the earlier range', () => {
  const was = { 'question|jclaw': { ownP50: 100, ownP25: 90, ownP75: 110, e2eP50: 700, e2eP25: 600, e2eP75: 800 } }
  const at = (own, e2e) => ({ 'question|jclaw': { ownP50: own, e2eP50: e2e } })
  assert.deepEqual(compare(was, at(121, 700)).map(c => [c.metric, Math.round(c.share * 100)]), [['own', 21]])
  assert.deepEqual(compare(was, at(119, 700)), [])
  assert.deepEqual(compare(was, at(100, 840)).map(c => [c.metric, Math.round(c.share * 100)]), [['e2e', 20]])
  assert.deepEqual(compare(was, at(100, 780)), [])
})
