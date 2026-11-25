// Joins the driver's turn log with the proxy's request logs and splits every turn into the harness's own time and
// the provider's time. Own time = (first model request − send) + the gaps between rounds less the searches in them
// + (the driver's last byte − the last model byte); provider time = each model request to its last byte.
// Usage: node analyze.mjs [--run-dir dir]   writes analysis.json beside turns.jsonl
import fs from 'node:fs'
import path from 'node:path'
import { parseArgs } from 'node:util'
import { isMain } from './config.mjs'

export const isSearch = r => /web_search|web_fetch/.test(r.path)
export const isModel = r => !isSearch(r) && r.method === 'POST' && /chat|completions|responses|generate/.test(r.path)
export const thinkingOff = r => r.think === false || r.reasoning_effort === 'none'

export const readJsonl = (f) => (fs.existsSync(f) ? fs.readFileSync(f, 'utf8').split('\n').filter(Boolean).map(JSON.parse) : [])

export function quantile(a, p) {
  if (!a.length) return null
  const s = a.slice().sort((x, y) => x - y)
  const i = (s.length - 1) * p
  const lo = Math.floor(i)
  return s[lo] + (s[Math.ceil(i)] - s[lo]) * (i - lo)
}

/** Two-sided Mann–Whitney U test by the normal approximation, corrected for ties, without a continuity correction. */
export function mannWhitney(x, y) {
  const all = [...x.map(v => [v, 0]), ...y.map(v => [v, 1])].sort((a, b) => a[0] - b[0])
  const n1 = x.length
  const n = all.length
  let r1 = 0
  let ties = 0
  for (let i = 0; i < n;) {
    let j = i
    while (j + 1 < n && all[j + 1][0] === all[i][0]) j++
    const rank = (i + j) / 2 + 1
    for (let k = i; k <= j; k++) if (all[k][1] === 0) r1 += rank
    ties += (j - i + 1) ** 3 - (j - i + 1)
    i = j + 1
  }
  const n2 = n - n1
  const sigma = Math.sqrt((n1 * n2 / 12) * (n + 1 - ties / (n * (n - 1))))
  if (!(sigma > 0)) return 1
  const z = Math.abs(r1 - n1 * (n1 + 1) / 2 - n1 * n2 / 2) / sigma
  return Math.min(1, 2 * (1 - normalCdf(z)))
}

// Abramowitz and Stegun 26.2.17, for z ≥ 0, within 7.5e-8.
function normalCdf(z) {
  const t = 1 / (1 + 0.2316419 * z)
  const poly = t * (0.31938153 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
  return 1 - Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI) * poly
}

export function unionMs(iv) {
  const s = iv.filter(([a, b]) => b > a).sort((x, y) => x[0] - y[0])
  let total = 0
  let cur = null
  for (const [a, b] of s) {
    if (!cur || a > cur[1]) { if (cur) total += cur[1] - cur[0]; cur = [a, b] } else cur[1] = Math.max(cur[1], b)
  }
  if (cur) total += cur[1] - cur[0]
  return total
}

