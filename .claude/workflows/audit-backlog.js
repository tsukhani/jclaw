export const meta = {
  name: 'audit-backlog',
  description: 'Turn adjudicated audit findings into a ranked, Jira-deduplicated proposal of epics and stories; reads Jira, never writes it',
  whenToUse: 'Called by code-audit.js and resilience-audit.js as their last step; not run on its own',
  phases: [
    { title: 'Survey', detail: 'read the open JCLAW epics', model: 'opus' },
    { title: 'Cluster', detail: 'assign every finding to an open or a proposed epic', model: 'opus' },
    { title: 'Stories', detail: 'per epic: group findings into shippable stories', model: 'opus' },
    { title: 'Dedup', detail: 'per epic: search Jira for issues already covering each story', model: 'opus' },
  ],
}

// args: { source: 'code-audit' | 'resilience-audit', date, head, scope, lanes, findings: [...], themes: [...], known: [...] }
if (!args || !Array.isArray(args.findings)) throw new Error('audit-backlog takes { source, date, head, scope, findings, themes }')
const SOURCE = args.source
const findings = args.findings.map((f, i) => ({ ...f, id: `F${String(i + 1).padStart(3, '0')}` }))
const byId = new Map(findings.map((f) => [f.id, f]))
const LANES = Math.min(Math.max(Math.trunc(Number(args.lanes)) || 6, 1), 16)
if (!findings.length) return { source: SOURCE, epics: [], stories: [], unfiled: [], gaps: [] }

// Open issues, looked up before anything is proposed. /code-audit maps each finding to its unit's issues
// (knownKeys); /resilience-audit has no units, so every open issue is a candidate for its findings.
const KNOWN = new Map((args.known || []).map((k) => [k.key, k]))
const MAPPED = findings.some((f) => Array.isArray(f.knownKeys))
const knownFor = (fs) => {
  const list = MAPPED
    ? [...new Set(fs.flatMap((f) => f.knownKeys || []))].map((k) => KNOWN.get(k)).filter(Boolean)
    : [...KNOWN.values()]
  return list.map(({ key, status, epic, summary }) => ({ key, status, epic, summary }))
}

const READ_ONLY = `Jira is read-only for you: search and read with the mcp__jira-confluence__* tools (load them with ToolSearch),
and never create, edit, comment on, link or transition anything. The operator approves every write later.`
const kindOf = (f) => f.axis || f.concept || ''
const compact = (f) => ({
  id: f.id, severity: f.severity, file: f.file, lines: f.lines, kind: kindOf(f), category: f.category || f.concept || '',
  title: f.title, impact: String(f.impact || f.scenario || '').slice(0, 240),
  ...(MAPPED ? { knownKeys: f.knownKeys || [] } : {}),
  ...(f.coveredBy ? { coveredBy: f.coveredBy } : {}),
})

phase('Survey')
const survey = await agent(
  `List every open epic in Jira project JCLAW: JQL \`project = JCLAW AND issuetype = Epic AND statusCategory != Done\`,
fields summary, description, customfield_10004 (Epic Name). Page with start_at until you have all of them.
Return each with key, summary, epicName and a one-sentence gist of its description (at most 200 characters).
${READ_ONLY}`,
  {
    label: 'jira:epics', phase: 'Survey', model: 'opus', effort: 'low',
    schema: {
      type: 'object', additionalProperties: false, required: ['epics'],
      properties: {
        epics: {
          type: 'array',
          items: {
            type: 'object', additionalProperties: false, required: ['key', 'summary', 'epicName', 'gist'],
            properties: { key: { type: 'string' }, summary: { type: 'string' }, epicName: { type: 'string' }, gist: { type: 'string' } },
          },
        },
      },
    },
  },
)
if (!survey) throw new Error('jira:epics returned nothing; no backlog without the open-epic list')

