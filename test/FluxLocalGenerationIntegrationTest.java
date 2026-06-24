import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.imagegen.FluxLocalImageClient;
import services.imagegen.FluxModelManager;
import services.imagegen.FluxSidecarProbe;
import services.imagegen.ImageGenerationService;
import services.imagegen.LocalFluxSidecarManager;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end smoke for the local Flux 2 Klein sidecar (JCLAW-226). SKIPS unless
 * {@code uv} is on PATH and the model weights are already present locally — the
 * real generation is too heavy (GPU + ~13 GB weights) for CI, so this only
 * exercises the full spawn → load → generate path on a developer machine that
 * has already set it up (the protocol layer itself is unit-tested with a mocked
 * sidecar in {@code ImageGenerationClientTest}).
 */
class FluxLocalGenerationIntegrationTest extends UnitTest {

    @Test
    void generatesAnImageWhenSidecarAndWeightsArePresent() {
        var model = FluxModelManager.configuredModel();
        assumeTrue(FluxSidecarProbe.isAvailable(),
                "uv not on PATH — skipping local Flux integration test");
        assumeTrue(FluxModelManager.availableLocally(model),
                "Flux weights not downloaded — skipping local Flux integration test");
        try {
            ImageGenerationService client = new FluxLocalImageClient();
            var image = client.generate("a small solid red square", null, 512, 512);
            assertNotNull(image, "generate must return an image");
            assertTrue(image.bytes().length > 0, "generated image must have bytes");
            assertTrue(image.mimeType().startsWith("image/"),
                    "mime type should be image/*, was " + image.mimeType());
            assertTrue(image.generatedBy().startsWith("flux-local:"), image.generatedBy());
        } finally {
            // Always release the daemon (and its GPU memory) when the test finishes.
            LocalFluxSidecarManager.stop();
        }
    }
}
