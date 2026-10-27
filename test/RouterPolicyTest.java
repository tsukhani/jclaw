import llm.ProviderRegistry;
import llm.routing.RouterPolicy;
import llm.routing.RouterPolicy.Candidate;
import llm.routing.TaskClass;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.decision.BasetenDecision;
import services.decision.LayaDecision;
import services.decision.OllamaDecision;
import utils.HttpFactories;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** JCLAW-1222: what a router policy write accepts, and how a class without its own list reads. */
class RouterPolicyTest extends UnitTest {

    private static final String ELEPHANT = "decision-models/baseten-elephant-9b-latest";
    private static final String MAMMOTH = "decision-models/baseten-mammoth-27b-latest";
    private static final String OLLAMA = "http://192.168.1.20:11434";

    private String provider;

    @BeforeEach
    void seedProvider() {
        // The classifier keys and the Ollama address this class stores are read by ModelRouterTurnTest's turns.
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

    /** An {@code /api/tags} entry with one capability. */
    private static String tag(String name, String capability) {
        return "{\"name\":\"" + name + "\",\"capabilities\":[\"" + capability + "\"]}";
    }

    /** The paths {@link #withOllama} was asked for. */
    private final List<String> paths = new CopyOnWriteArrayList<>();

    /**
     * Runs {@code body} against an Ollama server whose {@code /api/tags} answers {@code tags} and whose
     * {@code /api/show} knows nothing, or that refuses every connection when {@code tags} is null.
     */
    private <T> T withOllama(@Nullable String tags, Supplier<T> body) {
        Interceptor server = chain -> {
            paths.add(chain.request().url().encodedPath());
            if (tags == null) throw new ConnectException("Connection refused");
            var listing = chain.request().url().encodedPath().equals("/api/tags");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(listing ? 200 : 404).message("canned")
                    .body(ResponseBody.create(listing ? tags : "{}", MediaType.get("application/json"))).build();
        };
        return HttpFactories.callWith(new OkHttpClient.Builder().addInterceptor(server).build(), body);
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
    void anOllamaClassifierModelIsADecisionModelTheServerListsWithNothingTicked() {
        // JF-135. Only one classifier half is stored, and the address under the lock, as ModelRouterTurnTest reads both.
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, OllamaDecision.PROVIDER),
                "ollama-decision is not a registered provider, and needs none");
        ConfigService.set(OllamaDecision.BASE_URL_KEY, OLLAMA);
        ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, OllamaDecision.PROVIDER);
        try {
            var tags = "{\"models\":[" + tag("tev1:latest", "decision") + "," + tag("llama3.2:latest", "completion")
                    + "," + tag("nimble:latest", "decision") + "]}";
            assertNull(withOllama(tags, () -> RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "tev1:latest")));
            assertNull(withOllama(tags, () -> RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, " nimble:latest ")));
            // llama3.2 is installed but no decision model; the server names tev1 by its tag.
            for (var bad : new String[] {"llama3.2:latest", "tev1", "tev1:0.8b", "jev-latest"}) {
                assertEquals("router.classifier.model must be a decision model on the Ollama server at " + OLLAMA
                        + ": tev1:latest, nimble:latest.",
                        withOllama(tags, () -> RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, bad)),
                        () -> "accepted " + bad);
            }

            assertEquals("router.classifier.model must be a decision model on the Ollama server at " + OLLAMA
                    + ", which lists none.", withOllama("{\"models\":[" + tag("llama3.2:latest", "completion") + "]}",
                    () -> RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "tev1:latest")));
            assertEquals("router.classifier.model could not be checked against the Ollama server at " + OLLAMA
                    + ": not reachable (ConnectException).",
                    withOllama(null, () -> RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, "tev1:latest")));
            assertEquals(List.of("/api/tags"), paths.stream().distinct().toList(), "the check asks /api/tags alone");
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
            ConfigService.delete(OllamaDecision.BASE_URL_KEY);
        }
    }

    @Test
    void aLayaClassifierModelIsAnyCheckpointWithNothingTicked() {
        // JF-135. Only one classifier half is stored.
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, LayaDecision.PROVIDER),
                "laya is not a registered provider, and needs none");
        ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, LayaDecision.PROVIDER);
        try {
            for (var ok : new String[] {"english", " multilingual ", "typed-decisions"}) {
                assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, ok), () -> "refused " + ok);
            }
            for (var bad : new String[] {"tev1", "jev-latest", "English", "laya-next"}) {
                assertEquals("router.classifier.model must be a Laya checkpoint: english, multilingual, typed-decisions.",
                        RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, bad), () -> "accepted " + bad);
            }
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
        }
        assertNotNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, "rp-nope"),
                "another unregistered name is still refused");
    }

    @Test
    void aBasetenClassifierModelIsEitherModelsIdWithNothingTicked() {
        // JF-135. Only one classifier half is stored.
        assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_PROVIDER, BasetenDecision.PROVIDER),
                "baseten-decision is not a registered provider, and needs none");
        ConfigService.set(RouterPolicy.CLASSIFIER_PROVIDER, BasetenDecision.PROVIDER);
        try {
            for (var ok : new String[] {ELEPHANT, " " + MAMMOTH + " "}) {
                assertNull(RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, ok), () -> "refused " + ok);
            }
            // elephant-1 is its name, not its ID.
            for (var bad : new String[] {"elephant-1", "tev1", "jev-latest"}) {
                assertEquals("router.classifier.model must be the ID of a Baseten model: " + ELEPHANT + " (elephant-1), "
                        + MAMMOTH + " (mammoth-1).", RouterPolicy.rejectionFor(RouterPolicy.CLASSIFIER_MODEL, bad),
                        () -> "accepted " + bad);
            }
        } finally {
            ConfigService.delete(RouterPolicy.CLASSIFIER_PROVIDER);
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
