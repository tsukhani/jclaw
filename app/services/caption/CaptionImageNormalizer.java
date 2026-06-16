package services.caption;

import play.Logger;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Set;

/**
 * Normalizes an image {@code data:} URL to a format every vision model can decode before it's sent
 * to a caption backend. Caption models differ in format support — notably local Ollama vision models
 * reject WebP with {@code "Failed to load image or audio file"} (HTTP 400) — so any image that isn't
 * already JPEG/PNG is transcoded to PNG.
 *
 * <p>WebP decoding comes from the TwelveMonkeys {@code imageio-webp} plugin (auto-registered on the
 * classpath via {@code ServiceLoader}); core ImageIO handles GIF/BMP. A format ImageIO still can't
 * read even with the plugin (AVIF/HEIC) yields a {@code null} {@code BufferedImage} — we then return
 * the input unchanged so the caller degrades gracefully (the caption fails with the existing warn +
 * "description unavailable" fallback) rather than throwing.
 */
public final class CaptionImageNormalizer {

    private CaptionImageNormalizer() {}

    /** Formats every vision-model image loader accepts — passed through untouched. */
    private static final Set<String> MODEL_SAFE = Set.of("image/jpeg", "image/png");

    /**
     * Return a {@code data:} URL whose image is in a model-decodable format (PNG), or the input
     * unchanged when it's already safe, isn't an image, or can't be decoded.
     */
    public static String toModelSafeDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return dataUrl;
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return dataUrl;

        var header = dataUrl.substring(5, comma);                       // e.g. "image/webp;base64"
        int semi = header.indexOf(';');
        var mime = semi < 0 ? header : header.substring(0, semi);
        if (!mime.startsWith("image/") || MODEL_SAFE.contains(mime)) return dataUrl;

        try {
            var bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            var img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return dataUrl;                            // undecodable (e.g. AVIF/HEIC)
            var out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            Logger.warn("caption image normalize failed (%s) — sending original: %s", mime, e.getMessage());
            return dataUrl;
        }
    }
}
