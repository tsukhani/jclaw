package services.video;

/**
 * Typed failure from a video-interpretation adapter (JCLAW-220/221/222) — e.g.
 * an empty frame list or a failed text-summary caption/summarize call. Distinct from
 * {@link services.video.FrameSampler.FrameSamplingException} (the ffmpeg layer) so the
 * dispatcher (JCLAW-224) can tell "couldn't extract frames" from "couldn't wrap them".
 */
public class VideoAdapterException extends RuntimeException {
    public VideoAdapterException(String message) { super(message); }
    public VideoAdapterException(String message, Throwable cause) { super(message, cause); }
}
