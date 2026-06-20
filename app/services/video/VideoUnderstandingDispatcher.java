package services.video;

import models.Agent;
import models.MessageAttachment;
import services.AgentService;
import services.EventLogger;
import services.video.FrameSampler.FrameSamplingException;

import java.util.List;
import java.util.Map;

/**
 * Routes a video attachment to an interpretation strategy and returns the OpenAI-style content parts
 * to splice into the outgoing user message (JCLAW-224).
 *
 * <p>Capability-gated, mirroring transcription and image captioning: a chat model that supports video
 * natively watches the clip directly. Only when it can't does a configured dedicated video model
 * ({@code video.provider}/{@code video.model} in Settings → Video Interpretation) step in —
 * interpreting the clip in a separate call ({@link VideoInterpretationRouter}/{@link
 * VideoInterpretationClient}) whose prose is spliced back as a text part, so even a text-only chat
 * model gains video understanding. On any failure (or no dedicated model) the video falls back to what
 * the chat model itself can do, in preference order:
 * <ol>
 *   <li>{@code supportsVideo} → {@link Strategy#NATIVE_VIDEO} ({@link QwenVideoAdapter}, a native
 *       Qwen video part);</li>
 *   <li>else {@code supportsVision} → {@link Strategy#MULTI_IMAGE} ({@link MultiImageVideoAdapter},
 *       sampled frames as still images), falling back to the text summary if frame extraction fails
 *       (no ffmpeg / corrupt video);</li>
 *   <li>else {@link Strategy#TEXT_SUMMARY} ({@link TextSummaryVideoAdapter}, a captioned text
 *       summary).</li>
 * </ol>
 *
 * <p>Call this OUTSIDE any JPA transaction — NATIVE_VIDEO/MULTI_IMAGE sample frames (ffmpeg
 * subprocess) and TEXT_SUMMARY additionally captions each frame and runs a summarize call, all of
 * which must not hold a pooled DB connection. {@code VisionAudioAssembler.applyVideoForCapability}
 * is the orchestrator that runs this in its no-Tx phase and splices the result.
 */
public final class VideoUnderstandingDispatcher {

    private VideoUnderstandingDispatcher() {}

    /** The interpretation strategy chosen for a video, by the target model's capabilities. */
    public enum Strategy { NATIVE_VIDEO, MULTI_IMAGE, TEXT_SUMMARY }

    private static final String EVENT_CATEGORY = "video";

    /** Pick the strategy and produce the content parts for the given video attachment + agent. */
    public static List<Map<String, Object>> dispatch(MessageAttachment video, Agent agent) {
        if (video == null || !video.isVideo()) {
            throw new VideoAdapterException("not a video attachment");
        }
        var supportsVideo = AgentService.supportsVideo(agent);
        var supportsVision = AgentService.supportsVision(agent);

        // Rule 1 — the chat model the user picked is video-capable, so IT interprets the video.
        // Qwen-VL ingests our native frame-array; any other video-capable model (e.g. Gemini) can't
        // (it would silently ignore the Qwen part), so it gets the sampled frames as images — robust,
        // since every vision model reads image_url. A model tagged video but neither Qwen nor vision
        // can take neither, so it falls through to the dedicated/captioning ladder.
        if (supportsVideo) {
            if (QwenVideoAdapter.isQwenVideoModel(agent.modelId)) return nativeVideo(video, agent);
            if (supportsVision) return multiImageOrTextSummary(video, agent);
        }

        // Rule 2 — the chat model can't do video → use the dedicated video model from Settings →
        // Video Interpretation (video.provider/video.model) if one is configured. It interprets the
        // clip in a separate call and we splice the prose back as a text part, so even a text-only
        // chat model gains video understanding. On any failure (missing config, frame sampling, HTTP)
        // fall through to what the chat model itself can do.
        var dedicated = VideoInterpretationRouter.configuredService();
        if (dedicated.isPresent()) {
            try {
                return dedicatedInterpretation(video, dedicated.get());
            } catch (RuntimeException e) {
                EventLogger.warn(EVENT_CATEGORY, "dedicated video model failed (%s); falling back to the chat model for attachment %s"
                        .formatted(e.getMessage(), video.uuid));
            }
        }

        // Rule 5 — the chat model has vision → sampled frames as images. Rule 3 — no vision but an
        // Image Captioning provider is configured → caption each frame into a text summary. Rule 4 —
        // captioning also off → textSummary throws and the caller splices a "could not be interpreted"
        // note (so the model tells the user rather than the turn crashing).
        return supportsVision
                ? multiImageOrTextSummary(video, agent)
                : textSummary(video, agent);
    }

    /** Interpret the whole video with the dedicated model and wrap its prose as a single text part. */
    private static List<Map<String, Object>> dedicatedInterpretation(MessageAttachment video, VideoInterpretationClient client) {
        var text = client.interpret(video);
        if (text == null || text.isBlank()) {
            throw new VideoAdapterException("dedicated video model returned no text");
        }
        EventLogger.info(EVENT_CATEGORY, "dedicated-video: %d chars, attachment=%s".formatted(text.length(), video.uuid));
        var part = new java.util.LinkedHashMap<String, Object>();
        part.put("type", "text");
        part.put("text", "Video interpretation:\n" + text);
        return List.of(part);
    }

    /**
     * The strategy the agent's chat model would use — NATIVE_VIDEO only when it's a Qwen-VL model
     * (the sole family that ingests our inline video format), else {@code supportsVision} ⇒
     * MULTI_IMAGE, else TEXT_SUMMARY. Exposed for the Settings read-only "which strategy would my
     * model use" display (JCLAW-223) and the routing functional tests.
     */
    public static Strategy strategyFor(Agent agent) {
        if (AgentService.supportsVideo(agent) && QwenVideoAdapter.isQwenVideoModel(agent.modelId)) {
            return Strategy.NATIVE_VIDEO;
        }
        if (AgentService.supportsVision(agent)) return Strategy.MULTI_IMAGE;
        return Strategy.TEXT_SUMMARY;
    }

    private static List<Map<String, Object>> nativeVideo(MessageAttachment video, Agent agent) {
        var sampled = FrameSampler.sample(video);
        var shape = QwenVideoAdapter.shapeForProvider(agent.modelProvider);
        EventLogger.info(EVENT_CATEGORY, "native-video: %d frames, shape=%s, attachment=%s"
                .formatted(sampled.frames().size(), shape, video.uuid));
        return QwenVideoAdapter.contentParts(sampled.frames(), sampled.durationSeconds(), shape);
    }

    private static List<Map<String, Object>> multiImageOrTextSummary(MessageAttachment video, Agent agent) {
        try {
            var sampled = FrameSampler.sample(video);
            EventLogger.info(EVENT_CATEGORY, "multi-image: %d frames, attachment=%s"
                    .formatted(sampled.frames().size(), video.uuid));
            return MultiImageVideoAdapter.contentParts(sampled.frames(), sampled.durationSeconds());
        } catch (FrameSamplingException e) {
            EventLogger.warn(EVENT_CATEGORY, "multi-image frame extraction failed (%s); falling back to text summary for attachment %s"
                    .formatted(e.getMessage(), video.uuid));
            return textSummary(video, agent);
        }
    }

    private static List<Map<String, Object>> textSummary(MessageAttachment video, Agent agent) {
        EventLogger.info(EVENT_CATEGORY, "text-summary: attachment=" + video.uuid);
        return TextSummaryVideoAdapter.contentParts(video, agent);
    }
}
