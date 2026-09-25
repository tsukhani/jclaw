import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.printing.IppClient;
import services.printing.JobAttributes;
import services.printing.PrintFormatNegotiator;
import services.printing.PrintRenderer;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Rasterisation and format negotiation (JCLAW-911).
 *
 * <p>Both exist because of one measured failure: a Canon E3300 answered
 * {@code client-error-document-format-not-supported} for {@code text/plain},
 * and the raw-socket fallback then accepted the bytes and printed nothing —
 * the worst outcome, since it reports success.
 */
class PrintRenderingTest extends UnitTest {

    private static byte[] png(int w, int h) throws Exception {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ─── Rendering ───

    @Test
    void textPaginatesRatherThanTruncating() {
        var many = "line\n".repeat(400);
        var pages = PrintRenderer.renderText(many, PrintRenderer.PageSize.a4());
        // 400 lines cannot fit one A4 page at 11pt. Silently dropping the
        // remainder would print a document that looks complete.
        assertTrue(pages.size() > 1, "expected pagination, got " + pages.size() + " page(s)");
        assertEquals(PrintRenderer.PageSize.a4().width(), pages.getFirst().getWidth());
        assertEquals(PrintRenderer.PageSize.a4().height(), pages.getFirst().getHeight());
    }

    @Test
    void textWrapsAtTheColumnAndKeepsExplicitNewlines() {
        var wrapped = PrintRenderer.wrap("aaaaaaaaaa\n\nbb", 4);
        // Long line splits into ceil(10/4)=3; the blank line survives; short line intact.
        assertEquals(java.util.List.of("aaaa", "aaaa", "aa", "", "bb"), wrapped);
    }

    @Test
    void anImageIsFittedWholeRatherThanCropped() throws Exception {
        // A wide image on a portrait page gets bars, not a trimmed subject —
        // cropping a photo to fit is a silent content change.
        var wide = ImageIO.read(new java.io.ByteArrayInputStream(png(400, 100)));
        var fitted = PrintRenderer.fitToPage(wide, PrintRenderer.PageSize.a4());

        assertEquals(PrintRenderer.PageSize.a4().width(), fitted.getWidth());
        assertEquals(PrintRenderer.PageSize.a4().height(), fitted.getHeight());
        // Corners stay white (the letterbox), centre carries the image.
        assertEquals(Color.WHITE.getRGB(), fitted.getRGB(5, 5));
        assertEquals(Color.RED.getRGB(),
                fitted.getRGB(PrintRenderer.PageSize.a4().width() / 2, PrintRenderer.PageSize.a4().height() / 2));
    }

    @Test
    void anImageFilenameReachesNativePassThroughRatherThanOctetStream() throws Exception {
        // Covers the seam: formatFor turns a filename into the MIME type the negotiator
        // matches on. Both sides were tested alone, so no image filename crossed the join.
        var canon = Set.of("application/octet-stream", "image/jpeg", "image/urf", "image/pwg-raster");
        var prepared = PrintFormatNegotiator.prepare(
                png(120, 80), tools.PrinterTool.formatFor("photo.jpeg"),
                canon, JobAttributes.DEFAULTS);

        // Sent unchanged as the type the printer advertises, not sniffed as bytes.
        assertEquals("image/jpeg", prepared.format());
        assertFalse(prepared.converted());
    }

    /** Same picture as {@link #png}, encoded as a real progressive JPEG. */
    private static byte[] progressiveJpeg(int w, int h) throws Exception {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
        var out = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        }
        writer.dispose();
        return out.toByteArray();
    }

