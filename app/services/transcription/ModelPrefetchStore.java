package services.transcription;

import com.google.gson.JsonObject;
import play.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared "model status + prefetch" state machine behind the transcription
 * Settings panels. {@link AsrModelStore} (keyed by {@link AsrModel} id) and
 * {@link DiarizeModelStore} (keyed by HF repo) both project a sidecar's
 * per-model status object into a Settings row and drive a single-flight
 * background download; everything common to that lives here so the two stores
 * can't drift apart. Each subclass supplies only its variation — the concrete
 * {@code Status} shape (via {@link #buildStatus}, where ASR folds in its size
 * denominator) and the prefetch body (via the action passed to
 * {@link #startPrefetch}).
 *
 * <p>Deliberately not named {@code *ModelStore}: an ArchUnit rule requires every
 * {@code *ModelStore} class to extend this base, so the base itself sits outside
 * that naming convention on purpose.
 *
 * @param <S> the store-specific status record {@link #rowFor} produces
 */
public abstract class ModelPrefetchStore<S> {

    /** Internal download lifecycle of a model, projected to the frontend wire
     *  vocabulary by {@link #wireName()}. */
    public enum State {
        NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, ERROR, UNAVAILABLE;

        /**
         * Wire status string the Settings frontend consumes — the vocabulary
         * shared with the imagegen panel (ABSENT / DOWNLOADING / AVAILABLE /
         * ERROR / UNAVAILABLE). JCLAW-650 renamed this internal enum
         * (ABSENT→NOT_DOWNLOADED, AVAILABLE→DOWNLOADED) without updating the
         * frontend contract, so the Download button and Ready badge silently
         * stopped rendering; this projection restores it. Keep in lockstep with
         * the {@code TranscriptionModelStatus} union in SettingsTranscriptionPanel.vue.
         */
        public String wireName() {
            return switch (this) {
                case NOT_DOWNLOADED -> "ABSENT";
                case DOWNLOADED -> "AVAILABLE";
                case DOWNLOADING -> "DOWNLOADING";
                case ERROR -> "ERROR";
                case UNAVAILABLE -> "UNAVAILABLE";
            };
        }
    }

    /** In-flight prefetches by id/repo; completed entries are pruned on read. */
    private final Map<String, CompletableFuture<Void>> prefetches = new ConcurrentHashMap<>();
    private final Map<String, String> prefetchErrors = new ConcurrentHashMap<>();

    /** Test-only: clear prefetch state so tests don't bleed. */
    protected final void resetPrefetchState() {
        prefetches.clear();
        prefetchErrors.clear();
    }

    /** A string field on a JSON object, or null when absent/JSON-null. */
    private static String strOrNull(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }

    /**
     * One Settings row from the sidecar's per-model status object, folding in
     * the in-JVM prefetch/error state. The sidecar owns download state (it runs
     * the pull as a detached subprocess and reports progress off the cache dir);
     * the prefetch-error map only carries a failure to even reach the sidecar to
     * kick it off. Subclasses supply the concrete row shape via {@link #buildStatus}.
     */
    protected final S rowFor(String key, JsonObject s) {
        boolean cached = s.get("cached").getAsBoolean();
        boolean downloading = s.has("downloading") && s.get("downloading").getAsBoolean();
        long onDisk = s.get("bytesOnDisk").getAsLong();
        var engine = strOrNull(s, "engine");
        var sidecarError = strOrNull(s, "error");
        String error = sidecarError != null ? sidecarError : prefetchErrors.get(key);
        State state;
        if (downloading) {
            state = State.DOWNLOADING;
        } else if (error != null && !cached) {
            state = State.ERROR;
        } else {
            state = cached ? State.DOWNLOADED : State.NOT_DOWNLOADED;
        }
        return buildStatus(key, state, onDisk, engine, error);
    }

    /**
     * Assemble the store-specific status record. The base has already resolved
     * the shared {@link State} ladder and folded any prefetch error; the
     * subclass adds only its own shape (e.g. the ASR size denominator).
     */
    protected abstract S buildStatus(String key, State state, long bytesDownloaded,
                                     String engine, String error);

    /**
     * Kick a background prefetch of a model's weights; single-flight per key,
     * mirroring the sidecar's detached download. The {@code action} performs the
     * store-specific sidecar call and success log; any {@link RuntimeException}
     * it throws is captured into the per-key error map and re-thrown.
     */
    protected final void startPrefetch(String key, PrefetchAction action) {
        prefetchErrors.remove(key);
        prefetches.compute(key, (k, existing) -> {
            if (existing != null && !existing.isDone()) return existing;
            return CompletableFuture.runAsync(() -> {
                try {
                    action.run(k);
                } catch (RuntimeException e) {
                    prefetchErrors.put(k, e.getMessage());
                    Logger.warn("%s: prefetch of %s failed: %s", getClass().getSimpleName(), k, e.getMessage());
                    throw e;
                }
            });
        });
    }

    /** The store-specific prefetch body: call the sidecar and log success. */
    @FunctionalInterface
    protected interface PrefetchAction {
        void run(String key);
    }
}
