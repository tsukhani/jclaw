import agents.ModelResolver;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;

/**
 * JCLAW-1196: the per-turn thinking resolution honours a conversation override ahead of the
 * agent's default, and still intersects with what the effective model advertises.
 */
class ModelResolverThinkingTest extends UnitTest {

    private static final String PROVIDER = "jclaw1196-resolver";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.set("provider." + PROVIDER + ".baseUrl", "http://127.0.0.1:9999/v1");
        ConfigService.set("provider." + PROVIDER + ".apiKey", "sk-test");
        ConfigService.set("provider." + PROVIDER + ".models",
                "[{\"id\":\"thinker\",\"contextWindow\":1000,\"supportsThinking\":true,\"thinkingLevels\":[\"low\",\"high\"]},"
                        + "{\"id\":\"plain\",\"contextWindow\":1000}]");
        ProviderRegistry.refresh();
    }

    private static Agent agent(String thinking) {
        var a = new Agent();
        a.modelProvider = PROVIDER;
        a.modelId = "thinker";
        a.thinkingMode = thinking;
        return a;
    }

    @Test
    void overrideBeatsTheAgentDefaultAndOffSilencesIt() {
        var provider = ProviderRegistry.get(PROVIDER);
        var inherits = new Conversation();
        assertEquals("high", ModelResolver.resolveThinkingMode(agent("high"), inherits, provider));

        var low = new Conversation();
        low.thinkingModeOverride = "low";
        assertEquals("low", ModelResolver.resolveThinkingMode(agent("high"), low, provider));

        var off = new Conversation();
        off.thinkingModeOverride = Conversation.THINKING_OFF;
        assertNull(ModelResolver.resolveThinkingMode(agent("high"), off, provider));

        var onWhereAgentIsOff = new Conversation();
        onWhereAgentIsOff.thinkingModeOverride = "high";
        assertEquals("high", ModelResolver.resolveThinkingMode(agent(null), onWhereAgentIsOff, provider),
                "a conversation can turn thinking on for an agent whose default is off");
    }

    @Test
    void anOverrideTheEffectiveModelCannotHonourResolvesToNull() {
        var provider = ProviderRegistry.get(PROVIDER);
        var onPlainModel = new Conversation();
        onPlainModel.modelProviderOverride = PROVIDER;
        onPlainModel.modelIdOverride = "plain";
        onPlainModel.thinkingModeOverride = "high";
        assertNull(ModelResolver.resolveThinkingMode(agent("high"), onPlainModel, provider),
                "a model without thinking ignores the override rather than sending an unknown level");
    }
}