phase('Cluster')
const cluster = await agent(
  `Assign each adjudicated ${SOURCE} finding below to an epic, for a remediation backlog in Jira project JCLAW.

Rules:
- Every finding id appears exactly once: in one epic's findingIds, or in unfiled with a reason. Leave a finding
  unfiled only when it is low severity, fits no epic, and is not worth a story of its own.
- Prefer an OPEN epic whose subject matches — judge by its gist, not its title alone, and never by a shared fix
  pattern across unrelated subsystems. Set existingKey to its key and say why in fitReason.
- An epic named for an earlier audit wave (e.g. "Static audit 2026-09 ...") takes a finding only when the finding
  extends one of that epic's own open stories.
- Propose a NEW epic (existingKey "") only for a theme no open epic covers. Name it for its subject, never for this
  audit or its date: summary (one line), epicName (at most three words), description (two to four sentences on the
  problem its stories share).
- A finding with coveredBy names the open issue its reviewer believes already plans the fix; it goes to that issue's
  epic when it has one, as does any finding an open issue below already plans to fix${MAPPED ? ' (a finding\'s knownKeys are the issues filed against its files)' : ''}.
- As few epics as cover the themes coherently. Refs are E1, E2, ...

OPEN EPICS: ${JSON.stringify(survey.epics)}
OPEN ISSUES THAT MAY ALREADY COVER FINDINGS: ${JSON.stringify(knownFor(findings))}
THEMES FROM THE AUDIT SYNTHESIS: ${JSON.stringify(args.themes || [])}
FINDINGS: ${JSON.stringify(findings.map(compact))}`,
  {
    label: 'plan:epics', phase: 'Cluster', model: 'opus',
    schema: {
      type: 'object', additionalProperties: false, required: ['epics', 'unfiled'],
      properties: {
        epics: {
          type: 'array',
          items: {
            type: 'object', additionalProperties: false,
            required: ['ref', 'existingKey', 'summary', 'epicName', 'description', 'fitReason', 'findingIds'],
            properties: {
              ref: { type: 'string' }, existingKey: { type: 'string' }, summary: { type: 'string' },
              epicName: { type: 'string' }, description: { type: 'string' }, fitReason: { type: 'string' },
              findingIds: { type: 'array', items: { type: 'string' } },
            },
          },
        },
        unfiled: {
          type: 'array',
          items: {
            type: 'object', additionalProperties: false, required: ['findingId', 'reason'],
            properties: { findingId: { type: 'string' }, reason: { type: 'string' } },
          },
        },
      },
    },
  },
)
if (!cluster) throw new Error('plan:epics returned nothing')

// Every finding lands exactly once; anything the clusterer lost or doubled is reported, never dropped silently.
const gaps = []
const placed = new Set()
const epics = []
for (const e of cluster.epics) {
  const ids = []
  for (const id of e.findingIds) {
    if (!byId.has(id)) gaps.push({ findingId: id, problem: `unknown id in ${e.ref}` })
    else if (placed.has(id)) gaps.push({ findingId: id, problem: `also placed in ${e.ref}; kept in the first epic` })
    else {
      placed.add(id)
      ids.push(id)
    }
  }
  if (ids.length) epics.push({ ...e, findingIds: ids })
}
const unfiled = cluster.unfiled.filter((u) => byId.has(u.findingId) && !placed.has(u.findingId))
unfiled.forEach((u) => placed.add(u.findingId))
for (const f of findings) if (!placed.has(f.id)) gaps.push({ findingId: f.id, problem: 'placed in no epic and not unfiled' })

const STORY_SCHEMA = {
  type: 'object', additionalProperties: false, required: ['stories'],
  properties: {
    stories: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false,
        required: ['ref', 'summary', 'problem', 'fix', 'acceptanceCriteria', 'findingIds', 'labels', 'quickWin', 'afkCandidate', 'afkReason', 'dependsOn', 'coveredBy'],
        properties: {
          ref: { type: 'string' }, summary: { type: 'string' }, problem: { type: 'string' }, fix: { type: 'string' },
          acceptanceCriteria: { type: 'array', items: { type: 'string' } },
          findingIds: { type: 'array', items: { type: 'string' } },
          labels: { type: 'array', items: { type: 'string' } },
          quickWin: { type: 'boolean' }, afkCandidate: { type: 'boolean' }, afkReason: { type: 'string' },
          dependsOn: { type: 'array', items: { type: 'string' } },
          coveredBy: { type: 'string', description: 'key of the open issue that already plans this change, or ""' },
        },
      },
    },
  },
}
const DEDUP_SCHEMA = {
  type: 'object', additionalProperties: false, required: ['verdicts'],
  properties: {
    verdicts: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false, required: ['ref', 'verdict', 'key', 'sitesListed', 'reason'],
        properties: {
          ref: { type: 'string' }, verdict: { type: 'string', enum: ['new', 'extends', 'covered'] },
          key: { type: 'string' }, reason: { type: 'string' },
          sitesListed: { type: 'boolean', description: 'covered only: the issue names these files or sites; false when it covers the defect but not these sites' },
        },
      },
    },
  },
}

