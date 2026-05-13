import org.junit.jupiter.api.*;
import play.test.*;
import services.DocumentWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Smoke tests for DocumentWriter rendering methods. Verifies that each output
 * format produces a non-empty file from simple markdown input.
 */
class DocumentWriterTest extends UnitTest {

    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("docwriter-test");
    }

    @AfterEach
    void cleanup() throws IOException {
        // Clean up temp files
        if (tempDir != null && Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException _) {}
                        });
            }
        }
    }

    private static final String SIMPLE_MARKDOWN = """
            # Test Document

            This is a **bold** and *italic* test.

            ## Section Two

            - Item one
            - Item two
            - Item three

            Some `inline code` here.

            ```
            code block
            ```
            """;

    // --- HTML ---

    @Test
    void writeHtmlCreatesFile() throws IOException {
        var target = tempDir.resolve("output.html");
        DocumentWriter.writeHtml(target, SIMPLE_MARKDOWN);

        assertTrue(Files.exists(target), "HTML file should exist");
        assertTrue(Files.size(target) > 0, "HTML file should not be empty");
        var content = Files.readString(target);
        assertTrue(content.contains("<!DOCTYPE html>"), "Should contain HTML doctype");
        assertTrue(content.contains("Test Document"), "Should contain heading text");
        assertTrue(content.contains("<strong>bold</strong>"), "Should render bold");
    }

    @Test
    void writeHtmlWithMinimalMarkdown() throws IOException {
        var target = tempDir.resolve("minimal.html");
        DocumentWriter.writeHtml(target, "Hello world");

        assertTrue(Files.exists(target));
        var content = Files.readString(target);
        assertTrue(content.contains("Hello world"));
    }

    // --- DOCX ---

    @Test
    void writeDocxCreatesFile() throws IOException {
        var target = tempDir.resolve("output.docx");
        DocumentWriter.writeDocx(target, SIMPLE_MARKDOWN);

        assertTrue(Files.exists(target), "DOCX file should exist");
        assertTrue(Files.size(target) > 0, "DOCX file should not be empty");
    }

    @Test
    void writeDocxWithTableMarkdown() throws IOException {
        var markdown = """
                # Table Test

                | Name  | Value |
                |-------|-------|
                | Alpha | 1     |
                | Beta  | 2     |
                """;
        var target = tempDir.resolve("table.docx");
        DocumentWriter.writeDocx(target, markdown);

        assertTrue(Files.exists(target), "DOCX with table should exist");
        assertTrue(Files.size(target) > 0, "DOCX with table should not be empty");
    }

    // --- PDF ---

    @Test
    void writePdfCreatesFile() throws IOException {
        var target = tempDir.resolve("output.pdf");
        DocumentWriter.writePdf(target, SIMPLE_MARKDOWN);

        assertTrue(Files.exists(target), "PDF file should exist");
        assertTrue(Files.size(target) > 0, "PDF file should not be empty");
    }

    // --- Edge cases ---

    @Test
    void writeHtmlCreatesParentDirectories() throws IOException {
        var target = tempDir.resolve("sub/dir/output.html");
        DocumentWriter.writeHtml(target, "# Nested");

        assertTrue(Files.exists(target), "Should create parent directories");
    }

    @Test
    void writeDocxWithEmptyMarkdown() throws IOException {
        var target = tempDir.resolve("empty.docx");
        DocumentWriter.writeDocx(target, "");

        assertTrue(Files.exists(target), "DOCX from empty markdown should still create a file");
    }
}
