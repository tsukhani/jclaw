import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import services.AgentService;
import tools.FileSystemTools;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Focused regression coverage for {@link FileSystemTools#editFile}'s CRLF→LF
 * normalization fallback. The defect (JCLAW-399): a literal edit that only
 * matched after normalizing the WHOLE buffer used to rewrite every CRLF in the
 * file to LF as a side effect. The fix splices newText into the ORIGINAL buffer
 * at the matched span, so line endings outside the edited region are preserved.
 */
class FileSystemToolsTest extends UnitTest {

    private FileSystemTools tool;
    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        tool = new FileSystemTools();
        agent = AgentService.create("filesystemtools-test-agent", "openrouter", "gpt-4.1");
    }

    @AfterAll
    static void cleanup() {
        deleteDir(AgentService.workspacePath("filesystemtools-test-agent"));
    }

    /**
     * A small edit on a CRLF-convention file matches via CRLF→LF normalization,
     * but must NOT convert the rest of the file to LF. Only the matched region's
     * EOLs follow newText; every other line keeps its original CRLF.
     */
    @Test
    void crlfNormalizedEditPreservesSurroundingEols() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("crlf.txt"), "alpha\r\nbeta\r\ngamma\r\ndelta\r\n");

        var result = tool.execute("""
                {"action": "editFile", "path": "crlf.txt",
                 "edits": [{"oldText": "alpha\\nbeta", "newText": "ALPHA\\nBETA"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertTrue(result.contains("CRLF"), "should note the CRLF→LF normalization: " + result);

        var read = Files.readString(workspace.resolve("crlf.txt"));
        // The matched region took on newText's LF separator.
        assertTrue(read.contains("ALPHA\nBETA"), "edited region content: " + read);
        // Crucially, the UNMATCHED lines keep their original CRLF — the whole
        // file was NOT converted to LF.
        assertTrue(read.contains("gamma\r\ndelta\r\n"),
                "lines outside the matched region must keep their CRLF: " + read);
        assertTrue(read.endsWith("delta\r\n"), "trailing CRLF preserved: " + read);
        // Exact expected splice: only the matched span lost its CRLF.
        assertEquals("ALPHA\nBETA\r\ngamma\r\ndelta\r\n", read);
    }

    /**
     * The boundary CRLF immediately after the matched region is part of the
     * surrounding text, not the match, so it survives the splice.
     */
    @Test
    void crlfNormalizedEditKeepsTrailingBoundaryCrlf() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("boundary.txt"), "one\r\ntwo\r\nthree\r\n");

        var result = tool.execute("""
                {"action": "editFile", "path": "boundary.txt",
                 "edits": [{"oldText": "one\\ntwo", "newText": "ONE\\nTWO"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);

        var read = Files.readString(workspace.resolve("boundary.txt"));
        assertEquals("ONE\nTWO\r\nthree\r\n", read);
    }

    /**
     * The non-CRLF path is unchanged: when oldText matches literally, no
     * normalization note is emitted and the file is edited verbatim.
     */
    @Test
    void literalMatchOnLfFileNeedsNoNormalization() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("lf.txt"), "alpha\nbeta\ngamma\n");

        var result = tool.execute("""
                {"action": "editFile", "path": "lf.txt",
                 "edits": [{"oldText": "beta", "newText": "BETA"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertFalse(result.contains("CRLF"), "no normalization note expected: " + result);

        var read = Files.readString(workspace.resolve("lf.txt"));
        assertEquals("alpha\nBETA\ngamma\n", read);
    }

    // ==================== Helpers ====================

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (java.io.IOException _) {}
            });
        } catch (java.io.IOException _) {}
    }
}
