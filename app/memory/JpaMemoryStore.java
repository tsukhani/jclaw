package memory;

import llm.ProviderRegistry;
import models.Agent;
import models.Memory;
import play.Play;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import play.db.jpa.JPA;
import services.EventLogger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * JPA-backed memory store. Uses LIKE for H2 (dev/test), PostgreSQL full-text search
 * in production, and optional pgvector hybrid search when enabled.
 */
public class JpaMemoryStore implements MemoryStore {

    private static final String EVENT_CATEGORY_MEMORY = "memory";

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
            EventLogger.info(EVENT_CATEGORY_MEMORY, "JPA memory store with pgvector enabled (model: %s, dims: %d)"
                    .formatted(vectorModel, vectorDimensions));
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
            generateAndStoreEmbedding(memory);
        }

        return memory.id.toString();
    }

    @Override
    public List<MemoryEntry> search(String agentId, String query, int limit) {
        if (vectorEnabled && isPostgres) {
            return hybridSearch(agentId, query, limit);
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
        List<Memory> memories = Memory.find("agent.id = ?1 ORDER BY updatedAt DESC", pk)
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

    @SuppressWarnings("unchecked")
    private List<MemoryEntry> fullTextSearch(String agentId, String query, int limit) {
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
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
                    .setParameter(1, pk)
                    .setParameter(2, query)
                    .setMaxResults(limit)
                    .getResultList();
            return toEntriesRankScored(memories);
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY, "PG FTS failed, falling back to LIKE search: %s".formatted(e.getMessage()));
            return likeSearch(agentId, query, limit);
        }
    }

    @SuppressWarnings("unchecked")
    private List<MemoryEntry> hybridSearch(String agentId, String query, int limit) {
        Long pk = pkOrNull(agentId);
        if (pk == null) return List.of();
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
                    .setParameter(1, pk)
                    .setParameter(2, query)
                    .setParameter(3, embeddingStr)
                    .setMaxResults(limit)
                    .getResultList();
            return toEntriesRankScored(memories);
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY, "Hybrid search failed, falling back to FTS: %s".formatted(e.getMessage()));
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
     * JCLAW-532: the Postgres FTS/hybrid queries return rows already ordered by
     * their (ts_rank / vector) score, but don't surface the raw score cheaply, so
     * relevance is approximated from rank position (top = 1.0) — preserving the
     * existing ordering while giving the recall blend a per-entry relevance.
     * Real Postgres relevance scoring is deferred to the retrieval rework
     * (JCLAW-527); the Lucene path (H2/default) already threads true normalized
     * scores via {@link #likeSearch}.
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
