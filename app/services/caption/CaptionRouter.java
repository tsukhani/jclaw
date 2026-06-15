package services.caption;

import services.ConfigService;

import java.util.Optional;

/**
 * Picks the {@link ImageCaptionService} for the non-vision-model image fallback, using the operator's
 * two-tier Image Captioning Settings (JCLAW-214): a <b>cloud</b> option (preferred) and a <b>local</b>
 * option (offline fallback). Resolution order:
 *
 * <ol>
 *   <li><b>Cloud</b> — when {@code caption.cloud.provider} names a recognised provider
 *       ({@code openai} → {@link OpenAiImageCaptionClient}, {@code openrouter} →
 *       {@link OpenRouterImageCaptionClient}).</li>
 *   <li><b>Local</b> — otherwise, when the local ViT-GPT2 model is downloaded
 *       ({@link VlmModelManager#availableLocally}) → {@link LocalImageCaptioner}.</li>
 *   <li><b>None</b> — {@link Optional#empty()} when neither is configured; callers then emit the
 *       "description unavailable" note rather than blocking the turn.</li>
 * </ol>
 *
 * <p>"If the cloud model is not set, use the local model." Resolved per call so a Settings change
 * takes effect on the next image without a restart. Mirrors {@code TranscriptionRouter}'s role, but
 * with cloud-preferred precedence rather than a single-select provider key.
 */
public final class CaptionRouter {

    private CaptionRouter() {}

    public static Optional<ImageCaptionService> configuredService() {
        var cloud = ConfigService.get("caption.cloud.provider");
        if ("openai".equals(cloud)) return Optional.of(new OpenAiImageCaptionClient());
        if ("openrouter".equals(cloud)) return Optional.of(new OpenRouterImageCaptionClient());
        // Cloud not set (or unrecognised) → fall back to the local model when it's downloaded.
        if (VlmModelManager.availableLocally(VlmModel.DEFAULT)) return Optional.of(new LocalImageCaptioner());
        return Optional.empty();
    }
}
