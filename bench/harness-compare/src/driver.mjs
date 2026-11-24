// Runs each task in each harness, one harness at a time, and writes one JSON line per turn to turns.jsonl.
// Run k of a task goes through every harness before run k+1, and the harness order rotates each run, so the
// provider's drift over the window falls evenly on all three.
// Usage: node driver.mjs [--runs 10] [--tasks a,b] [--harnesses jclaw,openclaw,hermes] [--out file]
import fs from 'node:fs'
import path from 'node:path'
import { parseArgs } from 'node:util'
import { config, HARNESSES, statusUrl, now } from './config.mjs'
import { TASKS, groundTruth } from './tasks.mjs'
import { ADAPTERS } from './adapters.mjs'

const { values: opt } = parseArgs({
  options: {
    runs: { type: 'string', default: '10' },
    tasks: { type: 'string' },
    harnesses: { type: 'string', default: HARNESSES.join(',') },
    out: { type: 'string', default: path.join(config.runDir, 'turns.jsonl') },
  },
})
const harnesses = opt.harnesses.split(',')
const tasks = opt.tasks ? TASKS.filter(t => opt.tasks.split(',').includes(t.id)) : TASKS
const runs = Number(opt.runs)
const sink = fs.createWriteStream(opt.out, { flags: 'a' })
const write = (row) => sink.write(JSON.stringify(row) + '\n')

// Waits until no harness has a request in flight and none has had one for quietMs, so one harness's background
// calls (a title, a memory capture) never land inside another turn.
async function quiesce() {
  const t0 = now()
  for (;;) {
    const s = await (await fetch(statusUrl())).json()
    const busy = HARNESSES.some(h => s[h].inflight > 0)
    const last = Math.max(...HARNESSES.map(h => s[h].lastActivity))
    if (!busy && s.now - last >= config.quietMs) return now() - t0
    if (now() - t0 > 180000) throw new Error('the proxy never went quiet in 180 s')
    await new Promise(r => setTimeout(r, 250))
  }
}

const truth = await groundTruth()
write({ kind: 'meta', at: new Date().toISOString(), truth, runs, tasks: tasks.map(t => t.id), harnesses, quietMs: config.quietMs, model: config.model })
console.log('ground truth', JSON.stringify(truth))

for (const h of harnesses) await ADAPTERS[h].init?.()

// One untimed turn per harness first, so no harness pays its JIT or connection warm-up inside a measured turn.
for (const h of harnesses) {
  await quiesce()
  const conv = await ADAPTERS[h].newConversation('warmup')
  const r = await ADAPTERS[h].send(conv, TASKS[0].turns[0].text).catch(e => ({ error: String(e?.message ?? e) }))
  write({ kind: 'warmup', harness: h, ...r })
  console.log(`[warmup] ${h}: ${r.error ? 'ERROR ' + r.error : Math.round(r.tLast - r.tSend) + ' ms'}`)
}

for (let run = 1; run <= runs; run++) {
  for (const task of tasks) {
    const order = harnesses.map((_, i) => harnesses[(i + run + tasks.indexOf(task)) % harnesses.length])
    for (const h of order) {
      let conv = null
      for (let i = 0; i < task.turns.length; i++) {
        const turn = task.turns[i]
        const waited = await quiesce()
        let r
        try {
          conv ??= await ADAPTERS[h].newConversation(`${task.id}-${run}`)
          r = await ADAPTERS[h].send(conv, turn.text)
        } catch (e) {
          r = { error: String(e?.message ?? e) }
        }
        const correct = r.error ? false : Boolean(turn.grade(r.text ?? '', truth))
        write({ kind: 'turn', harness: h, task: task.id, run, turn: i + 1, timed: turn.timed, waitedMs: Math.round(waited), correct, ...r })
        const ms = r.tLast && r.tSend ? Math.round(r.tLast - r.tSend) : null
        console.log(`[run ${run}] ${task.id}#${i + 1} ${h}: ${r.error ? 'ERROR ' + r.error : `${ms} ms, ${correct ? 'correct' : 'WRONG'}`}`)
        if (r.error) break
      }
    }
  }
}
await quiesce()
write({ kind: 'end', at: new Date().toISOString() })
await new Promise(r => sink.end(r))
