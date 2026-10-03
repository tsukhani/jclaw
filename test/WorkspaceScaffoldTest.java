import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.WorkspaceFiles;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCLAW-910: the default agent's workspace is scaffolded from shipped templates
 * rather than from tracked repository content.
 *
 * <p>Those five markdown files used to live under {@code workspace/main/} as tracked
 * files, kept in git by hand-written {@code .gitignore} exceptions. They were operator
 * state rather than templates, so every release swept the operator's own edits into a
 * commit and pushed them to a public mirror. Untracking them means a fresh install has
 * to get its content from here instead — hence these tests.
 *
 * <p>Scaffolding runs against {@code workspace-test} in test mode
 * ({@code %test.jclaw.workspace.path}), never the operator's real workspace.
 */
class WorkspaceScaffoldTest extends UnitTest {

    private static String read(String agentName, String filename) throws Exception {
        Path file = WorkspaceFiles.workspacePath(agentName).resolve(filename);
        return Files.readString(file);
    }

    @Test
    void defaultAgentGetsTheShippedSoulAndIdentity() throws Exception {
        // resetWorkspace rather than createWorkspace: workspace-test survives between
        // runs, so a createWorkspace here would read whatever a previous run left and
        // pass on stale content. Overwriting is safe — the directory is ephemeral.
        WorkspaceFiles.resetWorkspace(Agent.MAIN_AGENT_NAME);

        var soul = read(Agent.MAIN_AGENT_NAME, "SOUL.md");
        assertTrue(soul.contains("high-agency"),
                "the default agent must get the shipped soul, not the blank template");
        assertTrue(soul.contains("Intellectual Honesty Above All"), "soul content is truncated");
        assertFalse(soul.contains("Leave blank to skip"),
                "the default agent must not fall back to the blank soul template");

        var identity = read(Agent.MAIN_AGENT_NAME, "IDENTITY.md");
        assertTrue(identity.contains("Clawdia"),
                "the default agent must get the shipped identity, not 'Name: main'");
    }

    @Test
    void everyOtherAgentGetsTheBlankTemplates() throws Exception {
        var name = "scaffold-probe-agent";
        WorkspaceFiles.resetWorkspace(name);

        var soul = read(name, "SOUL.md");
        assertTrue(soul.contains("Leave blank to skip"), "a custom agent gets the blank soul");
        assertFalse(soul.contains("high-agency"),
                "the default agent's soul must not leak into other agents");

        var identity = read(name, "IDENTITY.md");
        assertTrue(identity.contains("Name: " + name), "identity is named for its agent");
        assertFalse(identity.contains("Clawdia"),
                "the default agent's identity must not leak into other agents");
    }

    @Test
    void theDefaultAgentsUserFileShipsEmpty() throws Exception {
        // The regression guard for what JCLAW-910 actually fixed. The tracked USER.md
        // carried the operator's name, employer and location, and shipping a populated
        // one as a template would republish that to every clone. Only SOUL and IDENTITY
        // are main-specific; USER stays the blank template.
        WorkspaceFiles.resetWorkspace(Agent.MAIN_AGENT_NAME);

        var user = read(Agent.MAIN_AGENT_NAME, "USER.md");
        assertTrue(user.contains("Add information about the user here"),
                "the default agent's USER.md must ship as the blank template");
        assertTrue(user.contains("\nName:\n"),
                "the template carries an empty Name line for the bootstrap to fill, and no populated details; got: " + user);
    }

    @Test
    void theBootstrapAsksForTheNameOnlyWhileItIsMissing() throws Exception {
        WorkspaceFiles.resetWorkspace(Agent.MAIN_AGENT_NAME);
        var bootstrap = read(Agent.MAIN_AGENT_NAME, "BOOTSTRAP.md");
        assertTrue(bootstrap.contains("If the Name line in USER.md is empty"), bootstrap);
        assertTrue(bootstrap.contains("never in a group chat"), "only the owner may supply the name; got: " + bootstrap);
    }

