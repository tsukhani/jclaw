package services.caption;

import services.ConfigService;

import java.util.Optional;

/**
 * Picks the {@link ImageCaptionService} matching the operator's {@code caption.provider} selection
 * (JCLAW-214), single-select like {@code TranscriptionRouter}. The operator chooses exactly one
 * backend in Settings → Image Captioning; presence of a non-empty value is the master enable.
 *
 * <ul>
 *   <li>{@code openrouter} → {@link OpenRouterImageCaptionClient}</li>
 *   <li>{@code openai} → {@link OpenAiImageCaptionClient}</li>
 *   <li>{@code ollama-local} → {@link OpenAiCompatibleImageCaptionClient} bound to the
 *       {@code provider.ollama-local.*} namespace (local Ollama at {@code localhost:11434/v1}).
 *       Caption a non-vision turn with a vision model the operator runs in Ollama. The model is
 *       required via {@code caption.model} (no hardcoded default — there's no universally-pulled
 *       Ollama vision model, so a blank model is a clean no-op rather than a guess).</li>
 * </ul>
 *
 * <p>Returns {@link Optional#empty()} when unset/unrecognised — callers then emit the "description
 * unavailable" note rather than blocking the turn. Resolved per call so a Settings change takes
 * effect on the next image without a restart.
 */
public final class CaptionRouter {

    private CaptionRouter() {}

    public static Optional<ImageCaptionService> configuredService() {
        var provider = ConfigService.get("caption.provider");
        if (provider == null || provider.isBlank()) return Optional.empty();
        return switch (provider) {
            case "openrouter" -> Optional.of(new OpenRouterImageCaptionClient());
            case "openai" -> Optional.of(new OpenAiImageCaptionClient());
            case "ollama-local" -> Optional.of(new OpenAiCompatibleImageCaptionClient("ollama-local", null));
            default -> Optional.empty();
        };
    }
}
