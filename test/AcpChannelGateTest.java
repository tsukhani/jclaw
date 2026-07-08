import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.EventLogger;
import services.Tx;
import tools.SubagentSpawnTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-669: coding-harness spawns are channel-gated. A run whose
 * operator-facing conversation is the web chat launches uninterrupted (the
 * headless -p contract); a run originating from an unsafe channel routes
 * through DangerousActionGate — with tool.approval.offChannelPolicy=deny and
 * no interactive surface for the fake channel, the spawn must fail closed
 * WITHOUT launching the harness process.
 */
class AcpChannelGateTest extends UnitTest {

    private Path harness;
    private Path sentinel;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
        new jobs.ToolRegistrationJob().doJob();
        agents.DangerousActionGate.clearGrantsForTest();

        sentinel = Files.createTempFile("jclaw-gate-sentinel-", ".txt");
        Files.deleteIfExists(sentinel);
        harness = Files.createTempFile("jclaw-gate-harness-", ".sh");
        Files.writeString(harness, "#!/bin/sh\ncat >/dev/null 2>&1\ntouch " + sentinel + "\necho ran\n");
        harness.toFile().setExecutable(true, false);

        ConfigService.set(SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
        ConfigService.set(SubagentSpawnTool.ACP_HARNESS_KEY, "generic");
        ConfigService.set(SubagentSpawnTool.ACP_MODE_KEY, "json");
    }

    @AfterEach
    void teardown() throws Exception {
        ConfigService.set("tool.approval.offChannelPolicy", "");
        if (harness != null) Files.deleteIfExists(harness);
        if (sentinel != null) Files.deleteIfExists(sentinel);
    }

    @Test
    void webOriginSpawnsUninterrupted() throws Exception {
        ConfigService.set("tool.approval.offChannelPolicy", "deny");
        var reply = spawnAcp("gate-web", "web");
        assertTrue(reply.contains("ran"), "web-origin run must execute: " + reply);
        assertTrue(Files.exists(sentinel), "harness must actually have run");
    }

    @Test
    void unsafeChannelOriginFailsClosedUnderDenyPolicy() throws Exception {
        ConfigService.set("tool.approval.offChannelPolicy", "deny");
        var reply = spawnAcp("gate-wa", "whatsapp");
        assertTrue(reply.toLowerCase().contains("denied") || reply.toLowerCase().contains("failed"),
                "unsafe-channel run must be denied: " + reply);
        assertFalse(Files.exists(sentinel),
                "the harness process must never have launched");
    }

    /** Spawn a synchronous acp run whose parent conversation uses {@code channelType}. */
    private String spawnAcp(String parentName, String channelType) throws Exception {
        var parent = services.AgentService.create(parentName, "test-provider", "test-model");
        parent.enabled = true;
        parent.acpAllowed = true;
        parent.save();
        services.ConversationService.create(parent, channelType, "u-" + parentName);
        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();

        var parentId = parent.id;
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var p = Tx.run(() -> (models.Agent) models.Agent.findById(parentId));
                var tool = agents.ToolRegistry.lookupTool(SubagentSpawnTool.TOOL_NAME);
                resultRef.set(tool.execute("{\"task\":\"gate-check\",\"runtime\":\"acp\"}", p));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "acp spawn must complete within 30s");
        if (errorRef.get() != null) return "failed: " + errorRef.get().getMessage();
        return String.valueOf(resultRef.get());
    }
}
