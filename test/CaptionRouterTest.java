import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.caption.CaptionRouter;
import services.caption.LocalImageCaptioner;
import services.caption.OpenAiImageCaptionClient;
import services.caption.OpenRouterImageCaptionClient;
import services.caption.VlmModel;
import services.caption.VlmModelManager;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCLAW-214: {@link CaptionRouter} resolves the non-vision image fallback with cloud-preferred,
 * local-fallback precedence — cloud (when {@code caption.cloud.provider} is a recognised provider)
 * → local ViT-GPT2 (when downloaded) → none. Resolved per call so a Settings change applies without
 * a restart.
 */
class CaptionRouterTest extends UnitTest {

    private Path modelRoot;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        VlmModelManager.resetForTest();
        // Redirect the model store to an empty temp dir so "local available" is controllable and the
        // real data/vlm-models/ is never consulted.
        modelRoot = Files.createTempDirectory("caption-router-test");
        VlmModelManager.setRootForTest(modelRoot);
    }

    @AfterEach
    void tearDown() {
        VlmModelManager.resetForTest();
    }

    @Test
    void emptyWhenNothingConfigured() {
        assertTrue(CaptionRouter.configuredService().isEmpty(),
                "no cloud provider and no local model → no backend (skip the fallback)");
    }

    @Test
    void cloudProviderRoutesToItsClient() {
        ConfigService.set("caption.cloud.provider", "openai");
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof OpenAiImageCaptionClient,
                "openai → OpenAiImageCaptionClient");

        ConfigService.set("caption.cloud.provider", "openrouter");
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof OpenRouterImageCaptionClient,
                "openrouter → OpenRouterImageCaptionClient");
    }

    @Test
    void fallsBackToLocalWhenCloudUnsetAndModelDownloaded() throws Exception {
        stageLocalModel();
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof LocalImageCaptioner,
                "cloud unset + local model present → LocalImageCaptioner");
    }

    @Test
    void cloudTakesPrecedenceOverLocal() throws Exception {
        stageLocalModel();
        ConfigService.set("caption.cloud.provider", "openai");
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof OpenAiImageCaptionClient,
                "cloud configured wins even when the local model is also present");
    }

    @Test
    void unknownCloudValueFallsBackToLocal() throws Exception {
        stageLocalModel();
        ConfigService.set("caption.cloud.provider", "bogus");
        assertTrue(CaptionRouter.configuredService().orElseThrow() instanceof LocalImageCaptioner,
                "unrecognised cloud value is treated as unset → local fallback");
    }

    @Test
    void emptyWhenCloudUnsetAndModelNotDownloaded() {
        // modelRoot is empty (no manifest files staged) → not available.
        assertTrue(CaptionRouter.configuredService().isEmpty(),
                "cloud unset + local model absent → empty");
    }

    /** Create the DEFAULT model's manifest files under the test root so availableLocally() is true. */
    private void stageLocalModel() throws Exception {
        var dir = modelRoot.resolve(VlmModel.DEFAULT.id());
        Files.createDirectories(dir);
        for (var f : VlmModel.DEFAULT.files()) {
            Files.write(dir.resolve(f.localName()), new byte[]{0});
        }
    }
}
