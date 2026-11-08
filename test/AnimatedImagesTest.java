import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.AnimatedImages;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Real cwebp output of 24x16 stills: lossy (A, B), lossless (C), lossy with alpha (D) and a 20x16 lossy (E). */
class AnimatedImagesTest extends UnitTest {

    static final byte[] A = Base64.getDecoder().decode("UklGRnQAAABXRUJQVlA4IGgAAADQAwCdASoYABAAPtFUo0uoJKMhsAgBABoJbAC7AYqC37K15n3LHQAA9r7SjPIWPMZ8x2JtD2PcUN+2tt5owq98i5PrEmzBL37sL7HHmtsC7+YYxPOn5ul94cWAXSEv/RiJ9X5G23sAAA==");
    static final byte[] B = Base64.getDecoder().decode("UklGRnoAAABXRUJQVlA4IG4AAACQAwCdASoYABAAPtFUo0uoJKMhsAgBABoJbACdACHqTOiSU25QAP5PgyN8hY8xnzzYdDtXtOBndmmWdNaQPKiSs8nd8OvGDCdf9btt5A7XRvzDGJ50/N0vvDiwC6Ql/6MRPq/I3XY/IW65v2AAAA==");
    static final byte[] C = Base64.getDecoder().decode("UklGRjIAAABXRUJQVlA4TCUAAAAvF8ADAA/w5P9//P/jf/7jAQraSGEe8O8RJ3tJiOj/BFgr+vIMAA==");
    static final byte[] D = Base64.getDecoder().decode("UklGRmQAAABXRUJQVlA4WAoAAAAQAAAAFwAADwAAQUxQSBgAAAABDzD/ERGCINumJvN3PMARIvofwLoAAABWUDggJgAAAHACAJ0BKhgAEAAumWi0WiKoKCgIAJhLSAAHsoAA/veQF/mngAAA");
    static final byte[] E = Base64.getDecoder().decode("UklGRlQAAABXRUJQVlA4IEgAAAAwAwCdASoUABAAPtFUo0uoJKMhsAgBABoJaQAAW4N2M3TQAM4+uuHeq5g8JWUxZPRikufJa++ytta0++MDFHklNb+EoL4AAAA=");

    /** One RIFF chunk: its id and where its data starts and ends. */
    private record Chunk(String id, int start, int end) {}

    private static List<Chunk> chunksOf(byte[] file, int from, int to) {
        var chunks = new ArrayList<Chunk>();
        var pos = from;
        while (pos + 8 <= to) {
            var id = new String(file, pos, 4, StandardCharsets.ISO_8859_1);
            var len = (file[pos + 4] & 0xFF) | (file[pos + 5] & 0xFF) << 8 | (file[pos + 6] & 0xFF) << 16 | (file[pos + 7] & 0xFF) << 24;
            chunks.add(new Chunk(id, pos + 8, pos + 8 + len));
            pos += 8 + len + (len & 1);
        }
        return chunks;
    }

    private static int le24(byte[] f, int at) {
        return (f[at] & 0xFF) | (f[at + 1] & 0xFF) << 8 | (f[at + 2] & 0xFF) << 16;
    }

