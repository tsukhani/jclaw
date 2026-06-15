package agents;

import llm.LlmProvider;
import models.Agent;
import models.Conversation;
import services.EventLogger;

/**
 * Runtime retry policy for the JCLAW-215 image-passthrough path — the vision analogue of
 * {@link AudioRetryStrategy} (JCLAW-216). When a model advertises {@code supportsVision} but the
 * provider rejects the actual image at call time (a 4xx format / size / decode error), the
 * synchronous tool loop downgrades that turn to a generated caption and retries once.
 *
 * <p>Unlike audio, the caption is computed on demand by
 * {@link VisionAudioAssembler#applyCaptionsForCapability} (or falls back to a "description
 * unavailable" note), so there is no "nothing to fall back to → hard fail" branch — the retry
 * always has something usable, which is strictly better than surfacing the provider's 4xx.
 *
 * <p>The build-time piece (assembling the user message as an {@code image_url} part or a caption
 * text block) lives in {@link VisionAudioAssembler}; this class is purely the runtime-error
 * reaction. Reuses {@link AudioRetryStrategy#shortErrorTag} for the log tag.
 */
public final class ImageRetryStrategy {

    private ImageRetryStrategy() {}

    /**
     * Heuristic: detect provider 4xx errors that are "we can't accept this image" rejections
     * rather than generic client errors. Lenient on purpose — a false positive downgrades to a
     * usable caption-text retry, which is better than a flat error.
     */
    public static boolean isImageFormatRejection(Throwable t) {
        if (t == null) return false;
        var msg = t.getMessage();
        if (msg == null) return false;
        var lower = msg.toLowerCase();
        if (!lower.contains("http 4")) return false; // 4xx only
        if (lower.contains("invalid_image") || lower.contains("invalid image")) return true;
        if (lower.contains("image") && (lower.contains("not supported") || lower.contains("unsupported")
                || lower.contains("decode") || lower.contains("too large") || lower.contains("invalid"))) {
            return true;
        }
        // Some providers spell it on the image_url content part or with generic format wording.
        return lower.contains("image_url") || (lower.contains("image") && lower.contains("format"));
    }

    /**
     * Structured outcome log mirroring {@link AudioRetryStrategy#logAudioPassthroughOutcome}, so the
     * vision passthrough grows the same provider/outcome field-data matrix. No message content.
     *
     * @param outcome one of {@code "accepted"} (passthrough success), {@code "downgraded"} (success
     *                after the caption retry), or {@code "error"} (failed even after retry).
     * @param errorTag short tag from {@link AudioRetryStrategy#shortErrorTag} on the error path;
     *                 null otherwise.
     */
    static void logImagePassthroughOutcome(Agent agent, Conversation conversation, LlmProvider provider,
                                           String outcome, String errorTag) {
        var providerName = provider != null && provider.config() != null ? provider.config().name() : "unknown";
        var modelId = ModelResolver.effectiveModelId(agent, conversation);
        var channel = conversation != null ? conversation.channelType : null;
        var detail = "provider=%s model=%s outcome=%s%s".formatted(
                providerName, modelId, outcome, errorTag != null ? " error_tag=" + errorTag : "");
        EventLogger.info("IMAGE_PASSTHROUGH_OUTCOME", agent != null ? agent.name : null, channel, detail);
    }
}
