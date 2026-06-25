package services.videogen;

/**
 * Typed failure for video-generation backends (JCLAW-231), mirroring
 * {@code services.imagegen.ImageGenerationException}. Thrown from a client's {@code submit} path, and
 * from {@code poll} only on a transport/parse failure of the poll request itself. An upstream
 * <em>generation</em> failure is surfaced as a {@link VideoGenerationService.State#FAILED} poll result,
 * not this exception — the job runner (JCLAW-230) records that on the job rather than blowing up its
 * polling loop.
 */
public class VideoGenerationException extends RuntimeException {

    public VideoGenerationException(String message) {
        super(message);
    }

    public VideoGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
