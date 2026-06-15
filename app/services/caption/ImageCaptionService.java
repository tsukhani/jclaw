package services.caption;

import models.MessageAttachment;

/**
 * Single contract for any image-captioning backend in jclaw (JCLAW-207) — the local in-JVM
 * captioner (JCLAW-213, the vision analogue of whisper-jni) and the cloud OpenAI / OpenRouter
 * clients (JCLAW-212) all implement this so the message pipeline can pick one via the
 * {@code caption.provider} config key without caring about the transport.
 *
 * <p>This is the fallback path for <b>non-vision</b> models: a text-only agent still "sees" an
 * uploaded image as a generated description, exactly as a non-audio agent "hears" audio as a
 * whisper transcript ({@code services.transcription.TranscriptionService}).
 *
 * <p>Failure surface: implementations return an <b>empty string</b> on failure (errors logged),
 * never null, so the caller can log-and-fall-through without a transient backend problem blocking
 * the whole turn.
 */
public interface ImageCaptionService {

    /**
     * Describe the image referenced by {@code attachment}. Returns the caption text, or an empty
     * string when captioning is unavailable or fails. Never returns null.
     */
    String caption(MessageAttachment attachment);

    /**
     * Describe the image carried by a {@code data:<mime>;base64,...} URL. This is the
     * transaction-free seam the message pipeline uses (JCLAW-215): the caller reads the
     * attachment bytes inside a short JPA transaction, releases the connection, then runs the
     * (slow) caption model via this method with <b>no transaction held</b> — mirroring how the
     * audio path awaits Whisper outside any {@code Tx.run}.
     *
     * <p>Unlike {@link #caption(MessageAttachment)} this raw seam <b>throws</b>
     * {@link CaptionException} on failure rather than swallowing it; the pipeline catches and
     * falls through to a "description unavailable" note so a transient backend problem never
     * blocks the turn.
     */
    String captionDataUrl(String dataUrl);
}
