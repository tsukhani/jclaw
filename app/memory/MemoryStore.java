package memory;

import java.time.Instant;
import java.util.List;

/**
 * Pluggable memory backend interface. Implementations provide store, search, delete, list.
 * The base contract is minimal — backends add richness internally.
 * <p>
 * Backends:
 * - JpaMemoryStore (default): H2/PostgreSQL with optional pgvector
 * - Neo4jMemoryStore (opt-in): requires neo4j-java-driver in lib/
 */
public interface MemoryStore {

    record MemoryEntry(
            String id,
            String agentId,
            String text,
            String category,
            Instant createdAt
    ) {}

    String store(String agentId, String text, String category);

    List<MemoryEntry> search(String agentId, String query, int limit);

    void delete(String id);

    List<MemoryEntry> list(String agentId);
}
