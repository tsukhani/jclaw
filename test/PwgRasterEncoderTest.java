import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.printing.PrintRenderer;
import services.printing.PwgRasterEncoder;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * PWG Raster encoding (JCLAW-911).
 *
 * <p>A binary format encoder cannot be checked by eye, and the printer's only
 * verdict is "successful-ok" or a blank page. So the RLE is validated by
 * decoding it back — the decoder here is written from PWG 5102.4 independently
 * of the encoder, so agreement means both match the spec rather than matching
 * each other's mistakes.
 */
class PwgRasterEncoderTest extends UnitTest {

    private static final int HEADER_BYTES = 1796;
    private static final int SYNC_BYTES = 4;
    private static final int BYTES_PER_PIXEL = 3;

    /** Decode one page's RLE back to pixels, per the spec's rules. */
    private static byte[][] decodePage(byte[] stream, int offset, int width, int height) {
        var rows = new byte[height][width * BYTES_PER_PIXEL];
        var pos = offset;
        var y = 0;
        while (y < height) {
            var repeats = stream[pos++] & 0xFF;
            var line = new ByteArrayOutputStream();
            var pixels = 0;
            while (pixels < width) {
                var count = stream[pos++] & 0xFF;
                if (count < 128) {
                    // count+1 copies of the single pixel that follows
                    var n = count + 1;
                    for (int i = 0; i < n; i++) {
                        line.write(stream, pos, BYTES_PER_PIXEL);
                    }
                    pos += BYTES_PER_PIXEL;
                    pixels += n;
                } else {
                    // 257-count literal pixels
                    var n = 257 - count;
                    line.write(stream, pos, n * BYTES_PER_PIXEL);
                    pos += n * BYTES_PER_PIXEL;
                    pixels += n;
                }
            }
            var decoded = line.toByteArray();
            assertEquals(width * BYTES_PER_PIXEL, decoded.length,
                    "a decoded line must be exactly one line wide");
            for (int r = 0; r <= repeats && y < height; r++, y++) {
                rows[y] = decoded;
            }
        }
        return rows;
    }

