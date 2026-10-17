export const meta = {
  name: 'resilience-audit',
  description: 'Static audit of how app/ behaves under failing dependencies, duplicate delivery, concurrent writers and load — one reviewer, skeptic and extractor per concept group, then a concept scorecard',
  whenToUse: 'Run through /resilience-audit; args.groups may name a subset of group ids',
  phases: [
    { title: 'Review', detail: 'one reviewer per concept group — prose findings + concept status' },
    { title: 'Verify', detail: 'one skeptic per group — prose adversarial adjudication', model: 'opus' },
    { title: 'Extract', detail: 'read-free transform of adjudicated prose into structured findings', model: 'opus' },
    { title: 'Synthesize', detail: 'concept scorecard, themes and measurement hand-offs', model: 'opus' },
  ],
}

// The 30 concepts the scorecard reports on. Each is reviewed by the groups that list it, or is not applicable.
const CONCEPTS = {
  'p95-p99': 'p95 / p99 latency',
  'tail-amplification': 'Tail latency amplification',
  'connection-pooling': 'Connection pooling',
  'backpressure': 'Backpressure',
  'rate-limiting': 'Rate limiting',
  'idempotency-keys': 'Idempotency keys',
  'retry-backoff-jitter': 'Retry with backoff + jitter',
  'circuit-breakers': 'Circuit breakers',
  'bulkheads': 'Bulkheads',
  'timeouts': 'Timeouts',
  'query-plans': 'Reading query plans',
  'index-selectivity': 'Index selectivity',
  'write-amplification': 'Write amplification',
  'read-amplification': 'Read amplification',
  'consistent-hashing': 'Consistent hashing',
  'sharding-keys': 'Sharding keys',
  'hot-partitions': 'Hot partitions',
  'replication-lag': 'Replication lag',
  'strong-vs-eventual': 'Strong vs eventual consistency',
  'optimistic-locking': 'Optimistic locking',
  'pessimistic-locking': 'Pessimistic locking',
  'change-data-capture': 'Change Data Capture',
  'outbox': 'Outbox pattern',
  'dead-letter': 'Dead-letter queues',
  'at-least-once': 'At-least-once delivery',
  'cache-invalidation': 'Cache invalidation',
  'cache-stampede': 'Cache stampede protection',
  'graceful-degradation': 'Graceful degradation',
  'slo-error-budget': 'SLOs / error budgets',
  'correlation-tracing': 'Correlation IDs + tracing',
}

const NOT_APPLICABLE = [
  { concept: 'consistent-hashing', reason: 'One JVM owns every conversation, queue and cache; there is no key space spread across nodes.' },
  { concept: 'sharding-keys', reason: 'One embedded H2 file holds every table; nothing is partitioned.' },
  { concept: 'replication-lag', reason: 'H2 has no replicas. The single-node analog, a cache or derived index lagging the database, is reviewed under strong-vs-eventual and cache-invalidation.' },
]

