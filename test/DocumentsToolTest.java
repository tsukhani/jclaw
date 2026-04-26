import org.junit.jupiter.api.*;
import play.test.*;
import services.OcrHealthProbe;
import tools.DocumentsTool;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Covers the collision-avoidance helpers used by {@link DocumentsTool#writeDocument}.
 * Exercises the pure path logic against a real tmp dir — no Agent, workspace, or
 * DocumentWriter plumbing needed. Rendering paths are validated in manual UAT.
 */
public class DocumentsToolTest extends UnitTest {

    private Path tmp;

    @BeforeEach
    void setup() throws Exception {
        tmp = Files.createTempDirectory("docs-tool-test-");
    }

    @AfterEach
    void teardown() throws Exception {
        if (tmp != null) {
            try (var walk = Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception _) {}
                });
            }
        }
    }

    @Test
    public void noConflict_returnsDesiredPath() {
        var desired = tmp.resolve("fresh.docx");
        var out = DocumentsTool.resolveNonConflicting(desired);
        assertEquals(desired, out);
    }

    @Test
    public void existingFile_picksFirstFreeSuffix() throws Exception {
        var desired = tmp.resolve("report.docx");
        Files.writeString(desired, "x");
        var out = DocumentsTool.resolveNonConflicting(desired);
        assertEquals(tmp.resolve("report-1.docx"), out);
    }

    @Test
    public void multipleExisting_skipsToNextFree() throws Exception {
        Files.writeString(tmp.resolve("report.docx"), "x");
        Files.writeString(tmp.resolve("report-1.docx"), "x");
        Files.writeString(tmp.resolve("report-2.docx"), "x");
        var out = DocumentsTool.resolveNonConflicting(tmp.resolve("report.docx"));
        assertEquals(tmp.resolve("report-3.docx"), out);
    }

    @Test
    public void nameWithSpacesAndHyphens_suffixesBeforeExtension() throws Exception {
        var desired = tmp.resolve("Shiva Play - ENHANCED VERSION.docx");
        Files.writeString(desired, "x");
        var out = DocumentsTool.resolveNonConflicting(desired);
        assertEquals(tmp.resolve("Shiva Play - ENHANCED VERSION-1.docx"), out);
    }

    @Test
    public void noExtension_suffixesAtEnd() throws Exception {
        var desired = tmp.resolve("README");
        Files.writeString(desired, "x");
        var out = DocumentsTool.resolveNonConflicting(desired);
        assertEquals(tmp.resolve("README-1"), out);
    }

    @Test
    public void hiddenDotFile_treatsLeadingDotAsPartOfBase() throws Exception {
        // ".hidden" has no extension; the leading dot belongs to the base name.
        // A naive lastIndexOf('.') would produce " (1).hidden" — wrong.
        var desired = tmp.resolve(".hidden");
        Files.writeString(desired, "x");
        var out = DocumentsTool.resolveNonConflicting(desired);
        assertEquals(tmp.resolve(".hidden-1"), out);
    }

    // ----- JCLAW-177: Tika TesseractOCRParser integration ---------------------

    @Test
    public void readDocument_imageWithText_extractsTextViaTesseract() throws Exception {
        // Skip when the binary isn't installed — the assertion below would
        // otherwise spuriously fail on hosts that haven't run apt-get install
        // tesseract-ocr / brew install tesseract. The companion test below
        // covers the missing-tesseract code path independently.
        var probe = OcrHealthProbe.probe();
        Assumptions.assumeTrue(probe.available(),
                "tesseract not on PATH (" + probe.reason() + ") — skipping OCR happy-path test");

        var img = renderTextPng("OCR Hello World", 800, 200);
        var file = tmp.resolve("ocr-sample.png");
        ImageIO.write(img, "png", file.toFile());

        var result = DocumentsTool.readDocument(file);

        // Tesseract sometimes reads "Hello World" with stray punctuation around
        // it depending on the rendered font's hinting; substring-match is the
        // realistic assertion. A non-blank result that contains "Hello" is
        // already proof OCR fired (a no-op parser would return empty text).
        assertTrue(result.toLowerCase().contains("hello"),
                "Expected OCR to extract 'Hello' from rendered image, got: " + result);
    }

    @Test
    public void readDocument_imageWithNoText_andProbeUnavailable_returnsClearHint() throws Exception {
        // Render a blank PNG so Tika legitimately returns empty text, then
        // force the probe into the "unavailable" state so the diagnostic
        // appends. This exercises the JCLAW-177 error-surfacing path on hosts
        // where tesseract is actually installed (the developer's machine).
        var saved = OcrHealthProbe.lastResult();
        try {
            OcrHealthProbe.setForTest(new OcrHealthProbe.ProbeResult(
                    false, null, "tesseract --version exited 127: command not found"));

            var img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 20, 20);
            g.dispose();
            var file = tmp.resolve("blank.png");
            ImageIO.write(img, "png", file.toFile());

            var result = DocumentsTool.readDocument(file);

            assertTrue(result.contains("tesseract"),
                    "Expected diagnostic to name 'tesseract', got: " + result);
            assertTrue(result.contains("brew install tesseract"),
                    "Expected diagnostic to suggest macOS install command, got: " + result);
            assertTrue(result.contains("apt-get install tesseract-ocr"),
                    "Expected diagnostic to suggest Debian/Ubuntu install command, got: " + result);
        } finally {
            OcrHealthProbe.setForTest(saved);
        }
    }

    @Test
    public void readDocument_pdfWithEmbeddedImage_doesNotThrowOnInlineImageExtraction() throws Exception {
        // Regression for the JCLAW-177 follow-up: extractInlineImages=true
        // routes embedded PDF images through Tika's ImageGraphicsEngine, which
        // calls org.apache.pdfbox.tools.imageio.ImageIOUtil. That class lives
        // in the pdfbox-tools artifact; an over-eager exclude in
        // conf/dependencies.yml previously dropped pdfbox-tools entirely,
        // causing NoClassDefFoundError when an agent fed a scanned PDF to
        // the documents tool. Build a synthetic PDF with one embedded image
        // and assert the extraction completes — no exception, real result.
        var img = renderTextPng("PDF OCR Hello World", 600, 200);
        var pdfPath = tmp.resolve("scanned.pdf");
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            var pdImg = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
                    .createFromImage(pdf, img);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                cs.drawImage(pdImg, 50, 400, 500, 200);
            }
            pdf.save(pdfPath.toFile());
        }

        var result = DocumentsTool.readDocument(pdfPath);

        // Whether OCR'd text comes back depends on tesseract availability —
        // either way, the call must NOT throw NoClassDefFoundError, and the
        // response must be a normal tool string (not a stack trace).
        assertNotNull(result);
        assertFalse(result.contains("NoClassDefFoundError"),
                "PDF inline-image extraction must not throw NoClassDef, got: " + result);
        assertFalse(result.contains("ImageIOUtil"),
                "Diagnostic must not mention ImageIOUtil — that means pdfbox-tools is missing again. Got: " + result);
    }

    @Test
    public void readDocument_blankImage_andProbeAvailable_omitsDiagnostic() throws Exception {
        // Mirror image of the above: when probe says tesseract is fine, an
        // empty extraction is a real "no text in this document" — don't
        // muddy the response with a misleading install hint.
        var saved = OcrHealthProbe.lastResult();
        try {
            OcrHealthProbe.setForTest(new OcrHealthProbe.ProbeResult(
                    true, "tesseract 5.5.2 (test stub)", null));

            var img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 20, 20);
            g.dispose();
            var file = tmp.resolve("blank-with-probe-ok.png");
            ImageIO.write(img, "png", file.toFile());

            var result = DocumentsTool.readDocument(file);

            assertFalse(result.contains("tesseract is unavailable"),
                    "Diagnostic must not appear when probe says tesseract is available, got: " + result);
        } finally {
            OcrHealthProbe.setForTest(saved);
        }
    }

    private static BufferedImage renderTextPng(String text, int w, int h) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
            g.drawString(text, 30, h / 2 + 20);
        } finally {
            g.dispose();
        }
        return img;
    }

    // ----- pre-existing collision-helper coverage -----------------------------

    @Test
    public void replaceFinalSegment_flatPath() {
        assertEquals("new.docx",
                DocumentsTool.replaceFinalSegment("old.docx", "new.docx"));
    }

    @Test
    public void replaceFinalSegment_nestedPath() {
        assertEquals("reports/q4/new.docx",
                DocumentsTool.replaceFinalSegment("reports/q4/old.docx", "new.docx"));
    }
}
