package services;

import models.MessageAttachment;

/**
 * Per-kind upload size limits (JCLAW-131). Config-backed so operators can
 * tune limits live through Settings without a restart. The framework-level
 * {@code play.netty.maxContentLength} ceiling is deliberately set generous
 * ({@code 512 MB}) so Netty never rejects before these checks run.
 *
 * <p>Three keys, one per {@link MessageAttachment} kind:
 * <ul>
 *   <li>{@code upload.maxImageBytes} — defaults to 20 MB, matches the ceiling
 *       typical vision models accept in {@code image_url} content parts</li>
 *   <li>{@code upload.maxAudioBytes} — defaults to 100 MB, enough headroom
 *       for a lossless hour-long recording</li>
 *   <li>{@code upload.maxFileBytes} — defaults to 100 MB, covers PDFs,
 *       office documents, and small datasets</li>
 * </ul>
 */
public final class UploadLimits {

    private UploadLimits() {}

    public static final String KEY_MAX_IMAGE_BYTES = "upload.maxImageBytes";
    public static final String KEY_MAX_AUDIO_BYTES = "upload.maxAudioBytes";
    public static final String KEY_MAX_FILE_BYTES = "upload.maxFileBytes";

    public static final int DEFAULT_MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    public static final int DEFAULT_MAX_AUDIO_BYTES = 100 * 1024 * 1024;
    public static final int DEFAULT_MAX_FILE_BYTES = 100 * 1024 * 1024;

    /** Resolve the effective cap for the given attachment kind. */
    public static long forKind(String kind) {
        return switch (kind) {
            case MessageAttachment.KIND_IMAGE ->
                    ConfigService.getInt(KEY_MAX_IMAGE_BYTES, DEFAULT_MAX_IMAGE_BYTES);
            case MessageAttachment.KIND_AUDIO ->
                    ConfigService.getInt(KEY_MAX_AUDIO_BYTES, DEFAULT_MAX_AUDIO_BYTES);
            default ->
                    ConfigService.getInt(KEY_MAX_FILE_BYTES, DEFAULT_MAX_FILE_BYTES);
        };
    }

    /** Human-friendly name for error messages — "image", "audio", or "file". */
    public static String displayName(String kind) {
        return switch (kind) {
            case MessageAttachment.KIND_IMAGE -> "image";
            case MessageAttachment.KIND_AUDIO -> "audio";
            default -> "file";
        };
    }
}