const GROUPS = [
  {
    id: 'llm-calls',
    title: 'LLM and decision-provider calls',
    concepts: ['timeouts', 'retry-backoff-jitter', 'circuit-breakers', 'rate-limiting', 'graceful-degradation'],
    where: 'app/llm/**, app/llm/routing/**, app/services/decision/**, embedding calls from app/memory/**, and every caller that dispatches a model call (captioning, transcription through an LLM, classifiers).',
    seeds: 'llm.LlmResilience, utils.CircuitBreakers, services.BreakerAlarms, utils.TransientErrorClassifier, llm.ProviderRegistry, llm.routing.ModelRouter, llm.OkHttpLlmHttpDriver, the agent fallback provider/model, utils.HttpFactories tiers.',
    questions: `- Does every call path (chat, stream, embeddings, classifier, single-shot helpers) carry a connect timeout, an
  idle/read timeout suited to streaming, and an overall bound on the turn, or can one hang a turn forever?
- Retries: only on transient errors, with exponential backoff AND jitter, honoring Retry-After on a 429? Do retries
  nest (driver, provider, turn, task fire), multiplying attempts against an already-failing provider?
- Breakers: keyed per provider (or per provider+model where that is the failure domain), half-open probing, and
  does a fallback provider get its own breaker? What does the operator or channel user see when all fail?`,
  },
  {
    id: 'local-deps',
    title: 'MCP servers, ACP harnesses and sidecars',
    concepts: ['timeouts', 'retry-backoff-jitter', 'circuit-breakers', 'bulkheads', 'graceful-degradation'],
    where: 'app/mcp/**, app/services/*SidecarManager.java and the sidecar managers under app/services/** subpackages, services.LocalSidecarDaemon, app/services/sidecar/**, ACP harness runs (SubagentRun runtime acp), app/services/browser/**.',
    seeds: 'mcp.McpConnectionManager (breaker, reconnect), services.LocalSidecarDaemon.singleFlight and its spawn-failure cooldown, services.sidecar.SidecarHttpClient fair lock, the sidecar idle-timeout respawn.',
    questions: `- A stdio MCP server whose process is alive but no longer answers: does each call time out, and does the
  connection get torn down and rebuilt without a reconnect storm?
- Sidecar cold boot versus the caller's request timeout; callers queued behind the fair inference lock — is that wait
  bounded, and does one slow inference starve every conversation that needs the sidecar?
- When a sidecar, harness or MCP server is down, does the turn degrade (tool error the model can route around) or
  fail outright?`,
  },
  {
    id: 'external-apis',
    title: 'Channel platform APIs and other third-party HTTP',
    concepts: ['timeouts', 'retry-backoff-jitter', 'rate-limiting', 'circuit-breakers', 'graceful-degradation'],
    where: 'Outbound sends, edits, uploads and downloads in app/channels/** (Slack, Telegram, WhatsApp Cloud and Web), web search/fetch/scrape in app/tools/** and app/services/scrape/**, app/services/scanners/**, imagegen, videogen, catalog and discovery services, pricing refresh, app/services/printing/**.',
    seeds: 'utils.RetryScheduler, utils.TransientErrorClassifier, the Telegram streaming sink rate limiter, utils.HttpFactories tiers, the staged-download helpers, services.scanners.ScannerHttpClient.',
    questions: `- Does streaming edit or send cadence respect each platform's documented rate limits (Telegram 429 retry_after,
  Slack method tiers), and is a 429 retried after the stated delay rather than immediately?
- Is any non-idempotent send retried in a way that can deliver the same message twice?
- Are downloads bounded in time and size, and does a third-party outage degrade the one tool rather than the turn?`,
  },
  {
    id: 'load-isolation',
    title: 'Inbound load, isolation and pooling',
    concepts: ['backpressure', 'bulkheads', 'rate-limiting', 'connection-pooling'],
    where: 'Webhook and inbound controllers (controllers.WebhookIngressGate and the Telegram/Slack/WhatsApp webhook controllers), socket-mode and polling runners, agents.ChannelInboundDispatcher, services.ConversationQueue, agents.QueueDrainOrchestrator, services.NotificationBus and the SSE endpoints, agents.ParallelToolExecutor, every ExecutorService and virtual-thread spawn, utils.HttpFactories pools and dispatchers, the Hikari pool (utils.DbPoolStats, services.telemetry.TelemetryDataSource), the db-scheduler worker pool.',
    seeds: 'channels.TelegramWebhookRateLimiter, controllers.LoginRateLimiter, controllers.WebhookIngressGate, ConfigService.isDispatcherCapKey caps, the two shared OkHttp connection pools and dispatchers in HttpFactories.',
    questions: `- Is every work queue bounded, and when it is full does the code shed, reject with a visible error, or block —
  and if it blocks, with a timeout?
- Can one slow provider, channel or agent exhaust a shared resource (OkHttp dispatcher maxRequestsPerHost, a
  connection pool, the Hikari pool, the db-scheduler workers) and starve unrelated work? Virtual threads are cheap
  but connections are not: is there a path where thousands of virtual threads wait on a pool with no timeout?
- Is an OkHttpClient built outside utils.HttpFactories (each new client brings its own pool and dispatcher)?
- Inbound: can a webhook flood or a burst of messages from one chat starve other chats or the operator's web chat?`,
  },
  {
    id: 'delivery',
    title: 'Delivery guarantees and idempotency',
    concepts: ['idempotency-keys', 'at-least-once', 'dead-letter'],
    where: 'Inbound redelivery: Slack retries and socket-mode envelopes, Telegram update ids and the offset store, WhatsApp message ids, channels.InboundEventDedup. Outbound: services.DeliveryDispatcher, models.DeliveredMessage, services.ReminderDispatcher, the conversation/message send tools. Task fires: db-scheduler, services.TaskExecutionHandler, services.JClawFailureHandler, services.LostTaskDetector, jobs.OrphanReArmJob. Subagent and streaming recovery: jobs.SubagentOrphanRecoveryJob, jobs.TelegramStreamingRecoveryJob. Create/POST endpoints the SPA may double-submit.',
    seeds: 'channels.InboundEventDedup, models.DeliveredMessage, the Telegram offset store, the lost-task and orphan recovery jobs.',
    questions: `- For each at-least-once source, is the consumer idempotent? Is the dedup key persisted (survives a restart) or
  in memory only, and is its window at least as long as the sender's redelivery window?
- Crash windows: can a restart between an external side effect and the record of it repeat the side effect (a
  message sent twice, a task fired twice, a subagent run twice), or lose it?
- When retries are exhausted, where does the item go? A terminal state the operator can see and replay is the
  single-node analog of a dead-letter queue; a silent drop or an endless retry is the finding.`,
  },
  {
    id: 'consistency',
    title: 'Dual writes, staleness and concurrency control',
    concepts: ['outbox', 'change-data-capture', 'strong-vs-eventual', 'optimistic-locking', 'pessimistic-locking', 'hot-partitions'],
    where: 'Every site that commits to the database and also writes elsewhere — the Lucene index (services.search.LuceneIndexer), workspace files, in-process state (agents.DangerousActionGate grants, registries, caches), SSE notifications, channel sends. services.Tx boundaries. Entities written from more than one thread: Conversation, Message, Task, TaskRun, SubagentRun, Agent, Config, Memory and the memory graph.',
    seeds: 'services.Tx, services.search.LuceneIndexer, DangerousActionGate.revokeGrant pairing (AGENTS.md), Task.transitionTo status guard. No entity declares @Version.',
    questions: `- Outbox / CDC analog: where a commit is followed by a second write (Lucene, a file, a send, an in-process set),
  what happens if the second write fails or the process dies between them? Is there a repair path (rebuild,
  reconcile job) or does the derived state drift permanently? Is any side effect done before the commit, so a
  rollback leaves it behind?
- Lost updates: an entity read, modified and saved while a channel turn, a UI PATCH and a task fire touch the same
  row. With no @Version, the last writer silently wins: name the rows where that loses operator data or status.
- Pessimistic locking: H2 MVStore row locks under the transactions that hold them — any long transaction (one that
  spans a model call or network I/O) holding a row other paths need?
- Hot rows (the single-node hot partition): a row or key every turn updates (counters, a shared Config key) that
  serializes otherwise-parallel turns.
- Strong vs eventual: which reads may be stale (caches, the Lucene index, in-process sets) and is any of them
  trusted for a decision that must be current (authorization, a revoked grant, a deleted agent)?`,
  },
  {
    id: 'data-access',
    title: 'Query shape, indexes and amplification',
    concepts: ['query-plans', 'index-selectivity', 'write-amplification', 'read-amplification'],
    where: 'app/models/** (@Table indexes, @Index), JPQL and criteria queries in app/services/**, app/controllers/**, app/jobs/**, utils.JpqlFilter, LIKE searches, per-message Lucene indexing, the memory graph store, retention and cleanup jobs (jobs.RetentionDays callers), the event log and metric tables, services.database.H2Maintenance compaction.',
    seeds: 'The @Index declarations on models, the Lucene-for-text / LIKE-for-status split, jobs.RetentionDays.',
    questions: `- For the hottest queries (conversation list, message history for a turn, task runs, event-log listing, memory
  recall, subagent runs), do the WHERE and ORDER BY columns have a selective index? A low-cardinality column (a
  status, a boolean) indexed alone, a leading-wildcard LIKE on a growing table, or a count(*) per request.
- Read amplification: a turn or page that re-reads far more rows or bytes than it uses.
- Write amplification: a small change that rewrites a large row, a JSON blob or a Lucene document per turn; deletes
  that scan the table.
- You cannot run EXPLAIN here. When a verdict depends on the plan, write the exact query and the EXPLAIN to run
  into the finding and mark it needs-measurement. Do not connect to any database.`,
  },
  {
    id: 'caching',
    title: 'Cache correctness',
    concepts: ['cache-invalidation', 'cache-stampede'],
    where: 'Every Caffeine/JCache cache (services.CaffeineJCacheConfigPlugin and each Caffeine.newBuilder site), static maps used as caches, llm.ProviderRegistry, services.ModelDiscoveryService, the model catalogs, llm.TokenUsageEstimator, memoized catalogs and probes.',
    seeds: 'LocalSidecarDaemon.singleFlight, ProviderRegistry.refresh(), the 60 s ProviderRegistry window AGENTS.md documents.',
    questions: `- Is every write to a source of truth (config change, agent edit, provider key change, delete) followed by
  invalidating each cache derived from it, or does the cache serve the old value until it expires — and what does a
  stale value cost there?
- Concurrent misses on an expensive load (a catalog fetch, model discovery, a probe, a sidecar spawn): single-flight,
  or N parallel loads? Is a failure cached briefly so an outage is not retried by every caller at once?`,
  },
  {
    id: 'latency-observability',
    title: 'Tail latency, tracing and SLOs',
    concepts: ['p95-p99', 'tail-amplification', 'correlation-tracing', 'slo-error-budget'],
    where: 'agents.ParallelToolExecutor, utils.TaskScope callers, subagent batch spawns, the sequential steps on a turn critical path (prompt preparation, memory recall, compaction, tool catalog), utils.LatencyStats and utils.LatencyTrace segments and /api/metrics/latency, services.telemetry.OtelRuntime, GenAiSpans and the Play plugin, every thread hop (agent-stream, llm-stream, the drain thread, db-scheduler workers, virtual-thread executors), services.EventLogger rows, services.BreakerAlarms and services.OperatorAlerts.',
    seeds: 'LatencyStats/LatencyTrace, OtelRuntime and the Context.current().wrap rule in AGENTS.md, the latency metrics endpoint.',
    questions: `- Tail amplification: a turn that waits for the slowest of N branches (tools, subagents, recall sources) with no
  per-branch deadline, or independent steps run in sequence on the critical path.
- p95/p99: which segments of a turn are not recorded by LatencyStats/LatencyTrace, so their tail cannot be seen?
- Tracing: thread hops that drop the OTel Context (orphaned spans), and log/event rows that carry no conversation,
  agent, run or trace id an operator could correlate.
- SLOs: is any latency or availability target defined and alerted on? With one operator this is low severity unless
  its absence hides a failure the operator would act on.`,
  },
]

