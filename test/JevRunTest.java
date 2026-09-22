import agents.ToolContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import tools.PlaywrightBrowserTool;
import tools.jev.JevException;
import tools.jev.JevPage;
import tools.jev.JevRun;
import utils.CircuitBreakers;
import utils.HttpFactories;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Jev run end to end in real Chromium (JCLAW-1274), on an inline {@code data:} form. Jev is
 * played by an OkHttp interceptor that decides only from the request body it is sent, so the run
 * succeeds only if the snapshot put the labels, values and checked state on the wire. Gated on
 * {@code JCLAW_PLAYWRIGHT_TEST} like the other live browser tests; the text-helper contract
 * and the text helper's model resolution need no browser and always run. {@code JevLoopTest}
 * covers the loop itself without a browser.
 */
class JevRunTest extends UnitTest {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String GOAL = "Use the destination search: type Lisbon, submit it, set the category "
            + "to Design, tick Free cancellation, then open Casa Flora.";

    private static final String FIXTURE = """
            <!doctype html><title>Stays</title>
            <style>body{margin:24px;font:16px sans-serif}</style>
            <form id="search">
              <label for="dest">Destination</label> <input id="dest" type="text">
              <button type="submit">Find stays</button>
            </form>
            <p id="summary">No search yet</p>
            <div id="filters" hidden>
              <label for="cat">Stay category</label>
              <select id="cat"><option value="">Any</option><option value="design">Design</option>
                <option value="budget">Budget</option></select>
              <label><input id="free" type="checkbox"> Free cancellation</label>
              <a id="casa" href="#casa-flora">View Casa Flora</a>
            </div>
            <script>
              window.searches = 0;
              document.getElementById('search').addEventListener('submit', e => {
                e.preventDefault();
                window.searches++;
                document.getElementById('summary').textContent = 'Stays in ' + document.getElementById('dest').value;
                document.getElementById('filters').hidden = false;
              });
              document.getElementById('casa').addEventListener('click', e => {
                e.preventDefault();
                document.title = 'Casa Flora';
              });
            </script>""";

    private static final String FREEZE = """
            <!doctype html><title>Frozen</title>
            <button id="freeze">Freeze</button>
            <script>
              document.getElementById('freeze').addEventListener('click', () => setTimeout(() => { for (;;) {} }, 20));
            </script>""";

    private static Playwright playwright;
    private static Browser browser;
    private Page page;
    private JevPage jev;
    private final AtomicInteger jevCalls = new AtomicInteger();
    private final List<JsonObject> typedFor = new ArrayList<>();
    private final List<String> jevBodies = new CopyOnWriteArrayList<>();