    @Test
    void theOwnerNameIsReadFromTheNameLineAboveTheFirstSection() {
        var name = "owner-name-probe";
        WorkspaceFiles.resetWorkspace(name);
        WorkspaceFiles.writeWorkspaceFile(name, "USER.md", "# User Information\n\nName: **Ada Lovelace**\n\n## Kids\n\n- Name: Theo\n");
        assertEquals("Ada Lovelace", WorkspaceFiles.ownerName(name));

        WorkspaceFiles.writeWorkspaceFile(name, "USER.md", "# User Information\n\nName:\n\n## Kids\n\n- Name: Theo\n");
        assertNull(WorkspaceFiles.ownerName(name), "a child's name in a later section is never the owner's");

        WorkspaceFiles.writeWorkspaceFile(name, "USER.md", "# User Information\n\n<!-- Name: Example -->\nUsername: ada\n");
        assertNull(WorkspaceFiles.ownerName(name), "neither a comment nor a Username line names the owner");
        assertNull(WorkspaceFiles.ownerName("no-such-agent-workspace"));
    }

    @Test
    void anOlderWorkspaceGainsTheNameLineAndTheBootstrapStepOnce() throws Exception {
        var name = "owner-name-upgrade";
        WorkspaceFiles.resetWorkspace(name);
        WorkspaceFiles.writeWorkspaceFile(name, "USER.md", "# User Information\n\n## Movie Preferences\n\n- Likes sci-fi\n");
        WorkspaceFiles.writeWorkspaceFile(name, "BOOTSTRAP.md", """
                # Bootstrap

                <!-- Priming / initialization context the agent should see before task
                     instructions in AGENT.md. Examples: preconditions, environment
                     assumptions, warm-up context. Leave blank to skip. -->
                """);

        WorkspaceFiles.addOwnerNamePrompts(name);
        assertEquals("# User Information\n\nName:\n\n## Movie Preferences\n\n- Likes sci-fi\n", read(name, "USER.md"));
        assertEquals(WorkspaceFiles.BOOTSTRAP_TEMPLATE, read(name, "BOOTSTRAP.md"), "an untouched template is replaced whole");

        WorkspaceFiles.addOwnerNamePrompts(name);
        assertEquals("# User Information\n\nName:\n\n## Movie Preferences\n\n- Likes sci-fi\n", read(name, "USER.md"), "a second run changes nothing");
        assertEquals(WorkspaceFiles.BOOTSTRAP_TEMPLATE, read(name, "BOOTSTRAP.md"));
    }

    @Test
    void anEditedBootstrapKeepsItsTextAndAFilledNameIsLeftAlone() throws Exception {
        var name = "owner-name-edited";
        WorkspaceFiles.resetWorkspace(name);
        WorkspaceFiles.writeWorkspaceFile(name, "USER.md", "# User Information\n\nName: Ada\n");
        WorkspaceFiles.writeWorkspaceFile(name, "BOOTSTRAP.md", "# Bootstrap\n\nCheck the build is green first.\n");

        WorkspaceFiles.addOwnerNamePrompts(name);
        assertEquals("# User Information\n\nName: Ada\n", read(name, "USER.md"));
        var bootstrap = read(name, "BOOTSTRAP.md");
        assertTrue(bootstrap.startsWith("# Bootstrap\n\nIf the Name line in USER.md is empty"), bootstrap);
        assertTrue(bootstrap.endsWith("Check the build is green first.\n"), "the operator's own text survives; got: " + bootstrap);
    }

    @Test
    void scaffoldingNeverOverwritesExistingContent() throws Exception {
        var name = "scaffold-idempotency-agent";
        WorkspaceFiles.resetWorkspace(name);

        var dir = WorkspaceFiles.workspacePath(name);
        Files.writeString(dir.resolve("AGENT.md"), "OPERATOR EDIT 3f9c");
        Files.writeString(dir.resolve("SOUL.md"), "OPERATOR SOUL 3f9c");

        // The boot path calls this on every start; it must be a no-op over a populated
        // workspace or an operator loses their edits on restart.
        WorkspaceFiles.createWorkspace(name);

        assertEquals("OPERATOR EDIT 3f9c", read(name, "AGENT.md"));
        assertEquals("OPERATOR SOUL 3f9c", read(name, "SOUL.md"));
        assertTrue(Files.isDirectory(dir.resolve("skills")), "skills/ is created alongside");
    }
}