// Every open JCLAW issue (`plan.mjs --known-only`), so a reviewer does not pay to re-find what is already filed.
const KNOWN = (args && args.known) || []
const STILL_OPEN = /^\s*STILL OPEN:\s*(JCLAW-\d+)\s*[-–—:]*\s*(.*)$/gim
const KNOWN_LIST = KNOWN.length
  ? `ALREADY FILED — every open JCLAW issue:\n${KNOWN.map((k) => `- ${k.key} (${k.status}${k.epic ? `, epic ${k.epic}` : ''}): ${k.summary}`).join('\n')}`
  : ''

// /code-audit's OUT_OF_SCOPE names this audit's categories; keep the two lists mirrored.
const OUT_OF_SCOPE = `
OUT OF SCOPE — another audit owns these; do not report them here:
- /code-audit: clean code, SOLID, GRASP, Java 25 structure, and what one code path costs — N+1 and per-iteration
  queries, collections or caches that grow without bound or never evict, resource leaks (unclosed streams,
  responses, executors; listeners or ThreadLocals never removed), blocking calls on hot paths as CPU cost,
  redundant allocation, algorithmic complexity, missed memoization, JVM lock contention, virtual-thread hazards.
- /quality: mechanical residue (comments, spelling, inline names, one-line idiom rewrites, dead code, unused deps).
- /vulnhunt: security. /loadtest-audit: measured numbers — this audit reads code and never measures; mark a
  question that needs a number as needs-measurement and say how to measure it.
`

