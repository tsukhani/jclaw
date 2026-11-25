// Writes report.md for a finished run and, when the run passed conformance, appends its summary to history.jsonl.
// A run is compared with the previous and the first run of the same host, model and Node.js major: a median that moved
// by at least MARKED_SHARE, with the two runs' 25th–75th percentile ranges apart, is a marked change, and a harness whose
// own time moved by ACROSS_SHARE across its tasks, with a rank test over every timed turn agreeing, is worth a look.
// Usage: node report.mjs [--run-dir dir] [--history file]
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { parseArgs } from 'node:util'
import { config, HARNESSES, isMain } from './config.mjs'
import { mannWhitney, readJsonl, quantile } from './analyze.mjs'

export const MARKED_SHARE = 0.2
// Per task, a uniform 20% slowdown of JClaw's own time cleared MARKED_SHARE with the ranges apart in 4 of 20 pairs of
// five identical runs; across tasks, a 10% line caught it in all 20 and marked none of the identical pairs (2026-11-25).
// A 20% line there would catch a real 20% slowdown only half the time.
export const ACROSS_SHARE = 0.1
export const ACROSS_P = 0.01
const N = { jclaw: 'JClaw', openclaw: 'OpenClaw', hermes: 'Hermes' }
const LABEL = {
  question: 'One-line factual question, fresh conversation', followup: 'Follow-up in the same conversation (second turn timed)',
  summary: 'Summary of a 3,009-word passage', reasoning: 'Multi-step reasoning, one right answer', json: 'Reply in a fixed JSON shape',
  fiveturn: 'Five-turn conversation (all five turns timed)', search1: 'One web search', search3: 'Three independent web searches',
}
const f = v => (v == null || Number.isNaN(v) ? '–' : Math.abs(v) >= 10 ? Math.round(v).toLocaleString('en-US') : v.toFixed(1))
const rng = (s, k) => `${f(s[`${k}P50`])} [${f(s[`${k}P25`])}–${f(s[`${k}P75`])}]`
const med = a => quantile(a, 0.5)

export function compare(prev, cur) {
  const changes = []
  for (const [key, now] of Object.entries(cur)) {
    const was = prev[key]
    if (!was) continue
    for (const k of ['own', 'e2e']) {
      const a = was[`${k}P50`]
      const b = now[`${k}P50`]
      if (a == null || b == null || a === 0) continue
      const share = (b - a) / a
      // A median merely leaving the earlier range marked 5 of 48 medians between two runs of identical releases
      // (2026-11-25); requiring the ranges themselves to part marked 1.
      const apart = now[`${k}P75`] < was[`${k}P25`] || now[`${k}P25`] > was[`${k}P75`]
      if (Math.abs(share) >= MARKED_SHARE && apart) changes.push({ key, metric: k, was: a, now: b, share })
    }
  }
  return changes
}

// Memory has no spread across runs to step out of, so a move of MARKED_SHARE alone marks it.
export function compareMemory(prev, cur) {
  const changes = []
  for (const h of HARNESSES) {
    const a = prev?.[h]
    const b = cur?.[h]
    if (!a || !b || a.measure !== b.measure) continue
    for (const k of ['median', 'peak']) {
      const was = a[`${k}Kb`]
      const now = b[`${k}Kb`]
      if (!was || now == null) continue
      const share = (now - was) / was
      if (Math.abs(share) >= MARKED_SHARE) changes.push({ harness: h, metric: k, was, now, share })
    }
  }
  return changes
}

/** Each harness's own time per timed turn, to 0.01 ms: what the rank test across tasks needs from the earlier run. */
export function ownSamplesOf(rows) {
  return Object.fromEntries(HARNESSES.map(h => [h, rows.filter(r => r.harness === h && r.timed && !r.bad && r.own != null).map(r => Math.round(r.own * 100) / 100)]))
}

/**
 * Per harness, the median of its tasks' changes in median own time, marked from ACROSS_SHARE either way when a
 * Mann–Whitney test over every timed turn of the two runs gives p below ACROSS_P. Null when the earlier run's history
 * line keeps no per-turn own times.
 */
