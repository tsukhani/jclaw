# Memories

Agents remember. After each conversation turn, JClaw quietly extracts durable, reusable facts — preferences you've stated, decisions made, lessons learned — and stores them per agent. Those memories flow back into later sessions: the most important are loaded at session start, the rest are recalled when relevant to what you're asking. The [Memories](/memory) page (sidebar → **Admin**) is where you inspect and curate everything your agents have captured.

## How capture works

There is no "save this" command — capture is automatic and happens in the background after a turn completes, so it never delays a reply:

1. A cheap **attention gate** skips trivial turns (greetings, bare acknowledgements) so the system doesn't pay an extraction call for "thanks".
2. An **LLM extractor** reads the turn and proposes candidate memories, each with a category and an importance score. At most 5 are kept per turn.
3. Each candidate is **deduplicated** against the agent's recent memories — a near-duplicate of something already stored is dropped rather than appended.

Capture applies to your operator-facing agents only — [subagents](/subagents) never capture (their work returns to the parent, which captures what matters). Each agent has its own **Auto-capture memories** toggle and an optional extractor-model override on its [Agents](/agents) edit form; point the override at a cheap model to keep extraction costs negligible.

### What never gets stored

Two deterministic guards run on every candidate at write time, independent of the extractor's judgment:

- **Secrets** — API keys, bearer/JWT tokens, private-key blocks, explicit `password=...` assignments, and valid card numbers are refused. A credential must never reach the long-term store.
- **Prompt injection** — memory text is re-read by the agent in future sessions, so a hostile instruction stored as a "fact" would become persistent. Injection phrasing, exfiltration payloads, and invisible-unicode smuggling are refused wholesale.

A tripped guard drops the whole candidate: losing one memory is cheaper than persisting a live credential or a hostile directive.

## Categories and importance

Every memory carries one of six categories and an importance score from 0 to 1:

| Category | Default importance | What it's for |
|----------|--------------------|---------------|
| `core` | 0.9 | Identity-level facts the agent should always know — auto-loaded every session. |
| `preference` | 0.7 | How you like things done. |
| `decision` | 0.7 | Choices that were settled and shouldn't be re-litigated. |
| `lesson` | 0.6 | Something that went wrong (or right) and why. |
| `fact` | 0.5 | Reference data, recalled when relevant. |
| `entity` | 0.5 | People, systems, and things the agent works with. |

Importance drives everything downstream: recall ranking, and whether a `core` memory qualifies for session auto-load. You can adjust it inline on the Memories page — raising a memory toward 1.0 makes it surface more; dropping it toward 0 quietly retires it without deleting.

## How memories come back

Two paths return memories to the agent:

- **Core auto-load** — `core`-category memories at or above the importance threshold (default 0.8) are injected into every session at start, capped at 20 entries within a small token budget so they can never crowd out the context window.
- **Per-turn recall** — each message triggers a relevance search over the agent's store; the best matches (up to 10) are injected for that turn only. Recalled text is framed to the model as stored reference data, **not** instructions — the soft counterpart of the write-time injection guard.

By default relevance is keyword-based. Enabling **vector search** adds semantic recall — "what did we decide about invoicing?" finds a memory that never uses the word "invoicing" — with the two result lists blended by reciprocal-rank fusion. The backend is picked automatically: `pgvector` on PostgreSQL, an embedded Lucene HNSW index otherwise. See [Tuning](#memory-tuning) below.

## The Memories page

The [Memories](/memory) page is a cross-agent table: owning agent, memory text, category badge, importance, and created date. The filter bar composes free text with per-field predicates, e.g.:

```
q:invoice category:core importance:>0.8 agent:main
```

From a row you can:

- **Adjust importance** inline (0–1, in 0.05 steps) — takes effect on the next recall.
- **Delete** the memory — permanent, behind a confirm dialog. Delete anything wrong or stale; a bad memory recalled into future sessions is worse than no memory.

The **Export** action downloads the currently filtered view as a JSON snapshot. The table shows up to 200 matching entries — narrow the filters if you're near the cap.

## Tuning

Day-to-day knobs (per-agent capture toggle, extractor model) live on the [Agents](/agents) page. The rest are server config keys with sensible defaults — they live in the config store (`POST /api/config`) rather than a [Settings](/settings) section:

| Key | Default | Meaning |
|-----|---------|---------|
| `memory.coreload.enabled` | `true` | Auto-load `core` memories at session start. |
| `memory.coreload.minImportance` | `0.8` | Importance floor for auto-load. |
| `memory.coreload.maxCount` | `20` | Max core memories per session. |
| `memory.coreload.tokenBudget` | `400` | Token cap on the core-memory block. |
| `memory.recall.limit` | `10` | Max memories recalled per turn. |
| `memory.autocapture.maxPerTurn` | `5` | Max memories captured from one turn. |
| `memory.autocapture.maxTokens` | `1024` | Output budget for the extractor call. |
| `memory.autocapture.dedup.threshold` | `0.85` | Similarity above which a candidate is a duplicate. |
| `memory.autocapture.dedup.scanLimit` | `100` | Recent memories compared during dedup. |

Vector search is opt-in via `conf/application.conf` (JVM restart required):

```properties
memory.jpa.vector.enabled=true
memory.jpa.vector.provider=openai
memory.jpa.vector.model=text-embedding-3-small
memory.jpa.vector.dimensions=1536
```

When the embedding provider is unavailable, recall degrades gracefully to keyword-only — memory never blocks the agent.