    private static boolean isPlaywrightTestEnabled() {
        var v = System.getenv("JCLAW_PLAYWRIGHT_TEST");
        return v != null && !v.isBlank() && !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    @BeforeAll
    static void launch() {
        if (!isPlaywrightTestEnabled()) return;
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void openFixture() {
        jevCalls.set(0);
        typedFor.clear();
        jevBodies.clear();
        if (!isPlaywrightTestEnabled()) return;
        page = browser.newPage();
        load(page, FIXTURE);
        jev = new JevPage(page.context().newCDPSession(page), JevPage.CALL_LIMIT, () -> {});
    }

    private static void load(Page page, String html) {
        page.navigate("data:text/html;charset=utf-8,"
                + URLEncoder.encode(html, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    @AfterEach
    void closeFixture() {
        if (page != null) page.close();
    }

    private static void requireBrowser() {
        Assumptions.assumeTrue(isPlaywrightTestEnabled(), "JCLAW_PLAYWRIGHT_TEST is not set");
    }

    // --- the stand-in for Jev ----------------------------------------------------------------

    /** The index of the element Jev was shown under {@code label}. */
    private static String element(JsonArray elements, String label) {
        for (var e : elements) {
            if (e.getAsJsonObject().get("label").getAsString().equals(label)) {
                return e.getAsJsonObject().get("index").getAsString();
            }
        }
        throw new AssertionError("Jev was not shown '" + label + "': " + elements);
    }

    private static JsonObject field(JsonArray elements, String label) {
        var index = Integer.parseInt(element(elements, label));
        return elements.get(index - 1).getAsJsonObject();
    }

    /** Step through the goal from what the request says the page shows, like a well-behaved Jev. */
    private static String[] next(JsonObject state) {
        var title = state.getAsJsonObject("page").get("title").getAsString();
        var text = state.getAsJsonObject("page").get("text").getAsString();
        var elements = state.getAsJsonArray("elements");
        if (title.equals("Casa Flora")) return new String[] {"DONE", null};
        if (!field(elements, "Destination").get("value").getAsString().equals("Lisbon")) {
            return new String[] {"TYPE_TEXT", element(elements, "Destination")};
        }
        if (!text.contains("Stays in")) return new String[] {"CLICK", element(elements, "Find stays")};
        var category = field(elements, "Stay category");
        if (!category.get("value").getAsString().equals("Design")) {
            for (var option : category.getAsJsonArray("options")) {
                if (option.getAsJsonObject().get("label").getAsString().endsWith("→ Design")) {
                    return new String[] {"SELECT", option.getAsJsonObject().get("index").getAsString()};
                }
            }
        }
        if (!field(elements, "Free cancellation").get("checked").getAsString().equals("true")) {
            return new String[] {"CLICK", element(elements, "Free cancellation")};
        }
        return new String[] {"CLICK", element(elements, "View Casa Flora")};
    }

    private static JsonObject peaked(JsonObject question, String choice) {
        var probabilities = new JsonObject();
        question.getAsJsonObject("criteria").keySet()
                .forEach(k -> probabilities.addProperty(k, k.equals(choice) ? 1.0 : 0.0));
        var answer = new JsonObject();
        answer.addProperty("choice", choice);
        answer.addProperty("confidence", 0.97);
        answer.add("probabilities", probabilities);
        return answer;
    }

    private Interceptor fakeJev(java.util.function.Function<JsonObject, String[]> policy) {
        return chain -> {
            jevCalls.incrementAndGet();
            var buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            var raw = buffer.readUtf8();
            jevBodies.add(raw);
            var body = JsonParser.parseString(raw).getAsJsonObject();
            var decision = policy.apply(body.getAsJsonObject("state"));
            var questions = body.getAsJsonObject("questions");
            var answers = new JsonObject();
            answers.add("operation", peaked(questions.getAsJsonObject("operation"), decision[0]));
            for (var head : questions.keySet()) {
                if (head.equals("operation")) continue;
                // A malformed answer on every head but the chosen one: it must never be read.
                answers.add(head, head.equals(decision[0].toLowerCase() + "_target")
                        ? peaked(questions.getAsJsonObject(head), decision[1])
                        : JsonParser.parseString("{\"choice\":\"999\"}"));
            }
            var result = new JsonObject();
            result.addProperty("model", "stand-in");
            result.add("answers", answers);
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(result.toString(), JSON)).build();
        };
    }

    private JevRun.Outcome run(java.util.function.Function<JsonObject, String[]> policy) {
        return run(policy, jev);
    }

    private JevRun.Outcome run(java.util.function.Function<JsonObject, String[]> policy, JevPage on) {
        JevRun.TextHelper agentModel = context -> {
            typedFor.add(context);
            return "Lisbon";
        };
        return HttpFactories.callWith(new OkHttpClient.Builder().addInterceptor(fakeJev(policy)).build(),
                () -> JevRun.run(on, "ts-test-key", GOAL, agentModel, "jev-run-test"));
    }

    // --- runs --------------------------------------------------------------------------------

    @Test
    void aRunCarriesOutTheGoalWithOneJevCallPerDecision() {
        requireBrowser();
        var outcome = run(JevRunTest::next);

        assertEquals("done", outcome.status(), outcome.format());
        assertEquals(6, outcome.decisions());
        assertEquals(outcome.decisions(), jevCalls.get(), "one request per decision");
        assertEquals(5, outcome.steps().size());
        assertEquals(1, typedFor.size(), "the agent's model writes the one text field");
        assertEquals("Destination", typedFor.getFirst().getAsJsonObject("field").get("label").getAsString());
        assertEquals(GOAL, typedFor.getFirst().get("goal").getAsString());

        assertEquals("Lisbon", page.inputValue("#dest"));
        assertEquals(1, ((Number) page.evaluate("window.searches")).intValue());
        assertEquals("design", page.inputValue("#cat"));
        assertTrue(page.isChecked("#free"));
        assertEquals("Casa Flora", page.title());

        var result = outcome.format();
        assertTrue(result.startsWith("Jev run: done after 6 decisions."), result);
        assertTrue(result.contains("Final page: Casa Flora — data:text/html"), result);
        assertTrue(result.contains("1. TYPE_TEXT Destination ← \"Lisbon\""), result);
        assertTrue(result.contains("2. CLICK Find stays"), result);
        assertTrue(result.contains("3. SELECT Stay category → Design"), result);
        assertTrue(result.contains("Jev's judgement"), result);
        assertTrue(result.contains("Visible text:\n"), result);
        assertTrue(result.contains("Stays in Lisbon"), result);
    }

    @Test
    void aPrefilledFieldIsReplacedNotAppendedTo() {
        requireBrowser();
        page.evaluate("document.getElementById('dest').value = 'Paris'");

        var outcome = run(JevRunTest::next);

        assertEquals("done", outcome.status(), outcome.format());
        assertEquals("Lisbon", page.inputValue("#dest"));
        assertEquals("Paris", typedFor.getFirst().getAsJsonObject("field").get("value").getAsString(),
                "the agent's model saw what the field held");
    }

    @Test
    void aPasswordFieldNeverReachesJev() {
        requireBrowser();
        // Added by script: in the fixture's markup it would ride the data: URL, which Jev is sent.
        page.evaluate("(() => { const pw = document.createElement('input'); pw.type = 'password'; "
                + "pw.setAttribute('aria-label', 'Account password'); pw.value = 'hunter2-secret'; "
                + "document.querySelector('#search button').before(pw); })()");

        var outcome = run(JevRunTest::next);

        assertEquals("done", outcome.status(), outcome.format());
        assertEquals("hunter2-secret", page.inputValue("input[type=password]"), "the password was on the page");
        assertTrue(jevBodies.getFirst().contains("\"label\":\"Destination\""), "other fields' labels are sent");
        for (var body : jevBodies) {
            assertFalse(body.contains("hunter2-secret"), "the password's value was sent to Jev");
            assertFalse(body.contains("Account password"), "the password's label was sent to Jev");
        }
    }

    @Test
    void aFrozenPageEndsTheRunWithinTheBoundAndClosesTheSession() throws Exception {
        requireBrowser();
        var driver = PlaywrightBrowserTool.startDriver();
        assertNotNull(driver.process(), "the driver process must be identifiable to bound a frozen page");
        try {
            var frozen = driver.playwright().chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
                    .newPage();
            load(frozen, FREEZE);
            var chromium = driver.process().descendants().toList();
            assertFalse(chromium.isEmpty(), "Chromium runs under the driver");
            var bounded = new JevPage(frozen.context().newCDPSession(frozen), Duration.ofSeconds(2),
                    () -> PlaywrightBrowserTool.killDriver(driver.process()));

            long started = System.nanoTime();
            var outcome = run(state -> new String[] {"CLICK", element(state.getAsJsonArray("elements"), "Freeze")},
                    bounded);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertTrue(bounded.frozen());
            assertTrue(elapsedMs < 10_000, "ended within the bound, took " + elapsedMs + " ms");
            // The loop starts 20 ms after the click, so the read that hangs may be the first or a later one.
            assertTrue(outcome.format().startsWith("Error: the page stopped responding; the browser session was "
                    + "closed\n\nActions executed before the error:\n1. CLICK Freeze"), outcome.format());
            driver.process().onExit().get(10, TimeUnit.SECONDS);
            for (var process : chromium) process.onExit().get(10, TimeUnit.SECONDS);
        } finally {
            try {
                driver.playwright().close();
            } catch (RuntimeException _) {
                // The driver is already gone.
            }
        }
    }

    @Test
    void aDecisionOnAChangedPageSendsNoInput() {
        requireBrowser();
        var state = jev.observe();
        var findStays = action(state, "Find stays");
        page.evaluate("document.getElementById('dest').value = 'Paris'");

        var e = assertThrows(JevPage.StalePage.class, () -> jev.act(findStays, state, null));

        assertTrue(e.getMessage().contains("Page changed"), e.getMessage());
        assertEquals(0, ((Number) page.evaluate("window.searches")).intValue());
    }

    @Test
    void aCoveredTargetIsNotClicked() {
        requireBrowser();
        var state = jev.observe();
        var findStays = action(state, "Find stays");
        page.evaluate("(() => { const d = document.createElement('div'); "
                + "d.style.cssText = 'position:fixed;inset:0;z-index:99'; document.body.append(d); })()");

        var e = assertThrows(JevPage.StalePage.class, () -> jev.act(findStays, state, null));

        assertTrue(e.getMessage().contains("covered"), e.getMessage());
        assertEquals(0, ((Number) page.evaluate("window.searches")).intValue());
    }

    @Test
    void anInterruptedSelectEndsTheRunWithoutARetry() {
        requireBrowser();
        page.evaluate("Object.defineProperty(document.getElementById('cat'), 'value', {"
                + "get() { return ''; },"
                + "set(v) { window.selectAttempts = (window.selectAttempts || 0) + 1; throw new Error('interrupted'); }})");

        var outcome = run(JevRunTest::next);

        assertEquals("error", outcome.status(), outcome.format());
        assertEquals(3, outcome.decisions(), "no decision after the failed select");
        assertEquals(3, jevCalls.get());
        assertEquals(1, ((Number) page.evaluate("window.selectAttempts")).intValue(), "the select is not repeated");
        var result = outcome.format();
        assertTrue(result.startsWith("Error: Dropdown execution was interrupted; inspect before retrying"), result);
        assertTrue(result.contains("Actions executed before the error:\n1. TYPE_TEXT Destination"), result);
    }

    @Test
    void threeActionsThatChangeNothingEndTheRunBlocked() {
        requireBrowser();
        var outcome = run(state -> new String[] {"CLICK", element(state.getAsJsonArray("elements"), "Destination")});

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("the last 3 actions changed nothing", outcome.reason());
        assertEquals(3, outcome.steps().size());
        assertTrue(outcome.format().contains("(page unchanged)"), outcome.format());
    }

    @Test
    void sixtyActionsEndTheRunBlocked() {
        requireBrowser();
        var outcome = run(_ -> new String[] {"WAIT", null});

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("used all 60 actions", outcome.reason());
        assertEquals(60, outcome.steps().size());
        assertEquals(61, outcome.decisions());
    }

    @Test
    void fiveStaleDecisionsInARowEndTheRunBlocked() {
        requireBrowser();
        // Every read of the title differs, so each DONE lands on a page that has moved and is decided again.
        page.evaluate("(() => { let reads = 0; "
                + "Object.defineProperty(document, 'title', {get: () => 'read ' + (++reads)}); })()");

        var outcome = run(_ -> new String[] {"DONE", null});

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("the page keeps changing", outcome.reason());
        assertEquals(5, jevCalls.get());
        assertTrue(outcome.steps().isEmpty(), outcome.format());
    }

    @Test
    void aFailedTextHelperStopsTheRunWithNothingTyped() {
        requireBrowser();
        JevRun.TextHelper refuses = _ -> JevRun.parseText("I cannot tell what to type.");
        var outcome = HttpFactories.callWith(new OkHttpClient.Builder().addInterceptor(fakeJev(JevRunTest::next)).build(),
                () -> JevRun.run(jev, "ts-test-key", GOAL, refuses, "jev-run-test"));

        assertEquals("error", outcome.status());
        assertTrue(outcome.format().startsWith("Error: Text helper returned no valid field value; nothing typed"),
                outcome.format());
        assertEquals("", page.inputValue("#dest"));
        assertEquals(1, jevCalls.get());
    }

    private static JsonObject action(JsonObject state, String label) {
        for (var a : state.getAsJsonArray("actions")) {
            if (a.getAsJsonObject().get("label").getAsString().equals(label)) return a.getAsJsonObject();
        }
        throw new AssertionError("no action labelled " + label);
    }

    // --- the text helper's model (no browser) -------------------------------------------------

    /** Seeds a provider with two models, an agent on the first, and a conversation overridden to the second. */
    private static final class ModelFixture implements AutoCloseable {
        final String provider = "jev-" + UUID.randomUUID().toString().substring(0, 8);
        final Agent agent;
        final Conversation conversation;

        ModelFixture() {
            ConfigService.set("provider." + provider + ".baseUrl", "http://jev-text-helper.invalid/v1");
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models", "[{\"id\":\"own\",\"name\":\"Own\","
                    + "\"contextWindow\":8000},{\"id\":\"picked\",\"name\":\"Picked\",\"contextWindow\":8000}]");
            for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
            assertNotNull(ProviderRegistry.get(provider), "test precondition: the provider must register");
            agent = AgentService.create(provider + "-agent", provider, "own");
            conversation = ConversationService.create(agent, "web", "jev-" + provider);
            conversation.modelProviderOverride = provider;
            conversation.modelIdOverride = "picked";
            conversation.save();
        }

        @Override
        public void close() {
            AgentService.delete(agent);
            CircuitBreakers.remove(LlmResilience.breakerName(provider));
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
        }
    }

    /** Answers every chat request with {@code content}, recording the model each one named. */
    private static OkHttpClient llm(String content, List<String> models) {
        Interceptor canned = chain -> {
            var buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            models.add(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject().get("model").getAsString());
            var message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", content);
            var choice = new JsonObject();
            choice.addProperty("index", 0);
            choice.add("message", message);
            choice.addProperty("finish_reason", "stop");
            var choices = new JsonArray();
            choices.add(choice);
            var completion = new JsonObject();
            completion.addProperty("id", "jev-text");
            completion.add("choices", choices);
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(completion.toString(), JSON)).build();
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    @Test
    void theTextComesFromTheConversationsModelAndOtherwiseTheAgentsOwn() {
        try (var fixture = new ModelFixture()) {
            var models = new ArrayList<String>();
            var helper = JevRun.agentModel(fixture.agent);
            var context = new JsonObject();
            context.addProperty("goal", GOAL);

            var onATaskFire = HttpFactories.callWith(llm("{\"text\": \"Lisbon\"}", models), () -> helper.text(context));
            var inTheChat = HttpFactories.callWith(llm("{\"text\": \"Porto\"}", models),
                    () -> ToolContext.withConversation(fixture.conversation.id, () -> helper.text(context)));

            assertEquals("Lisbon", onATaskFire);
            assertEquals("Porto", inTheChat);
            assertEquals(List.of("own", "picked"), models, "no conversation: the agent's model; else the override");
        }
    }

    @Test
    void aReasoningPrefixedReplyIsTypedButTextAfterTheObjectIsNot() {
        try (var fixture = new ModelFixture()) {
            var helper = JevRun.agentModel(fixture.agent);
            // The glm-5.3-flash shape JCLAW-1275 was filed for: leaked reasoning, a stray </think>, then the object.
            assertEquals("Lisbon", HttpFactories.callWith(llm("The field is the destination.</think>{\"text\": \"Lisbon\"}",
                    new ArrayList<>()), () -> helper.text(new JsonObject())));
            var e = assertThrows(JevException.class, () -> HttpFactories.callWith(
                    llm("{\"text\": \"Lisbon\"} and then some", new ArrayList<>()), () -> helper.text(new JsonObject())));
            assertEquals("Text helper returned no valid field value; nothing typed", e.getMessage());
        }
    }

    // --- the text-helper contract (no browser) ------------------------------------------------

    @Test
    void theTextHelperTakesExactlyOneTextKey() {
        assertEquals("Lisbon", JevRun.parseText("{\"text\": \"Lisbon\"}"));
        assertEquals("Lisbon", JevRun.parseText("  {\"text\":\"Lisbon\"}\n"));
    }

    @Test
    void aBraceInsideTheValueIsNotAnObject() {
        assertEquals("x{y}", JevRun.parseText("{\"text\": \"x{y}\"}"));
        assertEquals("{Lisbon}", JevRun.parseText("Answer: {\"text\": \"{Lisbon}\"}"));
    }

    @Test
    void aNullValueSaysTheGoalLacksItAndNeverFallsBackToAnEarlierObject() {
        var expected = "The goal gives no value for this field; nothing typed. Put every value to enter in the goal";
        assertEquals(expected, assertThrows(JevException.class, () -> JevRun.parseText("{\"text\": null}")).getMessage());
        // A guess in the reasoning must not be typed when the final answer declines to give a value.
        var guessed = "I guess {\"text\": \"John Smith\"} but no name is given.</think>{\"text\": null}";
        assertEquals(expected, assertThrows(JevException.class, () -> JevRun.parseText(guessed)).getMessage());
        var guessedThenBlank = "Maybe {\"text\": \"John Smith\"}.</think>{\"text\": \"  \"}";
        assertThrows(JevException.class, () -> JevRun.parseText(guessedThenBlank));
    }

    /** JCLAW-1275: the reply shapes live models produced, each ending in the object to type. */
    @ParameterizedTest
    @ValueSource(strings = {
            "```json\n{\"text\": \"Lisbon\"}\n```",
            "The field is a search box. So I should return {\"text\": \"Porto\"}.</think>{\"text\": \"Lisbon\"}",
            "The goal says to type \"Lisbon\". So the text should be \"Lisbon\".{\"text\": \"Lisbon\"}",
            "Reasoning first.\n\n```json\n{\"text\": \"Lisbon\"}\n```\n"
    })
    void theFinalObjectIsTypedAfterReasoningOrInsideAFence(String reply) {
        assertEquals("Lisbon", JevRun.parseText(reply), reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Lisbon",
            "{\"text\": \"   \"}",
            "{\"text\": 42}",
            "{\"text\": \"Lisbon\", \"why\": \"goal\"}",
            "Here it is: {\"text\": \"Lisbon\", \"why\": \"goal\"}",
            "{\"answer\": {\"text\": \"Lisbon\"}}",
            "{text: 'Lisbon'}",
            "{\"text\": \"Lisbon\"} trailing",
            "```json\n{\"text\": \"Lisbon\"}\n```\nDone.",
            "I would type Lisbon but I am not sure.",
            "[\"Lisbon\"]",
            ""
    })
    void anythingElseTypesNothing(String reply) {
        var e = assertThrows(JevException.class, () -> JevRun.parseText(reply));
        assertEquals("Text helper returned no valid field value; nothing typed", e.getMessage(), reply);
    }

    @Test
    void aValueLongerThanTwoThousandCharactersTypesNothing() {
        assertThrows(JevException.class, () -> JevRun.parseText("{\"text\": \"" + "x".repeat(2001) + "\"}"));
        assertEquals(2000, JevRun.parseText("{\"text\": \"" + "x".repeat(2000) + "\"}").length());
    }
}
