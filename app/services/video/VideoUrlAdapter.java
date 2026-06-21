package services.video;

import models.MessageAttachment;
import services.AttachmentService;
import services.ConfigService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Native-video adapter: wraps the WHOLE video as an OpenAI-compatible {@code video_url} content part
 * — a {@code {"type":"video_url","video_url":{"url":"data:<mime>;base64,<bytes>"}}} object. This is
 * the format video-capable models actually accept on OpenRouter and vLLM (validated live against a
 * Gemini route, which read a test clip's colours in order; the older Qwen frame-array format
 * {@code {type:"video", video:[…]}} returned HTTP 200 but was silently dropped). Provider-agnostic —
 * no per-provider wire shapes, and gated on the model's {@code supportsVideo} ({@code input_modalities})
 * flag rather than any model-family guess.
 *
 * <p>Because the whole clip is inlined as base64 (which inflates bytes ~1.33×), this is bounded by a
 * size cap ({@code video.maxInlineMb}, default {@value #DEFAULT_MAX_INLINE_MB}). Over the cap the
 * request body would blow past provider limits, so {@link VideoUnderstandingDispatcher} falls back to
 * frames-as-images. {@link #isWithinInlineCap} exposes that gate.
 */
public final class VideoUrlAdapter {

    private VideoUrlAdapter() {}

    static final String CFG_MAX_INLINE_MB = "video.maxInlineMb";
    static final int DEFAULT_MAX_INLINE_MB = 20;
    static final int MAX_INLINE_MB_MIN = 1;
    static final int MAX_INLINE_MB_MAX = 100;

    /** The configured inline cap in bytes (clamped to [1, 100] MB). */
    public static long maxInlineBytes() {
        int mb = Math.clamp(ConfigService.getInt(CFG_MAX_INLINE_MB, DEFAULT_MAX_INLINE_MB),
                MAX_INLINE_MB_MIN, MAX_INLINE_MB_MAX);
        return mb * 1024L * 1024L;
    }

    /** True when the clip is small enough to inline as a base64 {@code video_url}. */
    public static boolean isWithinInlineCap(MessageAttachment video) {
        return video != null && video.sizeBytes > 0 && video.sizeBytes <= maxInlineBytes();
    }

    /**
     * Build the {@code video_url} content part for the whole video (a {@code data:} URL of its bytes).
     * Reads the file off disk, so call OUTSIDE any JPA transaction. Check {@link #isWithinInlineCap}
     * first — this does not itself enforce the cap.
     */
    public static Map<String, Object> contentPart(MessageAttachment video) {
        var url = new LinkedHashMap<String, Object>();
        url.put("url", AttachmentService.readAsDataUrl(video));
        var part = new LinkedHashMap<String, Object>();
        part.put("type", "video_url");
        part.put("video_url", url);
        return part;
    }
}
