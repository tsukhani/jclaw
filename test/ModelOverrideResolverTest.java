import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ModelOverrideResolver;

/**
 * Unit tests for {@link ModelOverrideResolver} (JCLAW-269). Pure value-level
 * checks — no DB, no transaction. The resolver is stateless and takes
 * fully-constructed entities, so plain field assignment is enough.
 */
class ModelOverrideResolverTest extends UnitTest {

    @Test
    void agentDefaultsApplyWhenConversationHasNoOverride() {
        var agent = new Agent();
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        var conv = new Conversation();
        // Both override columns null — fall through to the agent.

        var r = ModelOverrideResolver.resolve(conv, agent);
        assertEquals("openrouter", r.provider());
        assertEquals("gpt-4.1", r.modelId());
        assertFalse(ModelOverrideResolver.hasOverride(conv));
    }

    @Test
    void conversationOverrideWinsWhenBothColumnsSet() {
        var agent = new Agent();
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        var conv = new Conversation();
        conv.modelProviderOverride = "anthropic";
        conv.modelIdOverride = "claude-opus-4";

        var r = ModelOverrideResolver.resolve(conv, agent);
        assertEquals("anthropic", r.provider());
        assertEquals("claude-opus-4", r.modelId());
        assertTrue(ModelOverrideResolver.hasOverride(conv));
    }

    @Test
    void halfSetOverrideTreatedAsNoOverride() {
        // Defensive: the schema contract says "both or neither". A half-set
        // row is undefined input; resolver coerces it to "use the agent
        // default" rather than mixing the agent's modelId with the
        // conversation's provider override.
        var agent = new Agent();
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";

        var convProviderOnly = new Conversation();
        convProviderOnly.modelProviderOverride = "anthropic";
        // modelIdOverride still null.
        var r1 = ModelOverrideResolver.resolve(convProviderOnly, agent);
        assertEquals("openrouter", r1.provider());
        assertEquals("gpt-4.1", r1.modelId());
        assertFalse(ModelOverrideResolver.hasOverride(convProviderOnly));

        var convIdOnly = new Conversation();
        convIdOnly.modelIdOverride = "claude-opus-4";
        var r2 = ModelOverrideResolver.resolve(convIdOnly, agent);
        assertEquals("openrouter", r2.provider());
        assertEquals("gpt-4.1", r2.modelId());
        assertFalse(ModelOverrideResolver.hasOverride(convIdOnly));
    }

    @Test
    void nullConversationFallsBackToAgent() {
        var agent = new Agent();
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        var r = ModelOverrideResolver.resolve(null, agent);
        assertEquals("openrouter", r.provider());
        assertEquals("gpt-4.1", r.modelId());
        assertFalse(ModelOverrideResolver.hasOverride(null));
    }

    @Test
    void nullAgentReturnsNullsWhenNoOverride() {
        var conv = new Conversation();
        var r = ModelOverrideResolver.resolve(conv, null);
        assertNull(r.provider());
        assertNull(r.modelId());
    }

    @Test
    void nullAgentStillResolvesViaOverride() {
        // Override is sufficient on its own — callers without a managed
        // Agent (test fixtures, early bootstrap paths) should still see
        // the override take effect.
        var conv = new Conversation();
        conv.modelProviderOverride = "anthropic";
        conv.modelIdOverride = "claude-opus-4";
        var r = ModelOverrideResolver.resolve(conv, null);
        assertEquals("anthropic", r.provider());
        assertEquals("claude-opus-4", r.modelId());
    }
}
