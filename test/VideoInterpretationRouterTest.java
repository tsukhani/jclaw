import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
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
    void emptyForUnknownProvider() {
        ConfigService.set("video.provider", "bogus");
        assertTrue(VideoInterpretationRouter.configuredService().isEmpty(),
                "unrecognized provider → empty");
    }
}
