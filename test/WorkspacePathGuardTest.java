import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.WorkspacePathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Unit tests for {@link WorkspacePathGuard} (JCLAW-732).
 *
 * <p>Focus: the read-only-probe contract of
 * {@link WorkspacePathGuard#resolveContained}. Despite the "resolve" name it
 * previously called {@code Files.createDirectories} on the root — a hidden
 * filesystem mutation. These tests pin the resolver as side-effect-free: it
 * must resolve a contained relative path (even when the root doesn't exist on
 * disk yet) without ever creating the root or the target.
 */
class WorkspacePathGuardTest extends UnitTest {

    @Test
    void resolveContainedIsReadOnlyProbeAndCreatesNothing() throws Exception {
        var parent = Files.createTempDirectory("wpg-probe");
        // A workspace root that does NOT exist yet, under an existing parent.
        var root = parent.resolve("ws-root");
        try {
            var resolved = WorkspacePathGuard.resolveContained(root, "agent/AGENT.md");

            assertNotNull(resolved,
                    "a contained relative path must resolve even when the root is not yet materialised");
            assertTrue(resolved.endsWith(Path.of("agent", "AGENT.md")), resolved.toString());

            // The contract under test: a "resolve" probe must not touch the filesystem.
            assertFalse(Files.exists(root), "resolveContained must not create the workspace root");
            assertFalse(Files.exists(resolved), "resolveContained must not create the target");
        } finally {
            deleteRecursive(parent);
        }
    }

    @Test
    void resolveContainedRejectsDotDotEscape() {
        var root = Path.of("workspace").toAbsolutePath();
        assertNull(WorkspacePathGuard.resolveContained(root, "../../../etc/passwd"),
                "a lexical .. escape must return null");
    }

    // ==================== resolveChild: one entry directly inside the root ====================

    @Test
    void resolveChildReturnsTheCanonicalPathOfAnEntryOfTheRoot() throws Exception {
        var root = Files.createTempDirectory("wpg-child");
        try {
            Files.createDirectories(root.resolve("alpha/inner"));

            assertEquals(root.toRealPath().resolve("alpha"), WorkspacePathGuard.resolveChild(root, "alpha"));
            assertEquals(root.toRealPath().resolve("al.pha v2"), WorkspacePathGuard.resolveChild(root, "al.pha v2"),
                    "an entry that does not exist yet still resolves, as a write target does");
            assertFalse(Files.exists(root.resolve("al.pha v2")), "resolveChild must not create the entry");
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void resolveChildResolvesUnderARootThatDoesNotExistYetAndCreatesNothing() throws Exception {
        var parent = Files.createTempDirectory("wpg-child-probe");
        var root = parent.resolve("skills");
        try {
            assertEquals(parent.toRealPath().resolve("skills/alpha"), WorkspacePathGuard.resolveChild(root, "alpha"));
            assertFalse(Files.exists(root), "resolveChild must not create the root");
        } finally {
            deleteRecursive(parent);
        }
    }

    @Test
    void resolveChildRefusesTheRootItselfAndEveryPath() throws Exception {
        var root = Files.createTempDirectory("wpg-child-paths");
        try {
            Files.createDirectories(root.resolve("alpha/inner"));

            for (var name : List.of("", ".", "..", "alpha/inner", "alpha/", "/alpha", "./alpha", "x/..", "x/../alpha",
                    "alpha\\inner", "\0", "alpha\0", root.toString(), "../" + root.getFileName())) {
                assertNull(WorkspacePathGuard.resolveChild(root, name), "'" + name + "' is not one entry of the root");
            }
            // The limit: one segment resolves, and the same segment with one more does not.
            assertEquals(root.toRealPath().resolve("inner"), WorkspacePathGuard.resolveChild(root, "inner"));
        } finally {
            deleteRecursive(root);
        }
    }

    @Test
    void resolveChildFollowsALinkOnlyToAnotherEntryOfTheRoot() throws Exception {
        var root = Files.createTempDirectory("wpg-child-links");
        var outside = Files.createTempDirectory("wpg-child-outside");
        try {
            Files.createDirectories(root.resolve("alpha/inner"));
            Files.createSymbolicLink(root.resolve("to-sibling"), root.resolve("alpha"));
            Files.createSymbolicLink(root.resolve("to-inside"), root.resolve("alpha/inner"));
            Files.createSymbolicLink(root.resolve("to-root"), root);
            Files.createSymbolicLink(root.resolve("to-outside"), outside);

            assertEquals(root.toRealPath().resolve("alpha"), WorkspacePathGuard.resolveChild(root, "to-sibling"));
            assertNull(WorkspacePathGuard.resolveChild(root, "to-inside"), "a link into an entry is not an entry");
            assertNull(WorkspacePathGuard.resolveChild(root, "to-root"), "a link to the root is the root");
            assertNull(WorkspacePathGuard.resolveChild(root, "to-outside"), "a link out of the root escapes it");
        } finally {
            deleteRecursive(root);
            deleteRecursive(outside);
        }
    }

    @Test
    void resolveContainedStillAcceptsTheRootAndAnyDepthBelowIt() throws Exception {
        var root = Files.createTempDirectory("wpg-contained");
        try {
            Files.createDirectories(root.resolve("alpha/inner"));

            assertEquals(root.toRealPath(), WorkspacePathGuard.resolveContained(root, ""));
            assertEquals(root.toRealPath(), WorkspacePathGuard.resolveContained(root, "."));
            assertEquals(root.toRealPath().resolve("alpha/inner"), WorkspacePathGuard.resolveContained(root, "alpha/inner"));
        } finally {
            deleteRecursive(root);
        }
    }

    private static void deleteRecursive(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception _) { /* best-effort */ }
            });
        }
    }
}
