package services.search;

import models.TaskRunMessage;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM-wide owner of the Lucene 10 IndexWriter + SearcherManager for the
 * {@code task_run_message.content} full-text index. Replaces H2's
 * {@code FullTextLucene} as the keeper of the on-disk Lucene index;
 * sync is driven by JPA lifecycle hooks on
 * {@link models.TaskRunMessage#onIndex} rather than DB triggers.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>{@link #open()} — called once from
 *       {@code FullTextSearchInitJob} at {@code @OnApplicationStart}.
 *       Opens the FSDirectory under {@code data/jclaw-lucene/task_run_message/},
 *       builds the IndexWriter, and wraps it in a SearcherManager.</li>
 *   <li>{@link #close()} — called from
 *       {@code FullTextSearchShutdownJob} at {@code @OnApplicationStop}.
 *       Commits pending writes and releases the file lock so the next
 *       boot opens cleanly.</li>
 *   <li>{@link #upsert(TaskRunMessage)} / {@link #remove(long)} —
 *       called from the entity lifecycle hooks. Failures are caught and
 *       logged; the indexer never aborts the parent JPA transaction.</li>
 * </ul>
 *
 * <h3>Why a SearcherManager</h3>
 * Lucene's IndexReader is immutable per snapshot — writes are invisible
 * until a fresh reader is opened. SearcherManager handles the
 * reopen-on-search cadence (we call {@code maybeRefresh} on each query),
 * so callers always see the latest commits with near-real-time freshness
 * without managing reader lifecycles by hand.
 */
public final class LuceneIndexer {

    /** Field names exposed in indexed Documents. */
    static final String ID_FIELD = "id";
    static final String CONTENT_FIELD = "content";

    /** EventLogger category for all messages emitted from this indexer. */
    private static final String CATEGORY = "search";

    // Writer and SearcherManager are written once at open() under the class
    // monitor and read by every subsequent indexer/search call. Pure
    // publish-once-read-many reference handoff — volatile reference is
    // the textbook valid use of volatile, S3077 doesn't apply.
    @SuppressWarnings("java:S3077")
    private static final AtomicReference<IndexWriter> WRITER = new AtomicReference<>();
    @SuppressWarnings("java:S3077")
    private static final AtomicReference<SearcherManager> SEARCHER = new AtomicReference<>();

    private LuceneIndexer() {}

    /**
     * Open the index. Idempotent across boot retries; a second call
     * while the writer is alive is a no-op.
     */
    public static synchronized void open() throws IOException {
        if (WRITER.get() != null) return;

        var indexDir = indexPath();
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
            try { dir.close(); } catch (IOException ignored) { /* surface original */ }
            throw e;
        }
        WRITER.set(writer);
        SEARCHER.set(new SearcherManager(writer, new SearcherFactory()));

        EventLogger.info(CATEGORY, null, null,
                "Lucene index opened at %s (%d existing docs)"
                        .formatted(indexDir, writer.getDocStats().numDocs));
    }

    /** Commit pending writes and close. Idempotent. */
    public static synchronized void close() {
        var sm = SEARCHER.getAndSet(null);
        if (sm != null) {
            try {
                sm.close();
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, null, null,
                        "SearcherManager close: %s".formatted(e.getMessage()));
            }
        }
        var writer = WRITER.getAndSet(null);
        if (writer != null) {
            try {
                writer.close();
                EventLogger.info(CATEGORY, null, null, "Lucene index closed");
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, null, null,
                        "IndexWriter close: %s".formatted(e.getMessage()));
            }
        }
    }

    /**
     * Add or update the index entry for {@code m}. Hibernate calls this
     * from {@link TaskRunMessage}'s {@code @PostPersist}/{@code @PostUpdate}
     * callbacks; failures must not propagate, or the calling Tx would
     * roll back over a transient FS issue.
     */
    public static void upsert(TaskRunMessage m) {
        var writer = WRITER.get();
        if (writer == null || m == null || m.id == null) return;
        try {
            var doc = new Document();
            doc.add(new StringField(ID_FIELD, String.valueOf(m.id), Field.Store.YES));
            doc.add(new TextField(CONTENT_FIELD, m.content != null ? m.content : "", Field.Store.NO));
            writer.updateDocument(new Term(ID_FIELD, String.valueOf(m.id)), doc);
            // Commit per-write keeps the index durable across crashes.
            // 50-conc, 50-turn loadtest @ ~9 req/s for real provider showed
            // no contention pressure on the writer's per-commit fsync.
            writer.commit();
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, null, null,
                    "Lucene upsert failed for id=%d: %s".formatted(m.id, e.getMessage()));
        }
    }

    /**
     * Drop the index entry for the row with the given id. Hibernate calls
     * this from {@link TaskRunMessage}'s {@code @PostRemove}; same no-throw
     * contract as {@link #upsert}.
     */
    public static void remove(long id) {
        var writer = WRITER.get();
        if (writer == null) return;
        try {
            writer.deleteDocuments(new Term(ID_FIELD, String.valueOf(id)));
            writer.commit();
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, null, null,
                    "Lucene remove failed for id=%d: %s".formatted(id, e.getMessage()));
        }
    }

    /** Internal accessor for the SearcherManager. */
    static SearcherManager searcherManager() {
        return SEARCHER.get();
    }

    /** Whether the index has been opened. */
    public static boolean isOpen() {
        return WRITER.get() != null;
    }

    /** Number of indexed documents. Test/admin introspection. */
    public static int docCount() {
        var writer = WRITER.get();
        if (writer == null) return 0;
        return writer.getDocStats().numDocs;
    }

    /** System-property override for {@link #indexPath()}. Tests set this
     *  in {@code @BeforeAll} to a freshly-created temp directory so the
     *  autotest JVM doesn't fight a running production JVM for the
     *  production index's {@code write.lock}. Unset (or blank) preserves
     *  the production default at {@code data/jclaw-lucene/task_run_message}.
     *
     *  <p>Tests must {@link #close} before clearing the property, so the
     *  next test's {@link #open} re-resolves against either a fresh
     *  override or the production default. The companion
     *  {@link #setIndexPathForTest} helper handles both halves and is
     *  the documented test entrypoint. */
    public static final String INDEX_PATH_PROPERTY = "jclaw.search.lucenePath";

    /**
     * Test-only seam: redirect {@link #indexPath} to {@code path} for the
     * lifetime of the JVM (or until called again with {@code null} to
     * clear). Tests that drive the real Lucene path must call this in
     * {@code @BeforeAll} pointing at a per-class temp directory; without
     * the redirect the index opens at {@code data/jclaw-lucene/...} and
     * collides with any running production JVM holding the same lock.
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

    private static Path indexPath() {
        // Test override wins so autotest runs land their index in a
        // dedicated tmp directory, never the production default.
        var override = System.getProperty(INDEX_PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        // Production default: resolve against the Play app root so the
        // running JVM and any subprocess (cli admin, migration tool) all
        // agree on the same path regardless of cwd.
        return Play.applicationPath.toPath().resolve("data/jclaw-lucene/task_run_message");
    }
}
