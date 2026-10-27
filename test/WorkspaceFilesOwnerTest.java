import models.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.WorkspaceFiles;

/**
 * The owner a subagent's persona findings are keyed by is the owner of the workspace it reads, on
 * either side of the 32-hop limit the parent walk stops at.
 */
class WorkspaceFilesOwnerTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void aSubagentThirtyTwoHopsDownReadsAndIsKeyedByItsRoot() {
        var leaf = chainOf(33);

        assertEquals("owner-walk-0", WorkspaceFiles.workspaceOwnerName(leaf));
        assertEquals("owner-walk-0", workspaceDirName(leaf));
    }

    @Test
    void aSubagentPastTheWalkLimitReadsAndIsKeyedByTheSameAncestor() {
        var leaf = chainOf(34);

        assertEquals("owner-walk-1", WorkspaceFiles.workspaceOwnerName(leaf));
        assertEquals("owner-walk-1", workspaceDirName(leaf));
    }

    @Test
    void aRootAgentOwnsItsOwnWorkspace() {
        var root = chainOf(1);

        assertEquals("owner-walk-0", WorkspaceFiles.workspaceOwnerName(root));
        assertEquals("owner-walk-0", workspaceDirName(root));
    }

    /** {@code length} agents, each the parent of the next; returns the last. */
    private static Agent chainOf(int length) {
        Agent parent = null;
        for (int i = 0; i < length; i++) {
            parent = AgentService.create("owner-walk-" + i, "owner-walk-provider", "owner-walk-model",
                    null, null, false, parent);
        }
        return parent;
    }

    private static String workspaceDirName(Agent agent) {
        return WorkspaceFiles.workspacePath(agent.name).getFileName().toString();
    }
}