const PROJECT_GIVENS = `
PROJECT GIVENS — AGENTS.md is already in your context; its conventions and pitfalls are settled. A finding that
recommends what it rules out, or re-proposes a verdict recorded in docs/spikes/ without new evidence, is wrong.
- Single operator, single node: one JVM, one embedded H2 file, no message broker, no replicas. Realistic load is
  one operator, a handful of channels and agents, scheduled tasks, and the loadtest harness's 100 concurrent
  conversations. A failure that needs internet-scale traffic to occur is low severity at most.
- Play 1.x idioms (static controllers, active-record models) are not findings.
`

const SEVERITY = `
SEVERITY (calibrated for one operator on one node):
- critical: data loss or corruption; an external side effect duplicated or lost (a message sent twice, a task fired
  twice with real effects); a failure that stops every channel or turn.
- high: a turn, channel or job that can hang without bound; one dependency's failure cascading to unrelated agents
  or channels; a lost update to operator data; a queue that grows without bound under realistic load.
- medium: degraded but recoverable behavior; a protection present but misconfigured (retries without jitter,
  retrying a non-transient error, a breaker keyed too broadly).
- low: observability and hygiene gaps.
`

const BLOCK_FORMAT = `
First emit ONE block per concept of your group, in EXACTLY this shape:

### CONCEPT
- concept: <concept id>
- status: sound | gaps | needs-measurement
- mechanism: <the mechanism(s) that implement it, with file:line, or "none">
- note: <one line>

Then ONE block per finding:

### FINDING
- concept: <concept id from your group>
- file: <repo-relative path>
- lines: <line or range>
- severity: critical | high | medium | low
- title: <one line>
- scenario: <the trigger (what is slow, failing, duplicated, concurrent or overloaded) and the step-by-step consequence>
- evidence: <the code that allows it, quoted concisely>
- existing_protection: <the nearest mechanism and why it does not cover this path, or "none">
- fix: <specific change, naming the existing mechanism to reuse where one fits>
- measurement: <only if status needs-measurement: the query, EXPLAIN, loadtest or JFR view that would settle it; else "none">
`

