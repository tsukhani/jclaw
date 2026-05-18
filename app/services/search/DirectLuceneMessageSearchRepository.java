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
 * Replaces {@code H2LuceneMessageSearchRepository} now that we own the
 * Lucene lifecycle directly (see {@link LuceneIndexer}) rather than
 * leaning on H2's bundled FullTextLucene.
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
 * <h3>Surface</h3>
 * <ul>
 *   <li>{@link #init()} — opens the {@link LuceneIndexer} and, if the
 *       on-disk index is empty, backfills every existing
 *       {@link TaskRunMessage} row. Subsequent boots skip the
 *       backfill (the index is durable across restarts).</li>
 *   <li>{@link #search(String, int)} — parses the query against the
 *       {@code content} field, returns matching rows ordered by
 *       relevance.</li>
 * </ul>
 */
public final class DirectLuceneMessageSearchRepository implements MessageSearchRepository {

    /** {@inheritDoc} */
    @Override
    public void init() throws Exception {
        LuceneIndexer.open();
        // Pre-v1 wipe path: an empty index after open() means either
        // first boot OR an operator wiped data/jclaw-lucene/ explicitly.
        // Either way, the right move is to backfill from the JPA store
        // — slow on a huge transcript history, but JClaw at pre-v1 has
        // hundreds-to-low-thousands of rows.
        if (LuceneIndexer.docCount() == 0) {
            backfillFromDb();
        }
    }

    private static void backfillFromDb() {
        services.Tx.run(() -> {
            @SuppressWarnings("unchecked")
            var raw = play.db.jpa.JPA.em()
                    .createQuery("SELECT m FROM TaskRunMessage m")
                    .getResultList();
            int n = 0;
            for (var r : raw) {
                LuceneIndexer.upsert((TaskRunMessage) r);
                n++;
            }
            if (n > 0) {
                EventLogger.info("search", null, null,
                        "Lucene index backfilled from JPA: %d row(s)".formatted(n));
            }
            return null;
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<TaskRunMessage> search(String query, int limit) throws Exception {
        if (query == null || query.isBlank()) return List.of();

        var sm = LuceneIndexer.searcherManager();
        if (sm == null) return List.of();

        var ids = collectMatchingIds(sm, query, limit);
        if (ids.isEmpty()) return List.of();
        return hydrateInOrder(ids);
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

    private static List<TaskRunMessage> hydrateInOrder(List<Long> ids) {
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