    private static BufferedImage image(int w, int h, java.util.function.BiFunction<Integer, Integer, Color> paint) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, paint.apply(x, y).getRGB());
            }
        }
        return img;
    }

    @Test
    void beginsWithTheBigEndianSyncWordAndAFixedHeader() throws Exception {
        var page = image(8, 4, (x, y) -> Color.WHITE);
        var out = PwgRasterEncoder.encode(List.of(page), 300, "iso_a4_210x297mm", false, false, false);

        // "RaS2" declares big-endian. The little-endian variant is legal CUPS
        // raster but PWG pins the order, and a printer trusting the sync word
        // would read a byte-swapped header as nonsense dimensions.
        assertEquals("RaS2", new String(out, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(out.length > SYNC_BYTES + HEADER_BYTES,
                "sync word + header + at least some line data");
    }

    @Test
    void headerCarriesTheDimensionsThePrinterReadsPositionally() throws Exception {
        var page = image(16, 9, (x, y) -> Color.WHITE);
        var out = PwgRasterEncoder.encode(List.of(page), 300, "iso_a4_210x297mm", false, false, false);
        // slice(), not wrap(array, offset, len): the latter sets position/limit but
        // leaves absolute getInt(i) indexing, so every offset below would have been
        // read four bytes early — from inside the sync word.
        var header = ByteBuffer.wrap(out).slice(SYNC_BYTES, HEADER_BYTES);

        // cupsWidth/cupsHeight sit at fixed offsets inside the 1796-byte header.
        // Every field before them is written even when zero precisely so these
        // land here; a skipped field shifts them and the printer reads garbage.
        assertEquals(16, header.getInt(372), "cupsWidth at its spec offset");
        assertEquals(9, header.getInt(376), "cupsHeight at its spec offset");
        assertEquals(8, header.getInt(384), "cupsBitsPerColor");
        assertEquals(24, header.getInt(388), "cupsBitsPerPixel");
        assertEquals(16 * 3, header.getInt(392), "cupsBytesPerLine");
        assertEquals(19, header.getInt(400), "cupsColorSpace = sRGB");
        assertEquals(3, header.getInt(420), "cupsNumColors");

        // cupsInteger[] starts at 452. The two transforms must be 1, not 0 — a
        // zero transform is a scale-by-zero, and the Canon answered such a job
        // with successful-ok then stopped without printing. A 53 KB raster is not
        // a full spool; the printer had derived nonsense geometry.
        assertEquals(1, header.getInt(452 + 4), "cupsInteger[1] CrossFeedTransform must be 1");
        assertEquals(1, header.getInt(452 + 8), "cupsInteger[2] FeedTransform must be 1");
        assertEquals(16, header.getInt(452 + 20), "cupsInteger[5] ImageBoxRight = width");
        assertEquals(9, header.getInt(452 + 24), "cupsInteger[6] ImageBoxBottom = height");
        assertEquals(0xFFFFFF, header.getInt(452 + 28),
                "cupsInteger[7] AlternatePrimary must be white, or blank area may print black");
    }

    @Test
    void rleRoundTripsAGradientExactly() throws Exception {
        // A gradient defeats run-length compression on every axis, so this
        // exercises the literal path and the run-boundary logic together.
        var w = 40;
        var h = 6;
        var page = image(w, h, (x, y) -> new Color((x * 6) % 256, (y * 40) % 256, (x + y) % 256));
        var out = PwgRasterEncoder.encode(List.of(page), 300, "iso_a4_210x297mm", false, false, false);

        var rows = decodePage(out, SYNC_BYTES + HEADER_BYTES, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                var rgb = page.getRGB(x, y);
                assertEquals((byte) (rgb >> 16), rows[y][x * 3], "R at " + x + "," + y);
                assertEquals((byte) (rgb >> 8), rows[y][x * 3 + 1], "G at " + x + "," + y);
                assertEquals((byte) rgb, rows[y][x * 3 + 2], "B at " + x + "," + y);
            }
        }
    }

    @Test
    void rleRoundTripsRunsAndAlternationAtTheBoundary() throws Exception {
        // Long runs, then strict alternation — the two shapes the encoder switches
        // between. A wrong boundary here shears color channels rather than
        // failing loudly, which is why this decodes rather than eyeballs sizes.
        var w = 300;
        var page = image(w, 2, (x, y) -> x < 200 ? Color.BLACK : (x % 2 == 0 ? Color.RED : Color.BLUE));
        var out = PwgRasterEncoder.encode(List.of(page), 300, null, false, false, false);

        var rows = decodePage(out, SYNC_BYTES + HEADER_BYTES, w, 2);
        for (int x = 0; x < w; x++) {
            var expected = page.getRGB(x, 0);
            assertEquals((byte) (expected >> 16), rows[0][x * 3], "R at " + x);
            assertEquals((byte) (expected >> 8), rows[0][x * 3 + 1], "G at " + x);
            assertEquals((byte) expected, rows[0][x * 3 + 2], "B at " + x);
        }
    }

    @Test
    void identicalLinesCollapseSoABlankPageIsSmall() throws Exception {
        // A blank A4 at 300 DPI is ~26 MB of raw pixels. If line-repeat collapsing
        // is broken the job still prints, but every page costs megabytes over the
        // wire — a failure that only shows up as mysterious slowness.
        var page = PrintRenderer.blankPage(PrintRenderer.PageSize.a4());
        var out = PwgRasterEncoder.encode(List.of(page), 300, "iso_a4_210x297mm", false, false, false);

        var raw = (long) page.getWidth() * page.getHeight() * 3;
        assertTrue(out.length < raw / 1000,
                "blank page should compress ~1000x, got %d bytes vs %d raw".formatted(out.length, raw));
    }

    @Test
    void multiplePagesEachGetTheirOwnHeader() throws Exception {
        var a = image(8, 4, (x, y) -> Color.WHITE);
        var b = image(8, 4, (x, y) -> Color.BLACK);
        var one = PwgRasterEncoder.encode(List.of(a), 300, null, false, false, false);
        var two = PwgRasterEncoder.encode(List.of(a, b), 300, null, false, false, false);

        // Readers seek page-to-page by the fixed header size, so a second page
        // must add a full header, not just more line data.
        assertTrue(two.length >= one.length + HEADER_BYTES,
                "second page must carry its own 1796-byte header");
    }
}
