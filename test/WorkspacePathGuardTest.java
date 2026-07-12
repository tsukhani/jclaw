import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.WorkspacePathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

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

    private static void deleteRecursive(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception _) { /* best-effort */ }
            });
        }
    }
}
