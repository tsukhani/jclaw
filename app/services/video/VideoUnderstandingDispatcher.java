package services.video;

import models.Agent;
import models.MessageAttachment;
import services.AgentService;
import services.EventLogger;
import services.video.FrameSampler.FrameSamplingException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes a video attachment to an interpretation strategy and returns the OpenAI-style content parts
 * to splice into the outgoing user message (JCLAW-224).
 *
 * <p>Capability-gated, mirroring transcription and image captioning: a chat model that supports video
 * natively ({@code supportsVideo}, from the provider's {@code input_modalities}) watches the clip
 * directly — we send the whole video as an OpenAI-compatible {@code video_url} part
 * ({@link VideoUrlAdapter}). Only when it can't does a configured dedicated video model
 * ({@code video.provider}/{@code video.model} in Settings → Video Interpretation) step in —
 * interpreting the clip in a separate call ({@link VideoInterpretationRouter}/{@link
 * VideoInterpretationClient}) whose prose is spliced back as a text part, so even a text-only chat
 * model gains video understanding. The interpretation strategy, in preference order:
 * <ol>
 *   <li>{@code supportsVideo} + clip within the inline size cap → {@link Strategy#NATIVE_VIDEO}
 *       ({@link VideoUrlAdapter}, the whole video as a {@code video_url}); an over-cap clip degrades
 *       to frames-as-images (a video model also has vision);</li>
 *   <li>else {@code supportsVision} → {@link Strategy#MULTI_IMAGE} ({@link MultiImageVideoAdapter},
 *       sampled frames as still images), falling back to the text summary if frame extraction fails
 *       (no ffmpeg / corrupt video);</li>
 *   <li>else {@link Strategy#TEXT_SUMMARY} ({@link TextSummaryVideoAdapter}, a captioned text
 *       summary).</li>
 * </ol>
 *
 * <p>Call this OUTSIDE any JPA transaction — NATIVE_VIDEO reads the file off disk, MULTI_IMAGE samples
 * frames (ffmpeg subprocess), and TEXT_SUMMARY additionally captions each frame and runs a summarize
 * call, none of which must hold a pooled DB connection. {@code VisionAudioAssembler.applyVideoForCapability}
 * is the orchestrator that runs this in its no-Tx phase and splices the result.
 */
public final class VideoUnderstandingDispatcher {

    private VideoUnderstandingDispatcher() {}

    /** The interpretation strategy chosen for a video, by the target model's capabilities. */
    public enum Strategy { NATIVE_VIDEO, MULTI_IMAGE, TEXT_SUMMARY }

    private static final String EVENT_CATEGORY = "video";

    /**
     * Lead-in for a dedicated-model interpretation spliced into the (often text-only) chat model's turn.
     * A bare "Video interpretation:" prefix read as user-pasted content, so a pedantic text model
     * disclaimed "I can't see videos" instead of relaying it. This mirrors the image-caption framing
     * ("auto-generated — you cannot ... directly") and adds an explicit do-not-disclaim steer, so the
     * chat model answers from the description as its own observation of the user's attached video.
     */
    static final String DEDICATED_INTERPRETATION_PREFIX =
            "[Video description (auto-generated): you cannot watch video directly, so a vision model "
            + "analyzed the video the user attached and produced the description below. Treat it as your "
            + "own observation of the video and answer the user from it — do not tell the user you cannot "
            + "see or process videos.]\n\n";

    /** Pick the strategy and produce the content parts for the given video attachment + agent. */
    public static List<Map<String, Object>> dispatch(MessageAttachment video, Agent agent) {
        if (video == null || !video.isVideo()) {
            throw new VideoAdapterException("not a video attachment");
        }
        var supportsVideo = AgentService.supportsVideo(agent);
        var supportsVision = AgentService.supportsVision(agent);

        // Rule 1 — the chat model the user picked is video-capable, so IT interprets the video. Send
        // the whole clip as a video_url part (the format video-capable models accept; validated live),
        // when it's small enough to inline. An over-cap clip would blow past provider body limits, so
        // it degrades to frames-as-images — a video-capable model also has vision, so it reads them.
        if (supportsVideo) {
            return VideoUrlAdapter.isWithinInlineCap(video)
                    ? nativeVideo(video)
                    : multiImageOrTextSummary(video, agent);
        }

        // Rule 2 — the chat model can't do video → use the dedicated video model from Settings →
        // Video Interpretation (video.provider/video.model) if one is configured. It interprets the
        // clip in a separate call and we splice the prose back as a text part, so even a text-only
        // chat model gains video understanding. On any failure (oversize, config, HTTP) fall through
        // to what the chat model itself can do.
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
        var part = new LinkedHashMap<String, Object>();
        part.put("type", "text");
        part.put("text", DEDICATED_INTERPRETATION_PREFIX + text);
        return List.of(part);
    }

    /**
     * The strategy the agent's chat model would use — {@code supportsVideo} ⇒ NATIVE_VIDEO (the whole
     * clip as a {@code video_url}), else {@code supportsVision} ⇒ MULTI_IMAGE, else TEXT_SUMMARY.
     * Exposed for the Settings read-only "which strategy would my model use" display (JCLAW-223) and
     * the routing functional tests. Does not model the inline size cap (an over-cap clip on a
     * video-capable model degrades to MULTI_IMAGE at dispatch time).
     */
    public static Strategy strategyFor(Agent agent) {
        if (AgentService.supportsVideo(agent)) return Strategy.NATIVE_VIDEO;
        if (AgentService.supportsVision(agent)) return Strategy.MULTI_IMAGE;
        return Strategy.TEXT_SUMMARY;
    }

    private static List<Map<String, Object>> nativeVideo(MessageAttachment video) {
        EventLogger.info(EVENT_CATEGORY, "native-video: video_url whole clip (%d bytes), attachment=%s"
                .formatted(video.sizeBytes, video.uuid));
        return List.of(VideoUrlAdapter.contentPart(video));
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
