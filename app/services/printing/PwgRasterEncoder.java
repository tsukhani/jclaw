package services.printing;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes page bitmaps as PWG Raster (PWG 5102.4) — JCLAW-911.
 *
 * <p>This is what AirPrint-class printers actually want. The Canon this was
 * built against advertises {@code image/pwg-raster} and rejects both
 * {@code text/plain} and {@code application/pdf}, so without an encoder there is
 * no correct way to print to it at all.
 *
 * <p>Format, in the order it is written:
 * <pre>
 *   "RaS2"                     file sync word, big-endian variant
 *   per page:
 *     1796-byte page header    fixed layout, big-endian integers
 *     RLE-compressed lines     see {@link #encodeLine}
 * </pre>
 *
 * <p>Big-endian throughout, which is what the {@code RaS2} sync word declares.
 * The little-endian variant ({@code 2SaR}) is legal CUPS raster but PWG Raster
 * pins the byte order, and a printer that trusts the sync word will read a
 * little-endian header as garbage dimensions.
 *
 * <p>Color space and resolution are the caller's to choose, from what the
 * printer advertises in {@code pwg-raster-document-type-supported} and
 * {@code pwg-raster-document-resolution-supported}. Both matter more than they
 * look: sending 300 DPI sRGB to a device that declares 600 DPI produced
 * {@code printer-state-reasons=spool-area-full} and a stopped printer, not a
 * page. Greyscale ({@code sgray_8}) is a third the bytes of {@code srgb_8},
 * which is the difference between fitting a small printer's spool and not.
 */
public final class PwgRasterEncoder {

    /** File sync word for the big-endian variant. */
    private static final byte[] SYNC_WORD = "RaS2".getBytes(StandardCharsets.US_ASCII);

    /** Fixed page-header size in bytes. Not negotiable; readers seek by it. */
    static final int HEADER_BYTES = 1796;

    /** cupsColorSpace 19 = sRGB, 8 bits x 3. */
    private static final int COLORSPACE_SRGB = 19;

    /**
     * cupsColorSpace 18 = sGray, 8 bits x 1.
     *
     * <p>Worth having rather than always sending color: a page is a third the
     * size, and this class of printer has a small spool. A 600 DPI A4 page is
     * 104 MB as sRGB and 35 MB as sGray before compression — the Canon answered
     * a color job with printer-state-reasons=spool-area-full and stopped.
     */
    private static final int COLORSPACE_SGRAY = 18;

    /** cupsColorOrder 0 = chunky (RGBRGB…), the only order PWG requires support for. */
    private static final int COLOR_ORDER_CHUNKED = 0;

    /** cupsCompression is unused by PWG Raster; the line RLE is mandatory regardless. */
    private static final int COMPRESSION_NONE = 0;

    private static final int BITS_PER_COLOR = 8;

    private PwgRasterEncoder() {}

    /**
     * Encode pages into one PWG Raster stream.
     *
     * @param pages      page bitmaps, all assumed the same size
     * @param dpi        resolution to declare; must match how the pages were rendered
     * @param mediaName  PWG media name for the header (e.g. {@code iso_a4_210x297mm})
     * @param duplex     whether to request two-sided output
     * @param tumble     short-edge binding when {@code duplex}
     */
    public static byte[] encode(List<BufferedImage> pages, int dpi, String mediaName,
                                boolean duplex, boolean tumble, boolean grayscale)
            throws IOException {
        var colors = grayscale ? 1 : 3;
        var out = new ByteArrayOutputStream();
        out.write(SYNC_WORD);
        for (var page : pages) {
            writePageHeader(out, page.getWidth(), page.getHeight(), dpi, mediaName,
                    duplex, tumble, colors);
            writePageData(out, page, colors);
        }
        return out.toByteArray();
    }

    /**
     * The 1796-byte header. Every field is written even when zero, because the
     * layout is positional — a skipped field shifts everything after it and the
     * printer reads the page dimensions out of the wrong offset.
     */
    private static void writePageHeader(OutputStream out, int width, int height, int dpi,
                                        String mediaName, boolean duplex, boolean tumble,
                                        int colors) throws IOException {
        var bytesPerLine = width * colors;
        // Page size in PWG units (1/72 inch points), derived from pixels and DPI.
        var pointsWide = (int) Math.round(width * 72.0 / dpi);
        var pointsHigh = (int) Math.round(height * 72.0 / dpi);

        cstring(out, "", 64);                       // MediaClass
        cstring(out, "", 64);                       // MediaColor
        cstring(out, "", 64);                       // MediaType
        cstring(out, "", 64);                       // OutputType
        u32(out, 0);                                // AdvanceDistance
        u32(out, 0);                                // AdvanceMedia
        u32(out, 0);                                // Collate
        u32(out, 0);                                // CutMedia
        u32(out, duplex ? 1 : 0);                   // Duplex
        u32(out, dpi);                              // HWResolution X
        u32(out, dpi);                              // HWResolution Y
        u32(out, 0); u32(out, 0); u32(out, 0); u32(out, 0);   // ImagingBoundingBox
        u32(out, 0);                                // InsertSheet
        u32(out, 0);                                // Jog
        u32(out, 0);                                // LeadingEdge
        u32(out, 0); u32(out, 0);                   // Margins
        u32(out, 0);                                // ManualFeed
        u32(out, 0);                                // MediaPosition
        u32(out, 0);                                // MediaWeight
        u32(out, 0);                                // MirrorPrint
        u32(out, 0);                                // NegativePrint
        u32(out, 1);                                // NumCopies
        u32(out, 0);                                // Orientation
        u32(out, 0);                                // OutputFaceUp
        u32(out, pointsWide);                       // PageSize width
        u32(out, pointsHigh);                       // PageSize height
        u32(out, 0);                                // Separations
        u32(out, 0);                                // TraySwitch
        u32(out, tumble ? 1 : 0);                   // Tumble
        u32(out, width);                            // cupsWidth
        u32(out, height);                           // cupsHeight
        u32(out, 0);                                // cupsMediaType
        u32(out, BITS_PER_COLOR);                   // cupsBitsPerColor
        u32(out, BITS_PER_COLOR * colors);          // cupsBitsPerPixel
        u32(out, bytesPerLine);                     // cupsBytesPerLine
        u32(out, COLOR_ORDER_CHUNKED);              // cupsColorOrder
        u32(out, colors == 1 ? COLORSPACE_SGRAY : COLORSPACE_SRGB);   // cupsColorSpace
        u32(out, COMPRESSION_NONE);                 // cupsCompression
        u32(out, 0);                                // cupsRowCount
        u32(out, 0);                                // cupsRowFeed
        u32(out, 0);                                // cupsRowStep
        u32(out, colors);                           // cupsNumColors
        f32(out, 1.0f);                             // cupsBorderlessScalingFactor
        f32(out, pointsWide); f32(out, pointsHigh); // cupsPageSize
        f32(out, 0); f32(out, 0); f32(out, pointsWide); f32(out, pointsHigh); // cupsImagingBBox

        // cupsInteger[16] — PWG 5102.4 gives these slots specific meanings, and
        // two of them are NOT optional.
        //
        // CrossFeedTransform and FeedTransform (1 and 2) must be 1 or -1. Leaving
        // them at zero is a scale-by-zero: the Canon accepted such a job with
        // successful-ok, then stopped with printer-state-reasons=spool-area-full
        // and printed nothing. The raster was 53 KB, so the spool was never
        // actually full — the printer had derived nonsense geometry from a zero
        // transform and reported the nearest error it had. CUPS's own rastertopwg
        // writes 1 for both.
        u32(out, 0);                        // [0]  reserved
        u32(out, 1);                        // [1]  CrossFeedTransform
        u32(out, 1);                        // [2]  FeedTransform
        u32(out, 0);                        // [3]  ImageBoxLeft
        u32(out, 0);                        // [4]  ImageBoxTop
        u32(out, width);                    // [5]  ImageBoxRight
        u32(out, height);                   // [6]  ImageBoxBottom
        // AlternatePrimary is the color used where the page is "blank"; white,
        // as 0xFFFFFF, or the printer may treat unwritten area as black.
        u32(out, 0xFFFFFF);                 // [7]  AlternatePrimary
        u32(out, 0);                        // [8]  PrintQuality (0 = printer default)
        for (int i = 9; i < 16; i++) {      // [9..15] reserved / vendor
            u32(out, 0);
        }
        for (int i = 0; i < 16; i++) {                // cupsReal[16]
            f32(out, 0);
        }
        for (int i = 0; i < 16; i++) {                // cupsString[16][64]
            cstring(out, "", 64);
        }
        cstring(out, "", 64);                        // cupsMarkerType
        cstring(out, "", 64);                        // cupsRenderingIntent
        cstring(out, mediaName == null ? "" : mediaName, 64);  // cupsPageSizeName
    }

    /**
     * Write one page's pixels, RLE-compressed line group by line group.
     *
     * <p>Identical consecutive lines collapse into one encoded line with a repeat
     * count, which is why a mostly-white page costs almost nothing: a blank A4 at
     * 300 DPI is 26 MB raw and a few kilobytes encoded.
     */
    private static void writePageData(OutputStream out, BufferedImage page, int colors)
            throws IOException {
        var width = page.getWidth();
        var height = page.getHeight();
        var line = new byte[width * colors];
        var previous = new byte[line.length];
        var haveePrevious = false;
        var repeats = 0;
        var encoded = new ByteArrayOutputStream();

        for (int y = 0; y < height; y++) {
            readLine(page, y, width, line, colors);
            if (haveePrevious && java.util.Arrays.equals(line, previous) && repeats < 255) {
                // Same as the line before: bump the repeat count rather than
                // re-encoding it. The count is one byte, so runs cap at 256 lines.
                repeats++;
                continue;
            }
            if (haveePrevious) {
                out.write(repeats);
                encoded.writeTo(out);
            }
            encoded.reset();
            encodeLine(encoded, line, width, colors);
            System.arraycopy(line, 0, previous, 0, line.length);
            haveePrevious = true;
            repeats = 0;
        }
        if (haveePrevious) {
            out.write(repeats);
            encoded.writeTo(out);
        }
    }

    /** Extract one row as chunky RGB, without allocating per pixel. */
    private static void readLine(BufferedImage page, int y, int width, byte[] into, int colors) {
        for (int x = 0; x < width; x++) {
            var rgb = page.getRGB(x, y);
            var i = x * colors;
            if (colors == 1) {
                // Rec. 601 luma. Averaging the channels instead would render red
                // text near-white on a greyscale printer.
                into[i] = (byte) ((299 * ((rgb >> 16) & 0xFF)
                        + 587 * ((rgb >> 8) & 0xFF) + 114 * (rgb & 0xFF)) / 1000);
            } else {
                into[i] = (byte) (rgb >> 16);
                into[i + 1] = (byte) (rgb >> 8);
                into[i + 2] = (byte) rgb;
            }
        }
    }

    /**
     * CUPS/PWG line RLE.
     *
     * <pre>
     *   count 0..127   → the next single pixel repeats (count + 1) times
     *   count 128..255 → (257 - count) literal pixels follow
     * </pre>
     *
     * Runs are counted in <em>pixels</em>, not bytes — encoding by byte would
     * split an RGB triple across a run boundary and shear the color channels.
     */
    static void encodeLine(OutputStream out, byte[] line, int width, int colors) throws IOException {
        int x = 0;
        while (x < width) {
            var runLength = 1;
            while (x + runLength < width && runLength < 128
                    && samePixel(line, x, x + runLength, colors)) {
                runLength++;
            }

            if (runLength > 1) {
                out.write(runLength - 1);
                out.write(line, x * colors, colors);
                x += runLength;
                continue;
            }

            // No repeat here — gather literals until pixels start repeating again.
            var literalStart = x;
            var literals = 0;
            while (x < width && literals < 128
                    && (x + 1 >= width || !samePixel(line, x, x + 1, colors))) {
                literals++;
                x++;
            }
            out.write(257 - literals);
            out.write(line, literalStart * colors, literals * colors);
        }
    }

    private static boolean samePixel(byte[] line, int a, int b, int colors) {
        var i = a * colors;
        var j = b * colors;
        for (int c = 0; c < colors; c++) {
            if (line[i + c] != line[j + c]) {
                return false;
            }
        }
        return true;
    }

    /** Fixed-width NUL-padded ASCII, truncated rather than overflowing its slot. */
    private static void cstring(OutputStream out, String value, int size) throws IOException {
        var bytes = value.getBytes(StandardCharsets.US_ASCII);
        var n = Math.min(bytes.length, size - 1);
        out.write(bytes, 0, n);
        for (int i = n; i < size; i++) {
            out.write(0);
        }
    }

    private static void u32(OutputStream out, int value) throws IOException {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static void f32(OutputStream out, float value) throws IOException {
        u32(out, Float.floatToIntBits(value));
    }
}