const writeStories = (e, mainIds, lowIds) => agent(
  `Write the remediation stories for one epic of a JCLAW backlog, from adjudicated ${SOURCE} findings.
Epic: ${e.existingKey || 'NEW'} — ${e.summary}. ${e.description}

Rules:
- A story is one coherent change one developer ships in one commit with its tests — roughly 1 to 8 story points.
  Group findings that share a fix AND a subsystem. Never merge different fixes because they share a pattern across
  unrelated code; split a theme that spans several subsystems into one story per subsystem.
- A critical finding stands alone unless its fix is literally the same change as another finding's.
- summary: one line in the board's style, "<Subject>: <what changes>" (e.g. "Compression: fix the parser language
  level, unify code detection, and make the fallback observable"), no ticket keys, at most 120 characters.
- problem: one to three sentences — what goes wrong and when. fix: the change, naming the existing mechanism to reuse.
- acceptanceCriteria: two to five testable criteria. At least one names the test that fails before the change and
  passes after (a UnitTest or FunctionalTest, per AGENTS.md's testing rules), or the measurement for a cost finding.
- labels: any of performance, reliability, observability, testing, documentation, safety that fit; [] otherwise.
- quickWin: true when the fix is small and local (about 1 to 2 points).
- afkCandidate: true only when an unattended agent could do it from the description alone — exact files, a clear
  fix, a test that proves it, no product decision, no change to a wire or storage contract. afkReason: one line.
- dependsOn: refs of stories in this epic that must land first.
- Open issues below are already filed. A finding whose coveredBy names one is its reviewer's claim that the issue
  plans the fix: put the findings that share a coveredBy key in one story, set the story's coveredBy to that key, and
  never mix them with findings that carry none. Where an open issue plans a finding's fix that nobody claimed, do the
  same. A covered story is filed as evidence on that issue, not as a new story. Never write a story that re-scopes,
  splits or repeats work an open issue already plans. coveredBy is "" otherwise.
- Refs are S1, S2, ... Every id in FINDINGS appears in exactly one story.
- LOW FINDINGS are not yours to group. Add one to a story above only when its fix is part of that same change;
  leave every other out — the workflow folds them into one cleanup story for this epic. Never write a story of
  Low findings alone.

OPEN ISSUES: ${JSON.stringify(knownFor(e.findingIds.map((id) => byId.get(id))))}
FINDINGS: ${JSON.stringify(mainIds.map((id) => byId.get(id)))}
LOW FINDINGS: ${JSON.stringify(lowIds.map((id) => byId.get(id)))}`,
  { label: `plan:stories:${e.ref}`, phase: 'Stories', schema: STORY_SCHEMA, model: 'opus' },
)

