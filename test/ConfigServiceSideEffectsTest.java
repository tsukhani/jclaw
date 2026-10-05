import agents.ToolRegistry;
import llm.LlmResilience;
import llm.ProviderRegistry;
import memory.MemoryStoreFactory;
import memory.MemoryVectorSettings;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.Tx;
import services.telemetry.OtelRuntime;
import utils.CircuitBreakers;

/**
 * The side effects {@link ConfigService#setWithSideEffects} runs once a write is stored: each test writes through
 * it and observes one effect, so a change that drops one, or gates it on the wrong key, fails here.
 */
class ConfigServiceSideEffectsTest extends UnitTest {

    @Test
    void anOtelWriteReappliesTheTelemetryConfigAtOnce() {
        TelemetryTestSync.acquire();
        var name = "jclaw-cfgse-" + System.nanoTime();
        try {
            assertNull(ConfigService.setWithSideEffects("otel.service.name", name));
            assertEquals(name, OtelRuntime.status().serviceName());
        } finally {
            ConfigService.delete("otel.service.name");
            OtelRuntime.applyConfig();
            TelemetryTestSync.release();
        }
    }

    @Test
    void aBreakerWriteDropsTheClosedProviderBreakers() {
        var provider = "zz-cfgse-breaker";
        // The registry lists a provider only once both its baseUrl and apiKey are set.
        ConfigService.set("provider." + provider + ".baseUrl", "http://cfgse-breaker.invalid/v1");
        ConfigService.set("provider." + provider + ".apiKey", "k");
        ProviderRegistry.refresh();
        var window = ConfigService.get("llm.breaker.window");
        try {
            LlmResilience.breakerFor(provider);
            assertNull(ConfigService.setWithSideEffects("llm.breaker.window", window != null ? window : "10"));
            assertTrue(CircuitBreakers.find(LlmResilience.breakerName(provider)).isEmpty(),
                    "the closed breaker is dropped, so its next call mints it from the new tuning");
        } finally {
            CircuitBreakers.remove(LlmResilience.breakerName(provider));
            if (window == null) ConfigService.delete("llm.breaker.window");
            else ConfigService.set("llm.breaker.window", window);
            ConfigService.delete("provider." + provider + ".baseUrl");
            ConfigService.delete("provider." + provider + ".apiKey");
            ProviderRegistry.refresh();
        }
    }

    @Test
    void aMemoryVectorWriteDropsTheCachedStore() {
        var key = MemoryVectorSettings.KEY_PREFIX + "zzCfgseProbe";
        try (var _ = LuceneTestSync.openLease()) {
            try {
                var before = MemoryStoreFactory.get();
                assertNull(ConfigService.setWithSideEffects(key, "1"));
                assertNotSame(before, MemoryStoreFactory.get(), "the next use builds the store from the new settings");
            } finally {
                ConfigService.delete(key);
                MemoryStoreFactory.reset();
            }
        }
    }

    @Test
    void aProviderWriteResyncsTheAgentsEnabledStates() {
        var provider = "zz-cfgse-sync";
        var agent = AgentService.create("cfgse-sync-" + System.nanoTime(), provider, "m1");
        try {
            Tx.run(() -> {
                Agent a = Agent.findById(agent.id);
                a.enabled = true;
                a.save();
            });
            assertNull(ConfigService.setWithSideEffects("provider." + provider + ".apiKey", "k"));
            Agent after = Tx.run(() -> Agent.findById(agent.id));
            assertFalse(after.enabled, "an agent whose model no configured provider offers is disabled by the write");
        } finally {
            ConfigService.delete("provider." + provider + ".apiKey");
            Tx.run(() -> AgentService.delete(Agent.findById(agent.id)));
        }
    }

    @Test
    void theLoadtestMockFlagReregistersTheTools() {
        ToolRegistrySync.canonicalForTest();
        try {
            assertNull(ConfigService.setWithSideEffects("provider.loadtest-mock.enabled", "false"));
            assertNull(ToolRegistry.lookupTool("loadtest_sleep"));
            assertNull(ConfigService.setWithSideEffects("provider.loadtest-mock.enabled", "true"));
            assertNotNull(ToolRegistry.lookupTool("loadtest_sleep"), "enabling the mock provider registers its tool");
        } finally {
            ConfigService.delete("provider.loadtest-mock.enabled");
            ToolRegistrySync.release();
        }
    }
}
