package services.caption;

/**
 * Typed failure for image-captioning backends (JCLAW-212), mirroring
 * {@code TranscriptionException}. Cloud clients throw this from their HTTP core; the
 * {@link ImageCaptionService#caption} entry point catches it, logs, and returns "" so a
 * transient backend problem never blocks the whole turn.
 */
public class CaptionException extends RuntimeException {

    public CaptionException(String message) {
        super(message);
    }

    public CaptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