const EXTRACT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['groupId', 'concepts', 'findings'],
  properties: {
    groupId: { type: 'string' },
    concepts: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['concept', 'status', 'mechanism', 'note'],
        properties: {
          concept: { type: 'string' },
          status: { type: 'string', enum: ['sound', 'gaps', 'needs-measurement'] },
          mechanism: { type: 'string' },
          note: { type: 'string' },
        },
      },
    },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['concept', 'file', 'lines', 'severity', 'title', 'scenario', 'evidence', 'existingProtection', 'fix', 'measurement', 'verdict', 'adjudication'],
        properties: {
          concept: { type: 'string' },
          file: { type: 'string' },
          lines: { type: 'string' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low', 'none'], description: 'severity AFTER adjudication' },
          title: { type: 'string' },
          scenario: { type: 'string' },
          evidence: { type: 'string' },
          existingProtection: { type: 'string' },
          fix: { type: 'string' },
          measurement: { type: 'string' },
          verdict: { type: 'string', enum: ['confirmed', 'downgraded', 'rejected'] },
          adjudication: { type: 'string' },
        },
      },
    },
  },
}

const SYNTH_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['headline', 'scorecard', 'themes', 'topIssues', 'quickWins', 'needsMeasurement'],
  properties: {
    headline: { type: 'string', description: '2-3 sentence verdict on how the backend behaves under failure and load' },
    scorecard: {
      type: 'array',
      description: 'one row per concept in the CONCEPTS list, in that order',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['concept', 'status', 'summary'],
        properties: {
          concept: { type: 'string', description: 'the display name' },
          status: { type: 'string', enum: ['sound', 'gaps', 'needs-measurement', 'not-applicable'] },
          summary: { type: 'string', description: 'one line: the mechanism, or the gap with its worst finding' },
        },
      },
    },
    themes: {
      type: 'array',
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
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['title', 'concept', 'file', 'lines', 'severity', 'why', 'fix'],
        properties: {
          title: { type: 'string' },
          concept: { type: 'string' },
          file: { type: 'string' },
          lines: { type: 'string' },
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          why: { type: 'string' },
          fix: { type: 'string' },
        },
      },
    },
    quickWins: { type: 'array', items: { type: 'string' } },
    needsMeasurement: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['concept', 'question', 'how'],
        properties: {
          concept: { type: 'string' },
          question: { type: 'string' },
          how: { type: 'string', description: '/loadtest-audit, an EXPLAIN, a JFR view or another concrete measurement' },
        },
      },
    },
  },
}

