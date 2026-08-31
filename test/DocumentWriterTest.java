import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import play.test.UnitTest;
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

    /**
     * JCLAW-1142: the width plumbing runs through the OOXML schema classes (CTTbl, CTRow,
     * CTTc, STTblWidth) that ship in poi-ooxml-full. Tika 3.3.2 pulled that jar transitively
     * and Tika 4.0.0 does not, so it is now declared directly — and an existence-and-size
     * check on the file cannot tell a correct table from a structurally broken one.
     */
    @Test
    void writeDocxTableCarriesGridAndCellWidths() throws IOException {
        var markdown = """
                | Name  | Value |
                |-------|-------|
                | Alpha | 1     |
                | Beta  | 2     |
                """;
        var target = tempDir.resolve("table-widths.docx");
        DocumentWriter.writeDocx(target, markdown);

        // TABLE_TOTAL_WIDTH 9000 split across 2 columns.
        int expectedColWidth = 4500;
        try (var in = Files.newInputStream(target); var doc = new XWPFDocument(in)) {
            var tables = doc.getTables();
            assertEquals(1, tables.size(), "expected exactly one table");
            var table = tables.get(0);

            assertEquals(3, table.getRows().size(), "header row plus two body rows");

            var grid = table.getCTTbl().getTblGrid();
            assertEquals(2, grid.sizeOfGridColArray(), "one gridCol per column");
            for (int i = 0; i < 2; i++) {
                // getW() is an XmlBeans union type (ST_TwipsMeasure), so it comes back as
                // Object; compare the lexical form rather than casting to a numeric type.
                assertEquals(String.valueOf(expectedColWidth),
                        String.valueOf(grid.getGridColArray(i).getW()), "gridCol " + i + " width");
            }

            String[][] expected = {{"Name", "Value"}, {"Alpha", "1"}, {"Beta", "2"}};
            for (int r = 0; r < 3; r++) {
                var row = table.getRow(r);
                assertEquals(2, row.getTableCells().size(), "row " + r + " cell count");
                for (int c = 0; c < 2; c++) {
                    var cell = row.getCell(c);
                    assertEquals(expected[r][c], cell.getText().trim(),
                            "cell text at r" + r + "c" + c);
                    var tcW = cell.getCTTc().getTcPr().getTcW();
                    assertEquals(String.valueOf(expectedColWidth), String.valueOf(tcW.getW()),
                            "cell width at r" + r + "c" + c);
                    assertEquals(STTblWidth.DXA, tcW.getType(),
                            "cell width type at r" + r + "c" + c);
                }
            }
        }
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

    @Test
    void writeDocxWithBlockQuoteVisitsBlockQuoteBranch() throws IOException {
        // Exercises DocxVisitor.visitBlockQuote which is 0%-covered. The
        // markdown engine emits a BlockQuote node for "> ..." lines.
        var markdown = """
                # Quoted

                > This is a quoted paragraph.
                > Continuation of the quote.

                Normal paragraph after the quote.
                """;
        var target = tempDir.resolve("blockquote.docx");
        DocumentWriter.writeDocx(target, markdown);

        assertTrue(Files.exists(target));
        assertTrue(Files.size(target) > 0, "DOCX with blockquote should not be empty");
    }

    @Test
    void writeDocxWithOrderedListVisitsOrderedListBranch() throws IOException {
        // Exercises DocxVisitor.visitOrderedList — same 0%-covered situation.
        var markdown = """
                # Numbered

                1. First item
                2. Second item
                3. Third item
                """;
        var target = tempDir.resolve("ordered-list.docx");
        DocumentWriter.writeDocx(target, markdown);

        assertTrue(Files.exists(target));
        assertTrue(Files.size(target) > 0);
    }

    @Test
    void writeDocxWithBulletListAndCodeFence() throws IOException {
        // Covers visit(BulletList) + visit(FencedCodeBlock) — both inner-
        // class branches that may be skipped by the simple-markdown test.
        var markdown = """
                # Mixed elements

                - Bullet one
                - Bullet two

                ```
                code-fence-line-one
                code-fence-line-two
                ```
                """;
        var target = tempDir.resolve("mixed.docx");
        DocumentWriter.writeDocx(target, markdown);

        assertTrue(Files.exists(target));
        assertTrue(Files.size(target) > 0);
    }
}
