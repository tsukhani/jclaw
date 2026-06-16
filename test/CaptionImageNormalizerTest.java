import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.caption.CaptionImageNormalizer;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * Caption images are transcoded to PNG when their format isn't universally model-decodable — the
 * concrete trigger is local Ollama rejecting WebP ("Failed to load image"). Verifies WebP → PNG
 * (proving the TwelveMonkeys imageio-webp plugin is on the classpath and registered), JPEG/PNG
 * pass-through, and graceful pass-through for a format ImageIO can't decode.
 */
class CaptionImageNormalizerTest extends UnitTest {

    /** A real 16x16 WebP, base64 — decoded via the imageio-webp ImageReader. */
    private static final String TINY_WEBP =
            "UklGRngBAABXRUJQVlA4WAoAAAAQAAAADwAADwAAQUxQSLYAAAABgGPb2rHn/lZcsbJtZwq2bat0Uhml7eo"
            + "fQlamYKezjft7DBExAQCgTEkUAwkbrjCcPFVYgsjWznojjnmSFrsOC19bIwDyl3pgqnLujR/TNibkUn/dSk8"
            + "ECNb5/08euQKQ1G40iy/4T3JZACDmmurwH+oXIn05d4A8OaTBo0my2FZ9T5NTgEGTrvzgOLrzaeTtKg4wC1"
            + "zafP4jv3af9rdrBYBT3PZeZnlDsCR3odJeAEDqWSeEcVZQOCCcAAAA8AIAnQEqEAAQAAIANCWwAnQ4gDeRyV"
            + "HeMVkaTdrnvAAA+ehUtXMb/pV95kq+0ggpoNJp666ZmGkEld9YCikXSwtmER1qVhbv9o2zc122ul0h61jEpe"
            + "6bfr1xUGYEnfYVvJ36ZMgLfJclzwDjXBtNBKgN6KmQ2D/97u7973vuAp/rG1eFXzn/KzcaC4M8eYyXk82NwQ"
            + "3ECyNyXEAA";

    @Test
    void transcodesWebpToPng() throws Exception {
        var out = CaptionImageNormalizer.toModelSafeDataUrl("data:image/webp;base64," + TINY_WEBP);
        assertTrue(out.startsWith("data:image/png;base64,"),
                "WebP must be transcoded to PNG, got: " + out.substring(0, Math.min(40, out.length())));
        var bytes = Base64.getDecoder().decode(out.substring(out.indexOf(',') + 1));
        var img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(img, "transcoded output must be a readable PNG");
        assertEquals(16, img.getWidth());
        assertEquals(16, img.getHeight());
    }

    @Test
    void passesThroughModelSafeFormats() {
        var png = "data:image/png;base64,iVBORw0KGgo=";
        assertEquals(png, CaptionImageNormalizer.toModelSafeDataUrl(png), "PNG is already model-safe");
        var jpg = "data:image/jpeg;base64,/9j/4AAQ";
        assertEquals(jpg, CaptionImageNormalizer.toModelSafeDataUrl(jpg), "JPEG is already model-safe");
    }

    @Test
    void leavesUndecodableFormatUnchanged() {
        // ImageIO has no AVIF reader → null BufferedImage → graceful pass-through, no exception.
        var avif = "data:image/avif;base64," + Base64.getEncoder().encodeToString(new byte[]{0, 0, 0, 32});
        assertEquals(avif, CaptionImageNormalizer.toModelSafeDataUrl(avif));
    }
}
