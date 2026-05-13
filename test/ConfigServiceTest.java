import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import services.AgentService;
import services.ConfigService;

class ConfigServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @Test
    void setAndGet() {
        ConfigService.set("test.key", "test-value");
        assertEquals("test-value", ConfigService.get("test.key"));
    }

    @Test
    void getWithDefault() {
        assertEquals("fallback", ConfigService.get("missing.key", "fallback"));
    }

    @Test
    void getReturnsNullForMissing() {
        assertNull(ConfigService.get("nonexistent"));
    }

    @Test
    void setOverwrites() {
        ConfigService.set("key", "v1");
        ConfigService.set("key", "v2");
        assertEquals("v2", ConfigService.get("key"));
    }

    @Test
    void deleteRemovesEntry() {
        ConfigService.set("to-delete", "value");
        assertNotNull(ConfigService.get("to-delete"));
        ConfigService.delete("to-delete");
        assertNull(ConfigService.get("to-delete"));
    }

    @Test
    void listAllReturnsAll() {
        ConfigService.set("a", "1");
        ConfigService.set("b", "2");
        var all = ConfigService.listAll();
        assertEquals(2, all.size());
    }

    @Test
    void isSensitiveDetectsKeys() {
        assertTrue(ConfigService.isSensitive("provider.openrouter.apiKey"));
        assertTrue(ConfigService.isSensitive("jclaw.admin.password"));
        assertTrue(ConfigService.isSensitive("slack.signing.secret"));
        assertTrue(ConfigService.isSensitive("telegram.bot.token"));
        assertFalse(ConfigService.isSensitive("provider.openrouter.baseUrl"));
        assertFalse(ConfigService.isSensitive("jclaw.workspace.path"));
    }

    @Test
    void maskValueHidesSensitive() {
        assertEquals("sk-t****", ConfigService.maskValue("apiKey", "sk-test-123"));
        assertEquals("https://openrouter.ai/api/v1",
                ConfigService.maskValue("baseUrl", "https://openrouter.ai/api/v1"));
    }

    @Test
    void cacheServesWithoutDb() {
        ConfigService.set("cached", "value");
        assertEquals("value", ConfigService.get("cached"));
        // Value is in cache, even if DB row were somehow gone the cache would serve it
    }

    // --- setWithSideEffects: the privilege-guard path ---

    @Test
    void setWithSideEffectsRejectsShellBypassForNonMainAgent() {
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
    void setWithSideEffectsRejectsAllowGlobalPathsForNonMainAgent() {
        AgentService.create("helper", "openrouter", "gpt-4.1");
        var error = ConfigService.setWithSideEffects(
                "agent.helper.shell.allowGlobalPaths", "true");
        assertNotNull(error);
        assertNull(ConfigService.get("agent.helper.shell.allowGlobalPaths"));
    }

    @Test
    void setWithSideEffectsAcceptsShellPrivilegeForMainAgent() {
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
    void deleteEvictsCacheSoSubsequentGetReturnsNull() {
        // After delete, the in-memory cache entry must be removed or invalidated
        // so the next get() either returns null (if the caller knows to avoid
        // defaults) or forces a DB round-trip that also returns null.
        ConfigService.set("evict.me", "present");
        assertEquals("present", ConfigService.get("evict.me"));

        ConfigService.delete("evict.me");
        assertNull(ConfigService.get("evict.me"),
                "cache must not serve a stale value after delete");
    }

    // --- setWithSideEffects: ollama-cloud LLM key → ollama search key linkage ---

    @Test
    void setWithSideEffectsMirrorsOllamaCloudKeyToSearchWhenSearchKeyEmpty() {
        // Fresh state: no search.ollama.apiKey set. Operator sets the LLM key
        // via Settings — both providers hit the same Ollama account, so the
        // search key should auto-populate AND the search provider should flip
        // enabled=true. Saves the operator a redundant paste.
        assertNull(ConfigService.get("search.ollama.apiKey"));

        var error = ConfigService.setWithSideEffects(
                "provider.ollama-cloud.apiKey", "sk-ollama-abc");
        assertNull(error);

        assertEquals("sk-ollama-abc", ConfigService.get("provider.ollama-cloud.apiKey"));
        assertEquals("sk-ollama-abc", ConfigService.get("search.ollama.apiKey"),
                "search key must be auto-populated from the LLM key");
        assertEquals("true", ConfigService.get("search.ollama.enabled"),
                "web search must be enabled when the search key is auto-populated");
    }

    @Test
    void setWithSideEffectsLeavesExistingSearchKeyAlone() {
        // The "set once, owned by you" rule: once the operator has explicitly
        // set search.ollama.apiKey, a later rotation of the LLM key must NOT
        // overwrite it. Search-key independence is preserved.
        ConfigService.set("search.ollama.apiKey", "operator-set-key");
        ConfigService.set("search.ollama.enabled", "false");

        var error = ConfigService.setWithSideEffects(
                "provider.ollama-cloud.apiKey", "sk-ollama-xyz");
        assertNull(error);

        assertEquals("operator-set-key", ConfigService.get("search.ollama.apiKey"),
                "operator-set search key must not be overwritten");
        assertEquals("false", ConfigService.get("search.ollama.enabled"),
                "operator-set enabled flag must not be flipped");
    }

    @Test
    void setWithSideEffectsDoesNotMirrorBlankLlmKey() {
        // Clearing the LLM key (operator pastes empty / removes credentials)
        // must not mirror the empty value into the search key — that would
        // silently break a working search-only setup. The mirror is a
        // strictly-additive convenience.
        var error = ConfigService.setWithSideEffects(
                "provider.ollama-cloud.apiKey", "");
        assertNull(error);

        assertNull(ConfigService.get("search.ollama.apiKey"),
                "blank LLM key must not propagate to the search key");
        assertNull(ConfigService.get("search.ollama.enabled"),
                "blank LLM key must not enable the search provider");
    }

    @Test
    void setWithSideEffectsIgnoresUnrelatedProviderApiKey() {
        // Sanity guard: the linkage is specific to provider.ollama-cloud.apiKey.
        // Setting a different provider's apiKey must not touch search.ollama.*.
        var error = ConfigService.setWithSideEffects(
                "provider.openrouter.apiKey", "sk-or-some-key");
        assertNull(error);

        assertNull(ConfigService.get("search.ollama.apiKey"));
        assertNull(ConfigService.get("search.ollama.enabled"));
    }
}
