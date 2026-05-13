package memory;

import models.Memory;
import play.Play;
import play.cache.CacheConfig;
import play.cache.Caches;
import play.db.jpa.JPA;
import services.EventLogger;

import java.time.Duration;
import java.util.List;

/**
 * JPA-backed memory store. Uses LIKE for H2 (dev/test), PostgreSQL full-text search
 * in production, and optional pgvector hybrid search when enabled.
 */
public class JpaMemoryStore implements MemoryStore {

    private final boolean vectorEnabled;
    private final boolean isPostgres;
    private final String vectorModel;
    private final int vectorDimensions;

    public JpaMemoryStore() {
        var dbUrl = Play.configuration.getProperty("db.url", "");
        this.isPostgres = dbUrl.contains("postgresql") || dbUrl.contains("postgres");
        this.vectorEnabled = "true".equals(
                Play.configuration.getProperty("memory.jpa.vector.enabled", "false"));
        this.vectorModel = Play.configuration.getProperty("memory.jpa.vector.model", "text-embedding-3-small");
        this.vectorDimensions = Integer.parseInt(
                Play.configuration.getProperty("memory.jpa.vector.dimensions", "1536"));

        if (vectorEnabled) {
            EventLogger.info("memory", "JPA memory store with pgvector enabled (model: %s, dims: %d)"
                    .formatted(vectorModel, vectorDimensions));
        }
    }

    @Override
    public String store(String agentId, String text, String category) {
        var memory = new Memory();
        memory.agentId = agentId;
        memory.text = text;
        memory.category = category;
        memory.save();

        if (vectorEnabled) {
            generateAndStoreEmbedding(memory);
        }

        return memory.id.toString();
    }

    @Override
    public List<MemoryEntry> search(String agentId, String query, int limit) {
        if (vectorEnabled && isPostgreSQL()) {
            return hybridSearch(agentId, query, limit);
        }
        if (isPostgreSQL()) {
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
        List<Memory> memories = Memory.find("agentId = ?1 ORDER BY updatedAt DESC", agentId)
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
        return JPA.em().createQuery("DELETE FROM Memory m WHERE m.agentId = :agentId")
                .setParameter("agentId", agentId)
                .executeUpdate();
    }

    // --- Search strategies ---

    private List<MemoryEntry> likeSearch(String agentId, String query, int limit) {
        return Memory.searchByText(agentId, query, limit).stream()
                .map(this::toEntry)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<MemoryEntry> fullTextSearch(String agentId, String query, int limit) {
        // Single native query returning full Memory entities ranked by FTS score.
        // Avoids the prior two-query pattern (IDs then re-fetch) and the in-memory re-sort.
        var sql = """
                SELECT m.* FROM memory m
                WHERE m.agent_id = ?1
                AND to_tsvector('english', m.text) @@ plainto_tsquery('english', ?2)
                ORDER BY ts_rank(to_tsvector('english', m.text), plainto_tsquery('english', ?2)) DESC
                """;
        try {
            List<Memory> memories = JPA.em().createNativeQuery(sql, Memory.class)
                    .setParameter(1, agentId)
                    .setParameter(2, query)
                    .setMaxResults(limit)
                    .getResultList();
            return memories.stream().map(this::toEntry).toList();
        } catch (Exception e) {
            EventLogger.warn("memory", "PG FTS failed, falling back to LIKE search: %s".formatted(e.getMessage()));
            return likeSearch(agentId, query, limit);
        }
    }

    @SuppressWarnings("unchecked")
    private List<MemoryEntry> hybridSearch(String agentId, String query, int limit) {
        // Combine PG full-text search + pgvector cosine similarity in a single query.
        try {
            var embedding = generateEmbedding(query);
            if (embedding == null) {
                return fullTextSearch(agentId, query, limit);
            }

            var embeddingStr = toVectorLiteral(embedding);
            var sql = """
                    SELECT m.* FROM memory m
                    WHERE m.agent_id = ?1
                    AND m.embedding IS NOT NULL
                    ORDER BY (COALESCE(ts_rank(to_tsvector('english', m.text), plainto_tsquery('english', ?2)), 0) * 0.3
                             + (1 - (m.embedding <=> ?3::text::vector)) * 0.7) DESC
                    """;

            List<Memory> memories = JPA.em().createNativeQuery(sql, Memory.class)
                    .setParameter(1, agentId)
                    .setParameter(2, query)
                    .setParameter(3, embeddingStr)
                    .setMaxResults(limit)
                    .getResultList();
            return memories.stream().map(this::toEntry).toList();
        } catch (Exception e) {
            EventLogger.warn("memory", "Hybrid search failed, falling back to FTS: %s".formatted(e.getMessage()));
            return fullTextSearch(agentId, query, limit);
        }
    }

    // --- Embedding helpers ---

    private void generateAndStoreEmbedding(Memory memory) {
        try {
            var embedding = generateEmbedding(memory.text);
            if (embedding != null) {
                // Store embedding as raw SQL since JPA doesn't natively handle pgvector
                var sql = "UPDATE memory SET embedding = ?::text::vector WHERE id = ?";
                var conn = JPA.em().unwrap(java.sql.Connection.class);
                try (var stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, toVectorLiteral(embedding));
                    stmt.setLong(2, (Long) memory.id);
                    stmt.executeUpdate();
                }
            }
        } catch (Exception e) {
            EventLogger.warn("memory", "Failed to generate embedding: %s".formatted(e.getMessage()));
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
    private static final play.cache.Cache<EmbeddingKey, float[]> embeddingCache = Caches.named(
            "llm-embeddings",
            CacheConfig.newBuilder()
                    .expireAfterWrite(Duration.ofHours(24))
                    .maximumSize(10_000)
                    .recordStats(true)
                    .build());

    /**
     * Cache key for {@link #embeddingCache}. The {@code (model, text)} tuple
     * prevents cross-model collision — switching the configured model
     * implicitly invalidates because keys no longer match.
     */
    private record EmbeddingKey(String model, String text) {}

    private float[] generateEmbedding(String text) {
        return embeddingCache.get(new EmbeddingKey(vectorModel, text), k -> {
            try {
                var provider = llm.ProviderRegistry.getPrimary();
                if (provider == null) return null;
                // Embeddings are computed lazily inside a Caffeine cache load —
                // the chat-channel context that triggered the lookup isn't
                // available here, so the call records under "unknown" channel
                // for dispatcher_wait. Acceptable: embeddings hit a different
                // provider endpoint than chat and are typically cheap.
                return provider.embeddings(k.model(), k.text(), null);
            } catch (Exception e) {
                EventLogger.warn("memory", "Embedding generation failed: %s".formatted(e.getMessage()));
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

    private boolean isPostgreSQL() {
        return isPostgres;
    }

    private MemoryEntry toEntry(Memory m) {
        return new MemoryEntry(
                m.id.toString(),
                m.agentId,
                m.text,
                m.category,
                m.createdAt
        );
    }
}
