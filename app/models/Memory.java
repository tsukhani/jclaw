package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;
import services.EventLogger;
import services.search.LuceneIndexer;
import services.search.MessageSearch;
import services.search.MessageSearchRepository;

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

    /**
     * Owning agent (JCLAW-537). A real foreign key with {@code ON DELETE CASCADE}
     * so deleting an agent removes its memories at the DB level — referential
     * integrity is DB-enforced, not only app-managed via {@code AgentService.delete}.
     * Replaces the former opaque {@code String agentId} that existed so the
     * {@code MemoryStore} abstraction could swap in Neo4j (now dropped).
     *
     * <p>{@code LAZY}: the recall, dedup, and index paths read only
     * {@code agent.id}, which a lazy proxy supplies without loading the Agent row,
     * so per-memory fetches don't drag the Agent along.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

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

    /**
     * JCLAW-526: when this memory last surfaced in recall (injected into a
     * system prompt). Recency anchor for retrieval decay — a recently-accessed
     * memory decays more slowly. Written by {@link #touchAccessed} via bulk
     * JPQL, deliberately bypassing entity callbacks: a recall touch must not
     * bump {@code updatedAt} (which means "content changed") nor re-index the
     * row in Lucene on every chat turn. Nullable (never accessed yet).
     */
    @Column(name = "last_accessed_at")
    public Instant lastAccessedAt;

    /**
     * JCLAW-525: when non-null, this memory has been superseded by a newer
     * write on the same subject and is excluded from every recall path (search,
     * core auto-load, dedup scans) — but never hard-deleted: the row stays as
     * an auditable trail (Zep-style temporal invalidation). Nullable adds are
     * safe on a populated table without a DDL default.
     */
    @Column(name = "superseded_at")
    public Instant supersededAt;

    /**
     * The id of the memory that superseded this one. A plain id, not a foreign
     * key — this schema has no cascades beyond the agent FK (JCLAW-540 policy),
     * and a dangling pointer after the newer memory is itself deleted is
     * harmless provenance.
     */
    @Column(name = "superseded_by_id")
    public Long supersededById;

    /**
     * Mark this memory superseded by {@code newerId} (JCLAW-525). Saving fires
     * {@code @PostUpdate}, which removes the row's Lucene doc — FTS and KNN
     * vector leave the index together — while the DB-side
     * {@code supersededAt IS NULL} filters on the recall queries act as the
     * backstop for anything already hydrating.
     */
    public void supersede(Long newerId) {
        supersededAt = Instant.now();
        supersededById = newerId;
        save();
    }

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
    // the sibling entities). The agent id rides as the exact-match filter field
    // so searchByText can scope results to one agent. The indexer never throws —
    // a transient FS issue must not abort the parent JPA transaction.
    // JCLAW-525: a superseded row leaves the index instead — recall must not
    // spend FTS/KNN top-k slots on invalidated facts, and removing the whole
    // doc drops its vector too (an update would otherwise re-upsert it).
    @PostPersist
    @PostUpdate
    void onIndexUpsert() {
        if (id == null) return;
        if (supersededAt != null) {
            LuceneIndexer.remove(LuceneIndexer.Scope.MEMORY, id);
            return;
        }
        if (agent != null) {
            LuceneIndexer.upsert(
                    LuceneIndexer.Scope.MEMORY, id, text, String.valueOf(agent.id));
        }
    }

    @PostRemove
    void onIndexRemove() {
        if (id != null) {
            LuceneIndexer.remove(
                    LuceneIndexer.Scope.MEMORY, id);
        }
    }

    /**
     * The decay anchor (JCLAW-526): the most recent of "content changed" and
     * "surfaced in recall". Both a re-store and a recall access reset a
     * memory's age for retrieval decay.
     */
    public Instant recencyAnchor() {
        if (lastAccessedAt == null) return updatedAt;
        if (updatedAt == null) return lastAccessedAt;
        return lastAccessedAt.isAfter(updatedAt) ? lastAccessedAt : updatedAt;
    }

    /**
     * JCLAW-526: stamp {@code lastAccessedAt} on the memories a recall just
     * injected. Bulk JPQL so no {@code @PreUpdate}/{@code @PostUpdate}
     * lifecycle fires — {@code updatedAt} keeps meaning "content changed" and
     * the Lucene doc is not re-upserted on every chat turn.
     */
    public static void touchAccessed(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        play.db.jpa.JPA.em()
                .createQuery("UPDATE Memory m SET m.lastAccessedAt = :now WHERE m.id IN (:ids)")
                .setParameter("now", Instant.now())
                .setParameter("ids", ids)
                .executeUpdate();
    }

    /** Parse an agent-id string (the partition key callers pass) to its PK, or null when non-numeric. */
    private static Long parsePk(String agentId) {
        if (agentId == null) return null;
        try {
            return Long.valueOf(agentId.strip());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Active (non-superseded) memories for one agent, newest first. Superseded
     * rows are excluded (JCLAW-525): every caller — recall, the auto-capture
     * dedup scan, the store's list API — wants the agent's <em>current</em>
     * memory, and a superseded old fact must not NOOP-dedup a re-emerging new
     * one. The admin view reads the table through its own query and still sees
     * everything.
     */
    public static List<Memory> findByAgent(String agentId) {
        return findByAgent(agentId, 200);
    }

    public static List<Memory> findByAgent(String agentId, int limit) {
        Long pk = parsePk(agentId);
        if (pk == null) return List.of();
        return Memory.find("agent.id = ?1 AND supersededAt IS NULL ORDER BY updatedAt DESC", pk)
                .fetch(limit);
    }

    /**
     * High-importance {@code core}-category memories for session-start auto-load
     * (JCLAW-40), ranked by importance then recency and bounded by {@code limit}
     * (the caller additionally enforces a token budget). The {@code core}
     * category is matched as a literal to keep this {@code models} class free of
     * a dependency on the {@code memory} package's {@code MemoryCategory}.
     */
    public static List<Memory> findCore(String agentId, double minImportance, int limit) {
        Long pk = parsePk(agentId);
        if (pk == null) return List.of();
        return Memory.find(
                "agent.id = ?1 AND category = ?2 AND importance >= ?3 AND supersededAt IS NULL"
                        + " ORDER BY importance DESC, updatedAt DESC",
                pk, "core", minImportance).fetch(limit);
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
        return searchByTextScored(agentId, query, limit).stream().map(ScoredMemory::memory).toList();
    }

    /** A recalled memory paired with its normalized relevance score (JCLAW-532). */
    public record ScoredMemory(Memory memory, double relevance) {}

    /**
     * JCLAW-532: as {@link #searchByText}, but pairs each hit with a relevance
     * score normalized to {@code [0,1]} (top hit = 1.0) so the recall path can
     * rank by real relevance rather than list position. The substring fallback
     * (search backend not initialized) has no scores, so each hit is scored 1.0 —
     * recall then degrades to importance ordering, acceptable for that edge.
     */
    public static List<ScoredMemory> searchByTextScored(String agentId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        Long pk = parsePk(agentId);
        if (pk == null) return List.of();
        if ("none".equals(MessageSearch.activeDialect())) {
            // Backend not initialized — substring fallback, agent-bounded.
            return likeFallback(pk, query, limit);
        }
        List<MessageSearchRepository.ScoredId> scored;
        try {
            scored = MessageSearch.searchMemoryIds(agentId, query, limit);
        } catch (IOException e) {
            // Backend initialized but unavailable (index closed, IO error) —
            // degrade to the same LIKE fallback instead of silently recalling
            // nothing: an empty recall looks identical to "no memories exist",
            // which is the worse failure mode.
            EventLogger.warn("search", null, null,
                    "Memory search failed for agent %s, using LIKE fallback: %s"
                            .formatted(agentId, e.getMessage()));
            return likeFallback(pk, query, limit);
        }
        if (scored.isEmpty()) return List.of();
        var ids = scored.stream().map(MessageSearchRepository.ScoredId::id).toList();
        // supersededAt IS NULL: backstop for an index doc whose row was
        // superseded but whose removal hasn't landed yet (JCLAW-525).
        List<Memory> rows = Memory.find(
                "agent.id = ?1 AND id IN (?2) AND supersededAt IS NULL", pk, ids).fetch();
        var byId = new HashMap<Long, Memory>();
        for (var m : rows) byId.put(m.id, m);
        var ordered = new ArrayList<ScoredMemory>(scored.size());
        for (var s : scored) {
            var m = byId.get(s.id());
            if (m != null) ordered.add(new ScoredMemory(m, s.score()));
        }
        return ordered;
    }

    /**
     * Agent-bounded case-insensitive substring search — the degraded recall
     * path when the search backend is uninitialized or unavailable. Substring
     * matching carries no relevance signal, so every hit scores 1.0 and recall
     * effectively degrades to importance ordering.
     */
    private static List<ScoredMemory> likeFallback(Long pk, String query, int limit) {
        List<Memory> rows = Memory.find(
                "agent.id = ?1 AND LOWER(text) LIKE ?2 AND supersededAt IS NULL",
                pk, "%" + query.toLowerCase() + "%").fetch(limit);
        return rows.stream().map(m -> new ScoredMemory(m, 1.0)).toList();
    }
}
