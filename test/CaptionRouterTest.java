import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.caption.CaptionRouter;
import services.caption.OpenAiCompatibleImageCaptionClient;
import services.caption.OpenAiImageCaptionClient;
import services.caption.OpenRouterImageCaptionClient;

/**
 * JCLAW-214: the {@code caption.provider} config key selects the {@link CaptionRouter} backend —
 * single-select, mirroring {@code TranscriptionRouter}. The operator picks exactly one backend in
 * Settings → Image Captioning; resolved per call so a change applies without a restart.
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
        ConfigService.set("caption.provider", "openrouter");
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof OpenRouterImageCaptionClient,
                "openrouter → OpenRouterImageCaptionClient");

        ConfigService.set("caption.provider", "openai");
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof OpenAiImageCaptionClient,
                "openai → OpenAiImageCaptionClient");

        ConfigService.set("caption.provider", "ollama-local");
        assertEquals(OpenAiCompatibleImageCaptionClient.class,
                CaptionRouter.configuredService().orElseThrow().getClass(),
                "ollama-local → OpenAiCompatibleImageCaptionClient bound to provider.ollama-local.*");
    }

    @Test
    void emptyForUnknownProvider() {
        ConfigService.set("caption.provider", "bogus");
        assertTrue(CaptionRouter.configuredService().isEmpty(), "unrecognized provider → empty");
    }
}