const dedupStories = (e, stories) => agent(
  `For each proposed story below, search Jira project JCLAW for an issue that already covers the same defect: open
issues, and issues resolved in the last 90 days. Query by the classes it touches, e.g. \`project = JCLAW AND
(statusCategory != Done OR resolved >= -90d) AND text ~ "ClassName"\`, plus the story's own key words. Read each
candidate's description, and its comments where they matter, before ruling.
- "new": nothing covers it (key "").
- "covered": an open issue already plans this same change. Its evidence will be added there as a comment instead.
- "extends": an open issue changes the same code for a related reason, or a recently resolved one fixed only part
  of it. The story is created and linked to that key.
A story with a coveredBy key claims that issue plans its fix — a claim made from the issue's title alone. Read the
issue's description: rule "covered" only when it plans this same fix, and set sitesListed true when it names these
files or sites, false when it covers the defect but not these sites, so its comment asks to add them. When it does
not plan this fix, rule "extends" or "new", whatever the title suggests. sitesListed is false for any other verdict.
A cleanup story (ref ending -LOW) gathers independent low-severity findings: rule it "covered" or "extends" only
when one issue already plans most of them, otherwise "new".
${READ_ONLY}

Epic: ${e.existingKey || 'NEW'} — ${e.summary}
STORIES: ${JSON.stringify(stories.map((s) => ({
    ref: s.ref, summary: s.summary, problem: s.problem, coveredBy: s.coveredBy,
    files: [...new Set(s.findingIds.map((id) => byId.get(id)?.file).filter(Boolean))].slice(0, 12),
  })))}`,
  { label: `jira:dedup:${e.ref}`, phase: 'Dedup', schema: DEDUP_SCHEMA, model: 'opus' },
)

// Low findings never get stories of their own: whatever the writer does not attach to a Medium-or-up change is
// folded into one cleanup story per epic, so a full-tree run proposes tens of stories, not hundreds.
// A defect never folds, whatever its severity: the cleanup list is for cost and maintainability, not bugs. Nor
// does a covered finding: it is filed as evidence on its issue once the Jira check confirms the cover.
const isLow = (id) => byId.get(id).severity === 'low' && byId.get(id).defect !== 'yes' && !byId.get(id).coveredBy
const cleanupStory = (e, ids) => {
  const files = new Set(ids.map((id) => String(byId.get(id).file).split(' ')[0])).size
  return {
    ref: `${e.ref}-LOW`, cleanup: true,
    summary: `${(e.epicName || e.summary).slice(0, 60)}: low-severity cleanup, ${ids.length} finding${ids.length > 1 ? 's' : ''} in ${files} file${files > 1 ? 's' : ''}`,
    problem: `${ids.length} low-severity ${SOURCE} finding${ids.length > 1 ? 's' : ''} in this epic's code. None is urgent alone; they are gathered here so they do not crowd the board.`,
    fix: 'Each finding below carries its own fix and stands alone: fix it, or decline it in a comment with the reason. Split one out into its own story when it is worth doing by itself.',
    acceptanceCriteria: [
      'Every finding below is fixed, or declined in a comment that gives the reason.',
      'A fix that changes behavior comes with a test that fails before it and passes after, and every touched class keeps its tests green.',
    ],
    findingIds: ids, labels: [], quickWin: false, afkCandidate: false,
    afkReason: 'a batch of independent changes; split a finding out before handing it to the factory',
    dependsOn: [], coveredBy: '',
  }
}

// Stories, then dedup, per epic in lanes.
const queue = [...epics]
const planned = []
const failedEpics = []
const lane = async () => {
  while (queue.length) {
    const e = queue.shift()
    try {
      const mains = e.findingIds.filter((id) => !isLow(id))
      const lows = e.findingIds.filter(isLow)
      const seen = new Set()
      let stories = []
      if (mains.length) {
        const written = await writeStories(e, mains, lows)
        if (!written) throw new Error(`plan:stories:${e.ref} returned nothing`)
        const own = new Set(e.findingIds)
        stories = written.stories.map((s) => ({
          ...s,
          ref: `${e.ref}-${s.ref}`,
          dependsOn: s.dependsOn.map((d) => `${e.ref}-${d}`),
          findingIds: s.findingIds.filter((id) => own.has(id) && !seen.has(id) && seen.add(id)),
        })).filter((s) => s.findingIds.length)
        // A story of Low findings alone goes back into the fold, whatever the writer was told.
        for (const s of stories.filter((x) => x.findingIds.every(isLow))) s.findingIds.forEach((id) => seen.delete(id))
        stories = stories.filter((s) => !s.findingIds.every(isLow))
        const refs = new Set(stories.map((s) => s.ref))
        for (const s of stories) s.dependsOn = s.dependsOn.filter((d) => refs.has(d))
        for (const id of mains) if (!seen.has(id)) gaps.push({ findingId: id, problem: `in ${e.ref} but in none of its stories` })
      }
      const folded = lows.filter((id) => !seen.has(id))
      if (folded.length) stories.push(cleanupStory(e, folded))
      const dedup = await dedupStories(e, stories)
      const verdicts = new Map((dedup?.verdicts || []).map((v) => [`${e.ref}-${v.ref}`, v]).concat(
        (dedup?.verdicts || []).map((v) => [v.ref, v])))
      planned.push({
        epic: e,
        stories: stories.map((s) => ({
          ...s,
          jira: verdicts.get(s.ref) || { verdict: 'new', key: '', sitesListed: false, reason: dedup ? 'not ruled on; treated as new' : 'dedup failed; treated as new' },
        })),
      })
    } catch (err) {
      failedEpics.push({ ref: e.ref, summary: e.summary, reason: String((err && err.message) || err) })
      for (const id of e.findingIds) gaps.push({ findingId: id, problem: `epic ${e.ref} failed` })
    }
  }
}
await parallel(Array.from({ length: Math.min(LANES, epics.length) }, () => lane))

