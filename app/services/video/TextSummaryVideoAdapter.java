package services.video;

import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;
import models.MessageAttachment;
import services.ConfigService;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;
import services.caption.CaptionRouter;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Text-summary video adapter (JCLAW-222): for text-only models, samples N keyframes, captions each
 * via the image-interpretation epic's {@link services.caption.ImageCaptionService}, and assembles a
 * temporal text summary — a header (duration + frame count) followed by {@code [hh:mm:ss] caption}
 * lines and an optional one-sentence overview. The summary is persisted on
 * {@link MessageAttachment#videoSummary} so replays reuse it instead of re-captioning.
 *
 * <p><b>Cost control.</b> Each frame caption is a full LLM round-trip, so this adapter caps the
 * frame count below the duration-aware figure: effective N = {@code min(frameCountFor(duration),
 * MAX_FRAMES)}. That keeps caption-call cost bounded even when an operator raises
 * {@code video.sampleFrames} to densify the native-video / multi-image strategies.
 *
 * <p><b>Resilience.</b> A single frame's caption failing does not fail the whole summary — the line
 * is logged and replaced with a placeholder. The overview line is best-effort: any failure yields a
 * summary without it.
 *
 * <p>Follows the two-phase transaction pattern of {@code VisionAudioAssembler.captionOne}: the slow
 * caption/summarize model calls run with NO JPA transaction held; only the short cache-read and the
 * final persist touch the DB.
 */
public final class TextSummaryVideoAdapter {

    private TextSummaryVideoAdapter() {}

    static final String CFG_MAX_FRAMES = "video.textSummaryMaxFrames";
    static final int DEFAULT_MAX_FRAMES = 8;
    static final String CAPTION_UNAVAILABLE = "(frame caption unavailable)";

    /** Build (or reuse) the temporal-summary text content part for a text-only model. */
    public static List<Map<String, Object>> contentParts(MessageAttachment video, Agent agent) {
        if (video == null) throw new VideoAdapterException("attachment is null");
        return List.of(Map.of("type", "text", "text", ensureSummary(video, agent)));
    }

    /** Reuse the persisted summary if present (cache hit), else build it and persist for next time. */
    static String ensureSummary(MessageAttachment video, Agent agent) {
        final Long attId = video.id;
        String cached = Tx.run(() -> {
            var att = (MessageAttachment) MessageAttachment.findById(attId);
            return (att != null && att.videoSummary != null && !att.videoSummary.isBlank())
                    ? att.videoSummary : null;
        });
        if (cached != null) return cached;

        var summary = buildSummary(video, agent);

        Tx.run(() -> {
            var att = (MessageAttachment) MessageAttachment.findById(attId);
            if (att != null) {
                att.videoSummary = summary;
                att.save();
            }
            return null;
        });
        return summary;
    }

    /** The effective frame count: the duration-aware count capped at the {@link #MAX_FRAMES} limit. */
    public static int effectiveFrameCount(double durationSeconds) {
        return Math.min(FrameSampler.frameCountFor(durationSeconds), maxFrames());
    }

    static int maxFrames() {
        return Math.max(FrameSampler.MIN_FRAMES,
                ConfigService.getInt(CFG_MAX_FRAMES, DEFAULT_MAX_FRAMES));
    }

    /** Sample (capped), caption each frame, assemble the [hh:mm:ss] timeline + a one-sentence overview. */
    static String buildSummary(MessageAttachment video, Agent agent) {
        var sampled = FrameSampler.sample(video, maxFrames()); // effective N = min(frameCountFor, cap)
        var frames = sampled.frames();

        var svc = CaptionRouter.configuredService()
                .orElseThrow(() -> new VideoAdapterException(
                        "no caption provider configured (caption.provider) for the text-summary video fallback"));

        var lines = new ArrayList<String>(frames.size());
        var realCaptions = new ArrayList<String>(frames.size());
        for (var f : frames) {
            String caption;
            try {
                var dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(f.jpeg());
                caption = svc.captionDataUrl(dataUrl);
                if (caption == null || caption.isBlank()) caption = CAPTION_UNAVAILABLE;
            } catch (RuntimeException e) {
                EventLogger.warn("video", "frame caption failed at %s: %s"
                        .formatted(VideoTimecode.format(f.timestampSeconds()), e.getMessage()));
                caption = CAPTION_UNAVAILABLE;
            }
            caption = caption.trim();
            lines.add("[" + VideoTimecode.format(f.timestampSeconds()) + "] " + caption);
            if (!CAPTION_UNAVAILABLE.equals(caption)) realCaptions.add(caption);
        }

        var header = "Video summary (duration %s, %d frames sampled):"
                .formatted(VideoTimecode.format(sampled.durationSeconds()), frames.size());
        var body = header + "\n" + String.join("\n", lines);

        var overview = overviewLine(realCaptions, agent);
        return overview == null ? body : body + "\n\nOverview: " + overview;
    }

    /** One-shot single-sentence overview via the agent's current chat model; null on any failure. */
    static String overviewLine(List<String> captions, Agent agent) {
        if (captions.isEmpty() || agent == null || agent.modelProvider == null) return null;
        try {
            var provider = ProviderRegistry.get(agent.modelProvider);
            if (provider == null) return null;
            var prompt = "These are timestamped descriptions of frames from one video, in order:\n"
                    + String.join("\n", captions)
                    + "\n\nWrite a single concise sentence summarizing what happens in the video. "
                    + "Reply with only that sentence.";
            var resp = provider.chat(agent.modelId,
                    List.of(ChatMessage.user(prompt)), List.of(), 120, null, "video-summary");
            var text = SessionCompactor.firstChoiceText(resp);
            return (text == null || text.isBlank()) ? null : text.trim();
        } catch (RuntimeException e) {
            EventLogger.warn("video", "overview summarization failed: " + e.getMessage());
            return null;
        }
    }
}