export function compareAcrossTasks(base, summary, samples) {
  if (!base.ownSamples) return null
  const out = []
  for (const h of HARNESSES) {
    const was = base.ownSamples[h]
    const now = samples[h]
    if (!was?.length || !now?.length) continue
    // The median of the tasks' changes, not of all turns pooled: a pooled median sits wherever the task mix puts it,
    // and JClaw's pooled end-to-end median moved 21.6% between two identical runs in which 5 of 8 tasks moved under 10%.
    const ratios = Object.keys(summary).filter(k => k.endsWith(`|${h}`) && base.summary?.[k]?.ownP50 > 0 && summary[k].ownP50 != null)
      .map(k => summary[k].ownP50 / base.summary[k].ownP50)
    if (!ratios.length) continue
    const shift = quantile(ratios, 0.5) - 1
    const p = mannWhitney(was, now)
    if (Math.abs(shift) >= ACROSS_SHARE && p < ACROSS_P) out.push({ harness: h, shift, p, tasks: ratios.length, n: [was.length, now.length] })
  }
  return out
}

const cores = v => v.toFixed(1)
const pText = v => (v < 0.0001 ? 'p below 0.0001' : `p = ${v.toFixed(4)}`)
function busyNote(then, now) {
  if (now?.otherCoresMedian == null) return ''
  return then?.otherCoresMedian != null
    ? ` Outside the harnesses the host used a median of ${cores(then.otherCoresMedian)} cores then and ${cores(now.otherCoresMedian)} now, of ${now.cpus}.`
    : ` Outside the harnesses the host used a median of ${cores(now.otherCoresMedian)} of its ${now.cpus} cores; that run did not record it.`
}

const mib = kb => (kb == null ? '–' : Math.round(kb / 1024).toLocaleString('en-US'))
const nodeMajorOf = v => (v?.node ? Number(String(v.node).split('.')[0]) : null)

function e2eNote(S, task) {
  const j = S[`${task}|jclaw`]
  return ['openclaw', 'hermes'].filter(h => S[`${task}|${h}`]).map(h => {
    const o = S[`${task}|${h}`]
    if (j.e2eP75 < o.e2eP25) return `faster than ${N[h]}`
    if (j.e2eP25 > o.e2eP75) return `slower than ${N[h]}`
    return `no measurable difference from ${N[h]}`
  }).join('; ')
}

