import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.video.VideoInterpretationClient;
import services.video.VideoInterpretationRouter;

/**
 * The {@code video.provider} config key selects the dedicated video-model backend — single-select,
 * mirroring {@code CaptionRouter}. Presence is the master enable; resolved per call so a Settings
 * change applies without a restart. Empty/unrecognised → the dispatcher falls back to the chat model.
 */
class VideoInterpretationRouterTest extends UnitTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        // Config is cached: deleteDatabase clears the row but not the cache, so a video.provider set
        // by a sibling test would leak into emptyWhenProviderUnset (order-dependent). Evict to isolate.
        ConfigService.clearCache();
    }

    @Test
    void emptyWhenProviderUnset() {
        assertTrue(VideoInterpretationRouter.configuredService().isEmpty(),
                "no video.provider → no dedicated model (fall back to the chat model)");
    }

    @Test
    void routesOpenrouterAndVllm() {
        ConfigService.set("video.provider", "openrouter");
        assertTrue(VideoInterpretationRouter.configuredService().isPresent(),
                "openrouter → a VideoInterpretationClient");

        ConfigService.set("video.provider", "vllm");
        assertTrue(VideoInterpretationRouter.configuredService().isPresent(),
                "vllm → a VideoInterpretationClient");
    }

    @Test
    void wireModeIsNativeForOpenrouterAndMultiImageForVllm() {
        ConfigService.set("video.provider", "openrouter");
        assertEquals(VideoInterpretationClient.WireMode.NATIVE_VIDEO,
                VideoInterpretationRouter.configuredService().orElseThrow().wireMode(),
                "openrouter → native video_url");

        ConfigService.set("video.provider", "vllm");
        assertEquals(VideoInterpretationClient.WireMode.MULTI_IMAGE,
                VideoInterpretationRouter.configuredService().orElseThrow().wireMode(),
                "vllm → multi-image frames");
    }

    @Test
    void wireModeForMapsProvidersToModes() {
        assertEquals(VideoInterpretationClient.WireMode.NATIVE_VIDEO,
                VideoInterpretationRouter.wireModeFor("openrouter").orElseThrow());
        assertEquals(VideoInterpretationClient.WireMode.MULTI_IMAGE,
                VideoInterpretationRouter.wireModeFor("vllm").orElseThrow());
        assertEquals(VideoInterpretationClient.WireMode.MULTI_IMAGE,
                VideoInterpretationRouter.wireModeFor("ollama-local").orElseThrow());
        assertEquals(VideoInterpretationClient.WireMode.MULTI_IMAGE,
                VideoInterpretationRouter.wireModeFor("ollama-cloud").orElseThrow());
        assertTrue(VideoInterpretationRouter.wireModeFor("bogus").isEmpty(),
                "unrecognized provider → no wire mode");
        assertTrue(VideoInterpretationRouter.wireModeFor(null).isEmpty(),
                "null provider → no wire mode");
    }

    @Test
    void routesOllamaProvidersAsMultiImage() {
        ConfigService.set("video.provider", "ollama-local");
        assertEquals(VideoInterpretationClient.WireMode.MULTI_IMAGE,
                VideoInterpretationRouter.configuredService().orElseThrow().wireMode(),
                "ollama-local → multi-image frames");

        ConfigService.set("video.provider", "ollama-cloud");
        assertEquals(VideoInterpretationClient.WireMode.MULTI_IMAGE,
                VideoInterpretationRouter.configuredService().orElseThrow().wireMode(),
                "ollama-cloud → multi-image frames");
    }

    @Test
    void emptyForUnknownProvider() {
        ConfigService.set("video.provider", "bogus");
        assertTrue(VideoInterpretationRouter.configuredService().isEmpty(),
                "unrecognized provider → empty");
    }
}
