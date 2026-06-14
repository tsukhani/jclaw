import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.caption.CaptionRouter;
import services.caption.LocalImageCaptioner;
import services.caption.OpenAiImageCaptionClient;
import services.caption.OpenRouterImageCaptionClient;

/**
 * JCLAW-212: the {@code caption.provider} config key selects the right {@link CaptionRouter} backend,
 * resolved per call so a Settings change takes effect without a restart (mirrors TranscriptionRouter).
 */
class CaptionRouterTest extends UnitTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
    }

    @Test
    void emptyWhenProviderUnset() {
        assertTrue(CaptionRouter.configuredService().isEmpty(),
                "no caption.provider → no backend (skip the fallback)");
    }

    @Test
    void routesEachProviderToItsBackend() {
        ConfigService.set("caption.provider", "vlm-local");
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof LocalImageCaptioner,
                "vlm-local → LocalImageCaptioner");

        ConfigService.set("caption.provider", "openai");
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof OpenAiImageCaptionClient,
                "openai → OpenAiImageCaptionClient");

        ConfigService.set("caption.provider", "openrouter");
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof OpenRouterImageCaptionClient,
                "openrouter → OpenRouterImageCaptionClient");
    }

    @Test
    void emptyForUnknownProvider() {
        ConfigService.set("caption.provider", "bogus");
        assertTrue(CaptionRouter.configuredService().isEmpty(), "unrecognized provider → empty");
    }
}
