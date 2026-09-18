import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.WorkspaceFiles;
import services.WorkspaceFiles.DeleteOutcome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * JCLAW-1249: {@link WorkspaceFiles#deleteWorkspaceEntry}. Runs against its own uniquely named
 * agent directory under {@code workspace-test} so concurrent test classes cannot collide.
 */
class WorkspaceDeleteTest extends UnitTest {

    private String agentName;
    private Path root;

    @BeforeEach
    void materialize() throws IOException {
        agentName = "jclaw1249-delete-" + System.nanoTime();
        root = WorkspaceFiles.workspacePath(agentName);
        Files.createDirectories(root);
    }

    @AfterEach
    void cleanUp() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) { /* best-effort */ }
            });
        }
    }

    @Test
    void removesAFileAndLeavesItsSiblings() throws IOException {
        Files.writeString(root.resolve("stray.txt"), "junk");
        Files.writeString(root.resolve("keep.txt"), "keep");

        assertEquals(DeleteOutcome.DELETED, WorkspaceFiles.deleteWorkspaceEntry(agentName, "stray.txt"));

        assertFalse(Files.exists(root.resolve("stray.txt")));
        assertTrue(Files.exists(root.resolve("keep.txt")), "a sibling is untouched");
    }

    @Test
    void removesAFolderWithItsWholeSubtree() throws IOException {
        Files.createDirectories(root.resolve("downloads/nested"));
        Files.writeString(root.resolve("downloads/report.pdf"), "pdf");
        Files.writeString(root.resolve("downloads/nested/deep.bin"), "deep");

        assertEquals(DeleteOutcome.DELETED, WorkspaceFiles.deleteWorkspaceEntry(agentName, "downloads"));

        assertFalse(Files.exists(root.resolve("downloads")), "the folder and everything under it is gone");
        assertTrue(Files.isDirectory(root), "the workspace root survives its subtree");
    }

    @Test
    void unlinksASymlinkWithoutTouchingItsTarget() throws IOException {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/b.txt"), "hello");
        Files.createSymbolicLink(root.resolve("inner"), root.resolve("data"));

        assertEquals(DeleteOutcome.DELETED, WorkspaceFiles.deleteWorkspaceEntry(agentName, "inner"));

        assertFalse(Files.exists(root.resolve("inner"), LinkOption.NOFOLLOW_LINKS), "the link is gone");
        assertTrue(Files.exists(root.resolve("data/b.txt")), "the directory it pointed at is untouched");
    }

    @Test
    void aSymlinkInsideADeletedFolderIsUnlinkedNotFollowed() throws IOException {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/b.txt"), "hello");
        Files.createDirectories(root.resolve("downloads"));
        Files.createSymbolicLink(root.resolve("downloads/link"), root.resolve("data"));

        assertEquals(DeleteOutcome.DELETED, WorkspaceFiles.deleteWorkspaceEntry(agentName, "downloads"));

        assertFalse(Files.exists(root.resolve("downloads")));
        assertTrue(Files.exists(root.resolve("data/b.txt")), "the linked directory survives");
    }

    @Test
    void refusesEveryStandingOrdersFileAtTheRoot() throws IOException {
        for (var name : WorkspaceFiles.PROTECTED_ROOT_FILES) {
            Files.writeString(root.resolve(name), "persona");

            assertEquals(DeleteOutcome.PROTECTED, WorkspaceFiles.deleteWorkspaceEntry(agentName, name),
                    name + " is a Standing Orders file");
            assertTrue(Files.exists(root.resolve(name)), name + " is still on disk");
        }
    }

    @Test
    void aNestedNamesakeOfAStandingOrdersFileIsAnOrdinaryFile() throws IOException {
        Files.createDirectories(root.resolve("notes"));
        Files.writeString(root.resolve("notes/AGENT.md"), "not the persona");

        assertEquals(DeleteOutcome.DELETED, WorkspaceFiles.deleteWorkspaceEntry(agentName, "notes/AGENT.md"));

        assertFalse(Files.exists(root.resolve("notes/AGENT.md")), "Standing Orders live only at the root");
    }

    @Test
    void refusesTheWorkspaceRootItself() throws IOException {
        Files.writeString(root.resolve("keep.txt"), "keep");

        assertEquals(DeleteOutcome.PROTECTED, WorkspaceFiles.deleteWorkspaceEntry(agentName, ""));
        assertEquals(DeleteOutcome.PROTECTED, WorkspaceFiles.deleteWorkspaceEntry(agentName, "."));

        assertTrue(Files.exists(root.resolve("keep.txt")), "the workspace is intact");
    }

    @Test
    void refusesAPathThatEscapesTheWorkspace() {
        assertThrows(SecurityException.class,
                () -> WorkspaceFiles.deleteWorkspaceEntry(agentName, "../../etc/passwd"));
    }

    @Test
    void reportsAMissingTargetRatherThanFailing() throws IOException {
        assertEquals(DeleteOutcome.MISSING, WorkspaceFiles.deleteWorkspaceEntry(agentName, "never-existed.txt"));
    }
}