// ---- rank in code, so the order is reproducible and its reasons are visible ----
const WEIGHT = { critical: 8, high: 4, medium: 2, low: 1, none: 0 }
const PRIORITY = { critical: 'Highest', high: 'High', medium: 'Medium', low: 'Low' }
const ALLOWED_LABELS = new Set(['performance', 'reliability', 'observability', 'testing', 'documentation', 'safety'])
// The writer's AFK mark is its own judgment, and it grades generously (77 of 96 on the first run). Keep the mark
// only on a small, local, non-structural change; the operator still picks which marked stories get `afk`.
const STRUCTURAL = /SRP|God|DIP|ISP|OCP|LSP|GRASP|encapsulation|Java25/i
const afkGate = (s, fs) => {
  if (!s.afkCandidate) return { afkCandidate: false, afkReason: s.afkReason }
  const files = new Set(fs.map((f) => String(f.file).split(' ')[0])).size
  const why = !s.quickWin ? 'not a quick win'
    : files > 2 ? `touches ${files} files`
      : fs.some((f) => STRUCTURAL.test(f.category || '')) ? 'a structural refactor' : null
  return why ? { afkCandidate: false, afkReason: `writer marked it; withheld: ${why}` } : { afkCandidate: true, afkReason: s.afkReason }
}
let stories = planned.flatMap(({ epic, stories }) => stories.map((s) => {
  const fs = s.findingIds.map((id) => byId.get(id))
  const severity = fs.reduce((top, f) => (WEIGHT[f.severity] > WEIGHT[top] ? f.severity : top), 'low')
  return {
    ...s, ...afkGate(s, fs), epicRef: epic.ref, severity, priority: PRIORITY[severity],
    // A cleanup list ranks on its severity alone, never its length, and a defect outranks cleanup at equal severity.
    score: WEIGHT[severity] * 100 + (s.cleanup ? 0 : fs.reduce((a, f) => a + WEIGHT[f.severity], 0) * 2)
      + (fs.some((f) => f.defect === 'yes') ? 50 : 0) + (s.quickWin ? 3 : 0),
    labels: [...new Set(['audit', SOURCE, `sev-${severity}`, ...s.labels.filter((l) => ALLOWED_LABELS.has(l))])],
  }
}))
stories.sort((a, b) => b.score - a.score || a.ref.localeCompare(b.ref))
// A story never ranks above one it depends on.
for (let pass = 0; pass < stories.length; pass++) {
  let moved = false
  stories.forEach((s, i) => {
    for (const dep of s.dependsOn) {
      const j = stories.findIndex((x) => x.ref === dep)
      if (j > i) {
        stories.splice(i, 0, stories.splice(j, 1)[0])
        moved = true
      }
    }
  })
  if (!moved) break
}
stories = stories.map((s, i) => ({ ...s, rank: i + 1 }))

