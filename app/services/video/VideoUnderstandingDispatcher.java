package services.video;

import models.Agent;
import models.MessageAttachment;
import services.AgentService;
import services.EventLogger;
import services.video.FrameSampler.FrameSamplingException;

import java.util.List;
import java.util.Map;

/**
 * Routes a video attachment to one of the three interpretation tiers based on the agent's
 * model capabilities and returns the OpenAI-style content parts to splice into the outgoing
 * user message (JCLAW-224). The routing order:
 * <ol>
 *   <li>{@code supportsVideo} → <b>Tier-1</b> ({@link QwenVideoAdapter}, native Qwen video part);</li>
 *   <li>else {@code supportsVision} → <b>Tier-2</b> ({@link Tier2VideoAdapter}, frames as images),
 *       falling through to Tier-3 if frame extraction fails (no ffmpeg / corrupt video);</li>
 *   <li>else <b>Tier-3</b> ({@link Tier3VideoAdapter}, text temporal summary).</li>
 * </ol>
 *
 * <p>Call this OUTSIDE any JPA transaction — Tier-1/Tier-2 sample frames (ffmpeg subprocess)
 * and Tier-3 additionally captions each frame and runs a summarize call, all of which must not
 * hold a pooled DB connection. {@code VisionAudioAssembler.applyVideoForCapability} is the
 * orchestrator that runs this in its no-Tx phase and splices the result.
 */
public final class VideoUnderstandingDispatcher {

    private VideoUnderstandingDispatcher() {}

    public enum Tier { TIER1_NATIVE, TIER2_FRAMES, TIER3_SUMMARY }

    /** Pick the tier and produce the content parts for the given video attachment + agent. */
    public static List<Map<String, Object>> dispatch(MessageAttachment video, Agent agent) {
        if (video == null || !video.isVideo()) {
            throw new VideoAdapterException("not a video attachment");
        }
        return switch (tierFor(agent)) {
            case TIER1_NATIVE -> tier1(video, agent);
            case TIER2_FRAMES -> tier2OrFallback(video, agent);
            case TIER3_SUMMARY -> tier3(video, agent);
        };
    }

    /**
     * The tier the agent's model would use — {@code supportsVideo} ⇒ Tier-1, else
     * {@code supportsVision} ⇒ Tier-2, else Tier-3. Exposed for the Settings read-only
     * "which tier would my model use" display (JCLAW-223) and the routing functional tests.
     */
    public static Tier tierFor(Agent agent) {
        if (AgentService.supportsVideo(agent)) return Tier.TIER1_NATIVE;
        if (AgentService.supportsVision(agent)) return Tier.TIER2_FRAMES;
        return Tier.TIER3_SUMMARY;
    }

    private static List<Map<String, Object>> tier1(MessageAttachment video, Agent agent) {
        var sampled = FrameSampler.sample(video);
        var shape = QwenVideoAdapter.shapeForProvider(agent.modelProvider);
        EventLogger.info("video", "Tier-1 native: %d frames, shape=%s, attachment=%s"
                .formatted(sampled.frames().size(), shape, video.uuid));
        return QwenVideoAdapter.contentParts(sampled.frames(), sampled.durationSeconds(), shape);
    }

    private static List<Map<String, Object>> tier2OrFallback(MessageAttachment video, Agent agent) {
        try {
            var sampled = FrameSampler.sample(video);
            EventLogger.info("video", "Tier-2 frames-as-images: %d frames, attachment=%s"
                    .formatted(sampled.frames().size(), video.uuid));
            return Tier2VideoAdapter.contentParts(sampled.frames(), sampled.durationSeconds());
        } catch (FrameSamplingException e) {
            EventLogger.warn("video", "Tier-2 frame extraction failed (%s); falling through to Tier-3 for attachment %s"
                    .formatted(e.getMessage(), video.uuid));
            return tier3(video, agent);
        }
    }

    private static List<Map<String, Object>> tier3(MessageAttachment video, Agent agent) {
        EventLogger.info("video", "Tier-3 text summary: attachment=" + video.uuid);
        return Tier3VideoAdapter.contentParts(video, agent);
    }
}
