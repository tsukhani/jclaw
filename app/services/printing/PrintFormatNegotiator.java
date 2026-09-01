package services.printing;

import services.EventLogger;
import utils.HttpKeys;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Decides what bytes to actually send a printer (JCLAW-911).
 *
 * <p>Exists because the obvious approach — send the file as-is with its own MIME
 * type — fails on the printers people own. The Canon this was built against
 * advertises only {@code application/octet-stream}, {@code image/jpeg},
 * {@code image/urf} and {@code image/pwg-raster}. A text job sent as
 * {@code text/plain} came back
 * {@code client-error-document-format-not-supported}; the RAW fallback then
 * accepted the bytes and printed nothing at all, because a host-based inkjet has
 * no interpreter for ASCII.
 *
 * <p>So the rule is: send the source untouched when the printer claims to
 * understand it, and otherwise rasterise and re-encode into something it does.
 */
public final class PrintFormatNegotiator {

    private static final String CATEGORY = "printer";

    public static final String PWG_RASTER = "image/pwg-raster";
    public static final String JPEG = "image/jpeg";
    public static final String OCTET_STREAM = HttpKeys.APPLICATION_OCTET_STREAM;

    /** JPEG quality for rendered pages. 0.9 keeps text edges clean without bloating the job. */
    private static final float JPEG_QUALITY = 0.9f;

    private PrintFormatNegotiator() {}

    /**
     * What to send, and how it was arrived at.
     *
     * @param document    bytes to transmit
     * @param format      MIME type to declare
     * @param converted   whether the source was rasterised rather than passed through
     * @param explanation one line for the operator; null when the source went as-is
     */
    public record Prepared(byte[] document, String format, boolean converted, String explanation,
                           String media) {

        /** Pass-through, still declaring the media the printer says is loaded. */
        static Prepared asIs(byte[] document, String format, String explanation, String media) {
            return new Prepared(document, format, false, explanation, media);
        }
    }

    /**
     * Choose an encoding the printer accepts.
     *
     * @param document        source bytes
     * @param sourceFormat    MIME type of the source
     * @param supported       formats the printer advertises; empty means unknown
     * @param job             job options, for duplex hints in the raster header
     */
    public static Prepared prepare(byte[] document, String sourceFormat,
                                   Set<String> supported, JobAttributes job) throws IOException {
        return prepare(document, sourceFormat, supported, job, IppClient.RasterCapabilities.UNKNOWN);
    }

