import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.routing.JevRouterClassifier;
import llm.routing.JevRouterClassifier.Verdict;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.UnitTest;
import services.decision.JevApi;
import services.telemetry.BreakerMetrics;
import services.telemetry.OtelRuntime;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.HttpFactories;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * JCLAW-1300: TypeSafe's JEV as the router's classifier. One request carrying only the truncated
 * prompt and the two questions; JEV's class and effort when it is sure, and the keyword rules —
 * never a failed turn — when it is unsure, wrong, unreachable, has no key, or its breaker is open. The key
 * is passed in, because {@code decision.jev.apiKey} is flipped by other classes running concurrently.
 */
class JevRouterClassifierTest extends UnitTest {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String KEY = "ts-router-test-key";
    private static final String PROMPT = "Run daily briefing skill";
    private static final List<String> CLASS_IDS = List.of("chat", "summarize", "agentic", "reasoning", "coding");
    private static final List<String> EFFORT_IDS = List.of("low", "medium", "high");

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<JsonObject> bodies = new CopyOnWriteArrayList<>();

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

    private <T> T withJev(Answer answer, Supplier<T> body) {
        Interceptor jev = chain -> {
            var buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            requests.add(chain.request());
            bodies.add(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject());
            return answer.reply(chain);
        };
        return HttpFactories.callWith(new OkHttpClient.Builder().addInterceptor(jev).build(), body);
    }

