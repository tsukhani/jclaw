import llm.LlmResilience;
import llm.ProviderRegistry;
import llm.routing.ModelRouter;
import llm.routing.ModelRouter.RouteRequest;
import llm.routing.RouteDecision;
import llm.routing.RouteDecision.Target;
import llm.routing.RouterPolicy;
import llm.routing.RouterPolicy.Candidate;
import llm.routing.SubscriptionUsage;
import llm.routing.TaskClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import utils.CircuitBreakers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JCLAW-1222: how the router chooses among the operator's models. Each test mints its own provider
 * names — the registry, the usage cache and the breaker registry are all process-global, and play1
 * runs test classes side by side — and passes its policy explicitly rather than writing router keys.
 */
class ModelRouterTest extends UnitTest {

    private static final String CHAT = "hello, how are you today";
    private static final String REASONING = "Prove that there are infinitely many primes";

    private String sub;
    private String sub2;
    private String perToken;
    private final List<String> minted = new ArrayList<>();

    @BeforeEach
    void seedProviders() {
        var id = UUID.randomUUID().toString().substring(0, 8);
        sub = provider("mr-sub-" + id, true,
                model("light", 100_000, false), model("heavy", 100_000, false), model("small-window", 1_000, false),
                model("eyes", 100_000, true));
        sub2 = provider("mr-sub2-" + id, true, model("light", 100_000, false));
        perToken = provider("mr-pt-" + id, false, model("light", 100_000, false), model("heavy", 100_000, false));
        for (var i = 0; i < 50 && !minted.stream().allMatch(n -> ProviderRegistry.get(n) != null); i++) {
            ProviderRegistry.refresh();
        }
        minted.forEach(n -> assertNotNull(ProviderRegistry.get(n), "test precondition: " + n + " must register"));
    }

