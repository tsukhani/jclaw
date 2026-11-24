// Measures each harness's memory as the whole process tree under the process it was started as, so JClaw's sidecars
// count with its JVM. On Linux it reads PSS, which splits a page shared between processes among them, so a tree's sum
// counts a shared library once. Elsewhere `ps` gives only RSS, which counts a shared page in every process using it.
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

// /proc/<pid>/stat: "pid (comm) state ppid …", where comm may itself hold spaces and parentheses.
export function parseStat(text) {
  const close = text.lastIndexOf(')')
  return { name: text.slice(text.indexOf('(') + 1, close), ppid: Number(text.slice(close + 2).split(' ')[1]) }
}

// The kernel's process name says little: Node calls every main thread "MainThread", and names stop at 15 characters.
// So a process is named for its command line: an interpreter by the script it runs, with that script's folder (each
// JClaw sidecar is a serve.py started from its own folder), anything else by its program, which keeps a title a
// process gave itself.
const INTERPRETER = /^(node|nodejs|python[\d.]*|bun|deno)$/
export function nameOf(argv, cwd = '') {
  const args = argv.length === 1 ? argv[0].split(' ') : argv
  const program = path.basename(args[0] ?? '')
  const script = INTERPRETER.test(program) ? args.slice(1).find(a => a && !a.startsWith('-')) : undefined
  if (!script) return program
  return (script.startsWith('/') || !cwd ? script : path.join(cwd, script)).split('/').slice(-2).join('/')
}

export function parseRollup(text) {
  const kb = (key) => {
    const m = text.match(new RegExp(`^${key}:\\s+(\\d+) kB`, 'm'))
    return m ? Number(m[1]) : null
  }
  return { pssKb: kb('Pss'), rssKb: kb('Rss') }
}

export function descendants(table, root) {
  if (!table.has(root)) return []
  const children = new Map()
  for (const [pid, p] of table) {
    if (!children.has(p.ppid)) children.set(p.ppid, [])
    children.get(p.ppid).push(pid)
  }
  const out = []
  const stack = [root]
  while (stack.length) {
    const pid = stack.pop()
    out.push(pid)
    stack.push(...(children.get(pid) ?? []))
  }
  return out
}

function linuxTable() {
  const table = new Map()
  for (const d of fs.readdirSync('/proc')) {
    if (!/^\d+$/.test(d)) continue
    try {
      table.set(Number(d), parseStat(fs.readFileSync(`/proc/${d}/stat`, 'utf8')))
    } catch {
      // The process exited between the listing and the read.
    }
  }
  return table
}

function linuxName(pid, fallback) {
  try {
    const argv = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0').filter(Boolean)
    let cwd = ''
    try {
      cwd = fs.readlinkSync(`/proc/${pid}/cwd`)
    } catch {
      // Without its folder, a relative script keeps its bare name.
    }
    return argv.length ? nameOf(argv, cwd) : fallback
  } catch {
    return fallback
  }
}

function linuxMemory(pid) {
  try {
    return parseRollup(fs.readFileSync(`/proc/${pid}/smaps_rollup`, 'utf8'))
  } catch {
    try {
      const m = fs.readFileSync(`/proc/${pid}/status`, 'utf8').match(/^VmRSS:\s+(\d+) kB/m)
      return m ? { pssKb: null, rssKb: Number(m[1]) } : null
    } catch {
      return null
    }
  }
}

function psTable() {
  const table = new Map()
  for (const line of execFileSync('ps', ['-A', '-o', 'pid=,ppid=,rss=,args='], { encoding: 'utf8' }).split('\n')) {
    const m = line.trim().match(/^(\d+)\s+(\d+)\s+(\d+)\s+(.+)$/)
    if (m) table.set(Number(m[1]), { ppid: Number(m[2]), rssKb: Number(m[3]), name: nameOf(m[4].split(/\s+/)) })
  }
  return table
}

/** Each root's process tree, its memory added up by process name; a root that is not running gives no processes. */
export function snapshot(roots) {
  const linux = process.platform === 'linux'
  const table = linux ? linuxTable() : psTable()
  const out = {}
  for (const [harness, root] of Object.entries(roots)) {
    const procs = []
    for (const pid of descendants(table, root)) {
      const p = table.get(pid)
      const m = linux ? linuxMemory(pid) : { pssKb: null, rssKb: p.rssKb }
      // Kernel threads have no memory of their own to report.
      if (m && m.rssKb != null) procs.push({ name: linux ? linuxName(pid, p.name) : p.name, ...m })
    }
    out[harness] = aggregate(procs)
  }
  return out
}

export function aggregate(procs) {
  const byName = {}
  for (const p of procs) {
    const e = (byName[p.name] ??= { count: 0, pssKb: 0, rssKb: 0 })
    e.count++
    e.rssKb += p.rssKb
    e.pssKb = e.pssKb == null || p.pssKb == null ? null : e.pssKb + p.pssKb
  }
  const names = Object.values(byName)
  const pss = names.length > 0 && names.every(e => e.pssKb != null)
  return {
    processes: procs.length,
    pssKb: pss ? names.reduce((s, e) => s + e.pssKb, 0) : null,
    rssKb: names.reduce((s, e) => s + e.rssKb, 0),
    byName,
  }
}

/** "jclaw=123,openclaw=456" as { jclaw: 123, openclaw: 456 }, leaving out an entry with no pid. */
export function parseRoots(text) {
  const roots = {}
  for (const part of (text ?? '').split(',')) {
    const [name, pid] = part.split('=')
    if (name && Number(pid) > 0) roots[name.trim()] = Number(pid)
  }
  return roots
}
