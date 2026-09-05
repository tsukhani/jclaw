package memory;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import llm.LlmProvider;
import llm.ProviderRegistry;
import models.Agent;
import models.Memory;
import org.hibernate.Session;
import play.Play;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import play.db.DB;
import play.db.jpa.JPA;
import services.ConfigService;
import services.EventLogger;
import services.Tx;
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
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    private final String vectorProvider;
    private final String vectorModel;
    private final int vectorDimensions;

    /**
     * Test seam for embedding generation (mirrors {@code MemoryAutoCapture.Extractor}):
     * when set, {@link #generateEmbedding} returns {@code override.apply(text)}
     * directly — no provider call, no shared cache — so tests can drive the
     * Lucene KNN path with canned vectors. Volatile: tests set/clear it around
     * the {@code LuceneTestSync} lock while production threads read it.
     */
    private static volatile Function<String, float[]> embedderOverride;

    /** Test-only: install (or clear with {@code null}) a canned embedder. */
    public static void setEmbedderForTest(Function<String, float[]> override) {
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
        boolean enabled = MemoryVectorSettings.enabled();
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
        this.vectorProvider = MemoryVectorSettings.provider();
        this.vectorModel = MemoryVectorSettings.model();
        this.vectorDimensions = MemoryVectorSettings.dimensions();

        if (vectorEnabled) {
            EventLogger.info(EVENT_CATEGORY_MEMORY,
                    "JPA memory store with vector search enabled: backend=%s (model: %s, dims: %d)"
                            .formatted(isPostgres ? "pgvector" : "lucene-hnsw", vectorModel, vectorDimensions));
        }
    }

    /**
     * The active dialect, for callers that need to know which vector backend applies
     * without constructing a store — construction re-runs pgvector provisioning
     * (JCLAW-935).
     */
    public static boolean isPostgresDialect() {
        return detectPostgres();
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
        var memory = persistRow(agentId, text, category, importance);
        if (vectorEnabled) {
            embedMemory(memory);
        }
        return memory.id.toString();
    }

    /**
     * JCLAW-807-follow-up: persist the row only, deferring the (blocking) vector
     * embedding to {@link #embedStored}. The memory-auto-capture apply phase uses
     * this so its write transaction never spans the embedding HTTP call — the row
     * commits, then the embedding is generated and written outside that tx. The
     * non-vector path is identical to {@link #store} (there is nothing to defer).
     */
    @Override
    public String storeDeferred(String agentId, String text, String category, double importance) {
        return storeDeferred(agentId, text, category, importance, null);
    }

    @Override
    public String storeDeferred(String agentId, String text, String category, double importance,
            String retrievalKey) {
        return persistRow(agentId, text, category, importance, retrievalKey).id.toString();
    }

    private Memory persistRow(String agentId, String text, String category, double importance) {
        return persistRow(agentId, text, category, importance, null);
    }

    private Memory persistRow(String agentId, String text, String category, double importance,
            String retrievalKey) {
        var memory = new Memory();
        memory.agent = resolveAgent(agentId);   // JCLAW-537: real FK — the agent must exist
        memory.text = text;
        memory.category = category;
        memory.importance = importance;
        memory.retrievalKey = retrievalKey;
        memory.save();
        return memory;
    }

    /**
     * What the search index and the embedding see: the statement plus the questions it
     * answers (JCLAW-529). Both legs get it — the vector leg so a relation-phrased
     * question matches question text rather than a statement, and the keyword leg
     * because the questions carry relation vocabulary ("children", "kids") that the
     * statement does not, giving BM25 a term to match that it previously had no route to.
     *
     * <p>Never what a prompt renders: {@code Memory.text} is still the payload, so a
     * key cannot leak generated questions into the model's context.
     */
    public static String searchText(String text, String retrievalKey) {
        return (retrievalKey == null || retrievalKey.isBlank()) ? text : text + "\n" + retrievalKey;
    }

    /**
     * JCLAW-555: dialect-split embedding storage for a freshly-persisted, still-
     * attached row. Postgres writes the pgvector column; every other dialect
     * re-upserts the row's Lucene MEMORY doc with a KNN vector field. No pgvector
     * SQL is ever attempted on H2 (the pre-555 gap: the raw ::vector UPDATE ran on
     * any dialect whenever the flag was on, failing on every store).
     */
    private void embedMemory(Memory memory) {
        if (isPostgres) {
            generateAndStoreEmbedding(memory);
        } else {
            generateAndIndexEmbedding(memory);
        }
    }

    @Override
    public List<MemoryEntry> search(String agentId, String query, int limit) {
        return search(agentId, query, limit, null);
    }

    /**
     * JCLAW-960: {@code queryEmbedding} is the vector {@link #embedQuery} produced outside
     * the caller's transaction. When it is null the vector leg embeds here, which is the
     * pre-960 behavior and still correct for callers that own no transaction boundary to
     * hoist the call out of (the operator introspection and eval endpoints).
     */
    @Override
    public List<MemoryEntry> search(String agentId, String query, int limit, float[] queryEmbedding) {
        if (vectorEnabled) {
            var embedding = queryEmbedding != null ? queryEmbedding : generateQueryEmbedding(query);
            return isPostgres
                    ? hybridSearch(agentId, query, limit, embedding)
                    : luceneHybridSearch(agentId, query, limit, embedding);
        }
        if (isPostgres) {
            return fullTextSearch(agentId, query, limit);
        }
        return likeSearch(agentId, query, limit);
    }

    /** {@inheritDoc} */
    @Override
    public float[] embedQuery(String query) {
        if (!vectorEnabled || query == null || query.isBlank()) return null;
        return generateQueryEmbedding(query);
    }

    public static final String KEY_QUERY_PREFIX = "memory.jpa.vector.queryPrefix";

    /**
     * JCLAW-529: instruction prefix for <em>query</em> embeddings only.
     *
     * <p>Asymmetric retrieval models are trained with queries and documents in different
     * input formats and degrade badly when both are embedded the same way. Measured on this
     * corpus with {@code snowflake-arctic-embed} (which wants
     * {@code "Represent this sentence for searching relevant passages: "} on queries and
     * documents bare): the memory answering "what do I call my children?" ranked <b>54th of
     * 89</b> by cosine unprefixed and <b>16th</b> prefixed; with retrieval keys indexed,
     * 13th against <b>2nd</b>. Unprefixed the whole corpus bunched into 0.65–0.69 — a
     * near-uniform similarity that carries almost no ranking signal.
     *
     * <p>Empty by default because the correct string is a property of the model, and a wrong
     * prefix is worse than none. Documents are never prefixed, so setting this needs no
     * re-embedding — only the query path changes.
     *
     * <p><b>Re-sweep {@link #KEY_RECALL_MIN_COSINE} whenever this changes.</b> The prefix
     * moves the absolute scale, not only the order: the same corpus tops out near 0.35
     * prefixed against 0.69 bare, so the 0.60 floor written for the bare scale would reject
     * every vector leg on every query and silently leave recall keyword-only.
     */
    private String queryPrefix() {
        return ConfigService.get(KEY_QUERY_PREFIX, "");
    }

    /**
     * Embed a search query. Distinct from {@link #generateEmbedding}, which stays bare and
     * is what documents and the symmetric dedup comparison in {@link #semanticNeighbours}
     * must keep using — prefixing a document would compare a query format against itself.
     */
    private float[] generateQueryEmbedding(String query) {
        return generateEmbedding(prefixQuery(queryPrefix(), query));
    }

    /** Pure half of {@link #generateQueryEmbedding}, so the rule is testable without
     *  flipping a process-global config key across concurrent test lanes. */
    public static String prefixQuery(String prefix, String query) {
        return (prefix == null || prefix.isEmpty()) ? query : prefix + query;
    }

    /**
     * JCLAW-922: the semantic leg of capture-time dedup. Embeds {@code text} (no
     * transaction held — see the interface contract), then asks the vector backend
     * for its nearest stored memories and keeps those at or above {@code minCosine}.
     *
     * <p>Lucene reports a {@code COSINE} KNN hit as {@code (1 + cosine) / 2}, so the
     * raw score is rescaled before comparison. Postgres orders by the cosine-distance
     * operator without returning the distance, so its leg re-reads the stored vector
     * to score; both dialects therefore threshold on a real cosine rather than on a
     * rank-derived or RRF-fused number, neither of which is a similarity.
     */
    @Override
    public List<Long> semanticNeighbours(String agentId, String text, int limit, double minCosine) {
        return semanticNeighbours(agentId, text, null, limit, minCosine);
    }

    /**
     * JCLAW-529: the candidate is embedded exactly as a stored memory is — statement plus
     * the questions it answers — because this compares it against those stored vectors.
     *
     * <p>Embedding it bare against a keyed index is not a stricter test, it is a different
     * one: measured on this corpus, an <em>identical</em> memory scored a mean 0.869 that
     * way (min 0.710) against a 0.90 threshold, so the semantic leg stopped firing on the
     * exact duplicates it exists to catch. Nothing looked wrong, because the deterministic
     * Jaccard rule still caught those and the corpus showed no survivors — it was the
     * paraphrases only this leg can catch that were silently getting through.
     */
    @Override
    public List<Long> semanticNeighbours(String agentId, String text, String retrievalKey,
            int limit, double minCosine) {
        if (!vectorEnabled || text == null || text.isBlank()) return List.of();
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
        var embedding = generateEmbedding(searchText(text, retrievalKey));
        if (embedding == null) return List.of();
        try {
            return isPostgres
                    ? pgSemanticNeighbours(pk, embedding, limit, minCosine)
                    : luceneSemanticNeighbours(agentId, embedding, limit, minCosine);
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY,
                    "Semantic dedup lookup failed, falling back to lexical only: %s".formatted(e.getMessage()));
            return List.of();
        }
    }

    private List<Long> luceneSemanticNeighbours(String agentId, float[] embedding, int limit, double minCosine)
            throws IOException {
        var out = new ArrayList<Long>();
        for (var hit : DirectLuceneMessageSearchRepository.searchMemoryIdsByVector(agentId, embedding, limit)) {
            if (2 * hit.score() - 1 >= minCosine) out.add(hit.id());
        }
        return activeOnly(agentId, out);
    }

    /**
     * Drop ids whose row is superseded or gone. The pgvector leg filters
     * {@code superseded_at IS NULL} in SQL; the Lucene leg gets its ids from the index,
     * which can briefly hold a document for a row superseded since it was written. Without
     * this a superseded fact could NOOP a re-emerging new one as a semantic duplicate —
     * the exact thing JCLAW-525 exists to prevent. Runs in its own short read transaction:
     * the caller is outside one by contract, because it just embedded.
     */
    private List<Long> activeOnly(String agentId, List<Long> ids) {
        if (ids.isEmpty()) return ids;
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
        List<Memory> rows = Tx.run(() -> Memory.find(
                "agent.id = ?1 AND id IN (?2) AND supersededAt IS NULL", pk, ids).fetch());
        var alive = rows.stream().map(m -> m.id).collect(Collectors.toSet());
        return ids.stream().filter(alive::contains).toList();
    }

    private List<Long> pgSemanticNeighbours(Long pk, float[] embedding, int limit, double minCosine) {
        var sql = """
                SELECT m.id FROM memory m
                WHERE m.agent_id = ?1 AND m.embedding IS NOT NULL AND m.superseded_at IS NULL
                AND 1 - (m.embedding <=> ?2::text::vector) >= ?3
                ORDER BY m.embedding <=> ?2::text::vector
                """;
        List<?> rows = Tx.run(() -> JPA.em().createNativeQuery(sql)
                .setParameter(1, pk)
                .setParameter(2, toVectorLiteral(embedding))
                .setParameter(3, minCosine)
                .setMaxResults(limit)
                .getResultList());
        var ids = new ArrayList<Long>(rows.size());
        for (Object r : rows) ids.add(((Number) r).longValue());
        return ids;
    }

    @Override
    public void delete(String id) {
        var memory = Memory.findById(Long.parseLong(id));
        if (memory != null) {
            // deleteWithLineage, not delete(): this is the store-level choke point every
            // caller reaches, including the memory tool's forget action. Routing only the
            // admin controller through it (as JCLAW-529 first did) left forget able to
            // strand supersession pointers, which is how the 265 accumulated.
            ((Memory) memory).deleteWithLineage();
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
        int deleted = JPA.em().createQuery("DELETE FROM Memory m WHERE m.agent.id = :agentId")
                .setParameter("agentId", pk)
                .executeUpdate();
        // JCLAW-820: the bulk JPQL DELETE bypasses @PostRemove, so the agent's
        // MEMORY-scope FTS + HNSW vector docs would orphan. Evict them in one
        // race-free delete by the agent-field term (mirrors the JCLAW-673 evict
        // pattern the other scopes use for their bulk deletes).
        //
        // JCLAW-1014: deferred to after the caller's transaction commits. removeByAgent
        // fsyncs, and AgentDeletionCascade calls this at the top of a cascade that still
        // has to delete config rows, evict four other scopes and cascade the Agent row —
        // so a throw anywhere after this point used to leave the rows alive in the DB with
        // their documents permanently gone, unreachable because init() only backfills a
        // scope whose docCount() is 0. Same afterCompletion shape as
        // ConfigService.scheduleRollbackEviction.
        evictAfterCommit(pk);
        return deleted;
    }

    /**
     * Evict {@code pk}'s MEMORY documents once the caller's transaction commits, never on a
     * rollback. Registered rather than called inline because this store does not own the
     * transaction boundary and cannot know what the caller still has left to do.
     */
    private static void evictAfterCommit(Long pk) {
        JPA.em().unwrap(Session.class).getTransaction().registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                // no-op: the evict/skip decision is made after completion
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                    LuceneIndexer.removeByAgent(LuceneIndexer.Scope.MEMORY, String.valueOf(pk));
                }
            }
        });
    }

    // --- Search strategies ---

    /** JCLAW-615: the PG chain's terminal fallback. Goes STRAIGHT to the
     *  SQL substring scan — the chain exists because SQL constructs failed,
     *  and re-entering the search abstraction here made the result depend on
     *  JVM-global Lucene index state (a concurrent test/caller opening the
     *  index flipped recall onto an index missing the rows). */
    private List<MemoryEntry> sqlLikeSearch(String agentId, String query, int limit) {
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
        return Memory.likeFallback(pk, query, limit).stream()
                .map(s -> toEntry(s.memory(), s.relevance()))
                .toList();
    }

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
            return sqlLikeSearch(agentId, query, limit);
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
            SELECT m.id, 1 - (m.embedding <=> ?2::text::vector) AS cos FROM memory m
            WHERE m.agent_id = ?1 AND m.embedding IS NOT NULL AND m.superseded_at IS NULL
            ORDER BY m.embedding <=> ?2::text::vector
            """;

    private List<Long> pgVectorIds(Long pk, float[] embedding, int limit) {
        // JCLAW-940: selects the cosine so the leg can be gated on its best hit in Java,
        // matching the Lucene branch. The ORDER BY stays the bare operator the HNSW index
        // serves — only the projection is added, never a wrapping expression.
        List<?> rows = JPA.em().createNativeQuery(PG_VECTOR_LEG_SQL)
                .setParameter(1, pk)
                .setParameter(2, toVectorLiteral(embedding))
                .setMaxResults(limit)
                .getResultList();
        if (rows.isEmpty()) return List.of();
        var ids = new ArrayList<Long>(rows.size());
        double best = -1;
        for (Object r : rows) {
            var cols = (Object[]) r;
            ids.add(((Number) cols[0]).longValue());
            best = Math.max(best, ((Number) cols[1]).doubleValue());
        }
        return vectorLegAboveFloor(best) ? ids : List.of();
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> semanticMatchesForQuery(String agentId, String query, int limit, double minCosine) {
        if (!vectorEnabled || query == null || query.isBlank()) return List.of();
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
        var embedding = generateQueryEmbedding(query);
        if (embedding == null) return List.of();
        try {
            return isPostgres
                    ? pgSemanticNeighbours(pk, embedding, limit, minCosine)
                    : luceneSemanticNeighbours(agentId, embedding, limit, minCosine);
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY,
                    "Semantic query match failed, falling back to lexical only: %s".formatted(e.getMessage()));
            return List.of();
        }
    }

    /** {@inheritDoc} */
    @Override
    public double bestQueryCosine(String agentId, String query) {
        // Lucene-backed only: the Postgres leg scores through pgvector and this probe would
        // read an index that path never populates.
        if (!vectorEnabled || isPostgres || query == null || query.isBlank()) return Double.NaN;
        var embedding = generateQueryEmbedding(query);
        if (embedding == null) return Double.NaN;
        try {
            var hits = DirectLuceneMessageSearchRepository.searchMemoryIdsByVector(agentId, embedding, 1);
            return hits.isEmpty() ? Double.NaN : 2 * hits.getFirst().score() - 1;
        } catch (IOException _) {
            return Double.NaN;
        }
    }

    /**
     * JCLAW-940: whether the vector leg's <em>best</em> hit clears the floor.
     *
     * <p>All-or-nothing per leg, not per hit. Measured on this corpus, the two
     * distributions overlap in the tail but separate at the head: four invented words top
     * out below 0.60 cosine while a real question's best hit clears 0.62, yet a real
     * question's tenth hit sits down in the same band as the nonsense. Filtering hit by hit
     * therefore removes the tail of good queries — recall-large complete misses rose from
     * 4 to 8 of 150 across the sweep — while only a floor high enough to do that damage
     * silenced the degenerate queries. Gating the leg on its best hit separates them: a
     * query with nothing relevant loses the whole leg, and a query with something relevant
     * keeps all of it.
     *
     * <p>This is also the acceptance criterion read literally — no memory above the
     * threshold means no block, rather than dropping whichever individual memories fall
     * below it.
     */
    private static boolean vectorLegAboveFloor(double bestCosine) {
        return bestCosine >= ConfigService.getDouble(KEY_RECALL_MIN_COSINE, DEFAULT_RECALL_MIN_COSINE);
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
    private List<MemoryEntry> hybridSearch(String agentId, String query, int limit, float[] embedding) {
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
        try {
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
     * Reciprocal Rank Fusion (see {@link #DEFAULT_RECALL_RRF_K}) through the same
     * {@link #fuseHydrateRerank} path the Postgres backend runs (JCLAW-527).
     * Degrades to FTS-only when the query embedding is unavailable (no provider,
     * embeddings endpoint down) or the KNN leg fails.
     */
    private List<MemoryEntry> luceneHybridSearch(String agentId, String query, int limit, float[] embedding) {
        if (embedding == null) {
            return likeSearch(agentId, query, limit);
        }
        var fts = Memory.searchByTextScored(agentId, query, limit);
        List<ScoredId> knn;
        try {
            var hits = DirectLuceneMessageSearchRepository.searchMemoryIdsByVector(agentId, embedding, limit);
            double bestCosine = hits.isEmpty() ? -1 : 2 * hits.getFirst().score() - 1;
            knn = vectorLegAboveFloor(bestCosine) ? hits : List.of();
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

    public static final String KEY_RECALL_MIN_COSINE = "memory.recall.minCosine";

    /**
     * JCLAW-940: absolute cosine a memory must reach to enter recall at all.
     *
     * <p>Both vector legs return the k nearest regardless of distance, and
     * {@link ReciprocalRankFusion#fuse} then divides every fused score by the top one — so
     * the best hit is 1.0 by construction however far away it actually is. The keyword
     * leg's {@code minScoreRatio} is likewise relative to its own top hit. With every
     * signal relative, nothing absolute survives to the selection step, and recall
     * returned a full budget for "hey" and for four invented words.
     *
     * <p>Deliberately the same shape as the capture-dedup floor
     * ({@code memory.autocapture.dedup.cosineThreshold}), which already thresholds on a
     * real cosine on both dialects. The asymmetry was that dedup got this and recall
     * never did.
     *
     * <p>0.60 measured on this corpus with nomic-embed-text-v1.5 at 768 dims. It silences
     * four invented words and a bare "hey" completely, for one question of 150 losing its
     * gold from the top ten (R@10 0.973 to 0.967) with coverage@k and R@1 unchanged. 0.55
     * and 0.58 cost the identical case while gating less, so 0.60 dominates them; 0.62
     * costs three and 0.65 costs seven.
     *
     * <p>It does not gate a coherent question about the wrong subject — "how do I bake
     * sourdough bread at high altitude" tops 0.65 against this corpus — because there the
     * distributions genuinely overlap. The gate removes degenerate queries, not irrelevant
     * ones, and no floor on this embedding model separates the latter.
     *
     * <p>The threshold is a property of the embedding model, not of the code: cosine
     * distributions differ per model, so re-sweep after changing it. The knob is what makes
     * that a config change rather than a release.
     */
    public static final double DEFAULT_RECALL_MIN_COSINE = 0.60;

    public static final String KEY_RRF_K = "memory.recall.rrfK";

    /**
     * JCLAW-938: 5, not the textbook 60, because this pipeline consumes RRF's scores as
     * magnitudes rather than as ranks.
     *
     * <p>{@link ReciprocalRankFusion#fuse} normalizes against the top hit and that value
     * becomes {@link MemoryEntry#relevance()}, which the recall blend weighs against
     * importance. At k=60 consecutive fused scores differ by under 2%, so relevance
     * arrives as a near-constant and importance — spanning 0.2 to 0.9 on a real corpus —
     * decides the order instead. Low k restores the spread the blend was written to weigh.
     *
     * <p>Measured on a live 603-memory corpus over a 150-case suite: R@1 0.393 at k=60
     * against 0.687 at k=5, MRR 0.599 against 0.797, with R@10 barely moving (0.913 to
     * 0.973) — an ordering defect, not a retrieval one. Configurable because the optimum
     * is a property of a corpus, and only the Lucene backend was measured.
     */
    public static final int DEFAULT_RECALL_RRF_K = 5;

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
        var fused = ReciprocalRankFusion.fuse(
                ConfigService.getInt(KEY_RRF_K, DEFAULT_RECALL_RRF_K), keywordIds, vectorIds);
        hydrateMissing(agentId, fused, preloaded);

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
            return rerankedEntries(query, shortlist, limit);
        }

        var out = new ArrayList<MemoryEntry>(Math.min(limit, shortlist.size()));
        for (int i = 0; i < shortlist.size() && out.size() < limit; i++) {
            out.add(toEntry(shortlist.get(i), scores.get(i)));
        }
        return out;
    }

    /** Month abbreviations read as proper nouns by the capitalisation rule, which would
     *  otherwise make every dated memory look like it names an entity. */
    private static final Set<String> NON_ENTITY_CAPITALS = Set.of(
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
            "january", "february", "march", "april", "june", "july", "august", "september",
            "october", "november", "december", "monday", "tuesday", "wednesday", "thursday",
            "friday", "saturday", "sunday");

    /**
     * Entity names in {@code text}: capitalised, alphabetic, not boilerplate. Used by
     * {@link MemoryKeyBackfillService} to find a memory's neighbors when writing its
     * retrieval key.
     */
    public static List<String> entityNames(String text) {
        if (text == null) return List.of();
        var out = new ArrayList<String>();
        for (var tok : text.split("[^A-Za-z]+")) {
            if (tok.length() < 3 || !Character.isUpperCase(tok.charAt(0))) continue;
            var lower = tok.toLowerCase(Locale.ROOT);
            if (MemorySimilarity.BOILERPLATE.contains(lower) || NON_ENTITY_CAPITALS.contains(lower)) continue;
            if (!out.contains(tok)) out.add(tok);
        }
        return out;
    }

    /** Load the fused ids the legs didn't already hydrate, re-bounded to the
     *  agent (and to non-superseded rows) so a stale/foreign index id can
     *  never leak another agent's memory into recall. */
    private void hydrateMissing(String agentId, List<ReciprocalRankFusion.Ranked> fused,
            HashMap<Long, Memory> preloaded) {
        var missing = fused.stream().map(ReciprocalRankFusion.Ranked::id)
                .filter(id -> !preloaded.containsKey(id)).toList();
        if (missing.isEmpty()) return;
        Long pk = pkOrNull(agentId);
        List<Memory> rows = pk == null ? List.of()
                : Memory.find("agent.id = ?1 AND id IN (?2) AND supersededAt IS NULL",
                        pk, missing).fetch();
        for (var m : rows) preloaded.put(m.id, m);
    }

    /** Cross-encoder path: the rerank's order replaces the fused scores, with
     *  relevance derived from rank position (1.0 best → 0.0 worst). */
    private List<MemoryEntry> rerankedEntries(String query, List<Memory> shortlist, int limit) {
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

    // --- Embedding helpers ---

    /**
     * JCLAW-555: Lucene-side embedding persistence. Re-upserts the memory's
     * MEMORY-scope document with the KNN vector field — repeating text and agent
     * key because {@code updateDocument} replaces whole documents (the
     * {@code @PostPersist} hook indexed the FTS-only doc a moment earlier; it
     * can't carry the vector because embedding generation is an LLM call that
     * must never run inside a JPA lifecycle callback).
     *
     * <p>A metadata edit no longer drops the vector: {@code Memory.onIndexUpsert}
     * short-circuits on an unchanged doc identity (JCLAW-921). A text change still
     * would, but nothing mutates {@code Memory.text} after insert.
     */
    private void generateAndIndexEmbedding(Memory memory) {
        var indexed = searchText(memory.text, memory.retrievalKey);
        var embedding = generateEmbedding(indexed);
        if (embedding == null) return;
        LuceneIndexer.upsert(LuceneIndexer.Scope.MEMORY, memory.id, indexed,
                String.valueOf(memory.agent.id), embedding);
    }

    private void generateAndStoreEmbedding(Memory memory) {
        var embedding = generateEmbedding(searchText(memory.text, memory.retrievalKey));
        if (embedding != null) {
            storeEmbeddingSql(memory.id, embedding);
        }
    }

    /** Persist a pre-computed embedding onto the pgvector column via raw SQL (JPA
     *  has no native pgvector binding). Runs inside the caller's transaction. */
    private void storeEmbeddingSql(Long id, float[] embedding) {
        try {
            var sql = "UPDATE memory SET embedding = ?::text::vector WHERE id = ?";
            var conn = JPA.em().unwrap(Connection.class);
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, toVectorLiteral(embedding));
                stmt.setLong(2, id);
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            // JCLAW-1005: generation already SUCCEEDED by the time we get here — this is the
            // pgvector write failing. Blaming generation sent operators to the wrong subsystem,
            // and a dimension mismatch surfaces here first.
            EventLogger.warn(EVENT_CATEGORY_MEMORY,
                    "Failed to PERSIST embedding for memory %d (generation succeeded): %s"
                            .formatted(id, e.getMessage()));
        }
    }

    /**
     * JCLAW-807-follow-up: generate and persist the embedding for a row already
     * written by {@link #storeDeferred}, with the embedding HTTP call held OUTSIDE
     * any DB transaction — the invariant the memory-auto-capture pipeline is built
     * around (no connection pinned during a slow network call). A short read tx
     * snapshots the row's text + agent id, the slow embedding call then runs with
     * no connection held, and the write lands in a fresh short tx: a pgvector
     * UPDATE on Postgres, a Lucene HNSW upsert (no DB) elsewhere. No-op when vector
     * memory is disabled or the id is unknown.
     */
    @Override
    public void embedStored(String id) {
        if (!vectorEnabled) return;
        long pk = Long.parseLong(id);
        var target = Tx.run(() -> {
            Memory m = Memory.findById(pk);
            return m == null ? null
                    : new EmbedTarget(m.id, searchText(m.text, m.retrievalKey), String.valueOf(m.agent.id));
        });
        if (target == null) return;
        var embedding = generateEmbedding(target.text());   // blocking HTTP — no tx held
        if (embedding == null) return;
        if (isPostgres) {
            Tx.run(() -> storeEmbeddingSql(target.id(), embedding));
        } else {
            LuceneIndexer.upsert(LuceneIndexer.Scope.MEMORY, target.id(),
                    target.text(), target.agentId(), embedding);
        }
    }

    /** Fields carried out of the snapshot tx so {@link #embedStored} can embed a
     *  row without holding a connection across the embedding HTTP call. */
    private record EmbedTarget(Long id, String text, String agentId) {}

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
    private record EmbeddingKey(String provider, String model, String textHash) {}

    /**
     * The provider that serves embeddings: {@code memory.jpa.vector.provider} when
     * set, otherwise the registry primary.
     *
     * <p>The explicit key matters because {@code getPrimary()} is just the first
     * provider in alphabetical order unless {@code llm.primaryProvider} pins one — so
     * on a host with several providers configured it can easily resolve to a local
     * chat endpoint that serves no embedding model at all, and every embedding call
     * fails with nothing but a warning to show for it. Embedding models and chat
     * models are chosen independently; this lets them be.
     */
    private LlmProvider embeddingProvider() {
        if (vectorProvider.isBlank()) {
            return ProviderRegistry.getPrimary();
        }
        var named = ProviderRegistry.get(vectorProvider);
        if (named == null) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY,
                    "memory.jpa.vector.provider=%s is not a configured provider — falling back to the primary"
                            .formatted(vectorProvider));
            return ProviderRegistry.getPrimary();
        }
        return named;
    }

    /** Cache-key component: the resolved provider's name, or a marker when none. */
    private String embeddingProviderName() {
        var p = embeddingProvider();
        return p == null ? "none" : p.config().name();
    }

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
        return cachedEmbedding(new EmbeddingKey(embeddingProviderName(), vectorModel, hashText(text)), () -> {
            try {
                var provider = embeddingProvider();
                if (provider == null) return null;
                // Embeddings are computed lazily on a cache miss — the
                // chat-channel context that triggered the lookup isn't
                // available here, so the call records under "unknown" channel
                // for dispatcher_wait. Acceptable: embeddings hit a different
                // provider endpoint than chat and are typically cheap.
                return provider.embeddings(vectorModel, text, null);
            } catch (Exception e) {
                EventLogger.warn(EVENT_CATEGORY_MEMORY, "Embedding generation failed: %s".formatted(e.getMessage()));
                return null;
            }
        });
    }

    /**
     * Cache-through for {@link #embeddingCache} that never holds a monitor
     * across the blocking embeddings round-trip (JCLAW-807). A synchronous
     * {@code Cache.get(key, loader)} computes the loader under Caffeine's
     * per-bin {@code ConcurrentHashMap} monitor — a synchronized block wrapped
     * around the blocking OkHttp call — pinning the virtual thread's carrier
     * for the whole request. The fork is virtual-thread-only, so this fires on
     * every recall cache miss (recall text is usually novel), shrinking the
     * ForkJoinPool carrier pool under concurrent recall. Instead: read via
     * {@link Cache#getIfPresent}, compute outside any cache lock on a miss,
     * then {@link Cache#put} — no monitor is held across the I/O. A rare
     * duplicate compute under a race is correct because embeddings are
     * deterministic (the same {@code (model, text)} always yields the same
     * vector); a {@code null} compute (no provider, failure) is not cached,
     * matching the loader's old null-skip.
     */
    private static float[] cachedEmbedding(EmbeddingKey key, Supplier<float[]> compute) {
        var cached = embeddingCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        var embedding = compute.get();
        if (embedding != null) {
            embeddingCache.put(key, embedding);
        }
        return embedding;
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
                m.createdAt,
                1.0,
                m.recencyAnchor()
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
                relevance,
                m.recencyAnchor()
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
