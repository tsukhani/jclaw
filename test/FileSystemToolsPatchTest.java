import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import models.Agent;
import services.AgentService;
import tools.FileSystemTools;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * applyPatch coverage for {@link FileSystemTools}: parser error reporting
 * (line-numbered malformed-patch messages), sandbox enforcement of paths
 * encoded inside the patch body (Add File / Move to escapes, skill-creator
 * guard), plan-phase validation (Add-exists, Delete-missing, chunk shape,
 * anchors, uniqueness), and mid-apply rollback.
 */
class FileSystemToolsPatchTest extends UnitTest {

    private static final String AGENT_NAME = "fstools-patch-agent";

    private FileSystemTools tool;
    private Agent agent;
    private Path workspace;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        tool = new FileSystemTools();
        agent = AgentService.create(AGENT_NAME, "openrouter", "gpt-4.1");
        workspace = AgentService.workspacePath(AGENT_NAME);
    }

    @AfterAll
    static void cleanup() {
        deleteDir(AgentService.workspacePath(AGENT_NAME));
    }

    private String applyPatch(String patch) {
        return tool.execute("{\"action\": \"applyPatch\", \"patch\": " + jsonEscape(patch) + "}", agent);
    }

    // ==================== Top-level validation ====================

    @Test
    void applyPatchWithoutPatchFieldIsRejected() {
        var result = tool.execute("""
                {"action": "applyPatch"}
                """, agent);
        assertEquals("Error: applyPatch requires a non-empty 'patch' field", result);
    }

    @Test
    void applyPatchWhitespaceOnlyPatchIsRejected() {
        var result = tool.execute("""
                {"action": "applyPatch", "patch": "   \\n  "}
                """, agent);
        assertEquals("Error: applyPatch requires a non-empty 'patch' field", result);
    }

    @Test
    void applyPatchWithNoFileOperationsIsRejected() {
        var result = applyPatch("""
                *** Begin Patch
                *** End Patch
                """);
        assertEquals("Error: patch contains no file operations", result);
    }

    // ==================== Parser errors (line-numbered) ====================

    @Test
    void applyPatchMissingBeginHeaderIsMalformed() {
        var result = applyPatch("""
                *** Update File: x.txt
                -old
                +new
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error: malformed patch at line 1"), "got: " + result);
        assertTrue(result.contains("missing '*** Begin Patch' header"), "got: " + result);
    }

    @Test
    void applyPatchUnexpectedDirectiveIsMalformed() {
        var result = applyPatch("""
                *** Begin Patch
                *** Rename File: a.txt
                *** End Patch
                """);
        assertTrue(result.startsWith("Error: malformed patch"), "got: " + result);
        assertTrue(result.contains("Unexpected directive"), "got: " + result);
        assertTrue(result.contains("Rename File"), "got: " + result);
    }

    @Test
    void applyPatchMissingEndPatchFooterIsMalformed() throws Exception {
        Files.writeString(workspace.resolve("ghost.txt"), "boo\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Delete File: ghost.txt
                """);
        assertTrue(result.startsWith("Error: malformed patch"), "got: " + result);
        assertTrue(result.contains("missing '*** End Patch' footer"), "got: " + result);
        assertTrue(Files.exists(workspace.resolve("ghost.txt")),
                "parse failure must not delete anything");
    }

    @Test
    void applyPatchAddFileBodyLineWithoutPlusIsMalformed() {
        var result = applyPatch("""
                *** Begin Patch
                *** Add File: bad.txt
                no-plus-line
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error: malformed patch at line 3"), "got: " + result);
        assertTrue(result.contains("must start with '+'"), "got: " + result);
        assertFalse(Files.exists(workspace.resolve("bad.txt")));
    }

    @Test
    void applyPatchAddFileMissingEndOfFileTerminatorIsMalformed() {
        var result = applyPatch("*** Begin Patch\n*** Add File: never.txt\n+data");
        assertTrue(result.startsWith("Error: malformed patch"), "got: " + result);
        assertTrue(result.contains("Add File missing '*** End of File' terminator"), "got: " + result);
        assertFalse(Files.exists(workspace.resolve("never.txt")));
    }

    @Test
    void applyPatchUpdateFileWithNoChunksIsMalformed() throws Exception {
        Files.writeString(workspace.resolve("empty-chunks.txt"), "x\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: empty-chunks.txt
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error: malformed patch"), "got: " + result);
        assertTrue(result.contains("has no chunks"), "got: " + result);
        assertEquals("x\n", Files.readString(workspace.resolve("empty-chunks.txt")));
    }

    @Test
    void applyPatchUpdateChunkLineWithBadPrefixIsMalformed() throws Exception {
        Files.writeString(workspace.resolve("badchunk.txt"), "x\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: badchunk.txt
                ?what
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error: malformed patch at line 3"), "got: " + result);
        assertTrue(result.contains("must start with ' ', '+', '-', or '@@'"), "got: " + result);
    }

    @Test
    void applyPatchHalfOpenAnchorLineIsNotAnAnchor() throws Exception {
        // "@@ not closed" starts with @@ but doesn't end with @@ — it is NOT an
        // anchor line, and since it doesn't carry a valid chunk prefix either,
        // the parser rejects it rather than silently treating it as an anchor.
        Files.writeString(workspace.resolve("halfanchor.txt"), "x\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: halfanchor.txt
                @@ not closed
                -x
                +y
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error: malformed patch at line 3"), "got: " + result);
        assertTrue(result.contains("must start with"), "got: " + result);
        assertEquals("x\n", Files.readString(workspace.resolve("halfanchor.txt")));
    }

    @Test
    void applyPatchToleratesLeadingBlankLinesAndBlankLinesInAddBody() throws Exception {
        // Leading blank lines before *** Begin Patch are skipped; a blank line
        // inside an Add File body becomes an empty line in the created file.
        var result = applyPatch("\n\n*** Begin Patch\n*** Add File: gap.txt\n+first\n\n+third\n*** End of File\n*** End Patch\n");
        assertTrue(result.contains("1 added"), "got: " + result);
        assertEquals("first\n\nthird", Files.readString(workspace.resolve("gap.txt")));
    }

    // ==================== Sandbox enforcement inside the patch body ====================

    @Test
    void applyPatchAddFileEscapingWorkspaceIsBlocked() {
        var result = applyPatch("""
                *** Begin Patch
                *** Add File: ../patch-escape.txt
                +evil
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.contains("escapes the workspace"), "got: " + result);
        assertFalse(Files.exists(workspace.getParent().resolve("patch-escape.txt")),
                "escaped path must never be created on disk");
    }

    @Test
    void applyPatchMoveToEscapingWorkspaceIsBlocked() throws Exception {
        Files.writeString(workspace.resolve("moveme.txt"), "content\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: moveme.txt
                *** Move to: ../stolen.txt
                -content
                +changed
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.contains("escapes the workspace"), "got: " + result);
        assertEquals("content\n", Files.readString(workspace.resolve("moveme.txt")),
                "source file must be untouched when the move target escapes");
        assertFalse(Files.exists(workspace.getParent().resolve("stolen.txt")));
    }

    @Test
    void applyPatchIntoSkillCreatorIsBlockedForNonMainAgent() {
        var result = applyPatch("""
                *** Begin Patch
                *** Add File: skills/skill-creator/hack.txt
                +payload
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.contains("read-only"), "got: " + result);
        assertFalse(Files.exists(workspace.resolve("skills/skill-creator/hack.txt")));
    }

    // ==================== Plan-phase validation ====================

    @Test
    void applyPatchAddFileThatAlreadyExistsIsRejected() throws Exception {
        Files.writeString(workspace.resolve("dup-add.txt"), "keep me\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Add File: dup-add.txt
                +clobber
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error: op #1 Add File"), "got: " + result);
        assertTrue(result.contains("file already exists"), "got: " + result);
        assertEquals("keep me\n", Files.readString(workspace.resolve("dup-add.txt")));
    }

    @Test
    void applyPatchDeleteMissingFileIsRejected() {
        var result = applyPatch("""
                *** Begin Patch
                *** Delete File: not-there.txt
                *** End Patch
                """);
        assertTrue(result.startsWith("Error: op #1 Delete File"), "got: " + result);
        assertTrue(result.contains("file does not exist"), "got: " + result);
    }

    @Test
    void applyPatchChunkWithOnlyAddLinesIsRejected() throws Exception {
        // A chunk needs at least one context or removal line to anchor the
        // edit; pure additions are ambiguous and must be rejected untouched.
        Files.writeString(workspace.resolve("addonly.txt"), "base\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: addonly.txt
                +floating addition
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.contains("no removal or context lines"), "got: " + result);
        assertEquals("base\n", Files.readString(workspace.resolve("addonly.txt")));
    }

    @Test
    void applyPatchAnchorNotFoundIsRejected() throws Exception {
        Files.writeString(workspace.resolve("anchored-miss.txt"), "alpha\nbeta\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: anchored-miss.txt
                @@ nonexistent anchor @@
                -alpha
                +ALPHA
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.contains("anchor 'nonexistent anchor' not found"), "got: " + result);
        assertEquals("alpha\nbeta\n", Files.readString(workspace.resolve("anchored-miss.txt")));
    }

    @Test
    void applyPatchAnchorDisambiguatesDuplicateContext() throws Exception {
        // The same context appears twice; the @@ anchor restricts the search
        // to the region after the anchor, so only the second copy changes.
        Files.writeString(workspace.resolve("anchored.ini"),
                "[section one]\nvalue = 0\n[section two]\nvalue = 0\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: anchored.ini
                @@ [section two] @@
                -value = 0
                +value = 1
                *** End of File
                *** End Patch
                """);
        assertTrue(result.contains("1 updated"), "got: " + result);
        assertEquals("[section one]\nvalue = 0\n[section two]\nvalue = 1\n",
                Files.readString(workspace.resolve("anchored.ini")),
                "only the occurrence after the anchor may change");
    }

    @Test
    void applyPatchUnanchoredDuplicateContextIsRejected() throws Exception {
        Files.writeString(workspace.resolve("dup-ctx.ini"),
                "[section one]\nvalue = 0\n[section two]\nvalue = 0\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: dup-ctx.ini
                -value = 0
                +value = 9
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.contains("context is not unique"), "got: " + result);
        assertTrue(result.contains("@@ anchor @@"),
                "error must suggest anchoring: " + result);
        assertEquals("[section one]\nvalue = 0\n[section two]\nvalue = 0\n",
                Files.readString(workspace.resolve("dup-ctx.ini")));
    }

    @Test
    void applyPatchEmptyAnchorBehavesAsUnanchored() throws Exception {
        // "@@ @@" carries no anchor text → treated as no anchor; a unique
        // context still applies cleanly.
        Files.writeString(workspace.resolve("empty-anchor.txt"), "solo line\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: empty-anchor.txt
                @@ @@
                -solo line
                +SOLO LINE
                *** End of File
                *** End Patch
                """);
        assertTrue(result.contains("1 updated"), "got: " + result);
        assertEquals("SOLO LINE\n", Files.readString(workspace.resolve("empty-anchor.txt")));
    }

    @Test
    void applyPatchContextLinesAnchorTheEdit() throws Exception {
        // Space-prefixed context lines participate in matching and are kept
        // verbatim in the output.
        Files.writeString(workspace.resolve("ctx.txt"), "keep\nold\nkeep2\n");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: ctx.txt
                 keep
                -old
                +new
                 keep2
                *** End of File
                *** End Patch
                """);
        assertTrue(result.contains("1 updated"), "got: " + result);
        assertEquals("keep\nnew\nkeep2\n", Files.readString(workspace.resolve("ctx.txt")));
    }

    // ==================== Mid-apply failure & rollback ====================

    @Test
    void applyPatchRollsBackCommittedOpsWhenALaterOpFailsToWrite() throws Exception {
        // Op #1 (Update) succeeds; op #2 (Add) passes plan-phase validation
        // (target doesn't exist) but fails at write time because its parent
        // "directory" is a regular file. The already-applied update must be
        // rolled back to the pre-patch content.
        Files.writeString(workspace.resolve("rollback-a.txt"), "original\n");
        Files.writeString(workspace.resolve("rollback-blocker.txt"), "i am a file");
        var result = applyPatch("""
                *** Begin Patch
                *** Update File: rollback-a.txt
                -original
                +CHANGED
                *** End of File
                *** Add File: rollback-blocker.txt/child.txt
                +nested
                *** End of File
                *** End Patch
                """);
        assertTrue(result.startsWith("Error applying Add File"), "got: " + result);
        assertEquals("original\n", Files.readString(workspace.resolve("rollback-a.txt")),
                "committed update must be rolled back after the later op fails");
        assertEquals("i am a file", Files.readString(workspace.resolve("rollback-blocker.txt")),
                "the blocking file must survive unchanged");
    }

    // ==================== Helpers ====================

    /** JSON-escape a string for embedding in a tool-call arg blob. */
    private static String jsonEscape(String raw) {
        var sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            var c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (java.io.IOException _) {}
            });
        } catch (java.io.IOException _) {}
    }
}
