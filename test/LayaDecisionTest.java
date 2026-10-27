import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.ApiDecisionController;
import llm.routing.JevRouterClassifier;
import llm.routing.PromptClassifier.Classification;
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
import play.Play;
import play.test.UnitTest;
import services.ConfigService;
import services.EventLogger;
import services.decision.DecisionSettings;
import services.decision.JevApi;
import services.decision.LayaDecision;
import services.decision.OllamaDecision;
import utils.CircuitBreaker;
import utils.HttpFactories;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * JCLAW-1447: a Laya checkpoint as the router's classifier. The JEV request goes to the operator's
 * {@code laya-serve}, with its key when one is stored, under the {@code decision:laya} breaker, and every
 * failure leaves the keyword rules to answer.
 *
 * <p>This class and {@code ApiDecisionControllerTest} write {@code decision.laya.baseUrl} and
 * {@code decision.laya.apiKey}, each under {@link JevBreakerTestSync}.
 */
class LayaDecisionTest extends UnitTest {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String BASE = "http://192.168.1.30:8000";
    private static final String KEY = "laya-test-key-0123";
    private static final String PROMPT = "Run daily briefing skill";
    private static final List<String> CLASS_IDS = List.of("chat", "summarize", "agentic", "reasoning", "coding");
    private static final List<String> EFFORT_IDS = List.of("low", "medium", "high");
    /** What {@code laya-serve} 0.4.6 reports of a prompt it cut. */
    private static final String CUT = "{\"input_tokens\":512,\"output_tokens\":0,\"state_tokens\":619,"
            + "\"state_tokens_dropped\":276,\"truncated\":true,\"truncated_questions\":[\"task_class\",\"effort\"]}";

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

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

    private <T> T withLaya(Answer answer, Supplier<T> body) {
        Interceptor laya = chain -> {
            requests.add(chain.request());
            var requestBody = chain.request().body();
            if (requestBody != null) {
                var buffer = new Buffer();
                requestBody.writeTo(buffer);
                bodies.add(buffer.readUtf8());
            }
            return answer.reply(chain);
        };
        return HttpFactories.callWith(new OkHttpClient.Builder().addInterceptor(laya).build(), body);
    }