// args: { groups?: [group ids], known, openIssuesChecked, date, head, lanes, backlog?: false } — every field optional.
const opts = args || {}
const wanted = Array.isArray(opts.groups) && opts.groups.length ? opts.groups : GROUPS.map((g) => g.id)
const unknown = wanted.filter((id) => !GROUPS.some((g) => g.id === id))
if (unknown.length) throw new Error(`unknown group ids: ${unknown.join(', ')} (known: ${GROUPS.map((g) => g.id).join(', ')})`)
const groups = GROUPS.filter((g) => wanted.includes(g.id))
const conceptList = (g) => g.concepts.map((c) => `  - ${c}: ${CONCEPTS[c]}`).join('\n')

log(`Reviewing ${groups.length} concept groups: ${groups.map((g) => g.id).join(', ')}`)

const perGroup = await pipeline(
  groups,

  // stage 1 — REVIEW (prose; searches and reads app/; NO schema so it reliably finishes)
  (g) => agent(
    `You are a senior backend reliability engineer auditing JClaw (Play 1.x, Java 25, OkHttp 5, embedded H2) for
one concern: ${g.title}.
Concepts in your group:
${conceptList(g)}

Where to look: ${g.where}
Existing mechanisms to start from (judge whether each covers every path; do not re-propose one that already does):
${g.seeds}

Questions to answer:
${g.questions}
${KNOWN_LIST && `${KNOWN_LIST}
Write no FINDING for a defect one of these already plans to fix. Where one is still visibly present, add a line
"STILL OPEN: <key> — <file>:<lines>" after your findings instead; it needs no adjudication. Spend the review on
what they do not cover.`}

Method: start from the seeds, then search all of app/ for every site of each pattern (every client builder, every
executor, every queue, every cache, every query — whatever your concepts touch) so a path the seeds miss is still
examined. Read the code at each site before judging it. Say how many sites you examined on the SUMMARY line.
${PROJECT_GIVENS}
${SEVERITY}
${OUT_OF_SCOPE}

Rules:
- Every finding needs a concrete scenario and the code that allows it. "Consider adding X" with no failure is not
  a finding.
- Name the existing protection nearest the path and why it does not cover it. If it does cover it, there is no finding.
- Prefer FEWER, HIGH-CONFIDENCE findings. Report low severity only where it is cheap to fix.
${BLOCK_FORMAT}

Your plain-text response IS the deliverable — do NOT call any structured-output tool. Begin with a one-line
"SUMMARY:" then the CONCEPT blocks, then the FINDING blocks (none is fine).`,
    { label: `review:${g.id}`, phase: 'Review' },
  ).then((text) => ({ g, reviewText: text })),

  // stage 2 — ADVERSARIAL ADJUDICATE (prose; re-reads the cited code; NO schema)
  ({ g, reviewText }) => {
    // A dead agent returns null; throwing drops the group into groupsDropped instead of counting it reviewed.
    if (!reviewText) throw new Error(`review:${g.id} returned nothing`)
    const stillOpen = [...reviewText.matchAll(STILL_OPEN)].map((m) => ({ key: m[1], where: m[2].trim(), group: g.id }))
    return agent(
      `You are a skeptical staff engineer adjudicating a peer's reliability findings for JClaw, concern: ${g.title}.
Your job is to REFUTE weak findings, not to agree. Re-read the cited code YOURSELF before ruling, and follow the
call path far enough to see whether a protection upstream or downstream already covers it.
${KNOWN_LIST && `${KNOWN_LIST}
Reject a finding one of these already plans to fix, and name the key in the adjudication.`}
${PROJECT_GIVENS}
${SEVERITY}
${OUT_OF_SCOPE}

For EACH finding, rule independently:
- "confirmed": real, the scenario can happen, and the severity is right.
- "downgraded": real but over-rated for one operator on one node — lower the severity.
- "rejected": a protection on the path already covers it (name it), the scenario cannot occur, it needs scale
  JClaw does not have, it contradicts AGENTS.md or a docs/spikes verdict, it is out of scope, or the evidence
  misreads the code. State WHY.
When you doubt a finding is real, reject it. When it is real, rate its consequence on the scale above and never
lower a severity because you are unsure. Check that file, lines and quoted evidence are accurate.

For EACH concept block, check the cited mechanism exists at that file:line. Set status "gaps" where a finding is
confirmed or downgraded for that concept, "sound" where none survives and the mechanism is real, and keep
"needs-measurement" where only a number would settle it.

Re-emit EVERY concept and finding block in the SAME format, adding to each FINDING block:
- verdict: confirmed | downgraded | rejected
- final_severity: critical | high | medium | low | none
- adjudication: <why; name the covering protection or the false-positive reason if rejected>
${BLOCK_FORMAT}

Your plain-text response IS the deliverable — do NOT call any structured-output tool.

REVIEW TO ADJUDICATE:
${reviewText}`,
      { label: `verify:${g.id}`, phase: 'Verify', model: 'opus' },
    ).then((text) => ({ g, verdictText: text, stillOpen }))
  },

  // stage 3 — EXTRACT (schema; NO file reading)
  ({ g, verdictText, stillOpen }) => {
    if (!verdictText) throw new Error(`verify:${g.id} returned nothing`)
    return agent(
    `Transform the adjudicated reliability-review prose below into structured JSON. Do NOT read any files or use any
tool except the final structured-output call. Parse each "### CONCEPT" block into concepts[] and each
"### FINDING" block into findings[]. Map "final_severity" -> "severity", "existing_protection" ->
"existingProtection". Carry every other field over verbatim. A FINDING block with no verdict is "confirmed" at its
stated severity. Produce {groupId:"${g.id}", concepts:[...], findings:[...]}.

ADJUDICATED PROSE:
${verdictText}`,
    { label: `extract:${g.id}`, phase: 'Extract', schema: EXTRACT_SCHEMA, model: 'opus', effort: 'low' },
    ).then((r) => r && { ...r, groupId: g.id, stillOpen })
  },
)

