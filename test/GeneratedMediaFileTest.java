import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.WorkspaceFiles;
import tools.GenerateAudioTool;
import tools.GenerateImageTool;
import tools.GenerateVideoTool;
import tools.GeneratedMediaFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * The workspace-save path added to {@code generate_image} / {@code generate_audio}
 * (JCLAW-1057).
 *
 * <p>Both tools deliver their bytes as an inline chat attachment and return no path,
 * which is right for an interactive turn and unusable from a scheduled task. Asked to
 * "save it to a file, then send that file", an agent with no way to comply improvised:
 * a production run copied an unrelated local file and sent it onward as the generated
 * image. These tests pin the two properties that stop that recurring — the option exists
 * and is advertised, and it cannot be used to write outside the workspace.
 *
 * <p>Owns its workspace directory rather than borrowing a seeded agent's. The first
 * version of this test resolved the {@code main} agent from the database and passed in
 * isolation but failed in the full suite, where another test had wiped the row — the
 * path guard needs a directory, not a persisted agent.
 */
class GeneratedMediaFileTest extends UnitTest {

    private static final String AGENT = "generated-media-file-test";

    private Path root;

    @BeforeEach
    void createWorkspace() throws IOException {
        root = WorkspaceFiles.workspacePath(AGENT);
        Files.createDirectories(root);
    }

    @AfterEach
    void removeWorkspace() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /**
     * The model only reaches for an option it can see. The whole defect began with a tool
     * whose schema offered no way to obtain a file, so the description has to say both
     * that the option exists and that nothing is written without it.
     */
    @Test
    void bothToolsAdvertiseTheSaveOption() {
        for (var params : new Object[]{new GenerateImageTool().parameters(),
                new GenerateAudioTool().parameters()}) {
            var json = params.toString();
            assertTrue(json.contains("save_to"),
                    "the save option must be in the schema or the model cannot use it: " + json);
            assertTrue(json.contains("NO file exists on disk"),
                    "the schema must say a file is NOT written by default — the agent that "
                            + "assumed otherwise went hunting the filesystem: " + json);
        }
    }

    /**
     * A caller-supplied name reaches the write straight from model output, so traversal
     * must be rejected rather than sanitized into something surprising.
     */
    @Test
    void refusesToWriteOutsideTheWorkspace() {
        for (var escape : new String[]{"../escaped.png", "../../escaped.png",
                "sub/../../escaped.png"}) {
            assertThrows(SecurityException.class,
                    () -> WorkspaceFiles.acquireWorkspacePath(AGENT, escape),
                    "'" + escape + "' must not resolve to a writable path");
        }
        assertFalse(Files.exists(root.getParent().resolve("escaped.png")),
                "nothing may be written beside the workspace");
    }

    @Test
    void resolvesAPlainNameInsideTheWorkspace() throws Exception {
        var resolved = WorkspaceFiles.acquireWorkspacePath(AGENT, "tea-reminder.png");
        assertTrue(resolved.startsWith(root.toRealPath()),
                "a plain filename must land inside the workspace: " + resolved);
    }

    /** A nested name is legitimate as long as it stays inside; the parent is created. */
    @Test
    void writesIntoASubdirectoryOfTheWorkspace() throws Exception {
        var resolved = WorkspaceFiles.acquireWorkspacePath(AGENT, "media/tea.png");
        Files.createDirectories(resolved.getParent());
        Files.write(resolved, new byte[]{1, 2, 3});

        assertTrue(Files.isRegularFile(resolved));
        assertTrue(resolved.startsWith(root.toRealPath()));
    }

    /**
     * Video is asynchronous: the file appears minutes later, after the turn that asked
     * for it has ended. The schema has to say so, because a caller that assumes the file
     * is ready on return will send a path that does not exist yet — the same shape of
     * failure this whole change exists to stop.
     */
    @Test
    void theVideoToolSaysTheFileIsNotThereYet() {
        var json = new GenerateVideoTool().parameters().toString();
        assertTrue(json.contains("save_to"), "the option must be in the schema: " + json);
        assertTrue(json.contains("does NOT exist when"),
                "the schema must say the file is not ready on return: " + json);
        assertTrue(json.contains("written whole"),
                "a caller polling for the file needs to know existence means complete: " + json);
    }

    /**
     * The completion signal for an async generation is the file appearing, so a partial
     * write is indistinguishable from a finished one. Write-then-rename is what makes
     * {@code test -f} trustworthy; without it a poller can pick up a truncated clip.
     */
    @Test
    void leavesNoPartialFileBehind() throws Exception {
        GeneratedMediaFile.write(AGENT, "clip.mp4", new byte[]{1, 2, 3, 4});

        assertTrue(Files.isRegularFile(root.resolve("clip.mp4")));
        assertEquals(4, Files.size(root.resolve("clip.mp4")));
        try (var walk = Files.walk(root)) {
            assertTrue(walk.noneMatch(f -> f.getFileName().toString().endsWith(".partial")),
                    "the temp file must not survive the write");
        }
    }

    @Test
    void overwritesAnEarlierFileOfTheSameName() throws Exception {
        GeneratedMediaFile.write(AGENT, "clip.mp4", new byte[]{1, 2, 3, 4});
        GeneratedMediaFile.write(AGENT, "clip.mp4", new byte[]{9});

        assertEquals(1, Files.size(root.resolve("clip.mp4")),
                "a re-run must replace the previous clip, not fail on it");
    }
}