    private static byte[] png(int width, int height, int color) throws IOException {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (var x = 0; x < width; x++) for (var y = 0; y < height; y++) img.setRGB(x, y, color);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String message(Runnable body) {
        try {
            body.run();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return "no exception";
    }

    @Test
    void aStillWebpReportsItsSizeWhateverItsEncoding() {
        assertEquals("24x16", AnimatedImages.webpSize(A).toString());
        assertEquals("24x16", AnimatedImages.webpSize(C).toString());
        assertEquals("24x16", AnimatedImages.webpSize(D).toString());
        assertEquals("20x16", AnimatedImages.webpSize(E).toString());
    }

    @Test
    void theAnimatedWebpIsAWellFormedRiffWithOneFramePerStill() {
        var out = AnimatedImages.webp(List.of(A, B, A), 250);

        assertEquals("RIFF", new String(out, 0, 4, StandardCharsets.ISO_8859_1));
        assertEquals("WEBP", new String(out, 8, 4, StandardCharsets.ISO_8859_1));
        assertEquals(out.length - 8, (out[4] & 0xFF) | (out[5] & 0xFF) << 8 | (out[6] & 0xFF) << 16 | (out[7] & 0xFF) << 24);
        var top = chunksOf(out, 12, out.length);
        assertEquals(List.of("VP8X", "ANIM", "ANMF", "ANMF", "ANMF"), top.stream().map(Chunk::id).toList());
        var vp8x = top.get(0);
        assertEquals(0x02, out[vp8x.start()] & 0xFF, "animation, no alpha");
        assertEquals(23, le24(out, vp8x.start() + 4), "canvas width - 1");
        assertEquals(15, le24(out, vp8x.start() + 7), "canvas height - 1");
        for (var frame : top.subList(2, 5)) {
            assertEquals(250, le24(out, frame.start() + 12), "duration");
            assertEquals(23, le24(out, frame.start() + 6), "frame width - 1");
            assertEquals(List.of("VP8 "), chunksOf(out, frame.start() + 16, frame.end()).stream().map(Chunk::id).toList());
        }
    }

    @Test
    void aFrameWithAlphaKeepsItsAlphaChunkAndSetsTheAlphaFlag() {
        var out = AnimatedImages.webp(List.of(A, D), 100);

        var top = chunksOf(out, 12, out.length);
        assertEquals(0x12, out[top.get(0).start()] & 0xFF, "animation and alpha");
        var second = top.get(3);
        assertEquals(List.of("ALPH", "VP8 "), chunksOf(out, second.start() + 16, second.end()).stream().map(Chunk::id).toList());
    }

    @Test
    void aLosslessFrameIsCarriedAsItIs() {
        var out = AnimatedImages.webp(List.of(A, C), 100);

        var second = chunksOf(out, 12, out.length).get(3);
        assertEquals(List.of("VP8L"), chunksOf(out, second.start() + 16, second.end()).stream().map(Chunk::id).toList());
    }

    @Test
    void badInputIsNamedExactly() {
        assertEquals("no frames", message(() -> AnimatedImages.webp(List.of(), 100)));
        assertEquals("frame time must be between 1 and 16777215 ms", message(() -> AnimatedImages.webp(List.of(A), 0)));
        assertEquals("frame time must be between 1 and 16777215 ms", message(() -> AnimatedImages.webp(List.of(A), 16_777_216)));
        assertEquals("frames differ in size: 24x16 and 20x16", message(() -> AnimatedImages.webp(List.of(A, E), 100)));
        assertEquals("not a WebP image", message(() -> AnimatedImages.webp(List.of(A, "not a webp at all, just text".getBytes(StandardCharsets.UTF_8)), 100)));
        assertEquals("not a WebP image", message(() -> AnimatedImages.webp(List.of(new byte[3]), 100)));
        assertEquals("the WebP is already animated", message(() -> AnimatedImages.webp(List.of(AnimatedImages.webp(List.of(A), 100)), 100)));
    }

    @Test
    void aTruncatedWebpIsRefused() {
        var cut = java.util.Arrays.copyOf(A, A.length - 10);

        assertEquals("truncated WebP chunk", message(() -> AnimatedImages.webp(List.of(cut), 100)));
    }

    @Test
    void theFrameLimitIsEnforced() {
        var many = java.util.Collections.nCopies(AnimatedImages.MAX_FRAMES + 1, A);

        assertEquals("at most 1000 frames", message(() -> AnimatedImages.webp(many, 100)));
        var atLimit = AnimatedImages.webp(java.util.Collections.nCopies(AnimatedImages.MAX_FRAMES, A), 100);
        assertEquals(AnimatedImages.MAX_FRAMES, chunksOf(atLimit, 12, atLimit.length).stream().filter(c -> c.id().equals("ANMF")).count());
    }

    @Test
    void theGifDecodesBackToTheSameFramesAtTheSameSizeAndPace() throws Exception {
        var out = AnimatedImages.gif(List.of(png(40, 30, 0xC81E1E), png(40, 30, 0x1E1EC8), png(40, 30, 0x1EC81E)), 400);

        assertEquals("GIF89a", new String(out, 0, 6, StandardCharsets.ISO_8859_1));
        assertTrue(new String(out, StandardCharsets.ISO_8859_1).contains("NETSCAPE2.0"), "loops forever");
        var reader = ImageIO.getImageReadersByFormatName("gif").next();
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(out)));
        assertEquals(3, reader.getNumImages(true));
        for (var i = 0; i < 3; i++) {
            var frame = reader.read(i);
            assertEquals(40, frame.getWidth());
            assertEquals(30, frame.getHeight());
            var root = (javax.imageio.metadata.IIOMetadataNode) reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
            var control = root.getElementsByTagName("GraphicControlExtension").item(0);
            assertEquals("40", control.getAttributes().getNamedItem("delayTime").getNodeValue());
        }
        assertEquals(0x1EC81E, reader.read(2).getRGB(5, 5) & 0xFFFFFF);
    }

    @Test
    void aGifRefusesWhatIsNotAnImageOrNotOneSize() throws Exception {
        var a = png(40, 30, 0);
        var b = png(41, 30, 0);

        assertEquals("not an image", message(() -> {
            try {
                AnimatedImages.gif(List.of("text".getBytes(StandardCharsets.UTF_8)), 100);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
        assertEquals("frames differ in size: 40x30 and 41x30", message(() -> {
            try {
                AnimatedImages.gif(List.of(a, b), 100);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
        assertEquals("no frames", message(() -> {
            try {
                AnimatedImages.gif(List.of(), 100);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
    }
}