// ---- Jira wiki markup, rendered here so every description has the same shape ----
const inline = (s) => String(s ?? '').replace(/\s+/g, ' ').replace(/([{}[\]|])/g, '\\$1').trim()
const block = (s) => String(s ?? '').replace(/^```.*$/gm, '').replace(/\{noformat\}/g, '').trim()
// h4 per finding, not a # list: a {noformat} block inside a list item ends the list and restarts its numbering.
const renderFinding = (f) => [
  `h4. {{${f.file}:${f.lines}}} — ${f.severity}${f.defect === 'yes' ? ' defect' : ''} — ${inline(f.title)}`,
  f.scenario ? `*Scenario:* ${inline(f.scenario)}` : `*Impact:* ${inline(f.impact)}`,
  f.existingProtection ? `*Existing protection:* ${inline(f.existingProtection)}` : '',
  f.evidence ? `{noformat}\n${block(f.evidence)}\n{noformat}` : '',
  f.adjudication ? `_Adjudication (${f.verdict}):_ ${inline(f.adjudication)}` : '',
].filter(Boolean).join('\n')
// A cleanup finding stands alone, so it carries its own fix and leaves the evidence to file:lines.
const renderCompact = (f) => [
  `h4. {{${f.file}:${f.lines}}} — ${inline(f.title)}`,
  `*Impact:* ${inline(f.impact || f.scenario)}`,
  `*Fix:* ${inline(f.fix)}`,
].join('\n')
// Jira Server refuses a text field over 32,767 characters by default.
const DESCRIPTION_MAX = 30000
const provenance = (ids) => `_Found by /${SOURCE} on ${args.date || 'an unstamped date'} at ${args.head || 'an unstamped commit'}` +
  ` (scope: ${inline(args.scope || 'app')}); every finding survived an independent skeptic. Finding ids: ${ids.join(', ')}._`
const storyDescription = (s) => {
  const head = ['h3. Problem', inline(s.problem), '', 'h3. Findings'].join('\n')
  const tail = ['', 'h3. Fix', inline(s.fix), '',
    'h3. Acceptance criteria', s.acceptanceCriteria.map((c) => `* ${inline(c)}`).join('\n'), '',
    '----', provenance(s.findingIds)].join('\n')
  const render = s.cleanup ? renderCompact : renderFinding
  const shown = []
  let room = DESCRIPTION_MAX - head.length - tail.length - 200
  for (const id of s.findingIds) {
    const text = render(byId.get(id))
    if (text.length + 2 > room) break
    shown.push(text)
    room -= text.length + 2
  }
  const omitted = s.findingIds.slice(shown.length)
  const more = omitted.length ? `\n\n_${omitted.length} more finding${omitted.length > 1 ? 's' : ''} did not fit Jira's field limit: ${omitted.join(', ')}, in the saved audit result._` : ''
  return `${head}\n${shown.join('\n\n')}${more}\n${tail}`
}

const epicRows = planned.filter(({ epic }) => stories.some((s) => s.epicRef === epic.ref)).map(({ epic }) => {
  const own = stories.filter((s) => s.epicRef === epic.ref)
  return {
    ref: epic.ref, key: epic.existingKey || null, summary: epic.summary, epicName: epic.epicName,
    fitReason: epic.fitReason,
    description: epic.existingKey ? null : `${inline(epic.description)}\n\n----\n_Proposed by /${SOURCE} on ${args.date || 'an unstamped date'}._`,
    bestRank: Math.min(...own.map((s) => s.rank)),
    storyRefs: own.map((s) => s.ref),
  }
}).sort((a, b) => a.bestRank - b.bestRank).map((e, i) => ({ rank: i + 1, ...e }))

return {
  source: SOURCE, date: args.date || null, head: args.head || null, scope: args.scope || 'app',
  epics: epicRows,
  stories: stories.map((s) => ({
    rank: s.rank, ref: s.ref, epicRef: s.epicRef, summary: s.summary, priority: s.priority, severity: s.severity,
    labels: s.labels, quickWin: s.quickWin, afkCandidate: s.afkCandidate, afkReason: s.afkReason, cleanup: !!s.cleanup,
    dependsOn: s.dependsOn, jira: s.jira, findingIds: s.findingIds, description: storyDescription(s),
  })),
  unfiled: unfiled.map((u) => ({ ...u, title: byId.get(u.findingId).title, severity: byId.get(u.findingId).severity })),
  failedEpics,
  gaps,
}
