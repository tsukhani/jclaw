package memory;

import models.Memory;
import play.Play;
import play.db.DB;
import services.EventLogger;

import java.util.List;

/**
 * JPA-backed memory store. Uses LIKE for H2 (dev/test), PostgreSQL full-text search
 * in production, and optional pgvector hybrid search when enabled.
 */
public class JpaMemoryStore implements MemoryStore {

    private final boolean vectorEnabled;
    private final boolean isPostgres;
    private final String vectorProvider;
    private final String vectorModel;
    private final int vectorDimensions;

    public JpaMemoryStore() {
        var dbUrl = Play.configuration.getProperty("db.url", "");
        this.isPostgres = dbUrl.contains("postgresql") || dbUrl.contains("postgres");
        this.vectorEnabled = "true".equals(
                Play.configuration.getProperty("memory.jpa.vector.enabled", "false"));
        this.vectorProvider = Play.configuration.getProperty("memory.jpa.vector.provider", "openai");
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

    // --- Search strategies ---

    private List<MemoryEntry> likeSearch(String agentId, String query, int limit) {
        return Memory.searchByText(agentId, query, limit).stream()
                .map(this::toEntry)
                .toList();
    }

    private List<MemoryEntry> fullTextSearch(String agentId, String query, int limit) {
        // PostgreSQL full-text search using to_tsvector/to_tsquery
        var sql = """
                SELECT m.id FROM memory m
                WHERE m.agent_id = ?
                AND to_tsvector('english', m.text) @@ plainto_tsquery('english', ?)
                ORDER BY ts_rank(to_tsvector('english', m.text), plainto_tsquery('english', ?)) DESC
                LIMIT ?
                """;
        try (var conn = DB.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, agentId);
            stmt.setString(2, query);
            stmt.setString(3, query);
            stmt.setInt(4, limit);
            var rs = stmt.executeQuery();
            var ids = new java.util.ArrayList<Long>();
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
            return ids.stream()
                    .map(id -> (Memory) Memory.findById(id))
                    .filter(m -> m != null)
                    .map(this::toEntry)
                    .toList();
        } catch (Exception e) {
            EventLogger.warn("memory", "PG FTS failed, falling back to LIKE search: %s".formatted(e.getMessage()));
            return likeSearch(agentId, query, limit);
        }
    }

    private List<MemoryEntry> hybridSearch(String agentId, String query, int limit) {
        // Combine PG full-text search + pgvector cosine similarity
        try {
            var embedding = generateEmbedding(query);
            if (embedding == null) {
                return fullTextSearch(agentId, query, limit);
            }

            var embeddingStr = toVectorLiteral(embedding);
            var sql = """
                    SELECT m.id,
                           ts_rank(to_tsvector('english', m.text), plainto_tsquery('english', ?)) AS text_rank,
                           1 - (m.embedding <=> ?::text::vector) AS vector_score
                    FROM memory m
                    WHERE m.agent_id = ?
                    AND m.embedding IS NOT NULL
                    ORDER BY (COALESCE(ts_rank(to_tsvector('english', m.text), plainto_tsquery('english', ?)), 0) * 0.3
                             + (1 - (m.embedding <=> ?::text::vector)) * 0.7) DESC
                    LIMIT ?
                    """;

            try (var conn = DB.getConnection();
                 var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, query);
                stmt.setString(2, embeddingStr);
                stmt.setString(3, agentId);
                stmt.setString(4, query);
                stmt.setString(5, embeddingStr);
                stmt.setInt(6, limit);
                var rs = stmt.executeQuery();
                var ids = new java.util.ArrayList<Long>();
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
                return ids.stream()
                        .map(id -> (Memory) Memory.findById(id))
                        .filter(m -> m != null)
                        .map(this::toEntry)
                        .toList();
            }
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
                try (var conn = DB.getConnection();
                     var stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, toVectorLiteral(embedding));
                    stmt.setLong(2, (Long) memory.id);
                    stmt.executeUpdate();
                }
            }
        } catch (Exception e) {
            EventLogger.warn("memory", "Failed to generate embedding: %s".formatted(e.getMessage()));
        }
    }

    private float[] generateEmbedding(String text) {
        try {
            var provider = llm.ProviderRegistry.getPrimary();
            if (provider == null) return null;
            return llm.OpenAiCompatibleClient.embeddings(provider, vectorModel, text);
        } catch (Exception e) {
            EventLogger.warn("memory", "Embedding generation failed: %s".formatted(e.getMessage()));
            return null;
        }
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
