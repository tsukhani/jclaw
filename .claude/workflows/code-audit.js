export const meta = {
  name: 'code-audit',
  description: 'Two-axis static audit of app/ Java (clean-code/SOLID/Java-25 structure + code-level performance/memory), adversarially verified',
  whenToUse: 'Run through /code-audit, which builds the unit plan this script takes as args',
  phases: [
    { title: 'Review', detail: 'one reviewer per unit, in lanes — prose two-axis findings' },
    { title: 'Verify', detail: 'one skeptic per unit with findings — prose adversarial adjudication', model: 'opus' },
    { title: 'Extract', detail: 'only for a unit whose adjudicated prose the in-script parser cannot read', model: 'opus' },
    { title: 'Synthesize', detail: 'cross-cutting theming by axis + exec summary', model: 'opus' },
  ],
}

// args is /code-audit's unit plan, { scope, maxLoc, totalFiles, totalLoc, units: [{ id, count, loc, slices: [{ pkg, from, to }] }] },
// plus optional lanes (default 6: the most agents in flight), maxOutputTokens (stop before a unit it cannot afford),
// date and head (provenance on the filed stories), and backlog: false to skip the remediation backlog.
if (!args || !Array.isArray(args.units) || !args.units.length) {
  throw new Error('code-audit takes the unit plan /code-audit builds; pass it as args')
}
const units = args.units
const filesPlanned = units.reduce((a, u) => a + u.count, 0)
const locPlanned = units.reduce((a, u) => a + u.loc, 0)
if (filesPlanned !== args.totalFiles || locPlanned !== args.totalLoc) {
  throw new Error(`unit plan does not add up (${filesPlanned}/${args.totalFiles} files, ${locPlanned}/${args.totalLoc} LOC); pass the builder output unchanged`)
}

// A slice is a range of one package's sorted listing, so the reviewer lists exactly the files the builder counted.
const listCmds = (unit) => unit.slices
  .map((s) => `  /usr/bin/git ls-files -- ':(glob)${s.pkg}/*.java' | sed -n '${s.from},${s.to}p'`)
  .join('\n')
