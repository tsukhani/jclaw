package services.imagegen;

import services.ConfigService;

import java.util.Optional;

/**
 * Picks the {@link ImageGenerationService} matching the operator's {@code imagegen.provider}
 * selection (JCLAW-225/229), single-select like {@code services.caption.CaptionRouter}. The operator
 * chooses exactly one backend in Settings → Image Generation; a non-empty {@code imagegen.provider}
 * is the master enable.
 *
 * <ul>
 *   <li>{@code openai} → {@link OpenAiImageGenerationClient} ({@code gpt-image-1})</li>
 *   <li>{@code bfl} → {@link BflImageGenerationClient} (Black Forest Labs Flux)</li>
 *   <li>{@code replicate} → {@link ReplicateImageGenerationClient} (hosted models, e.g. Flux)</li>
 * </ul>
 *
 * <p>Returns {@link Optional#empty()} when unset/unrecognised — the {@code generate_image} tool then
 * reports "image generation is not configured" to the agent rather than attempting a call. Resolved
 * per call so a Settings change takes effect on the next generation without a restart. (OpenRouter is
 * not wired here yet: its image surface uses a different chat-completions/modalities shape — JCLAW-225
 * shipped two clients, which the AC permits.)
 */
public final class ImageGenerationRouter {

    private ImageGenerationRouter() {}

    public static Optional<ImageGenerationService> configuredService() {
        var provider = ConfigService.get("imagegen.provider");
        if (provider == null || provider.isBlank()) return Optional.empty();
        return switch (provider) {
            case "openai" -> Optional.of(new OpenAiImageGenerationClient());
            case "bfl" -> Optional.of(new BflImageGenerationClient());
            case "replicate" -> Optional.of(new ReplicateImageGenerationClient());
            default -> Optional.empty();
        };
    }
}
