package services.search;

import models.TaskRunMessage;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import services.EventLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import models.Memory;
import models.Message;
import models.SubagentRun;
import models.Task;
import play.db.jpa.JPA;
import services.Tx;

/**
 * Lucene 10 backed implementation of {@link MessageSearchRepository}.
 * Owns the per-scope SearcherManager dispatch and backfill logic that
 * sits behind {@link MessageSearch}.
 *
 * <h2>Why direct over H2.FullTextLucene</h2>
 * H2 2.3.232's {@code FullTextLucene} was compiled against Lucene 9.x
 * and reads {@code TotalHits.value} as a public field. Lucene 10 made
 * that field private (now accessed via a getter), so H2.FullTextLucene
 * IllegalAccessError's at the first search query against a Lucene-10
 * classpath. Carrying H2.FullTextLucene means we can't move past
 * Lucene 9.12.3 until H2 ships a fix — uncertain timeline. Owning the
 * lifecycle ourselves removes that coupling permanently and gives the
 * Postgres path a clean unified implementation (the same direct repo
 * works against either dialect; only the disk location and crawl
 * source vary).
 *
 * <h2>Multi-scope (JCLAW-304)</h2>
 * Each {@link LuceneIndexer.Scope} maintains its own on-disk index
 * under {@code data/jclaw-lucene/<scope>/}. The id-only search path
 * ({@link #searchIds}) takes a scope explicitly and returns a list
 * of matching primary-key ids ordered by Lucene's relevance scoring;
 * callers hydrate entity rows from JPA. The legacy entity-typed
 * {@link #search(String, int)} path is preserved for the existing
 * {@code /api/task-runs/search} endpoint, which still wants
 * {@link TaskRunMessage} rows back directly.
 */
public final class DirectLuceneMessageSearchRepository implements MessageSearchRepository {

    /**
     * Per-scope backfill descriptor: the JPQL that loads the rows, the row's
     * id accessor (null id → skip), and the indexed-content extractor. One
     * entry per {@link LuceneIndexer.Scope}; {@link #init()} drives them all
     * through {@link #backfill(Backfiller)}.
     */
    private record Backfiller(LuceneIndexer.Scope scope, String jpql,
                              Function<Object, Long> id,
                              Function<Object, String> content,
                              Function<Object, String> agent) {
        /** Most scopes carry no per-owner filter field. */
        Backfiller(LuceneIndexer.Scope scope, String jpql,
                   Function<Object, Long> id,
                   Function<Object, String> content) {
            this(scope, jpql, id, content, null);
        }
    }

    private static final List<Backfiller> BACKFILLERS = List.of(
            new Backfiller(LuceneIndexer.Scope.TASK_RUN_MESSAGE, "SELECT m FROM TaskRunMessage m",
                    row -> ((TaskRunMessage) row).id, row -> ((TaskRunMessage) row).content),
            new Backfiller(LuceneIndexer.Scope.CONVERSATION_MESSAGE, "SELECT m FROM Message m",
                    row -> ((Message) row).id, row -> ((Message) row).content),
            new Backfiller(LuceneIndexer.Scope.TASK, "SELECT t FROM Task t",
                    row -> ((Task) row).id, row -> taskContent((Task) row)),
            new Backfiller(LuceneIndexer.Scope.SUBAGENT_RUN, "SELECT r FROM SubagentRun r",
                    row -> ((SubagentRun) row).id, row -> subagentRunContent((SubagentRun) row)),
            new Backfiller(LuceneIndexer.Scope.MEMORY, "SELECT m FROM Memory m",
                    row -> ((Memory) row).id, row -> ((Memory) row).text,
                    row -> ((Memory) row).agentId));

    /** {@inheritDoc} */
    @Override
    public void init() throws IOException {
        LuceneIndexer.open();
        // Pre-v1 wipe path: an empty index after open() means either
        // first boot OR an operator wiped data/jclaw-lucene/ explicitly.
        // Either way, the right move is to backfill from the JPA store
        // — slow on a huge transcript history, but JClaw at pre-v1 has
        // hundreds-to-low-thousands of rows.
        for (var b : BACKFILLERS) {
            if (LuceneIndexer.docCount(b.scope()) == 0) {
                backfill(b);
            }
        }
    }