const unitPkgs = (unit) => [...new Set(unit.slices.map((s) => s.pkg.replace(/^app\//, '')))].join('+')
const unitFiles = (unit) => `Your ${unit.count} files (${unit.loc} LOC) are the output of:
${listCmds(unit)}
Run those commands first. If they print a different number of files than ${unit.count}, say so on the SUMMARY line.`

// Open Jira issues the plan builder matched to each unit's files, so a reviewer does not pay to re-find them.
const KNOWN = new Map((args.known || []).map((k) => [k.key, k]))
const knownList = (unit) => {
  const ks = (unit.known || []).map((key) => KNOWN.get(key)).filter(Boolean)
  if (!ks.length) return ''
  const shown = unit.knownTotal ? ` (the ${ks.length} naming most of its files, of ${unit.knownTotal})` : ''
  return `ALREADY FILED — open JCLAW issues that name this unit's files${shown}:
${ks.map((k) => `- ${k.key} (${k.status}${k.epic ? `, epic ${k.epic}` : ''}): ${k.summary}`).join('\n')}`
}
// A defect a filed issue already covers still gets a card, marked covered_by: the backlog step reads that issue's
// description before anything is filed, so a title that merely sounds related cannot hide a bug.
const knownForReview = (unit) => knownList(unit) && `${knownList(unit)}
When a defect you find is one an issue above already plans to fix, still write its FINDING card and set covered_by
to that key; it is filed as evidence on that issue, not as a new story. Spend the review on what they do not cover.`
const knownForVerify = (unit) => knownList(unit) && `${knownList(unit)}
Judge a card with a covered_by key like any other: its defect must be real. Keep covered_by as written; the
backlog step checks it against that issue's description, which you have not seen.`

// ---- shared context baked into every agent so it judges THIS codebase, not generic Java ----
const PROJECT_CONSTRAINTS = `
PROJECT GIVENS — treat these as settled, not as findings (flagging them is a false positive):
- AGENTS.md is already in your context. A finding that recommends what it rules out (a manual IN query instead of
  @BatchSize, StructuredTaskScope instead of utils.TaskScope, Thread.interrupt() for cancellation, deleting a
  "zero call sites" member that a retention Javadoc, @SuppressWarnings("unused") or a reflection test keeps) is wrong.
- If docs/spikes/ holds a verdict on the code you are judging, that verdict stands unless you name evidence it did
  not consider.
- Play 1.x: controllers in app/controllers are intentionally STATIC methods — framework idiom, NOT an SRP/OOD/DIP
  violation. Do not flag "static controller" or "no DI container". Models extend Play's JPA active-record Model:
  public fields, static finders and Model.find()/save()/delete() are idiomatic, not encapsulation violations.
- Compile-time gates already hold: NullAway over the @NullMarked packages, Error Prone MustBeClosed (every
  @MustBeClosed resource reaches try-with-resources or a @MustBeClosed return), and the ArchUnit capability and
  wall-clock rules. A defect those gates would fail the build on cannot exist; one inside a
  @SuppressWarnings("NullAway") or @SuppressWarnings("MustBeClosed") body is fair game.
- Test-only seams (public for testability, *ForTest methods, *TestHooks classes) are deliberate.
- JDK 25: synchronized no longer pins a virtual thread (JEP 491), so synchronized around blocking I/O is not a
  pinning finding by itself.
- Views are Groovy templates (out of scope). JClaw is pre-v1 with no legacy users: dropped backward-compat shims
  are intentional, not missing.
`

// /resilience-audit's OUT_OF_SCOPE names this audit's categories; keep the two lists mirrored.
const OUT_OF_SCOPE = `
OUT OF SCOPE — another audit owns these; do not report them here:
- /resilience-audit: timeouts, retries/backoff/jitter, circuit breakers, graceful degradation, bulkheads,
  backpressure and work-queue bounds, rate limiting, connection-pool sizing and sharing (including an OkHttpClient
  built outside utils.HttpFactories), idempotency and delivery guarantees, dual writes, cache invalidation,
  staleness and stampedes, DB locking and lost updates, query plans and indexes, read/write amplification, tail
  latency, tracing and log correlation.
- /quality: mechanical residue — comment trimming, spelling, inline fully-qualified names, one-line idiom rewrites
  (var, isBlank/strip, getFirst/getLast, Stream.toList, _ for an unused binding), provably dead code, unused
  dependencies.
- /vulnhunt: security. Frontend, test/ and Groovy views are not reviewed.
`

const JAVA25_CHECKLIST = `
JAVA 25 STRUCTURE (axis "clean-code", category "Java25-idiom") — report only where the type design changes:
- records / record patterns for immutable data carriers and destructuring (replacing DTO classes, tuples, Maps-as-structs)
- sealed interfaces/classes + exhaustive pattern-matching switch (incl. guarded patterns) replacing instanceof-chains,
  visitor boilerplate, or an enum type tag switched on in several places
- switch EXPRESSIONS (yield) replacing a statement switch that assigns through fall-through
- text blocks for multi-line SQL/JSON/prompt strings built by concatenation
- Stream gatherers (JEP 485, final in 24) or mapMulti where a hand-rolled stateful loop fights the stream
- ScopedValue (JEP 506, final in 25) in place of a mutable ThreadLocal on a virtual-thread path
- Optional as a return type only (not a field or parameter), and no Optional.get() without a presence check
Only raise an idiom finding when it MEASURABLY improves clarity, safety, or correctness — never style-for-style's-sake.
`

// the prose "finding card" format both reviewer and adjudicator emit, so the extractor can parse deterministically
const FINDING_FORMAT = `
Emit ONE block per finding, in EXACTLY this markdown shape (repeat the block per finding):

### FINDING
- file: <repo-relative path>
- lines: <line or range, e.g. 120-145>
- axis: clean-code | performance
- category: <e.g. SRP-God-class, DIP, ISP, DRY, OCP, GRASP-coupling, GRASP-cohesion, encapsulation, mutability, Java25-idiom, N+1, unbounded-state, memory-leak, resource-leak, blocking-hot-path, redundant-alloc, algorithmic, missed-memoization, lock-contention, VT-hazard>
- principle: <e.g. SOLID:SRP, GRASP:LowCoupling, Java25:RecordPatterns, Perf:UnboundedMap>
- severity: critical | high | medium | low
- covered_by: <the ALREADY FILED key whose issue plans this fix, or none>
- defect: yes | no   (yes when the code misbehaves today on input it can actually receive: lost or corrupted data, a wrong result, a broken feature, a crash. no when the cost is only performance or maintainability)
- title: <one line>
- evidence: <quote the actual smell from the code, concise>
- impact: <concrete consequence — why it matters here>
- fix: <specific actionable change — name the record/sealed type/switch/pattern or the eviction/close/cache change>
`

const VERIFY_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['unitId', 'verified'],
  properties: {
    unitId: { type: 'string' },
    verified: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['file', 'lines', 'axis', 'category', 'principle', 'severity', 'defect', 'coveredBy', 'title', 'evidence', 'impact', 'fix', 'verdict', 'adjudication'],
        properties: {
          file: { type: 'string' },
          lines: { type: 'string' },
          axis: { type: 'string', enum: ['clean-code', 'performance'] },
          category: { type: 'string' },
          principle: { type: 'string' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low', 'none'], description: 'severity AFTER adjudication' },
          defect: { type: 'string', enum: ['yes', 'no'] },
          coveredBy: { type: 'string', description: 'the JCLAW key from covered_by, or "" for none' },
          title: { type: 'string' },
          evidence: { type: 'string' },
          impact: { type: 'string' },
          fix: { type: 'string' },
          verdict: { type: 'string', enum: ['confirmed', 'downgraded', 'rejected'] },
          adjudication: { type: 'string', description: 'why confirmed/downgraded/rejected; name the false-positive reason if rejected' },
        },
      },
    },
  },
}

const SYNTH_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['axis', 'headline', 'themes', 'topIssues', 'quickWins'],
  properties: {
    axis: { type: 'string' },
    headline: { type: 'string', description: '2-3 sentence state-of-the-codebase verdict for this axis' },
    themes: {
      type: 'array',
      description: 'cross-cutting patterns spanning multiple files, most important first',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['theme', 'severity', 'affectedFiles', 'description', 'recommendation'],
        properties: {
          theme: { type: 'string' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          affectedFiles: { type: 'array', items: { type: 'string' } },
          description: { type: 'string' },
          recommendation: { type: 'string' },
        },
      },
    },
    topIssues: {
      type: 'array',
      description: 'the single worst concrete offenders, most severe first',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['title', 'file', 'lines', 'severity', 'why', 'fix'],
        properties: {
          title: { type: 'string' },
          file: { type: 'string' },
          lines: { type: 'string' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          why: { type: 'string' },
          fix: { type: 'string' },
        },
      },
    },
    quickWins: { type: 'array', items: { type: 'string' }, description: 'low-effort high-value fixes' },
  },
}

// The adjudicated cards are a fixed "- key: value" shape, so they parse in code. An extractor agent runs only for a
// unit whose prose does not parse: every agent carries ~27k tokens of injected instructions before it does any work.
const CARD_KEYS = ['file', 'lines', 'axis', 'category', 'principle', 'severity', 'covered_by', 'defect', 'title', 'evidence', 'impact', 'fix', 'verdict', 'final_severity', 'adjudication']
const KEY_LINE = /^\s*[-*]\s+\**([a-z_]+)\**\s*:\**\s?(.*)$/i
const CARD_LIKE = /^\s*[-*]\s+\**(file|verdict)\**\s*:/im
const enumOf = (s) => (s || '').replace(/[*`]/g, '').trim().toLowerCase().split(/[\s(,;]/)[0]

function parseCards(text) {
  const verified = []
  for (const block of text.split(/^#{2,4}\s*FINDING\b.*$/m).slice(1)) {
    const card = {}
    let key = null
    for (const line of block.split('\n')) {
      const m = KEY_LINE.exec(line)
      const k = m && m[1].toLowerCase()
      if (k && CARD_KEYS.includes(k) && !(k in card)) {
        key = k
        card[k] = m[2]
      } else if (key) {
        card[key] += `\n${line}`
      }
    }
    for (const k of Object.keys(card)) card[k] = card[k].replace(/\s*(-{3,}\s*)?$/, '').trim()
    const f = {
      file: card.file, lines: card.lines, axis: enumOf(card.axis),
      category: card.category || '', principle: card.principle || '',
      severity: enumOf(card.final_severity || card.severity), defect: enumOf(card.defect) === 'yes' ? 'yes' : 'no', title: card.title,
      coveredBy: ((card.covered_by || '').match(/JCLAW-\d+/) || [''])[0],
      evidence: card.evidence || '', impact: card.impact || '', fix: card.fix || '',
      verdict: enumOf(card.verdict) || 'confirmed', adjudication: card.adjudication || '',
    }
    if (!f.file || !f.lines || !f.title) return null
    if (!['clean-code', 'performance'].includes(f.axis)) return null
    if (!['critical', 'high', 'medium', 'low', 'none'].includes(f.severity)) return null
    if (!['confirmed', 'downgraded', 'rejected'].includes(f.verdict)) return null
    verified.push(f)
  }
  // Card-shaped lines under no FINDING header mean the format drifted; let the extractor read it.
  return verified.length || !CARD_LIKE.test(text) ? verified : null
}

const reviewPrompt = (unit) => `You are a senior Java architect reviewing part of JClaw (Play 1.x backend, Java 25).
Review ONLY this unit: ${unitPkgs(unit)}.
${unitFiles(unit)}
Read EVERY file fully before judging (use the Read tool on each).
${knownForReview(unit)}

Evaluate against TWO axes:
AXIS 1 "clean-code": OOP/OOD, DRY, GRASP (information-expert, low-coupling, high-cohesion, controller, creator,
  polymorphism, pure-fabrication, indirection, protected-variations), SOLID (SRP/OCP/LSP/ISP/DIP), encapsulation
  & immutability, naming, and Java 25 type structure.
AXIS 2 "performance": what one code path costs in CPU, memory and resources — N+1 / per-iteration DB calls,
  collections/caches/maps (esp. static) that grow without bound or never evict, resource leaks (unclosed streams,
  responses, EventSources, ExecutorServices; listeners or ThreadLocals never removed), blocking calls on hot paths,
  redundant allocation, O(n^2) loops, string concatenation in loops, expensive work repeated where it could be
  memoized, JVM lock contention, and the virtual-thread hazards AGENTS.md lists.
${JAVA25_CHECKLIST}
${PROJECT_CONSTRAINTS}
${OUT_OF_SCOPE}

Rules:
- Cite exact file + line range and quote the actual smell in "evidence". No vague "could be improved".
- Only report findings of severity MEDIUM or above, OR Java25-idiom findings that genuinely improve the code.
- Severity is the consequence if the finding is real, never your confidence in it. A defect that loses or corrupts
  data, takes the database down, or breaks a feature its users rely on is at least MEDIUM, whatever the axis.
- Prefer FEWER, HIGH-CONFIDENCE findings over a long speculative list. Quality over quantity.
- If the unit is clean, say "NO FINDINGS" and nothing else.

${FINDING_FORMAT}

Your plain-text response IS the deliverable — do NOT call any structured-output tool. Just emit the finding blocks
(or "NO FINDINGS"). Begin with a one-line "SUMMARY:" of the unit's overall structural health.`

const verifyPrompt = (unit, reviewText) => `You are a skeptical staff engineer adjudicating a peer's code-review findings for JClaw (Play 1.x, Java 25).
Your job is to REFUTE weak findings, not to agree. Re-read the cited code YOURSELF (Read tool) before ruling.
${unitFiles(unit)}
${knownForVerify(unit)}

${PROJECT_CONSTRAINTS}
${OUT_OF_SCOPE}

For EACH finding in the review below, rule independently and decisively:
- "confirmed": real AND severity right.
- "downgraded": real but over-rated — lower the severity.
- "rejected": false positive — framework-idiomatic (static controllers, Play active-record), already mitigated
  elsewhere, would not actually leak/bottleneck, would not compile, a compile-time gate already rules it out,
  contradicts AGENTS.md or a docs/spikes verdict, out of scope for this audit, evidence misreads the code, or
  style-for-style's-sake. State WHY.
When you doubt a finding is real, reject it. When it is real, rate its consequence and never lower a severity
because you are unsure: a defect that loses or corrupts data, takes the database down, or breaks a feature its users
rely on stays at least medium. Check the defect field too — yes only when the misbehavior happens on reachable input
today. Verify the line numbers and quoted evidence are accurate.

Re-emit EVERY finding using the SAME block format, but ADD three fields to each block:
- verdict: confirmed | downgraded | rejected
- final_severity: critical | high | medium | low | none
- adjudication: <why; name the false-positive reason if rejected>

${FINDING_FORMAT}

If the review said "NO FINDINGS", reply with exactly "NO FINDINGS".
Your plain-text response IS the deliverable — do NOT call any structured-output tool.

REVIEW TO ADJUDICATE:
${reviewText}`

const extractPrompt = (unit, verdictText) => `Transform the adjudicated code-review prose below into structured JSON. Do NOT read any files or use any
tool except the final structured-output call. Parse each "### FINDING" block into one array entry.
Map "final_severity" -> "severity". Map "verdict" -> "verdict". Carry over file/lines/axis/category/principle/
defect/title/evidence/impact/fix/adjudication verbatim; a block with no defect field is "no". Map "covered_by" ->
"coveredBy" as the bare JCLAW key, or "" when it is none or absent. If a block lacks a verdict, treat it as "confirmed" at its
stated severity. Produce {unitId:"${unit.id}", verified:[...]} with one entry per FINDING block. If the text is
"NO FINDINGS" or contains no blocks, return an empty verified array.

ADJUDICATED PROSE:
${verdictText}`

// A dead agent returns null; throwing records the unit as failed instead of counting it clean.
async function auditUnit(unit) {
  const reviewText = await agent(reviewPrompt(unit), { label: `review:${unit.id} ${unitPkgs(unit)}`, phase: 'Review' })
  if (!reviewText) throw new Error(`review:${unit.id} returned nothing`)
  if (/NO FINDINGS/i.test(reviewText) && reviewText.replace(/SUMMARY:.*/i, '').trim().length < 60) {
    return { unitId: unit.id, verified: [], parsedBy: 'clean' }
  }
  const verdictText = await agent(verifyPrompt(unit, reviewText),
    { label: `verify:${unit.id} ${unitPkgs(unit)}`, phase: 'Verify', model: 'opus' })
  if (!verdictText) throw new Error(`verify:${unit.id} returned nothing`)
  if (/^\s*NO FINDINGS\s*$/i.test(verdictText)) return { unitId: unit.id, verified: [], parsedBy: 'clean' }
  const parsed = parseCards(verdictText)
  if (parsed) return { unitId: unit.id, verified: parsed, parsedBy: 'code' }
  const extracted = await agent(extractPrompt(unit, verdictText),
    { label: `extract:${unit.id}`, phase: 'Extract', schema: VERIFY_SCHEMA, model: 'opus', effort: 'low' })
  if (!extracted) throw new Error(`extract:${unit.id} returned nothing`)
  return { unitId: unit.id, verified: extracted.verified, parsedBy: 'agent' }
}

// ---- Review + Verify in lanes: each lane takes the next unit and runs its reviewer, then its skeptic, so at most
// LANES agents are in flight with no barrier between units ----
const LANES = Math.min(Math.max(Math.trunc(Number(args.lanes)) || 6, 1), 16)
// A "+500k" turn target, else args.maxOutputTokens. Either counts what agents write, never the code they read.
const OUTPUT_CAP = budget.total || Math.trunc(Number(args.maxOutputTokens)) || null
log(`Auditing ${units.length} units / ${locPlanned} LOC / ${filesPlanned} files of ${args.scope || 'app'} in ${LANES} lanes`)

const queue = [...units]
const audited = []
const failed = []
let streak = 0
let halted = null
const lane = async () => {
  while (queue.length && !halted) {
    const perUnit = audited.length ? budget.spent() / audited.length : 0
    const left = OUTPUT_CAP ? OUTPUT_CAP - budget.spent() : Infinity
    if (left < perUnit * LANES) {
      halted = `output-token cap: ${Math.round(left / 1000)}k left at ~${Math.round(perUnit / 1000)}k per unit`
      break
    }
    const unit = queue.shift()
    try {
      audited.push(await auditUnit(unit))
      streak = 0
    } catch (e) {
      failed.push({ id: unit.id, pkgs: unitPkgs(unit), reason: String((e && e.message) || e) })
      // As many failures in a row as there are lanes is systemic (a usage limit, an outage), not one bad unit.
      if (++streak >= LANES && !halted) halted = `${streak} units failed in a row; last: ${failed.at(-1).reason}`
    }
    const done = audited.length + failed.length
    if (done % 5 === 0 || done === units.length) {
      log(`${done}/${units.length} units · ${audited.reduce((a, u) => a + u.verified.length, 0)} adjudicated findings · ${failed.length} failed`)
    }
  }
}
await parallel(Array.from({ length: LANES }, () => lane))

audited.sort((a, b) => a.unitId.localeCompare(b.unitId))
const unitById = new Map(units.map((u) => [u.id, u]))
const surviving = audited
  .flatMap((u) => u.verified.map((f) => ({ ...f, unitId: u.unitId, knownKeys: unitById.get(u.unitId).known || [] })))
  .filter((f) => f.verdict !== 'rejected' && f.severity !== 'none')
const cleanFindings = surviving.filter((f) => f.axis === 'clean-code')
const perfFindings = surviving.filter((f) => f.axis === 'performance')

const leftover = units.filter((u) => !audited.some((a) => a.unitId === u.id))
const remainingPlan = leftover.length
  ? {
      scope: args.scope, maxLoc: args.maxLoc, date: args.date, head: args.head,
      openIssuesChecked: args.openIssuesChecked, known: args.known,
      totalFiles: leftover.reduce((a, u) => a + u.count, 0),
      totalLoc: leftover.reduce((a, u) => a + u.loc, 0),
      units: leftover,
    }
  : null

// ---- cross-cutting synthesis, one synthesizer per axis — skipped on a halted run, whose cause would fail it too ----
let synthesis = null
if (halted) {
  log(`HALTED: ${halted}. ${leftover.length} units not audited; resume this run, or rerun remainingPlan`)
} else {
  log(`${surviving.length} findings survived adjudication; synthesizing by axis`)
  phase('Synthesize')
  const [cleanSynth, perfSynth] = await parallel([
    () => agent(
      `Synthesize the CONFIRMED clean-code / OOP / SOLID / GRASP / Java-25-structure findings below into a prioritized
report for JClaw (Play 1.x, Java 25). Find CROSS-CUTTING themes that span multiple files (a smell repeated in
many places is more important than any single instance). Rank by real impact on maintainability. Name the worst
concrete offenders in topIssues. Identify low-effort high-value quickWins. Do NOT read files; use only the data.
${PROJECT_CONSTRAINTS}
CONFIRMED FINDINGS (JSON):
${JSON.stringify(cleanFindings)}
Return the structured synthesis with axis="clean-code".`,
      { label: 'synth:clean-code', phase: 'Synthesize', schema: SYNTH_SCHEMA, model: 'opus' },
    ),
    () => agent(
      `Synthesize the CONFIRMED performance / memory findings below into a prioritized report for JClaw (Play 1.x,
Java 25, OkHttp 5, virtual threads). Group cross-cutting themes (e.g. unbounded state, leak patterns, blocking on
hot paths, per-iteration queries). Rank by real runtime/leak risk. Name the worst concrete offenders in topIssues.
Identify low-effort high-value quickWins. Do NOT read files; use only the data.
${PROJECT_CONSTRAINTS}
CONFIRMED FINDINGS (JSON):
${JSON.stringify(perfFindings)}
Return the structured synthesis with axis="performance".`,
      { label: 'synth:performance', phase: 'Synthesize', schema: SYNTH_SCHEMA, model: 'opus' },
    ),
  ])
  synthesis = { cleanCode: cleanSynth, performance: perfSynth }
}

// ---- remediation backlog: the shared child clusters into epics, writes stories, dedups against Jira, ranks ----
let backlog = null
if (!halted && args.backlog !== false) {
  try {
    backlog = await workflow({ scriptPath: '.claude/workflows/audit-backlog.js' }, {
      source: 'code-audit', date: args.date, head: args.head, scope: args.scope, lanes: LANES,
      findings: surviving, known: args.known || [],
      themes: [synthesis.cleanCode, synthesis.performance].filter(Boolean).flatMap((s) => s.themes || []),
    })
  } catch (e) {
    log(`backlog step failed: ${(e && e.message) || e}; the findings are still returned`)
  }
}

// ---- coverage + severity tally for the final report ----
const sev = (s) => surviving.filter((f) => f.severity === s).length
const stats = {
  scope: args.scope || 'app',
  lanes: LANES,
  outputCap: OUTPUT_CAP,
  halted,
  unitsTotal: units.length,
  unitsAudited: audited.length,
  unitsFailed: failed,
  unitsNotStarted: leftover.length - failed.length,
  filesPlanned,
  locPlanned,
  locReviewed: units.filter((u) => audited.some((a) => a.unitId === u.id)).reduce((a, u) => a + u.loc, 0),
  parsedBy: {
    clean: audited.filter((a) => a.parsedBy === 'clean').length,
    code: audited.filter((a) => a.parsedBy === 'code').length,
    agent: audited.filter((a) => a.parsedBy === 'agent').length,
  },
  outputTokensThisTurn: budget.spent(),
  openIssuesChecked: args.openIssuesChecked ?? null,
  coveredClaims: surviving.filter((f) => f.coveredBy).length,
  survivingFindings: surviving.length,
  defects: surviving.filter((f) => f.defect === 'yes').length,
  rejected: audited.flatMap((u) => u.verified).filter((f) => f.verdict === 'rejected').length,
  bySeverity: { critical: sev('critical'), high: sev('high'), medium: sev('medium'), low: sev('low') },
  byAxis: { cleanCode: cleanFindings.length, performance: perfFindings.length },
}
if (failed.length) log(`WARNING: ${failed.length} units failed: ${failed.map((u) => u.id).join(', ')}`)

return { stats, synthesis, backlog, findings: surviving, remainingPlan }
