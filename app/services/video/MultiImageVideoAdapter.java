package services.video;

import services.video.FrameSampler.Frame;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Multi-image video adapter (JCLAW-221): for models that advertise {@code supportsVision} but not
 * {@code supportsVideo}, sends the sampled frames as a sequence of OpenAI-style
 * {@code image_url} content parts preceded by a text part that frames them as evenly-spaced
 * stills from one video (timestamps + duration). This is the OpenClaw approach — a vision
 * model reasons over the timeline of stills rather than receiving a native video stream.
 *
 * <p>This is the degraded fallback for a video-capable model whose clip is too large to inline as a
 * {@code video_url} ({@link VideoUrlAdapter}), and the primary path for a vision-but-not-video model.
 * Frames are {@link FrameSampler} output at JPEG quality ~90 (its {@code -q:v}).
 *
 * <p>The number of frames is decided upstream by {@link FrameSampler} (duration-aware,
 * ceilinged by {@code video.sampleFrames}); the dispatcher (JCLAW-224) logs the count used.
 */
public final class MultiImageVideoAdapter {

    private MultiImageVideoAdapter() {}

    /** Build the leading text part plus one {@code image_url} part per frame. */
    public static List<Map<String, Object>> contentParts(List<Frame> frames, double durationSeconds) {
        if (frames == null || frames.isEmpty()) {
            throw new VideoAdapterException("no frames to build a multi-image part");
        }
        var parts = new ArrayList<Map<String, Object>>(frames.size() + 1);
        parts.add(Map.of("type", "text", "text", header(frames, durationSeconds)));
        for (var f : frames) {
            var dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(f.jpeg());
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        return parts;
    }

    static String header(List<Frame> frames, double durationSeconds) {
        var timestamps = frames.stream()
                .map(f -> VideoTimecode.format(f.timestampSeconds()))
                .collect(Collectors.joining(", "));
        return ("The following %d images are evenly-spaced frames sampled from a single video of "
                + "duration %s. They are in chronological order at timestamps [%s]. Treat them as a "
                + "temporal sequence and describe what happens across the video, not each image in "
                + "isolation.")
                .formatted(frames.size(), VideoTimecode.format(durationSeconds), timestamps);
    }
}
