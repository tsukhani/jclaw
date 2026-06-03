package services.search;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.FSDirectory;
import play.Play;
import services.EventLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JVM-wide owner of the Lucene 10 IndexWriter + SearcherManager instances
 * for every on-disk full-text index JClaw maintains. Replaces H2's
 * {@code FullTextLucene} as the keeper of the index files; sync is driven
 * by JPA lifecycle hooks on each indexed entity rather than DB triggers.
 *
 * <h2>Multi-scope (JCLAW-304)</h2>
 * One {@link Scope} per indexed entity. Each scope gets its own
 * subdirectory under {@code data/jclaw-lucene/<scope>/}, its own
 * {@link IndexWriter}, and its own {@link SearcherManager}. Callers pass
 * the scope explicitly on every {@link #upsert}/{@link #remove}/
 * {@link #searcherManager} call, so segments never cross-contaminate
 * between e.g. TaskRunMessage transcripts and Task name/description
 * documents.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #open()} — called once from {@code FullTextSearchInitJob}
 *       at {@code @OnApplicationStart}. Opens an FSDirectory + writer +
 *       SearcherManager per scope.</li>
 *   <li>{@link #close()} — called from {@code ShutdownJob} as one of its
 *       {@code @OnApplicationStop} fan-out components. Commits pending writes and
 *       releases file locks for every scope so the next boot opens
 *       cleanly.</li>
 *   <li>{@link #upsert(Scope, long, String)} / {@link #remove(Scope, long)}
 *       — called from each entity's lifecycle hooks. Failures are caught
 *       and logged; the indexer never aborts the parent JPA transaction.</li>
 * </ul>
 *
 * <h2>Why a SearcherManager</h2>
 * Lucene's IndexReader is immutable per snapshot — writes are invisible
 * until a fresh reader is opened. SearcherManager handles the
 * reopen-on-search cadence (we call {@code maybeRefresh} on each query),
 * so callers always see the latest commits with near-real-time freshness
 * without managing reader lifecycles by hand.
 */
public final class LuceneIndexer {

    /**
     * The set of indexed entities JClaw maintains. Adding a new scope
     * requires (a) the corresponding entity's {@code @PostPersist} /
     * {@code @PostUpdate} / {@code @PostRemove} hooks and (b) a backfill
     * branch in {@code DirectLuceneMessageSearchRepository.init}.
     */
    public enum Scope {
        /** TaskRunMessage.content — per-fire transcript turns. */
        TASK_RUN_MESSAGE("task_run_message"),
        /** Message.content — every chat / conversation message. */
        CONVERSATION_MESSAGE("conversation_message"),
        /** Task virtual doc: name + description for the operator-facing
         *  task catalog search. */
        TASK("task"),
        /** SubagentRun virtual doc: label + outcome for the admin
         *  subagent-runs search. */
        SUBAGENT_RUN("subagent_run"),
        /** Memory.text — per-agent memories. The only scope that carries an
         *  {@link #AGENT_FIELD} so search can be filtered to one agent
         *  (memories never cross agents). */
        MEMORY("memory");

        private final String dirName;

        Scope(String dirName) {
            this.dirName = dirName;
        }

        /** Subdirectory under {@code data/jclaw-lucene/} where this scope's
         *  Lucene segments live. */
        public String dirName() {
            return dirName;
        }
    }

    /** Field names exposed in indexed Documents. */
    static final String ID_FIELD = "id";
    static final String CONTENT_FIELD = "content";
    /** Exact-match filter field, set only on scopes that need per-owner
     *  filtering (currently {@link Scope#MEMORY}, keyed by agent id). */
    static final String AGENT_FIELD = "agent";

    /** EventLogger category for all messages emitted from this indexer. */
    private static final String CATEGORY = "search";

    // Writer and SearcherManager maps are written once at open() under the
    // class monitor and read by every subsequent indexer/search call.
    // EnumMap keyed by Scope so per-scope lookups are O(1) array accesses
    // without boxing or hash work. Reads are always after a synchronized
    // open() write, so the JMM happens-before from the synchronized region
    // is sufficient — no separate volatile needed on the map references
    // themselves since the EnumMap is mutated only inside open()/close().
    private static final Map<Scope, IndexWriter> WRITERS = new EnumMap<>(Scope.class);
    private static final Map<Scope, SearcherManager> SEARCHERS = new EnumMap<>(Scope.class);

    /**
     * Seconds between periodic background commits. Search visibility comes
     * from {@code maybeRefresh} on the writer-NRT SearcherManager (sees
     * in-RAM uncommitted segments), so per-write commit only bought
     * durability at the cost of an fsync on every entity hook. We relax
     * that to a periodic fsync cadence; the index is DERIVED from DB rows
     * (re-backfillable on a wiped/stale boot), so a small window of
     * uncommitted segments lost in a crash is recoverable. Override via
     * {@value #COMMIT_INTERVAL_PROPERTY}.
     */
    private static final String COMMIT_INTERVAL_PROPERTY = "jclaw.search.commitIntervalSeconds";
    private static final long DEFAULT_COMMIT_INTERVAL_SECONDS = 30L;

    /**
     * Single daemon thread that fsyncs every scope's writer on the
     * {@link #COMMIT_INTERVAL_PROPERTY} cadence. Created in {@link #open()},
     * stopped in {@link #close()}. Guarded by the class monitor along with
     * the writer/searcher maps.
     */
    private static ScheduledExecutorService commitScheduler;

    private LuceneIndexer() {}

    /**
     * Open every scope's index. Idempotent across boot retries; a second
     * call while writers are alive is a no-op.
     */
    public static synchronized void open() throws IOException {
        if (!WRITERS.isEmpty()) return;
        try {
            for (var scope : Scope.values()) {
                openScope(scope);
            }
            startCommitScheduler();
        } catch (IOException | RuntimeException e) {
            // Partial-open rollback: any scope opened before the failure
            // is closed so the next retry starts clean. Without this, a
            // mid-loop failure leaves writers holding FS locks and the
            // next open() can't grab them.
            closeQuietly();
            throw e;
        }
    }

    private static void startCommitScheduler() {
        var interval = commitIntervalSeconds();
        commitScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "jclaw-lucene-commit");
            t.setDaemon(true);
            return t;
        });
        commitScheduler.scheduleWithFixedDelay(
                LuceneIndexer::commitAll, interval, interval, TimeUnit.SECONDS);
    }

    private static long commitIntervalSeconds() {
        var raw = Play.configuration.getProperty(COMMIT_INTERVAL_PROPERTY);
        if (raw == null || raw.isBlank()) return DEFAULT_COMMIT_INTERVAL_SECONDS;
        try {
            var v = Long.parseLong(raw.trim());
            return v > 0 ? v : DEFAULT_COMMIT_INTERVAL_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_COMMIT_INTERVAL_SECONDS;
        }
    }

    /**
     * Fsync every open scope's writer. Runs on the commit-scheduler daemon
     * thread and (synchronously) from {@link #closeQuietly()} before close.
     * Per-scope failures are logged, never propagated, so one bad writer
     * doesn't starve the others' cadence.
     */
    private static void commitAll() {
        for (var entry : WRITERS.entrySet()) {
            try {
                entry.getValue().commit();
            } catch (IOException | RuntimeException e) {
                EventLogger.warn(CATEGORY, null, null,
                        "Lucene periodic commit failed: scope=%s: %s"
                                .formatted(entry.getKey().name(), e.getMessage()));
            }
        }
    }

    private static void openScope(Scope scope) throws IOException {
        var indexDir = indexPath(scope);
        Files.createDirectories(indexDir);
        // Pre-v1 codec note: if the directory contains segments from an
        // older Lucene codec (e.g. data/jclaw-lucene/ left over from a
        // pre-Lucene-10 install), they read fine via the
        // lucene-backward-codecs path. We don't ship backward-codecs on
        // the classpath, so segments older than Lucene99 fail to open —
        // the only way that happens is if an operator drops in segments
        // from another install. The cleanup story is "wipe the directory".
        var dir = FSDirectory.open(indexDir);
        IndexWriter writer;
        try {
            var iwc = new IndexWriterConfig(new StandardAnalyzer());
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            // IndexWriter takes ownership of dir on success and closes it
            // via its own close(); we only release dir if construction throws
            // (e.g. lock contention, corrupt segment) — otherwise the FS lock
            // leaks and blocks next-boot retry.
            writer = new IndexWriter(dir, iwc);
        } catch (IOException | RuntimeException e) {
            try { dir.close(); } catch (IOException _) { /* surface original */ }
            throw e;
        }
        WRITERS.put(scope, writer);
        SEARCHERS.put(scope, new SearcherManager(writer, new SearcherFactory()));
        EventLogger.info(CATEGORY, null, null,
                "Lucene index opened: scope=%s at %s (%d existing docs)"
                        .formatted(scope.name(), indexDir, writer.getDocStats().numDocs));
    }

    /** Commit pending writes and close every scope. Idempotent. */
    public static synchronized void close() {
        closeQuietly();
    }

    private static void closeQuietly() {
        // Stop the periodic-commit daemon before closing writers so it
        // doesn't fire a commit into a half-closed writer. IndexWriter.close()
        // below flushes and commits any segments buffered since the last
        // cadence tick, so durability is preserved across the shutdown.
        if (commitScheduler != null) {
            commitScheduler.shutdownNow();
            commitScheduler = null;
        }
        for (var entry : SEARCHERS.entrySet()) {
            try {
                entry.getValue().close();
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, null, null,
                        "SearcherManager close (scope=%s): %s"
                                .formatted(entry.getKey().name(), e.getMessage()));
            }
        }
        SEARCHERS.clear();
        for (var entry : WRITERS.entrySet()) {
            try {
                entry.getValue().close();
                EventLogger.info(CATEGORY, null, null,
                        "Lucene index closed: scope=%s".formatted(entry.getKey().name()));
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, null, null,
                        "IndexWriter close (scope=%s): %s"
                                .formatted(entry.getKey().name(), e.getMessage()));
            }
        }
        WRITERS.clear();
    }

    /**
     * Add or update the index entry for the given {@code (scope, id)}
     * pair, storing {@code content} as the indexed text. Hibernate calls
     * this from each entity's {@code @PostPersist}/{@code @PostUpdate}
     * callback; failures must not propagate, or the calling Tx would
     * roll back over a transient FS issue.
     *
     * <p>{@code null} or blank content writes an empty content field —
     * the doc still exists so a subsequent {@link #remove} can target it
     * by id, but it matches no full-text queries until content arrives
     * via a later update. This matches the pre-multi-scope behavior where
     * empty TaskRunMessage rows were still indexed.
     */
    public static void upsert(Scope scope, long id, String content) {
        upsert(scope, id, content, null);
    }

    /**
     * As {@link #upsert(Scope, long, String)} but also indexes an exact-match
     * {@link #AGENT_FIELD} when {@code agentKey} is non-null, so the scope can
     * be searched filtered to a single owner (the {@link Scope#MEMORY} scope
     * keys this on the agent id). A null {@code agentKey} writes no agent field,
     * matching every other scope.
     */
    public static void upsert(Scope scope, long id, String content, String agentKey) {
        var writer = WRITERS.get(scope);
        if (writer == null) return;
        try {
            var doc = new Document();
            doc.add(new StringField(ID_FIELD, String.valueOf(id), Field.Store.YES));
            doc.add(new TextField(CONTENT_FIELD, content != null ? content : "", Field.Store.NO));
            if (agentKey != null) doc.add(new StringField(AGENT_FIELD, agentKey, Field.Store.NO));
            writer.updateDocument(new Term(ID_FIELD, String.valueOf(id)), doc);
            // No per-write commit: the write is searchable via the writer-NRT
            // SearcherManager's maybeRefresh (called on every query). Durability
            // comes from the periodic commit cadence plus close(), not an fsync
            // on every entity hook — see the COMMIT_INTERVAL_PROPERTY rationale.
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, null, null,
                    "Lucene upsert failed: scope=%s id=%d: %s"
                            .formatted(scope.name(), id, e.getMessage()));
        }
    }

    /**
     * Convenience overload for {@link Scope#TASK_RUN_MESSAGE} — the
     * existing call-site shape from before JCLAW-304's multi-scope work.
     * Reads {@link models.TaskRunMessage#content} and delegates to
     * {@link #upsert(Scope, long, String)}.
     */
    public static void upsert(models.TaskRunMessage m) {
        if (m == null || m.id == null) return;
        upsert(Scope.TASK_RUN_MESSAGE, m.id, m.content);
    }

    /**
     * Drop the index entry for the row with the given id. Hibernate calls
     * this from each entity's {@code @PostRemove}; same no-throw contract
     * as {@link #upsert}.
     */
    public static void remove(Scope scope, long id) {
        var writer = WRITERS.get(scope);
        if (writer == null) return;
        try {
            writer.deleteDocuments(new Term(ID_FIELD, String.valueOf(id)));
            // No per-write commit — same cadence-based durability as upsert.
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, null, null,
                    "Lucene remove failed: scope=%s id=%d: %s"
                            .formatted(scope.name(), id, e.getMessage()));
        }
    }

    /**
     * Convenience overload for {@link Scope#TASK_RUN_MESSAGE} — preserves
     * the pre-multi-scope call shape.
     */
    public static void remove(long id) {
        remove(Scope.TASK_RUN_MESSAGE, id);
    }

    /** Internal accessor for a scope's SearcherManager. */
    static SearcherManager searcherManager(Scope scope) {
        return SEARCHERS.get(scope);
    }

    /**
     * Fsync one scope's writer. Used by the backfill path to commit the
     * whole row loop ONCE at the end instead of one fsync per row (the
     * per-write {@link #upsert} no longer commits). No-op if the scope
     * isn't open. Failures are logged, never propagated — same no-throw
     * contract as {@link #upsert}.
     */
    public static void commit(Scope scope) {
        var writer = WRITERS.get(scope);
        if (writer == null) return;
        try {
            writer.commit();
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, null, null,
                    "Lucene commit failed: scope=%s: %s"
                            .formatted(scope.name(), e.getMessage()));
        }
    }

    /** Whether the indexes have been opened. */
    public static boolean isOpen() {
        return !WRITERS.isEmpty();
    }

    /** Number of indexed documents in the given scope. Test/admin
     *  introspection. */
    public static int docCount(Scope scope) {
        var writer = WRITERS.get(scope);
        if (writer == null) return 0;
        return writer.getDocStats().numDocs;
    }

    /** Backwards-compatible doc count for the TASK_RUN_MESSAGE scope —
     *  preserved so legacy callers and tests don't need refactoring on
     *  the JCLAW-304 cut. New code should call {@link #docCount(Scope)}. */
    public static int docCount() {
        return docCount(Scope.TASK_RUN_MESSAGE);
    }

    /** System-property override for {@link #indexPath(Scope)}. Tests set
     *  this in {@code @BeforeAll} to a freshly-created temp directory so
     *  the autotest JVM doesn't fight a running production JVM for the
     *  production index's {@code write.lock}. Unset (or blank) preserves
     *  the production default at {@code data/jclaw-lucene/<scope>/}. Each
     *  scope's subdirectory is created under whichever root resolves.
     *
     *  <p>Tests must {@link #close} before clearing the property, so the
     *  next test's {@link #open} re-resolves against either a fresh
     *  override or the production default. The companion
     *  {@link #setIndexPathForTest} helper handles both halves and is
     *  the documented test entrypoint. */
    public static final String INDEX_PATH_PROPERTY = "jclaw.search.lucenePath";

    /**
     * Test-only seam: redirect {@link #indexPath(Scope)} to use {@code path}
     * as the parent directory (each scope gets its own subdirectory under
     * it) for the lifetime of the JVM, or until called again with
     * {@code null} to clear. Tests that drive the real Lucene path must
     * call this in {@code @BeforeAll} pointing at a per-class temp
     * directory; without the redirect the index opens at
     * {@code data/jclaw-lucene/...} and collides with any running
     * production JVM holding the same lock.
     *
     * <p>Idempotent. Safe to call before any {@link #open}; the resolved
     * path is read fresh on every {@code open}.
     */
    public static void setIndexPathForTest(Path path) {
        if (path == null) {
            System.clearProperty(INDEX_PATH_PROPERTY);
        } else {
            System.setProperty(INDEX_PATH_PROPERTY, path.toString());
        }
    }

    private static Path indexPath(Scope scope) {
        // Test override wins so autotest runs land their index in a
        // dedicated tmp directory, never the production default.
        var override = System.getProperty(INDEX_PATH_PROPERTY);
        Path root;
        if (override != null && !override.isBlank()) {
            root = Path.of(override);
        } else {
            // Production default: resolve against the Play app root so the
            // running JVM and any subprocess (cli admin, migration tool)
            // all agree on the same path regardless of cwd.
            root = Play.applicationPath.toPath().resolve("data/jclaw-lucene");
        }
        return root.resolve(scope.dirName());
    }
}
