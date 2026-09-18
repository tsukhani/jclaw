import llm.ProviderRegistry;
import llm.routing.RouterPolicy;
import llm.routing.RouterPolicy.Candidate;
import llm.routing.TaskClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JCLAW-1222: what a router policy write accepts, and how a class without its own list reads. */
class RouterPolicyTest extends UnitTest {

    private String provider;

    @BeforeEach
    void seedProvider() {
        provider = "rp-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:1/v1");
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models", "[{\"id\":\"m1\",\"name\":\"M1\",\"contextWindow\":1000}]");
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the provider must register");
    }

    @AfterEach
    void removeProvider() {
        for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
            ConfigService.delete("provider." + provider + suffix);
        }
    }

    private String list(String providerName, String model) {
        return "[{\"provider\":\"" + providerName + "\",\"model\":\"" + model + "\"}]";
    }

    @Test
    void aListOfRegisteredModelsIsAccepted() {
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.modelsKey(TaskClass.REASONING), list(provider, "m1")));
    }

    @Test
    void clearingAListIsAccepted() {
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.modelsKey(TaskClass.CHAT), ""));
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.modelsKey(TaskClass.CHAT), "[]"));
    }

    @Test
    void malformedListsAreRefusedWithTheExpectedShape() {
        var key = RouterPolicy.modelsKey(TaskClass.CHAT);
        for (var bad : new String[] {"not json", "{}", "[\"m1\"]", "[{\"provider\":\"x\"}]", "[{\"provider\":\"\",\"model\":\"m\"}]"}) {
            var rejection = RouterPolicy.rejectionFor(key, bad);
            assertNotNull(rejection, () -> "accepted " + bad);
            assertTrue(rejection.contains("JSON array"), rejection);
        }
    }

    @Test
    void anUnconfiguredProviderOrUnregisteredModelIsRefused() {
        var key = RouterPolicy.modelsKey(TaskClass.CHAT);
        assertTrue(RouterPolicy.rejectionFor(key, list("rp-missing-provider", "m1")).contains("not configured"));
        assertTrue(RouterPolicy.rejectionFor(key, list(provider, "nope")).contains("has no model"));
    }

    @Test
    void theRouterCannotListItself() {
        assertTrue(RouterPolicy.rejectionFor(RouterPolicy.modelsKey(TaskClass.CHAT), list("router", "auto"))
                .contains("router itself"));
    }

    @Test
    void anUnknownRouterKeyIsRefusedRatherThanSilentlyIgnored() {
        assertNotNull(RouterPolicy.rejectionFor("router.cheap.models", list(provider, "m1")));
        assertNotNull(RouterPolicy.rejectionFor("router.budget.threshold", "0.5"));
    }

    @Test
    void thresholdsMustBeFractionsInOrder() {
        for (var bad : new String[] {"0", "-0.1", "1.5", "NaN", "lots"}) {
            assertNotNull(RouterPolicy.rejectionFor(RouterPolicy.DOWNSHIFT_AT, bad), () -> "accepted " + bad);
        }
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.DOWNSHIFT_AT, "0.6"));
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.EXHAUSTED_AT, "1"));
        // Against the defaults (downshift 0.75, exhausted 0.95): each must stay on its side of the other.
        assertTrue(RouterPolicy.rejectionFor(RouterPolicy.DOWNSHIFT_AT, "0.96").contains("must be below"));
        assertTrue(RouterPolicy.rejectionFor(RouterPolicy.EXHAUSTED_AT, "0.7").contains("must be below"));
    }

    @Test
    void theClassifierPairIsCheckedAgainstTheProviderRegistry() {
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, provider));
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, ""), "clearing is allowed");
        assertTrue(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, "rp-nope").contains("not configured"));
        assertTrue(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, "router").contains("router itself"),
                "the router cannot classify the prompt it is routing");
        // The model half is checked against whatever provider is stored, so a bare model needs one.
        assertTrue(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "m1").contains("needs"));
    }

    @Test
    void theClassifierTimeoutMustBeSecondsInRange() {
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_TIMEOUT_SECONDS, "8"));
        for (var bad : new String[] {"0", "-1", "61", "soon", ""}) {
            assertNotNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_TIMEOUT_SECONDS, bad), () -> "accepted " + bad);
        }
    }

    @Test
    void aPolicyWithoutAClassifierUsesTheRules() {
        var policy = new RouterPolicy(Map.of(TaskClass.CHAT, List.of(new Candidate("p", "m"))), 0.75, 0.95);
        assertNull(policy.classifier());
        assertEquals(RouterPolicy.DEFAULT_CLASSIFIER_TIMEOUT_SECONDS, policy.classifierTimeoutSeconds());
    }

    @Test
    void aClassWithoutItsOwnListUsesTheChatList() {
        var chat = List.of(new Candidate("p", "light"));
        var reasoning = List.of(new Candidate("p", "heavy"));
        var policy = new RouterPolicy(Map.of(TaskClass.CHAT, chat, TaskClass.REASONING, reasoning), 0.75, 0.95);
        assertEquals(reasoning, policy.candidates(TaskClass.REASONING));
        assertEquals(chat, policy.candidates(TaskClass.CODING));
        assertTrue(policy.available());
        assertFalse(new RouterPolicy(Map.of(TaskClass.REASONING, reasoning), 0.75, 0.95).available(),
                "without a chat list the router has no default and is not offered");
    }
}
