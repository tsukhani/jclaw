package services.transcription;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static holder for in-flight transcription futures keyed by
 * {@code MessageAttachment.id}. The dispatcher in
 * {@code ConversationService.appendUserMessage} registers a future
 * here when it kicks off a virtual-thread transcription; the
 * {@code agents.VisionAudioAssembler.userMessageFor} text-only branch and the
 * provider-rejection retry branch look up the future to await it.
 *
 * <p>Lookup semantics: completed futures stay in the map after
 * resolution so late lookups still return the result instantly. Memory
 * footprint is one {@link CompletableFuture} of a transcript string
 * per audio attachment ever processed by this JVM — small enough that
 * we don't need eviction. JVM restarts naturally clean the map.
 *
 * <p>Failure contract: on any exception during transcription, the
 * future resolves with the empty-string sentinel {@code ""}. Callers
 * that need to differentiate "transcription failed" from "audio was
 * silent" should check {@link String#isEmpty()} on the result.
 */
public final class PendingTranscripts {

    private static final ConcurrentHashMap<Long, CompletableFuture<String>> futures = new ConcurrentHashMap<>();

    private PendingTranscripts() {}

    /** Register a freshly-spawned future against {@code attachmentId}. */
    public static void register(Long attachmentId, CompletableFuture<String> future) {
        if (attachmentId == null || future == null) return;
        futures.put(attachmentId, future);
    }

    /** Look up the future for {@code attachmentId}; empty when no
     *  transcription was ever dispatched (e.g. transcription provider
     *  unconfigured at the time the message landed). */
    public static Optional<CompletableFuture<String>> lookup(Long attachmentId) {
        if (attachmentId == null) return Optional.empty();
        return Optional.ofNullable(futures.get(attachmentId));
    }

    /** Test seam — drop all in-flight state between tests. */
    public static void clearForTest() {
        futures.clear();
    }
}
