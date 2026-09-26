import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.routing.JevRouterClassifier;
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
import services.decision.JevException;
import tools.jev.JevActionSpace;
import tools.jev.JevClient;
import utils.CircuitBreaker;
import utils.HttpFactories;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Jev decision request (JCLAW-1274): answer validation, one request per decision, only the
 * chosen head read, and the retry policy — a decision has no side effects, so dropped connections
 * are retried as well as 429/503/529, while any other status ends the run with nothing executed.
 */
class JevClientTest extends UnitTest {

    private static final MediaType JSON = MediaType.get("application/json");

    private final List<Request> requests = new ArrayList<>();
    private final List<JsonObject> bodies = new ArrayList<>();

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

    /** jev-ultrafast's tests/test_agent.py page: a text field (typed or clicked), a button, and WAIT. */
    private static JsonObject page() {
        return JsonParser.parseString("""
                {"url":"https://example.test/","title":"Search","text":"Search","actions":[
                  {"id":"e1","kind":"fill","label":"Search","role":"textbox","value":"","node":10},
                  {"id":"e2","kind":"click","label":"Open Search","role":"textbox","value":"","node":10},
                  {"id":"e3","kind":"click","label":"Go","role":"button","value":"","node":20},
                  {"id":"wait","kind":"wait","label":"Wait for the page to update"}]}""").getAsJsonObject();
    }

    private static JsonObject choice(Set<String> ids, String selected) {
        var probabilities = new JsonObject();
        ids.forEach(id -> probabilities.addProperty(id, id.equals(selected) ? 1.0 : 0.0));
        var answer = new JsonObject();
        answer.addProperty("choice", selected);
        answer.addProperty("confidence", 1.0);
        answer.add("probabilities", probabilities);
        return answer;
    }

    private static Set<String> criteria(JsonObject body, String head) {
        return body.getAsJsonObject("questions").getAsJsonObject(head).getAsJsonObject("criteria").keySet();
    }

