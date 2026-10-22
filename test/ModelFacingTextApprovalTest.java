import agents.SystemPromptAssembler;
import agents.ToolRegistry;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import tools.JClawApiTool;
import utils.AppClock;
import utils.GsonHolder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * Approval tests for the text the model reads (JF-17): the assembled system prompt, the tool definitions and the
 * {@code jclaw_api} discover surface, each compared with a checked-in file in {@code test/approved/}. A change to any
 * of them fails here and writes {@code <name>.received.txt} beside the approved file; read the diff, and if the model
 * should now read exactly that, move the received file over the approved one. Kept to these three, because a snapshot
 * approved without reading is noise.
 */
class ModelFacingTextApprovalTest extends UnitTest {

    private static final Path APPROVED = Path.of("test", "approved");
    private static final String AGENT = "approval-text-agent";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-22T09:00:00Z"), ZoneOffset.UTC);

    private Agent agent;

    @BeforeEach
    void setup() {
        ToolRegistrySync.canonicalForTest();
        var stale = Agent.findByName(AGENT);
        if (stale != null) AgentService.delete(stale);
        agent = AgentService.create(AGENT, "openrouter", "gpt-4.1");
    }

    @AfterEach
    void teardown() {
        try {
            var created = Agent.findByName(AGENT);
            if (created != null) AgentService.delete(created);
        } finally {
            ToolRegistrySync.release();
        }
    }

    @Test
    void theSystemPromptIsApproved() throws IOException {
        var prompt = AppClock.callWith(FIXED, () -> SystemPromptAssembler.assemble(agent, null, Set.of(), "web").systemPrompt());
        // The environment block names the machine and the run; everything else is the text itself.
        assertApproved("system-prompt", prompt
                .replaceAll("- Agent ID: \\d+", "- Agent ID: <id>")
                .replaceAll("- JClaw version: .*", "- JClaw version: <version>")
                .replaceAll("- Platform: .*", "- Platform: <platform>")
                .replaceAll("- Runtime: Java \\d+", "- Runtime: Java <feature>"));
    }

    @Test
    void theToolDefinitionsAreApproved() throws IOException {
        assertApproved("tool-definitions", ApprovedText.canonicalJson(GsonHolder.GSON.toJsonTree(ToolRegistry.getToolDefsForAgent(agent))));
    }

    @Test
    void theJclawApiDiscoverSurfaceIsApproved() throws IOException {
        assertApproved("jclaw-api-discover", new JClawApiTool().execute("{\"action\":\"discover\"}", agent));
    }

    private static void assertApproved(String name, String received) throws IOException {
        ApprovedText.assertApproved(APPROVED.resolve(name + ".approved.txt"), APPROVED.resolve(name + ".received.txt"), received);
    }
}