    private static Response reply(Interceptor.Chain chain, int code, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("canned").body(ResponseBody.create(body, JSON)).build();
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

    /** Laya's answer, with {@code usage} as raw JSON, or without one when it is null. */
    private static Answer answering(String taskClass, double p, String effort, String usage) {
        var answers = new JsonObject();
        answers.add("task_class", head(CLASS_IDS, taskClass, p));
        answers.add("effort", head(EFFORT_IDS, effort, 0.8));
        var result = new JsonObject();
        result.addProperty("model", "english");
        result.add("answers", answers);
        if (usage != null) result.add("usage", JsonParser.parseString(usage));
        return chain -> reply(chain, 200, result.toString());
    }

    private static Answer answering(String taskClass, double p, String effort) {
        return answering(taskClass, p, effort, "{\"input_tokens\":181,\"output_tokens\":0,\"state_tokens\":9,"
                + "\"state_tokens_dropped\":0,\"truncated\":false,\"truncated_questions\":[]}");
    }

    private static Answer unreachable() {
        return _ -> {
            throw new ConnectException("Connection refused");
        };
    }

    private static JevRouterClassifier.Verdict classify(String checkpoint, String key) {
        return JevRouterClassifier.classifyWithLaya(PROMPT, BASE, key, checkpoint, 0.5, 8);
    }

    private static JevRouterClassifier.Verdict classify(String checkpoint) {
        return classify(checkpoint, null);
    }

    private static RouterPolicy layaPolicy(int timeoutSeconds, double minConfidence) {
        return new RouterPolicy(Map.of(TaskClass.CHAT, List.of(new Candidate("p", "m"))), 0.75, 0.95,
                new Candidate(LayaDecision.PROVIDER, "english"), timeoutSeconds, true, minConfidence);
    }

    /** The policy's defaults: 8 s and 0.50. */
    private static RouterPolicy layaPolicy() {
        return layaPolicy(8, 0.50);
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

    /** A routed turn whose classifier is Laya's {@code english}, on the stored address and key. */
    private Classification route(Answer answer) {
        return withLaya(answer, () -> RouterClassifier.classify(PROMPT, null, 0, layaPolicy(), () -> null));
    }

    // --- the request -----------------------------------------------------------------------

    @Test
    void theRoutedTurnPostsJevsRequestToTheServersSystemOneRouteNamingTheCheckpoint() {
        withLaya(answering("chat", 0.9, "low"), () -> JevRouterClassifier.classify(PROMPT, "ts-key", 0.5, 8));
        var jev = JsonParser.parseString(bodies.getFirst()).getAsJsonObject();
        requests.clear();
        bodies.clear();

        route(answering("agentic", 0.76, "medium"));

        assertEquals(1, requests.size());
        var request = requests.getFirst();
        assertEquals("POST", request.method());
        assertEquals(LayaDecision.DEFAULT_BASE_URL + "/v1/systemone", request.url().toString(),
                "no address stored, so laya-serve's own default on this machine");
        assertNull(request.header("Authorization"), "no key stored, so none is sent");
        var body = JsonParser.parseString(bodies.getFirst()).getAsJsonObject();
        assertEquals("english", body.get("model").getAsString());
        jev.addProperty("model", "english");
        assertEquals(jev, body, "the request JEV gets, but for the model");
    }

    @Test
    void aStoredAddressAndKeyAreWhereAndHowTheRoutedTurnCalls() {
        ConfigService.set(LayaDecision.BASE_URL_KEY, BASE + "/");
        ConfigService.set(LayaDecision.API_KEY, " " + KEY + " ");
        try {
            route(answering("agentic", 0.76, "medium"));
            assertEquals(BASE + "/v1/systemone", requests.getFirst().url().toString(), "a trailing slash is not doubled");
            assertEquals("Bearer " + KEY, requests.getFirst().header("Authorization"));
        } finally {
            ConfigService.delete(LayaDecision.BASE_URL_KEY);
            ConfigService.delete(LayaDecision.API_KEY);
        }
    }

    // --- the answer ------------------------------------------------------------------------

    @Test
    void aConfidentAnswerIsTheRoutersClassAndEffort() {
        var c = route(answering("agentic", 0.76, "medium"));
        assertEquals(TaskClass.AGENTIC, c.taskClass());
        assertEquals(ReasoningEffort.MEDIUM, c.effort());
        assertEquals(List.of("classified by Laya english (agentic 0.76)"), c.signals());
    }

    @Test
    void aCutPromptSaysHowMuchOfItWasRead() {
        var c = route(answering("agentic", 0.76, "medium", CUT));
        assertEquals(TaskClass.AGENTIC, c.taskClass());
        assertEquals(List.of("classified by Laya english (agentic 0.76)", "Laya english read 343 of 619 prompt tokens"),
                c.signals());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "\"truncated\"",
            "[true]",
            "{}",
            "{\"truncated\":true}",
            "{\"truncated\":\"true\",\"state_tokens\":619,\"state_tokens_dropped\":276}",
            "{\"truncated\":1,\"state_tokens\":619,\"state_tokens_dropped\":276}",
            "{\"truncated\":false,\"state_tokens\":619,\"state_tokens_dropped\":276}",
            "{\"truncated\":true,\"state_tokens\":\"619\",\"state_tokens_dropped\":276}",
            "{\"truncated\":true,\"state_tokens\":619,\"state_tokens_dropped\":null}",
            "{\"truncated\":true,\"state_tokens\":619,\"state_tokens_dropped\":0}",
            "{\"truncated\":true,\"state_tokens\":619,\"state_tokens_dropped\":-3}",
            "{\"truncated\":true,\"state_tokens\":619,\"state_tokens_dropped\":620}",
            "{\"truncated\":true,\"state_tokens\":619.5,\"state_tokens_dropped\":276}",
            "{\"truncated\":true,\"state_tokens\":1e400,\"state_tokens_dropped\":276}",
            "{\"truncated\":true,\"state_tokens\":{},\"state_tokens_dropped\":[]}"})
    void aUsageThatReportsNoCutOrCannotBeReadAddsNothingAndStillAnswers(String usage) {
        var c = route(answering("agentic", 0.76, "medium", usage));
        assertEquals(TaskClass.AGENTIC, c.taskClass(), usage);
        assertEquals(List.of("classified by Laya english (agentic 0.76)"), c.signals(), usage);
    }

    @Test
    void anAnswerWithNoUsageAtAllStillAnswers() {
        var c = route(answering("agentic", 0.76, "medium", null));
        assertEquals(List.of("classified by Laya english (agentic 0.76)"), c.signals());
    }

    @Test
    void anUnsureAnswerIsLeftToTheRules() {
        var c = route(answering("reasoning", 0.42, "high", CUT));
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules' class");
        assertEquals(List.of("asks to run", "Laya english unsure (0.42 < 0.50)"), c.signals());
    }

    // --- outages ---------------------------------------------------------------------------

    @Test
    void anUnreachableServerLeavesTheRulesToAnswerWarnsAndCountsOnLayasBreakerAlone() {
        var verdict = new JevRouterClassifier.Verdict[1];
        var logged = EventLogger.captureForTest(() -> verdict[0] = withLaya(unreachable(), () -> classify("english")));

        assertEquals(1, requests.size(), "never retried on a routed turn");
        assertNull(verdict[0].classification());
        assertFalse(verdict[0].signal(), "a fault is logged, not put on the route");
        assertEquals("the Laya english classifier failed (Laya unreachable)", verdict[0].reason());
        assertTrue(logged.stream().anyMatch(e -> e.level().equals("WARN") && e.category().equals("router")
                        && e.message().equals("the Laya english classifier failed (Laya unreachable); using the keyword rules")),
                () -> "no warning among " + logged);
        assertEquals(1, LayaDecision.breaker().stats().failures());
        assertEquals(0, JevApi.breaker().stats().failures(), "JEV's breaker is its own");
        assertEquals(0, OllamaDecision.breaker().stats().failures(), "and so is Ollama's");

        var c = route(unreachable());
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules answer");
        assertEquals(List.of("asks to run"), c.signals());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void aServerErrorOrABusyServerCountsAsAnOutage(int status) {
        var verdict = withLaya(chain -> reply(chain, status, "{\"detail\":\"server busy, try again later\"}"),
                () -> classify("english"));
        assertEquals(1, requests.size());
        assertEquals("the Laya english classifier failed (Laya returned HTTP " + status + ")", verdict.reason());
        assertEquals(1, LayaDecision.breaker().stats().failures());
    }

    @Test
    void aTimeoutSaysSoAndCounts() {
        var verdict = withLaya(hang(), () -> JevRouterClassifier.classifyWithLaya(PROMPT, BASE, null, "english", 0.5, 1));
        assertNull(verdict.classification());
        assertEquals("the Laya english classifier failed (Laya did not answer within 1 s)", verdict.reason());
        assertEquals(1, LayaDecision.breaker().stats().failures());
    }

    // --- the policy's own bounds -----------------------------------------------------------

    @Test
    void theRoutedTurnHoldsLayaToThePolicysMinimumConfidence() {
        var c = withLaya(answering("reasoning", 0.76, "high"),
                () -> RouterClassifier.classify(PROMPT, null, 0, layaPolicy(1, 0.90), () -> null));
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "0.76 stands under the default 0.50, and not under this policy's 0.90");
        assertEquals(List.of("asks to run", "Laya english unsure (0.76 < 0.90)"), c.signals());
    }

    @Test
    void theRoutedTurnBoundsLayaByThePolicysClassifierTimeout() {
        long started = System.nanoTime();
        var c = withLaya(hang(), () -> RouterClassifier.classify(PROMPT, null, 0, layaPolicy(1, 0.90), () -> null));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules answer once the 1 s bound passes");
        assertEquals(1, requests.size());
        assertTrue(elapsedMs < 5_000, "took " + elapsedMs + " ms");
    }

    @Test
    void anOpenBreakerSendsNothingToLayaAndLeavesJevAndOllamaCalled() {
        for (var i = 0; i < 3; i++) withLaya(unreachable(), () -> classify("english"));
        assertEquals(CircuitBreaker.State.OPEN, LayaDecision.breaker().state());
        assertEquals(CircuitBreaker.State.CLOSED, JevApi.breaker().state());
        assertEquals(CircuitBreaker.State.CLOSED, OllamaDecision.breaker().state());

        var c = route(answering("reasoning", 0.97, "high"));
        assertEquals(3, requests.size(), "an open breaker sends nothing");
        assertEquals(List.of("asks to run", "Laya english breaker open"), c.signals());

        requests.clear();
        var jev = withLaya(answering("reasoning", 0.97, "high"),
                () -> JevRouterClassifier.classify(PROMPT, "ts-key", 0.5, 8));
        var ollama = withLaya(answering("reasoning", 0.97, "high"),
                () -> JevRouterClassifier.classifyWithOllama(PROMPT, "http://192.168.1.20:11434", "tev1", 0.5, 8));
        assertEquals(List.of("api.typesafe.ai", "192.168.1.20"), requests.stream().map(r -> r.url().host()).toList(),
                "both were sent");
        assertNotNull(jev.classification());
        assertNotNull(ollama.classification());
    }

    @Test
    void anOutageOfJevOrOllamaNeverMovesLayasBreaker() {
        for (var i = 0; i < 3; i++) {
            withLaya(unreachable(), () -> JevRouterClassifier.classify(PROMPT, "ts-key", 0.5, 8));
            withLaya(unreachable(),
                    () -> JevRouterClassifier.classifyWithOllama(PROMPT, "http://192.168.1.20:11434", "tev1", 0.5, 8));
        }
        assertEquals(CircuitBreaker.State.OPEN, JevApi.breaker().state());
        assertEquals(CircuitBreaker.State.OPEN, OllamaDecision.breaker().state());
        assertEquals(0, LayaDecision.breaker().stats().failures());

        requests.clear();
        assertNotNull(withLaya(answering("agentic", 0.76, "medium"), () -> classify("english")).classification());
        assertEquals(1, requests.size());
    }

    @Test
    void theTargetIsDialedThroughTheGuardedClientUnderLayasBreaker() {
        // A canned transport answers for the guarded and the plain client alike, so the flag is read here.
        var target = LayaDecision.target(BASE + "/", KEY);
        assertTrue(target.guarded(), "an address the operator chose goes through the SSRF-guarded client");
        assertEquals(BASE + "/v1/systemone", target.url());
        assertEquals(KEY, target.apiKey());
        assertEquals("decision:laya", target.breakerName());
        assertThrows(SecurityException.class, () -> LayaDecision.target("http://169.254.169.254:8000", KEY));
    }

    @Test
    void theBreakerHasJevsFixedThresholdsUnderItsOwnName() {
        assertEquals("decision:laya", LayaDecision.BREAKER_NAME);
        withLaya(unreachable(), () -> classify("multilingual"));
        withLaya(unreachable(), () -> classify("typed-decisions"));
        assertEquals(CircuitBreaker.State.CLOSED, LayaDecision.breaker().state());
        withLaya(unreachable(), () -> classify("english"));
        assertEquals(CircuitBreaker.State.OPEN, LayaDecision.breaker().state(),
                "three in a row, whichever checkpoint was asked");
    }

    // --- the key ---------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedKeyNamesTheLayaKeyAndWhereToChangeItAndIsNeverCounted(int status) {
        var verdict = new JevRouterClassifier.Verdict[1];
        var logged = EventLogger.captureForTest(() -> verdict[0] = withLaya(
                chain -> reply(chain, status, "{\"detail\":\"invalid or missing bearer token\"}"),
                () -> classify("english", KEY)));

        assertEquals("Bearer " + KEY, requests.getFirst().header("Authorization"));
        assertNull(verdict[0].classification());
        assertEquals("the Laya english classifier failed (the Laya server refused the Laya API key (HTTP " + status
                + "); the operator must update it in Settings → Decision Providers)", verdict[0].reason());
        var warning = logged.stream().filter(e -> e.level().equals("WARN") && e.category().equals("router")).toList();
        assertEquals(1, warning.size(), () -> "one router warning among " + logged);
        assertTrue(warning.getFirst().message().contains("Laya API key"), warning.getFirst().message());
        assertTrue(warning.getFirst().message().contains("Settings → Decision Providers"), warning.getFirst().message());
        assertTrue(logged.stream().noneMatch(e -> e.message().contains(KEY)), "the key is never logged");
        assertEquals(0, LayaDecision.breaker().stats().failures(), "a refused key is the server answering");

        for (var i = 0; i < 3; i++) {
            withLaya(chain -> reply(chain, status, "{}"), () -> classify("english", KEY));
        }
        assertEquals(CircuitBreaker.State.CLOSED, LayaDecision.breaker().state());
    }

