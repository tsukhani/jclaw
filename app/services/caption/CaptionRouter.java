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
 *   <li>{@code vlm-local} → {@link LocalImageCaptioner} (Self-Hosted Image Captioner, ViT-GPT2)</li>
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
            case "vlm-local" -> Optional.of(new LocalImageCaptioner());
            default -> Optional.empty();
        };
    }
}
