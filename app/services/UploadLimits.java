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
    public static final String KEY_MAX_FILES = "upload.maxFiles";

    public static final int DEFAULT_MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    public static final int DEFAULT_MAX_AUDIO_BYTES = 100 * 1024 * 1024;
    public static final int DEFAULT_MAX_FILE_BYTES = 100 * 1024 * 1024;

    /** Hard ceiling on {@link #maxFiles()} — operators can lower the per-message
     *  file count, but never raise it above this. Keeping the cap centralised
     *  here means the Settings slider, the server-side check, and any future
     *  callers all agree on the same upper bound. */
    public static final int ABSOLUTE_MAX_FILES = 5;
    public static final int DEFAULT_MAX_FILES = ABSOLUTE_MAX_FILES;

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

    /** Resolve the effective per-message file-count cap. Clamps the configured
     *  value into [1, {@link #ABSOLUTE_MAX_FILES}] so a malformed or
     *  out-of-range config row never lifts the policy ceiling. */
    public static int maxFiles() {
        int configured = ConfigService.getInt(KEY_MAX_FILES, DEFAULT_MAX_FILES);
        if (configured < 1) return 1;
        if (configured > ABSOLUTE_MAX_FILES) return ABSOLUTE_MAX_FILES;
        return configured;
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
