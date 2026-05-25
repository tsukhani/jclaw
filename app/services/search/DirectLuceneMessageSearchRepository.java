package services.search;

import models.TaskRunMessage;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import services.EventLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Lucene 10 backed implementation of {@link MessageSearchRepository}.
 * Owns the per-scope SearcherManager dispatch and backfill logic that
 * sits behind {@link MessageSearch}.
 *
 * <h3>Why direct over H2.FullTextLucene</h3>
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
 * <h3>Multi-scope (JCLAW-304)</h3>
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

    /** {@inheritDoc} */
    @Override
    public void init() throws IOException {
        LuceneIndexer.open();
        // Pre-v1 wipe path: an empty index after open() means either
        // first boot OR an operator wiped data/jclaw-lucene/ explicitly.
        // Either way, the right move is to backfill from the JPA store
        // — slow on a huge transcript history, but JClaw at pre-v1 has
        // hundreds-to-low-thousands of rows.
        if (LuceneIndexer.docCount(LuceneIndexer.Scope.TASK_RUN_MESSAGE) == 0) {
            backfillTaskRunMessages();
        }
        if (LuceneIndexer.docCount(LuceneIndexer.Scope.CONVERSATION_MESSAGE) == 0) {
            backfillConversationMessages();
        }
        if (LuceneIndexer.docCount(LuceneIndexer.Scope.TASK) == 0) {
            backfillTasks();
        }
        if (LuceneIndexer.docCount(LuceneIndexer.Scope.SUBAGENT_RUN) == 0) {
            backfillSubagentRuns();
        }
    }

    private static void backfillTaskRunMessages() {
        backfill(LuceneIndexer.Scope.TASK_RUN_MESSAGE,
                "SELECT m FROM TaskRunMessage m",
                row -> {
                    var m = (TaskRunMessage) row;
                    if (m.id == null) return;
                    LuceneIndexer.upsert(LuceneIndexer.Scope.TASK_RUN_MESSAGE, m.id, m.content);
                });
    }

    private static void backfillConversationMessages() {
        backfill(LuceneIndexer.Scope.CONVERSATION_MESSAGE,
                "SELECT m FROM Message m",
                row -> {
                    var m = (models.Message) row;
                    if (m.id == null) return;
                    LuceneIndexer.upsert(LuceneIndexer.Scope.CONVERSATION_MESSAGE, m.id, m.content);
                });
    }

    private static void backfillTasks() {
        backfill(LuceneIndexer.Scope.TASK,
                "SELECT t FROM Task t",
                row -> {
                    var t = (models.Task) row;
                    if (t.id == null) return;
                    LuceneIndexer.upsert(LuceneIndexer.Scope.TASK, t.id, taskContent(t));
                });
    }

    private static void backfillSubagentRuns() {
        backfill(LuceneIndexer.Scope.SUBAGENT_RUN,
                "SELECT r FROM SubagentRun r",
                row -> {
                    var r = (models.SubagentRun) row;
                    if (r.id == null) return;
                    LuceneIndexer.upsert(LuceneIndexer.Scope.SUBAGENT_RUN, r.id, subagentRunContent(r));
                });
    }

    /**
     * Bulk indexing helper. Runs the JPQL inside a single Tx, iterates
     * the result list calling the per-row indexer, and logs the count
     * when it's positive. Empty JPA tables produce zero log lines so
     * fresh installs stay quiet.
     */
    private static void backfill(LuceneIndexer.Scope scope, String jpql,
                                  java.util.function.Consumer<Object> indexRow) {
        services.Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var rows = play.db.jpa.JPA.em().createQuery(jpql).getResultList();
            int n = 0;
            for (var row : rows) {
                indexRow.accept(row);
                n++;
            }
            if (n > 0) {
                EventLogger.info("search", null, null,
                        "Lucene index backfilled: scope=%s rows=%d"
                                .formatted(scope.name(), n));
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
    static String taskContent(models.Task t) {
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
    static String subagentRunContent(models.SubagentRun r) {
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

    private static List<Long> collectMatchingIds(SearcherManager sm, String query, int limit) throws IOException {
        // QueryParser.parse throws on malformed input. Treat parse
        // failure as an empty result rather than propagating —
        // operators typing free-form text shouldn't 500 the UI.
        var parser = new QueryParser(LuceneIndexer.CONTENT_FIELD, new StandardAnalyzer());
        org.apache.lucene.search.Query q;
        try {
            q = parser.parse(query);
        } catch (ParseException _) {
            return List.of();
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
        var rows = services.Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var raw = play.db.jpa.JPA.em()
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