    private static Response reply(Interceptor.Chain chain, int code, String json) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("canned").body(ResponseBody.create(json, JSON)).build();
    }

    /** A valid head: {@code p} on the choice, the rest shared evenly among the other ids. */
    private static JsonObject head(List<String> ids, String selected, double p) {
        var probabilities = new JsonObject();
        ids.forEach(id -> probabilities.addProperty(id, id.equals(selected) ? p : (1 - p) / (ids.size() - 1)));
        var answer = new JsonObject();
        answer.addProperty("choice", selected);
        answer.addProperty("confidence", p);
        answer.add("probabilities", probabilities);
        return answer;
    }

    private static Answer answering(JsonObject taskClass, JsonObject effort) {
        var answers = new JsonObject();
        answers.add("task_class", taskClass);
        answers.add("effort", effort);
        var result = new JsonObject();
        result.addProperty("model", "jev-latest");
        result.add("answers", answers);
        return chain -> reply(chain, 200, result.toString());
    }

    private static Answer answering(String taskClass, double p, String effort) {
        return answering(head(CLASS_IDS, taskClass, p), head(EFFORT_IDS, effort, 0.8));
    }

    private static Verdict classify(String prompt, String key, double minConfidence) {
        return JevRouterClassifier.classify(prompt, key, minConfidence, 8);
    }

    private static RouterPolicy jevPolicy() {
        return jevPolicy(8);
    }

    private static RouterPolicy jevPolicy(int timeoutSeconds) {
        return new RouterPolicy(Map.of(TaskClass.CHAT, List.of(new Candidate("p", "m"))), 0.75, 0.95,
                new Candidate(RouterPolicy.JEV, "jev-latest"), timeoutSeconds, true, 0.90);
    }

    /** Answers confidently, but only after 1.3 s. */
    private static Answer hang() {
        return chain -> {
            try {
                Thread.sleep(1_300);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return answering("reasoning", 0.97, "high").reply(chain);
        };
    }

    // --- the request -----------------------------------------------------------------------

    @Test
    void theRequestCarriesOnlyTheModelTheTruncatedPromptAndTheTwoQuestions() {
        var huge = "Summarize this: " + "x".repeat(RouterClassifier.MAX_PROMPT_CHARS * 2);
        withJev(answering("summarize", 0.95, "low"), () -> classify(huge, KEY, 0.9));

        assertEquals(1, requests.size(), "exactly one systemone request");
        var request = requests.getFirst();
        assertEquals("https://api.typesafe.ai/v1/systemone", request.url().toString());
        assertEquals("Bearer " + KEY, request.header("Authorization"));

        var body = bodies.getFirst();
        assertEquals(List.of("model", "state", "questions"), List.copyOf(body.keySet()),
                "no history, system prompt or other context: " + body.keySet());
        assertEquals("jev-latest", body.get("model").getAsString());
        var state = body.getAsJsonObject("state");
        assertEquals(List.of("prompt"), List.copyOf(state.keySet()));
        assertEquals(huge.substring(0, RouterClassifier.MAX_PROMPT_CHARS), state.get("prompt").getAsString());

        var questions = body.getAsJsonObject("questions");
        assertEquals(List.of("task_class", "effort"), List.copyOf(questions.keySet()));
        var taskClass = questions.getAsJsonObject("task_class");
        assertEquals("choice", taskClass.get("type").getAsString());
        assertEquals(CLASS_IDS, List.copyOf(taskClass.getAsJsonObject("criteria").keySet()));
        assertTrue(taskClass.getAsJsonObject("criteria").get("chat").getAsString().startsWith("greetings"),
                "the criteria are the LLM classifier's own definitions: " + taskClass);
        assertTrue(taskClass.getAsJsonObject("instructions").isJsonObject());
        var effort = questions.getAsJsonObject("effort");
        assertEquals("choice", effort.get("type").getAsString());
        assertEquals(EFFORT_IDS, List.copyOf(effort.getAsJsonObject("criteria").keySet()));
    }

    // --- confident and unsure --------------------------------------------------------------

    @Test
    void aConfidentAnswerSetsTheClassAndTheEffort() {
        var verdict = withJev(answering("reasoning", 0.97, "high"), () -> classify(PROMPT, KEY, 0.9));
        var c = verdict.classification();
        assertNotNull(c);
        assertEquals(TaskClass.REASONING, c.taskClass(), "JEV's class wins over the rules, which say agentic");
        assertEquals(ReasoningEffort.HIGH, c.effort());
        assertEquals(List.of("classified by JEV (reasoning 0.97)"), c.signals());
        assertNull(verdict.reason());
    }

    @Test
    void jevsEffortIsUsedEvenWhereTheClassDefaultDiffers() {
        var c = withJev(answering("reasoning", 0.95, "low"), () -> classify(PROMPT, KEY, 0.9)).classification();
        assertNotNull(c);
        assertEquals(ReasoningEffort.LOW, c.effort());
    }

    @Test
    void aClassExactlyAtTheMinimumConfidenceStands() {
        var c = withJev(answering("coding", 0.9, "medium"), () -> classify(PROMPT, KEY, 0.9)).classification();
        assertNotNull(c);
        assertEquals(TaskClass.CODING, c.taskClass());
    }

    @Test
    void anUnsureClassIsLeftToTheRulesWithoutAWarning() {
        var verdict = withJev(answering("reasoning", 0.62, "high"), () -> classify(PROMPT, KEY, 0.9));
        assertNull(verdict.classification());
        assertTrue(verdict.signal(), "unsure is not a fault");
        assertEquals("JEV unsure (0.62 < 0.90)", verdict.reason());
    }

    @Test
    void theRouterUsesTheRulesClassAndDefaultEffortWhenJevIsUnsure() {
        var c = withJev(answering("reasoning", 0.62, "high"),
                () -> RouterClassifier.classify(PROMPT, null, 0, jevPolicy(), () -> KEY));
        assertEquals(1, requests.size());
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules' class");
        assertEquals(TaskClass.AGENTIC.defaultEffort(), c.effort(), "and the rules' default effort, not JEV's");
        assertEquals(List.of("asks to run", "JEV unsure (0.62 < 0.90)"), c.signals());
    }

    @Test
    void theRouterUsesJevsAnswerWhenJevIsSure() {
        var c = withJev(answering("reasoning", 0.97, "high"),
                () -> RouterClassifier.classify(PROMPT, null, 0, jevPolicy(), () -> KEY));
        assertEquals(1, requests.size());
        assertEquals(TaskClass.REASONING, c.taskClass());
        assertEquals(ReasoningEffort.HIGH, c.effort());
        assertEquals(List.of("classified by JEV (reasoning 0.97)"), c.signals());
    }

    // --- invalid answers -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            // an unknown class
            "{\"task_class\":{\"choice\":\"banter\",\"confidence\":1.0,\"probabilities\":{\"banter\":1.0}},"
                    + "\"effort\":{\"choice\":\"low\",\"confidence\":1.0,\"probabilities\":{\"low\":1.0,\"medium\":0.0,\"high\":0.0}}}",
            // class probabilities that do not sum to one
            "{\"task_class\":{\"choice\":\"chat\",\"confidence\":0.5,\"probabilities\":"
                    + "{\"chat\":0.5,\"summarize\":0.1,\"agentic\":0.1,\"reasoning\":0.1,\"coding\":0.0}},"
                    + "\"effort\":{\"choice\":\"low\",\"confidence\":1.0,\"probabilities\":{\"low\":1.0,\"medium\":0.0,\"high\":0.0}}}",
            // an unknown effort beside a valid class
            "{\"task_class\":{\"choice\":\"chat\",\"confidence\":1.0,\"probabilities\":"
                    + "{\"chat\":1.0,\"summarize\":0.0,\"agentic\":0.0,\"reasoning\":0.0,\"coding\":0.0}},"
                    + "\"effort\":{\"choice\":\"extreme\",\"confidence\":1.0,\"probabilities\":{\"extreme\":1.0}}}",
            // effort probabilities that peak elsewhere than the choice
            "{\"task_class\":{\"choice\":\"chat\",\"confidence\":1.0,\"probabilities\":"
                    + "{\"chat\":1.0,\"summarize\":0.0,\"agentic\":0.0,\"reasoning\":0.0,\"coding\":0.0}},"
                    + "\"effort\":{\"choice\":\"low\",\"confidence\":1.0,\"probabilities\":{\"low\":0.1,\"medium\":0.9,\"high\":0.0}}}",
            // no effort head at all
            "{\"task_class\":{\"choice\":\"chat\",\"confidence\":1.0,\"probabilities\":"
                    + "{\"chat\":1.0,\"summarize\":0.0,\"agentic\":0.0,\"reasoning\":0.0,\"coding\":0.0}}}",
            // no answers
            "{}"
    })
    void anInvalidAnswerOnEitherHeadFallsBackToTheRules(String answers) {
        var result = JsonParser.parseString("{\"model\":\"jev-latest\"}").getAsJsonObject();
        if (!answers.equals("{}")) result.add("answers", JsonParser.parseString(answers));
        var verdict = withJev(chain -> reply(chain, 200, result.toString()), () -> classify(PROMPT, KEY, 0.0));
        assertNull(verdict.classification(), "even at a minimum confidence of 0: " + answers);
        assertFalse(verdict.signal());
        assertEquals("JEV answered with an invalid class or effort", verdict.reason());
        assertEquals(1, requests.size());
    }

    // --- transport failures ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {503, 429, 500})
    void anHttpErrorFallsBackAfterExactlyOneRequest(int status) {
        var verdict = withJev(chain -> reply(chain, status, "{}"), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(1, requests.size(), "a routed turn never retries JEV");
        assertNull(verdict.classification());
        assertFalse(verdict.signal());
        assertTrue(verdict.reason().contains("HTTP " + status), verdict.reason());
    }

    @Test
    void aRefusedKeyFallsBackWithoutEchoingTheKey() {
        var verdict = withJev(chain -> reply(chain, 401, "{\"error\":\"bad key\"}"), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(1, requests.size());
        assertNull(verdict.classification());
        assertTrue(verdict.reason().contains("HTTP 401"), verdict.reason());
        assertFalse(verdict.reason().contains(KEY), "the key is never logged");
    }

    @Test
    void aHangIsCutOffByTheClassifierTimeoutAndNotRetried() {
        long started = System.nanoTime();
        var verdict = withJev(hang(), () -> JevRouterClassifier.classify(PROMPT, KEY, 0.9, 1));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(1, requests.size());
        assertNull(verdict.classification(), "an answer that arrives after the timeout is not used");
        assertTrue(verdict.reason().contains("unreachable"), verdict.reason());
        assertTrue(elapsedMs < 5_000, "one attempt, no backoff: took " + elapsedMs + " ms");
        assertEquals(1, JevApi.breaker().stats().failures(), "a timeout counts against the breaker");
    }

    @Test
    void theRouterBoundsJevByThePolicysClassifierTimeout() {
        long started = System.nanoTime();
        var c = withJev(hang(), () -> RouterClassifier.classify(PROMPT, null, 0, jevPolicy(1), () -> KEY));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules answer once the 1 s bound passes");
        assertEquals(1, requests.size());
        assertTrue(elapsedMs < 5_000, "took " + elapsedMs + " ms");
    }

    @Test
    void theRouterFallsBackToTheRulesExactlyWhenJevFails() {
        var c = withJev(chain -> reply(chain, 503, "{}"),
                () -> RouterClassifier.classify(PROMPT, null, 0, jevPolicy(), () -> KEY));
        assertEquals(1, requests.size());
        assertEquals(TaskClass.AGENTIC, c.taskClass());
        assertEquals(List.of("asks to run"), c.signals(), "a fault adds nothing to the route; it was logged");
    }

    // --- the breaker (JCLAW-1302) ----------------------------------------------------------

    /** Minted before first use, so the registry hands JEV this one: open, it is probed on the next call. */
    private static CircuitBreaker breakerWithNoCooldown() {
        var c = JevApi.BREAKER_CONFIG;
        CircuitBreakers.remove(JevApi.BREAKER_NAME);
        return CircuitBreakers.get(JevApi.BREAKER_NAME, new CircuitBreaker.Config(c.windowSize(), c.failureRateThreshold(),
                c.minVolume(), 0L, c.halfOpenPermits(), c.slowCallDurationMillis(), c.slowCallRateThreshold(),
                c.consecutiveFailures()));
    }

    private void failThreeTimes() {
        for (var status : new int[] {503, 429, 500}) {
            withJev(chain -> reply(chain, status, "{}"), () -> classify(PROMPT, KEY, 0.9));
        }
    }

    @Test
    void threeCountedFailuresOpenTheBreakerAndTheRulesAnswerWithoutSendingOrWarning() {
        failThreeTimes();
        assertEquals(CircuitBreaker.State.OPEN, JevApi.breaker().state());

        var verdict = withJev(answering("reasoning", 0.97, "high"), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(3, requests.size(), "an open breaker sends nothing");
        assertNull(verdict.classification());
        assertTrue(verdict.signal(), "the breaker is no fault of this turn, so it joins the signals, not the log");
        assertEquals("JEV breaker open", verdict.reason());

        var c = withJev(answering("reasoning", 0.97, "high"),
                () -> RouterClassifier.classify(PROMPT, null, 0, jevPolicy(), () -> KEY));
        assertEquals(3, requests.size());
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules' class");
        assertEquals(List.of("asks to run", "JEV breaker open"), c.signals());
    }

    @Test
    void nothingIsSentWhileIsolatedAndRestoreResumes() {
        JevApi.breaker().trip();
        var verdict = withJev(answering("reasoning", 0.97, "high"), () -> classify(PROMPT, KEY, 0.9));
        assertTrue(requests.isEmpty(), "an isolated breaker sends nothing");
        assertTrue(verdict.signal());
        assertEquals("JEV breaker open", verdict.reason());

        JevApi.breaker().reset();
        var restored = withJev(answering("reasoning", 0.97, "high"), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(1, requests.size());
        assertNotNull(restored.classification());
    }

    @Test
    void theRegisteredBreakerReportsItsTransitions() {
        TelemetryTestSync.acquire();
        try {
            OtelRuntime.init();
            var metrics = OtelRuntime.captureMetricsForTest(this::failThreeTimes);
            var opened = metrics.stream()
                    .filter(m -> m.getName().equals(BreakerMetrics.TRANSITIONS))
                    .flatMap(m -> m.getLongSumData().getPoints().stream())
                    .filter(p -> JevApi.BREAKER_NAME.equals(p.getAttributes().get(BreakerMetrics.BREAKER)))
                    .map(p -> p.getAttributes().get(BreakerMetrics.STATE) + "/" + p.getAttributes().get(BreakerMetrics.REASON))
                    .toList();
            assertEquals(List.of("OPEN/CONSECUTIVE_FAILURES"), opened,
                    "BreakerAlarms logs the transition, which is why a routed turn warns about nothing");
        } finally {
            TelemetryTestSync.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    void aClientErrorIsNotCounted(int status) {
        for (int i = 0; i < 4; i++) withJev(chain -> reply(chain, status, "{}"), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(4, requests.size(), "TypeSafe answering with a 4xx never opens the breaker");
        var stats = JevApi.breaker().stats();
        assertEquals(CircuitBreaker.State.CLOSED, stats.state());
        assertEquals(0, stats.failures());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>gateway</html>", "{\"model\":\"jev-latest\",\"answers\":{}}"})
    void aMalformedAnswerIsNotCounted(String body) {
        for (int i = 0; i < 4; i++) withJev(chain -> reply(chain, 200, body), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(4, requests.size());
        assertEquals(CircuitBreaker.State.CLOSED, JevApi.breaker().state());
        assertEquals(0, JevApi.breaker().stats().failures());
    }

    @Test
    void aProbeThatAnswersClosesTheBreaker() {
        var breaker = breakerWithNoCooldown();
        failThreeTimes();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        var verdict = withJev(answering("reasoning", 0.97, "high"), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(4, requests.size(), "the cooldown has elapsed, so the next call is the probe");
        assertNotNull(verdict.classification());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void aProbeThatFailsReopensTheBreaker() {
        var breaker = breakerWithNoCooldown();
        failThreeTimes();
        withJev(chain -> reply(chain, 503, "{}"), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(4, requests.size());
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertEquals(CircuitBreaker.Reason.PROBE_FAILED, breaker.stats().reason());
    }

    @Test
    void aProbeTypeSafeRefusesStillHandsBackItsPermit() {
        var breaker = breakerWithNoCooldown();
        failThreeTimes();
        withJev(chain -> reply(chain, 401, "{}"), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(), "a refused key is TypeSafe answering");

        var verdict = withJev(answering("reasoning", 0.97, "high"), () -> classify(PROMPT, KEY, 0.9));
        assertEquals(5, requests.size());
        assertNotNull(verdict.classification());
    }

    // --- no key, and acknowledgments -------------------------------------------------------

    @Test
    void withNoKeyNothingIsSent() {
        for (var key : new String[] {null, "", "   "}) {
            var verdict = withJev(answering("reasoning", 0.97, "high"), () -> classify(PROMPT, key, 0.9));
            assertNull(verdict.classification());
            assertFalse(verdict.signal());
            assertTrue(verdict.reason().contains("Settings → Decision Providers"), verdict.reason());
        }
        assertTrue(requests.isEmpty(), "no request without a key");

        var c = withJev(answering("reasoning", 0.97, "high"),
                () -> RouterClassifier.classify(PROMPT, null, 0, jevPolicy(), () -> null));
        assertEquals(TaskClass.AGENTIC, c.taskClass());
        assertTrue(requests.isEmpty());
    }

    @Test
    void anAcknowledgmentIsSettledBeforeJevIsAsked() {
        var c = withJev(answering("coding", 0.99, "high"), () -> RouterClassifier.classify("thanks", TaskClass.REASONING, 0,
                jevPolicy(), () -> {
                    throw new AssertionError("the key is not even read for an acknowledgment");
                }));
        assertEquals(TaskClass.REASONING, c.taskClass());
        assertEquals(List.of("follow-up inherits reasoning"), c.signals());
        assertTrue(requests.isEmpty());
    }
}
