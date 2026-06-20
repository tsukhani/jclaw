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
 *       namespace (Qwen-VL via OpenRouter/DashScope; OpenAI {@code video} array wire shape).</li>
 *   <li>{@code vllm} → {@link VideoInterpretationClient} on the {@code provider.vllm.*} namespace
 *       (a self-hosted vLLM serving a Qwen-VL model; base64 {@code video/jpeg} wire shape).</li>
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
            case "openrouter" -> Optional.of(new VideoInterpretationClient("openrouter"));
            case "vllm" -> Optional.of(new VideoInterpretationClient("vllm"));
            default -> Optional.empty();
        };
    }
}
