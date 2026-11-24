// Like-for-like checks on a finished run. An update to any harness can rename a setting and silently fall back to
// its default (thinking back on, a search cache, another tool), which would show as a change that is really a broken
// setup. So every run is checked against what the proxy saw, and a run that fails any check gets no verdict.
// Usage: node conformance.mjs [--run-dir dir]   reads analysis.json, writes conformance.json, exits 1 on a failure
import fs from 'node:fs'
import path from 'node:path'
import { parseArgs } from 'node:util'
import { config, isMain } from './config.mjs'
import { TASKS, WEB_TASKS } from './tasks.mjs'

// A model may choose the offered search on a tool-free task now and then (Hermes's did on 1 of 110 turns,
// 2026-11-24), while a harness that searches by itself would do it on nearly every turn.
export const LIMITS = { maxErrorShare: 0.05, minCorrectShare: 0.8, maxStraySearchShare: 0.25 }

export function check(analysis, { model = config.model, searchTool = config.searchTool, limits = LIMITS } = {}) {
  const { meta, rows, background } = analysis
  const results = []
  const add = (name, harness, failures, detail, notes = []) => results.push({ name, harness, pass: failures.length === 0, detail, failures: failures.slice(0, 5), failureCount: failures.length, notes: notes.slice(0, 5) })
  const where = r => `${r.task}#${r.turn} run ${r.run}`
  const expected = meta.tasks.reduce((s, id) => s + (TASKS.find(t => t.id === id)?.turns.length ?? 0), 0) * meta.runs

  for (const h of meta.harnesses) {
    const hr = rows.filter(r => r.harness === h)
    const timed = hr.filter(r => !r.bad)
    const bg = background[h] ?? []

    const missing = []
    for (const id of meta.tasks) for (let run = 1; run <= meta.runs; run++) if (!hr.some(r => r.task === id && r.run === run)) missing.push(`${id} run ${run}`)
    add('complete', h, missing, `every task ran in all ${meta.runs} runs`)

    // The driver ends a conversation at its first failed turn, so the turns it skipped count as failed too.
    const failed = expected - timed.length
    const failures = hr.filter(r => r.bad).map(r => `${where(r)}: ${r.bad}`)
    if (failures.length < failed) failures.push(`${failed - failures.length} turns skipped or missing`)
    add('errors', h, failed / Math.max(1, expected) > limits.maxErrorShare ? failures : [],
      `${failed} of ${expected} turns failed or were skipped (limit ${Math.round(limits.maxErrorShare * 100)}%)`)

    const wrongModel = [...timed.filter(r => r.models.some(m => m !== model)).map(r => `${where(r)}: ${r.models.join(',')}`),
      ...bg.filter(b => b.model && b.model !== model).map(b => `background ${b.path}: ${b.model}`)]
    add('model', h, wrongModel, `every request names ${model}`)

    const thinkingSetting = timed.filter(r => r.roundsThinkingOn > 0).map(r => `${where(r)}: ${r.roundsThinkingOn} rounds without think:false or reasoning_effort:none`)
    add('thinking setting', h, thinkingSetting, 'every round asks for thinking off')

    const reasoned = [...timed.filter(r => r.reasoningChunks > 0).map(r => `${where(r)}: ${r.reasoningChunks} reasoning chunks`),
      ...bg.filter(b => b.reasoningChunks > 0).map(b => `background ${b.path}: ${b.reasoningChunks} reasoning chunks`)]
    add('no reasoning returned', h, reasoned, 'no response streamed reasoning text')

    const offered = timed.filter(r => r.offeredSets.some(s => s !== JSON.stringify([searchTool]))).map(r => `${where(r)}: offered ${r.offeredSets.join(' ')}`)
    add('tools offered', h, offered, `every round offers only ${searchTool}`)

    const called = timed.filter(r => r.calledNames.some(n => n !== searchTool)).map(r => `${where(r)}: called ${r.calledNames.join(',')}`)
    add('tools called', h, called, `only ${searchTool} was called`)

    const webMissed = timed.filter(r => WEB_TASKS.has(r.task) && r.timed && r.searches === 0).map(r => `${where(r)}: no search reached the proxy`)
    add('searches reach the proxy', h, webMissed, 'every web-task turn searched through the proxy (no cache, no bypass)')

    const toolFree = timed.filter(r => !WEB_TASKS.has(r.task))
    const strayed = toolFree.filter(r => r.searches > 0).map(r => `${where(r)}: ${r.searches} searches`)
    const strayShare = strayed.length / Math.max(1, toolFree.length)
    add('no searches on tool-free tasks', h, strayShare > limits.maxStraySearchShare ? strayed : [],
      `${strayed.length} of ${toolFree.length} tool-free turns searched (limit ${Math.round(limits.maxStraySearchShare * 100)}%)`, strayed)

    const correct = hr.filter(r => r.timed && r.correct).length
    const timedCount = hr.filter(r => r.timed).length
    add('correctness', h, correct / Math.max(1, timedCount) < limits.minCorrectShare ? [`${correct} of ${timedCount} correct`] : [],
      `${correct} of ${timedCount} answers correct (floor ${Math.round(limits.minCorrectShare * 100)}%)`)
  }
  return { pass: results.every(r => r.pass), results }
}

if (isMain(import.meta.url)) {
  const { values } = parseArgs({ options: { 'run-dir': { type: 'string', default: process.env.RUN_DIR } } })
  const analysis = JSON.parse(fs.readFileSync(path.join(values['run-dir'], 'analysis.json'), 'utf8'))
  const verdict = check(analysis)
  fs.writeFileSync(path.join(values['run-dir'], 'conformance.json'), JSON.stringify(verdict, null, 1))
  for (const r of verdict.results.filter(x => !x.pass)) console.log(`FAIL ${r.harness} ${r.name}: ${r.detail}; ${r.failures.join('; ')}`)
  console.log(`conformance: ${verdict.pass ? 'pass' : 'FAIL'} (${verdict.results.filter(r => r.pass).length}/${verdict.results.length} checks)`)
  process.exitCode = verdict.pass ? 0 : 1
}
