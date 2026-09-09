# Memories

Agents remember. After each conversation turn, JClaw quietly extracts durable, reusable facts — preferences you've stated, decisions made, lessons learned — and stores them per agent. Those memories flow back into later sessions: the most important are loaded at session start, the rest are recalled when relevant to what you're asking. The [Memories](/memories) page (sidebar → **Admin**) is where you inspect and curate everything your agents have captured.

## How capture works

Capture is automatic — it happens in the background after a turn completes, so it never delays a reply:

1. A cheap **attention gate** skips trivial turns (greetings, bare acknowledgements) so the system doesn't pay an extraction call for "thanks".
2. An **LLM extractor** reads the turn and proposes candidate memories, each with a category and an importance score. At most 5 are kept per turn.
3. Each candidate is **deduplicated** against the agent's recent memories — a near-duplicate of something already stored is dropped rather than appended.

An explicit instruction takes a different route: the agent's `memory` tool (recall, store, forget) answers "remember that…" and "forget what I told you about…" directly, and reports exactly what it touched — storing something already known is a no-op, not a second row. That tool is also the only way a `core` memory is created: automatic capture never assigns `core`, and a candidate the extractor labels `core` is demoted to `fact`.

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

The [Memories](/memories) page is a cross-agent table: owning agent, memory text, category badge, importance, and created date. The filter bar composes free text with per-field predicates, e.g.:

```
q:invoice category:core importance:>0.8 agent:main status:superseded
```

`status:` picks between **active** (the default — the same set recall sees), **superseded**, and **all**. A memory replaced by a newer one is kept rather than deleted: it renders dimmed with a **superseded** badge whose tooltip says when it was superseded and by which memory, and it is excluded from recall.

From a row you can:

- **Adjust importance** inline (0–1, in 0.05 steps) — takes effect on the next recall.
- **Delete** — select rows with their checkboxes and use **Delete**, or **Delete all matching** to clear everything the active filters match (behind a typed confirmation). Both are permanent. Delete anything wrong or stale; a bad memory recalled into future sessions is worse than no memory.

The table is paginated at 20 rows per page, with sorting and paging done server-side, so a filter applies to the whole store rather than the visible page. The **Export** action downloads the whole matching set (up to 500 entries) as a JSON snapshot — narrow the filters if you're near that cap.

## Tuning

Day-to-day knobs (per-agent capture toggle, extractor model) live on the [Agents](/agents) page. The three memory sections under [Settings](/settings) › Memory cover the rest:

- **Limits** — how many memories reach the prompt. `memory.coreload.maxCount` (default `20`) caps the core-memory block loaded at session start; `memory.recall.limit` (default `10`) caps the memories recalled per turn. These two counts are the *only* bound on their blocks, so between them they decide memory's whole footprint in a turn. Both are counts of whole memories — a value below 1 disables the block.
- **Embeddings** — the vector-memory toggle plus its provider and model. See below.
- **Reranker** — an optional second pass that re-orders the shortlist recall found, before it reaches the prompt (`memory.rerank.enabled`, `.provider`, `.model`; off by default).

The remaining knobs have no Settings section and live in the config store (`POST /api/config`):

| Key | Default | Meaning |
|-----|---------|---------|
| `memory.coreload.enabled` | `true` | Auto-load `core` memories at session start. |
| `memory.coreload.minImportance` | `0.8` | Importance floor for auto-load. |
| `memory.autocapture.maxPerTurn` | `5` | Max memories captured from one turn. |
| `memory.autocapture.maxTokens` | `1024` | Output budget for the extractor call. |
| `memory.autocapture.dedup.threshold` | `0.85` | Similarity above which a candidate is a duplicate. |
| `memory.autocapture.dedup.scanLimit` | `100` | Recent memories compared during dedup. |

Vector search is opt-in from **Settings › Memory › Embeddings** — the Vector memory toggle, then a provider and model. It is not a `conf/application.conf` edit and needs no restart; the keys (`memory.jpa.vector.enabled`, `.provider`, `.model`, `.dimensions`) live in the config store like any other setting.

**The provider must be one you've marked local** — the **local** flag on its LLM Providers card (`provider.<name>.local`), seeded for Ollama Local, LM Studio, vLLM and llama.cpp; the base URL is not consulted. Embedding a memory sends its full text to the provider, and reranking renders the whole candidate shortlist into a prompt, so both are restricted to a model running on this machine and memory text never leaves it. The picker lists only local providers, and the backend rejects a non-local value even if the key is set directly. Models are discovered live from the provider, because embedding models are usually absent from the stored catalog.

Save is gated on a probe (**Check the model before saving**) that confirms the model embeds and records its dimension. Changing the model afterwards marks the corpus **needs re-embedding**, with a re-embed action that rewrites the existing vectors against the new model.

When the embedding provider is unavailable, recall degrades gracefully to keyword-only — memory never blocks the agent. The same is true with vector memory off entirely: recall and duplicate detection fall back to keyword matching.