    private static Response reply(Interceptor.Chain chain, int code, String json) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("canned").body(ResponseBody.create(json, JSON)).build();
    }

    /** Answer each attempt with {@code answer}, which sees the attempt number and the request body. */
    private <T> T withJev(Answer answer, Supplier<T> body) {
        Interceptor jev = chain -> {
            var buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            var request = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
            requests.add(chain.request());
            bodies.add(request);
            return answer.reply(chain, requests.size(), request);
        };
        return HttpFactories.callWith(new OkHttpClient.Builder().addInterceptor(jev).build(), body);
    }

    @FunctionalInterface
    private interface Answer {
        Response reply(Interceptor.Chain chain, int attempt, JsonObject body) throws IOException;
    }

    private static Answer answering(Function<JsonObject, JsonObject> answers) {
        return (chain, _, body) -> {
            var result = new JsonObject();
            result.addProperty("model", "test");
            result.add("answers", answers.apply(body));
            return reply(chain, 200, result.toString());
        };
    }

    private static Answer typeIntoSearch() {
        return answering(body -> {
            var answers = new JsonObject();
            answers.add("operation", choice(criteria(body, "operation"), "TYPE_TEXT"));
            answers.add("type_text_target", choice(criteria(body, "type_text_target"), "1"));
            answers.add("click_target", JsonParser.parseString("{\"choice\":\"invented\"}"));
            return answers;
        });
    }

    private JevClient.Decision decide() {
        return JevClient.decide("ts-test-key", page(), "Find a book", new com.google.gson.JsonArray());
    }

    // --- validation ------------------------------------------------------------------------

    @Test
    void aWellFormedAnswerIsAccepted() {
        var answer = JsonParser.parseString(
                "{\"choice\":\"a\",\"confidence\":1.0,\"probabilities\":{\"a\":1.0,\"b\":0.0}}");
        assertEquals("a", JevApi.validateChoice(answer, Set.of("a", "b")).get("choice").getAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"choice\":\"invented\",\"confidence\":1.0,\"probabilities\":{\"a\":1.0,\"b\":0.0}}",
            "{\"choice\":\"a\",\"confidence\":1.0,\"probabilities\":{\"a\":NaN,\"b\":0.0}}",
            "{\"choice\":\"a\",\"confidence\":1.0,\"probabilities\":{\"a\":1.0}}",
            "{\"choice\":\"a\",\"confidence\":1.0,\"probabilities\":{\"a\":1.0,\"b\":-1}}",
            "{\"choice\":\"b\",\"confidence\":1.0,\"probabilities\":{\"a\":1.0,\"b\":0.0}}",
            "{\"choice\":\"a\",\"confidence\":5,\"probabilities\":{\"a\":1.0,\"b\":0.0}}",
            "{\"choice\":\"a\",\"probabilities\":{\"a\":1.0,\"b\":0.0}}",
            "{\"choice\":\"a\",\"confidence\":1.0,\"probabilities\":{\"a\":0.5,\"b\":0.1}}",
            "{\"choice\":\"a\",\"confidence\":1.0,\"probabilities\":{\"a\":\"1\",\"b\":0.0}}",
            "{\"choice\":1,\"confidence\":1.0,\"probabilities\":{\"a\":1.0,\"b\":0.0}}",
            "[\"a\"]"
    })
    void aMalformedAnswerIsRefused(String raw) {
        var e = assertThrows(JevException.class,
                () -> JevApi.validateChoice(JsonParser.parseString(raw), Set.of("a", "b")));
        assertEquals("Invalid Jev response", e.getMessage(), raw);
    }

    @Test
    void everyElementGetsOneIndexAndEachOperationItsOwnTargets() {
        var space = JevActionSpace.of(page().getAsJsonArray("actions"));
        assertEquals(2, space.elements().size());
        assertEquals("[\"TYPE_TEXT\",\"CLICK\"]",
                space.elements().get(0).getAsJsonObject().get("operations").toString());
        assertEquals("e1", space.targets().get("TYPE_TEXT").get("1").get("id").getAsString());
        assertEquals("e2", space.targets().get("CLICK").get("1").get("id").getAsString());
        assertEquals("e3", space.targets().get("CLICK").get("2").get("id").getAsString());
        assertEquals(List.of("TYPE_TEXT", "CLICK", "WAIT", "DONE", "BLOCKED"), List.copyOf(space.operations().keySet()));
    }

    @Test
    void onlyADropdownOptionLabelIsCutAtTheArrow() {
        var space = JevActionSpace.of(JsonParser.parseString("""
                [{"id":"e1","kind":"click","label":"Continue → Checkout","role":"button","value":"","node":1},
                 {"id":"e2","kind":"select","label":"Size → Large","role":"combobox","value":"l",
                  "current_value":"Small","node":2}]""").getAsJsonArray());
        assertEquals("Continue → Checkout", space.elements().get(0).getAsJsonObject().get("label").getAsString());
        assertEquals("Size", space.elements().get(1).getAsJsonObject().get("label").getAsString());
    }

    // --- one request, one head -------------------------------------------------------------

    @Test
    void oneRequestPerDecisionAndOnlyTheChosenHeadIsRead() {
        var decision = withJev(typeIntoSearch(), this::decide);

        assertEquals(1, requests.size());
        assertEquals("TYPE_TEXT", decision.operation());
        assertEquals("e1", decision.action().get("id").getAsString());
        assertEquals(Set.of("operation", "click_target", "type_text_target"),
                bodies.getFirst().getAsJsonObject("questions").keySet());
        var request = requests.getFirst();
        assertEquals("https://api.typesafe.ai/v1/systemone", request.url().toString());
        assertEquals("Bearer ts-test-key", request.header("Authorization"));
        assertEquals("jev-latest", bodies.getFirst().get("model").getAsString());
    }

    @Test
    void aClickCannotConsumeATextTarget() {
        var answer = answering(body -> {
            var answers = new JsonObject();
            answers.add("operation", choice(criteria(body, "operation"), "CLICK"));
            answers.add("type_text_target", choice(criteria(body, "type_text_target"), "1"));
            answers.add("click_target", choice(Set.of("1", "2", "999"), "999"));
            return answers;
        });
        var e = assertThrows(JevException.class, () -> withJev(answer, this::decide));
        assertEquals("Invalid Jev response; no action executed", e.getMessage());
    }

    @Test
    void doneCarriesNoAction() {
        var decision = withJev(answering(body -> {
            var answers = new JsonObject();
            answers.add("operation", choice(criteria(body, "operation"), "DONE"));
            return answers;
        }), this::decide);
        assertEquals("DONE", decision.operation());
        assertNull(decision.action());
    }

    // --- retries ---------------------------------------------------------------------------

    @Test
    void aRateLimitIsRetried() {
        var ok = typeIntoSearch();
        var decision = withJev((chain, attempt, body) -> attempt == 1
                ? reply(chain, 429, "{\"error\":\"rate limited\"}") : ok.reply(chain, attempt, body), this::decide);
        assertEquals(2, requests.size());
        assertEquals("e1", decision.action().get("id").getAsString());
        assertEquals(0, JevApi.breaker().stats().failures(), "a retried attempt is not an outcome of its own");
    }

    @Test
    void aDroppedConnectionIsRetried() {
        var ok = typeIntoSearch();
        var decision = withJev((chain, attempt, body) -> {
            if (attempt == 1) throw new IOException("connection reset");
            return ok.reply(chain, attempt, body);
        }, this::decide);
        assertEquals(2, requests.size());
        assertEquals("e1", decision.action().get("id").getAsString());
    }

    @Test
    void threeDroppedConnectionsMeanUnreachable() {
        long started = System.nanoTime();
        var e = assertThrows(JevException.class, () -> withJev((_, _, _) -> {
            throw new IOException("connection reset");
        }, this::decide));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals("Jev unreachable; no action executed", e.getMessage());
        assertEquals(3, requests.size());
        assertOneFailure();
        assertTrue(elapsedMs >= 1_500, "backed off 0.5 s then 1 s between attempts, took " + elapsedMs + " ms");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void aRefusedKeyIsNotRetriedAndSendsTheAgentToTheOperator(int status) {
        var e = assertThrows(JevException.class,
                () -> withJev((chain, _, _) -> reply(chain, status, "{\"error\":\"bad key\"}"), this::decide));
        assertEquals("TypeSafe refused the Jev API key (HTTP " + status + "); the operator must update it in "
                + "Settings → Decision Providers; no action executed", e.getMessage());
        assertEquals(1, requests.size());
        assertEquals(0, JevApi.breaker().stats().samples(), "a refused key is not counted against TypeSafe");
    }

    @Test
    void aServerErrorThatPersistsEndsAfterThreeAttempts() {
        var e = assertThrows(JevException.class,
                () -> withJev((chain, _, _) -> reply(chain, 503, "{}"), this::decide));
        assertEquals("Jev returned HTTP 503; no action executed", e.getMessage());
        assertEquals(3, requests.size());
        assertOneFailure();
    }

    @Test
    void aBodyThatIsNotJsonIsInvalid() {
        var e = assertThrows(JevException.class,
                () -> withJev((chain, _, _) -> reply(chain, 200, "<html>gateway</html>"), this::decide));
        assertEquals("Invalid Jev response; no action executed", e.getMessage());
        assertEquals(0, JevApi.breaker().stats().samples(), "a malformed answer is not counted");
    }

    // --- the breaker (JCLAW-1302) ----------------------------------------------------------

    /** A decision's whole retry loop is one outcome for the breaker, as a chat call's is. */
    private static void assertOneFailure() {
        var stats = JevApi.breaker().stats();
        assertEquals(1, stats.samples(), "three attempts, one outcome");
        assertEquals(1, stats.failures());
    }

    @Test
    void threeFailedDecisionsOpenTheBreakerAndTheRouterSendsNothingEither() {
        for (int i = 0; i < 3; i++) {
            assertThrows(JevException.class, () -> withJev((chain, _, _) -> reply(chain, 500, "{}"), this::decide));
        }
        assertEquals(3, requests.size(), "500 is not retried");
        assertEquals(CircuitBreaker.State.OPEN, JevApi.breaker().state());

        var e = assertThrows(JevException.class, () -> withJev(typeIntoSearch(), this::decide));
        assertEquals("JEV's circuit breaker is open: not calling TypeSafe until it recovers; no action executed",
                e.getMessage());
        var verdict = withJev(typeIntoSearch(), () -> JevRouterClassifier.classify("Explain this", "ts-test-key", 0.5, 8));
        assertEquals("JEV breaker open", verdict.reason());
        assertEquals(3, requests.size(), "neither consumer sends while the breaker is open");
    }

    @Test
    void anOutageTheRouterFoundStopsTheBrowserSending() {
        for (int i = 0; i < 3; i++) {
            withJev((chain, _, _) -> reply(chain, 503, "{}"),
                    () -> JevRouterClassifier.classify("Explain this", "ts-test-key", 0.5, 8));
        }
        assertThrows(JevException.class, () -> withJev(typeIntoSearch(), this::decide));
        assertEquals(3, requests.size(), "the browser shares the router's breaker");
    }

    @Test
    void anIsolatedBreakerSendsNothingAndSaysTheOperatorIsolatedIt() {
        JevApi.breaker().trip();
        var e = assertThrows(JevException.class, () -> withJev(typeIntoSearch(), this::decide));
        assertTrue(requests.isEmpty());
        assertEquals("JEV was isolated by the operator: not calling TypeSafe until the cooldown ends or it is "
                + "restored; no action executed", e.getMessage());
    }

    @Test
    void theBreakerKeepsTheNameThePanelAndTheDashboardLookUp() {
        assertEquals("decision:jev", JevApi.BREAKER);
    }
}