    private static byte[] baselineJpeg(int w, int h) throws Exception {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    @Test
    void progressiveJpegIsDetectedAndBaselineIsNot() throws Exception {
        assertTrue(PrintFormatNegotiator.isProgressiveJpeg(progressiveJpeg(64, 48)));
        assertFalse(PrintFormatNegotiator.isProgressiveJpeg(baselineJpeg(64, 48)));
        // Not a JPEG, and a truncated one, both take the normal path rather than guess.
        assertFalse(PrintFormatNegotiator.isProgressiveJpeg(png(16, 16)));
        assertFalse(PrintFormatNegotiator.isProgressiveJpeg(new byte[]{(byte) 0xFF, (byte) 0xD8}));
    }

    @Test
    void aProgressiveJpegIsReEncodedToBaselineRatherThanRasterised() throws Exception {
        // A Canon E3300 advertising image/jpeg accepted a progressive one with
        // successful-ok and printed a blank sheet; the same picture re-encoded
        // baseline came out. Rasterising also fixes the blank page but makes a
        // multi-megabyte PWG page for a photo, which timed the IPP upload out at 60s
        // and fell back to RAW — so re-encode and keep the payload near source size.
        var canon = Set.of("application/octet-stream", "image/jpeg", "image/urf", "image/pwg-raster");

        var progressive = PrintFormatNegotiator.prepare(
                progressiveJpeg(120, 80), "image/jpeg", canon, JobAttributes.DEFAULTS);
        assertTrue(progressive.converted(), "a progressive JPEG must be re-encoded");
        assertEquals("image/jpeg", progressive.format(), "still JPEG — not rasterised");
        assertFalse(PrintFormatNegotiator.isProgressiveJpeg(progressive.document()),
                "the re-encoded document must itself be baseline, or nothing was fixed");

        // The baseline equivalent still takes the fast path — the guard is narrow.
        var baseline = PrintFormatNegotiator.prepare(
                baselineJpeg(120, 80), "image/jpeg", canon, JobAttributes.DEFAULTS);
        assertFalse(baseline.converted(), "baseline JPEG must still pass through natively");
        assertEquals("image/jpeg", baseline.format());
    }

    @Test
    void passThroughStillDeclaresTheLoadedMedia() throws Exception {
        // Pass-through used to return before media was resolved, so it declared
        // nothing and the request's own size went through unchallenged. Letter
        // against a Legal tray is refused as E59/2114 with no page printed.
        var canon = Set.of("application/octet-stream", "image/jpeg", "image/urf", "image/pwg-raster");
        var legalLoaded = new IppClient.RasterCapabilities(
                600, true, Set.of("sgray_8"), "na_legal_8.5x14in");

        var prepared = PrintFormatNegotiator.prepare(
                png(120, 80), tools.PrinterTool.formatFor("photo.jpeg"),
                canon, JobAttributes.DEFAULTS, legalLoaded);

        assertFalse(prepared.converted(), "image/jpeg is advertised, so this is pass-through");
        assertEquals("na_legal_8.5x14in", prepared.media(),
                "pass-through must carry the loaded media, not leave it unset");
    }

    @Test
    void anExplicitMediaChoiceBeatsWhatTheTrayReports() throws Exception {
        // The operator asked for something specific; mediaReady is only the default.
        var canon = Set.of("application/octet-stream", "image/jpeg");
        var legalLoaded = new IppClient.RasterCapabilities(
                600, true, Set.of("sgray_8"), "na_legal_8.5x14in");
        var wantsA4 = new JobAttributes(null, null, "iso_a4_210x297mm");

        var prepared = PrintFormatNegotiator.prepare(
                png(120, 80), tools.PrinterTool.formatFor("photo.jpeg"),
                canon, wantsA4, legalLoaded);

        assertEquals("iso_a4_210x297mm", prepared.media());
    }

    @Test
    void anImageRastersThroughTheImageBranchNotTheTextBranch() throws Exception {
        // No octet-stream, so pass-through misses and the raster path runs. Undeclared,
        // the format is neither "image/" nor PDF and the PNG renders as text.
        var rasterOnly = Set.of("image/pwg-raster");
        var prepared = PrintFormatNegotiator.prepare(
                png(120, 80), tools.PrinterTool.formatFor("diagram.png"),
                rasterOnly, JobAttributes.DEFAULTS);

        assertEquals("image/pwg-raster", prepared.format());
        assertTrue(prepared.converted());
        assertEquals("RaS2", new String(prepared.document(), 0, 4, StandardCharsets.US_ASCII));
        // The explanation names the real source type; it read "application/octet-stream"
        // while the bug was live, which is the visible tell.
        assertTrue(prepared.explanation().contains("image/png"), prepared.explanation());
    }

    @Test
    void aCorruptImageFailsWithSomethingActionable() {
        var boom = assertThrows(java.io.IOException.class,
                () -> PrintRenderer.render("not an image".getBytes(StandardCharsets.UTF_8),
                        "image/png", PrintRenderer.PageSize.a4()));
        assertTrue(boom.getMessage().contains("image"), boom.getMessage());
    }

    @Test
    void mediaNameSelectsThePageGeometry() {
        var letter = PrintRenderer.PageSize.fromMedia("na_letter_8.5x11in", 300);
        assertEquals(2550, letter.width());
        assertEquals(3300, letter.height());

        // Anything unrecognised is A4 rather than a guessed size — a subtly wrong
        // page wastes paper more quietly than an obvious failure.
        var a4 = PrintRenderer.PageSize.a4();
        assertEquals(a4, PrintRenderer.PageSize.fromMedia("iso_a4_210x297mm", 300));
        assertEquals(a4, PrintRenderer.PageSize.fromMedia(null, 300));
        assertEquals(a4, PrintRenderer.PageSize.fromMedia("vendor-tray-7", 300));
    }

    @Test
    void pageGeometryScalesWithThePrintersDeclaredResolution() {
        // The bug this pins: 300 DPI was hardcoded and sent to a Canon that
        // declares 600dpi only. The job was accepted, then the printer stopped
        // with printer-state-reasons=spool-area-full and emitted nothing.
        var at300 = PrintRenderer.PageSize.fromMedia("iso_a4_210x297mm", 300);
        var at600 = PrintRenderer.PageSize.fromMedia("iso_a4_210x297mm", 600);

        assertEquals(600, at600.dpi(), "the page must carry the resolution it was built for");
        // Twice the DPI is twice the pixels on each axis, within rounding.
        assertTrue(Math.abs(at600.width() - at300.width() * 2) <= 2,
                "%d vs %d".formatted(at600.width(), at300.width()));
        assertTrue(Math.abs(at600.height() - at300.height() * 2) <= 2,
                "%d vs %d".formatted(at600.height(), at300.height()));

        // Zero means "the printer would not say" — fall back, never render 0x0.
        assertEquals(PrintRenderer.DEFAULT_DPI,
                PrintRenderer.PageSize.fromMedia(null, 0).dpi());
    }

    @Test
    void aColorModeThePrinterOffersIsAcceptedEvenIfItIsNotInTheShortList() {
        // Settings now lists what the device reports. The Canon advertises
        // auto-monochrome, which is outside the friendly three shown when there is
        // no printer to ask — validating against that short list would reject a
        // value the printer itself had just offered.
        assertFalse(JobAttributes.COLOR_MODE_VALUES.contains("auto-monochrome"),
                "sanity: auto-monochrome is not in the short UI list");
        assertNull(new JobAttributes(null, "auto-monochrome", null).validationError(),
                "a printer-advertised mode must validate");

        // Still a typo catcher, which is the whole point of validating at all.
        assertNotNull(new JobAttributes(null, "monochromatic", null).validationError());
    }

    @Test
    void enumOptionValuesCarryTheCodeNotTheDisplayString() {
        // JIPP renders enum attributes for humans: print-quality comes back as
        // "normal(4)" and orientation as "portrait(3)". IPP carries the integer,
        // so offering the rendered string as the value would have an operator save
        // print-quality=normal(4), which no printer accepts.
        var quality = services.printing.IppClient.toOptionValue("normal(4)");
        assertEquals("4", quality.value(), "the wire value is the enum code");
        assertEquals("normal", quality.label());

        var resolution = services.printing.IppClient.toOptionValue("600x600 dpi(3)");
        assertEquals("3", resolution.value());
        assertEquals("600x600 dpi", resolution.label());

        // Keyword attributes are already wire-ready and must pass through intact —
        // 'one-sided' is literally what IPP carries.
        var sides = services.printing.IppClient.toOptionValue("one-sided");
        assertEquals("one-sided", sides.value());
        assertEquals("one-sided", sides.label());
    }

    @Test
    void rangeOptionsAreDistinguishableFromSelects() {
        var copies = new services.printing.IppClient.JobOption(
                "copies", "Copies", java.util.List.of(), 1, 99, "1");
        var sides = new services.printing.IppClient.JobOption(
                "sides", "Sides",
                java.util.List.of(new services.printing.IppClient.OptionValue("one-sided", "one-sided")),
                null, null, "one-sided");

        // The UI branches on this: a range is a number input, because enumerating
        // copies-supported would be a ninety-nine item dropdown.
        assertTrue(copies.isRange());
        assertFalse(sides.isRange());
        assertTrue(copies.values().isEmpty(), "a range carries bounds, not values");
    }

    @Test
    void legalPaperGetsLegalGeometry() {
        var legal = PrintRenderer.PageSize.fromMedia("na_legal_8.5x14in", 300);
        // 8.5 x 14in. Falling through to A4 here is what put A4-shaped pixels in a
        // Legal tray, which this printer reported as spool-area-full and stopped on.
        assertEquals(2550, legal.width());
        assertEquals(4200, legal.height());

        // Legal and Letter share a width and differ in length; matching the wrong
        // one silently loses three inches of page.
        var letter = PrintRenderer.PageSize.fromMedia("na_letter_8.5x11in", 300);
        assertEquals(legal.width(), letter.width());
        assertTrue(legal.height() > letter.height());
    }

    @Test
    void theLoadedPaperIsUsedWhenTheCallerNamesNone() throws Exception {
        // The root cause of three rounds of failed prints: JClaw assumed A4 while
        // the printer had Legal loaded. media-ready is the printer telling us what
        // is physically in the tray, and it must win over our default.
        var caps = new services.printing.IppClient.RasterCapabilities(
                600, true, Set.of("sgray_8"), "na_legal_8.5x14in");
        var prepared = PrintFormatNegotiator.prepare(
                "hi".getBytes(StandardCharsets.UTF_8), "text/plain",
                Set.of("image/pwg-raster"), JobAttributes.DEFAULTS, caps);

        assertEquals("na_legal_8.5x14in", prepared.media(),
                "the resolved media must be declared on the job, not just rendered to");
        assertTrue(prepared.explanation().contains("na_legal_8.5x14in"), prepared.explanation());
    }

    @Test
    void anExplicitMediaRequestBeatsWhatIsLoaded() throws Exception {
        // Defaulting to the tray must not become overriding the operator: someone
        // who asks for A4 has presumably just loaded it.
        var caps = new services.printing.IppClient.RasterCapabilities(
                600, true, Set.of("sgray_8"), "na_legal_8.5x14in");
        var prepared = PrintFormatNegotiator.prepare(
                "hi".getBytes(StandardCharsets.UTF_8), "text/plain",
                Set.of("image/pwg-raster"),
                new JobAttributes(null, null, "iso_a4_210x297mm"), caps);

        assertEquals("iso_a4_210x297mm", prepared.media());
    }

    @Test
    void greyscaleRenderingIsAQuarterOfTheHeapAndAThirdOfTheWire() throws Exception {
        var canon = Set.of("image/pwg-raster");
        var caps = new services.printing.IppClient.RasterCapabilities(600, true, Set.of("sgray_8"), null);
        var text = "hello printer".getBytes(StandardCharsets.UTF_8);

        var gray = PrintFormatNegotiator.prepare(text, "text/plain", canon,
                JobAttributes.DEFAULTS, caps);
        var color = PrintFormatNegotiator.prepare(text, "text/plain", canon,
                new JobAttributes(null, "color", null), caps);

        assertEquals("image/pwg-raster", gray.format());
        assertTrue(gray.explanation().contains("600 DPI"), gray.explanation());
        assertTrue(gray.explanation().contains("greyscale"), gray.explanation());
        // Asking for colour must actually get colour, not be silently overridden.
        assertTrue(color.explanation().contains("colour"), color.explanation());
        assertTrue(gray.document().length < color.document().length,
                "greyscale should be smaller: %d vs %d"
                        .formatted(gray.document().length, color.document().length));
    }

    // ─── Negotiation ───

    @Test
    void aFormatThePrinterSupportsGoesThroughUntouched() throws Exception {
        var source = png(10, 10);
        var prepared = PrintFormatNegotiator.prepare(source, "image/png",
                Set.of("image/png", "image/jpeg"), JobAttributes.DEFAULTS);

        // Native pass-through beats re-encoding: no generation loss, and the
        // printer's own renderer is better than ours.
        assertFalse(prepared.converted());
        assertEquals("image/png", prepared.format());
        assertArrayEquals(source, prepared.document());
        assertNull(prepared.explanation());
    }

    @Test
    void textForACanonClassPrinterBecomesPwgRaster() throws Exception {
        // The exact capability set the Canon advertises.
        var canon = Set.of("application/octet-stream", "image/jpeg", "image/urf", "image/pwg-raster");
        var prepared = PrintFormatNegotiator.prepare(
                "hello printer".getBytes(StandardCharsets.UTF_8), "text/plain",
                canon, JobAttributes.DEFAULTS);

        assertTrue(prepared.converted());
        assertEquals("image/pwg-raster", prepared.format());
        assertEquals("RaS2", new String(prepared.document(), 0, 4, StandardCharsets.US_ASCII));
        assertTrue(prepared.explanation().contains("text/plain"), prepared.explanation());
    }

    @Test
    void jpegIsUsedWhenRasterIsNotOfferedAndTruncationIsAnnounced() throws Exception {
        var jpegOnly = Set.of("image/jpeg");
        var manyPages = "line\n".repeat(400);
        var prepared = PrintFormatNegotiator.prepare(
                manyPages.getBytes(StandardCharsets.UTF_8), "text/plain",
                jpegOnly, JobAttributes.DEFAULTS);

        assertEquals("image/jpeg", prepared.format());
        assertTrue(prepared.converted());
        // JPEG holds one page. Printing page 1 of 9 without saying so produces a
        // document that looks complete and is not.
        assertTrue(prepared.explanation().contains("ONLY PAGE 1"), prepared.explanation());
    }

    @Test
    void unknownCapabilitiesMeanSendAsIs() throws Exception {
        var source = "hello".getBytes(StandardCharsets.UTF_8);
        var prepared = PrintFormatNegotiator.prepare(source, "text/plain",
                Set.of(), JobAttributes.DEFAULTS);

        // Converting on a guess is worse than letting a printer that would have
        // coped decide; IPP will say so if it cannot.
        assertFalse(prepared.converted());
        assertArrayEquals(source, prepared.document());
    }

    @Test
    void aPrinterAcceptingNothingWeProduceFailsLoudly() {
        var boom = assertThrows(java.io.IOException.class, () ->
                PrintFormatNegotiator.prepare("x".getBytes(StandardCharsets.UTF_8), "text/plain",
                        Set.of("application/vnd.hp-PCL"), JobAttributes.DEFAULTS));
        // Naming what it does accept is the difference between "buy a different
        // printer" and "convert your file".
        assertTrue(boom.getMessage().contains("vnd.hp-PCL"), boom.getMessage());
    }

    @Test
    void advertisedFormatsComeFromTheMdnsPdlRecord() {
        var printer = new services.printing.DiscoveredPrinter("P", "10.0.0.5", 631,
                services.printing.PrintProtocol.IPP,
                java.util.Map.of("pdl", "application/octet-stream,image/jpeg,image/pwg-raster"));
        var formats = PrintFormatNegotiator.advertisedFormats(printer);

        assertTrue(formats.contains("image/pwg-raster"));
        assertTrue(formats.contains("image/jpeg"));
        assertEquals(3, formats.size());
        // No pdl record at all is "unknown", not "supports nothing".
        assertTrue(PrintFormatNegotiator.advertisedFormats(
                new services.printing.DiscoveredPrinter("P", "h", 631,
                        services.printing.PrintProtocol.IPP, java.util.Map.of())).isEmpty());
    }
}
