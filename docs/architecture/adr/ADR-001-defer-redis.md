# ADR-001: Defer Redis as a dependency

**Status:** Accepted (2026-05-18)

**Authors:** Tarun Sukhani (decision), Claude (drafted)

**Supersedes:** none

**Related stories:** JCLAW-303 (task subscription event-bus, intra-JVM)

---

## Context

JClaw's current shape:

- Single Play 1.x JVM serving the SPA, REST API, and the agent loop.
- File-mode H2 (Personal Edition) with an opt-in Postgres path.
- db-scheduler for persistent task scheduling (Postgres-compatible, currently H2-backed).
- Per-JVM in-memory caches for hot lookups (e.g. `ToolRegistry.DISABLED_TOOLS_CACHE`, `ConfigService` cache).
- MessageSearch via H2 FullTextLucene on the Personal Edition path, Postgres tsvector when configured.
- No `User` model; AuthCheck admits a single system principal. Multi-tenancy is out of scope by design — Personal Edition is single-operator.
- Single-process by design; multi-JVM deployment is out of scope.

The recurring question — "should we add Redis as a cache and pub/sub layer?" — has come up specifically around the JCLAW-303 task-completion subscription design and may recur whenever a new hot-cache or fan-out scenario lands.

## Decision

**Do not add Redis as a dependency. Build intra-JVM equivalents using existing primitives.**

For each candidate use case the alternative is:

| Use case | JClaw choice | Rationale |
|----------|--------------|-----------|
| Hot per-key cache (tool disable list, agent config, etc.) | Per-JVM cache (Caffeine / `ConcurrentHashMap` with TTL) | Single-process app; cross-process coherence isn't a concern |
| Task completion fan-out (JCLAW-303) | Java listener invoked on a virtual thread from `TaskExecutor`'s post-completion hook | Source and subscriber are in the same JVM; intra-process call costs micro-seconds |
| Streaming event delivery to N browser tabs | Server-Sent Events from the same JVM with a shared in-memory event registry | Browser tabs all hit the same backend; no cross-instance fan-out needed |
| Session/auth caching | DB-backed via AuthCheck (current shape) | Single operator; session count is low |
| LLM response caching | Out of scope at v0.12; if it ships, start with a per-JVM Caffeine cache | Volume doesn't yet justify a shared cache tier |
| Scheduling primitive | db-scheduler (current) | Already shipped, persistent across restarts, Postgres-portable |

## Consequences

**Positive**

- Operator deployment footprint stays one process. The Personal Edition's "you start the JAR, it runs everything" promise is preserved.
- No new single-point-of-failure category.
- No cache coherence problems between Redis and JPA.
- Smaller config surface (no Redis URL, auth, TLS, cluster mode).
- Easier local development and testing — no Redis container to manage.

**Negative**

- No cross-instance event fan-out. If JClaw later needs to span JVMs, intra-JVM listeners must be replaced with a cross-process primitive (which may or may not be Redis at that point).
- Cache size is bounded by JVM heap. Large operator deployments with millions of cached entries would need a tier external to the JVM.
- Per-JVM caches don't survive process restarts. For data that survives restart we use the DB; for data that doesn't (transient computed values), the warm-up cost on restart is acceptable today.

## When to revisit

Re-open this decision when any of the following becomes true:

1. **A fundamentally different product (out of scope for Personal Edition).** If JClaw ever became a hosted, multi-process service spanning many JVMs, intra-JVM fan-out and per-JVM caches would both break, and a shared tier (Redis, Kafka, or Postgres LISTEN/NOTIFY) would become a real candidate. This is explicitly not the Personal Edition roadmap.
2. **Horizontal scale-out.** If a single-operator deployment ever needed more than one JVM (CPU-bound LLM throughput, HA failover with active-active), cross-instance coordination would be required.
3. **LLM response caching with material cost savings.** If LLM provider spend reaches a level where caching identical-prompt completions saves more than the operational cost of a shared cache, a centralized cache tier earns its keep.
4. **External webhook fan-out.** If JClaw starts emitting webhook events to N external subscribers per task fire (across multiple JClaw instances), a queue/broker becomes structurally necessary.
5. **Postgres LISTEN/NOTIFY emerges as a friction point.** If we adopt cross-process pub/sub on the Postgres path (Postgres LISTEN/NOTIFY is one option), and that becomes unwieldy, Redis pub/sub may be a cleaner alternative.

## Notes for implementers

- The intra-JVM listener pattern for JCLAW-303 should expose its registration surface (`TaskEventBus.subscribe(taskName, listener)`) cleanly so a future cross-process replacement only swaps the bus's transport layer, not the call sites.
- If a future change requires a cache tier with shared state (e.g. session tokens across pods), prefer Postgres-backed shared state first (rows, advisory locks, LISTEN/NOTIFY) before introducing Redis — it reuses an existing dependency rather than adding a new one.
- Redis is not banned forever. This ADR captures "not now and not at this scale." A clean future ADR proposing it is the right vehicle when one of the revisit triggers fires.
