import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import services.AgentService;
import services.ConfigService;

public class ConfigServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @Test
    public void setAndGet() {
        ConfigService.set("test.key", "test-value");
        assertEquals("test-value", ConfigService.get("test.key"));
    }

    @Test
    public void getWithDefault() {
        assertEquals("fallback", ConfigService.get("missing.key", "fallback"));
    }

    @Test
    public void getReturnsNullForMissing() {
        assertNull(ConfigService.get("nonexistent"));
    }

    @Test
    public void setOverwrites() {
        ConfigService.set("key", "v1");
        ConfigService.set("key", "v2");
        assertEquals("v2", ConfigService.get("key"));
    }

    @Test
    public void deleteRemovesEntry() {
        ConfigService.set("to-delete", "value");
        assertNotNull(ConfigService.get("to-delete"));
        ConfigService.delete("to-delete");
        assertNull(ConfigService.get("to-delete"));
    }

    @Test
    public void listAllReturnsAll() {
        ConfigService.set("a", "1");
        ConfigService.set("b", "2");
        var all = ConfigService.listAll();
        assertEquals(2, all.size());
    }

    @Test
    public void isSensitiveDetectsKeys() {
        assertTrue(ConfigService.isSensitive("provider.openrouter.apiKey"));
        assertTrue(ConfigService.isSensitive("jclaw.admin.password"));
        assertTrue(ConfigService.isSensitive("slack.signing.secret"));
        assertTrue(ConfigService.isSensitive("telegram.bot.token"));
        assertFalse(ConfigService.isSensitive("provider.openrouter.baseUrl"));
        assertFalse(ConfigService.isSensitive("jclaw.workspace.path"));
    }

    @Test
    public void maskValueHidesSensitive() {
        assertEquals("sk-t****", ConfigService.maskValue("apiKey", "sk-test-123"));
        assertEquals("https://openrouter.ai/api/v1",
                ConfigService.maskValue("baseUrl", "https://openrouter.ai/api/v1"));
    }

    @Test
    public void cacheServesWithoutDb() {
        ConfigService.set("cached", "value");
        assertEquals("value", ConfigService.get("cached"));
        // Value is in cache, even if DB row were somehow gone the cache would serve it
    }

    // --- setWithSideEffects: the privilege-guard path ---

    @Test
    public void setWithSideEffectsRejectsShellBypassForNonMainAgent() {
        // Security-critical: shell bypass / global path privileges are only
        // legal for the main agent. A custom agent attempting to flip them
        // must be rejected BEFORE the set lands in the DB.
        var custom = AgentService.create("not-main", "openrouter", "gpt-4.1");
        assertFalse(custom.isMain(), "precondition: custom agent is not main");

        var error = ConfigService.setWithSideEffects(
                "agent.not-main.shell.bypassAllowlist", "true");
        assertNotNull(error, "non-main agent must be rejected with an error string");
        assertTrue(error.toLowerCase().contains("main"),
                "error must mention the main-agent restriction");

        // Critical invariant: the rejected value must NOT be persisted.
        assertNull(ConfigService.get("agent.not-main.shell.bypassAllowlist"));
    }

    @Test
    public void setWithSideEffectsRejectsAllowGlobalPathsForNonMainAgent() {
        AgentService.create("helper", "openrouter", "gpt-4.1");
        var error = ConfigService.setWithSideEffects(
                "agent.helper.shell.allowGlobalPaths", "true");
        assertNotNull(error);
        assertNull(ConfigService.get("agent.helper.shell.allowGlobalPaths"));
    }

    @Test
    public void setWithSideEffectsAcceptsShellPrivilegeForMainAgent() {
        // Seed main agent via direct construction — AgentService.create rejects
        // "main" by convention, but the router identifies it by name so it
        // must exist under that name for the privilege check to pass.
        var main = new Agent();
        main.name = Agent.MAIN_AGENT_NAME;
        main.modelProvider = "openrouter";
        main.modelId = "gpt-4.1";
        main.save();

        var error = ConfigService.setWithSideEffects(
                "agent." + Agent.MAIN_AGENT_NAME + ".shell.bypassAllowlist", "true");
        assertNull(error, "main agent must be allowed");
        assertEquals("true", ConfigService.get(
                "agent." + Agent.MAIN_AGENT_NAME + ".shell.bypassAllowlist"));
    }

    @Test
    public void deleteEvictsCacheSoSubsequentGetReturnsNull() {
        // After delete, the in-memory cache entry must be removed or invalidated
        // so the next get() either returns null (if the caller knows to avoid
        // defaults) or forces a DB round-trip that also returns null.
        ConfigService.set("evict.me", "present");
        assertEquals("present", ConfigService.get("evict.me"));

        ConfigService.delete("evict.me");
        assertNull(ConfigService.get("evict.me"),
                "cache must not serve a stale value after delete");
    }
}
