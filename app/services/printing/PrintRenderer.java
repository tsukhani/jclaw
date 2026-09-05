package services.printing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a document into page bitmaps (JCLAW-911).
 *
 * <p>Needed because printers do not agree on what they will accept. An
 * AirPrint-class inkjet — the Canon this was built against — advertises
 * {@code image/jpeg} and {@code image/pwg-raster} and nothing else useful: it
 * rejects {@code text/plain} outright and has no PDF interpreter. Sending the
 * source bytes and hoping is how a print job silently produces a blank page.
 *
 * <p>So JClaw rasterises first and lets {@link PrintFormatNegotiator} choose an
 * encoding the printer actually claims to support. Rendering is deliberately
 * plain: this is a utility print path, not a layout engine.
 */
public final class PrintRenderer {

    /**
     * Fallback resolution when the printer will not say what it wants. 300 DPI is
     * the floor for legible text; the real value comes from
     * {@code pwg-raster-document-resolution-supported}, and using it matters —
     * a 300 DPI raster sent to a 600-DPI-only Canon filled its spool and stopped it.
     */
    public static final int DEFAULT_DPI = 300;

    /** A4 in inches. */
    private static final double A4_IN_W = 8.268;
    private static final double A4_IN_H = 11.693;

    /** US Letter in inches. */
    private static final double LETTER_IN_W = 8.5;
    private static final double LETTER_IN_H = 11.0;

    /** US Legal in inches — same width as Letter, three inches longer. */
    private static final double LEGAL_IN_H = 14.0;

    /** Printable margin, in inches. Most inkjets cannot reach closer than ~5mm. */
    private static final double MARGIN_IN = 0.5;

    /** Text size in points; 11pt is comfortable for logs and code. */
    private static final int TEXT_POINTS = 11;

    /** Refuse absurd page counts rather than filling a tray. */
    private static final int MAX_PAGES = 100;

    private PrintRenderer() {}

    /**
     * Page geometry for a job, in pixels at a given resolution.
     *
     * @param dpi carried alongside so the raster header can declare the same
     *            resolution the pixels were produced at; a mismatch there is what
     *            a printer reads as a wrongly-sized page
     */
    public record PageSize(int width, int height, int dpi) {

        /**
         * Page size from a PWG media name at {@code dpi}, defaulting to A4.
         *
         * <p>Only the two common sizes are recognized. Guessing dimensions from an
         * arbitrary PWG name would produce a page that is subtly the wrong size,
         * which wastes paper more quietly than an obvious failure.
         */
        public static PageSize fromMedia(String media, int dpi) {
            var resolved = dpi > 0 ? dpi : DEFAULT_DPI;
            var name = media == null ? "" : media.toLowerCase();
            // Legal before Letter: "na_legal_8.5x14in" contains neither substring
            // of the other, but checking the longer name first keeps the intent
            // obvious if more sizes are added.
            var w = A4_IN_W;
            var h = A4_IN_H;
            if (name.contains("legal")) {
                w = LETTER_IN_W;
                h = LEGAL_IN_H;
            } else if (name.contains("letter")) {
                w = LETTER_IN_W;
                h = LETTER_IN_H;
            }
            return new PageSize((int) Math.round(w * resolved), (int) Math.round(h * resolved), resolved);
        }

        /** A4 at the default resolution, for callers with no printer to ask. */
        public static PageSize a4() {
            return fromMedia(null, DEFAULT_DPI);
        }

        int marginPx() {
            return (int) Math.round(MARGIN_IN * dpi);
        }
    }

    /**
     * Rasterise {@code document} into one bitmap per page.
     *
     * @param document     the source bytes
     * @param sourceFormat MIME type of {@code document}
     * @param page         target page geometry
     * @throws IOException if the document cannot be parsed as its declared type
     */
    public static List<BufferedImage> render(byte[] document, String sourceFormat, PageSize page)
            throws IOException {
        return render(document, sourceFormat, page, false);
    }

    /**
     * Rasterise, optionally straight to 8-bit greyscale.
     *
     * <p>The gray path is not just smaller on the wire — it is a quarter of the
     * heap. An A4 page at 600 DPI is 4961x7016; as {@code TYPE_INT_RGB} that is
     * 139 MB of {@code int[]} per page, which a multi-page job turns into an OOM.
     */
    public static List<BufferedImage> render(byte[] document, String sourceFormat, PageSize page,
                                             boolean grayscale) throws IOException {
        RENDER_GRAY.set(grayscale);
        try {
            return renderInternal(document, sourceFormat, page);
        } finally {
            RENDER_GRAY.remove();
        }
    }

    /** Per-call greyscale flag; avoids threading a parameter through every helper. */
    private static final ThreadLocal<Boolean> RENDER_GRAY = ThreadLocal.withInitial(() -> false);