    @AfterEach
    void cleanUp() {
        for (var name : minted) {
            SubscriptionUsage.clearForTest(name);
            CircuitBreakers.remove(LlmResilience.breakerName(name));
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models", ".paymentModality"}) {
                ConfigService.delete("provider." + name + suffix);
            }
        }
    }

    private String provider(String name, boolean subscription, String... models) {
        ConfigService.set("provider." + name + ".baseUrl", "http://127.0.0.1:1/v1");
        ConfigService.set("provider." + name + ".apiKey", "sk-test");
        ConfigService.set("provider." + name + ".models", "[" + String.join(",", models) + "]");
        if (subscription) ConfigService.set("provider." + name + ".paymentModality", "SUBSCRIPTION");
        minted.add(name);
        return name;
    }

    private static String model(String id, int contextWindow, boolean vision) {
        return "{\"id\":\"%s\",\"name\":\"%s\",\"contextWindow\":%d,\"supportsVision\":%s,\"supportsThinking\":true,\"thinkingLevels\":[\"high\",\"low\"]}"
                .formatted(id, id, contextWindow, vision);
    }

    private static RouterPolicy policy(Map<TaskClass, List<Candidate>> classes) {
        return new RouterPolicy(classes, 0.75, 0.95);
    }

    private static RouteRequest request(String message) {
        return new RouteRequest(message, null, null, 0, 0, false, false, true);
    }

    private static RouteDecision route(RouteRequest request, RouterPolicy policy) {
        var decision = ModelRouter.route(request, policy);
        assertNotNull(decision, "the policy lists resolvable models, so a route must exist");
        return decision;
    }

    @Test
    void prepaidModelsServeBeforePerTokenOnesWhateverTheListedOrder() {
        var p = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(perToken, "light"), new Candidate(sub, "light"))));
        var d = route(request(CHAT), p);
        assertEquals(TaskClass.CHAT, d.taskClass());
        assertEquals(new Target(sub, "light"), d.primary());
        assertEquals(new Target(perToken, "light"), d.fallback(), "the per-token model is kept as the fallback");
    }

    @Test
    void aHeavyClassUsesItsOwnListWhileTheBudgetAllows() {
        SubscriptionUsage.putSnapshotForTest(sub, Map.of("weekly", 0.5));
        var p = policy(Map.of(
                TaskClass.CHAT, List.of(new Candidate(sub, "light")),
                TaskClass.REASONING, List.of(new Candidate(sub, "heavy"))));
        var d = route(request(REASONING), p);
        assertEquals(TaskClass.REASONING, d.taskClass());
        assertEquals(new Target(sub, "heavy"), d.primary());
        assertFalse(d.downshifted());
    }

    @Test
    void pastTheDownshiftThresholdAHeavyClassDropsToTheLightSubscriptionModel() {
        SubscriptionUsage.putSnapshotForTest(sub, Map.of("session", 0.1, "weekly", 0.8));
        var p = policy(Map.of(
                TaskClass.CHAT, List.of(new Candidate(sub, "light")),
                TaskClass.REASONING, List.of(new Candidate(sub, "heavy"), new Candidate(perToken, "heavy"))));
        var d = route(request(REASONING), p);
        assertEquals(new Target(sub, "light"), d.primary(),
                "a lighter subscribed model beats paying per token for the heavy one");
        assertTrue(d.downshifted());
        assertEquals(new Target(perToken, "heavy"), d.fallback());
        assertTrue(d.skipped().stream().anyMatch(s -> s.contains("weekly 80% ≥ downshift 75%")), "skipped: " + d.skipped());
        assertTrue(d.reason().contains("downshifted by budget"));
    }

    @Test
    void theChatClassNeverDownshiftsButAnExhaustedProviderIsSkippedForEveryClass() {
        SubscriptionUsage.putSnapshotForTest(sub, Map.of("weekly", 0.8));
        var chatOnly = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(sub, "light"), new Candidate(perToken, "light"))));
        assertEquals(new Target(sub, "light"), route(request(CHAT), chatOnly).primary());

        SubscriptionUsage.putSnapshotForTest(sub, Map.of("weekly", 0.97));
        var d = route(request(CHAT), chatOnly);
        assertEquals(new Target(perToken, "light"), d.primary(), "per-token is the fallback once the plan is spent");
        assertNull(d.fallback());
    }

    @Test
    void aProviderBenchedForCreditIsSkipped() {
        SubscriptionUsage.noteFailure(new llm.LlmProvider.LlmException.ClientError("HTTP 402",
                new utils.LlmErrorTemplates.Failure(utils.LlmErrorTemplates.Remedy.QUOTA_EXHAUSTED, sub, "light", null, null)));
        var p = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(sub, "light"), new Candidate(sub2, "light"))));
        var d = route(request(CHAT), p);
        assertEquals(new Target(sub2, "light"), d.primary());
        assertTrue(d.skipped().contains(sub + "/light: out of credit"), "skipped: " + d.skipped());
    }

    @Test
    void aBreakerInItsCooldownIsSkippedButNotForever() {
        LlmResilience.breakerFor(sub).trip();
        var p = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(sub, "light"), new Candidate(sub2, "light"))));
        var d = route(request(CHAT), p);
        assertEquals(new Target(sub2, "light"), d.primary());
        assertTrue(d.skipped().contains(sub + "/light: circuit breaker open"), "skipped: " + d.skipped());

        LlmResilience.breakerFor(sub).reset();
        assertEquals(new Target(sub, "light"), route(request(CHAT), p).primary());
    }

    @Test
    void aPromptTooBigForAWindowPrefersOneThatFits() {
        var p = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(sub, "small-window"), new Candidate(sub, "light"))));
        var big = new RouteRequest(CHAT, null, null, 0, 5_000, false, false, true);
        assertEquals(new Target(sub, "light"), route(big, p).primary());
        var small = new RouteRequest(CHAT, null, null, 0, 100, false, false, true);
        assertEquals(new Target(sub, "small-window"), route(small, p).primary());
    }

    @Test
    void anImageTurnPrefersAModelThatSeesButFallsBackWhenNoneDoes() {
        var withEyes = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(sub, "light"), new Candidate(sub, "eyes"))));
        var imageTurn = new RouteRequest(CHAT, null, null, 0, 0, true, false, true);
        assertEquals(new Target(sub, "eyes"), route(imageTurn, withEyes).primary());

        var blind = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(sub, "light"))));
        assertEquals(new Target(sub, "light"), route(imageTurn, blind).primary(),
                "a preference never empties the list: the caption path covers a blind model");
    }

    @Test
    void theModelThePreviousTurnLandedOnIsKeptForTheSameClass() {
        var p = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(sub, "light"), new Candidate(sub2, "light"))));
        var followOn = new RouteRequest(CHAT, TaskClass.CHAT, new Target(sub2, "light"), 0, 0, false, false, true);
        var d = route(followOn, p);
        assertEquals(new Target(sub2, "light"), d.primary());
        assertTrue(d.sticky());

        var otherClass = new RouteRequest(CHAT, TaskClass.REASONING, new Target(sub2, "light"), 0, 0, false, false, true);
        assertEquals(new Target(sub, "light"), route(otherClass, p).primary(), "stickiness is per class");
    }

    @Test
    void stickinessNeverLiftsAPerTokenModelAheadOfAPrepaidOne() {
        var p = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(sub, "light"), new Candidate(perToken, "light"))));
        var followOn = new RouteRequest(CHAT, TaskClass.CHAT, new Target(perToken, "light"), 0, 0, false, false, true);
        var d = route(followOn, p);
        assertEquals(new Target(sub, "light"), d.primary());
        assertFalse(d.sticky());
    }

    @Test
    void whenEveryCandidateIsFilteredOutTheFiltersAreRelaxedRatherThanFailTheTurn() {
        SubscriptionUsage.putSnapshotForTest(sub, Map.of("weekly", 0.99));
        var p = policy(Map.of(TaskClass.CHAT, List.of(new Candidate(sub, "light"))));
        var d = route(request(CHAT), p);
        assertEquals(new Target(sub, "light"), d.primary());
        assertTrue(d.relaxed());
    }

    @Test
    void unresolvableCandidatesAreSkippedWithTheirReason() {
        var p = policy(Map.of(TaskClass.CHAT, List.of(
                new Candidate("mr-missing-" + UUID.randomUUID(), "x"), new Candidate(sub, "no-such-model"),
                new Candidate(sub, "light"))));
        var d = route(request(CHAT), p);
        assertEquals(new Target(sub, "light"), d.primary());
        assertTrue(d.skipped().stream().anyMatch(s -> s.endsWith("provider not configured")), "skipped: " + d.skipped());
        assertTrue(d.skipped().contains(sub + "/no-such-model: model not registered"), "skipped: " + d.skipped());
    }

    @Test
    void nothingResolvableMeansNoRoute() {
        var p = policy(Map.of(TaskClass.CHAT, List.of(new Candidate("mr-missing-" + UUID.randomUUID(), "x"))));
        assertNull(ModelRouter.route(request(CHAT), p));
        assertNull(ModelRouter.route(request(CHAT), policy(Map.of())), "an unconfigured router routes nothing");
    }

    @Test
    void theCatalogEntryAdvertisesTheUnionOfItsModels() {
        var p = policy(Map.of(
                TaskClass.CHAT, List.of(new Candidate(sub, "small-window")),
                TaskClass.CODING, List.of(new Candidate(sub, "eyes"))));
        var model = ModelRouter.catalogModel(p);
        assertEquals("auto", model.id());
        assertEquals(100_000, model.contextWindow());
        assertTrue(model.supportsVision(), "one listed model sees, so an image turn can be steered to it");
        assertTrue(model.toolCallingSupported());
        assertEquals(List.of("low", "high"), model.thinkingLevels(), "levels in ladder order, not listing order");
    }

    @Test
    void isPrepaidCoversSubscriptionsButNotPerTokenProviders() {
        assertTrue(ModelRouter.isPrepaid(ProviderRegistry.get(sub).config()));
        assertFalse(ModelRouter.isPrepaid(ProviderRegistry.get(perToken).config()));
    }
}
