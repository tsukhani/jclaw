package services.search;

import memory.MemoryReembedService;
import models.Memory;
import models.Message;
import models.SubagentRun;
import models.Task;
import models.TaskRunMessage;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import play.db.jpa.JPA;
import services.ConfigService;
import services.EventLogger;
import services.Tx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

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
    private record Backfiller(LuceneIndexer.Scope scope, String jpql, String countJpql,
                              Function<Object, Long> id,
                              Function<Object, String> content,
                              Function<Object, String> agent) {
        /** Most scopes carry no per-owner filter field. */
        Backfiller(LuceneIndexer.Scope scope, String jpql, String countJpql,
                   Function<Object, Long> id,
                   Function<Object, String> content) {
            this(scope, jpql, countJpql, id, content, null);
        }
    }

    private static final List<Backfiller> BACKFILLERS = List.of(
            new Backfiller(LuceneIndexer.Scope.TASK_RUN_MESSAGE, "SELECT m FROM TaskRunMessage m",
                    "SELECT COUNT(m) FROM TaskRunMessage m",
                    row -> ((TaskRunMessage) row).id, row -> ((TaskRunMessage) row).content),
            new Backfiller(LuceneIndexer.Scope.CONVERSATION_MESSAGE, "SELECT m FROM Message m",
                    "SELECT COUNT(m) FROM Message m",
                    row -> ((Message) row).id, row -> ((Message) row).content),
            new Backfiller(LuceneIndexer.Scope.TASK, "SELECT t FROM Task t",
                    "SELECT COUNT(t) FROM Task t",
                    row -> ((Task) row).id, row -> taskContent((Task) row)),
            new Backfiller(LuceneIndexer.Scope.SUBAGENT_RUN, "SELECT r FROM SubagentRun r",
                    "SELECT COUNT(r) FROM SubagentRun r",
                    row -> ((SubagentRun) row).id, row -> subagentRunContent((SubagentRun) row)),
            // JCLAW-966: superseded rows are deliberately absent from the index —
            // Memory.onIndexUpsert removes their document — so re-adding them here would
            // spend FTS/KNN top-k slots on facts a newer version has already replaced,
            // crowding the live version out of the k window entirely.
            new Backfiller(LuceneIndexer.Scope.MEMORY,
                    "SELECT m FROM Memory m WHERE m.supersededAt IS NULL",
                    "SELECT COUNT(m) FROM Memory m WHERE m.supersededAt IS NULL",
                    row -> ((Memory) row).id, row -> ((Memory) row).text,
                    row -> String.valueOf(((Memory) row).agent.id)));

    /** {@inheritDoc} */
    @Override
    public void init() throws IOException {
        LuceneIndexer.open();
        // An empty or short index (first boot, or an operator wiped data/jclaw-lucene/) is
        // backfilled from the JPA store — slow on a huge transcript history, but pre-v1 JClaw
        // has hundreds-to-low-thousands of rows.
        // JCLAW-1052: an analyzer change re-tokenizes every document, and the count
        // comparison below does not move. Without this the old index keeps answering
        // with the previous tokenization, so the new query terms match nothing and search
        // degrades silently instead of failing.
        boolean analyzerChanged = !LuceneIndexer.ANALYZER_GENERATION
                .equals(ConfigService.get(ANALYZER_GENERATION_KEY, null));
        // JCLAW-961: a deficit rebuilds rather than gating on an empty index — a hard kill
        // loses up to one commit interval of writes, and those rows would otherwise sit in
        // the database and on the UI while being permanently invisible to search. Re-running
        // over already-indexed rows is harmless: upsert keys on the id term.
        for (var b : BACKFILLERS) {
            long docs = LuceneIndexer.docCount(b.scope());
            long rows = rowCount(b);
            // JCLAW-1064: a surplus is rows deleted without their doc evicted, because a
            // cascade delete never fires @PostRemove. upsert only adds, so those docs
            // survive a backfill and go on answering queries — clear and rebuild instead.
            // MEMORY is exempt from the trigger, not just the clear: its rebuild restores
            // text but not KNN vectors, and an upsert-only pass can never settle a surplus,
            // so including it would drop the vector leg and re-prompt on every boot.
            boolean purgeSurplus = docs > rows && b.scope() != LuceneIndexer.Scope.MEMORY;
            if (!analyzerChanged && docs >= rows && !purgeSurplus) continue;
            if (purgeSurplus) LuceneIndexer.clear(b.scope());
            backfill(b);
            if (b.scope() == LuceneIndexer.Scope.MEMORY) {
                MemoryReembedService.invalidateBackfillMarker();
            }
        }
        if (analyzerChanged) {
            ConfigService.set(ANALYZER_GENERATION_KEY, LuceneIndexer.ANALYZER_GENERATION);
            EventLogger.info("search", null, null,
                    "Search index rebuilt for analyzer generation %s"
                            .formatted(LuceneIndexer.ANALYZER_GENERATION));
        }
    }

    /** Records which analyzer the on-disk index was written with. */
    private static final String ANALYZER_GENERATION_KEY = "search.lucene.analyzerGeneration";

    private static long rowCount(Backfiller b) {
        return Tx.run(() -> (Long) JPA.em().createQuery(b.countJpql()).getSingleResult());
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
    public List<ScoredId> searchMemoryIds(String agentId, String query, int limit) throws IOException {
        if (query == null || query.isBlank() || agentId == null) return List.of();
        var sm = LuceneIndexer.searcherManager(LuceneIndexer.Scope.MEMORY);
        if (sm == null) return List.of();
        return normalizeByTop(collectScored(sm, query, agentId, limit, false));
    }

    /**
     * JCLAW-555: KNN recall leg for the Lucene HNSW vector backend (H2 / any
     * non-Postgres dialect). Returns up to {@code k} memory ids nearest to
     * {@code queryVector} by cosine similarity, restricted to {@code agentId}'s
     * documents via the exact-match {@link LuceneIndexer#AGENT_FIELD} pre-filter
     * (memories never cross agents). Docs without a
     * {@link LuceneIndexer#VECTOR_FIELD} simply aren't in the KNN graph — rows
     * whose embedding generation failed degrade to FTS-only matching.
     *
     * <p>Static rather than part of {@link MessageSearchRepository}: the KNN leg
     * exists only on the Lucene backend (Postgres runs pgvector inside SQL), so
     * putting it on the dialect-shared interface would force a meaningless
     * implementation on {@link PostgresMessageSearchRepository}. Raw
     * best-first-ordered scores are returned; the caller fuses ranks via RRF
     * (rank-based — the raw score scale never matters).
     */
    public static List<ScoredId> searchMemoryIdsByVector(String agentId, float[] queryVector, int k)
            throws IOException {
        if (agentId == null || queryVector == null || k <= 0) return List.of();
        var sm = LuceneIndexer.searcherManager(LuceneIndexer.Scope.MEMORY);
        if (sm == null) return List.of();
        var q = new KnnFloatVectorQuery(LuceneIndexer.VECTOR_FIELD, queryVector, k,
                new TermQuery(new Term(LuceneIndexer.AGENT_FIELD, agentId)));
        sm.maybeRefresh();
        var out = new ArrayList<ScoredId>(k);
        try (var leased = new LeasedSearcher(sm)) {
            var searcher = leased.get();
            var top = searcher.search(q, k);
            var storedFields = searcher.storedFields();
            for (var sd : top.scoreDocs) {
                var raw = storedFields.document(sd.doc).get(LuceneIndexer.ID_FIELD);
                if (raw == null) continue;
                try {
                    out.add(new ScoredId(Long.parseLong(raw), sd.score));
                } catch (NumberFormatException _) {
                    // Skip malformed ids; partial results beat failing the query.
                }
            }
        }
        return out;
    }

    private static List<Long> collectMatchingIds(SearcherManager sm, String query, int limit) throws IOException {
        return collectScored(sm, query, null, limit, true).stream().map(ScoredId::id).toList();
    }

    /** The index-time analyzer itself, not a matching copy (JCLAW-1052): query tokens have to
     *  line up with indexed terms, and a divergence returns nothing rather than failing. */
    private static final Analyzer CONTENT_ANALYZER = LuceneIndexer.ANALYZER;

    /** Max index terms a single prefix token expands to (scored). Bounds the work
     *  on a large corpus while keeping BM25 scoring for the recall relevance floor. */
    private static final int MAX_PREFIX_EXPANSIONS = 50;

    /** Below this length a token matches exactly — a 1-char prefix would match
     *  almost everything and carry no signal. */
    private static final int MIN_PREFIX_LEN = 2;

    /**
     * Build the content-field query from free text: tokenize with the index-time
     * analyzer, then match each token as a bounded, scored {@link PrefixQuery} so a
     * partial term surfaces its longer forms — "pho" finds "phone".
     *
     * <p>Partial matching is now the only thing the prefix contributes. It was introduced
     * for possessives and plurals too, and the analyzer took both over in JCLAW-1052:
     * measured against the shipped analyzer, plain term matching alone already resolves
     * "marissa" to "Marissa's" (the possessive filter) and "phones" to "phone" (KStem).
     * Only "pho" still needs it — 0 hits as a term, 2 as a prefix — so the prefix stays,
     * on a narrower justification than it was added with.
     *
     * <p>Tokens shorter than {@link #MIN_PREFIX_LEN} match exactly. {@code requireAll} ANDs
     * the tokens (admin filter — all must appear), else ORs them (agent recall — any token,
     * then relevance-ranked + floored). Returns {@code null} when the query yields no
     * usable tokens (e.g. all stopwords).
     */
    private static Query buildContentQuery(String query, boolean requireAll) throws IOException {
        var terms = analyzeToTerms(query);
        if (terms.isEmpty()) return null;
        var occur = requireAll ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD;
        var b = new BooleanQuery.Builder();
        for (var t : terms) {
            Query tq = t.length() >= MIN_PREFIX_LEN
                    ? new PrefixQuery(new Term(LuceneIndexer.CONTENT_FIELD, t),
                            new MultiTermQuery.TopTermsScoringBooleanQueryRewrite(MAX_PREFIX_EXPANSIONS))
                    : new TermQuery(new Term(LuceneIndexer.CONTENT_FIELD, t));
            b.add(tq, occur);
        }
        return b.build();
    }

    /** Run {@link #CONTENT_ANALYZER} over {@code text} and collect the emitted
     *  tokens (lowercased, stopwords dropped). */
    private static List<String> analyzeToTerms(String text) throws IOException {
        var terms = new ArrayList<String>();
        try (var ts = CONTENT_ANALYZER.tokenStream(LuceneIndexer.CONTENT_FIELD, text)) {
            var attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) terms.add(attr.toString());
            ts.end();
        }
        return terms;
    }

    /**
     * As the 3-arg overload, but when {@code agentKey} is non-null the parsed
     * content query is AND-ed with an exact-match term on
     * {@link LuceneIndexer#AGENT_FIELD}, so only that owner's docs match (the
     * per-agent {@link LuceneIndexer.Scope#MEMORY} scope). A null
     * {@code agentKey} searches unfiltered.
     */
    private static List<ScoredId> collectScored(SearcherManager sm, String query,
                                                String agentKey, int limit, boolean requireAll) throws IOException {
        // Prefix-match each query token (see buildContentQuery) rather than the
        // free-form QueryParser: operators typing free text get partial-word matching,
        // and stray Lucene operator characters can't ParseException the query out from
        // under the UI. Possessives and plurals are the analyzer's job since JCLAW-1052.
        Query content = buildContentQuery(query, requireAll);
        if (content == null) return List.of(); // no usable tokens (e.g. all stopwords)
        Query q = content;
        if (agentKey != null) {
            q = new BooleanQuery.Builder()
                    .add(content, BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(LuceneIndexer.AGENT_FIELD, agentKey)),
                            BooleanClause.Occur.MUST)
                    .build();
        }

        // Refresh the reader so writes from the same Tx (post-commit) are
        // visible. maybeRefresh is cheap when nothing changed.
        sm.maybeRefresh();

        var out = new ArrayList<ScoredId>(limit);
        try (var leased = new LeasedSearcher(sm)) {
            var searcher = leased.get();
            var top = searcher.search(q, limit);
            var storedFields = searcher.storedFields();
            // JCLAW-532: on agent-scoped recall (agentKey != null) keep only hits
            // within a fraction of the best match's score, so a long user message
            // can't pull loosely-related memories into the prompt. The admin
            // q-search (agentKey == null) keeps every match.
            var sds = top.scoreDocs;
            var scores = new float[sds.length];
            for (int i = 0; i < sds.length; i++) scores[i] = sds[i].score;
            int keep = (agentKey != null) ? recallFloorCount(scores, recallMinScoreRatio()) : sds.length;
            for (int i = 0; i < keep; i++) {
                var doc = storedFields.document(sds[i].doc);
                var raw = doc.get(LuceneIndexer.ID_FIELD);
                if (raw == null) continue;
                try {
                    out.add(new ScoredId(Long.parseLong(raw), sds[i].score));
                } catch (NumberFormatException _) {
                    // Skip rows with malformed id values; partial results
                    // beat failing the whole query.
                }
            }
        }
        return out;
    }

    /**
     * JCLAW-532: normalize raw Lucene scores (already descending, best-first) to
     * {@code [0,1]} against the top hit, so the strongest match is {@code 1.0} and
     * the recall caller can blend a real relevance score with importance instead
     * of using rank position. Pure and public so it's unit-testable without a
     * live index. A non-positive top score (degenerate/constant-score query)
     * yields a uniform {@code 1.0} rather than dividing by zero.
     */
    public static List<ScoredId> normalizeByTop(List<ScoredId> rawDesc) {
        if (rawDesc.isEmpty()) return rawDesc;
        double topScore = rawDesc.getFirst().score();
        if (topScore <= 0.0) {
            return rawDesc.stream().map(s -> new ScoredId(s.id(), 1.0)).toList();
        }
        return rawDesc.stream().map(s -> new ScoredId(s.id(), s.score() / topScore)).toList();
    }

    /**
     * JCLAW-532: agent-recall relevance floor, expressed as a fraction of the top
     * hit's score (0 or negative disables). Hits scoring below
     * {@code topScore * ratio} are dropped from recall so loosely-related
     * memories don't crowd the prompt. Tunable via {@code memory.recall.minScoreRatio}.
     */
    private static double recallMinScoreRatio() {
        return services.ConfigService.getDouble("memory.recall.minScoreRatio", 0.2);
    }

    /**
     * Number of leading entries in a descending-sorted score array that clear the
     * relevance floor ({@code scores[0] * ratio}). Public and pure so the floor
     * decision is unit-testable without standing up a Lucene index (the end-to-end
     * search path is JVM-global and flaky under the concurrent runner — see
     * JCLAW-428).
     */
    public static int recallFloorCount(float[] scoresDesc, double ratio) {
        if (ratio <= 0.0 || scoresDesc.length == 0) return scoresDesc.length;
        float floor = (float) (scoresDesc[0] * ratio);
        int n = 0;
        for (float s : scoresDesc) {
            if (s >= floor) n++;
            else break;
        }
        return n;
    }

    private static List<TaskRunMessage> hydrateTaskRunMessagesInOrder(List<Long> ids) {
        // Bulk-fetch JPA rows, then re-order to match Lucene's relevance ranking.
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
