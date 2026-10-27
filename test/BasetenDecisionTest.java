import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import services.decision.BasetenDecision;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * JF-133: a Baseten model as the router's classifier. The JEV request goes to Baseten's endpoint with the stored key,
 * naming the model's ID, under the {@code decision:baseten} breaker, and every failure leaves the keyword rules to
 * answer.
 *
 * <p>Only this class writes {@code decision.baseten.apiKey}, and it and {@code RouterPolicyTest} write
 * {@code decision.baseten.models}, each under {@link JevBreakerTestSync}.
 */
class BasetenDecisionTest extends UnitTest {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String ELEPHANT = "decision-models/baseten-elephant-9b-latest";
    private static final String MAMMOTH = "decision-models/baseten-mammoth-27b-latest";
    private static final String KEY = "baseten-test-key-0123";
    private static final String PROMPT = "Run daily briefing skill";
    private static final List<String> CLASS_IDS = List.of("chat", "summarize", "agentic", "reasoning", "coding");
    private static final List<String> EFFORT_IDS = List.of("low", "medium", "high");

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
        try {
            ConfigService.delete(BasetenDecision.API_KEY);
            ConfigService.delete(BasetenDecision.MODELS_KEY);
        } finally {
            JevBreakerTestSync.release();
        }
    }

    @FunctionalInterface
    private interface Answer {
        Response reply(Interceptor.Chain chain) throws IOException;
    }

    private <T> T withBaseten(Answer answer, Supplier<T> body) {
        Interceptor baseten = chain -> {
            requests.add(chain.request());
            var requestBody = chain.request().body();
            if (requestBody != null) {
                var buffer = new Buffer();
                requestBody.writeTo(buffer);
                bodies.add(buffer.readUtf8());
            }
            return answer.reply(chain);
        };
        return HttpFactories.callWith(new OkHttpClient.Builder().addInterceptor(baseten).build(), body);
    }

    private static Response reply(Interceptor.Chain chain, int code, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("canned").body(ResponseBody.create(body, JSON)).build();
    }

    private static JsonObject head(List<String> ids, String selected, double p) {
        var probabilities = new JsonObject();
        ids.forEach(id -> probabilities.addProperty(id, id.equals(selected) ? p : (1 - p) / (ids.size() - 1)));
        var answer = new JsonObject();
        answer.addProperty("choice", selected);
        answer.addProperty("confidence", p);
        answer.add("probabilities", probabilities);
        return answer;
    }

    /** Baseten's answer, shaped as its decision-models page shows one. */
    private static Answer answering(String taskClass, double p, String effort) {
        var answers = new JsonObject();
        answers.add("task_class", head(CLASS_IDS, taskClass, p));
        answers.add("effort", head(EFFORT_IDS, effort, 0.8));
        var result = new JsonObject();
        result.add("answers", answers);
        result.add("usage", JsonParser.parseString("{\"input_tokens\":181,\"output_tokens\":2}"));
        return chain -> reply(chain, 200, result.toString());
    }

    private static Answer unreachable() {
        return _ -> {
            throw new ConnectException("Connection refused");
        };
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

    private static JevRouterClassifier.Verdict classify(String model) {
        return JevRouterClassifier.classifyWithBaseten(PROMPT, KEY, model, 0.5, 8);
    }

    private static RouterPolicy basetenPolicy(String model) {
        return new RouterPolicy(Map.of(TaskClass.CHAT, List.of(new Candidate("p", "m"))), 0.75, 0.95,
                new Candidate(BasetenDecision.PROVIDER, model), 8, true, 0.50);
    }

    /** A routed turn whose classifier is elephant-1, on the stored key. */
    private Classification route(Answer answer) {
        return withBaseten(answer, () -> RouterClassifier.classify(PROMPT, null, 0, basetenPolicy(ELEPHANT), () -> null));
    }

    private Classification routeWithKey(Answer answer) {
        ConfigService.set(BasetenDecision.API_KEY, " " + KEY + " ");
        return route(answer);
    }

    // --- the request -----------------------------------------------------------------------

    @Test
    void theRoutedTurnPostsJevsRequestToBasetensEndpointWithTheKeyAndTheModelsId() {
        withBaseten(answering("chat", 0.9, "low"), () -> JevRouterClassifier.classify(PROMPT, "ts-key", 0.5, 8));
        var jev = JsonParser.parseString(bodies.getFirst()).getAsJsonObject();
        requests.clear();
        bodies.clear();

        routeWithKey(answering("agentic", 0.76, "medium"));

        assertEquals(1, requests.size());
        var request = requests.getFirst();
        assertEquals("POST", request.method());
        assertEquals("https://inference.baseten.co/v1/systemone", request.url().toString());
        assertEquals("Bearer " + KEY, request.header("Authorization"), "the stored key, trimmed");
        jev.addProperty("model", ELEPHANT);
        assertEquals(jev, JsonParser.parseString(bodies.getFirst()).getAsJsonObject(), "the request JEV gets, but for the model");
    }

    @Test
    void theTargetIsBasetensFixedEndpointOnThePlainClientUnderItsOwnBreaker() {
        // A canned transport answers for the guarded and the plain client alike, so the flag is read here.
        var target = BasetenDecision.target(KEY);
        assertFalse(target.guarded(), "Baseten's address is never the operator's, as JEV's is not");
        assertEquals("https://inference.baseten.co/v1/systemone", target.url());
        assertEquals(KEY, target.apiKey());
        assertEquals("decision:baseten", target.breakerName());
    }

    // --- the answer ------------------------------------------------------------------------

    @Test
    void aConfidentAnswerIsTheRoutersClassAndEffortNamedByTheModel() {
        var c = routeWithKey(answering("agentic", 0.76, "medium"));
        assertEquals(TaskClass.AGENTIC, c.taskClass());
        assertEquals(ReasoningEffort.MEDIUM, c.effort());
        assertEquals(List.of("classified by Baseten elephant-1 (agentic 0.76)"), c.signals());

        var mammoth = withBaseten(answering("reasoning", 0.91, "high"), () -> classify(MAMMOTH)).classification();
        assertNotNull(mammoth);
        assertEquals(List.of("classified by Baseten mammoth-1 (reasoning 0.91)"), mammoth.signals());
        assertEquals(MAMMOTH, JsonParser.parseString(bodies.getLast()).getAsJsonObject().get("model").getAsString());
    }

    @Test
    void anUnsureAnswerIsLeftToTheRules() {
        var c = routeWithKey(answering("reasoning", 0.42, "high"));
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules' class");
        assertEquals(List.of("asks to run", "Baseten elephant-1 unsure (0.42 < 0.50)"), c.signals());
    }

    // --- the key ---------------------------------------------------------------------------

    @Test
    void withNoKeyNothingIsSentAndTheWarningSaysWhereToSetOne() {
        var c = new Classification[1];
        var logged = EventLogger.captureForTest(() -> c[0] = route(answering("reasoning", 0.97, "high")));

        assertTrue(requests.isEmpty(), "nothing is sent");
        assertEquals(TaskClass.AGENTIC, c[0].taskClass(), "the rules answer");
        assertEquals(List.of("asks to run"), c[0].signals());
        assertEquals(List.of("the Baseten classifier has no Baseten API key; set one in Settings → Decision Providers; "
                + "using the keyword rules"), routerWarnings(logged));
        assertEquals(0, BasetenDecision.breaker().stats().samples(), "refused before the breaker is asked");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedKeyNamesTheBasetenKeyAndWhereToChangeItAndIsNeverCounted(int status) {
        ConfigService.set(BasetenDecision.API_KEY, KEY);
        var c = new Classification[1];
        var logged = EventLogger.captureForTest(() -> c[0] = route(chain -> reply(chain, status, "{\"error\":\"bad key\"}")));

        assertEquals("Bearer " + KEY, requests.getFirst().header("Authorization"));
        assertEquals(TaskClass.AGENTIC, c[0].taskClass(), "the rules answer");
        assertEquals(List.of("the Baseten elephant-1 classifier failed (Baseten refused the Baseten API key (HTTP " + status
                + "); the operator must update it in Settings → Decision Providers); using the keyword rules"),
                routerWarnings(logged));
        assertTrue(logged.stream().noneMatch(e -> e.message().contains(KEY)), "the key is never logged");
        assertEquals(0, BasetenDecision.breaker().stats().failures(), "a refused key is Baseten answering");

        for (var i = 0; i < 3; i++) withBaseten(chain -> reply(chain, status, "{}"), () -> classify(ELEPHANT));
        assertEquals(CircuitBreaker.State.CLOSED, BasetenDecision.breaker().state());
    }

    @Test
    void aRefusedPaymentNamesTheBillingOfTheBasetenAccountAndIsNeverCounted() {
        ConfigService.set(BasetenDecision.API_KEY, KEY);
        var c = new Classification[1];
        var logged = EventLogger.captureForTest(() -> c[0] = route(chain -> reply(chain, 402, "{\"error\":\"pay\"}")));

        assertEquals(TaskClass.AGENTIC, c[0].taskClass(), "the rules answer");
        assertEquals(List.of("asks to run"), c[0].signals());
        assertEquals(List.of("the Baseten elephant-1 classifier failed (Baseten asked for payment (HTTP 402); the operator "
                + "must check the billing of the account behind the Baseten API key in Settings → Decision Providers); "
                + "using the keyword rules"), routerWarnings(logged));
        assertTrue(logged.stream().noneMatch(e -> e.message().contains(KEY)), "the key is never logged");

        for (var i = 0; i < 3; i++) withBaseten(chain -> reply(chain, 402, "{}"), () -> classify(ELEPHANT));
        assertEquals(0, BasetenDecision.breaker().stats().failures());
        assertEquals(CircuitBreaker.State.CLOSED, BasetenDecision.breaker().state());
    }

    @Test
    void anyOtherRefusalFallsBackWithoutCounting() {
        var verdict = withBaseten(chain -> reply(chain, 404, "{\"error\":\"no such model\"}"), () -> classify("tev1"));
        assertEquals("the Baseten tev1 classifier failed (Baseten returned HTTP 404)", verdict.reason());
        assertEquals(0, BasetenDecision.breaker().stats().failures());
    }

    // --- outages ---------------------------------------------------------------------------

    @Test
    void anUnreachableEndpointLeavesTheRulesToAnswerAndCountsOnBasetensBreakerAlone() {
        var verdict = new JevRouterClassifier.Verdict[1];
        var logged = EventLogger.captureForTest(() -> verdict[0] = withBaseten(unreachable(), () -> classify(ELEPHANT)));

        assertEquals(1, requests.size(), "never retried on a routed turn");
        assertNull(verdict[0].classification());
        assertFalse(verdict[0].signal(), "a fault is logged, not put on the route");
        assertEquals(List.of("the Baseten elephant-1 classifier failed (Baseten unreachable); using the keyword rules"),
                routerWarnings(logged));
        assertEquals(1, BasetenDecision.breaker().stats().failures());
        assertEquals(0, JevApi.breaker().stats().failures(), "JEV's breaker is its own");
        assertEquals(0, LayaDecision.breaker().stats().failures(), "and so is Laya's");
        assertEquals(0, OllamaDecision.breaker().stats().failures(), "and Ollama's");

        var c = routeWithKey(unreachable());
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules answer");
        assertEquals(List.of("asks to run"), c.signals());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503, 529})
    void aServerErrorOrABusyServerCountsAsAnOutage(int status) {
        var verdict = withBaseten(chain -> reply(chain, status, "{\"error\":\"overloaded\"}"), () -> classify(ELEPHANT));
        assertEquals(1, requests.size(), "one attempt on a routed turn, as JEV's");
        assertEquals("the Baseten elephant-1 classifier failed (Baseten returned HTTP " + status + ")", verdict.reason());
        assertEquals(1, BasetenDecision.breaker().stats().failures());
        assertEquals(0, JevApi.breaker().stats().failures());
    }

    @Test
    void aTimeoutSaysSoAndCounts() {
        var verdict = withBaseten(hang(), () -> JevRouterClassifier.classifyWithBaseten(PROMPT, KEY, ELEPHANT, 0.5, 1));
        assertNull(verdict.classification());
        assertEquals("the Baseten elephant-1 classifier failed (Baseten did not answer within 1 s)", verdict.reason());
        assertEquals(1, BasetenDecision.breaker().stats().failures());
    }

    @Test
    void theBreakerHasJevsFixedThresholdsUnderItsOwnName() {
        assertEquals("decision:baseten", BasetenDecision.BREAKER_NAME);
        withBaseten(unreachable(), () -> classify(ELEPHANT));
        withBaseten(unreachable(), () -> classify(MAMMOTH));
        assertEquals(CircuitBreaker.State.CLOSED, BasetenDecision.breaker().state());
        withBaseten(unreachable(), () -> classify(ELEPHANT));
        assertEquals(CircuitBreaker.State.OPEN, BasetenDecision.breaker().state(), "three in a row, whichever model was asked");
    }

    @Test
    void anOpenBreakerSendsNothingToBasetenAndLeavesTheOthersCalled() {
        for (var i = 0; i < 3; i++) withBaseten(unreachable(), () -> classify(ELEPHANT));
        assertEquals(CircuitBreaker.State.OPEN, BasetenDecision.breaker().state());

        var c = routeWithKey(answering("reasoning", 0.97, "high"));
        assertEquals(3, requests.size(), "an open breaker sends nothing");
        assertEquals(List.of("asks to run", "Baseten elephant-1 breaker open"), c.signals());

        requests.clear();
        var jev = withBaseten(answering("reasoning", 0.97, "high"),
                () -> JevRouterClassifier.classify(PROMPT, "ts-key", 0.5, 8));
        var laya = withBaseten(answering("reasoning", 0.97, "high"),
                () -> JevRouterClassifier.classifyWithLaya(PROMPT, "http://192.168.1.30:8000", null, "english", 0.5, 8));
        var ollama = withBaseten(answering("reasoning", 0.97, "high"),
                () -> JevRouterClassifier.classifyWithOllama(PROMPT, "http://192.168.1.20:11434", "tev1", 0.5, 8));
        assertEquals(List.of("api.typesafe.ai", "192.168.1.30", "192.168.1.20"),
                requests.stream().map(r -> r.url().host()).toList(), "all three were sent");
        assertNotNull(jev.classification());
        assertNotNull(laya.classification());
        assertNotNull(ollama.classification());
    }

    @Test
    void anOutageOfAnotherProviderNeverMovesBasetensBreaker() {
        for (var i = 0; i < 3; i++) {
            withBaseten(unreachable(), () -> JevRouterClassifier.classify(PROMPT, "ts-key", 0.5, 8));
            withBaseten(unreachable(),
                    () -> JevRouterClassifier.classifyWithLaya(PROMPT, "http://192.168.1.30:8000", null, "english", 0.5, 8));
            withBaseten(unreachable(),
                    () -> JevRouterClassifier.classifyWithOllama(PROMPT, "http://192.168.1.20:11434", "tev1", 0.5, 8));
        }
        assertEquals(CircuitBreaker.State.OPEN, JevApi.breaker().state());
        assertEquals(CircuitBreaker.State.OPEN, LayaDecision.breaker().state());
        assertEquals(CircuitBreaker.State.OPEN, OllamaDecision.breaker().state());
        assertEquals(0, BasetenDecision.breaker().stats().samples());

        requests.clear();
        assertNotNull(withBaseten(answering("agentic", 0.76, "medium"), () -> classify(ELEPHANT)).classification());
        assertEquals(1, requests.size());
    }

    // --- the write rules -------------------------------------------------------------------

    @Test
    void theModelsAreAJsonArrayOfBasetensModelIds() {
        var key = BasetenDecision.MODELS_KEY;
        for (var ok : new String[] {"", "[]", "[\"" + ELEPHANT + "\"]", "[\"" + MAMMOTH + "\",\"" + ELEPHANT + "\"]"}) {
            assertNull(DecisionSettings.rejectionFor(key, ok), () -> "refused " + ok);
        }
        for (var bad : new String[] {ELEPHANT, "[\"elephant-1\"]", "[\"tev1\"]", "[\"" + ELEPHANT + "\",\"mammoth-1\"]",
                "[1]", "[\"\"]", "{}", "[\"" + ELEPHANT + "\""}) {
            assertEquals("decision.baseten.models must be a JSON array of Baseten model IDs (" + ELEPHANT + ", " + MAMMOTH
                    + "), such as [\"" + ELEPHANT + "\"].", DecisionSettings.rejectionFor(key, bad), () -> "accepted " + bad);
        }
    }

    @Test
    void anUnknownDecisionKeyIsRefusedNamingEveryKey() {
        assertEquals("decision.baseten.apikey is not a decision-provider setting; use one of decision.jev.apiKey, "
                + "decision.ollama.baseUrl, decision.ollama.models, decision.ollama.keepAlive, decision.laya.baseUrl, "
                + "decision.laya.apiKey, decision.laya.models, decision.baseten.apiKey, decision.baseten.models.",
                DecisionSettings.rejectionFor("decision.baseten.apikey", "x"));
    }

    @Test
    void theKeyMustSurviveAnAuthorizationHeader() {
        assertNull(DecisionSettings.rejectionFor(BasetenDecision.API_KEY, "bt-anything_1.2/3+4="));
        assertNull(DecisionSettings.rejectionFor(BasetenDecision.API_KEY, ""), "a blank key clears it");
        for (var bad : new String[] {"baseten key", " baseten-key", "baseten-key\n", "baseten-kéy"}) {
            assertEquals("decision.baseten.apiKey must be printable ASCII with no spaces.",
                    DecisionSettings.rejectionFor(BasetenDecision.API_KEY, bad), () -> "accepted " + bad.replace("\n", "\\n"));
        }
    }

    @Test
    void aRefusedWriteLeavesTheStoredValueAsItWas() {
        ConfigService.set(BasetenDecision.MODELS_KEY, "[\"" + ELEPHANT + "\"]");
        ConfigService.set(BasetenDecision.API_KEY, KEY);

        assertNotNull(ConfigService.setWithSideEffects(BasetenDecision.MODELS_KEY, "[\"tev1\"]"));
        assertNotNull(ConfigService.setWithSideEffects(BasetenDecision.API_KEY, "baseten key"));

        assertEquals("[\"" + ELEPHANT + "\"]", ConfigService.get(BasetenDecision.MODELS_KEY));
        assertEquals(KEY, ConfigService.get(BasetenDecision.API_KEY));
    }

    @Test
    void aStoredSelectionTicksOnlyTheIdsThatAreBasetenModels() {
        // set() skips the write rules, as a row edited by hand would.
        ConfigService.set(BasetenDecision.MODELS_KEY, "[\"" + MAMMOTH + "\",\"elephant-1\",\"" + ELEPHANT + "\"]");
        assertEquals(List.of(MAMMOTH, ELEPHANT), BasetenDecision.selectedModels());
        ConfigService.delete(BasetenDecision.MODELS_KEY);
        assertEquals(List.of(), BasetenDecision.selectedModels());
    }

    // --- what the frontend holds a copy of -------------------------------------------------

    @Test
    void theModelsEqualTheirFrontendTwin() throws IOException {
        assertEquals(List.of(new BasetenDecision.Model(ELEPHANT, "elephant-1"), new BasetenDecision.Model(MAMMOTH, "mammoth-1")),
                BasetenDecision.MODELS);

        var twin = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "frontend/utils/decision-models.ts"));
        var table = Pattern.compile("export const BASETEN_MODELS[^=]*=\\s*\\[(.*?)]", Pattern.DOTALL).matcher(twin);
        assertTrue(table.find(), "frontend/utils/decision-models.ts declares BASETEN_MODELS");
        var models = Pattern.compile("id:\\s*'([^']+)',\\s*name:\\s*'([^']+)'").matcher(table.group(1)).results()
                .map(m -> new BasetenDecision.Model(m.group(1), m.group(2))).toList();
        assertEquals(BasetenDecision.MODELS, models);
    }

    private static List<String> routerWarnings(List<EventLogger.Captured> logged) {
        return logged.stream().filter(e -> e.level().equals("WARN") && e.category().equals("router"))
                .map(EventLogger.Captured::message).toList();
    }
}
