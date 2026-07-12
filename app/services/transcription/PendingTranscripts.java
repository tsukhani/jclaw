package services.transcription;

import play.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Static holder for in-flight transcription futures keyed by
 * {@code MessageAttachment.id}. The dispatcher in
 * {@code ConversationService.appendUserMessage} registers a future
 * here when it kicks off a virtual-thread transcription; the
 * {@code agents.VisionAudioAssembler.userMessageFor} text-only branch and the
 * provider-rejection retry branch look up the future to await it.
 *
 * <p>Lookup semantics: completed futures stay in the map until a
 * consumer awaits and {@linkplain #consume(Long) consumes} them. Keys
 * are monotonic attachment ids under a single-reader contract, so the
 * consumer removes its entry once it has read the resolved value
 * (JCLAW-405) — preventing an otherwise unbounded heap growth
 * proportional to lifetime audio volume on a long-lived backend.
 *
 * <p>Failure contract: on any exception during transcription, the
 * future resolves with the empty-string sentinel {@code ""}. Callers
 * that need to differentiate "transcription failed" from "audio was
 * silent" should check {@link String#isEmpty()} on the result.
 */
public final class PendingTranscripts {

    private static final ConcurrentHashMap<Long, CompletableFuture<String>> futures = new ConcurrentHashMap<>();

    private PendingTranscripts() {}

    /** JCLAW-626: a consumer that dies between register and await would
     *  leak the map entry forever — evict entries after this long as a
     *  backstop (far beyond any legitimate transcription duration). */
    private static final long EVICTION_MS = 60 * 60 * 1000L;

    /** Register a freshly-spawned future against {@code attachmentId}. */
    public static void register(Long attachmentId, CompletableFuture<String> future) {
        if (attachmentId == null || future == null) return;
        var scheduler = CompletableFuture.delayedExecutor(
                EVICTION_MS, TimeUnit.MILLISECONDS);
        scheduler.execute(() -> {
            if (futures.remove(attachmentId, future)) {
                Logger.warn("PendingTranscripts: evicted abandoned entry for attachment %d",
                        attachmentId);
            }
        });
        futures.put(attachmentId, future);
    }

    /** Look up the future for {@code attachmentId}; empty when no
     *  transcription was ever dispatched (e.g. transcription provider
     *  unconfigured at the time the message landed). */
    public static Optional<CompletableFuture<String>> lookup(Long attachmentId) {
        if (attachmentId == null) return Optional.empty();
        return Optional.ofNullable(futures.get(attachmentId));
    }

    /** Drop the entry for {@code attachmentId} once its resolved value
     *  has been read. The single-reader contract means each id is
     *  awaited exactly once; calling this after a successful await
     *  (covering both the transcript and the empty-string failure
     *  sentinel) releases the retained {@link CompletableFuture} so the
     *  map doesn't grow without bound (JCLAW-405). No-op for a null id
     *  or an id never registered. */
    public static void consume(Long attachmentId) {
        if (attachmentId == null) return;
        futures.remove(attachmentId);
    }

    /** Test seam — drop all in-flight state between tests. */
    public static void clearForTest() {
        futures.clear();
    }
}
