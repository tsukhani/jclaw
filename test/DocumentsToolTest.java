import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.test.UnitTest;
import services.ConfigService;
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
class DocumentsToolTest extends UnitTest {

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
    void noConflict_returnsDesiredPath() {
        var desired = tmp.resolve("fresh.docx");
        var out = DocumentsTool.resolveNonConflicting(desired);
        assertEquals(desired, out);
    }

    /**
     * Single-collision suffix resolution across the filename shapes the
     * helper has to handle: regular extension, spaces-and-hyphens, no
     * extension, and the dot-file edge case (leading dot is part of the
     * base name, not an extension separator).
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "existingFile_picksFirstFreeSuffix             | report.docx                          | report-1.docx",
            "nameWithSpacesAndHyphens_suffixesBeforeExt    | Shiva Play - ENHANCED VERSION.docx   | Shiva Play - ENHANCED VERSION-1.docx",
            "noExtension_suffixesAtEnd                     | README                               | README-1",
            "hiddenDotFile_treatsLeadingDotAsPartOfBase    | .hidden                              | .hidden-1"
    })
    void resolveNonConflicting_singleCollisionShapes(String label, String fileName, String expected) throws Exception {
        var desired = tmp.resolve(fileName);
        Files.writeString(desired, "x");
        var out = DocumentsTool.resolveNonConflicting(desired);
        assertEquals(tmp.resolve(expected), out);
    }

    @Test
    void multipleExisting_skipsToNextFree() throws Exception {
        Files.writeString(tmp.resolve("report.docx"), "x");
        Files.writeString(tmp.resolve("report-1.docx"), "x");
        Files.writeString(tmp.resolve("report-2.docx"), "x");
        var out = DocumentsTool.resolveNonConflicting(tmp.resolve("report.docx"));
        assertEquals(tmp.resolve("report-3.docx"), out);
    }

    // ----- JCLAW-177: Tika TesseractOCRParser integration ---------------------

    @Test
    void readDocument_imageWithText_extractsTextViaTesseract() throws Exception {
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
    void readDocument_imageWithNoText_andProbeUnavailable_returnsClearHint() throws Exception {
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
    void readDocument_pdfWithEmbeddedImage_doesNotThrowOnInlineImageExtraction() throws Exception {
        // Regression for the JCLAW-177 follow-up: extractInlineImages=true
        // routes embedded PDF images through Tika's ImageGraphicsEngine, which
        // calls org.apache.pdfbox.tools.imageio.ImageIOUtil. That class lives
        // in the pdfbox-tools artifact; an over-eager exclude in
        // build.gradle.kts previously dropped pdfbox-tools entirely,
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
    void readDocument_scannedPdf_whenOcrToggleOff_returnsHardErrorAndSkipsTesseract() throws Exception {
        // Regression: with ocr.tesseract.enabled=false the documents tool was
        // still hitting Tesseract on scanned PDFs. Tika's AutoDetectParser
        // service-loads TesseractOCRParser by default whenever the binary is
        // on PATH, so an empty ParseContext is NOT sufficient — TesseractOCRConfig
        // skipOcr=true plus PDFParserConfig OCR_STRATEGY.NO_OCR are the load-
        // bearing settings.
        //
        // Contract: when the toggle is off and a document yields no extractable
        // text, the tool returns an "Error: ..." string (not a soft hint) so the
        // LLM treats it as a real tool failure and reliably surfaces the cause
        // to the user instead of muddling around an inline note.
        var saved = ConfigService.get("ocr.tesseract.enabled", "true");
        try {
            ConfigService.set("ocr.tesseract.enabled", "false");

            var img = renderTextPng("PDF OCR Hello World", 600, 200);
            var pdfPath = tmp.resolve("scanned-ocr-off.pdf");
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

            assertFalse(result.toLowerCase().contains("hello"),
                    "OCR must not fire when toggle is off — got OCR'd text: " + result);
            assertTrue(result.startsWith("Error:"),
                    "Expected hard 'Error:' prefix when toggle is off so the LLM surfaces the failure, got: " + result);
            assertTrue(result.contains("OCR is disabled in Settings"),
                    "Expected error to name the toggle so the user knows where to fix it, got: " + result);
        } finally {
            ConfigService.set("ocr.tesseract.enabled", saved);
        }
    }

    @Test
    void readDocument_blankImage_andProbeAvailable_omitsDiagnostic() throws Exception {
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
    void replaceFinalSegment_flatPath() {
        assertEquals("new.docx",
                DocumentsTool.replaceFinalSegment("old.docx", "new.docx"));
    }

    @Test
    void replaceFinalSegment_nestedPath() {
        assertEquals("reports/q4/new.docx",
                DocumentsTool.replaceFinalSegment("reports/q4/old.docx", "new.docx"));
    }

    // ----- execute() dispatcher branches ------------------------------------

    @Test
    void execute_unknownActionReturnsError() {
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-unknown-action");
        var result = tool.execute(
                "{\"action\":\"nonsenseAction\",\"path\":\"foo.txt\"}", agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("Unknown action"));
    }

    @Test
    void execute_writeDocumentWithoutContentReturnsError() {
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-no-content");
        var result = tool.execute(
                "{\"action\":\"writeDocument\",\"path\":\"out.html\",\"content\":\"\"}", agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("requires 'content'"), "got: " + result);
    }

    @Test
    void execute_writeDocumentUnknownFormatReturnsError() {
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-bad-format");
        var result = tool.execute(
                "{\"action\":\"writeDocument\",\"path\":\"out.xyz\",\"content\":\"# Hello\"}", agent);
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void execute_writeDocumentExplicitUnsupportedFormatReturnsError() {
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-unsupported-format");
        var result = tool.execute(
                "{\"action\":\"writeDocument\",\"path\":\"out.html\",\"content\":\"# x\",\"format\":\"txt\"}",
                agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("Unsupported format"), "got: " + result);
    }

    @Test
    void execute_renderDocumentRequiresSourcePath() {
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-render-no-src");
        var result = tool.execute(
                "{\"action\":\"renderDocument\",\"path\":\"out.html\"}", agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("sourcePath"), "got: " + result);
    }

    @Test
    void execute_renderDocumentMissingSourceFileReturnsError() {
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-render-missing");
        var result = tool.execute(
                "{\"action\":\"renderDocument\",\"path\":\"out.html\",\"sourcePath\":\"does-not-exist.md\"}",
                agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("not found"), "got: " + result);
    }

    // ----- appendDocument branches -------------------------------------------

    @Test
    void execute_appendDocumentRequiresContent() {
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-append-no-content");
        var result = tool.execute(
                "{\"action\":\"appendDocument\",\"path\":\"draft.md\",\"content\":\"\"}", agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("requires 'content'"), "got: " + result);
    }

    @Test
    void execute_appendDocumentRejectsBinaryExtension() {
        // Cannot append text to .docx/.pdf — the LLM gets a redirect to .md.
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-append-binary");
        var result = tool.execute(
                "{\"action\":\"appendDocument\",\"path\":\"final.docx\",\"content\":\"hi\"}", agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("binary format"), "got: " + result);
        assertTrue(result.contains(".md draft"), "must redirect to .md: " + result);
    }

    @Test
    void execute_appendDocumentCreatesDraftWhenMissing() {
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-append-create");
        var result = tool.execute(
                "{\"action\":\"appendDocument\",\"path\":\"new-draft.md\",\"content\":\"first chunk\"}",
                agent);
        assertTrue(result.contains("Draft created"), "got: " + result);
    }

    @Test
    void execute_appendDocumentAppendsToExisting() {
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-append-extend");
        tool.execute(
                "{\"action\":\"appendDocument\",\"path\":\"chunked.md\",\"content\":\"# header\\n\"}",
                agent);
        var result = tool.execute(
                "{\"action\":\"appendDocument\",\"path\":\"chunked.md\",\"content\":\"second chunk\"}",
                agent);
        assertTrue(result.contains("Appended"), "got: " + result);
    }

    @Test
    void execute_pathTraversalReturns400StyleError() {
        // AgentService.acquireWorkspacePath throws SecurityException for ../
        // escapes; execute() maps that to a deterministic error string.
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-traversal");
        var result = tool.execute(
                "{\"action\":\"readDocument\",\"path\":\"../../etc/passwd\"}", agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("escapes"), "must surface SecurityException as escape: " + result);
    }

    // ----- output-extension correction ---------------------------------------

    @ParameterizedTest(name = "withFormatExtension[{0}]")
    @CsvSource(delimiter = '|', value = {
            "ReplacesMdToPdf   | report.md  | pdf  | report.pdf",
            "ReplacesPdfToDocx | report.pdf | docx | report.docx",
            "AppendsWhenNoExt  | report     | pdf  | report.pdf",
            "AppendsToDotfile  | .gitignore | pdf  | .gitignore.pdf"
    })
    void withFormatExtension_replacesOrAppends(String label, String fileName, String format, String expected) {
        assertEquals(expected, DocumentsTool.withFormatExtension(fileName, format));
    }

    @Test
    void execute_writeDocumentPdfFormatToMdPathProducesPdfFile() throws Exception {
        // Reproduces the append->render footgun: an explicit pdf format handed a
        // .md target must yield a .pdf file with real PDF bytes, not a .md file
        // whose name lies about its binary contents.
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-ext-correct");
        var result = tool.execute(
                "{\"action\":\"writeDocument\",\"path\":\"report.md\","
                        + "\"format\":\"pdf\",\"content\":\"# Audit\\n\\nBody text.\"}",
                agent);

        assertFalse(result.startsWith("Error"), "write failed: " + result);
        assertTrue(result.contains("report.pdf"), "response must reference the .pdf name: " + result);
        assertFalse(result.contains("report.md"), "response must not reference the .md path: " + result);

        var workspace = services.AgentService.workspacePath("docs-ext-correct");
        assertTrue(Files.exists(workspace.resolve("report.pdf")), "report.pdf must exist");
        assertFalse(Files.exists(workspace.resolve("report.md")), "report.md must NOT be written");
        var head = Files.readAllBytes(workspace.resolve("report.pdf"));
        var magic = new String(head, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals("%PDF", magic, "written file must be a real PDF");
    }

    // ----- JCLAW-765: executeRich carries the produced document -----------------

    @Test
    void executeRich_writeDocumentCarriesProducedFileAsAttachment() {
        // A written PDF comes back on the rich result as a GeneratedAttachment (real
        // bytes, honest .pdf name + mime) so the run pipeline persists it as a
        // downloadable MessageAttachment — the seam an app uses to fetch a rendered doc.
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-rich-attach");
        var rich = tool.executeRich(
                "{\"action\":\"writeDocument\",\"path\":\"proposal.pdf\",\"content\":\"# Proposal\\n\\nBody.\"}",
                agent);

        assertFalse(rich.text().startsWith("Error"), "write failed: " + rich.text());
        assertEquals(1, rich.attachments().size(), "one produced document expected");
        var att = rich.attachments().get(0);
        assertEquals("proposal.pdf", att.filename());
        assertEquals("application/pdf", att.mimeType());
        assertTrue(att.bytes().length > 0, "attachment must carry the produced bytes");
        var magic = new String(att.bytes(), 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals("%PDF", magic, "produced attachment must be a real PDF");
    }

    @Test
    void executeRich_readDocumentCarriesNoAttachment() {
        // A non-producing action stays text-only — no spurious attachment on the result.
        var tool = new DocumentsTool();
        var agent = freshAgent("docs-rich-read");
        var rich = tool.executeRich(
                "{\"action\":\"readDocument\",\"path\":\"nope.txt\"}", agent);
        assertTrue(rich.attachments().isEmpty(), "read must not produce an attachment");
    }

    private models.Agent freshAgent(String name) {
        play.test.Fixtures.deleteDatabase();
        // Reset the on-disk workspace too so files (drafts, rendered outputs,
        // appended chunks) from a prior run can't leak into this one.
        // Fixtures.deleteDatabase() only wipes the JPA tables; the workspace
        // root sits on disk and was persisting across runs, producing
        // flakes like execute_appendDocumentCreatesDraftWhenMissing failing
        // on a re-run because new-draft.md already existed from the first
        // run and appendDocument took the "already exists" branch.
        java.nio.file.Path workspaceDir = services.AgentService.workspacePath(name);
        if (java.nio.file.Files.exists(workspaceDir)) {
            try (var paths = java.nio.file.Files.walk(workspaceDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                java.nio.file.Files.delete(p);
                            } catch (java.io.IOException e) {
                                throw new RuntimeException(
                                        "Could not clean workspace dir " + p + ": " + e.getMessage(), e);
                            }
                        });
            } catch (java.io.IOException e) {
                throw new RuntimeException(
                        "Could not walk workspace dir " + workspaceDir + ": " + e.getMessage(), e);
            }
        }
        return services.AgentService.create(name, "openrouter", "gpt-4.1");
    }
}
