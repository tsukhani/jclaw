import llm.ProviderRegistry;
import llm.routing.RouterPolicy;
import llm.routing.RouterPolicy.Candidate;
import llm.routing.TaskClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.decision.LayaDecision;
import services.decision.OllamaDecision;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JCLAW-1222: what a router policy write accepts, and how a class without its own list reads. */
class RouterPolicyTest extends UnitTest {

    private String provider;

    @BeforeEach
    void seedProvider() {
        // The classifier keys and decision.*.models this class stores are read by ModelRouterTurnTest's turns.
        JevBreakerTestSync.acquire();
        provider = "rp-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:1/v1");
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models", "[{\"id\":\"m1\",\"name\":\"M1\",\"contextWindow\":1000}]");
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the provider must register");
    }

    @AfterEach
    void removeProvider() {
        try {
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
        } finally {
            JevBreakerTestSync.release();
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
    void jevIsAcceptedAsTheClassifierWithItsOneModel() {
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, RouterPolicy.JEV),
                "jev is not a registered provider, and needs none");
        // Only one half is ever stored here, so a concurrent RouterPolicy.load() never sees a classifier.
        ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, RouterPolicy.JEV);
        try {
            assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "jev-latest"));
            var wrongModel = RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "m1");
            assertNotNull(wrongModel, "jev has exactly one model");
            assertTrue(wrongModel.contains("jev-latest"), wrongModel);
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
        }
    }

    @Test
    void anOllamaClassifierModelMustBeASelectedDecisionModel() {
        // JCLAW-1336. No other class writes decision.ollama.models, and only one classifier half is stored.
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, OllamaDecision.PROVIDER),
                "ollama-decision is not a registered provider, and needs none");
        ConfigService.set(OllamaDecision.MODELS_KEY, "[\"tev1\",\"nimble\"]");
        ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, OllamaDecision.PROVIDER);
        try {
            assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "tev1"));
            assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, " nimble "));
            for (var bad : new String[] {"tev1:0.8b", "llama3.2", "jev-latest"}) {
                var rejected = RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, bad);
                assertNotNull(rejected, () -> "accepted " + bad);
                assertTrue(rejected.contains(OllamaDecision.MODELS_KEY), rejected);
            }
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
            ConfigService.delete(OllamaDecision.MODELS_KEY);
        }
    }

    @Test
    void aLayaClassifierModelMustBeATickedCheckpoint() {
        // JCLAW-1447. Only ApiDecisionControllerTest also writes decision.laya.models, under the same lock.
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, LayaDecision.PROVIDER),
                "laya is not a registered provider, and needs none");
        ConfigService.set(LayaDecision.MODELS_KEY, "[\"english\",\"multilingual\"]");
        ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, LayaDecision.PROVIDER);
        try {
            assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "english"));
            assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, " multilingual "));
            // typed-decisions is a checkpoint, but not a ticked one.
            for (var bad : new String[] {"typed-decisions", "tev1", "jev-latest", "English"}) {
                var rejected = RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, bad);
                assertNotNull(rejected, () -> "accepted " + bad);
                assertTrue(rejected.contains(LayaDecision.MODELS_KEY), rejected);
            }
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
            ConfigService.delete(LayaDecision.MODELS_KEY);
        }
        assertNotNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, "rp-nope"),
                "another unregistered name is still refused");
    }

    @Test
    void aSelectionCannotDropTheTickedModelTheClassifierUses() {
        // JCLAW-1453, the reverse of the two tests above. The classifier is passed, not stored: see the class's lock.
        record Card(String key, Candidate classifier, String other) {}
        for (var card : List.of(
                new Card(OllamaDecision.MODELS_KEY, new Candidate(OllamaDecision.PROVIDER, "tev1"), "nimble"),
                new Card(LayaDecision.MODELS_KEY, new Candidate(LayaDecision.PROVIDER, "english"), "multilingual"))) {
            var key = card.key();
            var classifier = card.classifier();
            var other = card.other();
            ConfigService.set(key, "[\"" + classifier.model() + "\",\"" + other + "\"]");
            try {
                var dropped = RouterPolicy.selectionRejection(key, "[\"" + other + "\"]", classifier);
                assertEquals(key + " must keep " + classifier.model() + " while the Model Router uses it as its "
                        + "prompt classifier; choose another classifier in Settings > Model Router first.", dropped);
                assertEquals(dropped, RouterPolicy.selectionRejection(key, null, classifier), "a delete selects none");
                assertEquals(dropped, RouterPolicy.selectionRejection(key, "[]", classifier));

                assertNull(RouterPolicy.selectionRejection(key, "[\"" + classifier.model() + "\"]", classifier),
                        "keeping it, the other may go");
                assertNull(RouterPolicy.selectionRejection(key, "[\"" + other + "\"]", null), "no classifier");
                assertNull(RouterPolicy.selectionRejection(key, "[\"" + other + "\"]",
                        new Candidate(RouterPolicy.JEV, "jev-latest")), "another provider classifies");
                assertNull(RouterPolicy.selectionRejection(key, "[\"" + other + "\"]",
                        new Candidate(classifier.provider(), "unticked")), "a classifier already unticked");
                assertNull(RouterPolicy.selectionRejection(key, "not a list", classifier),
                        "a malformed list is DecisionSettings' to refuse");
                assertNull(RouterPolicy.selectionRejection("router.chat.models", "[]", classifier), "another key");
            } finally {
                ConfigService.delete(key);
            }
        }
    }

    @Test
    void theWriteAndTheDeleteOfASelectionBothAskTheRule() {
        // Both classifier halves are stored only here and under the lock, so no routed turn classifies with them.
        ConfigService.set(LayaDecision.MODELS_KEY, "[\"english\",\"multilingual\"]");
        ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, LayaDecision.PROVIDER);
        ConfigService.set(RouterPolicy.CLASSIFIER_MODEL, "english");
        try {
            var written = ConfigService.setWithSideEffects(LayaDecision.MODELS_KEY, "[\"multilingual\"]");
            assertNotNull(written, "the write was stored");
            assertTrue(written.contains("must keep english"), written);
            var deleted = ConfigService.deleteWithSideEffects(LayaDecision.MODELS_KEY);
            assertNotNull(deleted, "the row was deleted");
            assertTrue(deleted.contains("must keep english"), deleted);
            assertEquals(List.of("english", "multilingual"), LayaDecision.selectedModels(), "both were refused");

            assertNull(ConfigService.setWithSideEffects(LayaDecision.MODELS_KEY, "[\"english\"]"));
            assertEquals(List.of("english"), LayaDecision.selectedModels());
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_MODEL);
            ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
            ConfigService.delete(LayaDecision.MODELS_KEY);
        }
    }

    @Test
    void aStoredLayaSelectionTicksOnlyTheNamesThatAreCheckpoints() {
        // set() skips the write rules, as a row edited by hand would.
        ConfigService.set(LayaDecision.MODELS_KEY, "[\"laya-next\",\"english\"]");
        ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, LayaDecision.PROVIDER);
        try {
            assertEquals(List.of("english"), LayaDecision.selectedModels());
            assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "english"));
            assertNotNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "laya-next"));
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
            ConfigService.delete(LayaDecision.MODELS_KEY);
        }
    }

    @Test
    void theJevProviderWriteIsNotCheckedAgainstTheStoredModel() {
        ConfigService.set(RouterPolicy.CLASSIFIER_MODEL, "m1");
        try {
            assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, RouterPolicy.JEV));
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_MODEL);
        }
    }

    @Test
    void leavingJevForAModelSavesOnceTheStoredModelIsCleared() {
        ConfigService.set(RouterPolicy.CLASSIFIER_MODEL, "jev-latest");
        try {
            // Why the panel deletes the model before it writes the provider: this write is cross-checked.
            assertTrue(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, provider).contains("no model with id"));
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_MODEL);
        }
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, provider));
    }

    @Test
    void jevsMinimumConfidenceIsAProbability() {
        for (var ok : new String[] {"0", "0.9", "1", " 0.75 "}) {
            assertNull(RouterPolicy.rejectionFor(RouterPolicy.JEV_MIN_CONFIDENCE, ok), () -> "refused " + ok);
        }
        for (var bad : new String[] {"-0.01", "1.01", "NaN", "Infinity", "sure", ""}) {
            assertNotNull(RouterPolicy.rejectionFor(RouterPolicy.JEV_MIN_CONFIDENCE, bad), () -> "accepted " + bad);
        }
        var policy = new RouterPolicy(Map.of(TaskClass.CHAT, List.of(new Candidate("p", "m"))), 0.75, 0.95);
        assertEquals(RouterPolicy.DEFAULT_JEV_MIN_CONFIDENCE, policy.jevMinConfidence());
        assertEquals(0.50, RouterPolicy.DEFAULT_JEV_MIN_CONFIDENCE);
    }

    @Test
    void loadReadsJevsMinimumConfidence() {
        ConfigService.set(RouterPolicy.JEV_MIN_CONFIDENCE, "0.8");
        try {
            assertEquals(0.8, RouterPolicy.load().jevMinConfidence());
        } finally {
            ConfigService.delete(RouterPolicy.JEV_MIN_CONFIDENCE);
        }
        assertEquals(RouterPolicy.DEFAULT_JEV_MIN_CONFIDENCE, RouterPolicy.load().jevMinConfidence());
    }

    @Test
    void theClassifierTimeoutMustBeSecondsInRange() {
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_TIMEOUT_SECONDS, "8"));
        for (var bad : new String[] {"0", "-1", "61", "soon", ""}) {
            assertNotNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_TIMEOUT_SECONDS, bad), () -> "accepted " + bad);
        }
    }

    @Test
    void thePrepaidPreferenceIsABooleanAndDefaultsToOn() {
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.PREFER_PREPAID, "false"));
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.PREFER_PREPAID, "TRUE"));
        for (var bad : new String[] {"", "yes", "0", "off"}) {
            assertNotNull(RouterPolicy.rejectionFor(RouterPolicy.PREFER_PREPAID, bad), () -> "accepted " + bad);
        }
        assertTrue(new RouterPolicy(Map.of(TaskClass.CHAT, List.of(new Candidate("p", "m"))), 0.75, 0.95)
                .preferPrepaid(), "an operator who has set nothing gets credit protection");
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
