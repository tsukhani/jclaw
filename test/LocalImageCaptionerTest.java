import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.caption.LocalImageCaptioner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JCLAW-213 integration test for the in-JVM image captioner. Exercises the real
 * {@link LocalImageCaptioner} (DJL + ONNX Runtime, ViT-GPT2) end-to-end against locally-present
 * model files; <b>skips</b> when the ONNX graphs / sample image aren't on disk (so CI without the
 * ~245MB weights stays green) — the skip-unless-present pattern the story specifies.
 *
 * <p>Locally the weights live in the PoC dir {@code spike/image-caption-poc/} (gitignored). When
 * present this test is the production-integration proof: it confirms DJL resolves the ONNX Runtime
 * engine under Play's classloader and the captioner returns a sensible caption in-process.
 */
class LocalImageCaptionerTest extends UnitTest {

    @Test
    void captionsASampleImageInJvm() throws Exception {
        Path spike = Paths.get(System.getProperty("user.dir"), "spike", "image-caption-poc");
        Path models = spike.resolve("models");
        Path image = spike.resolve("dog_bike_car.jpg");
        Assumptions.assumeTrue(
                Files.isRegularFile(models.resolve("encoder.onnx"))
                        && Files.isRegularFile(models.resolve("decoder.onnx"))
                        && Files.isRegularFile(models.resolve("tokenizer.json"))
                        && Files.isRegularFile(image),
                "VLM model files / sample image not present — skipping in-JVM caption integration test");

        LocalImageCaptioner.setModelDir(models);
        String caption = new LocalImageCaptioner().captionImageBytes(Files.readAllBytes(image));

        assertNotNull(caption);
        assertFalse(caption.isBlank(), "captioner returned an empty caption");
        // dog_bike_car.jpg — ViT-GPT2 reliably mentions the dog.
        assertTrue(caption.toLowerCase().contains("dog"),
                "expected the caption to mention the dog, got: " + caption);
    }
}