export function render({ analysis, conformance, versions, turns, history, host }) {
  const { meta, rows, summary: S, background, memory, host: busy } = analysis
  const samplesNow = ownSamplesOf(rows)
  const model = meta.model ?? config.model
  const out = []
  const p = (s = '') => out.push(s)
  // Node.js runs OpenClaw and the proxy and driver that time all three, so a new major version starts a new series.
  const nodeMajor = nodeMajorOf(versions)
  const hostAndModel = history.filter(e => e.host === host && e.model === model)
  const same = hostAndModel.filter(e => nodeMajorOf(e.versions) === nodeMajor)
  const otherNode = [...new Set(hostAndModel.map(e => nodeMajorOf(e.versions)).filter(m => m !== nodeMajor))]
  const prev = same.at(-1)
  const first = same[0]
  p(`# JClaw against OpenClaw and Hermes: ${meta.at.slice(0, 10)} on ${host}`)
  p()
  p(`Run ${meta.at} to ${turns.find(l => l.kind === 'end')?.at ?? '?'}: ${meta.runs} runs of ${meta.tasks.length} tasks in each harness, model \`${model}\` with thinking off, \`${config.searchTool}\` the only tool.`)
  p()
  p('| Harness | Version | Commit |')
  p('|---|---|---|')
  for (const h of HARNESSES) p(`| ${N[h]} | ${versions[h]?.tag ?? '?'} | ${versions[h]?.commit ? `\`${versions[h].commit.slice(0, 12)}\`` : '–'} |`)
  if (versions.hermesPlugin) p(`| Hermes search plugin | web-ollama-cloud | \`${versions.hermesPlugin.slice(0, 12)}\` |`)
  if (versions.node) p(`| Node.js, for the proxy, the driver and OpenClaw | v${versions.node} | – |`)
  p()

  p('## Conformance')
  p()
  if (conformance.pass) p(`All ${conformance.results.length} checks passed, so the comparison below is like for like.`)
  else p(`**No verdict: ${conformance.results.filter(r => !r.pass).length} of ${conformance.results.length} checks failed.** A harness update probably changed a setting the benchmark relies on; fix the setup before reading the numbers below.`)
  p()
  p('| Check | ' + HARNESSES.map(h => N[h]).join(' | ') + ' |')
  p('|---|' + HARNESSES.map(() => '---').join('|') + '|')
  for (const name of [...new Set(conformance.results.map(r => r.name))]) {
    const cells = HARNESSES.map(h => {
      const r = conformance.results.find(x => x.name === name && x.harness === h)
      return !r ? '–' : r.pass ? (r.notes?.length ? `pass (${r.detail}: ${r.notes.join('; ')})` : 'pass') : `**FAIL**: ${r.failures.join('; ')}${r.failureCount > r.failures.length ? ` (+${r.failureCount - r.failures.length} more)` : ''}`
    })
    p(`| ${name} | ${cells.join(' | ')} |`)
  }
  p()

  if (conformance.pass) {
    p('## Verdict')
    p()
    const ratios = meta.tasks.map(t => {
      const others = ['openclaw', 'hermes'].map(h => [h, S[`${t}|${h}`]?.ownP50]).filter(([, v]) => v != null)
      const [bh, bv] = others.reduce((a, b) => (b[1] < a[1] ? b : a))
      return { t, j: S[`${t}|jclaw`].ownP50, bh, bv, ratio: S[`${t}|jclaw`].ownP50 / bv }
    })
    const comparable = ratios.filter(r => r.ratio <= config.bar).length
    p(`JClaw is comparable on ${comparable} of ${ratios.length} tasks: its median own time per turn is no more than ${config.bar.toFixed(2)} of the faster of the other two. Own time leaves out the model's time.`)
    p()
    p(`| Task | JClaw own, median | Faster other, median | Ratio | Comparable (≤ ${config.bar.toFixed(2)}) | End to end |`)
    p('|---|---|---|---|---|---|')
    for (const r of ratios) p(`| ${r.t} | ${f(r.j)} ms | ${f(r.bv)} ms (${N[r.bh]}) | ${r.ratio.toFixed(2)} | ${r.ratio <= config.bar ? 'yes' : '**no**'} | ${e2eNote(S, r.t)} |`)
    p()
    p('End to end: where the 25th–75th percentile ranges overlap, the runs show no measurable difference.')
    p()

    p('## Changes since earlier runs')
    p()
    const onNode = nodeMajor == null ? '' : ` on Node.js ${nodeMajor}`
    if (!prev) p(`This is the first run on ${host} with \`${model}\`${onNode}, so it is the baseline for later runs.`)
    if (otherNode.length) p(`Runs on Node.js ${otherNode.map(m => m ?? 'unrecorded').join(' and ')} stay in the history but are not compared with this one.`)
    if (!prev || otherNode.length) p()
    for (const [label, base] of [['the previous run', prev], ['the first run', first !== prev ? first : null]]) {
      if (!base) continue
      const vs = HARNESSES.filter(h => base.versions?.[h]?.tag !== versions[h]?.tag).map(h => `${N[h]} ${base.versions?.[h]?.tag ?? '?'} → ${versions[h]?.tag}`)
      if (base.versions?.node !== versions.node) vs.push(`Node.js ${base.versions?.node ?? '?'} → ${versions.node ?? '?'}`)
      const marked = compare(base.summary, S)
      p(`**Against ${label}** (${base.at.slice(0, 16).replace('T', ' ')} UTC): ${vs.length ? `versions changed: ${vs.join(', ')}` : 'no version changed'}.`)
      p()
      if (!marked.length) {
        p(`No marked change in time: no median own or end-to-end time moved by ${MARKED_SHARE * 100}% or more with its 25th–75th percentile range apart from that run's.`)
      } else {
        p('| Task | Harness | Measure | Then, median | Now, median | Change |')
        p('|---|---|---|---|---|---|')
        for (const c of marked) { const [t, h] = c.key.split('|'); p(`| ${t} | ${N[h]} | ${c.metric === 'own' ? 'own time' : 'end to end'} | ${f(c.was)} ms | ${f(c.now)} ms | ${c.share > 0 ? '+' : ''}${Math.round(c.share * 100)}% |`) }
      }
      p()
      const across = compareAcrossTasks(base, S, samplesNow)
      if (!across) p("The check across tasks was left out: that run's history line keeps no per-turn own times.")
      else if (!across.length) p(`No harness's own time moved by ${ACROSS_SHARE * 100}% or more across its tasks with a rank test p below ${ACROSS_P}.`)
      else {
        for (const c of across) p(`- **Worth a look:** ${N[c.harness]}'s own time moved ${c.shift > 0 ? '+' : ''}${Math.round(c.shift * 100)}% across its ${c.tasks} tasks, the median of their changes (Mann–Whitney ${pText(c.p)} over ${c.n[0]} turns then and ${c.n[1]} now).${busyNote(base.hostLoad, busy)}`)
        p()
        p(`A move across tasks is a first sign, not proof of a slowdown: its ${ACROSS_SHARE * 100}% line was set on five identical runs from one quiet night, between which it moved by 6.3% at most.`)
      }
      p()
      if (!base.memory || !memory) continue
      const memMarked = compareMemory(base.memory, memory)
      if (!memMarked.length) { p(`No marked change in memory: no harness's median or peak moved by ${MARKED_SHARE * 100}% or more.`); p(); continue }
      // A JVM's process memory follows the heap it holds, which can differ between identical runs, so a flagged change
      // shows the heap in use beside it: that is what says whether the harness needed more.
      for (const c of memMarked) {
        const k = c.metric === 'median' ? 'Median' : 'Peak'
        const then = base.memory[c.harness]?.heap
        const r = memory[c.harness]?.runtime
        const heap = then && r?.kind === 'jvm'
          ? `; its JVM heap in use ${mib(then[`used${k}Kb`])} → ${mib(r[`heapUsed${k}Kb`])} MiB, held ${mib(then[`committed${k}Kb`])} → ${mib(r[`heapCommitted${k}Kb`])} MiB`
          : ''
        p(`- ${N[c.harness]}'s ${c.metric} memory: ${mib(c.was)} → ${mib(c.now)} MiB (${c.share > 0 ? '+' : ''}${Math.round(c.share * 100)}%)${heap}`)
      }
      p()
    }
  }

  p('## Per-task results')
  p()
  p('Medians over the runs, with the 25th–75th percentile in brackets. "Before / between / after" are the three parts of own time.')
  p()
  for (const t of meta.tasks) {
    p(`### ${t}: ${LABEL[t] ?? t}`)
    p()
    p('| Harness | Correct | Own time, ms | before / between / after | End to end, ms | First token, ms | Provider, ms | Rounds | Searches | Prompt tokens | Cached tokens | Output tokens |')
    p('|---|---|---|---|---|---|---|---|---|---|---|---|')
    for (const h of HARNESSES) {
      const s = S[`${t}|${h}`]
      if (!s) continue
      p(`| ${N[h]} | ${s.correct}/${s.of} | ${rng(s, 'own')} | ${f(s.beforeP50)} / ${f(s.betweenP50)} / ${f(s.afterP50)} | ${rng(s, 'e2e')} | ${rng(s, 'ttft')} | ${f(s.providerP50)} | ${f(s.roundsP50)} | ${f(s.searchesP50)} | ${f(s.promptP50)} | ${s.cacheReported ? f(s.cachedP50) : 'not reported'} | ${f(s.outputP50)} |`)
    }
    p()
  }
  const good = rows.filter(r => !r.bad && r.timed)
  if (meta.tasks.includes('fiveturn')) {
    p('### Five-turn conversation, by turn')
    p()
    p('| Turn | ' + HARNESSES.map(h => `${N[h]} own / e2e / prompt`).join(' | ') + ' |')
    p('|---|' + HARNESSES.map(() => '---').join('|') + '|')
    for (let i = 1; i <= 5; i++) {
      p(`| ${i} | ` + HARNESSES.map(h => {
        const rs = good.filter(r => r.harness === h && r.task === 'fiveturn' && r.turn === i)
        return rs.length ? `${f(med(rs.map(r => r.own)))} / ${f(med(rs.map(r => r.e2e)))} / ${f(med(rs.map(r => r.promptTokens)))}` : '–'
      }).join(' | ') + ' |')
    }
    p()
  }
  const web = meta.tasks.filter(t => t.startsWith('search'))
  if (web.length) {
    p('### Web tasks: searches per first reply and concurrency')
    p()
    p('| Task | Harness | Searches the first reply asked for | Rounds with searches at the same time | Search time, median ms |')
    p('|---|---|---|---|---|')
    for (const t of web) for (const h of HARNESSES) {
      const s = S[`${t}|${h}`]
      if (s) p(`| ${t} | ${N[h]} | ${s.firstReplyCalls.join(', ')} | ${s.parallelRounds} of ${s.n} | ${f(s.searchMsP50)} |`)
    }
    p()
  }
  p('### All timed turns')
  p()
  p('| Harness | Turns | Correct | Own time, median ms | before / between / after | End to end, median ms | Auxiliary calls in turns | Background calls after turns | Requests the proxy corrected |')
  p('|---|---|---|---|---|---|---|---|---|')
  for (const h of HARNESSES) {
    const rs = good.filter(r => r.harness === h)
    if (!rs.length) continue
    const bg = background[h] ?? []
    const injected = rows.filter(r => r.harness === h).reduce((s, r) => s + (r.injected ?? 0), 0)
    p(`| ${N[h]} | ${rs.length} | ${rs.filter(r => r.correct).length} | ${f(med(rs.map(r => r.own)))} | ${f(med(rs.map(r => r.before)))} / ${f(med(rs.map(r => r.between)))} / ${f(med(rs.map(r => r.after)))} | ${f(med(rs.map(r => r.e2e)))} | ${rs.reduce((s, r) => s + r.auxCalls, 0)} | ${bg.length} (${f(bg.reduce((s, b) => s + b.ms, 0) / 1000)} s at the provider) | ${injected ? `${injected} given reasoning_effort` : '–'} |`)
  }
  p()
  p('## Memory')
  p()
  if (!memory) p('Not measured in this run.')
  else {
    const measured = HARNESSES.filter(h => memory[h])
    const rssOnly = measured.filter(h => memory[h].measure === 'rss').map(h => N[h])
    const PSS = 'PSS (proportional set size: memory shared between processes is split among them)'
    const unit = !rssOnly.length ? PSS
      : rssOnly.length === measured.length ? 'RSS (resident set size: memory shared between processes counts in each of them; PSS cannot be read here)'
        : `${PSS}, and in RSS for ${rssOnly.join(' and ')}, whose PSS could not be read`
    p(`Each harness's whole process tree, so JClaw's sidecars count with its JVM, sampled when no request was in flight: after the warm-up, before every turn and at the end. In MiB of ${unit}.`)
    p()
    p('| Harness | After the warm-up | Before each turn, median | Peak | At the end | Processes at the peak |')
    p('|---|---|---|---|---|---|')
    for (const h of HARNESSES) {
      const m = memory[h]
      if (!m) { p(`| ${N[h]} | not measured | | | | |`); continue }
      p(`| ${N[h]} | ${mib(m.startKb)} | ${mib(m.medianKb)} | ${mib(m.peakKb)} | ${mib(m.endKb)} | ${m.atPeak.map(x => `${x.name}${x.count > 1 ? ` ×${x.count}` : ''} (${mib(x.kb)})`).join(', ')} |`)
    }
    for (const h of HARNESSES) {
      const r = memory[h]?.runtime
      if (r?.kind !== 'jvm') continue
      p()
      p(`${N[h]}'s JVM, as it reports itself before each turn: ${mib(r.heapUsedMedianKb)} MiB of heap in use at the median and ${mib(r.heapUsedPeakKb)} at the most, in a heap of ${mib(r.heapCommittedMedianKb)} MiB committed at the median and ${mib(r.heapCommittedPeakKb)} at the most, which may grow to ${mib(r.heapMaxKb)}. Outside the heap, ${mib(r.nonHeapCommittedMedianKb)} MiB is committed for classes and compiled code. The process memory in the table counts the heap pages the JVM holds, whether or not they are in use.${r.failed ? ` ${r.failed} of ${r.samples + r.failed} reads failed.` : ''}`)
    }
  }
  p()
  p('## Host during the run')
  p()
  if (!busy) p('Not measured in this run.')
  else {
    const head = `Sampled with memory, when no request was in flight. The 1-minute load average was ${cores(busy.load1Median)} at the median and ${cores(busy.load1Peak)} at the most, on ${busy.cpus} cores.`
    if (busy.otherCoresMedian == null) p(`${head} Which processes kept the host busy is read from Linux's /proc, so it is not known here.`)
    else {
      const users = busy.users.filter(u => u.cores >= 0.05).slice(0, 4).map(u => `${u.user}${u.user === meta.user ? " (this benchmark's proxy and driver)" : ''} ${cores(u.cores)}`)
      p(`${head} Processes outside the three harnesses' trees used ${cores(busy.otherCoresMedian)} cores at the median between consecutive samples and ${cores(busy.otherCoresPeak)} at the most.${users.length ? ` Averaged over the run, by user: ${users.join(', ')}.` : ''}`)
    }
  }
  p()
  const wrong = turns.filter(l => l.kind === 'turn' && l.timed && !l.correct)
  p('## Wrong or failed answers')
  p()
  if (!wrong.length) p('None.')
  for (const w of wrong) p(`- ${N[w.harness]}, ${w.task}#${w.turn}, run ${w.run}: ${w.error ? `error: ${w.error}` : JSON.stringify((w.text ?? '').slice(0, 200))}`)
  p()
  p('Raw data in this run folder: `turns.jsonl` (one line per turn, with the answer), `proxy/proxy-*.jsonl` (one line per request), `analysis.json`, `conformance.json`, `versions.json`.')
  return out.join('\n') + '\n'
}

