import llm.routing.RouteDecision;
import llm.routing.RouteDecision.Target;
import llm.routing.RoutedTurn;
import llm.routing.TaskClass;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ModelOverrideResolver;

import java.util.List;

/**
 * JCLAW-1222: the routed model reaches the resolver for the conversation it was bound for, and
 * nowhere else — not outside the turn, not in another conversation's nested run.
 */
class RoutedTurnTest extends UnitTest {

    private static final Target PRIMARY = new Target("ollama-cloud", "glm-5.3-flash");
    private static final Target FALLBACK = new Target("openrouter", "z-ai/glm-5.3-flash");

    private static RouteDecision decision(Target fallback) {
        return new RouteDecision(TaskClass.CHAT, PRIMARY, fallback, List.of("no task markers"), List.of(),
                false, false, false);
    }

    private static Agent routerAgent() {
        var agent = new Agent();
        agent.name = "routed-turn-test";
        agent.modelProvider = "router";
        agent.modelId = "auto";
        return agent;
    }

    private static Conversation conversation(Long id) {
        var conversation = new Conversation();
        conversation.id = id;
        return conversation;
    }

    @Test
    void insideTheBindingTheResolverReturnsTheRoutedModel() {
        var agent = routerAgent();
        var bound = conversation(41L);
        var resolved = RoutedTurn.callWith(decision(FALLBACK), bound, () -> ModelOverrideResolver.resolve(bound, agent));
        assertEquals("ollama-cloud", resolved.provider());
        assertEquals("glm-5.3-flash", resolved.modelId());
        assertEquals("router", ModelOverrideResolver.provider(bound, agent), "outside the binding the stored pair stands");
    }

    @Test
    void aReFetchedInstanceOfTheSameConversationStillMatches() {
        var agent = routerAgent();
        var refetched = conversation(42L);
        var provider = RoutedTurn.callWith(decision(FALLBACK), conversation(42L),
                () -> ModelOverrideResolver.provider(refetched, agent));
        assertEquals("ollama-cloud", provider);
    }

    @Test
    void anotherConversationInsideTheTurnResolvesItsOwnModel() {
        var agent = routerAgent();
        var nestedChild = conversation(99L);
        var provider = RoutedTurn.callWith(decision(FALLBACK), conversation(43L),
                () -> ModelOverrideResolver.provider(nestedChild, agent));
        assertEquals("router", provider, "a subagent's conversation routes on its own turn, not the parent's");
    }

    @Test
    void anUnsavedTaskStubMatchesOnlyItself() {
        var agent = routerAgent();
        var stub = conversation(null);
        var otherStub = conversation(null);
        RoutedTurn.callWith(decision(FALLBACK), stub, () -> {
            assertEquals("glm-5.3-flash", ModelOverrideResolver.modelId(stub, agent));
            assertEquals("auto", ModelOverrideResolver.modelId(otherStub, agent));
            assertNull(RoutedTurn.current(null));
            return null;
        });
    }

    @Test
    void promotionHandsTheRestOfTheTurnToTheFallbackOnce() {
        var agent = routerAgent();
        var bound = conversation(44L);
        RoutedTurn.callWith(decision(FALLBACK), bound, () -> {
            var binding = RoutedTurn.current(bound);
            assertNotNull(binding);
            assertFalse(binding.failedOver());
            assertEquals(FALLBACK, binding.promote());
            assertTrue(binding.failedOver());
            assertEquals("openrouter", ModelOverrideResolver.provider(bound, agent));
            assertNull(binding.fallback(), "the fallback is in use; there is nothing left to fail over to");
            assertNull(binding.promote());
            return null;
        });
    }

    @Test
    void withoutAFallbackPromotionDoesNothing() {
        var bound = conversation(45L);
        RoutedTurn.callWith(decision(null), bound, () -> {
            var binding = RoutedTurn.current(bound);
            assertNull(binding.promote());
            assertEquals(PRIMARY, binding.active());
            return null;
        });
    }

    @Test
    void theRouteJsonNamesTheServedModelAndFlagsAFailover() {
        var json = decision(FALLBACK).toJson(FALLBACK, true);
        assertEquals("chat", json.get("class").getAsString());
        assertEquals("openrouter", json.get("provider").getAsString());
        assertEquals("z-ai/glm-5.3-flash", json.get("model").getAsString());
        assertTrue(json.get("failover").getAsBoolean());
        assertFalse(json.has("downshifted"), "a flag that did not happen is absent, not false");
    }
}