    private static List<BufferedImage> renderInternal(byte[] document, String sourceFormat,
                                                      PageSize page) throws IOException {
        var format = sourceFormat == null ? "" : sourceFormat.toLowerCase();
        if (format.startsWith("image/")) {
            return List.of(fitToPage(readImage(document), page));
        }
        if (format.equals("application/pdf")) {
            return renderPdf(document, page);
        }
        // Everything else is treated as text. Reaching here with binary content
        // produces mojibake on paper rather than a crash — the same thing a
        // printer would do with it, and the caller already had a chance to
        // declare a real type.
        return renderText(new String(document, StandardCharsets.UTF_8), page);
    }

    /** Decode image bytes, failing with a useful message rather than a null. */
    static BufferedImage readImage(byte[] bytes) throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Unsupported or corrupt image — no ImageIO decoder accepted it. "
                    + "JPEG, PNG, GIF, BMP, TIFF and WebP are readable here.");
        }
        return image;
    }

    /**
     * Scale an image to fit the page, preserving aspect ratio and centring it on
     * white. Never upscales beyond the printable area, and never crops: a photo
     * that arrives at the wrong aspect gets bars, not a silently trimmed subject.
     */
    public static BufferedImage fitToPage(BufferedImage source, PageSize page) {
        var canvas = blankPage(page);
        var g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            var margin = page.marginPx();
            var maxWidth = page.width() - 2 * margin;
            var maxHeight = page.height() - 2 * margin;
            var scale = Math.min((double) maxWidth / source.getWidth(),
                    (double) maxHeight / source.getHeight());
            var w = (int) Math.round(source.getWidth() * scale);
            var h = (int) Math.round(source.getHeight() * scale);
            g.drawImage(source, (page.width() - w) / 2, (page.height() - h) / 2, w, h, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /** Render each PDF page at the page's DPI, then fit it to the target page. */
    static List<BufferedImage> renderPdf(byte[] pdf, PageSize page) throws IOException {
        var pages = new ArrayList<BufferedImage>();
        try (var document = Loader.loadPDF(pdf)) {
            var renderer = new PDFRenderer(document);
            var count = Math.min(document.getNumberOfPages(), MAX_PAGES);
            for (int i = 0; i < count; i++) {
                // Rendered at DPI then fitted, rather than rendered straight to the
                // page box: PDF pages carry their own size, and scaling after the
                // fact keeps a Letter-sized PDF from being stretched onto A4.
                pages.add(fitToPage(renderer.renderImageWithDPI(i, page.dpi(), ImageType.RGB), page));
            }
        }
        return pages;
    }

    /**
     * Lay text out into pages: monospaced, hard-wrapped at the printable width,
     * paginated at the printable height.
     *
     * <p>Monospaced on purpose. The things an agent prints here are logs, tables
     * and code, all of which depend on column alignment; a proportional font
     * silently destroys that.
     */
    public static List<BufferedImage> renderText(String text, PageSize page) {
        // Point size scaled to the render resolution: 11pt is 11/72 inch, so it
        // must grow with DPI or 600 DPI output comes out half-size.
        var font = new Font(Font.MONOSPACED, Font.PLAIN,
                (int) Math.round(TEXT_POINTS * page.dpi() / 72.0));
        var probe = blankPage(new PageSize(1, 1, page.dpi())).createGraphics();
        probe.setFont(font);
        var metrics = probe.getFontMetrics();
        var charWidth = Math.max(1, metrics.charWidth('M'));
        var lineHeight = metrics.getHeight();
        probe.dispose();

        var margin = page.marginPx();
        var usableWidth = page.width() - 2 * margin;
        var usableHeight = page.height() - 2 * margin;
        var columns = Math.max(1, usableWidth / charWidth);
        var rows = Math.max(1, usableHeight / lineHeight);

        var lines = wrap(text, columns);
        var pages = new ArrayList<BufferedImage>();
        for (int start = 0; start < lines.size() && pages.size() < MAX_PAGES; start += rows) {
            var canvas = blankPage(page);
            var g = canvas.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                g.setFont(font);
                var y = margin + metrics.getAscent();
                for (int i = start; i < Math.min(start + rows, lines.size()); i++) {
                    g.drawString(lines.get(i), margin, y);
                    y += lineHeight;
                }
            } finally {
                g.dispose();
            }
            pages.add(canvas);
        }
        // An empty document still yields one page; the caller has already refused
        // genuinely empty input, so this only catches whitespace.
        return pages.isEmpty() ? List.of(blankPage(page)) : pages;
    }

    /** Hard-wrap at {@code columns}, preserving explicit newlines. */
    public static List<String> wrap(String text, int columns) {
        var out = new ArrayList<String>();
        for (var raw : text.replace("\t", "    ").split("\n", -1)) {
            if (raw.isEmpty()) {
                out.add("");
                continue;
            }
            for (int i = 0; i < raw.length(); i += columns) {
                out.add(raw.substring(i, Math.min(raw.length(), i + columns)));
            }
        }
        return out;
    }

    /** A white page. White, not transparent — a printer renders alpha as black. */
    public static BufferedImage blankPage(PageSize page) {
        var type = Boolean.TRUE.equals(RENDER_GRAY.get())
                ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB;
        var image = new BufferedImage(page.width(), page.height(), type);
        var g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, page.width(), page.height());
        } finally {
            g.dispose();
        }
        return image;
    }
}
