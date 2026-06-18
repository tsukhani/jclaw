package services.video;

import models.Agent;
import models.MessageAttachment;
import services.AgentService;
import services.EventLogger;
import services.video.FrameSampler.FrameSamplingException;

import java.util.List;
import java.util.Map;

/**
 * Routes a video attachment to one of three interpretation strategies based on the agent's model
 * capabilities and returns the OpenAI-style content parts to splice into the outgoing user message
 * (JCLAW-224). The strategy is chosen in preference order:
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

    /** Pick the strategy and produce the content parts for the given video attachment + agent. */
    public static List<Map<String, Object>> dispatch(MessageAttachment video, Agent agent) {
        if (video == null || !video.isVideo()) {
            throw new VideoAdapterException("not a video attachment");
        }
        return switch (strategyFor(agent)) {
            case NATIVE_VIDEO -> nativeVideo(video, agent);
            case MULTI_IMAGE -> multiImageOrTextSummary(video, agent);
            case TEXT_SUMMARY -> textSummary(video, agent);
        };
    }

    /**
     * The strategy the agent's model would use — {@code supportsVideo} ⇒ NATIVE_VIDEO, else
     * {@code supportsVision} ⇒ MULTI_IMAGE, else TEXT_SUMMARY. Exposed for the Settings read-only
     * "which strategy would my model use" display (JCLAW-223) and the routing functional tests.
     */
    public static Strategy strategyFor(Agent agent) {
        if (AgentService.supportsVideo(agent)) return Strategy.NATIVE_VIDEO;
        if (AgentService.supportsVision(agent)) return Strategy.MULTI_IMAGE;
        return Strategy.TEXT_SUMMARY;
    }

    private static List<Map<String, Object>> nativeVideo(MessageAttachment video, Agent agent) {
        var sampled = FrameSampler.sample(video);
        var shape = QwenVideoAdapter.shapeForProvider(agent.modelProvider);
        EventLogger.info("video", "native-video: %d frames, shape=%s, attachment=%s"
                .formatted(sampled.frames().size(), shape, video.uuid));
        return QwenVideoAdapter.contentParts(sampled.frames(), sampled.durationSeconds(), shape);
    }

    private static List<Map<String, Object>> multiImageOrTextSummary(MessageAttachment video, Agent agent) {
        try {
            var sampled = FrameSampler.sample(video);
            EventLogger.info("video", "multi-image: %d frames, attachment=%s"
                    .formatted(sampled.frames().size(), video.uuid));
            return MultiImageVideoAdapter.contentParts(sampled.frames(), sampled.durationSeconds());
        } catch (FrameSamplingException e) {
            EventLogger.warn("video", "multi-image frame extraction failed (%s); falling back to text summary for attachment %s"
                    .formatted(e.getMessage(), video.uuid));
            return textSummary(video, agent);
        }
    }

    private static List<Map<String, Object>> textSummary(MessageAttachment video, Agent agent) {
        EventLogger.info("video", "text-summary: attachment=" + video.uuid);
        return TextSummaryVideoAdapter.contentParts(video, agent);
    }
}
