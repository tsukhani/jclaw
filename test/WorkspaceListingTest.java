import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.WorkspaceFiles;
import services.WorkspaceFiles.WorkspaceEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * JCLAW-1247: the attribute-only workspace listing. Runs against its own uniquely named
 * agent directory under {@code workspace-test} so concurrent test classes cannot collide.
 */
class WorkspaceListingTest extends UnitTest {

    private String agentName;
    private Path root;

    @BeforeEach
    void materialize() throws IOException {
        agentName = "jclaw1247-listing-" + System.nanoTime();
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

    private static WorkspaceEntry find(List<WorkspaceEntry> entries, String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no entry named " + name + " in " + entries));
    }

    @Test
    void aggregatesFolderSizesAndTotalsFromAttributesOnly() throws IOException {
        Files.writeString(root.resolve("AGENT.md"), "12345");
        Files.createDirectories(root.resolve("data/nested"));
        Files.writeString(root.resolve("data/b.txt"), "hello");
        Files.writeString(root.resolve("data/nested/a.bin"), "abc");

        var listing = WorkspaceFiles.listWorkspace(agentName);

        assertEquals(13, listing.total(), "total is the byte sum of every file under the root");
        var data = find(listing.entries(), "data");
        assertEquals("dir", data.kind());
        assertEquals(8, data.size(), "a folder's size aggregates its whole subtree");
        assertEquals("data", data.path());
        var nested = find(data.children(), "nested");
        assertEquals(3, nested.size());
        var aBin = find(nested.children(), "a.bin");
        assertEquals("data/nested/a.bin", aBin.path(), "paths are root-relative with forward slashes");
        assertEquals("file", aBin.kind());
        assertNull(aBin.children(), "a file carries no children");
    }

    @Test
    void marksOnlyTheRootStandingOrdersFilesProtected() throws IOException {
        Files.writeString(root.resolve("AGENT.md"), "root");
        Files.writeString(root.resolve("SOUL.md"), "root");
        Files.writeString(root.resolve("notes.md"), "plain");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/AGENT.md"), "nested copy");

        var entries = WorkspaceFiles.listWorkspace(agentName).entries();

        assertTrue(find(entries, "AGENT.md").isProtected());
        assertTrue(find(entries, "SOUL.md").isProtected());
        assertFalse(find(entries, "notes.md").isProtected());
        assertFalse(find(find(entries, "sub").children(), "AGENT.md").isProtected(),
                "the protected set is the five root files only");
    }

    @Test
    void listsDotfilesAndSortsFoldersFirst() throws IOException {
        Files.writeString(root.resolve("zeta.txt"), "z");
        Files.writeString(root.resolve(".env"), "secret");
        Files.createDirectories(root.resolve(".cache"));
        Files.createDirectories(root.resolve("alpha"));

        var names = WorkspaceFiles.listWorkspace(agentName).entries().stream()
                .map(WorkspaceEntry::name).toList();

        assertEquals(List.of(".cache", "alpha", ".env", "zeta.txt"), names,
                "dot entries are listed like any other; directories precede files");
    }

    @Test
    void neverFollowsSymlinks() throws IOException {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/b.txt"), "hello");
        // A link out of the workspace is dropped by the guard; one inside is listed as a file
        // and not descended, so a self-referential tree cannot loop or double-count.
        Files.createSymbolicLink(root.resolve("escape"), root.toAbsolutePath().getParent());
        Files.createSymbolicLink(root.resolve("inner"), root.resolve("data"));

        var listing = WorkspaceFiles.listWorkspace(agentName);
        var names = listing.entries().stream().map(WorkspaceEntry::name).toList();

        assertFalse(names.contains("escape"), "a symlink escaping the root is not listed: " + names);
        var inner = find(listing.entries(), "inner");
        assertEquals("file", inner.kind());
        assertNull(inner.children());
        assertEquals(5, listing.total(), "the linked directory's bytes are counted once");
    }

    @Test
    void aWorkspaceThatWasNeverMaterializedListsAsEmpty() throws IOException {
        var listing = WorkspaceFiles.listWorkspace("jclaw1247-missing-" + System.nanoTime());
        assertEquals(0, listing.total());
        assertTrue(listing.entries().isEmpty());
    }
}
