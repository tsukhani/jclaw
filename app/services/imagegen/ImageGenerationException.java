package services.imagegen;

/**
 * Typed failure for image-generation backends (JCLAW-225), mirroring
 * {@code services.caption.CaptionException}. Clients throw this from their HTTP core; unlike the
 * caption path (which swallows failures so a missing description never blocks a turn), the
 * {@code generate_image} tool (JCLAW-228) catches this and returns a typed error string to the
 * agent so it can retry, fall back to text, or surface the failure to the user.
 */
public class ImageGenerationException extends RuntimeException {

    public ImageGenerationException(String message) {
        super(message);
    }

    public ImageGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