    /**
     * Bulk indexing helper for one {@link Backfiller} descriptor. Runs the
     * descriptor's JPQL inside a single Tx, upserts each row whose id is
     * non-null, and logs the count when it's positive. Empty JPA tables
     * produce zero log lines so fresh installs stay quiet.
     *
     * <p>The per-row {@link LuceneIndexer#upsert} no longer fsyncs, so the
     * whole loop is committed ONCE per scope at the end via
     * {@link LuceneIndexer#commit(LuceneIndexer.Scope)} — backfilling
     * thousands of rows pays one fsync instead of one per row.
     */
    private static void backfill(Backfiller b) {
        Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var rows = JPA.em().createQuery(b.jpql()).getResultList();
            int n = 0;
            for (var row : rows) {
                n++;
                var id = b.id().apply(row);
                if (id == null) continue;
                LuceneIndexer.upsert(b.scope(), id, b.content().apply(row),
                        b.agent() != null ? b.agent().apply(row) : null);
            }
            if (n > 0) {
                LuceneIndexer.commit(b.scope());
                EventLogger.info("search", null, null,
                        "Lucene index backfilled: scope=%s rows=%d"
                                .formatted(b.scope().name(), n));
            }
            return null;
        });
    }

    /**
     * Virtual-document content for {@link models.Task} — concatenates
     * the two operator-facing free-text fields into one indexed string.
     * Null fields contribute the empty string; the join uses a single
     * space so adjacent words from different fields don't accidentally
     * fuse into one stemmed token.
     */
    static String taskContent(Task t) {
        var name = t.name != null ? t.name : "";
        var desc = t.description != null ? t.description : "";
        return name + " " + desc;
    }

    /**
     * Virtual-document content for {@link models.SubagentRun} — same
     * pattern as {@link #taskContent}: {@code label} + space +
     * {@code outcome}. {@code outcome} is null while the run is RUNNING,
     * which is fine — it gets indexed once when the announce-VT writes
     * the terminal outcome and the entity's @PostUpdate hook fires.
     */
    static String subagentRunContent(SubagentRun r) {
        var label = r.label != null ? r.label : "";
        var outcome = r.outcome != null ? r.outcome : "";
        return label + " " + outcome;
    }

    /** {@inheritDoc} */
    @Override
    public List<TaskRunMessage> search(String query, int limit) throws IOException {
        var ids = searchIds(LuceneIndexer.Scope.TASK_RUN_MESSAGE, query, limit);
        if (ids.isEmpty()) return List.of();
        return hydrateTaskRunMessagesInOrder(ids);
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> searchIds(LuceneIndexer.Scope scope, String query, int limit) throws IOException {
        if (query == null || query.isBlank()) return List.of();
        var sm = LuceneIndexer.searcherManager(scope);
        if (sm == null) return List.of();
        return collectMatchingIds(sm, query, limit);
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> searchMemoryIds(String agentId, String query, int limit) throws IOException {
        if (query == null || query.isBlank() || agentId == null) return List.of();
        var sm = LuceneIndexer.searcherManager(LuceneIndexer.Scope.MEMORY);
        if (sm == null) return List.of();
        return collectMatchingIds(sm, query, agentId, limit);
    }

    private static List<Long> collectMatchingIds(SearcherManager sm, String query, int limit) throws IOException {
        return collectMatchingIds(sm, query, null, limit);
    }

    /**
     * As the 3-arg overload, but when {@code agentKey} is non-null the parsed
     * content query is AND-ed with an exact-match term on
     * {@link LuceneIndexer#AGENT_FIELD}, so only that owner's docs match (the
     * per-agent {@link LuceneIndexer.Scope#MEMORY} scope). A null
     * {@code agentKey} searches unfiltered.
     */
    private static List<Long> collectMatchingIds(SearcherManager sm, String query,
                                                 String agentKey, int limit) throws IOException {
        // QueryParser.parse throws on malformed input. Treat parse
        // failure as an empty result rather than propagating —
        // operators typing free-form text shouldn't 500 the UI.
        var parser = new QueryParser(LuceneIndexer.CONTENT_FIELD, new StandardAnalyzer());
        Query q;
        try {
            q = parser.parse(query);
        } catch (ParseException _) {
            return List.of();
        }
        if (agentKey != null) {
            q = new BooleanQuery.Builder()
                    .add(q, BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(LuceneIndexer.AGENT_FIELD, agentKey)),
                            BooleanClause.Occur.MUST)
                    .build();
        }

        // Refresh the reader so writes from the same Tx (post-commit) are
        // visible. maybeRefresh is cheap when nothing changed.
        sm.maybeRefresh();

        var ids = new ArrayList<Long>(limit);
        try (var leased = new LeasedSearcher(sm)) {
            var searcher = leased.get();
            var top = searcher.search(q, limit);
            var storedFields = searcher.storedFields();
            for (var sd : top.scoreDocs) {
                var doc = storedFields.document(sd.doc);
                var raw = doc.get(LuceneIndexer.ID_FIELD);
                if (raw == null) continue;
                try {
                    ids.add(Long.parseLong(raw));
                } catch (NumberFormatException _) {
                    // Skip rows with malformed id values; partial results
                    // beat failing the whole query.
                }
            }
        }
        return ids;
    }

    private static List<TaskRunMessage> hydrateTaskRunMessagesInOrder(List<Long> ids) {
        // Bulk-fetch JPA rows, then re-order to match Lucene's relevance
        // ranking. Same pattern as the prior H2-backed impl.
        var rows = Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var raw = JPA.em()
                    .createQuery("SELECT m FROM TaskRunMessage m WHERE m.id IN :ids")
                    .setParameter("ids", ids)
                    .getResultList();
            var typed = new ArrayList<TaskRunMessage>(raw.size());
            for (var r : raw) typed.add((TaskRunMessage) r);
            return typed;
        });
        var byId = HashMap.<Long, TaskRunMessage>newHashMap(rows.size());
        for (var row : rows) byId.put(row.id, row);
        var ordered = new ArrayList<TaskRunMessage>(ids.size());
        for (var id : ids) {
            var row = byId.get(id);
            if (row != null) ordered.add(row);
        }
        return ordered;
    }

    /**
     * Pairs a {@link SearcherManager#acquire()} with its matching
     * {@link SearcherManager#release(IndexSearcher)} so callers can use
     * try-with-resources around the lease.
     */
    private static final class LeasedSearcher implements AutoCloseable {
        private final SearcherManager sm;
        private final IndexSearcher searcher;

        LeasedSearcher(SearcherManager sm) throws IOException {
            this.sm = sm;
            this.searcher = sm.acquire();
        }

        IndexSearcher get() {
            return searcher;
        }

        @Override
        public void close() throws IOException {
            sm.release(searcher);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String dialectName() {
        return "lucene";
    }
}
