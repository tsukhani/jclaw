package com.aspose.words;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shim for {@code com.aspose.words.Document} (JCLAW-451), backed by Apache PDFBox.
 * Implements exactly the two operations Cobalt's {@code Medias} drives — page
 * count and first-page thumbnail — and nothing else. See {@code package-info} for
 * why this exists instead of the commercial Aspose.Words.
 *
 * <p>Never throws: a non-PDF input (e.g. DOCX, which PDFBox can't read) degrades
 * to a single-page count and a blank thumbnail so the WhatsApp document message
 * still sends, just without a rich preview.
 */
public class Document {

    /** Thumbnail render DPI — low, since WhatsApp downscales the preview anyway. */
    private static final int THUMBNAIL_DPI = 96;

    private final byte[] bytes;

    public Document(InputStream in) {
        byte[] read;
        try {
            read = in != null ? in.readAllBytes() : new byte[0];
        } catch (Exception _) {
            read = new byte[0];
        }
        this.bytes = read;
    }

    /** First-page count of the document, ≥1. PDFBox for PDFs; 1 for anything it
     *  can't parse (Cobalt additionally clamps with {@code Math.max(1, …)}). */
    public int getPageCount() {
        try (var doc = Loader.loadPDF(bytes)) {
            return Math.max(1, doc.getNumberOfPages());
        } catch (Exception _) {
            return 1;
        }
    }

    /**
     * Render the document's first page as a JPEG into {@code out} — Cobalt uses
     * the written bytes as the WhatsApp document thumbnail. PDFBox renders PDFs;
     * any other input falls back to a 1×1 JPEG (a valid, if empty, preview). The
     * returned {@link SaveOutputParameters} is discarded by Cobalt.
     */
    public SaveOutputParameters save(OutputStream out, SaveOptions options) {
        if (!renderFirstPageJpeg(out)) {
            writeBlankJpeg(out);
        }
        return new SaveOutputParameters();
    }

    private boolean renderFirstPageJpeg(OutputStream out) {
        try (var doc = Loader.loadPDF(bytes)) {
            if (doc.getNumberOfPages() == 0) return false;
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, THUMBNAIL_DPI);
            return ImageIO.write(img, "jpg", out);
        } catch (Exception _) {
            return false;
        }
    }

    private void writeBlankJpeg(OutputStream out) {
        try {
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", out);
        } catch (Exception _) {
            // Best-effort: an unwritten stream yields an empty thumbnail, which
            // Cobalt tolerates — the document message still sends.
        }
    }
}