export function analyze(lines, proxy) {
  const meta = lines.find(l => l.kind === 'meta')
  const turns = lines.filter(l => l.kind === 'turn')
  const claimed = new Set()
  const rows = []
  for (const t of turns) {
    if (t.error || !t.tSend || !t.tLast) { rows.push({ ...t, text: undefined, bad: t.error ?? 'no timing' }); continue }
    const reqs = (proxy[t.harness] ?? []).filter(r => r.tRecv >= t.tSend - 1 && r.tRecv <= t.tLast)
    reqs.forEach(r => claimed.add(`${t.harness}:${r.id}`))
    // A round offers the harness's tools; a model call with none (a title, a memory capture) is auxiliary.
    const model = reqs.filter(r => isModel(r) && r.tools?.length).sort((a, b) => a.tRecv - b.tRecv)
    const aux = reqs.filter(r => isModel(r) && !r.tools?.length)
    const search = reqs.filter(isSearch)
    const other = reqs.filter(r => !isModel(r) && !isSearch(r))
    const row = {
      harness: t.harness, task: t.task, run: t.run, turn: t.turn, timed: t.timed, correct: t.correct,
      e2e: t.tLast - t.tSend, ttft: t.tFirstText ? t.tFirstText - t.tSend : null,
      firstAny: t.tFirstAny ? t.tFirstAny - t.tSend : null,
      rounds: model.length, searches: search.length, otherReqs: other.map(r => r.path),
      auxCalls: aux.length, auxMs: aux.reduce((s, r) => s + (r.tLastByte - r.tRecv), 0),
      promptTokens: model.reduce((s, r) => s + (r.promptTokens ?? r.promptEval ?? 0), 0),
      cachedTokens: model.reduce((s, r) => s + (r.cachedTokens ?? 0), 0),
      outputTokens: model.reduce((s, r) => s + (r.completionTokens ?? r.eval ?? 0), 0),
      cacheReported: model.some(r => r.cachedTokens != null),
      firstReqBytes: model[0]?.reqBytes ?? null,
      models: [...new Set([...model, ...aux].map(r => r.model))],
      toolsOffered: [...new Set(model.flatMap(r => r.tools ?? []))],
      offeredSets: [...new Set(model.map(r => JSON.stringify([...(r.tools ?? [])].sort())))],
      roundsThinkingOn: model.filter(r => !thinkingOff(r)).length,
      reasoningChunks: [...model, ...aux].reduce((s, r) => s + (r.reasoningChunks ?? 0), 0),
      injected: [...model, ...aux].filter(r => r.injected).length,
      calledPerReply: model.map(r => (r.called ?? []).length),
      calledNames: [...new Set(model.flatMap(r => r.called ?? []))],
      errors: reqs.filter(r => r.error || (r.status && r.status >= 400)).map(r => `${r.path} ${r.status ?? r.error}`),
    }
    if (model.length === 0) { rows.push({ ...row, bad: 'no model request in the turn window' }); continue }
    row.before = model[0].tRecv - t.tSend
    row.after = t.tLast - model.at(-1).tLastByte
    let between = 0
    let searchMs = 0
    let parallelRounds = 0
    for (let i = 0; i + 1 < model.length; i++) {
      const a = model[i].tLastByte
      const b = model[i + 1].tRecv
      const inGap = search.filter(s => s.tRecv >= a && s.tRecv <= b)
      const sMs = unionMs(inGap.map(s => [Math.max(s.tRecv, a), Math.min(s.tLastByte ?? b, b)]))
      between += (b - a) - sMs
      searchMs += sMs
      const sorted = inGap.slice().sort((x, y) => x.tRecv - y.tRecv)
      if (sorted.some((s, j) => j > 0 && s.tRecv < sorted[j - 1].tLastByte)) parallelRounds++
    }
    Object.assign(row, {
      between, searchMs, parallelRounds,
      own: row.before + between + row.after,
      providerMs: model.reduce((s, r) => s + (r.tLastByte - r.tRecv), 0),
    })
    rows.push(row)
  }

  // Requests no turn window claimed: each harness's background calls (setup traffic before its first turn excluded).
  const background = {}
  for (const h of meta.harnesses) {
    const sends = turns.filter(t => t.harness === h && t.tSend).map(t => t.tSend)
    const first = sends.length ? Math.min(...sends) : Infinity
    background[h] = (proxy[h] ?? []).filter(r => r.tRecv >= first - 1 && !claimed.has(`${h}:${r.id}`)).map(r => ({
      path: r.path, model: r.model, ms: r.tLastByte - r.tRecv, tools: r.tools?.length ?? 0,
      promptTokens: r.promptTokens ?? r.promptEval, reasoningChunks: r.reasoningChunks ?? 0,
      thinkingOff: isModel(r) ? thinkingOff(r) : null, status: r.status, error: r.error,
    }))
  }

  const summary = {}
  for (const task of meta.tasks) {
    for (const h of meta.harnesses) {
      const all = rows.filter(r => r.task === task && r.harness === h && r.timed)
      if (!all.length) continue
      const rs = all.filter(r => !r.bad)
      const m = k => rs.map(r => r[k]).filter(v => v != null)
      const q = (k, p) => quantile(m(k), p)
      summary[`${task}|${h}`] = {
        n: rs.length, bad: all.length - rs.length, correct: all.filter(r => r.correct).length, of: all.length,
        ownP50: q('own', 0.5), ownP25: q('own', 0.25), ownP75: q('own', 0.75),
        beforeP50: q('before', 0.5), betweenP50: q('between', 0.5), afterP50: q('after', 0.5),
        e2eP50: q('e2e', 0.5), e2eP25: q('e2e', 0.25), e2eP75: q('e2e', 0.75),
        ttftP50: q('ttft', 0.5), ttftP25: q('ttft', 0.25), ttftP75: q('ttft', 0.75),
        providerP50: q('providerMs', 0.5), roundsP50: q('rounds', 0.5), searchesP50: q('searches', 0.5),
        searchMsP50: q('searchMs', 0.5), promptP50: q('promptTokens', 0.5), outputP50: q('outputTokens', 0.5),
        cacheReported: rs.some(r => r.cacheReported), cachedP50: q('cachedTokens', 0.5),
        auxCalls: rs.reduce((s, r) => s + r.auxCalls, 0), parallelRounds: rs.reduce((s, r) => s + (r.parallelRounds ?? 0), 0),
        firstReplyCalls: rs.map(r => r.calledPerReply[0] ?? 0),
      }
    }
  }
  return { meta, rows, summary, background, memory: summarizeMemory(lines, meta.harnesses), host: summarizeHost(lines) }
}

/**
 * Per harness: memory after the warm-up, the median and the peak over the samples taken before each turn, memory at
 * the end, and the processes at the peak, in PSS where every sample had it and RSS otherwise. Null for a harness whose
 * tree was never seen running, and for a run with no samples at all.
 */
