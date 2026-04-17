import org.junit.jupiter.api.*;
import play.test.*;
import tools.DocumentsTool;

import java.nio.file.Files;
import java.nio.file.Path;

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
