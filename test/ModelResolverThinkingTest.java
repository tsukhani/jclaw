import agents.ModelResolver;
import llm.ProviderRegistry;
import llm.routing.ReasoningEffort;
import llm.routing.RouteDecision;
import llm.routing.RouteDecision.Target;
import llm.routing.RoutedTurn;
import llm.routing.TaskClass;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;

import java.util.List;

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
                        + "{\"id\":\"defaults\",\"contextWindow\":1000,\"supportsThinking\":true},"
                        + "{\"id\":\"glm\",\"contextWindow\":1000,\"supportsThinking\":true,\"thinkingLevels\":[\"low\",\"high\",\"max\"]},"
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

    private static String routed(String modelId, ReasoningEffort effort, Conversation conv) {
        var router = new Agent();
        router.modelProvider = "router";
        router.modelId = "auto";
        var decision = new RouteDecision(TaskClass.CHAT, effort, new Target(PROVIDER, modelId), null,
                List.of("test"), List.of(), false, false, false);
        return RoutedTurn.callWith(decision, conv,
                () -> ModelResolver.resolveThinkingMode(router, conv, ProviderRegistry.get(PROVIDER)));
    }

    @Test
    void aRoutedReasoningModelThinksAtTheEffortTheRouterChose() {
        assertEquals("low", routed("defaults", ReasoningEffort.LOW, new Conversation()));
        assertEquals("medium", routed("defaults", ReasoningEffort.MEDIUM, new Conversation()));
        assertEquals("high", routed("defaults", ReasoningEffort.HIGH, new Conversation()));
        assertNull(routed("plain", ReasoningEffort.HIGH, new Conversation()), "a routed model without thinking stays off");
    }

    @Test
    void anEffortTheLadderLacksLandsOnTheNearestRung() {
        assertEquals("low", routed("glm", ReasoningEffort.LOW, new Conversation()));
        assertEquals("high", routed("glm", ReasoningEffort.MEDIUM, new Conversation()), "low/high/max: medium is the middle");
        assertEquals("high", routed("glm", ReasoningEffort.HIGH, new Conversation()), "a named rung wins over the top one");
        assertEquals("high", routed("thinker", ReasoningEffort.MEDIUM, new Conversation()), "low/high: size/2 picks high");
        assertEquals("max", ReasoningEffort.HIGH.fit(List.of("minimal", "max")), "no high rung: the top one");
        assertNull(ReasoningEffort.MEDIUM.fit(List.of()));
    }

    @Test
    void aConversationThinkingChoiceBeatsTheRoutedEffort() {
        var off = new Conversation();
        off.thinkingModeOverride = Conversation.THINKING_OFF;
        assertNull(routed("defaults", ReasoningEffort.HIGH, off));

        var low = new Conversation();
        low.thinkingModeOverride = "low";
        assertEquals("low", routed("defaults", ReasoningEffort.HIGH, low));

        var max = new Conversation();
        max.thinkingModeOverride = "max";
        assertNull(routed("defaults", ReasoningEffort.HIGH, max), "a level the routed model does not advertise is still dropped");
    }
}