/** The line history.jsonl keeps for a run that passed conformance: what later runs are compared with. */
export function historyEntry({ analysis, versions, host, runDir }) {
  const keep = ['ownP50', 'ownP25', 'ownP75', 'e2eP50', 'e2eP25', 'e2eP75', 'correct', 'of']
  const summary = Object.fromEntries(Object.entries(analysis.summary).map(([k, v]) => [k, Object.fromEntries(keep.map(x => [x, v[x]]))]))
  const heapOf = r => (r?.kind === 'jvm' ? { usedMedianKb: r.heapUsedMedianKb, usedPeakKb: r.heapUsedPeakKb, committedMedianKb: r.heapCommittedMedianKb, committedPeakKb: r.heapCommittedPeakKb } : undefined)
  const memory = analysis.memory && Object.fromEntries(Object.entries(analysis.memory).map(([h, m]) => [h, m && { measure: m.measure, startKb: m.startKb, medianKb: m.medianKb, peakKb: m.peakKb, endKb: m.endKb, heap: heapOf(m.runtime) }]))
  const b = analysis.host
  const hostLoad = b ? { cpus: b.cpus, load1Median: b.load1Median, load1Peak: b.load1Peak, otherCoresMedian: b.otherCoresMedian, otherCoresPeak: b.otherCoresPeak } : undefined
  return { at: analysis.meta.at, host, model: analysis.meta.model ?? config.model, runDir, versions, summary, memory, ownSamples: ownSamplesOf(analysis.rows), hostLoad }
}

if (isMain(import.meta.url)) {
  const { values } = parseArgs({ options: { 'run-dir': { type: 'string', default: process.env.RUN_DIR }, history: { type: 'string', default: process.env.BENCH_HISTORY } } })
  const dir = values['run-dir']
  const read = n => JSON.parse(fs.readFileSync(path.join(dir, n), 'utf8'))
  const analysis = read('analysis.json')
  const conformance = read('conformance.json')
  const versions = read('versions.json')
  const turns = readJsonl(path.join(dir, 'turns.jsonl'))
  const history = values.history ? readJsonl(values.history) : []
  const host = process.env.BENCH_HOST ?? os.hostname().split('.')[0]
  fs.writeFileSync(path.join(dir, 'report.md'), render({ analysis, conformance, versions, turns, history, host }))
  if (conformance.pass && values.history) fs.appendFileSync(values.history, JSON.stringify(historyEntry({ analysis, versions, host, runDir: dir })) + '\n')
  console.log(`report: ${path.join(dir, 'report.md')}${conformance.pass ? '' : ' (conformance failed: no verdict, not added to the history)'}`)
}
