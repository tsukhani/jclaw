import jobs.OrphanChildWorkspaceCleanupJob;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Cleanup job that removes leftover on-disk workspace directories for
 * child agents (subagents). Spawned subagents stopped creating their own
 * folders in 2026-05-19 ({@code SpawnSubagentTool} now calls
 * {@link AgentService#create create} with
 * {@code createWorkspace=false}), but instances created before that
 * change left orphan directories under {@code data/agents/}. The
 * job's job is to sweep those up at boot.
 */
class OrphanChildWorkspaceCleanupJobTest extends UnitTest {

    private Path parentWorkspace;
    private Path childWorkspace;
    private Path unrelatedWorkspace;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @AfterEach
    void cleanup() throws Exception {
        // Best-effort: scrub any directories the test materialised so a
        // re-run on the same checkout starts clean.
        for (var p : new Path[]{ parentWorkspace, childWorkspace, unrelatedWorkspace }) {
            if (p != null && Files.exists(p)) deleteRecursively(p);
        }
    }

    @Test
    void sweepRemovesChildAgentDirectoryButPreservesParentAndUnrelated() throws Exception {
        // Parent: created normally so the full workspace is materialised.
        var parent = AgentService.create("cleanup-parent", "openrouter", "gpt-4.1");
        parentWorkspace = AgentService.workspacePath(parent.name);
        assertTrue(Files.exists(parentWorkspace),
                "parent workspace must exist before the sweep");

        // Child: persist the Agent row with parentAgent FK set, AND
        // manually create a stale on-disk folder simulating the pre-fix
        // state where AgentService.create would have made it.
        var child = AgentService.create("cleanup-child", "openrouter", "gpt-4.1",
                null, null, /* createWorkspace */ false);
        child.parentAgent = parent;
        child.save();
        childWorkspace = AgentService.workspaceRoot().resolve(child.name);
        Files.createDirectories(childWorkspace);
        Files.writeString(childWorkspace.resolve("leftover.md"), "should be removed");
        assertTrue(Files.exists(childWorkspace),
                "stale child folder must exist before the sweep");

        // Unrelated: a directory under the workspace root whose name has
        // no matching Agent row. Could be an operator scratch dir or a
        // leftover from a manually-deleted Agent. Must NOT be touched.
        unrelatedWorkspace = AgentService.workspaceRoot().resolve("manual-scratch-dir-abc");
        Files.createDirectories(unrelatedWorkspace);
        Files.writeString(unrelatedWorkspace.resolve("keep.md"), "keep me");

        // Commit the Agent rows so the sweep's lookup-by-name query in a
        // fresh Tx sees them. Mirrors the production boot ordering where
        // the rows are long since committed before the job fires.
        commitAndReopen();

        OrphanChildWorkspaceCleanupJob.sweep();

        assertTrue(Files.exists(parentWorkspace),
                "parent's workspace must survive the sweep");
        assertFalse(Files.exists(childWorkspace),
                "child agent's stale folder must be removed: " + childWorkspace);
        assertTrue(Files.exists(unrelatedWorkspace),
                "unrelated directory (no matching Agent row) must be preserved: "
                        + unrelatedWorkspace);
    }

    @Test
    void sweepIsIdempotent() throws Exception {
        // No child folders exist on second run; sweep must complete
        // cleanly without errors. The production cron is implicit: the
        // job runs every boot, doing nothing after the first successful
        // cleanup.
        OrphanChildWorkspaceCleanupJob.sweep();
        OrphanChildWorkspaceCleanupJob.sweep();
        // No assertion needed beyond "did not throw" — pinning the
        // contract that subsequent runs are safe.
    }

    private static void commitAndReopen() {
        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception _) { /* best-effort */ }
            });
        }
    }
}
