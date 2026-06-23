package services.video;

import services.ConfigService;

import java.util.Optional;

/**
 * Picks the dedicated video model when one is configured, mirroring {@link services.caption.CaptionRouter}.
 * Presence of a non-empty {@code video.provider} is the master enable for native video interpretation;
 * the operator chooses exactly one backend in Settings → Video Interpretation:
 *
 * <ul>
 *   <li>{@code openrouter} → {@link VideoInterpretationClient} on the {@code provider.openrouter.*}
 *       namespace in {@link VideoInterpretationClient.WireMode#NATIVE_VIDEO} mode (Qwen-VL via
 *       OpenRouter/DashScope, which the model watches as a {@code video_url}).</li>
 *   <li>{@code vllm} → {@link VideoInterpretationClient} on the {@code provider.vllm.*} namespace in
 *       {@link VideoInterpretationClient.WireMode#MULTI_IMAGE} mode (a self-hosted vLLM serving Qwen-VL,
 *       whose native {@code video_url} path is broken, so sampled frames are sent as {@code image_url}
 *       parts — preserving temporal understanding such as panning).</li>
 * </ul>
 *
 * <p>Returns {@link Optional#empty()} when unset/unrecognised — the dispatcher then falls back to
 * interpreting the video with the agent's own chat model. Resolved per call so a Settings change
 * takes effect on the next video without a restart.
 */
public final class VideoInterpretationRouter {

    private VideoInterpretationRouter() {}

    public static Optional<VideoInterpretationClient> configuredService() {
        var provider = ConfigService.get("video.provider");
        if (provider == null || provider.isBlank()) return Optional.empty();
        return switch (provider) {
            case "openrouter" -> Optional.of(
                    new VideoInterpretationClient("openrouter", VideoInterpretationClient.WireMode.NATIVE_VIDEO));
            case "vllm" -> Optional.of(
                    new VideoInterpretationClient("vllm", VideoInterpretationClient.WireMode.MULTI_IMAGE));
            default -> Optional.empty();
        };
    }
}
