package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import play.db.jpa.Model;
import services.EventLogger;
import services.search.LuceneIndexer;
import services.search.MessageSearch;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Entity
@Table(name = "memory", indexes = {
        @Index(name = "idx_memory_agent", columnList = "agent_id"),
        // JCLAW-40: core-memory auto-load ranks by importance within an agent.
        @Index(name = "idx_memory_agent_importance", columnList = "agent_id,importance")
})
public class Memory extends Model {

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String text;

    @Column(length = 50)
    public String category;

    /**
     * Importance score in [0.0, 1.0] (JCLAW-40). Drives core-memory auto-load
     * ranking and the admin memory view, and is set by the auto-capture
     * extractor (JCLAW-39). NOT NULL with a DDL default so the column ALTER
     * stays safe on a populated table (same reason as the other
     * {@code @ColumnDefault} columns in this schema). The {@code 0.5} literal
     * mirrors {@code MemoryCategory.BASELINE_IMPORTANCE}.
     */
    @Column(nullable = false)
    @ColumnDefault("0.5")
    public double importance = 0.5;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // JCLAW-415: index per-agent memories in the Lucene MEMORY scope (mirrors
    // the sibling entities). agentId rides as the exact-match filter field so
    // searchByText can scope results to one agent. The indexer never throws —
    // a transient FS issue must not abort the parent JPA transaction.
    @PostPersist
    @PostUpdate
    void onIndexUpsert() {
        if (id != null) {
            LuceneIndexer.upsert(
                    LuceneIndexer.Scope.MEMORY, id, text, agentId);
        }
    }

    @PostRemove
    void onIndexRemove() {
        if (id != null) {
            LuceneIndexer.remove(
                    LuceneIndexer.Scope.MEMORY, id);
        }
    }

    public static List<Memory> findByAgent(String agentId) {
        return findByAgent(agentId, 200);
    }

    public static List<Memory> findByAgent(String agentId, int limit) {
        return Memory.find("agentId = ?1 ORDER BY updatedAt DESC", agentId).fetch(limit);
    }

    /**
     * High-importance {@code core}-category memories for session-start auto-load
     * (JCLAW-40), ranked by importance then recency and bounded by {@code limit}
     * (the caller additionally enforces a token budget). The {@code core}
     * category is matched as a literal to keep this {@code models} class free of
     * a dependency on the {@code memory} package's {@code MemoryCategory}.
     */
    public static List<Memory> findCore(String agentId, double minImportance, int limit) {
        return Memory.find(
                "agentId = ?1 AND category = ?2 AND importance >= ?3 ORDER BY importance DESC, updatedAt DESC",
                agentId, "core", minImportance).fetch(limit);
    }

    /**
     * JCLAW-415: full-text memory search for one agent. When the search backend
     * is initialized (production Personal Edition), query the Lucene MEMORY
     * scope — agent-filtered, token-based (StandardAnalyzer), matching the
     * Postgres {@code to_tsvector} path rather than the old substring LIKE — and
     * hydrate the rows from the DB in relevance order (re-bounding to the agent
     * and dropping any stale index ids whose rows no longer exist).
     *
     * <p>Falls back to an agent-bounded substring {@code LIKE} scan when the
     * backend isn't initialized (test mode skips {@code FullTextSearchInitJob};
     * a not-yet-opened/degraded index must not make agent memory recall silently
     * vanish — unlike an operator search box). The per-agent bound keeps the
     * fallback cheap (recall is capped at {@code limit}). The Postgres dialect
     * never reaches here — {@code JpaMemoryStore} routes it through
     * {@code to_tsvector} directly.
     */
    public static List<Memory> searchByText(String agentId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        if ("none".equals(MessageSearch.activeDialect())) {
            // Backend not initialized — substring fallback, agent-bounded.
            return Memory.find("agentId = ?1 AND LOWER(text) LIKE ?2",
                    agentId, "%" + query.toLowerCase() + "%").fetch(limit);
        }
        List<Long> ids;
        try {
            ids = MessageSearch.searchMemoryIds(agentId, query, limit);
        } catch (IOException e) {
            EventLogger.warn("search", null, null,
                    "Memory search failed for agent %s: %s".formatted(agentId, e.getMessage()));
            return List.of();
        }
        if (ids.isEmpty()) return List.of();
        List<Memory> rows = Memory.find("agentId = ?1 AND id IN (?2)", agentId, ids).fetch();
        var byId = new HashMap<Long, Memory>();
        for (var m : rows) byId.put(m.id, m);
        var ordered = new ArrayList<Memory>(ids.size());
        for (var rid : ids) {
            var m = byId.get(rid);
            if (m != null) ordered.add(m);
        }
        return ordered;
    }
}
