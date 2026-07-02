package memory;

import llm.ProviderRegistry;
import models.Agent;
import models.Memory;
import play.Play;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import play.db.DB;
import play.db.jpa.JPA;
import services.EventLogger;
import services.search.DirectLuceneMessageSearchRepository;
import services.search.LuceneIndexer;
import services.search.MessageSearchRepository.ScoredId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * JPA-backed memory store. Text search runs on the direct Lucene index for H2
 * (dev/test and the default Personal Edition) and PostgreSQL full-text search in
 * a Postgres production deployment. When vector memory is enabled
 * ({@code memory.jpa.vector.enabled}), the vector leg is dialect-split
 * (JCLAW-555): pgvector hybrid SQL on Postgres, Lucene HNSW
 * ({@code KnnFloatVectorField} on the MEMORY scope) everywhere else — selection
 * follows the JDBC product name, mirroring {@code MessageSearch}, so a prod-mode
 * boot on the bundled H2 never attempts pgvector SQL. On Postgres the pgvector
 * schema is provisioned at construction via {@link PgVectorProvisioner}
 * (JCLAW-528); when provisioning fails the vector leg is disabled and recall
 * degrades to full-text search.
 *
 * <p>Hybrid recall on both backends shares one retrieval contract (JCLAW-527):
 * each backend supplies a keyword leg and a vector leg as ranked lists, and
 * {@link #fuseHydrateRerank} — Reciprocal Rank Fusion (k = 60), agent-bounded
 * hydration, optional {@link MemoryReranker cross-encoder rerank} — does the
 * rest, written once above the backend seam.
 */
public class JpaMemoryStore implements MemoryStore {

    private static final String EVENT_CATEGORY_MEMORY = "memory";

    private final boolean vectorEnabled;
    private final boolean isPostgres;
    private final String vectorModel;
    private final int vectorDimensions;

    /**
     * Test seam for embedding generation (mirrors {@code MemoryAutoCapture.Extractor}):
     * when set, {@link #generateEmbedding} returns {@code override.apply(text)}
     * directly — no provider call, no shared cache — so tests can drive the
     * Lucene KNN path with canned vectors. Volatile: tests set/clear it around
     * the {@code LuceneTestSync} lock while production threads read it.
     */
    private static volatile java.util.function.Function<String, float[]> embedderOverride;

    /** Test-only: install (or clear with {@code null}) a canned embedder. */
    public static void setEmbedderForTest(java.util.function.Function<String, float[]> override) {
        embedderOverride = override;
    }

    public JpaMemoryStore() {
        this(detectPostgres());
    }

    private JpaMemoryStore(boolean isPostgres) {
        this(resolveVectorEnabled(isPostgres), isPostgres);
    }

    /**
     * Effective vector enablement (JCLAW-528). On Postgres the pgvector leg
     * needs its schema provisioned (extension + embedding column + HNSW index)
     * — {@link PgVectorProvisioner#ensureProvisioned()} runs the idempotent
     * guarded step and reports readiness. When it cannot provision (pgvector
     * not installed, missing privilege) the provisioner has already logged the
     * error and the store degrades to full-text search rather than attempting
     * embedding SQL that fails on every write. Non-Postgres dialects need no
     * provisioning: their vector leg is the Lucene HNSW backend (JCLAW-555).
     */
    private static boolean resolveVectorEnabled(boolean isPostgres) {
        boolean enabled = "true".equals(
                Play.configuration.getProperty("memory.jpa.vector.enabled", "false"));
        if (!enabled || !isPostgres) {
            return enabled;
        }
        return PgVectorProvisioner.ensureProvisioned();
    }

    /**
     * Test-visible constructor (JCLAW-555): lets a test fix the vector-enable and
     * dialect flags without flipping process-global config (the play1 test engine
     * runs unit + functional lanes concurrently — a config flip would leak into
     * sibling tests constructing the store through {@link MemoryStoreFactory}).
     * Public because the test tree compiles into the default package.
     */
    public JpaMemoryStore(boolean vectorEnabled, boolean isPostgres) {
        this.isPostgres = isPostgres;
        this.vectorEnabled = vectorEnabled;
        this.vectorModel = Play.configuration.getProperty("memory.jpa.vector.model", "text-embedding-3-small");
        this.vectorDimensions = Integer.parseInt(
                Play.configuration.getProperty("memory.jpa.vector.dimensions", "1536"));

        if (vectorEnabled) {
            EventLogger.info(EVENT_CATEGORY_MEMORY,
                    "JPA memory store with vector search enabled: backend=%s (model: %s, dims: %d)"
                            .formatted(isPostgres ? "pgvector" : "lucene-hnsw", vectorModel, vectorDimensions));
        }
    }

    /**
     * Dialect sniff (JCLAW-555): ask the live connection what it actually is —
     * the {@code MessageSearch.chooseRepository} pattern — rather than
     * string-matching config. The commented-out {@code %prod.db.*} PostgreSQL
     * block in application.conf, a {@code -Ddb.url} override, or an edited conf
     * all land on the same JDBC product name. Falls back to the configured
     * {@code db.url} when no connection is available yet (very early boot).
     */
    private static boolean detectPostgres() {
        try (var conn = DB.getDataSource().getConnection()) {
            return conn.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT).contains("postgresql");
        } catch (Exception _) {
            var dbUrl = Play.configuration.getProperty("db.url", "");
            return dbUrl.contains("postgresql") || dbUrl.contains("postgres");
        }
    }

    @Override
    public String store(String agentId, String text, String category, double importance) {
        var memory = new Memory();
        memory.agent = resolveAgent(agentId);   // JCLAW-537: real FK — the agent must exist
        memory.text = text;
        memory.category = category;
        memory.importance = importance;
        memory.save();

        if (vectorEnabled) {
            // JCLAW-555: dialect-split embedding storage. Postgres writes the
            // pgvector column; every other dialect re-upserts the row's Lucene
            // MEMORY doc with a KNN vector field. No pgvector SQL is ever
            // attempted on H2 (the pre-555 gap: the raw ::vector UPDATE ran on
            // any dialect whenever the flag was on, failing on every store).
            if (isPostgres) {
                generateAndStoreEmbedding(memory);
            } else {
                generateAndIndexEmbedding(memory);
            }
        }

        return memory.id.toString();
    }

    @Override
    public List<MemoryEntry> search(String agentId, String query, int limit) {
        if (vectorEnabled) {
            return isPostgres
                    ? hybridSearch(agentId, query, limit)
                    : luceneHybridSearch(agentId, query, limit);
        }
        if (isPostgres) {
            return fullTextSearch(agentId, query, limit);
        }
        return likeSearch(agentId, query, limit);
    }

    @Override
    public void delete(String id) {
        var memory = Memory.findById(Long.parseLong(id));
        if (memory != null) {
            ((Memory) memory).delete();
        }
    }

    @Override
    public List<MemoryEntry> list(String agentId) {
        return Memory.findByAgent(agentId).stream()
                .map(this::toEntry)
                .toList();
    }

    @Override
    public List<MemoryEntry> list(String agentId, int limit, int offset) {
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
        List<Memory> memories = Memory.find(
                "agent.id = ?1 AND supersededAt IS NULL ORDER BY updatedAt DESC", pk)
                .from(offset).fetch(limit);
        return memories.stream()
                .map(this::toEntry)
                .toList();
    }

    @Override
    public int deleteAll(String agentId) {
        // Bulk JPQL delete. The caller (AgentService.delete) calls em.clear()
        // before invoking us, so there are no stale Memory entities in the
        // Hibernate session that could conflict. We intentionally do NOT call
        // em.clear() here — the caller may still hold re-fetched entities
        // (e.g. the Agent itself) that must remain attached for subsequent ops.
        // The FK's ON DELETE CASCADE (JCLAW-537) is the DB-level backstop for
        // raw agent deletes; this explicit pass remains the service-path cleanup.
        Long pk = pkOrNull(agentId);
        if (pk == null) return 0;
        return JPA.em().createQuery("DELETE FROM Memory m WHERE m.agent.id = :agentId")
                .setParameter("agentId", pk)
                .executeUpdate();
    }

    // --- Search strategies ---

    private List<MemoryEntry> likeSearch(String agentId, String query, int limit) {
        // JCLAW-532: the Lucene-backed scored search carries a real top-normalized
        // relevance score per hit; thread it onto the entry so recall can rank by
        // relevance rather than list position.
        return Memory.searchByTextScored(agentId, query, limit).stream()
                .map(s -> toEntry(s.memory(), s.relevance()))
                .toList();
    }

    private List<MemoryEntry> fullTextSearch(String agentId, String query, int limit) {
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
        try {
            return toEntriesRankScored(pgFtsRows(pk, query, limit));
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY, "PG FTS failed, falling back to LIKE search: %s".formatted(e.getMessage()));
            return likeSearch(agentId, query, limit);
        }
    }

    /**
     * JCLAW-527: the Postgres keyword leg — full Memory entities ranked by
     * {@code ts_rank}. Single native query (no IDs-then-refetch round trip);
     * shared by {@link #fullTextSearch} and the hybrid fusion.
     */
    @SuppressWarnings("unchecked")
    private List<Memory> pgFtsRows(Long pk, String query, int limit) {
        var sql = """
                SELECT m.* FROM memory m
                WHERE m.agent_id = ?1 AND m.superseded_at IS NULL
                AND to_tsvector('english', m.text) @@ plainto_tsquery('english', ?2)
                ORDER BY ts_rank(to_tsvector('english', m.text), plainto_tsquery('english', ?2)) DESC
                """;
        return JPA.em().createNativeQuery(sql, Memory.class)
                .setParameter(1, pk)
                .setParameter(2, query)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * JCLAW-527: the Postgres vector leg. The {@code ORDER BY} is the bare
     * cosine-distance operator over the raw column — the exact shape the
     * planner can serve from the HNSW index provisioned in JCLAW-528. Any
     * wrapping expression (the old {@code COALESCE(ts_rank …) * 0.3 + … * 0.7}
     * weighted sum) forces a sequential scan of every embedding. Test-pinned.
     */
    public static final String PG_VECTOR_LEG_SQL = """
            SELECT m.id FROM memory m
            WHERE m.agent_id = ?1 AND m.embedding IS NOT NULL AND m.superseded_at IS NULL
            ORDER BY m.embedding <=> ?2::text::vector
            """;

    private List<Long> pgVectorIds(Long pk, float[] embedding, int limit) {
        List<?> rows = JPA.em().createNativeQuery(PG_VECTOR_LEG_SQL)
                .setParameter(1, pk)
                .setParameter(2, toVectorLiteral(embedding))
                .setMaxResults(limit)
                .getResultList();
        var ids = new ArrayList<Long>(rows.size());
        for (Object r : rows) {
            ids.add(((Number) r).longValue());
        }
        return ids;
    }

    /**
     * JCLAW-527: hybrid recall on the Postgres backend. Two separately-indexed
     * legs — {@code ts_rank} keyword rows and an HNSW-servable cosine KNN id
     * list — fused by Reciprocal Rank Fusion instead of the old single-query
     * weighted sum (0.3 ts_rank + 0.7 cosine), which mixed an unbounded and a
     * bounded score scale and wrapped the {@code ORDER BY} in an expression no
     * ANN index can serve. Fusion, hydration, and the optional rerank are the
     * same {@link #fuseHydrateRerank} code path the Lucene backend runs, per
     * the story's shared-contract AC. Degrades hybrid → FTS → LIKE, same as
     * before.
     */
    private List<MemoryEntry> hybridSearch(String agentId, String query, int limit) {
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
        try {
            var embedding = generateEmbedding(query);
            if (embedding == null) {
                return fullTextSearch(agentId, query, limit);
            }
            List<Memory> keywordLeg = pgFtsRows(pk, query, limit);
            List<Long> vectorLeg = pgVectorIds(pk, embedding, limit);
            if (vectorLeg.isEmpty()) {
                return toEntriesRankScored(keywordLeg);
            }
            var keywordIds = keywordLeg.stream().map(m -> m.id).toList();
            var preloaded = new HashMap<Long, Memory>();
            for (var m : keywordLeg) preloaded.put(m.id, m);
            return fuseHydrateRerank(agentId, query, keywordIds, vectorLeg, preloaded, limit);
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY, "Hybrid search failed, falling back to FTS: %s".formatted(e.getMessage()));
            return fullTextSearch(agentId, query, limit);
        }
    }

    /**
     * JCLAW-555: hybrid recall on the Lucene HNSW backend (H2 / any non-Postgres
     * dialect). Two legs — the existing scored FTS search and a KNN
     * cosine-similarity query over the MEMORY scope's vector field — fused by
     * Reciprocal Rank Fusion (k = 60) through the same
     * {@link #fuseHydrateRerank} path the Postgres backend runs (JCLAW-527).
     * Degrades to FTS-only when the query embedding is unavailable (no provider,
     * embeddings endpoint down) or the KNN leg fails.
     */
    private List<MemoryEntry> luceneHybridSearch(String agentId, String query, int limit) {
        var embedding = generateEmbedding(query);
        if (embedding == null) {
            return likeSearch(agentId, query, limit);
        }
        var fts = Memory.searchByTextScored(agentId, query, limit);
        List<ScoredId> knn;
        try {
            knn = DirectLuceneMessageSearchRepository.searchMemoryIdsByVector(agentId, embedding, limit);
        } catch (IOException e) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY,
                    "Lucene KNN search failed, falling back to FTS: %s".formatted(e.getMessage()));
            knn = List.of();
        }
        if (knn.isEmpty()) {
            return fts.stream().map(s -> toEntry(s.memory(), s.relevance())).toList();
        }

        var ftsIds = fts.stream().map(s -> s.memory().id).toList();
        var knnIds = knn.stream().map(ScoredId::id).toList();
        var preloaded = new HashMap<Long, Memory>();
        for (var s : fts) preloaded.put(s.memory().id, s.memory());
        return fuseHydrateRerank(agentId, query, ftsIds, knnIds, preloaded, limit);
    }

    /**
     * JCLAW-527: the shared retrieval contract — RRF fusion (k = 60), agent-
     * bounded hydration, and the optional cross-encoder rerank — written once,
     * above the backend seam. The Lucene path (H2, all dev/test) and the
     * Postgres path (production) both land here, so the fusion logic the AC
     * cares about is exercised by every test run, not only on live Postgres.
     *
     * <p>Legs arrive as best-first id lists plus whatever {@code Memory} rows
     * the caller already loaded; KNN-only ids are hydrated re-bounded to the
     * agent so a stale/foreign index id can never leak another agent's memory
     * into recall (same guard as {@code Memory.searchByTextScored}).
     *
     * <p>When a rerank is active it runs over the full fused-and-hydrated
     * shortlist (up to two legs' worth of candidates, before the limit cut) and
     * the result carries rank-derived relevance — the cross-encoder's opinion
     * replaces the fused scores, otherwise downstream importance blending
     * (JCLAW-40) would re-sort on scores the rerank just overruled.
     */
    private List<MemoryEntry> fuseHydrateRerank(String agentId, String query,
            List<Long> keywordIds, List<Long> vectorIds,
            HashMap<Long, Memory> preloaded, int limit) {
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K, keywordIds, vectorIds);

        var missing = fused.stream().map(ReciprocalRankFusion.Ranked::id)
                .filter(id -> !preloaded.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            Long pk = pkOrNull(agentId);
            List<Memory> rows = pk == null ? List.of()
                    : Memory.find("agent.id = ?1 AND id IN (?2) AND supersededAt IS NULL",
                            pk, missing).fetch();
            for (var m : rows) preloaded.put(m.id, m);
        }

        // The hydrated shortlist in fused order (stale index ids drop out here).
        var shortlist = new ArrayList<Memory>(fused.size());
        var scores = new ArrayList<Double>(fused.size());
        for (var r : fused) {
            var m = preloaded.get(r.id());
            if (m != null) {
                shortlist.add(m);
                scores.add(r.score());
            }
        }

        if (MemoryReranker.active() && shortlist.size() > 1) {
            var order = MemoryReranker.rerank(query,
                    shortlist.stream().map(m -> m.text).toList());
            var out = new ArrayList<MemoryEntry>(Math.min(limit, order.size()));
            int n = order.size();
            for (int pos = 0; pos < n && out.size() < limit; pos++) {
                double relevance = n <= 1 ? 1.0 : 1.0 - ((double) pos / (n - 1));
                out.add(toEntry(shortlist.get(order.get(pos)), relevance));
            }
            return out;
        }

        var out = new ArrayList<MemoryEntry>(Math.min(limit, shortlist.size()));
        for (int i = 0; i < shortlist.size() && out.size() < limit; i++) {
            out.add(toEntry(shortlist.get(i), scores.get(i)));
        }
        return out;
    }

    // --- Embedding helpers ---

    /**
     * JCLAW-555: Lucene-side embedding persistence. Re-upserts the memory's
     * MEMORY-scope document with the KNN vector field — repeating text and agent
     * key because {@code updateDocument} replaces whole documents (the
     * {@code @PostPersist} hook indexed the FTS-only doc a moment earlier; it
     * can't carry the vector because embedding generation is an LLM call that
     * must never run inside a JPA lifecycle callback).
     *
     * <p>Known trade-off: an update through any other write path re-fires the
     * entity hook without a vector, dropping the doc out of the KNN graph until
     * re-stored (FTS still matches it). Re-embedding on update is JCLAW-527-era
     * work.
     */
    private void generateAndIndexEmbedding(Memory memory) {
        var embedding = generateEmbedding(memory.text);
        if (embedding == null) return;
        LuceneIndexer.upsert(LuceneIndexer.Scope.MEMORY, memory.id, memory.text,
                String.valueOf(memory.agent.id), embedding);
    }

    private void generateAndStoreEmbedding(Memory memory) {
        try {
            var embedding = generateEmbedding(memory.text);
            if (embedding != null) {
                // Store embedding as raw SQL since JPA doesn't natively handle pgvector
                var sql = "UPDATE memory SET embedding = ?::text::vector WHERE id = ?";
                var conn = JPA.em().unwrap(Connection.class);
                try (var stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, toVectorLiteral(embedding));
                    stmt.setLong(2, (Long) memory.id);
                    stmt.executeUpdate();
                }
            }
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY, "Failed to generate embedding: %s".formatted(e.getMessage()));
        }
    }

    /**
     * Cache for {@link #generateEmbedding} results (JCLAW-206). Embeddings are
     * deterministic — the same {@code (model, text)} input always produces the
     * same {@code float[]} output — so the cache is safe with no TTL needed for
     * correctness. The 24-hour {@code expireAfterWrite} is purely defensive: if
     * a provider silently version-bumps a model behind the same name, bounding
     * cache age caps the staleness window. {@code maximumSize=10_000} bounds
     * heap (around 60 MB worst case for 1536-dim float vectors).
     */
    private static final Cache<EmbeddingKey, float[]> embeddingCache = Caches.named(
            "llm-embeddings",
            CacheConfig.newBuilder()
                    .expireAfterWrite(Duration.ofHours(24))
                    .maximumSize(10_000)
                    .recordStats(true)
                    .build());

    /**
     * Cache key for {@link #embeddingCache}. The {@code (model, textHash)} tuple
     * prevents cross-model collision — switching the configured model implicitly
     * invalidates because keys no longer match. {@code textHash} is the SHA-256
     * hex of the source text, so the key is a fixed 64-char string regardless of
     * text length: this keeps the documented heap bound accurate (the unbounded
     * {@code @Column TEXT} is no longer retained as the key) and stops pinning the
     * text of deleted {@code Memory} rows for the 24h {@code expireAfterWrite}
     * window. SHA-256 (256-bit) makes a collision — which would silently return
     * the wrong embedding for a different text — astronomically unlikely.
     */
    private record EmbeddingKey(String model, String textHash) {}

    private static String hashText(String text) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable — JDK install broken?", e);
        }
    }

    private float[] generateEmbedding(String text) {
        // Test seam: canned embeddings bypass the provider AND the shared cache
        // so cross-test pollution through the static Caffeine instance is
        // impossible (the cache would otherwise pin a canned vector under the
        // real model's key for 24h).
        var override = embedderOverride;
        if (override != null) {
            return override.apply(text);
        }
        return embeddingCache.get(new EmbeddingKey(vectorModel, hashText(text)), k -> {
            try {
                var provider = ProviderRegistry.getPrimary();
                if (provider == null) return null;
                // Embeddings are computed lazily inside a Caffeine cache load —
                // the chat-channel context that triggered the lookup isn't
                // available here, so the call records under "unknown" channel
                // for dispatcher_wait. Acceptable: embeddings hit a different
                // provider endpoint than chat and are typically cheap.
                // The key carries only a hash of the text, so the raw text is
                // captured from the enclosing scope rather than read off the key.
                return provider.embeddings(k.model(), text, null);
            } catch (Exception e) {
                EventLogger.warn(EVENT_CATEGORY_MEMORY, "Embedding generation failed: %s".formatted(e.getMessage()));
                return null;
            }
        });
    }

    private String toVectorLiteral(float[] embedding) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    // --- Helpers ---

    private MemoryEntry toEntry(Memory m) {
        return new MemoryEntry(
                m.id.toString(),
                String.valueOf(m.agent.id),   // lazy proxy supplies the id without loading the Agent
                m.text,
                m.category,
                m.importance,
                m.createdAt
        );
    }

    private MemoryEntry toEntry(Memory m, double relevance) {
        return new MemoryEntry(
                m.id.toString(),
                String.valueOf(m.agent.id),
                m.text,
                m.category,
                m.importance,
                m.createdAt,
                relevance
        );
    }

    /**
     * JCLAW-532: the Postgres FTS-only query returns rows already ordered by
     * {@code ts_rank}, but doesn't surface the raw score cheaply, so relevance
     * is approximated from rank position (top = 1.0) — preserving the existing
     * ordering while giving the recall blend a per-entry relevance. Since
     * JCLAW-527 the hybrid path no longer comes through here — it carries real
     * RRF fused scores; this remains only for the keyword-only (vector
     * disabled / degraded) Postgres path.
     */
    private List<MemoryEntry> toEntriesRankScored(List<Memory> ordered) {
        int n = ordered.size();
        var out = new ArrayList<MemoryEntry>(n);
        for (int i = 0; i < n; i++) {
            double relevance = n <= 1 ? 1.0 : 1.0 - ((double) i / (n - 1));
            out.add(toEntry(ordered.get(i), relevance));
        }
        return out;
    }

    /** Parse an agent-id string to its PK, or null when null/non-numeric. */
    private static Long pkOrNull(String agentId) {
        if (agentId == null) return null;
        try {
            return Long.valueOf(agentId.strip());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Resolve the owning agent for a write. With the real FK (JCLAW-537) the
     * agent must exist; a missing/invalid id is a programming error, surfaced
     * loudly rather than persisting an orphan.
     */
    private static Agent resolveAgent(String agentId) {
        Long pk = pkOrNull(agentId);
        Agent agent = pk == null ? null : Agent.findById(pk);
        if (agent == null) {
            throw new IllegalArgumentException("Cannot store memory: no agent with id " + agentId);
        }
        return agent;
    }
}
