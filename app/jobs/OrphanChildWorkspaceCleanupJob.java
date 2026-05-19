package jobs;

import models.Agent;
import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.AgentService;
import services.EventLogger;
import services.Tx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Boot-time sweep that removes leftover on-disk workspace directories for
 * child agents (subagents). Subagents are delegates of the parent agent
 * and inherit the parent's workspace via
 * {@link AgentService#workspacePath(String)}'s parent-chain walk — they
 * never need a folder of their own. Spawn-time materialisation was
 * suppressed by {@link tools.SpawnSubagentTool#bootstrapChild} starting
 * 2026-05-19, but instances created before that date left orphan
 * directories under {@code data/agents/}.
 *
 * <p>The sweep is idempotent and cheap once clean: it iterates the
 * immediate children of the workspace root, looks up each by name in the
 * {@code Agent} table, and removes the directory only when the matched
 * row's {@code parentAgent} FK is non-null. Directories with no matching
 * row (orphaned by an earlier {@code DELETE FROM agent}, scratch dirs an
 * operator created, etc.) are left alone — only the specific child-agent
 * pattern is touched.
 *
 * <p>Skipped in test mode so {@link play.test.Fixtures#deleteDatabase}
 * cycles don't trigger the sweep against an empty {@code Agent} table
 * and then look like a successful cleanup of nothing. The actual one-time
 * effect happens on the first production boot after the
 * {@code createWorkspace=false} change ships.
 */
@OnApplicationStart
public class OrphanChildWorkspaceCleanupJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        sweep();
    }

    /**
     * Visible for tests. Production path runs once via {@link #doJob} at
     * boot; tests call this directly to bypass the
     * {@link Play#runningInTestMode test-mode skip} and exercise the
     * scan-and-remove logic against fixtures.
     */
    public static void sweep() {
        var root = AgentService.workspaceRoot();
        if (!Files.exists(root) || !Files.isDirectory(root)) return;

        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory).forEach(OrphanChildWorkspaceCleanupJob::maybeRemoveChildDir);
        } catch (IOException e) {
            EventLogger.warn("agent",
                    "OrphanChildWorkspaceCleanupJob: list failed for " + root
                            + ": " + e.getMessage());
        }
        EventLogger.flush();
    }

    /**
     * If the directory name corresponds to a child {@link Agent}
     * (non-null {@code parentAgent} FK), remove it. The lookup is
     * by-name because the directory's only link to its owning Agent row
     * IS its name — there's no metadata file inside the workspace
     * referencing the row id. Names with no matching row are left
     * untouched: operator-created scratch directories, prior-rename
     * leftovers, and similar should NOT be reclaimed.
     */
    private static void maybeRemoveChildDir(Path dir) {
        var name = dir.getFileName().toString();
        boolean isChild;
        try {
            isChild = Tx.run(() -> {
                var agent = (Agent) Agent.find("name = ?1", name).first();
                return agent != null && agent.parentAgent != null;
            });
        } catch (RuntimeException e) {
            EventLogger.warn("agent",
                    "OrphanChildWorkspaceCleanupJob: lookup failed for '"
                            + name + "': " + e.getMessage());
            return;
        }
        if (!isChild) return;
        if (deleteRecursively(dir)) {
            EventLogger.info("agent",
                    "Removed orphan child-agent workspace folder: " + dir);
        }
    }

    /**
     * Walk the tree depth-first and remove every entry; returns
     * {@code true} on success, {@code false} on any I/O failure. Failures
     * are logged at the call site so the sweep keeps going on the next
     * directory rather than aborting.
     */
    private static boolean deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    EventLogger.warn("agent",
                            "OrphanChildWorkspaceCleanupJob: delete failed for "
                                    + p + ": " + e.getMessage());
                }
            });
            return !Files.exists(dir);
        } catch (IOException e) {
            EventLogger.warn("agent",
                    "OrphanChildWorkspaceCleanupJob: walk failed for "
                            + dir + ": " + e.getMessage());
            return false;
        }
    }
}
