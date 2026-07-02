package memory;

import java.time.Instant;
import java.util.List;

/**
 * Memory backend interface. Implementations provide store, search, delete, list.
 * The base contract is minimal — backends add richness internally.
 * <p>
 * The sole implementation is {@link JpaMemoryStore} (H2/PostgreSQL; pgvector for
 * vector similarity). The interface is retained as a seam for testing and future
 * Postgres-backed strategies.
 */
public interface MemoryStore {

    record MemoryEntry(
            String id,
            String agentId,
            String text,
            String category,
            double importance,
            Instant createdAt,
            double relevance,
            Instant recencyAt
    ) {
        /**
         * Non-recall paths (list, single fetch) carry no search relevance, so this
         * convenience constructor defaults {@code relevance} to 1.0. The recall
         * blend (JCLAW-532) reads {@link #relevance()}; a constant 1.0 makes it
         * degrade to importance ordering when a backend can't supply real scores.
         */
        public MemoryEntry(String id, String agentId, String text, String category,
                           double importance, Instant createdAt) {
            this(id, agentId, text, category, importance, createdAt, 1.0);
        }

        /**
         * Source-compatible with the pre-JCLAW-526 canonical form: defaults the
         * decay anchor ({@code recencyAt}) to {@code createdAt}. The store's
         * recall paths pass the real anchor ({@code Memory.recencyAnchor()} —
         * last content change or last recall access, whichever is newer).
         */
        public MemoryEntry(String id, String agentId, String text, String category,
                           double importance, Instant createdAt, double relevance) {
            this(id, agentId, text, category, importance, createdAt, relevance, createdAt);
        }
    }

    /**
     * Store a memory with an explicit importance score. This is the primary
     * contract (JCLAW-39/40); the three-arg convenience below delegates with a
     * category-derived default importance, keeping pre-existing call sites
     * source-compatible.
     */
    String store(String agentId, String text, String category, double importance);

    default String store(String agentId, String text, String category) {
        return store(agentId, text, category, MemoryCategory.defaultImportanceFor(category));
    }

    List<MemoryEntry> search(String agentId, String query, int limit);

    void delete(String id);

    List<MemoryEntry> list(String agentId);

    default List<MemoryEntry> list(String agentId, int limit, int offset) {
        var all = list(agentId);
        if (offset >= all.size()) return List.of();
        return all.subList(offset, Math.min(offset + limit, all.size()));
    }

    /**
     * Bulk-delete every memory belonging to the given agent. Called when an
     * agent itself is deleted, so per-agent data doesn't outlive its owner.
     * Returns the number of entries removed (best-effort — backends may
     * return 0 if they can't cheaply count).
     */
    int deleteAll(String agentId);
}
