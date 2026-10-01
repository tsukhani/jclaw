import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.routing.JevRouterClassifier;
import llm.routing.ReasoningEffort;
import llm.routing.RouterClassifier;
import llm.routing.RouterPolicy;
import llm.routing.RouterPolicy.Candidate;
import llm.routing.TaskClass;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.decision.JevApi;
import services.decision.OllamaDecision;
import utils.CircuitBreaker;
import utils.HttpFactories;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * JCLAW-1336: an Ollama System One model as the router's classifier. The JEV request goes to the
 * operator's server with no key, under the {@code decision:ollama} breaker, and every failure leaves
 * the keyword rules to answer.
 */
class OllamaDecisionTest extends UnitTest {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String BASE = "http://192.168.1.20:11434";
    private static final String PROMPT = "Run daily briefing skill";
    private static final List<String> CLASS_IDS = List.of("chat", "summarize", "agentic", "reasoning", "coding");
    private static final List<String> EFFORT_IDS = List.of("low", "medium", "high");

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private OkHttpClient client;

    @BeforeEach
    void reset() {
        JevBreakerTestSync.acquire();
        requests.clear();
        bodies.clear();
    }

    @AfterEach
    void releaseBreaker() {
        JevBreakerTestSync.release();
    }

    @FunctionalInterface
    private interface Answer {
        Response reply(Interceptor.Chain chain) throws IOException;
    }

    private <T> T withOllama(Answer answer, Supplier<T> body) {
        Interceptor ollama = chain -> {
            requests.add(chain.request());
            var requestBody = chain.request().body();
            if (requestBody != null) {
                var buffer = new Buffer();
                requestBody.writeTo(buffer);
                bodies.add(buffer.readUtf8());
            }
            return answer.reply(chain);
        };
        client = new OkHttpClient.Builder().addInterceptor(ollama).build();
        return HttpFactories.callWith(client, body);
    }

    private long requestsTo(String path) {
        return requests.stream().filter(r -> r.url().encodedPath().equals(path)).count();
    }

    /** Calls the last {@link #withOllama} client still holds: an enqueued load stays counted until it answers. */
    private int callsInFlight() {
        return client.dispatcher().queuedCallsCount() + client.dispatcher().runningCallsCount();
    }

    /** Answers a load only once {@code release} opens, so a test can see it in flight. */
    private static Answer loadingUntil(CountDownLatch release, Answer otherwise) {
        return chain -> {
            if (!chain.request().url().encodedPath().equals("/api/generate")) return otherwise.reply(chain);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return reply(chain, 200, "{\"model\":\"tev1:latest\",\"done\":true,\"done_reason\":\"load\"}");
        };
    }