    /**
     * Choose an encoding, using the printer's declared raster preferences.
     *
     * <p>{@code raster} is not a nicety. Rendering at our own choice of 300 DPI
     * sRGB and sending it to a Canon that declares 600 DPI and offers sgray_8
     * produced printer-state-reasons=spool-area-full: the job was accepted, the
     * printer stopped, and no page came out.
     */
    public static Prepared prepare(byte[] document, String sourceFormat, Set<String> supported,
                                   JobAttributes job, IppClient.RasterCapabilities raster)
            throws IOException {
        // The paper the printer says is loaded, unless the operator named one.
        // Resolved before any branch returns, because pass-through needs it just as
        // much as the raster path: a job declaring Letter into a Legal tray is
        // refused as E59/2114 and no page comes out.
        var media = job != null && job.media() != null ? job.media() : raster.mediaReady();

        // Unknown capabilities: send as-is. Converting on a guess is worse than
        // letting a printer that would have coped decide for itself, and IPP will
        // say document-format-not-supported if it cannot.
        if (supported.isEmpty()) {
            return Prepared.asIs(document, sourceFormat, null, media);
        }
        // Native pass-through wins whenever it is available — no re-encode, no
        // resolution loss, and the printer's own renderer is usually better than ours.
        if (sourceFormat != null && supported.contains(sourceFormat.toLowerCase())) {
            if (!isProgressiveJpeg(document)) {
                return Prepared.asIs(document, sourceFormat, null, media);
            }
            // A Canon E3300 advertising image/jpeg accepted a progressive one with
            // successful-ok, fed the sheet and printed nothing: the advertisement
            // covers the MIME type, not every encoding of it. Re-encode rather than
            // rasterise — a photo rasterised to A4 at 600 DPI is tens of MB because
            // RLE barely compresses photographic content, which timed the IPP upload
            // out at 60s and fell back to RAW. Baseline keeps the payload at roughly
            // source size and the printer renders it correctly.
            return new Prepared(toJpeg(PrintRenderer.readImage(document)), sourceFormat, true,
                    "re-encoded to baseline JPEG because this printer accepts image/jpeg "
                            + "but prints a progressive one as a blank page", media);
        }

        if (supported.contains(PWG_RASTER)) {
            // Color only when the printer has no greyscale mode or the operator
            // explicitly asked for color. Gray is a third the bytes and a quarter
            // of the heap, and this class of printer has very little spool.
            var wantsColor = job != null && "color".equals(job.colorMode());
            var gray = raster.grayscale() && !wantsColor;
            var page = PrintRenderer.PageSize.fromMedia(media, raster.dpi());
            var pages = PrintRenderer.render(document, sourceFormat, page, gray);

            var duplex = job != null && job.sides() != null && job.sides().startsWith("two-sided");
            var tumble = job != null && "two-sided-short-edge".equals(job.sides());
            var encoded = PwgRasterEncoder.encode(pages, page.dpi(), media, duplex, tumble, gray);
            return new Prepared(encoded, PWG_RASTER, true,
                    "rendered %d page(s) to PWG raster at %d DPI %s on %s because the printer does not accept %s"
                            .formatted(pages.size(), page.dpi(), gray ? "greyscale" : "colour",
                                    media == null ? "the default media" : media, describe(sourceFormat)),
                    media);
        }

        var page = PrintRenderer.PageSize.fromMedia(media, PrintRenderer.DEFAULT_DPI);
        var pages = PrintRenderer.render(document, sourceFormat, page);

        if (supported.contains(JPEG)) {
            // One page only: JPEG has no multi-page container, so a longer document
            // would silently lose everything after page one. Saying so beats
            // printing a truncated document that looks complete.
            var jpeg = toJpeg(pages.getFirst());
            var note = pages.size() > 1
                    ? " — ONLY PAGE 1 OF %d was sent, as this printer's best supported format "
                            .formatted(pages.size()) + "(JPEG) holds a single page"
                    : "";
            return new Prepared(jpeg, JPEG, true,
                    "rendered to JPEG because the printer does not accept %s"
                            .formatted(describe(sourceFormat)) + note, media);
        }

        if (supported.contains(OCTET_STREAM)) {
            // Last resort. octet-stream means "sniff it yourself", which a printer
            // may well fail at — but it is the only remaining thing it admits to
            // accepting, and an attempt beats a refusal.
            EventLogger.warn(CATEGORY, "Printer advertises no format JClaw can produce (%s); "
                    .formatted(String.join(", ", supported)) + "falling back to octet-stream");
            return Prepared.asIs(document, OCTET_STREAM,
                    "sent as octet-stream — the printer advertises no format JClaw can render to, "
                            + "so it must detect the type itself", media);
        }

        throw new IOException("Printer accepts none of the formats JClaw can produce. "
                + "It advertises: " + String.join(", ", supported));
    }

    /**
     * True when {@code document} is a progressive JPEG (SOF2).
     *
     * <p>Walks the marker chain rather than searching for the {@code FFC2} bytes,
     * which occur freely inside entropy-coded scan data. Anything that is not a
     * well-formed JPEG answers false and takes the normal path.
     */
    public static boolean isProgressiveJpeg(byte[] document) {
        if (document == null || document.length < 4
                || (document[0] & 0xFF) != 0xFF || (document[1] & 0xFF) != 0xD8) {
            return false;
        }
        var i = 2;
        while (i + 3 < document.length) {
            if ((document[i] & 0xFF) != 0xFF) {
                return false;  // desynchronised — do not guess
            }
            var marker = document[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;           // fill byte before the real marker
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                i += 2;        // standalone marker, carries no length
                continue;
            }
            if (marker == 0xC2) {
                return true;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return false;  // scan data or end of image; no SOF2 will follow
            }
            var length = ((document[i + 2] & 0xFF) << 8) | (document[i + 3] & 0xFF);
            if (length < 2) {
                return false;
            }
            i += 2 + length;
        }
        return false;
    }

    /** Encode one page as JPEG at {@link #JPEG_QUALITY}. */
    static byte[] toJpeg(BufferedImage page) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG encoder available in this JVM");
        }
        var writer = writers.next();
        var out = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            var params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(page, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String describe(String format) {
        return format == null || format.isBlank() ? "that document type" : format;
    }

    /** Formats a discovered printer advertises, from its mDNS {@code pdl} TXT record. */
    public static Set<String> advertisedFormats(DiscoveredPrinter printer) {
        var pdl = printer.capabilities().get("pdl");
        if (pdl == null || pdl.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(List.of(pdl.toLowerCase().split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
}
