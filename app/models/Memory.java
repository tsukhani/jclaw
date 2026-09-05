package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.JPA;
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
     * JCLAW-529: questions this memory answers, indexed and embedded alongside
     * {@link #text} — never rendered into a prompt, which still uses {@code text}.
     *
     * <p>Retrieval matches a question against a statement, and on this corpus the two
     * are further apart than the statement is from unrelated memories: "the user's son
     * Mateo has the nickname Ziggy" scored 0.588 against "what do I call my
     * children?" while "the user's name is Sam" scored 0.656. Indexing the questions
     * turns that into question-to-question matching, which the same embedding model
     * scores at 0.830.
     *
     * <p>Combined with the statement rather than replacing it, from a measured
     * trade-off: keys alone win on relation-phrased questions but <em>lose to an
     * unrelated memory</em> on a direct one ("what's Mateo's nickname?" 0.715 against
     * the distractor's 0.763). The concatenation beats the distractor on every query
     * tried, which is the property that decides ranking.
     *
     * <p>Nullable with no DDL default so the column ALTER is safe on a populated table;
     * a row without a key embeds exactly as before.
     */
    @Column(name = "retrieval_key", columnDefinition = "TEXT")
    public String retrievalKey;

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

    // Deliberately NOT on TimestampedModel: these callbacks also clamp the importance
    // score, so inheriting them would mean overriding both and calling super — relying on
    // JPA callback-override semantics that nothing here pins with a test. The duplicated
    // timestamp lines are the cheaper price.
    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
        clampImportance();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        clampImportance();
    }

    /**
     * JCLAW-733: enforce the [0.0, 1.0] {@link #importance} bound at the persistence
     * boundary. {@code importance} is a raw public field (Play active-record), so a
     * direct write — {@code memory.importance = 42.0} from the auto-capture
     * extractor, a store implementation, or any other writer — would otherwise
     * persist out of range and skew core-memory ranking. The controller already
     * 400s an out-of-range API edit; this is the defense-in-depth backstop for
     * every other writer, applied on both insert and update.
     *
     * <p>JCLAW-970: NaN is reset to the {@code 0.5} field default rather than clamped. It
     * arrives from lenient JSON parsing of extractor output, and leaving it would pin the row
     * to the top of every recall while {@code findCore}'s {@code importance >= x} excludes it.
     */
    private void clampImportance() {
        // Must precede the range checks — NaN compares false against both bounds.
        if (Double.isNaN(importance)) importance = 0.5;
        else if (importance < 0.0) importance = 0.0;
        else if (importance > 1.0) importance = 1.0;
    }

    /**
     * The indexed fields as last written, so {@link #onIndexUpsert} can tell a real
     * content change from a metadata edit (JCLAW-921). Transient by necessity — it
     * describes the Lucene document, not a column.
     */
    @Transient
    private String indexedSnapshot;

    /**
     * The document's identity for change detection: exactly the fields
     * {@code LuceneIndexer.upsert} puts in a MEMORY document besides the id and the
     * vector. Importance and category are deliberately absent because the document does
     * not carry them.
     */
    private String indexKey() {
        return (agent == null ? "" : agent.id) + "\0" + (text == null ? "" : text);
    }

    @PostLoad
    void captureIndexedSnapshot() {
        indexedSnapshot = indexKey();
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
        if (agent == null) return;
        // JCLAW-921: a metadata-only edit must not touch the document. updateDocument
        // replaces it wholesale and this callback cannot supply a vector — embedding is
        // an LLM call and must never run inside a JPA lifecycle callback — so rewriting
        // an unchanged document silently strips the vector written by
        // JpaMemoryStore.generateAndIndexEmbedding. Adjusting importance from the
        // Memories page did exactly that, dropping the row out of KNN recall while FTS
        // kept matching it, which is what made the loss invisible.
        var key = indexKey();
        if (key.equals(indexedSnapshot)) return;
        LuceneIndexer.upsert(LuceneIndexer.Scope.MEMORY, id, text, String.valueOf(agent.id));
        indexedSnapshot = key;
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
        JPA.em()
                .createQuery("UPDATE Memory m SET m.lastAccessedAt = :now WHERE m.id IN (:ids)")
                .setParameter("now", Instant.now())
                .setParameter("ids", ids)
                .executeUpdate();
    }

    /**
     * Delete this memory, first clearing any supersession pointer aimed at it (JCLAW-529).
     *
     * <p>{@code supersededById} is a bare column with no foreign key — only {@code agent_id}
     * carries one — so nothing at the database level stops a delete from leaving retired
     * rows pointing at an id that no longer exists. A live store had 265 such pointers, each
     * one a lineage chain that cannot be walked to its surviving descendant.
     *
     * <p>Recall is unaffected either way: it filters on {@code supersededAt}, never on this
     * pointer. What breaks is the audit question "what replaced this?", and the answer once
     * the successor is gone is "nothing that still exists" — which null records faithfully
     * and a dangling id does not.
     *
     * <p>Deliberately not a {@code @PreRemove} hook: modifying other entities from a
     * lifecycle callback is undefined under JPA, and this runs a bulk update.
     */
    public void deleteWithLineage() {
        if (id != null) {
            // Entity updates, not a bulk JPQL one: a bulk update bypasses the persistence
            // context, so an entity already loaded in this request would keep reporting the
            // pointer this method just cleared. The row count here is the number of rows
            // superseded by this one — almost always zero or one.
            List<Memory> referencing = find("supersededById = ?1", id).fetch();
            for (var m : referencing) {
                m.supersededById = null;
                m.save();
            }
        }
        delete();
    }

    /**
     * Null every supersession pointer whose target row is gone, returning how many were
     * repaired. Idempotent — a second run updates nothing.
     *
     * <p>Bulk JPQL, unlike {@link #deleteWithLineage()}: this runs from a boot job with
     * nothing else loaded, so there is no persistence context to leave stale, and the row
     * count on a neglected store can reach the hundreds.
     */
    public static int clearDanglingSupersessionPointers() {
        int repaired = JPA.em().createQuery(
                        "UPDATE Memory m SET m.supersededById = NULL WHERE m.supersededById IS NOT NULL "
                                + "AND NOT EXISTS (SELECT 1 FROM Memory x WHERE x.id = m.supersededById)")
                .executeUpdate();
        // The bulk update wrote past the persistence context; drop it so any entity read
        // afterwards comes from the database rather than reporting the pointer just cleared.
        if (repaired > 0) JPA.em().clear();
        return repaired;
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
     * Live {@code core} memories for an agent, at any importance (JCLAW-981).
     *
     * <p>Deliberately unfiltered by importance, unlike {@link #findCore}: that method
     * answers "what loads this turn", this one answers "how many core slots are taken". A
     * core memory below the load threshold still holds the category the operator granted,
     * so counting only the visible ones would let the tier fill up unseen.
     */
    public static long countLiveCore(String agentId) {
        Long pk = parsePk(agentId);
        if (pk == null) return 0;
        return Memory.count("agent.id = ?1 AND category = ?2 AND supersededAt IS NULL", pk, "core");
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
     * scope — agent-filtered, token-based (the shared analyzer on
     * {@code LuceneIndexer}, which stems since JCLAW-1052), matching the
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
        if ("none".equals(MessageSearch.activeDialect()) || !LuceneIndexer.isOpen()) {
            // Backend not initialized, or the FTS index isn't open — substring
            // fallback, agent-bounded. Production always has the index open
            // (FullTextSearchInitJob), so the !isOpen() arm only diverges under a
            // test's closed-mode window (JCLAW-737): recall stays on the DB scan
            // there instead of an empty Lucene result, regardless of the
            // process-global activeDialect a concurrent search lane may have set.
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
     *
     * <p>JCLAW-615: public for JpaMemoryStore's PG degradation chain — its
     * terminal fallback must be THIS deterministic SQL scan, never a hop
     * back into the Lucene-preferring search abstraction (whose JVM-global
     * index any concurrent caller can open with different contents).
     */
    public static List<ScoredMemory> likeFallback(Long pk, String query, int limit) {
        List<Memory> rows = Memory.find(
                "agent.id = ?1 AND LOWER(text) LIKE ?2 AND supersededAt IS NULL",
                pk, "%" + query.toLowerCase() + "%").fetch(limit);
        return rows.stream().map(m -> new ScoredMemory(m, 1.0)).toList();
    }
}