    private static Response reply(Interceptor.Chain chain, int code, String json) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("canned").body(ResponseBody.create(json, JSON)).build();
    }

    private static JsonObject head(List<String> ids, String selected, double p) {
        var probabilities = new JsonObject();
        ids.forEach(id -> probabilities.addProperty(id, id.equals(selected) ? p : (1 - p) / (ids.size() - 1)));
        var answer = new JsonObject();
        answer.addProperty("type", "choice");
        answer.addProperty("choice", selected);
        answer.addProperty("confidence", p);
        answer.add("probabilities", probabilities);
        return answer;
    }

    /** The shape Ollama v0.35.0's SystemOneHandler answers with. */
    private static Answer answering(String taskClass, double p, String effort) {
        var answers = new JsonObject();
        answers.add("task_class", head(CLASS_IDS, taskClass, p));
        answers.add("effort", head(EFFORT_IDS, effort, 0.8));
        var result = new JsonObject();
        result.addProperty("model", "tev1");
        result.add("answers", answers);
        return chain -> reply(chain, 200, result.toString());
    }

    private static Answer unreachable() {
        return _ -> {
            throw new ConnectException("Connection refused");
        };
    }

    /** Holds the call past a 1 s classifier timeout; OkHttp's call timeout ends it. */
    private static Answer hang() {
        return chain -> {
            try {
                Thread.sleep(1_300);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return answering("chat", 0.95, "low").reply(chain);
        };
    }

    private static JevRouterClassifier.Verdict classify(String model) {
        return JevRouterClassifier.classifyWithOllama(PROMPT, BASE, model, 0.9, 8);
    }

    private static RouterPolicy ollamaPolicy() {
        return new RouterPolicy(Map.of(TaskClass.CHAT, List.of(new Candidate("p", "m"))), 0.75, 0.95,
                new Candidate(OllamaDecision.PROVIDER, "tev1"), 8, true, 0.90);
    }

    // --- the request -----------------------------------------------------------------------

    @Test
    void theRequestGoesToTheServersSystemOneRouteWithTheModelAndNoKey() {
        withOllama(answering("summarize", 0.95, "low"), () -> classify("tev1:0.8b"));

        assertEquals(1, requests.size());
        var request = requests.getFirst();
        assertEquals(BASE + "/v1/systemone", request.url().toString());
        assertNull(request.header("Authorization"), "Ollama takes no key");
        var body = JsonParser.parseString(bodies.getFirst()).getAsJsonObject();
        assertEquals("tev1:0.8b", body.get("model").getAsString());
        assertEquals(List.of("task_class", "effort"), List.copyOf(body.getAsJsonObject("questions").keySet()),
                "the questions JEV answers");
    }

    @Test
    void theRequestKeepsTheModelLoadedUntilOllamaRestartsByDefault() {
        ConfigService.delete(OllamaDecision.KEEP_ALIVE_KEY);
        withOllama(answering("chat", 0.95, "low"), () -> classify("tev1"));
        var keepAlive = JsonParser.parseString(bodies.getFirst()).getAsJsonObject().getAsJsonPrimitive("keep_alive");
        assertNotNull(keepAlive, bodies.getFirst());
        assertTrue(keepAlive.isNumber(), "a bare \"-1\" string is a duration with no unit to Ollama");
        assertEquals(-1, keepAlive.getAsInt());
    }

    @Test
    void theRequestCarriesTheConfiguredKeepAlive() {
        try {
            for (var configured : List.of("30m", "0", "-1", "1.5h", "garbage")) {
                bodies.clear();
                ConfigService.set(OllamaDecision.KEEP_ALIVE_KEY, configured);
                withOllama(answering("chat", 0.95, "low"), () -> classify("tev1"));
                var keepAlive = JsonParser.parseString(bodies.getFirst()).getAsJsonObject().getAsJsonPrimitive("keep_alive");
                switch (configured) {
                    case "0", "-1" -> assertEquals(Integer.parseInt(configured), keepAlive.getAsInt());
                    case "garbage" -> assertEquals(-1, keepAlive.getAsInt(), "a value written around the check");
                    default -> {
                        assertTrue(keepAlive.isString(), configured);
                        assertEquals(configured, keepAlive.getAsString());
                    }
                }
            }
        } finally {
            ConfigService.delete(OllamaDecision.KEEP_ALIVE_KEY);
        }
    }

    @Test
    void aTrailingSlashOnTheAddressIsNotDoubled() {
        withOllama(answering("chat", 0.95, "low"),
                () -> JevRouterClassifier.classifyWithOllama(PROMPT, BASE + "/", "tev1", 0.9, 8));
        assertEquals(BASE + "/v1/systemone", requests.getFirst().url().toString());
    }

    @Test
    void theRouterUsesTheModelsConfidentAnswer() {
        var c = withOllama(answering("reasoning", 0.97, "high"),
                () -> RouterClassifier.classify(PROMPT, null, 0, ollamaPolicy(), () -> null));
        assertEquals(1, requests.size());
        assertTrue(requests.getFirst().url().encodedPath().endsWith("/v1/systemone"));
        assertEquals("tev1", JsonParser.parseString(bodies.getFirst()).getAsJsonObject().get("model").getAsString());
        assertEquals(TaskClass.REASONING, c.taskClass());
        assertEquals(ReasoningEffort.HIGH, c.effort());
        assertEquals(List.of("classified by Ollama tev1 (reasoning 0.97)"), c.signals());
    }

    @Test
    void anUnsureAnswerIsLeftToTheRulesUnderTheSameMinimumConfidence() {
        var c = withOllama(answering("reasoning", 0.62, "high"),
                () -> RouterClassifier.classify(PROMPT, null, 0, ollamaPolicy(), () -> null));
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules' class");
        assertEquals(List.of("asks to run", "Ollama tev1 unsure (0.62 < 0.90)"), c.signals());
    }

    // --- failures --------------------------------------------------------------------------

    @Test
    void aNonDecisionModelsRefusalFallsBackAndIsNotCountedAgainstTheBreaker() {
        var refusal = "{\"error\":\"model \\\"llama3.2\\\" is not supported by System One\"}";
        var verdict = withOllama(chain -> reply(chain, 400, refusal), () -> classify("llama3.2"));
        assertEquals(1, requests.size());
        assertNull(verdict.classification());
        assertFalse(verdict.signal());
        assertEquals("the Ollama llama3.2 classifier failed (Ollama returned HTTP 400)", verdict.reason());
        assertEquals(0, OllamaDecision.breaker().stats().failures(), "a 4xx is this request's fault, not the server's");

        var c = withOllama(chain -> reply(chain, 400, refusal),
                () -> RouterClassifier.classify(PROMPT, null, 0, ollamaPolicy(), () -> null));
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules answer");
    }

    @Test
    void anUnreachableServerFallsBackAndCountsAgainstOllamasBreakerNotJevs() {
        var verdict = withOllama(unreachable(), () -> classify("tev1"));
        assertEquals(1, requests.size(), "never retried on a routed turn");
        assertNull(verdict.classification());
        assertEquals("the Ollama tev1 classifier failed (Ollama unreachable)", verdict.reason());
        assertEquals(1, OllamaDecision.breaker().stats().failures());
        assertEquals(0, JevApi.breaker().stats().failures(), "each provider has its own breaker");

        var c = withOllama(unreachable(), () -> RouterClassifier.classify(PROMPT, null, 0, ollamaPolicy(), () -> null));
        assertEquals(TaskClass.AGENTIC, c.taskClass());
        assertEquals(List.of("asks to run"), c.signals(), "a fault adds nothing to the route; it was logged");
    }

    @Test
    void aTimeoutSaysSoRatherThanUnreachable() throws Exception {
        var verdict = withOllama(hang(),
                () -> JevRouterClassifier.classifyWithOllama(PROMPT, BASE, "tev1:latest", 0.9, 1));
        assertEquals(1, requestsTo("/v1/systemone"));
        assertNull(verdict.classification());
        assertEquals("the Ollama tev1:latest classifier failed (Ollama did not answer within 1 s)", verdict.reason());
        assertEquals(1, OllamaDecision.breaker().stats().failures(), "a timeout still counts against the breaker");
        // The timeout started a load; let it finish so it cannot answer for a later test's pin.
        withOllama(hang(), () -> OllamaDecision.pin(BASE, "tev1:latest")).get(10, TimeUnit.SECONDS);
    }

    // --- loading the model -------------------------------------------------------------------

    @Test
    void aTimedOutClassificationLoadsItsModelOnARequestOfItsOwn() throws Exception {
        ConfigService.delete(OllamaDecision.KEEP_ALIVE_KEY);
        var release = new CountDownLatch(1);
        try {
            var verdict = withOllama(loadingUntil(release, hang()),
                    () -> JevRouterClassifier.classifyWithOllama(PROMPT, BASE, "tev1:latest", 0.9, 1));
            assertTrue(verdict.reason().contains("did not answer within 1 s"), verdict.reason());
            assertEquals(1, callsInFlight(), "the load outlives the classifier's timeout");

            var again = OllamaDecision.pin(BASE, "tev1:latest");
            assertFalse(again.isDone(), "a second pin joins the one in flight");
            assertEquals(1, callsInFlight(), "and sends nothing of its own");

            release.countDown();
            assertTrue(again.get(10, TimeUnit.SECONDS));
            assertEquals(1, requestsTo("/api/generate"));
            var load = bodies.stream().filter(b -> !b.contains("questions")).findFirst().orElseThrow();
            var body = JsonParser.parseString(load).getAsJsonObject();
            assertEquals("tev1:latest", body.get("model").getAsString());
            assertEquals(-1, body.get("keep_alive").getAsInt());
            assertEquals(List.of("keep_alive", "model"), body.keySet().stream().sorted().toList(), "no prompt: load only");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aLoadGoesToTheServersNativeGenerateRouteWithNoKey() throws Exception {
        ConfigService.delete(OllamaDecision.KEEP_ALIVE_KEY);
        var loaded = withOllama(chain -> reply(chain, 200, "{\"done\":true}"), () -> OllamaDecision.pin(BASE + "/", "tev1"));
        assertTrue(loaded.get(10, TimeUnit.SECONDS));
        assertEquals(1, requests.size());
        var request = requests.getFirst();
        assertEquals("POST", request.method());
        assertEquals(BASE + "/api/generate", request.url().toString());
        assertNull(request.header("Authorization"), "Ollama takes no key");
        assertEquals(0, OllamaDecision.breaker().stats().samples(), "a load is no decision, so the breaker never sees it");
    }

    @Test
    void aFailedLoadReportsFalse() throws Exception {
        assertFalse(withOllama(unreachable(), () -> OllamaDecision.pin(BASE, "tev1")).get(10, TimeUnit.SECONDS));
        assertFalse(withOllama(chain -> reply(chain, 500, "{}"), () -> OllamaDecision.pin(BASE, "tev1"))
                .get(10, TimeUnit.SECONDS));
    }

    @Test
    void nothingIsLoadedForAZeroKeepAliveOrARefusedAddress() throws Exception {
        try {
            for (var zero : List.of("0", "0m")) {
                ConfigService.set(OllamaDecision.KEEP_ALIVE_KEY, zero);
                var loaded = withOllama(chain -> reply(chain, 200, "{}"), () -> OllamaDecision.pin(BASE, "tev1"));
                assertFalse(loaded.get(1, TimeUnit.SECONDS), zero + " would unload it at once");
            }
        } finally {
            ConfigService.delete(OllamaDecision.KEEP_ALIVE_KEY);
        }
        var refused = withOllama(chain -> reply(chain, 200, "{}"), () -> OllamaDecision.pin("http://169.254.169.254", "tev1"));
        assertFalse(refused.get(1, TimeUnit.SECONDS));
        assertTrue(requests.isEmpty());
    }

    @Test
    void aRefusalOrAnUnreachableServerLoadsNothing() {
        var release = new CountDownLatch(1);
        try {
            var refusal = "{\"error\":\"model \\\"llama3.2\\\" is not supported by System One\"}";
            withOllama(loadingUntil(release, chain -> reply(chain, 400, refusal)), () -> classify("llama3.2"));
            assertEquals(0, callsInFlight(), "a 4xx is no load in progress, and must not load a chat model");

            withOllama(loadingUntil(release, unreachable()), () -> classify("tev1"));
            assertEquals(0, callsInFlight(), "a server that cannot be reached cannot load either");
            assertEquals(0, requestsTo("/api/generate"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void onlyAnOllamaClassifierIsKeptLoaded() throws Exception {
        var loaded = withOllama(chain -> reply(chain, 200, "{\"done\":true}"),
                () -> RouterClassifier.keepOllamaModelLoaded(ollamaPolicy()));
        assertTrue(loaded.get(10, TimeUnit.SECONDS));
        assertEquals(OllamaDecision.baseUrl() + "/api/generate", requests.getFirst().url().toString());
        assertEquals("tev1", JsonParser.parseString(bodies.getFirst()).getAsJsonObject().get("model").getAsString());

        requests.clear();
        var classes = Map.of(TaskClass.CHAT, List.of(new Candidate("p", "m")));
        for (var policy : List.of(new RouterPolicy(classes, 0.75, 0.95, new Candidate(RouterPolicy.JEV, "jev-latest"), 8, true, 0.90),
                new RouterPolicy(classes, 0.75, 0.95, null, 8, true, 0.90))) {
            var other = withOllama(chain -> reply(chain, 200, "{}"), () -> RouterClassifier.keepOllamaModelLoaded(policy));
            assertFalse(other.get(1, TimeUnit.SECONDS));
        }
        assertTrue(requests.isEmpty(), "JEV, or no classifier, has no model to load");
    }

    @Test
    void anOpenBreakerSendsNothingAndNamesTheModel() {
        for (var i = 0; i < 3; i++) withOllama(unreachable(), () -> classify("tev1"));
        assertEquals(CircuitBreaker.State.OPEN, OllamaDecision.breaker().state());
        assertEquals(CircuitBreaker.State.CLOSED, JevApi.breaker().state(), "JEV is untouched");

        var verdict = withOllama(answering("reasoning", 0.97, "high"), () -> classify("tev1"));
        assertEquals(3, requests.size(), "an open breaker sends nothing");
        assertTrue(verdict.signal());
        assertEquals("Ollama tev1 breaker open", verdict.reason());
    }

    @Test
    void aLinkLocalAddressIsRefusedBeforeAnythingIsSent() {
        var verdict = withOllama(answering("chat", 0.95, "low"),
                () -> JevRouterClassifier.classifyWithOllama(PROMPT, "http://169.254.169.254", "tev1", 0.9, 8));
        assertTrue(requests.isEmpty());
        assertNull(verdict.classification());
        assertTrue(verdict.reason().contains("refused"), verdict.reason());
    }

    // --- the server's decision models --------------------------------------------------------

    private static final String TAGS = """
            {"models":[
              {"name":"tev1:latest","capabilities":["decision"]},
              {"name":"llama3.2:latest","capabilities":["completion","tools"]},
              {"name":"nimble:latest","capabilities":["completion","decision"]},
              {"name":"old:latest"},
              {"name":"odd:latest","capabilities":"decision"}
            ]}""";

    @Test
    void statusListsOnlyModelsWithTheDecisionCapability() {
        var status = withOllama(chain -> reply(chain, 200, TAGS), () -> OllamaDecision.status(BASE + "/"));
        assertEquals(BASE + "/api/tags", requests.getFirst().url().toString());
        assertTrue(status.reachable());
        assertNull(status.error());
        assertEquals(List.of("tev1:latest", "nimble:latest"), status.models());
        assertEquals(BASE, status.baseUrl());
    }

    @Test
    void statusOfAServerWithNoModelsIsReachableAndEmpty() {
        var status = withOllama(chain -> reply(chain, 200, "{\"models\":[]}"), () -> OllamaDecision.status(BASE));
        assertTrue(status.reachable());
        assertEquals(List.of(), status.models());
    }

    @Test
    void statusOfAnUnreachableServerNamesOnlyTheFailure() {
        var status = withOllama(unreachable(), () -> OllamaDecision.status(BASE));
        assertFalse(status.reachable());
        assertEquals("not reachable (ConnectException)", status.error());
        assertEquals(List.of(), status.models());
    }

    @Test
    void statusOfSomethingThatIsNotOllamaSaysSoWithoutEchoingIt() {
        var status = withOllama(chain -> reply(chain, 200, "<html>router login</html>"), () -> OllamaDecision.status(BASE));
        assertFalse(status.reachable());
        assertEquals("/api/tags did not answer with Ollama's model list", status.error());

        var denied = withOllama(chain -> reply(chain, 403, "{\"error\":\"secret detail\"}"), () -> OllamaDecision.status(BASE));
        assertEquals("HTTP 403 from /api/tags", denied.error());
    }

    @Test
    void statusRefusesALinkLocalAddressWithoutDialling() {
        var status = withOllama(chain -> reply(chain, 200, TAGS), () -> OllamaDecision.status("http://169.254.169.254"));
        assertTrue(requests.isEmpty());
        assertFalse(status.reachable());
        assertNotNull(status.error());
    }

    @Test
    void loopbackAndLanAddressesAreDialled() {
        for (var base : List.of("http://localhost:11434", "http://127.0.0.1:11434", "http://10.0.0.5:11434", BASE)) {
            var status = withOllama(chain -> reply(chain, 200, TAGS), () -> OllamaDecision.status(base));
            assertTrue(status.reachable(), () -> base + ": " + status.error());
        }
        assertEquals(4, requests.size());
    }
}