    @Test
    void aKeyTheServerWantsAndJClawDoesNotHoldFailsWithoutCounting() {
        var verdict = withLaya(chain -> reply(chain, 401, "{\"detail\":\"invalid or missing bearer token\"}"),
                () -> classify("english"));
        assertNull(requests.getFirst().header("Authorization"));
        assertEquals("the Laya english classifier failed (Laya returned HTTP 401)", verdict.reason());
        assertEquals(0, LayaDecision.breaker().stats().failures());
    }

    @Test
    void theStatusRoutesAnswerHasNoFieldForTheKey() {
        assertEquals(List.of("baseUrl", "reachable", "error", "loaded", "device"),
                Arrays.stream(ApiDecisionController.LayaDecisionStatus.class.getRecordComponents())
                        .map(c -> c.getName()).toList());
    }

    // --- the address -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254", "http://169.254.169.254:8000", "http://224.0.0.1:8000",
            "http://0.0.0.0:8000", "file:///etc/passwd"})
    void aRefusedAddressIsNeverDialedAndTheClassifierSaysSo(String address) {
        var verdict = withLaya(answering("chat", 0.95, "low"),
                () -> JevRouterClassifier.classifyWithLaya(PROMPT, address, KEY, "english", 0.5, 8));
        assertTrue(requests.isEmpty(), "nothing is sent, the key least of all");
        assertNull(verdict.classification());
        assertTrue(verdict.reason().startsWith("the Laya classifier's server address is refused ("), verdict.reason());
        assertEquals(0, LayaDecision.breaker().stats().samples(), "refused before the breaker is asked");

        var status = withLaya(chain -> reply(chain, 200, "{\"status\":\"ok\"}"), () -> LayaDecision.status(address, KEY));
        assertTrue(requests.isEmpty());
        assertFalse(status.reachable());
        assertNotNull(status.error());
    }

    @Test
    void aLinkLocalAddressStoredBeforeTheWriteCheckIsNeverDialedByARoutedTurn() {
        // set() skips the write rules, as a row older than them would have.
        ConfigService.set(LayaDecision.BASE_URL_KEY, "http://169.254.169.254:8000");
        try {
            var c = route(answering("reasoning", 0.97, "high"));
            assertTrue(requests.isEmpty());
            assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules answer");
            assertEquals(List.of("asks to run"), c.signals());
        } finally {
            ConfigService.delete(LayaDecision.BASE_URL_KEY);
        }
    }

    @Test
    void theAddressDefaultsToLayaServesOwnAndAStoredOneReplacesIt() {
        ConfigService.delete(LayaDecision.BASE_URL_KEY);
        assertEquals("http://localhost:8000", LayaDecision.baseUrl());
        ConfigService.set(LayaDecision.BASE_URL_KEY, " " + BASE + "/ ");
        try {
            assertEquals(BASE, LayaDecision.baseUrl());
        } finally {
            ConfigService.delete(LayaDecision.BASE_URL_KEY);
        }
    }

    // --- the write rules -------------------------------------------------------------------

    @Test
    void theAddressIsScreenedLikeAProviderUrl() {
        var key = LayaDecision.BASE_URL_KEY;
        for (var ok : new String[] {"", "http://localhost:8000", "http://127.0.0.1:8000", "http://192.168.1.30:8000",
                "http://10.0.0.5:8000/", "https://laya.internal"}) {
            assertNull(DecisionSettings.rejectionFor(key, ok), () -> "refused " + ok);
        }
        for (var bad : new String[] {"http://169.254.169.254", "http://0.0.0.0:8000", "http://224.0.0.1:8000",
                "file:///etc/passwd", "localhost:8000"}) {
            assertNotNull(DecisionSettings.rejectionFor(key, bad), () -> "accepted " + bad);
        }
    }

    @Test
    void theKeyMustSurviveAnAuthorizationHeader() {
        assertNull(DecisionSettings.rejectionFor(LayaDecision.API_KEY, "laya-anything_1.2/3+4="));
        assertNull(DecisionSettings.rejectionFor(LayaDecision.API_KEY, ""), "a blank key clears it");
        for (var bad : new String[] {"laya key", " laya-key", "laya-key\n", "laya-kéy"}) {
            var rejected = DecisionSettings.rejectionFor(LayaDecision.API_KEY, bad);
            assertNotNull(rejected, "refused: " + bad.replace("\n", "\\n"));
            assertTrue(rejected.startsWith(LayaDecision.API_KEY), rejected);
            assertFalse(rejected.contains(bad), "the refusal does not echo the key");
        }
    }

    // --- the server's status ---------------------------------------------------------------

    @Test
    void statusReadsTheLoadedCheckpointsAndTheDeviceFromHealthSendingTheKey() {
        var health = "{\"status\":\"ok\",\"loaded\":[\"english\",\"multilingual\"],\"revisions\":{},\"device\":\"mps\","
                + "\"device_is_preference\":false,\"checkpoint_devices\":{\"english\":\"mps\"},\"cpu_fallbacks\":{}}";
        var status = withLaya(chain -> reply(chain, 200, health), () -> LayaDecision.status(BASE + "/", KEY));

        var request = requests.getFirst();
        assertEquals("GET", request.method());
        assertEquals(BASE + "/health", request.url().toString());
        assertEquals("Bearer " + KEY, request.header("Authorization"));
        assertEquals(new LayaDecision.Status(BASE, true, null, List.of("english", "multilingual"), "mps"), status);
        assertEquals(0, LayaDecision.breaker().stats().samples(), "a status read is no decision");

        withLaya(chain -> reply(chain, 200, health), () -> LayaDecision.status(BASE, null));
        assertNull(requests.getLast().header("Authorization"));
    }

    @Test
    void aServerThatWantsAKeyItWasNotSentIsReachableWithNothingElseKnown() {
        var status = withLaya(chain -> reply(chain, 200, "{\"status\":\"ok\"}"), () -> LayaDecision.status(BASE, null));
        assertEquals(new LayaDecision.Status(BASE, true, null, List.of(), null), status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>router login secret-detail</html>", "", "[]", "\"ok\"", "{}", "{\"status\":\"down\"}",
            "{\"status\":true}", "{\"models\":[{\"name\":\"tev1:latest\"}]}", "{\"status\":\"ok\""})
    void anAnswerThatIsNotLayasHealthIsNotReachableAndIsNeverEchoed(String body) {
        var status = withLaya(chain -> reply(chain, 200, body), () -> LayaDecision.status(BASE, null));
        assertFalse(status.reachable(), body);
        assertEquals("/health did not answer as a Laya server", status.error());
        assertEquals(List.of(), status.loaded());
        assertNull(status.device());
    }

    @Test
    void statusOfAServerThatFailsOrCannotBeReachedNamesOnlyTheFailure() {
        var failing = withLaya(chain -> reply(chain, 503, "{\"detail\":\"secret detail\"}"), () -> LayaDecision.status(BASE, null));
        assertFalse(failing.reachable());
        assertEquals("HTTP 503 from /health", failing.error());

        var down = withLaya(unreachable(), () -> LayaDecision.status(BASE, null));
        assertFalse(down.reachable());
        assertEquals("not reachable (ConnectException)", down.error());
    }

    @Test
    void statusOfAnAddressTheWriteCheckPassesAndOkHttpCannotBuildIsNotReachable() {
        var address = "http://localhost:99999";
        assertNull(DecisionSettings.rejectionFor(LayaDecision.BASE_URL_KEY, address), "the write check lets it through");
        var status = withLaya(chain -> reply(chain, 200, "{\"status\":\"ok\"}"), () -> LayaDecision.status(address, KEY));
        assertTrue(requests.isEmpty());
        assertEquals(new LayaDecision.Status(address, false, "not a usable address", List.of(), null), status);
    }

    // --- what the frontend holds a copy of -------------------------------------------------

    @Test
    void theCheckpointNamesAndTheDefaultAddressEqualTheirFrontendTwins() throws IOException {
        assertEquals(List.of("english", "multilingual", "typed-decisions"), LayaDecision.CHECKPOINTS);

        var twin = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "frontend/utils/decision-models.ts"));
        var table = Pattern.compile("export const LAYA_CHECKPOINTS[^=]*=\\s*\\[(.*?)]", Pattern.DOTALL).matcher(twin);
        assertTrue(table.find(), "frontend/utils/decision-models.ts declares LAYA_CHECKPOINTS");
        var names = Pattern.compile("name:\\s*'([^']+)'").matcher(table.group(1)).results().map(m -> m.group(1)).toList();
        assertEquals(LayaDecision.CHECKPOINTS, names);

        var address = Pattern.compile("export const LAYA_DEFAULT_ADDRESS\\s*=\\s*'([^']+)'").matcher(twin);
        assertTrue(address.find(), "frontend/utils/decision-models.ts declares LAYA_DEFAULT_ADDRESS");
        assertEquals(LayaDecision.DEFAULT_BASE_URL, address.group(1));
    }
}