const done = perGroup.filter(Boolean)
const dropped = groups.filter((g) => !done.some((d) => d.groupId === g.id)).map((g) => g.id)
if (dropped.length) log(`WARNING: ${dropped.length} groups dropped: ${dropped.join(', ')}`)
const surviving = done.flatMap((d) => d.findings.map((f) => ({ ...f, group: d.groupId })))
  .filter((f) => f.verdict !== 'rejected' && f.severity !== 'none')
const rejected = done.flatMap((d) => d.findings).filter((f) => f.verdict === 'rejected').length
const stillOpen = done.flatMap((d) => d.stillOpen || [])
const unreviewed = Object.keys(CONCEPTS).filter((c) =>
  !NOT_APPLICABLE.some((n) => n.concept === c) && !groups.some((g) => g.concepts.includes(c) && !dropped.includes(g.id)))

log(`${surviving.length} findings survived adjudication (${rejected} rejected); building the scorecard`)

phase('Synthesize')
const synthesis = await agent(
  `Synthesize this reliability audit of JClaw into a scorecard and a prioritized report. Do NOT read files; use only
the data below.
${PROJECT_GIVENS}
CONCEPTS (scorecard rows, in this order; use the display names): ${JSON.stringify(CONCEPTS)}
NOT APPLICABLE (status not-applicable, summary = the reason): ${JSON.stringify(NOT_APPLICABLE)}
NOT REVIEWED THIS RUN (status needs-measurement, summary "not reviewed this run"): ${JSON.stringify(unreviewed)}
PER-GROUP CONCEPT STATUS (a concept reviewed by several groups is "gaps" if any group found gaps):
${JSON.stringify(done.map((d) => ({ group: d.groupId, concepts: d.concepts })))}
SURVIVING FINDINGS:
${JSON.stringify(surviving)}

Group cross-cutting themes (a gap repeated across dependencies or subsystems outranks a single instance). Name the
worst concrete offenders in topIssues. List cheap high-value fixes in quickWins. Collect every needs-measurement
question into needsMeasurement with how to settle it.`,
  { label: 'synth:scorecard', phase: 'Synthesize', schema: SYNTH_SCHEMA, model: 'opus' },
)

// ---- remediation backlog: the same child code-audit uses ----
let backlog = null
if (opts.backlog !== false) {
  try {
    backlog = await workflow({ scriptPath: '.claude/workflows/audit-backlog.js' }, {
      source: 'resilience-audit', date: opts.date, head: opts.head, scope: `groups: ${groups.map((g) => g.id).join(', ')}`,
      lanes: opts.lanes, findings: surviving, known: KNOWN, themes: (synthesis && synthesis.themes) || [],
    })
  } catch (e) {
    log(`backlog step failed: ${(e && e.message) || e}; the findings are still returned`)
  }
}

const sev = (s) => surviving.filter((f) => f.severity === s).length
return {
  stats: {
    groupsRun: groups.map((g) => g.id),
    groupsDropped: dropped,
    conceptsUnreviewed: unreviewed,
    openIssuesChecked: opts.openIssuesChecked ?? null,
    stillOpen: { confirmations: stillOpen.length, issues: [...new Set(stillOpen.map((s) => s.key))].length },
    survivingFindings: surviving.length,
    rejected,
    bySeverity: { critical: sev('critical'), high: sev('high'), medium: sev('medium'), low: sev('low') },
  },
  synthesis,
  backlog,
  stillOpen,
  findings: surviving,
}
