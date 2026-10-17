// Builds /code-audit's unit plan and attaches the open JCLAW issues that name each unit's files.
//   node .claude/audit/plan.mjs [pathspec ...] [max-loc] [--offline]   → the unit plan (default: app 3000)
//   node .claude/audit/plan.mjs --known-only                           → every open issue, for /resilience-audit
// Jira is read with the PAT the jira-confluence MCP server uses; the token is never printed.
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { basename, dirname } from 'node:path'

const flags = new Set(process.argv.slice(2).filter((a) => a.startsWith('--')))
for (const f of flags) if (!['--offline', '--known-only'].includes(f)) throw new Error(`unknown flag ${f}`)
const argv = process.argv.slice(2).filter((a) => !a.startsWith('--'))
const MAX = /^\d+$/.test(argv.at(-1) || '') ? Number(argv.pop()) : 3000
const pathspecs = argv.length ? argv.map((p) => p.replace(/\/+$/, '')) : ['app']
const scope = pathspecs.join(' ')
const git = (args) => execFileSync('/usr/bin/git', args, { encoding: 'utf8', maxBuffer: 64 << 20 })
const lines = (out) => out.split('\n').filter(Boolean)
const loc = (f) => readFileSync(f, 'utf8').split('\n').length - 1
const auditable = (f) => f.startsWith('app/') && f.endsWith('.java') && !f.startsWith('app/com/aspose/')

async function openIssues() {
  const env = JSON.parse(readFileSync(`${homedir()}/.claude.json`, 'utf8')).mcpServers['jira-confluence'].env
  const base = env.JIRA_URL.replace(/\/+$/, '')
  const out = []
  for (let start = 0; ;) {
    const q = new URLSearchParams({
      jql: 'project = JCLAW AND statusCategory != Done AND issuetype != Epic',
      fields: 'summary,description,status,customfield_10002', maxResults: '100', startAt: String(start),
    })
    const r = await fetch(`${base}/rest/api/2/search?${q}`, { headers: { Authorization: `Bearer ${env.JIRA_PERSONAL_TOKEN}` } })
    if (!r.ok) throw new Error(`Jira search failed: HTTP ${r.status}`)
    const d = await r.json()
    out.push(...d.issues)
    start += d.issues.length
    if (start >= d.total || !d.issues.length) return { issues: out, total: d.total }
  }
}

// An issue names a file by path, or by a class name with two or more capitals: a bare Agent, Task or Config names
// a model nearly every story mentions, and would tie each issue to every unit.
async function knownIssues() {
  const byClass = new Map()
  for (const f of lines(git(['ls-files', '--', 'app'])).filter(auditable)) {
    const c = basename(f, '.java')
    byClass.set(c, [...(byClass.get(c) || []), f])
  }
  const { issues, total } = await openIssues()
  const known = issues.map((i) => {
    const text = `${i.fields.summary}\n${i.fields.description || ''}`
    const files = new Set((text.match(/\bapp\/[\w/]+\.java\b/g) || []).filter((f) => byClass.has(basename(f, '.java'))))
    for (const t of new Set(text.match(/\b[A-Z][A-Za-z0-9]*\b/g) || [])) {
      if ((t.match(/[A-Z]/g) || []).length >= 2) for (const f of byClass.get(t) || []) files.add(f)
    }
    const epic = i.fields.customfield_10002
    return {
      key: i.key, status: i.fields.status?.name || '', epic: (epic && epic.value) || epic || null,
      summary: i.fields.summary, files: [...files].sort(),
    }
  })
  return { known, openTotal: total }
}

if (flags.has('--known-only')) {
  const { known, openTotal } = await knownIssues()
  console.log(JSON.stringify({ openIssuesChecked: openTotal, known: known.map(({ files, ...k }) => k) }))
  process.exit(0)
}

const inScope = new Set(lines(git(['ls-files', '--', ...pathspecs])).filter(auditable))
if (!inScope.size) throw new Error(`no app/ Java sources under ${scope}`)

// A slice is a contiguous range of one package's sorted listing; the reviewer lists its files with that same
// command, so the indices must come from it.
const slices = []
for (const pkg of [...new Set([...inScope].map(dirname))].sort()) {
  const listing = lines(git(['ls-files', '--', `:(glob)${pkg}/*.java`]))
  let run = null
  const flush = () => { if (run) slices.push(run); run = null }
  listing.forEach((f, i) => {
    if (!inScope.has(f)) return flush()
    const n = loc(f)
    if (run && (run.to !== i || run.loc + n > MAX)) flush()
    if (!run) run = { pkg, from: i + 1, to: i, count: 0, loc: 0, files: [] }
    run.to = i + 1
    run.count++
    run.loc += n
    run.files.push(f)
  })
  flush()
}

// Pack slices in package order so neighboring small packages share a unit.
const units = []
for (const s of slices) {
  const last = units.at(-1)
  if (last && last.loc + s.loc <= MAX) {
    last.slices.push(s)
    last.loc += s.loc
    last.count += s.count
  } else {
    units.push({ slices: [s], loc: s.loc, count: s.count })
  }
}

let known = []
let openTotal = null
if (!flags.has('--offline')) ({ known, openTotal } = await knownIssues())
known = known.map((k) => ({ ...k, files: k.files.filter((f) => inScope.has(f)) })).filter((k) => k.files.length)

const KNOWN_PER_UNIT = 12
console.log(JSON.stringify({
  scope, maxLoc: MAX,
  totalFiles: units.reduce((a, u) => a + u.count, 0),
  totalLoc: units.reduce((a, u) => a + u.loc, 0),
  openIssuesChecked: openTotal,
  // Files stay behind: a unit lists its issues by key, and a finding's candidates are its unit's.
  known: known.map(({ files, ...k }) => k),
  units: units.map((u, i) => {
    const files = new Set(u.slices.flatMap((s) => s.files))
    const hits = known.map((k) => ({ key: k.key, n: k.files.filter((f) => files.has(f)).length })).filter((h) => h.n)
      .sort((a, b) => b.n - a.n || a.key.localeCompare(b.key))
    return {
      id: `u${String(i + 1).padStart(2, '0')}`,
      count: u.count,
      loc: u.loc,
      slices: u.slices.map(({ pkg, from, to }) => ({ pkg, from, to })),
      known: hits.slice(0, KNOWN_PER_UNIT).map((h) => h.key),
      ...(hits.length > KNOWN_PER_UNIT ? { knownTotal: hits.length } : {}),
    }
  }),
}))
