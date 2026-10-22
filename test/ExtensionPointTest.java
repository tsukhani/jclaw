import agents.ToolRegistry;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentToolConfig;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import utils.CircuitBreakers;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extension points (JF-17): a dummy added through a pluggable seam alone is discovered and gated like a real one. A
 * channel and a sidecar cannot be added that way: JF-19 and JF-20 record what each would need.
 */
class ExtensionPointTest extends UnitTest {

    private static final String TOOL = "extension_probe";

    @Test
    void aToolPublishedThroughTheRegistryIsOfferedAndGated() throws Exception {
        var probe = new ToolRegistry.Tool() {
            @Override public String name() { return TOOL; }
            @Override public String description() { return "Added through ToolRegistry alone"; }
            @Override public Map<String, Object> parameters() { return Map.of("type", "object", "properties", Map.of()); }
            @Override public String execute(String argsJson, Agent agent) { return "probed"; }
        };
        ToolRegistrySync.withTools(List.of(probe), () -> {
            var agent = AgentService.create("extension-probe-agent", "openrouter", "gpt-4.1");
            try {
                assertTrue(offered(agent), "a published tool is in the agent's tool definitions");
                assertEquals("probed", ToolRegistry.executeRich(TOOL, "{}", agent, Set.of(TOOL)).text());
                assertEquals(ToolRegistry.ToolResult.Outcome.NOT_ENABLED,
                        ToolRegistry.executeRich(TOOL, "{}", agent, Set.of()).outcome(), "a tool the turn did not offer is refused");

                var disabled = new AgentToolConfig();
                disabled.agent = agent;
                disabled.toolName = TOOL;
                disabled.enabled = false;
                disabled.save();
                ToolRegistry.invalidateDisabledToolsCache(agent);
                assertFalse(offered(agent), "a tool the operator disabled for the agent is no longer offered");
            } finally {
                AgentService.delete(Agent.findById(agent.id));
            }
        });
    }

    @Test
    void aProviderConfiguredByNameIsRegisteredAndItsUrlScreened() {
        // Breakers are process-global by provider name, so the name is this run's own.
        var name = "extension-probe-" + System.nanoTime();
        try {
            assertNull(ConfigService.setWithSideEffects("provider." + name + ".baseUrl", "http://127.0.0.1:1/v1"));
            assertNull(ConfigService.setWithSideEffects("provider." + name + ".apiKey", "test-key"));
            ProviderRegistry.refresh();
            assertNotNull(ProviderRegistry.get(name), "a provider written to the config is registered");
            assertNotNull(ConfigService.setWithSideEffects("provider." + name + ".baseUrl", "http://169.254.169.254/v1"),
                    "a base URL on the cloud-metadata address is refused at the write");
        } finally {
            ConfigService.delete("provider." + name + ".baseUrl");
            ConfigService.delete("provider." + name + ".apiKey");
            ProviderRegistry.refresh();
            CircuitBreakers.remove(name);
        }
    }

    private static boolean offered(Agent agent) {
        return ToolRegistry.getToolDefsForAgent(agent).stream().anyMatch(def -> TOOL.equals(def.function().name()));
    }
}
