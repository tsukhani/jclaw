import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import services.AgentService;
import tools.FileSystemTools;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Edge-case and error-path coverage for {@link FileSystemTools}: argument
 * validation, the skill-creator read-only guard, readFile/writeFile/listFiles
 * failure modes, editFile diagnostics (partial-match snippets, regex errors,
 * CRLF ambiguity), and editLines operation validation. Complements the
 * happy-path coverage in ToolSystemTest and the CRLF-splice regression tests
 * in FileSystemToolsTest.
 */
class FileSystemToolsEdgeCasesTest extends UnitTest {

    private static final String AGENT_NAME = "fstools-edge-agent";

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

    // ==================== Top-level argument validation ====================

    @Test
    void missingPathFieldIsRejectedWithActionName() {
        var result = tool.execute("""
                {"action": "readFile"}
                """, agent);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.contains("requires a 'path' field"), "got: " + result);
        assertTrue(result.contains("readFile"), "error must name the action: " + result);
    }

    @Test
    void unknownActionIsRejectedByName() {
        var result = tool.execute("""
                {"action": "chmod", "path": "whatever.txt"}
                """, agent);
        assertEquals("Error: Unknown action 'chmod'", result);
    }

    @Test
    void editFileWithoutEditsArrayIsRejected() {
        var missing = tool.execute("""
                {"action": "editFile", "path": "x.txt"}
                """, agent);
        assertEquals("Error: editFile requires an 'edits' array", missing);

        // Wrong JSON type (string instead of array) hits the same validation.
        var wrongType = tool.execute("""
                {"action": "editFile", "path": "x.txt", "edits": "not-an-array"}
                """, agent);
        assertEquals("Error: editFile requires an 'edits' array", wrongType);
    }

    @Test
    void editLinesWithoutOperationsArrayIsRejected() {
        var missing = tool.execute("""
                {"action": "editLines", "path": "x.txt"}
                """, agent);
        assertEquals("Error: editLines requires an 'operations' array", missing);

        var wrongType = tool.execute("""
                {"action": "editLines", "path": "x.txt", "operations": 42}
                """, agent);
        assertEquals("Error: editLines requires an 'operations' array", wrongType);
    }

    @Test
    void writeFileWithoutContentWritesEmptyFile() throws Exception {
        // stringArg defaults a missing 'content' to "" — the file must be
        // created empty, not error out or write the literal string "null".
        var result = tool.execute("""
                {"action": "writeFile", "path": "empty-content.txt"}
                """, agent);
        assertTrue(result.startsWith("File written successfully"), "got: " + result);
        assertEquals("", Files.readString(workspace.resolve("empty-content.txt")));
    }

    // ==================== Skill-creator read-only guard ====================

    @Test
    void skillCreatorIsReadOnlyForNonMainAgent() throws Exception {
        var scDir = workspace.resolve("skills").resolve("skill-creator");
        Files.createDirectories(scDir);
        Files.writeString(scDir.resolve("SKILL.md"), "original guard content");

        var write = tool.execute("""
                {"action": "writeFile", "path": "skills/skill-creator/SKILL.md", "content": "hacked"}
                """, agent);
        assertTrue(write.startsWith("Error"), "got: " + write);
        assertTrue(write.contains("read-only"), "got: " + write);
        assertTrue(write.contains(AGENT_NAME), "error must name the blocked agent: " + write);
        assertEquals("original guard content", Files.readString(scDir.resolve("SKILL.md")),
                "guarded file must be untouched on disk");

        // The guard covers the whole skill-creator directory, not just SKILL.md.
        var append = tool.execute("""
                {"action": "appendFile", "path": "skills/skill-creator/notes.txt", "content": "x"}
                """, agent);
        assertTrue(append.contains("read-only"), "appendFile inside skill-creator: " + append);
        assertFalse(Files.exists(scDir.resolve("notes.txt")));

        var edit = tool.execute("""
                {"action": "editFile", "path": "skills/skill-creator/SKILL.md",
                 "edits": [{"oldText": "original", "newText": "mutated"}]}
                """, agent);
        assertTrue(edit.contains("read-only"), "editFile inside skill-creator: " + edit);
        assertEquals("original guard content", Files.readString(scDir.resolve("SKILL.md")));

        // Reading is NOT mutating — the guard must not block it.
        var read = tool.execute("""
                {"action": "readFile", "path": "skills/skill-creator/SKILL.md"}
                """, agent);
        assertEquals("original guard content", read);
    }

    @Test
    void mainAgentMayWriteInsideSkillCreator() throws Exception {
        var mainAgent = AgentService.create("main", "openrouter", "gpt-4.1");
        var mainWs = AgentService.workspacePath("main");
        Files.createDirectories(mainWs.resolve("skills").resolve("skill-creator"));
        var probe = mainWs.resolve("skills").resolve("skill-creator").resolve("guard-probe.txt");
        try {
            var result = tool.execute("""
                    {"action": "writeFile", "path": "skills/skill-creator/guard-probe.txt",
                     "content": "main can write"}
                    """, mainAgent);
            assertTrue(result.startsWith("File written successfully"), "got: " + result);
            assertEquals("main can write", Files.readString(probe));
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    // ==================== readFile / writeFile / appendFile / listFiles errors ====================

    @Test
    void readFileNotFoundNamesTheFile() {
        var result = tool.execute("""
                {"action": "readFile", "path": "does-not-exist.txt"}
                """, agent);
        assertEquals("Error: File not found: does-not-exist.txt", result);
    }

    @Test
    void readFileOnDirectoryReturnsIoError() throws Exception {
        Files.createDirectories(workspace.resolve("iam-a-dir"));
        var result = tool.execute("""
                {"action": "readFile", "path": "iam-a-dir"}
                """, agent);
        assertTrue(result.startsWith("Error reading file:"), "got: " + result);
    }

    @Test
    void readFileEmptyFileReturnsEmptyString() throws Exception {
        Files.writeString(workspace.resolve("zero-bytes.txt"), "");
        var result = tool.execute("""
                {"action": "readFile", "path": "zero-bytes.txt"}
                """, agent);
        assertEquals("", result);
    }

    @Test
    void writeFileWhoseParentIsARegularFileErrors() throws Exception {
        Files.writeString(workspace.resolve("write-blocker.txt"), "i am a file");
        var result = tool.execute("""
                {"action": "writeFile", "path": "write-blocker.txt/child.txt", "content": "nested"}
                """, agent);
        assertTrue(result.startsWith("Error writing file:"), "got: " + result);
        assertEquals("i am a file", Files.readString(workspace.resolve("write-blocker.txt")),
                "the blocking file must be untouched");
    }

    @Test
    void appendFileWhoseParentIsARegularFileErrors() throws Exception {
        Files.writeString(workspace.resolve("append-blocker.txt"), "i am a file");
        var result = tool.execute("""
                {"action": "appendFile", "path": "append-blocker.txt/child.txt", "content": "nested"}
                """, agent);
        assertTrue(result.startsWith("Error appending to file:"), "got: " + result);
        assertEquals("i am a file", Files.readString(workspace.resolve("append-blocker.txt")));
    }

    @Test
    void listFilesOnRegularFileErrors() throws Exception {
        Files.writeString(workspace.resolve("plain.txt"), "x");
        var result = tool.execute("""
                {"action": "listFiles", "path": "plain.txt"}
                """, agent);
        assertEquals("Error: Not a directory: plain.txt", result);
    }

    @Test
    void listFilesEmptyDirectoryReportsEmpty() throws Exception {
        Files.createDirectories(workspace.resolve("empty-dir"));
        var result = tool.execute("""
                {"action": "listFiles", "path": "empty-dir"}
                """, agent);
        assertEquals("(empty directory)", result);
    }

    @Test
    void listFilesMarksDirectoriesWithTrailingSlashAndSorts() throws Exception {
        var dir = workspace.resolve("mixed-dir");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("b.txt"), "x");
        Files.writeString(dir.resolve("a.txt"), "x");
        var result = tool.execute("""
                {"action": "listFiles", "path": "mixed-dir"}
                """, agent);
        assertEquals("a.txt\nb.txt\nsub/", result);
    }

    // ==================== SKILL.md detection boundaries ====================

    @Test
    void skillMdAtWorkspaceRootIsNotASkillDefinition() throws Exception {
        // Version-bump pipeline only fires for skills/{name}/SKILL.md. A
        // SKILL.md at the workspace root must be written verbatim, no note.
        var result = tool.execute("""
                {"action": "writeFile", "path": "SKILL.md", "content": "# not a skill"}
                """, agent);
        assertEquals("File written successfully: SKILL.md", result,
                "no version note may be appended for a non-skill SKILL.md");
        assertEquals("# not a skill", Files.readString(workspace.resolve("SKILL.md")),
                "content must land verbatim without version injection");
    }

    @Test
    void skillMdNestedBelowSkillFolderIsNotASkillDefinition() throws Exception {
        var result = tool.execute("""
                {"action": "writeFile", "path": "skills/some-skill/docs/SKILL.md",
                 "content": "# nested doc"}
                """, agent);
        assertEquals("File written successfully: SKILL.md", result);
        assertEquals("# nested doc",
                Files.readString(workspace.resolve("skills/some-skill/docs/SKILL.md")));
    }

    // ==================== editFile error paths & diagnostics ====================

    @Test
    void editFileEntryMustBeAnObject() throws Exception {
        Files.writeString(workspace.resolve("entry-shape.txt"), "alpha\n");
        var result = tool.execute("""
                {"action": "editFile", "path": "entry-shape.txt", "edits": [42]}
                """, agent);
        assertEquals("Error: edit #1 must be an object with oldText and newText fields", result);
        assertEquals("alpha\n", Files.readString(workspace.resolve("entry-shape.txt")));
    }

    @Test
    void editFileEntryMissingNewTextIsRejected() throws Exception {
        Files.writeString(workspace.resolve("entry-fields.txt"), "alpha\n");
        var result = tool.execute("""
                {"action": "editFile", "path": "entry-fields.txt",
                 "edits": [{"oldText": "alpha"}]}
                """, agent);
        assertEquals("Error: edit #1 must include oldText and newText fields", result);
    }

    @Test
    void editFileEmptyOldTextIsRejected() throws Exception {
        Files.writeString(workspace.resolve("empty-old.txt"), "alpha\n");
        var result = tool.execute("""
                {"action": "editFile", "path": "empty-old.txt",
                 "edits": [{"oldText": "", "newText": "y"}]}
                """, agent);
        assertEquals("Error: edit #1 has an empty oldText", result);
    }

    @Test
    void editFileOnDirectoryReturnsReadError() throws Exception {
        Files.createDirectories(workspace.resolve("edit-a-dir"));
        var result = tool.execute("""
                {"action": "editFile", "path": "edit-a-dir",
                 "edits": [{"oldText": "a", "newText": "b"}]}
                """, agent);
        assertTrue(result.startsWith("Error reading file:"), "got: " + result);
    }

    @Test
    void editFileRejectsOversizeFile() throws Exception {
        var big = workspace.resolve("edit-huge.txt");
        Files.writeString(big, "A".repeat(1_048_577)); // 1 MB + 1
        try {
            var result = tool.execute("""
                    {"action": "editFile", "path": "edit-huge.txt",
                     "edits": [{"oldText": "AA", "newText": "BB"}]}
                    """, agent);
            assertTrue(result.startsWith("Error"), "got: " + result);
            assertTrue(result.contains("exceeds edit size limit"), "got: " + result);
            assertTrue(result.contains("writeFile"),
                    "error must point at writeFile for wholesale replacement: " + result);
        } finally {
            Files.deleteIfExists(big);
        }
    }

    @Test
    void editFileIdenticalReplacementNotesNoMaterialChange() throws Exception {
        Files.writeString(workspace.resolve("same.txt"), "alpha\nbeta\n");
        var result = tool.execute("""
                {"action": "editFile", "path": "same.txt",
                 "edits": [{"oldText": "beta", "newText": "beta"}]}
                """, agent);
        assertTrue(result.startsWith("File written successfully"), "got: " + result);
        assertTrue(result.contains("(no material change)"), "got: " + result);
        assertEquals("alpha\nbeta\n", Files.readString(workspace.resolve("same.txt")));
    }

    @Test
    void editFileInvalidRegexIsRejected() throws Exception {
        Files.writeString(workspace.resolve("bad-regex.txt"), "alpha\n");
        var result = tool.execute("""
                {"action": "editFile", "path": "bad-regex.txt",
                 "edits": [{"oldText": "(", "newText": "x", "regex": true}]}
                """, agent);
        assertTrue(result.startsWith("Error: edit #1 has an invalid regex"), "got: " + result);
        assertEquals("alpha\n", Files.readString(workspace.resolve("bad-regex.txt")));
    }

    @Test
    void editFileRegexZeroMatchesShowsFileHead() throws Exception {
        Files.writeString(workspace.resolve("regex-none.txt"), "hello world\n");
        var result = tool.execute("""
                {"action": "editFile", "path": "regex-none.txt",
                 "edits": [{"oldText": "qux\\\\d+", "newText": "x", "regex": true}]}
                """, agent);
        assertTrue(result.startsWith("Error: edit #1 failed"), "got: " + result);
        assertTrue(result.contains("did not match"), "got: " + result);
        assertTrue(result.contains("1 | hello world"),
                "diagnostic must show the numbered file head: " + result);
    }

    @Test
    void editFileRegexMultiMatchReportsLineNumbers() throws Exception {
        Files.writeString(workspace.resolve("regex-multi.txt"), "foo1\nfoo2\nfoo3\n");
        var result = tool.execute("""
                {"action": "editFile", "path": "regex-multi.txt",
                 "edits": [{"oldText": "foo\\\\d", "newText": "bar", "regex": true}]}
                """, agent);
        assertTrue(result.contains("matched 3 times"), "got: " + result);
        assertTrue(result.contains("[1, 2, 3]"),
                "error must list the first match line numbers: " + result);
        assertEquals("foo1\nfoo2\nfoo3\n", Files.readString(workspace.resolve("regex-multi.txt")),
                "ambiguous regex must not mutate the file");
    }

    @Test
    void editFileCrlfNormalizedAmbiguityIsRejected() throws Exception {
        // oldText matches zero times literally (file uses CRLF) but twice
        // after CRLF→LF normalization — the edit must abort, not pick one.
        Files.writeString(workspace.resolve("crlf-dup.txt"), "a\r\nb\r\na\r\nb\r\n");
        var result = tool.execute("""
                {"action": "editFile", "path": "crlf-dup.txt",
                 "edits": [{"oldText": "a\\nb", "newText": "X"}]}
                """, agent);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.contains("not unique after CRLF→LF normalization"), "got: " + result);
        assertEquals("a\r\nb\r\na\r\nb\r\n", Files.readString(workspace.resolve("crlf-dup.txt")),
                "ambiguous CRLF match must leave the file untouched");
    }

    @Test
    void editFileNotFoundShowsNearest40CharPrefixMatch() throws Exception {
        var middle = "The quick brown fox jumps over the lazy dog by the river bank";
        var sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) sb.append("padding line number ").append(i).append("\n");
        sb.append(middle).append("\n");
        for (int i = 31; i <= 60; i++) sb.append("padding line number ").append(i).append("\n");
        Files.writeString(workspace.resolve("partial40.txt"), sb.toString());

        // First 40 chars exist in the file; the tail does not.
        var result = tool.execute("""
                {"action": "editFile", "path": "partial40.txt",
                 "edits": [{"oldText": "The quick brown fox jumps over the lazy dog by the river bank BUT WRONG TAIL",
                            "newText": "x"}]}
                """, agent);
        assertTrue(result.contains("oldText not found"), "got: " + result);
        assertTrue(result.contains("Nearest partial match (first 40 chars"), "got: " + result);
        assertTrue(result.contains("at line 31"), "prefix hit is on line 31: " + result);
        assertTrue(result.contains("…"),
                "mid-file snippet must be elided on both sides: " + result);
    }

    @Test
    void editFileNotFoundFallsBackToFirstLineAnchor() throws Exception {
        Files.writeString(workspace.resolve("firstline.txt"), "alpha\nbeta\ngamma\n");
        // Shorter than 40 chars, no 20/10-char prefix hit, but the first line
        // of oldText ("beta") does exist — the diagnostic anchors on it.
        var result = tool.execute("""
                {"action": "editFile", "path": "firstline.txt",
                 "edits": [{"oldText": "beta\\nTHIS LINE DOES NOT EXIST", "newText": "x"}]}
                """, agent);
        assertTrue(result.contains("oldText not found"), "got: " + result);
        assertTrue(result.contains("Nearest partial match (first line of oldText)"), "got: " + result);
        assertTrue(result.contains("at line 2"), "'beta' lives on line 2: " + result);
    }

    @Test
    void editFileNoPartialMatchShowsNumberedHeadWithMoreLinesMarker() throws Exception {
        var sb = new StringBuilder();
        for (int i = 1; i <= 45; i++) sb.append("ln ").append(i).append("\n");
        Files.writeString(workspace.resolve("head45.txt"), sb.toString());
        var result = tool.execute("""
                {"action": "editFile", "path": "head45.txt",
                 "edits": [{"oldText": "zzz-not-there", "newText": "x"}]}
                """, agent);
        assertTrue(result.contains("No partial match found. File begins with:"), "got: " + result);
        assertTrue(result.contains("1 | ln 1"), "head must be line-numbered: " + result);
        assertTrue(result.contains("more lines)"),
                "45-line file shows only 40 lines plus a more-lines marker: " + result);
    }

    @Test
    void editFileDiagnosticIsTruncatedAtSnippetCap() throws Exception {
        // 45 long lines → the 40-line head snippet exceeds the 1500-char cap
        // and must end with the explicit truncation marker.
        var sb = new StringBuilder();
        for (int i = 1; i <= 45; i++) {
            sb.append("this is a deliberately long padding line number ").append(i)
              .append(" with extra words\n");
        }
        Files.writeString(workspace.resolve("truncated.txt"), sb.toString());
        var result = tool.execute("""
                {"action": "editFile", "path": "truncated.txt",
                 "edits": [{"oldText": "@@absent-needle@@", "newText": "x"}]}
                """, agent);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.endsWith("… (truncated)"),
                "oversize diagnostic must end with the truncation marker: " + result);
        assertTrue(result.length() <= 1600,
                "capped payload, got length " + result.length());
    }

    // ==================== editLines operation validation ====================

    @Test
    void editLinesOperationMustBeAnObject() throws Exception {
        Files.writeString(workspace.resolve("lr-shape.txt"), "one\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-shape.txt", "operations": [5]}
                """, agent);
        assertEquals("Error: operation #1 must be an object", result);
        assertEquals("one\n", Files.readString(workspace.resolve("lr-shape.txt")));
    }

    @Test
    void editLinesOperationMissingStartLineIsRejected() throws Exception {
        Files.writeString(workspace.resolve("lr-nostart.txt"), "one\ntwo\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-nostart.txt",
                 "operations": [{"op": "delete", "endLine": 2}]}
                """, agent);
        assertEquals("Error: operation #1 must include 'op' and 'startLine' fields", result);
    }

    @Test
    void editLinesNonIntegerStartLineIsRejected() throws Exception {
        Files.writeString(workspace.resolve("lr-nan.txt"), "one\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-nan.txt",
                 "operations": [{"op": "insert", "startLine": "abc", "content": "x"}]}
                """, agent);
        assertEquals("Error: operation #1 startLine must be an integer", result);
    }

    @Test
    void editLinesStartLineBelowOneIsRejected() throws Exception {
        Files.writeString(workspace.resolve("lr-zero.txt"), "one\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-zero.txt",
                 "operations": [{"op": "insert", "startLine": 0, "content": "x"}]}
                """, agent);
        assertTrue(result.contains("startLine must be ≥ 1 (got 0)"), "got: " + result);
    }

    @Test
    void editLinesReplaceRequiresEndLineAndContent() throws Exception {
        Files.writeString(workspace.resolve("lr-req.txt"), "one\ntwo\n");
        var noEnd = tool.execute("""
                {"action": "editLines", "path": "lr-req.txt",
                 "operations": [{"op": "replace", "startLine": 1, "content": "x"}]}
                """, agent);
        assertEquals("Error: operation #1 (replace) requires 'endLine'", noEnd);

        var noContent = tool.execute("""
                {"action": "editLines", "path": "lr-req.txt",
                 "operations": [{"op": "replace", "startLine": 1, "endLine": 1}]}
                """, agent);
        assertEquals("Error: operation #1 (replace) requires 'content'", noContent);
        assertEquals("one\ntwo\n", Files.readString(workspace.resolve("lr-req.txt")));
    }

    @Test
    void editLinesDeleteRequiresEndLine() throws Exception {
        Files.writeString(workspace.resolve("lr-delreq.txt"), "one\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-delreq.txt",
                 "operations": [{"op": "delete", "startLine": 1}]}
                """, agent);
        assertEquals("Error: operation #1 (delete) requires 'endLine'", result);
    }

    @Test
    void editLinesInsertRequiresContent() throws Exception {
        Files.writeString(workspace.resolve("lr-insreq.txt"), "one\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-insreq.txt",
                 "operations": [{"op": "insert", "startLine": 1}]}
                """, agent);
        assertEquals("Error: operation #1 (insert) requires 'content'", result);
    }

    @Test
    void editLinesInsertBeyondAppendSlotIsRejected() throws Exception {
        Files.writeString(workspace.resolve("lr-beyond.txt"), "one\ntwo\n");
        // lineCount 2 → max insert startLine is 3 (append); 4 must fail.
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-beyond.txt",
                 "operations": [{"op": "insert", "startLine": 4, "content": "x"}]}
                """, agent);
        assertTrue(result.contains("beyond end of file"), "got: " + result);
        assertTrue(result.contains("max allowed 3"), "got: " + result);
        assertEquals("one\ntwo\n", Files.readString(workspace.resolve("lr-beyond.txt")));
    }

    @Test
    void editLinesEndLinePastEofIsRejected() throws Exception {
        Files.writeString(workspace.resolve("lr-endpast.txt"), "one\ntwo\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-endpast.txt",
                 "operations": [{"op": "replace", "startLine": 2, "endLine": 9, "content": "x"}]}
                """, agent);
        assertTrue(result.contains("endLine 9 exceeds file length (2 lines)"), "got: " + result);
        assertEquals("one\ntwo\n", Files.readString(workspace.resolve("lr-endpast.txt")));
    }

    @Test
    void editLinesDeleteStartPastEofIsRejected() throws Exception {
        Files.writeString(workspace.resolve("lr-startpast.txt"), "only\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-startpast.txt",
                 "operations": [{"op": "delete", "startLine": 3, "endLine": 3}]}
                """, agent);
        assertTrue(result.contains("startLine 3 exceeds file length (1 lines)"), "got: " + result);
    }

    @Test
    void editLinesOnDirectoryReturnsReadError() throws Exception {
        Files.createDirectories(workspace.resolve("lr-dir"));
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-dir",
                 "operations": [{"op": "delete", "startLine": 1, "endLine": 1}]}
                """, agent);
        assertTrue(result.startsWith("Error reading file:"), "got: " + result);
    }

    // ==================== editLines EOL / trailing-newline semantics ====================

    @Test
    void editLinesFileWithoutTrailingNewlineGainsOne() throws Exception {
        Files.writeString(workspace.resolve("lr-notrail.txt"), "one\ntwo");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-notrail.txt",
                 "operations": [{"op": "replace", "startLine": 1, "endLine": 1, "content": "ONE"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("ONE\ntwo\n", Files.readString(workspace.resolve("lr-notrail.txt")),
                "editLines normalizes to a trailing newline on non-empty output");
    }

    @Test
    void editLinesDeleteAllLinesOfNoTrailingNewlineFileLeavesEmptyFile() throws Exception {
        Files.writeString(workspace.resolve("lr-wipe.txt"), "a\nb");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-wipe.txt",
                 "operations": [{"op": "delete", "startLine": 1, "endLine": 2}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertTrue(result.contains("1 delete"), "got: " + result);
        assertEquals("", Files.readString(workspace.resolve("lr-wipe.txt")),
                "deleting every line must produce a genuinely empty file");
    }

    @Test
    void editLinesDeleteAllLinesOfTrailingNewlineFileLeavesSingleNewline() throws Exception {
        // The trailing-newline convention is preserved even when all authored
        // lines are gone: the file had one, so the output keeps one.
        Files.writeString(workspace.resolve("lr-wipe-nl.txt"), "a\nb\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-wipe-nl.txt",
                 "operations": [{"op": "delete", "startLine": 1, "endLine": 2}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("\n", Files.readString(workspace.resolve("lr-wipe-nl.txt")));
    }

    @Test
    void editLinesCrlfContentIsSplitAndRejoinedWithNativeLf() throws Exception {
        Files.writeString(workspace.resolve("lr-crlfcontent.txt"), "one\ntwo\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-crlfcontent.txt",
                 "operations": [{"op": "insert", "startLine": 2, "content": "A\\r\\nB\\r\\n"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("one\nA\nB\ntwo\n", Files.readString(workspace.resolve("lr-crlfcontent.txt")),
                "CRLF content on an LF file must be re-joined with the file's native LF");
    }

    @Test
    void editLinesEmptyContentInsertIsANoOpButCounts() throws Exception {
        Files.writeString(workspace.resolve("lr-emptyins.txt"), "one\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-emptyins.txt",
                 "operations": [{"op": "insert", "startLine": 1, "content": ""}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertTrue(result.contains("1 insert"), "got: " + result);
        assertEquals("one\n", Files.readString(workspace.resolve("lr-emptyins.txt")),
                "empty content inserts no lines");
    }

    @Test
    void editLinesMixedEolFileFollowsMajorityCrlf() throws Exception {
        // 2 CRLF vs 1 LF → detected native EOL is CRLF; the rewrite joins
        // every line with CRLF (majority-EOL normalization on line edits).
        Files.writeString(workspace.resolve("lr-mixed-eol.txt"), "a\r\nb\nc\r\n");
        var result = tool.execute("""
                {"action": "editLines", "path": "lr-mixed-eol.txt",
                 "operations": [{"op": "replace", "startLine": 2, "endLine": 2, "content": "B"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("a\r\nB\r\nc\r\n", Files.readString(workspace.resolve("lr-mixed-eol.txt")));
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