export function summarizeMemory(lines, harnesses) {
  const samples = lines.filter(l => l.kind === 'memory')
  if (!samples.length) return null
  const out = {}
  for (const h of harnesses) {
    const seen = samples.map(s => ({ phase: s.phase, t: s.trees?.[h] })).filter(x => x.t?.processes > 0)
    if (!seen.length) { out[h] = null; continue }
    const measure = seen.every(x => x.t.pssKb != null) ? 'pss' : 'rss'
    const kb = x => x.t[`${measure}Kb`]
    const run = seen.filter(x => x.phase === 'run')
    const peak = (run.length ? run : seen).reduce((a, b) => (kb(b) > kb(a) ? b : a))
    const start = seen.find(x => x.phase === 'start')
    const end = seen.findLast(x => x.phase === 'end')
    out[h] = {
      measure, samples: seen.length,
      startKb: start ? kb(start) : null, medianKb: quantile(run.map(kb), 0.5), peakKb: kb(peak), endKb: end ? kb(end) : null,
      atPeak: Object.entries(peak.t.byName).map(([name, e]) => ({ name, count: e.count, kb: e[`${measure}Kb`] })).sort((a, b) => b.kb - a.kb),
      runtime: summarizeRuntime(samples.filter(s => s.phase === 'run').map(s => s.runtime?.[h])),
    }
  }
  return out
}

/**
 * How busy the host was, from the samples taken with memory: the 1-minute load average, and on Linux the cores that
 * processes outside the harnesses' trees used between two samples, with each user's average over the run. Null for a
 * run with no host samples.
 */
export function summarizeHost(lines) {
  const hs = lines.filter(l => l.kind === 'memory' && l.host).map(l => l.host)
  if (!hs.length) return null
  const load = hs.map(h => h.load1)
  const out = { cpus: hs[0].cpus, load1Median: quantile(load, 0.5), load1Peak: Math.max(...load), otherCoresMedian: null, otherCoresPeak: null, users: [] }
  const ticked = hs.filter(h => h.totalTicks != null)
  const other = []
  const byUser = {}
  let total = 0
  for (let i = 1; i < ticked.length; i++) {
    const [a, b] = [ticked[i - 1], ticked[i]]
    const dt = b.totalTicks - a.totalTicks
    if (dt <= 0) continue
    total += dt
    other.push(Math.max(0, b.busyTicks - a.busyTicks - (b.harnessTicks - a.harnessTicks)) / dt * b.cpus)
    // A reaped process's ticks move to its parent, so a user's sum falls when another user's process reaps one of its.
    for (const [u, t] of Object.entries(b.byUser)) byUser[u] = (byUser[u] ?? 0) + Math.max(0, t - (a.byUser[u] ?? 0))
  }
  if (!other.length) return out
  const users = Object.entries(byUser).map(([user, t]) => ({ user, cores: t / total * out.cpus })).sort((a, b) => b.cores - a.cores)
  return { ...out, otherCoresMedian: quantile(other, 0.5), otherCoresPeak: Math.max(...other), users }
}

// What the harness itself reports about its memory, so far only JClaw's JVM heap; null when it reported nothing.
function summarizeRuntime(reports) {
  const ok = reports.filter(r => r && !r.error)
  if (!ok.length) return null
  const of = k => ok.map(r => r[k]).filter(v => v != null)
  const max = k => (of(k).length ? Math.max(...of(k)) : null)
  return {
    kind: ok[0].kind, samples: ok.length, failed: reports.length - ok.length,
    heapUsedMedianKb: quantile(of('heapUsedKb'), 0.5), heapUsedPeakKb: max('heapUsedKb'),
    heapCommittedMedianKb: quantile(of('heapCommittedKb'), 0.5), heapCommittedPeakKb: max('heapCommittedKb'),
    heapMaxKb: max('heapMaxKb'), nonHeapCommittedMedianKb: quantile(of('nonHeapCommittedKb'), 0.5),
  }
}

export function loadRun(runDir) {
  const lines = readJsonl(path.join(runDir, 'turns.jsonl'))
  const meta = lines.find(l => l.kind === 'meta')
  if (!meta) throw new Error(`${runDir}/turns.jsonl has no meta line`)
  const proxy = Object.fromEntries(meta.harnesses.map(h => [h, readJsonl(path.join(runDir, 'proxy', `proxy-${h}.jsonl`))]))
  return { lines, proxy }
}

if (isMain(import.meta.url)) {
  const { values } = parseArgs({ options: { 'run-dir': { type: 'string', default: process.env.RUN_DIR } } })
  const { lines, proxy } = loadRun(values['run-dir'])
  const result = analyze(lines, proxy)
  fs.writeFileSync(path.join(values['run-dir'], 'analysis.json'), JSON.stringify(result, null, 1))
  const bad = result.rows.filter(r => r.bad).length
  console.log(`analysis: ${result.rows.length} turns, ${bad} without timing, ${Object.keys(result.summary).length} task × harness cells`)
}
