import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmResilience;
import llm.ProviderRegistry;
import llm.routing.RouterClassifier;
import llm.routing.RouterPolicy;
import llm.routing.RouterPolicy.Candidate;
import llm.routing.TaskClass;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import utils.CircuitBreakers;
import utils.HttpFactories;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * JCLAW-1222: the optional classifier model. What it is asked, what it may answer, and what happens
 * when it cannot — the local rules, every time, rather than a failed turn.
 */
class RouterClassifierTest extends UnitTest {

    private static final String PROMPT = "Run daily briefing skill";

    private String provider;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void seedProvider() {
        provider = "rc-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigService.set("provider." + provider + ".baseUrl", "http://router-classifier.invalid/v1");
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models", "[{\"id\":\"tiny\",\"name\":\"Tiny\",\"contextWindow\":8000}]");
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the classifier provider must register");
        requests.clear();
    }

    @AfterEach
    void cleanUp() {
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
        for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
            ConfigService.delete("provider." + provider + suffix);
        }
    }

    private RouterPolicy withClassifier() {
        return new RouterPolicy(Map.of(TaskClass.CHAT, List.of(new Candidate(provider, "tiny"))),
                0.75, 0.95, new Candidate(provider, "tiny"), 8);
    }

    private RouterPolicy withoutClassifier() {
        return new RouterPolicy(Map.of(TaskClass.CHAT, List.of(new Candidate(provider, "tiny"))), 0.75, 0.95);
    }

    @Test
    void theClassifierRequestCarriesOnlyItsOwnInstructionAndThePrompt() {
        HttpFactories.runWith(canned(200, completion("reasoning")),
                () -> RouterClassifier.classify("Prove that P != NP", null, 0, withClassifier()));

        assertEquals(1, requests.size(), "exactly one classification call");
        var body = JsonParser.parseString(requests.getFirst()).getAsJsonObject();
        var messages = body.getAsJsonArray("messages");
        assertEquals(2, messages.size(), "only the instruction and the prompt: " + messages);

        var system = messages.get(0).getAsJsonObject();
        assertEquals("system", system.get("role").getAsString());
        assertTrue(system.get("content").getAsString().startsWith("You route one user message"),
                "the system message is the classifier's own, not the agent's assembled prompt");

        var user = messages.get(1).getAsJsonObject();
        assertEquals("user", user.get("role").getAsString());
        assertEquals("Prove that P != NP", user.get("content").getAsString(),
                "the prompt goes verbatim — no history, no memories, no standing orders around it");

        assertEquals("tiny", body.get("model").getAsString());
        assertFalse(body.has("tools") && !body.getAsJsonArray("tools").isEmpty(),
                "a classification needs no tool catalogue: " + body);
    }

    @Test
    void theModelsAnswerDecidesTheClass() {
        var c = HttpFactories.callWith(canned(200, completion("reasoning")),
                () -> RouterClassifier.classify(PROMPT, null, 0, withClassifier()));
        assertEquals(TaskClass.REASONING, c.taskClass(),
                "the model's answer wins over the keyword rules, which would have said agentic");
        assertEquals(List.of("classified by " + provider + "/tiny"), c.signals());
    }

    @Test
    void anAnswerWrappedInPunctuationOrJsonStillReads() {
        for (var answer : List.of("Coding.", "\"coding\"", "{\"class\": \"coding\"}", "I would say coding")) {
            var c = HttpFactories.callWith(canned(200, completion(answer)),
                    () -> RouterClassifier.classify(PROMPT, null, 0, withClassifier()));
            assertEquals(TaskClass.CODING, c.taskClass(), "unread answer: " + answer);
        }
    }

    @Test
    void anAnswerThatNamesNoClassFallsBackToTheRules() {
        var c = HttpFactories.callWith(canned(200, completion("banana")),
                () -> RouterClassifier.classify(PROMPT, null, 0, withClassifier()));
        assertEquals(TaskClass.AGENTIC, c.taskClass(), "the rules read the prompt instead");
        assertEquals(List.of("asks to run"), c.signals(), "and the route says the rules decided it");
    }

    @Test
    void aFailedClassifierCallFallsBackToTheRules() {
        var c = HttpFactories.callWith(canned(500, "{\"error\":{\"message\":\"boom\"}}"),
                () -> RouterClassifier.classify(PROMPT, null, 0, withClassifier()));
        assertEquals(TaskClass.AGENTIC, c.taskClass());
        assertFalse(c.signals().getFirst().startsWith("classified by"));
    }

    @Test
    void anAcknowledgementIsSettledWithoutCallingTheModel() {
        var c = HttpFactories.callWith(canned(200, completion("coding")),
                () -> RouterClassifier.classify("yes please", TaskClass.REASONING, 0, withClassifier()));
        assertEquals(TaskClass.REASONING, c.taskClass());
        assertTrue(requests.isEmpty(), "a bare acknowledgement must not cost a model call");
    }

    @Test
    void withNoClassifierConfiguredTheRulesDecideAndNothingIsCalled() {
        var c = HttpFactories.callWith(canned(200, completion("coding")),
                () -> RouterClassifier.classify(PROMPT, null, 0, withoutClassifier()));
        assertEquals(TaskClass.AGENTIC, c.taskClass());
        assertTrue(requests.isEmpty());
    }

    @Test
    void averyLongPromptIsTruncatedBeforeItIsSent() {
        var huge = "Summarize this: " + "x".repeat(RouterClassifier.MAX_PROMPT_CHARS * 2);
        HttpFactories.runWith(canned(200, completion("summarize")),
                () -> RouterClassifier.classify(huge, null, 0, withClassifier()));
        var body = JsonParser.parseString(requests.getFirst()).getAsJsonObject();
        var sent = body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertEquals(RouterClassifier.MAX_PROMPT_CHARS, sent.length());
    }

    private static String completion(String content) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        var choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", message);
        choice.addProperty("finish_reason", "stop");
        var body = new JsonObject();
        body.addProperty("id", "cls");
        body.addProperty("model", "tiny");
        var choices = new com.google.gson.JsonArray();
        choices.add(choice);
        body.add("choices", choices);
        return body.toString();
    }

    /** Records each outgoing request body, then answers with the canned response. */
    private OkHttpClient canned(int code, String body) {
        Interceptor canned = chain -> {
            var request = chain.request();
            var sink = new Buffer();
            if (request.body() != null) request.body().writeTo(sink);
            requests.add(sink.readUtf8());
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(ResponseBody.create(body, null))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }
}
