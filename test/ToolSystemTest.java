import org.junit.jupiter.api.*;
import play.test.*;
import agents.ToolRegistry;
import models.Agent;
import models.Task;
import services.AgentService;
import tools.*;

import java.io.IOException;
import java.nio.file.Path;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ToolSystemTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        ToolRegistry.publish(java.util.List.of(
                new TaskTool(), new CheckListTool(), new FileSystemTools(),
                new WebFetchTool()
        ));
        agent = AgentService.create("tool-test-agent", "openrouter", "gpt-4.1");
    }

    /** Publish the base tools plus additional ones for tests that need them. */
    private static void publishWithExtras(ToolRegistry.Tool... extras) {
        var tools = new java.util.ArrayList<ToolRegistry.Tool>(java.util.List.of(
                new TaskTool(), new CheckListTool(), new FileSystemTools(),
                new WebFetchTool()
        ));
        java.util.Collections.addAll(tools, extras);
        ToolRegistry.publish(tools);
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath("tool-test-agent"));
    }

    // --- ToolRegistry ---

    @Test
    public void registryListsAllTools() {
        assertEquals(4, ToolRegistry.listTools().size());
        assertEquals(4, ToolRegistry.getToolDefs().size());
    }

    @Test
    public void executeUnknownToolReturnsError() {
        var result = ToolRegistry.execute("nonexistent_tool", "{}", agent);
        assertTrue(result.startsWith("Error: Unknown tool"));
    }

    @Test
    public void executeToolCatchesExceptions() {
        ToolRegistry.publish(java.util.List.of(new ToolRegistry.Tool() {
            public String name() { return "throwing_tool"; }
            public String description() { return "Throws"; }
            public java.util.Map<String, Object> parameters() { return java.util.Map.of(); }
            public String execute(String args, Agent a) { throw new RuntimeException("boom"); }
        }));
        var result = ToolRegistry.execute("throwing_tool", "{}", agent);
        assertTrue(result.contains("Error executing tool"));
        assertTrue(result.contains("boom"));
    }

    /**
     * Reproduces the "toolu_bdrk_" Bedrock truncation bug: the streaming
     * accumulator delivered args that were cut off mid-field because the
     * model hit its output token budget, and the downstream tool blew up
     * inside Gson with a cryptic EOFException. ToolRegistry now pre-parses
     * the JSON and returns an actionable, LLM-readable error instead of
     * dispatching the broken call.
     */
    @Test
    public void executeToolRejectsTruncatedArgsJson() {
        var sentinel = new boolean[]{false};
        ToolRegistry.publish(java.util.List.of(new ToolRegistry.Tool() {
            public String name() { return "never_called_tool"; }
            public String description() { return "stub"; }
            public java.util.Map<String, Object> parameters() { return java.util.Map.of(); }
            public String execute(String args, Agent a) { sentinel[0] = true; return "ok"; }
        }));
        // Args missing the content field and closing brace — same shape the
        // user's Bedrock log produced.
        var truncated = "{\"action\":\"writeDocument\",\"path\":\"Shiva Play - ENHANCED VERSION.docx\"";
        var result = ToolRegistry.execute("never_called_tool", truncated, agent);
        assertFalse(sentinel[0], "tool must NOT be invoked on malformed JSON");
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("malformed"), "error should name the failure mode");
        assertTrue(result.contains("smaller"), "error must include the retry hint so the LLM can self-correct");
    }

    @Test
    public void executeToolRejectsEmptyArgsJson() {
        ToolRegistry.publish(java.util.List.of(new ToolRegistry.Tool() {
            public String name() { return "empty_args_tool"; }
            public String description() { return "stub"; }
            public java.util.Map<String, Object> parameters() { return java.util.Map.of(); }
            public String execute(String args, Agent a) { return "ok"; }
        }));
        var result = ToolRegistry.execute("empty_args_tool", "", agent);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("empty"));
    }

    @Test
    public void isTruncationFinishMatchesBothSpellings() {
        // Guard the finish_reason set. Both OpenAI-compatible "length" and
        // Anthropic-native "max_tokens" must trip the truncation path — the
        // second was added to fix OpenRouter's Bedrock route, which passes
        // the Anthropic spelling through verbatim.
        assertTrue(agents.AgentRunner.isTruncationFinish("length"));
        assertTrue(agents.AgentRunner.isTruncationFinish("max_tokens"));
        assertFalse(agents.AgentRunner.isTruncationFinish("stop"));
        assertFalse(agents.AgentRunner.isTruncationFinish("tool_calls"));
        assertFalse(agents.AgentRunner.isTruncationFinish(null));
    }

    // --- TaskTool ---

    @Test
    public void taskToolCreateTask() {
        var result = ToolRegistry.execute("task_manager",
                """
                {"action": "createTask", "name": "test-task", "description": "Do something"}
                """, agent);
        assertTrue(result.contains("created and queued"));
        var tasks = Task.findPendingDue();
        assertEquals(1, tasks.size());
        assertEquals("test-task", tasks.getFirst().name);
    }

    @Test
    public void taskToolScheduleRecurring() {
        var result = ToolRegistry.execute("task_manager",
                """
                {"action": "scheduleRecurringTask", "name": "daily-report", "description": "Generate report", "cronExpression": "0 9 * * *"}
                """, agent);
        assertTrue(result.contains("Recurring task"));
        var recurring = Task.findRecurring();
        assertEquals(1, recurring.size());
    }

    @Test
    public void taskToolListRecurring() {
        ToolRegistry.execute("task_manager",
                """
                {"action": "scheduleRecurringTask", "name": "task-1", "description": "First task", "cronExpression": "0 9 * * *"}
                """, agent);
        var result = ToolRegistry.execute("task_manager",
                """
                {"action": "listRecurringTasks"}
                """, agent);
        assertTrue(result.contains("task-1"));
    }

    // --- CheckListTool ---

    @Test
    public void checklistValidInput() {
        var result = ToolRegistry.execute("checklist",
                """
                {"items": [
                    {"content": "Step 1", "status": "completed", "activeForm": "Completing step 1"},
                    {"content": "Step 2", "status": "in_progress", "activeForm": "Working on step 2"},
                    {"content": "Step 3", "status": "pending", "activeForm": "Will do step 3"}
                ]}
                """, agent);
        assertTrue(result.contains("successfully"));
    }

    @Test
    public void checklistRejectsMultipleInProgress() {
        var result = ToolRegistry.execute("checklist",
                """
                {"items": [
                    {"content": "Step 1", "status": "in_progress", "activeForm": "Doing 1"},
                    {"content": "Step 2", "status": "in_progress", "activeForm": "Doing 2"}
                ]}
                """, agent);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("At most one"));
    }

    @Test
    public void checklistAllowsZeroInProgress() {
        // Parity with OpenClaw update_plan: at-most-one semantics, not exactly-one.
        // Needed so agents can submit initial "all pending" and final "all completed" states.
        var result = ToolRegistry.execute("checklist",
                """
                {"items": [
                    {"content": "Step 1", "status": "completed", "activeForm": "Did 1"},
                    {"content": "Step 2", "status": "completed", "activeForm": "Did 2"}
                ]}
                """, agent);
        assertTrue(result.contains("successfully"), "got: " + result);
    }

    // --- FileSystemTools ---

    @Test
    public void fileSystemReadFile() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "AGENT.md"}
                """, agent);
        assertTrue(result.contains("Agent Instructions"));
    }

    @Test
    public void fileSystemWriteAndRead() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "notes.txt", "content": "Hello from tool"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "notes.txt"}
                """, agent);
        assertEquals("Hello from tool", result);
    }

    @Test
    public void fileSystemListFiles() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "listFiles", "path": "."}
                """, agent);
        assertTrue(result.contains("AGENT.md"));
        assertTrue(result.contains("skills/"));
    }

    @Test
    public void fileSystemPathTraversalBlocked() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "../../etc/passwd"}
                """, agent);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("escapes"));
    }

    @Test
    public void fileSystemReadFileRejectsOversizeFile() throws Exception {
        // readFile caps at 1 MB; larger files must be rejected with a pointer
        // to the documents tool instead of silently truncating (which would
        // feed the agent half a file and make diffs nonsensical).
        var workspace = services.AgentService.workspacePath(agent.name);
        var bigFile = workspace.resolve("huge.txt");
        java.nio.file.Files.createDirectories(workspace);
        // 1_048_577 bytes = 1 MB + 1
        var content = new byte[1_048_577];
        java.util.Arrays.fill(content, (byte) 'A');
        java.nio.file.Files.write(bigFile, content);
        try {
            var result = ToolRegistry.execute("filesystem",
                    """
                    {"action": "readFile", "path": "huge.txt"}
                    """, agent);
            assertTrue(result.startsWith("Error"),
                    "oversize file must surface an error, not truncated content");
            assertTrue(result.contains("exceeds read limit"),
                    "error must explain the cap, got: " + result);
            assertTrue(result.contains("documents"),
                    "error must point the agent at the documents tool");
        } finally {
            java.nio.file.Files.deleteIfExists(bigFile);
        }
    }

    @Test
    public void fileSystemAppendCreatesMissingFile() {
        // appendFile on a non-existent path should create it, letting the LLM
        // start a chunked build without first calling writeFile.
        ToolRegistry.execute("filesystem",
                """
                {"action": "appendFile", "path": "append-new.txt", "content": "first chunk"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "append-new.txt"}
                """, agent);
        assertEquals("first chunk", result);
    }

    @Test
    public void fileSystemAppendConcatenates() {
        // The canonical "chunked build" flow: one writeFile, multiple appends.
        // The final readFile must return the chunks in call order with no
        // separator magic — the LLM controls newlines via the content strings.
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "chunks.md", "content": "# Part 1\\n"}
                """, agent);
        ToolRegistry.execute("filesystem",
                """
                {"action": "appendFile", "path": "chunks.md", "content": "# Part 2\\n"}
                """, agent);
        ToolRegistry.execute("filesystem",
                """
                {"action": "appendFile", "path": "chunks.md", "content": "# Part 3\\n"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "chunks.md"}
                """, agent);
        assertEquals("# Part 1\n# Part 2\n# Part 3\n", result);
    }

    @Test
    public void documentsRenderFromMarkdownSource() throws Exception {
        // End-to-end of the large-doc authoring pattern: draft markdown in the
        // workspace via writeFile + appendFile, then render once through the
        // documents tool. Proves the source path is read, the target is
        // written, and the non-conflict auto-rename still applies.
        publishWithExtras(new tools.DocumentsTool());
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "draft.md", "content": "# Title\\n\\nPart one.\\n"}
                """, agent);
        ToolRegistry.execute("filesystem",
                """
                {"action": "appendFile", "path": "draft.md", "content": "\\nPart two.\\n"}
                """, agent);
        var result = ToolRegistry.execute("documents",
                """
                {"action": "renderDocument", "sourcePath": "draft.md", "path": "out.html"}
                """, agent);
        assertTrue(result.contains("Document written"), "expected success: " + result);
        assertTrue(result.contains("out.html"));
        var workspace = AgentService.workspacePath(agent.name);
        var rendered = workspace.resolve("out.html");
        assertTrue(Files.exists(rendered), "rendered file should exist on disk");
        var body = Files.readString(rendered);
        assertTrue(body.contains("Title"));
        assertTrue(body.contains("Part one"));
        assertTrue(body.contains("Part two"));
    }

    @Test
    public void documentsAppendBuildsDraft() {
        publishWithExtras(new tools.DocumentsTool());
        var r1 = ToolRegistry.execute("documents",
                """
                {"action": "appendDocument", "path": "play.md", "content": "# Act 1\\n"}
                """, agent);
        assertTrue(r1.contains("Draft created"), "expected creation: " + r1);
        var r2 = ToolRegistry.execute("documents",
                """
                {"action": "appendDocument", "path": "play.md", "content": "# Act 2\\n"}
                """, agent);
        assertTrue(r2.contains("Appended"), "expected append: " + r2);
        // Now render the accumulated draft
        var r3 = ToolRegistry.execute("documents",
                """
                {"action": "renderDocument", "sourcePath": "play.md", "path": "play.html"}
                """, agent);
        assertTrue(r3.contains("Document written"), "expected render success: " + r3);
    }

    @Test
    public void documentsAppendRejectsBinaryExtension() {
        publishWithExtras(new tools.DocumentsTool());
        var result = ToolRegistry.execute("documents",
                """
                {"action": "appendDocument", "path": "Shiva Play.docx", "content": "text"}
                """, agent);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains(".md"), "error should suggest a .md draft path");
        assertTrue(result.contains("renderDocument"));
    }

    @Test
    public void documentsAppendFileAliasWorks() {
        // The LLM sent "appendFile" to the documents tool in the real failure.
        // Verify the alias routes to the same appendDocument handler.
        publishWithExtras(new tools.DocumentsTool());
        var result = ToolRegistry.execute("documents",
                """
                {"action": "appendFile", "path": "notes.md", "content": "hello"}
                """, agent);
        assertTrue(result.contains("Draft created"), "appendFile alias should work: " + result);
    }

    @Test
    public void documentsRenderMissingSourcePath() {
        publishWithExtras(new tools.DocumentsTool());
        var result = ToolRegistry.execute("documents",
                """
                {"action": "renderDocument", "path": "out.docx"}
                """, agent);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("sourcePath"));
    }

    @Test
    public void documentsRenderSourcePathNotFound() {
        publishWithExtras(new tools.DocumentsTool());
        var result = ToolRegistry.execute("documents",
                """
                {"action": "renderDocument", "sourcePath": "does-not-exist.md", "path": "out.html"}
                """, agent);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("not found"));
    }

    @Test
    public void fileSystemAppendRejectsSkillDefinitionFile() {
        // SKILL.md files route through SkillLoader.finalizeSkillMdWrite for
        // version bumps — appending bypasses that pipeline, so it's rejected
        // rather than silently breaking skill versioning.
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "appendFile", "path": "skills/test-skill/SKILL.md", "content": "extra\\n"}
                """, agent);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("SKILL.md"));
        assertTrue(result.contains("writeFile"));
    }

    // --- Symlink and obfuscated-traversal coverage for the canonical
    //     containment helpers in AgentService. The lexical-only validator
    //     used to allow a workspace-internal symlink whose target was
    //     outside the workspace; the canonical layer (toRealPath) catches
    //     it now. ---

    @Test
    public void fileSystemSymlinkEscapeBlocked() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.createDirectories(workspace);
        var outside = Files.createTempDirectory("jclaw-symlink-test-");
        Files.writeString(outside.resolve("secret.txt"), "should not be readable");
        var link = workspace.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
            var result = ToolRegistry.execute("filesystem",
                    "{\"action\": \"readFile\", \"path\": \"escape/secret.txt\"}",
                    agent);
            assertTrue(result.contains("Error"), "symlink escape must be rejected");
            assertTrue(result.contains("escapes"), "error must mention escape");
        } finally {
            Files.deleteIfExists(link);
            Files.walk(outside).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception _) {} });
        }
    }

    @Test
    public void resolveContainedRejectsObfuscatedTraversal() {
        // The old serveWorkspaceFile substring check let "./../../etc/passwd"
        // through because it does contain ".." but the normalized path was
        // never compared against the workspace root. Pin that down.
        assertNull(AgentService.resolveWorkspacePath(agent.name, "./../../etc/passwd"));
        assertNull(AgentService.resolveWorkspacePath(agent.name, "../../etc/passwd"));
        assertNull(AgentService.resolveWorkspacePath(agent.name, "/etc/passwd"));
    }

    @Test
    public void acquireWorkspacePathThrowsOnEscape() {
        assertThrows(SecurityException.class,
                () -> AgentService.acquireWorkspacePath(agent.name, "../../etc/passwd"));
        assertThrows(SecurityException.class,
                () -> AgentService.acquireWorkspacePath(agent.name, "/etc/passwd"));
    }

    @Test
    public void acquireWorkspacePathAcceptsLegitimateRelativePath() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.createDirectories(workspace);
        var hello = workspace.resolve("hello.txt");
        Files.writeString(hello, "world");
        try {
            var path = AgentService.acquireWorkspacePath(agent.name, "hello.txt");
            assertNotNull(path);
            assertEquals("world", Files.readString(path));
        } finally {
            Files.deleteIfExists(hello);
        }
    }

    @Test
    public void fileSystemHardlinkAliasingBlocked() throws Exception {
        // Hardlinks bypass the symlink check because there's no "link" to
        // follow — both names point to the same inode at the FS level. The
        // sandbox detects this via Files.getAttribute("unix:nlink") and
        // rejects any in-workspace path whose inode has more than one link.
        //
        // Setup: a "secret" file lives in a sibling-of-workspace directory
        // (same filesystem, so Files.createLink works — hardlinks can't cross
        // mount points). The attacker hardlinks it into the workspace and
        // tries to read it via FileSystemTools.
        var workspace = AgentService.workspacePath(agent.name);
        Files.createDirectories(workspace);
        var sibling = workspace.getParent().resolve("hardlink-test-outside");
        Files.createDirectories(sibling);
        var outsideSecret = sibling.resolve("secret.txt");
        Files.writeString(outsideSecret, "should not leak");
        var insideLink = workspace.resolve("escape.txt");
        try {
            Files.createLink(insideLink, outsideSecret);
            var result = ToolRegistry.execute("filesystem",
                    "{\"action\": \"readFile\", \"path\": \"escape.txt\"}",
                    agent);
            assertTrue(result.contains("Error"), "hardlink read must be rejected");
            assertTrue(result.contains("hardlink") || result.contains("nlink"),
                    "error must mention hardlink/nlink so the cause is obvious");
        } finally {
            Files.deleteIfExists(insideLink);
            Files.deleteIfExists(outsideSecret);
            Files.deleteIfExists(sibling);
        }
    }

    // --- editFile ---

    @Test
    public void editFileSingleReplacement() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "edit.txt", "content": "hello world"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "edit.txt",
                 "edits": [{"oldText": "world", "newText": "there"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);

        var read = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "edit.txt"}
                """, agent);
        assertEquals("hello there", read);
    }

    @Test
    public void editFileBatchAtomic() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "atomic.txt", "content": "alpha\\nbeta\\ngamma"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "atomic.txt",
                 "edits": [
                    {"oldText": "alpha", "newText": "ALPHA"},
                    {"oldText": "beta", "newText": "BETA"},
                    {"oldText": "nonexistent", "newText": "nope"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("Error"), "third edit should abort the batch: " + result);
        assertTrue(result.contains("#3"), "error should name the failing edit index: " + result);

        var read = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "atomic.txt"}
                """, agent);
        assertEquals("alpha\nbeta\ngamma", read, "file must be unchanged on partial failure");
    }

    @Test
    public void editFileRequiresUniqueMatch() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "dup.txt", "content": "foo\\nfoo\\nfoo"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "dup.txt",
                 "edits": [{"oldText": "foo", "newText": "bar"}]}
                """, agent);
        assertTrue(result.contains("not unique"), "got: " + result);
        assertTrue(result.contains("3"), "should mention the occurrence count");
    }

    @Test
    public void editFileDiagnosticSnippetCapped() {
        var big = "line A\n".repeat(60);
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "missing.txt", "content": "%s"}
                """.formatted(big.replace("\n", "\\n")), agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "missing.txt",
                 "edits": [{"oldText": "a phrase that is definitely not present", "newText": "x"}]}
                """, agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("not found") || result.contains("No partial match"));
        assertTrue(result.length() <= 1600, "error payload must stay under ~1500-char cap: " + result.length());
    }

    @Test
    public void editFileCrlfFallback() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("crlf.txt"), "alpha\r\nbeta\r\ngamma\r\n");
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "crlf.txt",
                 "edits": [{"oldText": "alpha\\nbeta", "newText": "ALPHA\\nBETA"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertTrue(result.contains("CRLF"), "should note the CRLF→LF normalization: " + result);

        var read = Files.readString(workspace.resolve("crlf.txt"));
        assertTrue(read.contains("ALPHA\nBETA"), "edited content: " + read);
    }

    @Test
    public void editFileEmptyEditsRejected() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "empty-edits.txt", "content": "x"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "empty-edits.txt", "edits": []}
                """, agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("non-empty"));
    }

    @Test
    public void editFileRegexMode() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "regex.txt", "content": "version=1.2.3"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "regex.txt",
                 "edits": [{"oldText": "version=(\\\\d+)\\\\.(\\\\d+)\\\\.(\\\\d+)",
                            "newText": "version=$1.$2.99",
                            "regex": true}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        var read = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "regex.txt"}
                """, agent);
        assertEquals("version=1.2.99", read);
    }

    @Test
    public void editFileRegexUniquenessEnforced() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "regex-dup.txt", "content": "foo 1\\nfoo 2\\nfoo 3"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "regex-dup.txt",
                 "edits": [{"oldText": "foo \\\\d", "newText": "bar", "regex": true}]}
                """, agent);
        assertTrue(result.contains("matched 3 times") || result.contains("matched 3"),
                "regex match-count enforcement: " + result);
    }

    @Test
    public void editFilePathTraversalBlocked() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "../../etc/passwd",
                 "edits": [{"oldText": "root", "newText": "nope"}]}
                """, agent);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("escapes"));
    }

    @Test
    public void editFileBumpsSkillMdVersionOnce() throws Exception {
        // Create a skill via writeFile, then make a material edit via editFile, and
        // assert the version incremented by exactly one patch level.
        var skillDir = AgentService.workspacePath(agent.name).resolve("skills").resolve("bump-test");
        Files.createDirectories(skillDir);
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "skills/bump-test/SKILL.md",
                 "content": "---\\nname: bump-test\\ndescription: x\\n---\\n# Title\\nOld body"}
                """, agent);
        var v1 = Files.readString(skillDir.resolve("SKILL.md"));
        assertTrue(v1.contains("version: 1.0.0"), "initial version: " + v1);

        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "skills/bump-test/SKILL.md",
                 "edits": [{"oldText": "Old body", "newText": "New body"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertTrue(result.contains("1.0.0 → 1.0.1"), "exactly one patch bump: " + result);

        var v2 = Files.readString(skillDir.resolve("SKILL.md"));
        assertTrue(v2.contains("version: 1.0.1"));
        assertTrue(v2.contains("New body"));
    }

    // --- Explicit version promotion (LLM-supplied version: in frontmatter) ---

    @Test
    public void editFilePromotesVersionWhenLlmRequests() throws Exception {
        // Create a skill, then edit it with an LLM-supplied version that jumps past the
        // auto-bump target. The explicit value must win.
        var skillDir = AgentService.workspacePath(agent.name).resolve("skills").resolve("promote-major");
        Files.createDirectories(skillDir);
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "skills/promote-major/SKILL.md",
                 "content": "---\\nname: promote-major\\ndescription: x\\n---\\n# Title\\nOld body"}
                """, agent);
        var v1 = Files.readString(skillDir.resolve("SKILL.md"));
        assertTrue(v1.contains("version: 1.0.0"));

        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "skills/promote-major/SKILL.md",
                 "edits": [
                    {"oldText": "version: 1.0.0", "newText": "version: 2.0.0"},
                    {"oldText": "Old body", "newText": "New body"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertTrue(result.contains("1.0.0 → 2.0.0"), "should show explicit promotion: " + result);

        var v2 = Files.readString(skillDir.resolve("SKILL.md"));
        assertTrue(v2.contains("version: 2.0.0"), "on-disk: " + v2);
        assertTrue(v2.contains("New body"));
    }

    @Test
    public void editFileVersionOnlyPromotion() throws Exception {
        // Edit only the version line — no body change. The auto path would reinstate the
        // old version because contentDiffersIgnoringVersion returns false. The explicit
        // LLM version must override that and land as the final value.
        var skillDir = AgentService.workspacePath(agent.name).resolve("skills").resolve("version-only");
        Files.createDirectories(skillDir);
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "skills/version-only/SKILL.md",
                 "content": "---\\nname: version-only\\ndescription: x\\n---\\n# Title\\nBody"}
                """, agent);
        assertTrue(Files.readString(skillDir.resolve("SKILL.md")).contains("version: 1.0.0"));

        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "skills/version-only/SKILL.md",
                 "edits": [{"oldText": "version: 1.0.0", "newText": "version: 1.5.0"}]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);

        var v = Files.readString(skillDir.resolve("SKILL.md"));
        assertTrue(v.contains("version: 1.5.0"), "version-only promotion must land: " + v);
        assertTrue(v.contains("Body"), "body content preserved");
    }

    @Test
    public void editFileRejectsVersionDowngrade() throws Exception {
        // LLM attempts to downgrade. The auto-bump must win.
        var skillDir = AgentService.workspacePath(agent.name).resolve("skills").resolve("no-downgrade");
        Files.createDirectories(skillDir);
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "skills/no-downgrade/SKILL.md",
                 "content": "---\\nname: no-downgrade\\ndescription: x\\n---\\n# Title\\nOriginal"}
                """, agent);

        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "skills/no-downgrade/SKILL.md",
                 "edits": [
                    {"oldText": "version: 1.0.0", "newText": "version: 0.5.0"},
                    {"oldText": "Original", "newText": "Updated"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertTrue(result.contains("1.0.0 → 1.0.1"), "downgrade must fall back to auto-bump: " + result);

        var v = Files.readString(skillDir.resolve("SKILL.md"));
        assertTrue(v.contains("version: 1.0.1"), "on-disk must be auto-bumped: " + v);
        assertFalse(v.contains("0.5.0"), "downgrade must not land");
    }

    @Test
    public void editFileRejectsEqualVersionAttempt() throws Exception {
        // LLM writes a version equal to the auto-bump target. Strict > rule means ties
        // collapse to the auto path.
        var skillDir = AgentService.workspacePath(agent.name).resolve("skills").resolve("equal-version");
        Files.createDirectories(skillDir);
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "skills/equal-version/SKILL.md",
                 "content": "---\\nname: equal-version\\ndescription: x\\n---\\n# Title\\nOriginal"}
                """, agent);

        // Auto bump of 1.0.0 is 1.0.1 — an LLM that writes exactly 1.0.1 should tie and
        // the auto path takes over (no distinction in the final result).
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "skills/equal-version/SKILL.md",
                 "edits": [
                    {"oldText": "version: 1.0.0", "newText": "version: 1.0.1"},
                    {"oldText": "Original", "newText": "Updated"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertTrue(result.contains("1.0.0 → 1.0.1"));
        assertTrue(Files.readString(skillDir.resolve("SKILL.md")).contains("version: 1.0.1"));
    }

    @Test
    public void editFileIgnoresMalformedExplicitVersion() throws Exception {
        // Malformed LLM version — parseVersion coerces to 0.0.0, well below the auto
        // target, so auto wins.
        var skillDir = AgentService.workspacePath(agent.name).resolve("skills").resolve("malformed-version");
        Files.createDirectories(skillDir);
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "skills/malformed-version/SKILL.md",
                 "content": "---\\nname: malformed-version\\ndescription: x\\n---\\n# Title\\nOriginal"}
                """, agent);

        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "skills/malformed-version/SKILL.md",
                 "edits": [
                    {"oldText": "version: 1.0.0", "newText": "version: stable"},
                    {"oldText": "Original", "newText": "Updated"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertTrue(result.contains("1.0.0 → 1.0.1"), "malformed must fall back to auto-bump: " + result);
        assertTrue(Files.readString(skillDir.resolve("SKILL.md")).contains("version: 1.0.1"));
    }

    @Test
    public void writeFileFreshSkillHonorsExplicitHigherVersion() throws Exception {
        // Fresh-skill branch: LLM supplies version: 2.0.0 in the very first writeFile.
        // Should land as 2.0.0, not the 1.0.0 floor.
        var skillDir = AgentService.workspacePath(agent.name).resolve("skills").resolve("fresh-high");
        Files.createDirectories(skillDir);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "skills/fresh-high/SKILL.md",
                 "content": "---\\nname: fresh-high\\ndescription: x\\nversion: 2.0.0\\n---\\n# Title"}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        var v = Files.readString(skillDir.resolve("SKILL.md"));
        assertTrue(v.contains("version: 2.0.0"), "explicit high version on fresh skill: " + v);
    }

    @Test
    public void writeFileFreshSkillFloorsAtOneZeroZero() throws Exception {
        // Fresh-skill floor: LLM writes version: 0.5.0. Must be coerced to 1.0.0.
        var skillDir = AgentService.workspacePath(agent.name).resolve("skills").resolve("fresh-low");
        Files.createDirectories(skillDir);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "skills/fresh-low/SKILL.md",
                 "content": "---\\nname: fresh-low\\ndescription: x\\nversion: 0.5.0\\n---\\n# Title"}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        var v = Files.readString(skillDir.resolve("SKILL.md"));
        assertTrue(v.contains("version: 1.0.0"), "fresh floor must pin to 1.0.0: " + v);
        assertFalse(v.contains("0.5.0"));
    }

    @Test
    public void editFileMissingFileErrors() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editFile", "path": "nonexistent.txt",
                 "edits": [{"oldText": "x", "newText": "y"}]}
                """, agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("not found"));
    }

    // --- editLines ---

    @Test
    public void editLinesReplaceRange() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("lr-replace.txt"), "one\ntwo\nthree\nfour\n");
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-replace.txt",
                 "operations": [
                   {"op": "replace", "startLine": 2, "endLine": 3, "content": "TWO\\nTHREE"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("one\nTWO\nTHREE\nfour\n", Files.readString(workspace.resolve("lr-replace.txt")));
    }

    @Test
    public void editLinesInsertBeforeLine() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("lr-insert.txt"), "one\ntwo\nthree\n");
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-insert.txt",
                 "operations": [
                   {"op": "insert", "startLine": 2, "content": "BETWEEN\\nANOTHER"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("one\nBETWEEN\nANOTHER\ntwo\nthree\n",
                Files.readString(workspace.resolve("lr-insert.txt")));
    }

    @Test
    public void editLinesInsertAppendsAtEnd() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("lr-append.txt"), "one\ntwo\n");
        // startLine = lineCount + 1 means append after the last line
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-append.txt",
                 "operations": [
                   {"op": "insert", "startLine": 3, "content": "three"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("one\ntwo\nthree\n", Files.readString(workspace.resolve("lr-append.txt")));
    }

    @Test
    public void editLinesDeleteRange() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("lr-del.txt"), "alpha\nbeta\ngamma\ndelta\n");
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-del.txt",
                 "operations": [
                   {"op": "delete", "startLine": 2, "endLine": 3}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("alpha\ndelta\n", Files.readString(workspace.resolve("lr-del.txt")));
    }

    @Test
    public void editLinesBottomUpOrdering() throws Exception {
        // Two operations referenced against ORIGINAL line numbers should both apply
        // cleanly — even though applying them top-to-bottom would shift later indices.
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("lr-multi.txt"), "a\nb\nc\nd\ne\n");
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-multi.txt",
                 "operations": [
                   {"op": "replace", "startLine": 2, "endLine": 2, "content": "B"},
                   {"op": "delete",  "startLine": 4, "endLine": 5}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("a\nB\nc\n", Files.readString(workspace.resolve("lr-multi.txt")));
    }

    @Test
    public void editLinesPreservesCrlfLineEndings() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        // Windows-authored file: CRLF everywhere.
        Files.writeString(workspace.resolve("lr-crlf.txt"), "one\r\ntwo\r\nthree\r\n");
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-crlf.txt",
                 "operations": [
                   {"op": "replace", "startLine": 2, "endLine": 2, "content": "TWO"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("File written"), "got: " + result);
        assertEquals("one\r\nTWO\r\nthree\r\n",
                Files.readString(workspace.resolve("lr-crlf.txt")),
                "native CRLF line endings must be preserved on write");
    }

    @Test
    public void editLinesValidatesOutOfBounds() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("lr-bounds.txt"), "only line\n");
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-bounds.txt",
                 "operations": [
                   {"op": "replace", "startLine": 5, "endLine": 10, "content": "nope"}
                 ]}
                """, agent);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertTrue(result.contains("exceeds file length"), "got: " + result);
        assertEquals("only line\n", Files.readString(workspace.resolve("lr-bounds.txt")),
                "file must be unchanged when validation fails");
    }

    @Test
    public void editLinesAtomicAllOrNothing() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("lr-atomic.txt"), "alpha\nbeta\ngamma\n");
        // Second operation has endLine < startLine → entire batch rejects, no mutation.
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-atomic.txt",
                 "operations": [
                   {"op": "replace", "startLine": 1, "endLine": 1, "content": "ALPHA"},
                   {"op": "delete",  "startLine": 3, "endLine": 2}
                 ]}
                """, agent);
        assertTrue(result.startsWith("Error"), "got: " + result);
        assertEquals("alpha\nbeta\ngamma\n",
                Files.readString(workspace.resolve("lr-atomic.txt")),
                "first op must not leak through when a later op fails validation");
    }

    @Test
    public void editLinesRejectsUnknownOp() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "lr-unknown.txt", "content": "x"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-unknown.txt",
                 "operations": [{"op": "rewrite", "startLine": 1, "endLine": 1, "content": "y"}]}
                """, agent);
        assertTrue(result.contains("unknown op"), "got: " + result);
    }

    @Test
    public void editLinesEmptyOperationsRejected() {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "lr-empty.txt", "content": "x"}
                """, agent);
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "lr-empty.txt", "operations": []}
                """, agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("non-empty"));
    }

    @Test
    public void editLinesMissingFileErrors() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "editLines", "path": "missing-file.txt",
                 "operations": [{"op": "delete", "startLine": 1, "endLine": 1}]}
                """, agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("not found"));
    }

    // --- applyPatch ---

    @Test
    public void applyPatchAddUpdateDelete() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("to-update.txt"), "hello world\n");
        Files.writeString(workspace.resolve("to-delete.txt"), "goodbye\n");

        var patch = """
                *** Begin Patch
                *** Add File: added.txt
                +first line
                +second line
                *** End of File
                *** Update File: to-update.txt
                -hello world
                +HELLO WORLD
                *** End of File
                *** Delete File: to-delete.txt
                *** End Patch
                """;
        var result = ToolRegistry.execute("filesystem",
                "{\"action\": \"applyPatch\", \"patch\": " + jsonEscape(patch) + "}", agent);
        assertTrue(result.contains("1 added"), "got: " + result);
        assertTrue(result.contains("1 updated"));
        assertTrue(result.contains("1 deleted"));

        assertEquals("first line\nsecond line", Files.readString(workspace.resolve("added.txt")));
        assertTrue(Files.readString(workspace.resolve("to-update.txt")).contains("HELLO WORLD"));
        assertFalse(Files.exists(workspace.resolve("to-delete.txt")));
    }

    @Test
    public void applyPatchAtomicValidation() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        var patch = """
                *** Begin Patch
                *** Add File: new-atomic.txt
                +created
                *** End of File
                *** Update File: missing.txt
                -old
                +new
                *** End of File
                *** End Patch
                """;
        var result = ToolRegistry.execute("filesystem",
                "{\"action\": \"applyPatch\", \"patch\": " + jsonEscape(patch) + "}", agent);
        assertTrue(result.startsWith("Error"), "expected validation error: " + result);
        assertFalse(Files.exists(workspace.resolve("new-atomic.txt")),
                "atomicity: first op must not be applied when a later op fails validation");
    }

    @Test
    public void applyPatchUpdateContextMismatch() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("mismatch.txt"), "actual content\n");
        var patch = """
                *** Begin Patch
                *** Update File: mismatch.txt
                -different content
                +whatever
                *** End of File
                *** End Patch
                """;
        var result = ToolRegistry.execute("filesystem",
                "{\"action\": \"applyPatch\", \"patch\": " + jsonEscape(patch) + "}", agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("did not match"));
        assertEquals("actual content\n", Files.readString(workspace.resolve("mismatch.txt")),
                "file must be unchanged after validation error");
    }

    @Test
    public void applyPatchMoveFile() throws Exception {
        var workspace = AgentService.workspacePath(agent.name);
        Files.writeString(workspace.resolve("old-name.txt"), "content here\n");
        var patch = """
                *** Begin Patch
                *** Update File: old-name.txt
                *** Move to: new-name.txt
                -content here
                +new content
                *** End of File
                *** End Patch
                """;
        var result = ToolRegistry.execute("filesystem",
                "{\"action\": \"applyPatch\", \"patch\": " + jsonEscape(patch) + "}", agent);
        assertTrue(result.contains("updated"), "got: " + result);
        assertFalse(Files.exists(workspace.resolve("old-name.txt")),
                "old path must be gone after move");
        assertTrue(Files.readString(workspace.resolve("new-name.txt")).contains("new content"));
    }

    @Test
    public void applyPatchMalformedFormat() {
        var patch = """
                *** Begin Patch
                *** Update File: x.txt
                -old
                +new
                """; // missing *** End of File AND *** End Patch
        var result = ToolRegistry.execute("filesystem",
                "{\"action\": \"applyPatch\", \"patch\": " + jsonEscape(patch) + "}", agent);
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("malformed") || result.contains("missing"));
    }

    @Test
    public void applyPatchEmptyPatchRejected() {
        var result = ToolRegistry.execute("filesystem",
                """
                {"action": "applyPatch", "patch": ""}
                """, agent);
        assertTrue(result.startsWith("Error"));
    }

    // --- Concurrency ---

    @Test
    public void concurrentEditsSerialize() throws Exception {
        ToolRegistry.execute("filesystem",
                """
                {"action": "writeFile", "path": "concurrent.txt", "content": "AAA BBB"}
                """, agent);

        var pool = Executors.newFixedThreadPool(2);
        var latch = new CountDownLatch(1);
        try {
            var f1 = pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException _) {}
                return ToolRegistry.execute("filesystem",
                        """
                        {"action": "editFile", "path": "concurrent.txt",
                         "edits": [{"oldText": "AAA", "newText": "aaa"}]}
                        """, agent);
            });
            var f2 = pool.submit(() -> {
                try { latch.await(); } catch (InterruptedException _) {}
                return ToolRegistry.execute("filesystem",
                        """
                        {"action": "editFile", "path": "concurrent.txt",
                         "edits": [{"oldText": "BBB", "newText": "bbb"}]}
                        """, agent);
            });
            latch.countDown();
            var r1 = f1.get(10, TimeUnit.SECONDS);
            var r2 = f2.get(10, TimeUnit.SECONDS);
            assertTrue(r1.startsWith("File written"), "r1: " + r1);
            assertTrue(r2.startsWith("File written"), "r2: " + r2);
        } finally {
            pool.shutdown();
        }

        var read = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "concurrent.txt"}
                """, agent);
        assertEquals("aaa bbb", read, "both edits must land without losing an update");
    }

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

    // --- WebFetchTool SSRF guard ---

    @Test
    public void webFetchRejectsFileScheme() {
        var result = ToolRegistry.execute("web_fetch",
                "{\"url\": \"file:///etc/passwd\"}", agent);
        assertTrue(result.contains("rejected by SSRF guard"),
                "file:// scheme must hit the scheme allowlist, got: " + result);
        assertTrue(result.contains("scheme not allowed"),
                "error must name the scheme rule, got: " + result);
    }

    @Test
    public void webFetchRejectsLocalhost() {
        var result = ToolRegistry.execute("web_fetch",
                "{\"url\": \"http://localhost:8080/admin\"}", agent);
        // DNS-layer rejection surfaces as UnknownHostException wrapped through
        // the tool's error handling, not SecurityException.
        assertTrue(result.contains("rejected") || result.contains("SSRF guard"),
                "localhost must be blocked by the SSRF DNS filter, got: " + result);
    }

    @Test
    public void webFetchRejectsAwsMetadataIp() {
        // The Capital One 2019 canary: 169.254.169.254 is AWS/GCP/Azure
        // instance metadata. A prompt-injected LLM could use this to exfil
        // IAM credentials. SsrfGuard must block it at the DNS layer.
        var result = ToolRegistry.execute("web_fetch",
                "{\"url\": \"http://169.254.169.254/latest/meta-data/\"}", agent);
        assertTrue(result.contains("rejected"),
                "cloud metadata IP must be blocked, got: " + result);
    }

    @Test
    public void webFetchRejectsRfc1918Address() {
        var result = ToolRegistry.execute("web_fetch",
                "{\"url\": \"http://10.0.0.1/\"}", agent);
        assertTrue(result.contains("rejected"),
                "RFC-1918 range must be blocked, got: " + result);
    }

    @Test
    public void webFetchRejectsGopherScheme() {
        // gopher:// is a classic SSRF amplifier (Redis RCE). The scheme
        // allowlist must reject it before any host lookup.
        var result = ToolRegistry.execute("web_fetch",
                "{\"url\": \"gopher://evil.example/\"}", agent);
        assertTrue(result.contains("rejected by SSRF guard"),
                "gopher:// must be rejected, got: " + result);
    }

    // --- Helpers ---

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }

    @Test
    public void getToolDefsForLoadtestAgentReturnsEmpty() {
        // Sanity: a normal agent gets the full registered tool set.
        assertEquals(4, ToolRegistry.getToolDefsForAgent(agent).size(),
                "non-loadtest agent should see every published tool");

        // Loadtest agent: zero tools, regardless of what's registered. This
        // keeps cross-provider tokens-per-second benchmarks clean — no
        // 2-3 KB tools array prefill, no risk of the model invoking a tool
        // instead of answering the benchmark prompt.
        var loadtestAgent = AgentService.create(
                services.LoadTestRunner.LOADTEST_AGENT_NAME, "openrouter", "gpt-4.1");
        try {
            assertTrue(ToolRegistry.getToolDefsForAgent(loadtestAgent).isEmpty(),
                    "loadtest agent must see zero tools so the benchmark stays apples-to-apples");
        } finally {
            try { loadtestAgent.delete(); } catch (Exception _) { /* best-effort */ }
        }
    }
}
