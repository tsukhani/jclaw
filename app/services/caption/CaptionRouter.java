package services.caption;

import services.ConfigService;

import java.util.Optional;

/**
 * Picks the {@link ImageCaptionService} implementation matching the operator's
 * {@code caption.provider} config selection (JCLAW-212), mirroring {@code TranscriptionRouter}.
 * Returns {@link Optional#empty()} when no provider is configured or the value is unrecognized —
 * callers treat that as "no captioning backend, skip the fallback."
 *
 * <p>Recognised values match the Image Interpretation Settings section (JCLAW-214):
 * <ul>
 *   <li>{@code vlm-local} → {@link LocalImageCaptioner} (in-JVM ONNX)</li>
 *   <li>{@code openai} → {@link OpenAiImageCaptionClient}</li>
 *   <li>{@code openrouter} → {@link OpenRouterImageCaptionClient}</li>
 * </ul>
 *
 * <p>Resolved per call so a Settings change takes effect on the next image without a restart.
 */
public final class CaptionRouter {

    private CaptionRouter() {}

    public static Optional<ImageCaptionService> configuredService() {
        var provider = ConfigService.get("caption.provider");
        if (provider == null || provider.isBlank()) return Optional.empty();
        return switch (provider) {
            case "vlm-local" -> Optional.of(new LocalImageCaptioner());
            case "openai" -> Optional.of(new OpenAiImageCaptionClient());
            case "openrouter" -> Optional.of(new OpenRouterImageCaptionClient());
            default -> Optional.empty();
        };
    }
}
