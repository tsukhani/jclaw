package memory;

import models.Memory;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;
import services.Tx;
import services.search.LuceneIndexer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rebuilds every memory's embedding against the currently configured model
 * (JCLAW-933).
 *
 * <p>Switching embedding model strands the whole corpus: vectors produced by a
 * different model cannot be compared with the new one, and on the Lucene backend they
 * cannot even coexist — Lucene pins vector dimension to the field name across the
 * index, so the first write at a new dimension throws before it can replace anything.
 * Re-embedding in place is therefore impossible; the old vectors must be gone first.
 *
 * <p>Ordering, and why it is this shape:
 *
 * <ol>
 *   <li><b>Guard.</b> Refuse outright when the configured dimension exceeds what the
 *       backend can index (JCLAW-935) — otherwise the wipe succeeds and every rebuild
 *       write fails silently, leaving no memory index at all.</li>
 *   <li><b>Wipe.</b> Clear the MEMORY scope, which releases the dimension pin.</li>
 *   <li><b>Text pass.</b> Re-index every memory's text with no vector. Fast, no
 *       network, and it restores keyword recall in seconds rather than leaving it dark
 *       for the whole embedding pass — the wipe removes FTS documents too, not just
 *       vectors.</li>
 *   <li><b>Vector pass.</b> Embed row by row and upsert. Slow and network-bound; only
 *       vector recall is degraded while it runs.</li>
 * </ol>
 *
 * <p>Captures during a run are unaffected: memories are still stored, and once the wipe
 * has happened both this pass and live captures write at the new dimension. The row is
 * the durable artifact — blocking capture would lose facts permanently for a transient
 * reindex.
 */
public final class MemoryReembedService {

    private MemoryReembedService() {}

    private static final String EVENT_CATEGORY = "memory";

    /** Set only after a clean sweep, so an interrupted run retries rather than being skipped. */
    static final String MARKER = "memory.jpa.vector.backfilledForModel";

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger processed = new AtomicInteger();
    private static final AtomicInteger total = new AtomicInteger();
    private static volatile String activeModel = "";
    private static volatile @Nullable String lastError;

    /**
     * What the UI polls. {@code upToDate} is false after a model switch, which is what
     * turns into the prompt to re-embed — it is not derivable from {@code running}.
     */
    public record Status(boolean running, int processed, int total, String model,
                         @Nullable String error, boolean upToDate) {}

    public static Status status() {
        return new Status(running.get(), processed.get(), total.get(),
                activeModel, lastError, upToDate());
    }

    /**
     * Start a rebuild on a virtual thread. Returns the reason it could not start, or
     * {@code null} when it did — single-flight, because two concurrent rebuilds would
     * wipe each other's work.
     */
    public static @Nullable String start() {
        if (!MemoryVectorSettings.enabled()) {
            return "Vector memory is disabled — there is nothing to embed.";
        }
        int dims = MemoryVectorSettings.dimensions();
        boolean pg = JpaMemoryStore.isPostgresDialect();
        if (!MemoryVectorSettings.dimensionsSupported(dims, pg)) {
            return "The configured model is %d-dimensional, above the %d the search index supports."
                    .formatted(dims, MemoryVectorSettings.maxDimensions(pg));
        }
        if (!running.compareAndSet(false, true)) {
            return "A re-embed is already running.";
        }
        lastError = null;
        processed.set(0);
        total.set(0);
        activeModel = MemoryVectorSettings.model();
        Thread.ofVirtual().name("memory-reembed").start(MemoryReembedService::run);
        return null;
    }

    private static void run() {
        try {
            rebuild();
        } catch (Exception e) {
            lastError = e.getMessage();
            EventLogger.warn(EVENT_CATEGORY, "Memory re-embed failed: %s".formatted(e.getMessage()));
        } finally {
            running.set(false);
        }
    }

    private static void rebuild() {
        // Wipe BEFORE snapshotting, not after. A memory stored between a snapshot and a
        // later wipe is absent from the snapshot but has already written its own index
        // document via @PostPersist, so the wipe deletes it and neither pass below puts
        // it back — it survives in the database but is permanently invisible to recall
        // and to dedup's retrieval leg. Wiping first makes every case safe: anything
        // written before the wipe is in the database and therefore in the snapshot,
        // anything written after it indexes itself, and a row caught in between is
        // covered twice, which is harmless because the upsert is idempotent.
        LuceneIndexer.clear(LuceneIndexer.Scope.MEMORY);

        var rows = Tx.run(() -> Memory.<Memory>find("supersededAt IS NULL ORDER BY id").<Memory>fetch()
                .stream().map(m -> new Row(m.id, m.text, String.valueOf(m.agent.id))).toList());
        total.set(rows.size());
        EventLogger.info(EVENT_CATEGORY,
                "Memory re-embed starting: %d memories, model %s".formatted(rows.size(), activeModel));

        for (var r : rows) {
            LuceneIndexer.upsert(LuceneIndexer.Scope.MEMORY, r.id(), r.text(), r.agentId());
        }

        var store = MemoryStoreFactory.get();
        int done = 0;
        for (var r : rows) {
            store.embedStored(String.valueOf(r.id()));   // embeds outside any transaction
            processed.incrementAndGet();
            done++;
        }

        if (done == rows.size()) {
            ConfigService.set(MARKER, activeModel);
        }
        EventLogger.info(EVENT_CATEGORY,
                "Memory re-embed complete: %d/%d memories embedded with %s"
                        .formatted(done, rows.size(), activeModel));
    }

    /** Fields carried out of the read transaction so the passes hold no connection. */
    private record Row(Long id, String text, String agentId) {}

    /**
     * Forget which model the corpus was embedded with, so {@link #upToDate()} reports false and
     * the Settings panel prompts for a re-embed.
     *
     * <p>JCLAW-961: called when the MEMORY index is rebuilt from the database. The backfill
     * restores document text but cannot restore KNN vectors — embedding is an HTTP round-trip
     * per row and must not run at boot — so without this the rebuilt index looks healthy,
     * keyword recall works, and the vector leg is silently gone for good.
     */
    public static void invalidateBackfillMarker() {
        ConfigService.delete(MARKER);
    }

    /**
     * Whether the stored corpus was embedded with the model now configured. False after
     * a model switch, which is what the Settings panel turns into a prompt to re-embed.
     */
    public static boolean upToDate() {
        return MemoryVectorSettings.model().equals(ConfigService.get(MARKER, ""));
    }
}
