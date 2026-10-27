import agents.AgentExecutionSink;
import agents.AgentRunner;
import agents.ContextWindowManager;
import agents.ConversationSink;
import agents.CurrentTimeInjector;
import agents.ToolCallLoopRunner;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import controllers.ApiChatController;
import llm.LlmProvider;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;
import llm.OpenAiProvider;
import llm.ProviderRegistry;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import utils.CircuitBreakers;
import utils.LatencyTrace;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A provider's refusal of a request longer than its context window: the fallback first, then one
 * trimmed retry per turn, then the context-window message — on the sync loop, the streaming
 * continuation rounds and the first streamed round.
 *
 * <p>The model is {@code gpt-4o} so the token estimate runs on its own encoding with no safety
 * multiplier, which {@code ContextWindowSafetyMultiplierTest} flips process-wide.
 */
class ContextOverflowRecoveryTest extends UnitTest {

    private static final String MODEL = "gpt-4o";
    private static final int WINDOW = 100_000;
    private static final String SYSTEM = "You are terse.";
    private static final String CURRENT = "Current question?";
    private static final String SHORT_ANSWER = "Short answer.";
    /** Large enough that dropping it alone meets the retry budget beside a streamed turn's full system prompt. */
    private static final String OLDEST = "Oldest question about the harbour. ".repeat(2000);
    private static final String GENERIC_REPLY =
            "I'm sorry, I encountered an error communicating with the AI provider. Please try again.";
    /** The current-time block a streamed turn sends after the question, whose text changes each minute. */
    private static final String CLOCK = "user:<current time>";
    private static final String ANSWER_NUDGE = "Write the full answer now as markdown. Keep any reasoning brief.";
    private static final String SYNTHESIS_NUDGE = "Synthesize the final response for me now using the tool results "
            + "above. Do not call any more tools. Write the full answer as markdown.";

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<String> providers = new ArrayList<>();
    private final List<String> registered = new ArrayList<>();

    @AfterEach
    void teardown() {
        servers.forEach(s -> s.stop(0));
        for (var name : registered) {
            ConfigService.delete("provider." + name + ".baseUrl");
            ConfigService.delete("provider." + name + ".apiKey");
            ConfigService.delete("provider." + name + ".models");
        }
        if (!registered.isEmpty()) ProviderRegistry.refresh();
        providers.forEach(name -> CircuitBreakers.remove(LlmResilience.breakerName(name)));
    }

    // ─── sync loop ───────────────────────────────────────────────────────────

    @Test
    void aSyncRoundRefusedForLengthIsResentOnceWithoutTheOldestHistoryAndAnswers() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), text("Answer from the smaller request."));
        var provider = provider("primary", primary);

        var outcome = runSyncLoop(provider, null, "web");

        assertEquals("Answer from the smaller request.", outcome.content());
        assertEquals(List.of("primary", "primary"), calls);
        assertEquals(List.of("system:" + SYSTEM, "user:" + OLDEST, "assistant:" + SHORT_ANSWER, "user:" + CURRENT),
                sent(primary.bodies.get(0)));
        assertEquals(List.of("system:" + SYSTEM, "assistant:" + SHORT_ANSWER, "user:" + CURRENT),
                sent(primary.bodies.get(1)));
        assertTrue(primary.bodies.get(1).length() < primary.bodies.get(0).length(),
                "the retried request is smaller than the refused one");
    }

    @Test
    void aSyncRoundRefusedForLengthTwiceEndsTheTurnWithTheContextWindowMessage() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), refusal(), text("never sent"));
        var provider = provider("primary", primary);

        var outcome = runSyncLoop(provider, null, "web");

        assertEquals(contextWindowMessage(provider.config().name()), outcome.content());
        assertEquals(List.of("primary", "primary"), calls, "exactly one recovery retry");
    }

    @Test
    void aChannelReaderKeepsTheGenericReplyWhenTheWindowRefusesTwice() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), refusal());
        var provider = provider("primary", primary);

        var outcome = runSyncLoop(provider, null, "telegram");

        assertEquals(GENERIC_REPLY, outcome.content(),
                "the operator's template names the provider and the model, which a channel reader never sees");
        assertEquals(List.of("primary", "primary"), calls);
    }

    @Test
    void aSyncRoundRefusedForAnotherReasonKeepsTheGenericReplyAndIsNotRetried() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, otherClientError(), text("never sent"));
        var provider = provider("primary", primary);

        var outcome = runSyncLoop(provider, null, "web");

        assertEquals(GENERIC_REPLY, outcome.content());
        assertEquals(List.of("primary"), calls);
    }

    @Test
    void aSyncFallbackThatAcceptsTheRefusedListAnswersWithoutAnyTrimming() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal());
        var secondary = serve("fallback", calls, text("The fallback's larger window took it."));

        var outcome = runSyncLoop(provider("primary", primary), provider("fallback", secondary), "web");

        assertEquals("The fallback's larger window took it.", outcome.content());
        assertEquals(List.of("primary", "fallback"), calls);
        assertEquals(sent(primary.bodies.get(0)), sent(secondary.bodies.get(0)),
                "the fallback is sent the list the primary refused, untrimmed");
    }

    @Test
    void aSyncTurnTrimsOnlyAfterTheFallbackRefusesToo() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), text("Answer after the fallback refused."));
        var secondary = serve("fallback", calls, refusal(), text("never sent"));

        var outcome = runSyncLoop(provider("primary", primary), provider("fallback", secondary), "web");

        assertEquals("Answer after the fallback refused.", outcome.content());
        assertEquals(List.of("primary", "fallback", "primary"), calls);
        assertEquals(List.of("system:" + SYSTEM, "user:" + OLDEST, "assistant:" + SHORT_ANSWER, "user:" + CURRENT),
                sent(secondary.bodies.get(0)));
        assertEquals(List.of("system:" + SYSTEM, "assistant:" + SHORT_ANSWER, "user:" + CURRENT),
                sent(primary.bodies.get(1)));
    }

    @Test
    void aLaterRoundAfterARecoverySendsWithinTheBudgetAndIsNotRetriedAgain() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), toolCall("tc1"), refusal(), text("never sent"));
        var provider = provider("primary", primary);

        var outcome = runSyncLoop(provider, null, "web");

        assertEquals(contextWindowMessage(provider.config().name()), outcome.content());
        assertEquals(List.of("primary", "primary", "primary"), calls,
                "the turn's one retry went to round 1, so round 2's refusal ends it");
        assertEquals(List.of("system:" + SYSTEM, "assistant:" + SHORT_ANSWER, "user:" + CURRENT,
                "assistant->tc1", "tool<-tc1"), sent(primary.bodies.get(2)));
    }

    @Test
    void aSyncEmptyReplyAfterARecoveryIsRetriedWithinTheBudget() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), emptyReply("length"), text("Answer within the budget."));
        var provider = provider("primary", primary);

        var outcome = runSyncLoop(provider, null, "web");

        assertEquals("Answer within the budget.", outcome.content());
        assertEquals(List.of("primary", "primary", "primary"), calls);
        assertEquals(List.of("system:" + SYSTEM, "assistant:" + SHORT_ANSWER, "user:" + CURRENT, "user:" + ANSWER_NUDGE),
                sent(primary.bodies.get(2)), "the retry leaves out what the provider refused");
    }

    @Test
    void aSyncEmptyContinuationAfterARecoveryIsRetriedWithinTheBudget() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), toolCall("tc1"), text(""), text("Answer within the budget."));
        var provider = provider("primary", primary);

        var outcome = runSyncLoop(provider, null, "web");

        assertEquals("Answer within the budget.", outcome.content());
        assertEquals(List.of("primary", "primary", "primary", "primary"), calls);
        assertEquals(List.of("system:" + SYSTEM, "assistant:" + SHORT_ANSWER, "user:" + CURRENT,
                "assistant->tc1", "tool<-tc1", "user:" + SYNTHESIS_NUDGE), sent(primary.bodies.get(3)));
    }

    // ─── streaming continuation rounds ───────────────────────────────────────

    @Test
    void aStreamedContinuationRefusedForLengthIsResentOnceWithASmallerList() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), text("Answer after trimming."));

        var result = runContinuation(provider("primary", primary), null);

        assertEquals("Answer after trimming.", result);
        assertEquals(List.of("primary", "primary"), calls);
        assertEquals(List.of("system:" + SYSTEM, "user:" + OLDEST, "assistant:" + SHORT_ANSWER, "user:" + CURRENT,
                "assistant->tc1", "tool<-tc1"), sent(primary.bodies.get(0)));
        assertEquals(List.of("system:" + SYSTEM, "assistant:" + SHORT_ANSWER, "user:" + CURRENT,
                "assistant->tc1", "tool<-tc1"), sent(primary.bodies.get(1)));
    }

    @Test
    void aStreamedContinuationRefusedTwiceEndsTheTurnWithTheContextWindowMessage() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), refusal(), text("never sent"));
        var provider = provider("primary", primary);

        var thrown = assertThrows(LlmProvider.LlmException.class, () -> runContinuation(provider, null));

        assertEquals(contextWindowMessage(provider.config().name()), ApiChatController.webErrorFrame(thrown).get("content"));
        assertEquals(List.of("primary", "primary"), calls, "exactly one recovery retry, and no synthesis retries");
    }

    @Test
    void aStreamedContinuationTriesTheFallbackBeforeTrimming() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), text("never sent"));
        var secondary = serve("fallback", calls, text("The fallback's larger window took it."));

        var result = runContinuation(provider("primary", primary), provider("fallback", secondary));

        assertEquals("The fallback's larger window took it.", result);
        assertEquals(List.of("primary", "fallback"), calls);
        assertEquals(sent(primary.bodies.get(0)), sent(secondary.bodies.get(0)),
                "the fallback is sent the list the primary refused, untrimmed");
    }

    @Test
    void aStreamedContinuationTrimsOnlyAfterTheFallbackRefusesToo() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), text("Answer after the fallback refused."));
        var secondary = serve("fallback", calls, refusal(), text("never sent"));

        var result = runContinuation(provider("primary", primary), provider("fallback", secondary));

        assertEquals("Answer after the fallback refused.", result);
        assertEquals(List.of("primary", "fallback", "primary"), calls);
        assertEquals(List.of("system:" + SYSTEM, "assistant:" + SHORT_ANSWER, "user:" + CURRENT,
                "assistant->tc1", "tool<-tc1"), sent(primary.bodies.get(1)));
    }

    @Test
    void aStreamedTrimmedListThePrimaryRefusesIsSentToTheFallback() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), refusal(), text("never sent"));
        var secondary = serve("fallback", calls, refusal(), text("The fallback took the trimmed list."));

        var result = runContinuation(provider("primary", primary), provider("fallback", secondary));

        assertEquals("The fallback took the trimmed list.", result);
        assertEquals(List.of("primary", "fallback", "primary", "fallback"), calls);
        var trimmed = List.of("system:" + SYSTEM, "assistant:" + SHORT_ANSWER, "user:" + CURRENT,
                "assistant->tc1", "tool<-tc1");
        assertEquals(trimmed, sent(primary.bodies.get(1)));
        assertEquals(trimmed, sent(secondary.bodies.get(1)));
    }

    @Test
    void aStreamedContinuationBothModelsRefuseTrimmedEndsTheTurnWithTheContextWindowMessage() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), refusal(), text("never sent"));
        var secondary = serve("fallback", calls, refusal(), refusal(), text("never sent"));
        var fallback = provider("fallback", secondary);

        var thrown = assertThrows(LlmProvider.LlmException.class,
                () -> runContinuation(provider("primary", primary), fallback));

        assertEquals(contextWindowMessage(fallback.config().name()),
                ApiChatController.webErrorFrame(thrown).get("content"), "the last model asked refused it");
        assertEquals(List.of("primary", "fallback", "primary", "fallback"), calls);
    }

    @Test
    void aStreamedEmptyContinuationAfterARecoveryIsRetriedWithinTheBudget() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), text(""), text("Answer within the budget."));

        var result = runContinuation(provider("primary", primary), null);

        assertEquals("Answer within the budget.", result);
        assertEquals(List.of("primary", "primary", "primary"), calls);
        assertEquals(List.of("system:" + SYSTEM, "assistant:" + SHORT_ANSWER, "user:" + CURRENT,
                "assistant->tc1", "tool<-tc1", "user:" + SYNTHESIS_NUDGE), sent(primary.bodies.get(2)));
    }

    @Test
    void aStreamedContinuationRefusedForAnotherReasonKeepsTodaysSynthesisRetry() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, otherClientError(), otherClientError());

        var result = runContinuation(provider("primary", primary), null);

        assertEquals("*[The model returned no synthesis after tool calls. Tool results are in the conversation "
                + "history above — try rephrasing your request or switching to a larger model.]*", result);
        assertEquals(List.of("primary", "primary"), calls);
        assertTrue(primary.bodies.get(1).contains("Do not call any more tools"),
                "the second call is the synthesis nudge, not a trimmed resend");
        assertTrue(primary.bodies.get(1).contains("Oldest question about the harbour"));
    }

    // ─── first streamed round ────────────────────────────────────────────────

    @Test
    void aFirstStreamedRoundRefusedForLengthIsResentWithoutTheOldestHistory() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), text("Answer after trimming."));
        var name = register("primary", primary);
        var convo = seededConversation(persistAgent(name, null));

        var harness = stream(convo);

        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS), "the turn must end");
        assertNull(harness.error.get(), () -> "unexpected error: " + harness.error.get());
        assertEquals("Answer after trimming.", harness.completed.get());
        assertEquals(List.of("primary", "primary"), calls);
        assertEquals(List.of("user:" + OLDEST, "assistant:" + SHORT_ANSWER, "user:" + CURRENT, CLOCK),
                sentAfterSystem(primary.bodies.get(0)));
        assertEquals(List.of("assistant:" + SHORT_ANSWER, "user:" + CURRENT, CLOCK),
                sentAfterSystem(primary.bodies.get(1)), "the retry drops the oldest history and keeps the question");
        assertTrue(primary.bodies.get(1).length() < primary.bodies.get(0).length(),
                "the retried request is smaller than the refused one");
    }

    @Test
    void aFirstStreamedEmptyReplyAfterARecoveryIsRetriedWithinTheBudget() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), emptyReply("length"), text("Answer within the budget."));
        var name = register("primary", primary);
        var convo = seededConversation(persistAgent(name, null));

        var harness = stream(convo);

        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS), "the turn must end");
        assertNull(harness.error.get(), () -> "unexpected error: " + harness.error.get());
        assertEquals("Answer within the budget.", harness.completed.get());
        assertEquals(List.of("primary", "primary", "primary"), calls);
        assertEquals(List.of("assistant:" + SHORT_ANSWER, "user:" + CURRENT, CLOCK, "user:" + ANSWER_NUDGE),
                sentAfterSystem(primary.bodies.get(2)), "the retry leaves out what the provider refused");
    }

    @Test
    void aFirstStreamedRoundRefusedTwiceShowsTheOperatorTheContextWindowMessage() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), refusal(), text("never sent"));
        var name = register("primary", primary);
        var convo = seededConversation(persistAgent(name, null));

        var harness = stream(convo);

        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS), "the turn must end");
        assertNull(harness.completed.get());
        assertEquals(contextWindowMessage(name), ApiChatController.webErrorFrame(harness.error.get()).get("content"));
        assertEquals(List.of("primary", "primary"), calls, "exactly one recovery retry");
    }

    @Test
    void aFirstStreamedRoundTriesTheAgentsFallbackBeforeTrimming() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var primary = serve("primary", calls, refusal(), text("never sent"));
        var secondary = serve("fallback", calls, text("The fallback's larger window took it."));
        var primaryName = register("primary", primary);
        var fallbackName = register("fallback", secondary);
        var convo = seededConversation(persistAgent(primaryName, fallbackName));

        var harness = stream(convo);

        assertTrue(harness.terminated.await(60, TimeUnit.SECONDS), "the turn must end");
        assertNull(harness.error.get(), () -> "unexpected error: " + harness.error.get());
        assertEquals("The fallback's larger window took it.", harness.completed.get());
        assertEquals(List.of("primary", "fallback"), calls);
        assertEquals(sent(primary.bodies.get(0)), sent(secondary.bodies.get(0)),
                "the fallback is sent the list the primary refused, untrimmed");
    }

    // ─── the trim itself ─────────────────────────────────────────────────────

    @Test
    void theRetryBudgetIsTighterThanAnEstimateThatAlreadyFitTheWindow() throws Exception {
        var messages = history();
        var estimate = TokenUsageEstimator.estimateChatRequest(MODEL, messages, List.of()).promptTokens();

        int budget = overflowBudget(messages, offlineProvider());

        assertTrue(estimate < WINDOW / 2, "precondition: the estimate says the list fits, got " + estimate);
        assertEquals((int) (estimate * 0.75), budget);
    }

    @Test
    void theRetryBudgetOfAListOverTheWindowIsAQuarterUnderTheTrimTarget() throws Exception {
        var messages = new ArrayList<>(history());
        messages.add(1, ChatMessage.user("Harbour ledger entry. ".repeat(30_000)));

        int budget = overflowBudget(messages, offlineProvider());

        assertEquals((int) ((WINDOW - 4096) * 0.75), budget);
    }

    @Test
    void anOversizedToolResultIsTruncatedBeforeAnyHistoryIsDropped() throws Exception {
        var big = "row,value\n".repeat(3_000);
        var messages = List.of(ChatMessage.system(SYSTEM), ChatMessage.user("Old question."),
                ChatMessage.assistant("Old answer."), ChatMessage.user(CURRENT),
                ChatMessage.assistant("", List.of(call("tc1"))), ChatMessage.toolResult("tc1", "lookup", big));
        int budget = TokenUsageEstimator.estimateChatRequest(MODEL, messages, List.of()).promptTokens() / 2;

        var trimmed = trimToBudget(messages, budget);

        assertEquals(messages.subList(0, 5), trimmed.subList(0, 5), "no message is dropped");
        assertEquals(ContextWindowManager.truncateToolResultContent(big, 500, 500), trimmed.get(5).content());
        assertEquals("tc1", trimmed.get(5).toolCallId());
    }

    @Test
    void aToolCallIsDroppedTogetherWithItsResultsAndTheQuestionAndLastRoundStay() throws Exception {
        var messages = List.of(ChatMessage.system(SYSTEM), ChatMessage.user("Old question."),
                ChatMessage.assistant("", List.of(call("tcA"), call("tcB"))),
                ChatMessage.toolResult("tcA", "lookup", "first result"),
                ChatMessage.toolResult("tcB", "lookup", "second result"),
                ChatMessage.user(CURRENT),
                ChatMessage.assistant("", List.of(call("tcC"))),
                ChatMessage.toolResult("tcC", "lookup", "latest result"));

        var trimmed = trimToBudget(messages, 1);

        assertEquals(List.of(messages.get(0), messages.get(5), messages.get(6), messages.get(7)), trimmed);
    }

    @Test
    void theQuestionAndTheClockAfterItStayAsOneUserTurn() throws Exception {
        var messages = List.of(ChatMessage.system(SYSTEM), ChatMessage.user("Old question."),
                ChatMessage.assistant("Old answer."), ChatMessage.user(CURRENT),
                ChatMessage.user(CurrentTimeInjector.block()),
                ChatMessage.assistant("", List.of(call("tc1"))), ChatMessage.toolResult("tc1", "lookup", "result"));

        var trimmed = trimToBudget(messages, 1);

        assertEquals(List.of(messages.get(0), messages.get(3), messages.get(4), messages.get(5), messages.get(6)),
                trimmed);
    }

    @Test
    void aListAlreadyWithinTheBudgetIsLeftAlone() throws Exception {
        var messages = history();
        int estimate = TokenUsageEstimator.estimateChatRequest(MODEL, messages, List.of()).promptTokens();

        assertNull(trimToBudget(messages, estimate), "at the budget nothing is removed");
        assertEquals(List.of(messages.get(0), messages.get(2), messages.get(3)), trimToBudget(messages, estimate - 1),
                "one token over it, the oldest message goes");
    }

    @Test
    void aListWithNothingToRemoveReportsSo() throws Exception {
        var messages = List.of(ChatMessage.system(SYSTEM), ChatMessage.user(CURRENT));

        assertNull(trimToBudget(messages, 1));
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private static List<ChatMessage> history() {
        return List.of(ChatMessage.system(SYSTEM), ChatMessage.user(OLDEST),
                ChatMessage.assistant(SHORT_ANSWER), ChatMessage.user(CURRENT));
    }

    private static String contextWindowMessage(String provider) {
        return "**What broke** — The request was longer than the context window of " + MODEL + " on " + provider
                + " (100,000 tokens).\n\n"
                + "**What to check** — Everything counts toward that limit, not just your last message: the system "
                + "prompt, standing orders, recalled memories, tool results and the whole conversation so far.\n\n"
                + "**How to retry** — Compact or start a fresh conversation, or switch to a model with a larger "
                + "context window.";
    }

    private record Reply(int status, String body) {}

    private static Reply refusal() {
        return new Reply(400, """
                {"error":{"message":"This model's maximum context length is 100000 tokens. However, your messages \
                resulted in 100500 tokens.","type":"invalid_request_error","param":"messages","code":"context_length_exceeded"}}""");
    }

    private static Reply otherClientError() {
        return new Reply(400, """
                {"error":{"message":"Invalid value for 'temperature'.","type":"invalid_request_error",\
                "param":"temperature","code":"invalid_value"}}""");
    }

    private static Reply text(String content) {
        return new Reply(200, """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(content));
    }

    /** A reply with no content and no tool call, as when reasoning spent the whole output budget. */
    private static Reply emptyReply(String finishReason) {
        return new Reply(200, """
                {"choices":[{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"%s"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(finishReason));
    }

    private static Reply toolCall(String id) {
        return new Reply(200, """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"%s",\
                "type":"function","function":{"name":"jf127_lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(id));
    }

    private static ToolCall call(String id) {
        return new ToolCall(id, "function", new FunctionCall("lookup", "{}"));
    }

    /** A chat endpoint answering each request with the next scripted reply, recording what it was sent. */
    private record ScriptedLlm(HttpServer server, List<String> bodies) {
        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    private ScriptedLlm serve(String label, List<String> calls, Reply... replies) throws IOException {
        var bodies = new CopyOnWriteArrayList<String>();
        var next = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.add(label);
            var reply = replies[Math.min(next.getAndIncrement(), replies.length - 1)];
            if (reply.status() == 200) {
                LlmReplyFixture.send(exchange, reply.body());
                return;
            }
            var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return new ScriptedLlm(server, bodies);
    }

    private OpenAiProvider provider(String label, ScriptedLlm llm) {
        var name = "jf127-" + label + "-" + System.nanoTime();
        providers.add(name);
        return new OpenAiProvider(new ProviderConfig(name, llm.baseUrl(), "sk-test",
                List.of(new ModelInfo(MODEL, "Test", WINDOW, 4096, false))));
    }

    private static OpenAiProvider offlineProvider() {
        return new OpenAiProvider(new ProviderConfig("jf127-offline", "http://127.0.0.1:1/v1", "sk-test",
                List.of(new ModelInfo(MODEL, "Test", WINDOW, 4096, false))));
    }

    private String register(String label, ScriptedLlm llm) {
        var name = "jf127-" + label + "-" + System.nanoTime();
        providers.add(name);
        registered.add(name);
        ConfigService.set("provider." + name + ".baseUrl", llm.baseUrl());
        ConfigService.set("provider." + name + ".apiKey", "sk-test");
        ConfigService.set("provider." + name + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"Test\",\"contextWindow\":" + WINDOW + ",\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
        return name;
    }

    private static Agent persistAgent(String provider, String fallbackProvider) {
        var agent = new Agent();
        agent.name = "jf127-agent-" + System.nanoTime();
        agent.modelProvider = provider;
        agent.modelId = MODEL;
        if (fallbackProvider != null) {
            agent.fallbackProvider = fallbackProvider;
            agent.fallbackModelId = MODEL;
        }
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private static Conversation conversation(Agent agent, String channelType) {
        var convo = ConversationService.create(agent, channelType, "jf127-peer-" + System.nanoTime());
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return convo;
    }

    /** A web conversation whose stored history is the oldest question and its short answer. */
    private static Conversation seededConversation(Agent agent) {
        var convo = ConversationService.create(agent, "web", "jf127-peer-" + System.nanoTime());
        for (var row : List.of(ChatMessage.user(OLDEST), ChatMessage.assistant(SHORT_ANSWER))) {
            var msg = new Message();
            msg.conversation = convo;
            msg.role = row.role();
            msg.content = (String) row.content();
            msg.save();
        }
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return convo;
    }

    /**
     * Each message as {@code role:content}, a tool-call turn as {@code assistant->ids}, a result as {@code tool<-id},
     * and the injected wall-clock block as {@link #CLOCK}.
     */
    private static List<String> sent(String body) {
        var out = new ArrayList<String>();
        for (var element : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages")) {
            var m = element.getAsJsonObject();
            var role = m.get("role").getAsString();
            var calls = m.get("tool_calls");
            if (calls != null && calls.isJsonArray() && !calls.getAsJsonArray().isEmpty()) {
                var ids = new ArrayList<String>();
                calls.getAsJsonArray().forEach(c -> ids.add(c.getAsJsonObject().get("id").getAsString()));
                out.add(role + "->" + String.join(",", ids));
            } else if (MessageRole.TOOL.value.equals(role)) {
                out.add("tool<-" + m.get("tool_call_id").getAsString());
            } else {
                var content = m.get("content");
                var text = content != null && content.isJsonPrimitive() ? content.getAsString() : "";
                out.add(text.contains("## Current Date and Time") ? CLOCK : role + ":" + text);
            }
        }
        return out;
    }

    /** {@link #sent} without the leading system prompt, which a streamed turn assembles in full. */
    private static List<String> sentAfterSystem(String body) {
        var all = sent(body);
        assertTrue(all.getFirst().startsWith("system:"), () -> "the request opens with the system prompt: " + all);
        return all.subList(1, all.size());
    }

    // ─── drivers ─────────────────────────────────────────────────────────────

    private ToolCallLoopRunner.LoopOutcome runSyncLoop(LlmProvider primary, LlmProvider secondary, String channel)
            throws Exception {
        var agent = persistAgent(primary.config().name(), null);
        var convo = conversation(agent, channel);
        var fallback = secondary == null ? null : new LlmProvider.Fallback(secondary, MODEL);
        Method m = ToolCallLoopRunner.class.getDeclaredMethod("callWithToolLoop",
                Agent.class, Conversation.class, Long.class, List.class, List.class, LlmProvider.class,
                LlmProvider.Fallback.class, List.class, List.class, AgentExecutionSink.class, Long.class);
        m.setAccessible(true);
        return onVirtualThread(() -> (ToolCallLoopRunner.LoopOutcome) m.invoke(null, agent, convo, convo.id,
                new ArrayList<>(history()), List.<ToolDef>of(), primary, fallback, List.of(), List.of(),
                new ConversationSink(convo), null));
    }

    private String runContinuation(LlmProvider primary, LlmProvider secondary) throws Exception {
        var agent = persistAgent(primary.config().name(), null);
        var convo = conversation(agent, "web");
        var fallback = secondary == null ? null : new LlmProvider.Fallback(secondary, MODEL);
        AgentRunner.StreamingCallbacks cb = new AgentRunner.StreamingCallbacks(
                _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {}, () -> {});
        var ctx = new ToolCallLoopRunner.StreamingTurnContext(agent, convo, convo.id, List.of(), primary, fallback,
                cb, null, new AtomicBoolean(false), new LatencyTrace(), new LlmProvider.TurnUsage(),
                new ArrayList<>(), "web", new ConversationSink(convo));
        Method m = ToolCallLoopRunner.class.getDeclaredMethod("handleToolCallsStreaming",
                ToolCallLoopRunner.StreamingTurnContext.class, List.class, List.class, String.class, int.class);
        m.setAccessible(true);
        return onVirtualThread(() -> (String) m.invoke(null, ctx, history(), List.of(call("tc1")), "", 0));
    }

    private interface Invocation<T> {
        T call() throws Exception;
    }

    private static <T> T onVirtualThread(Invocation<T> body) throws Exception {
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                result.set(body.call());
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "the loop must return within 60 s");
        var e = error.get();
        if (e instanceof InvocationTargetException ite && ite.getCause() instanceof RuntimeException re) throw re;
        if (e != null) throw e;
        return result.get();
    }

    private static final class Harness {
        final AtomicReference<String> completed = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch terminated = new CountDownLatch(1);
    }

    private static Harness stream(Conversation convo) {
        var h = new Harness();
        var cb = new AgentRunner.StreamingCallbacks(_ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {},
                content -> { h.completed.set(content); h.terminated.countDown(); },
                error -> { h.error.set(error); h.terminated.countDown(); },
                h.terminated::countDown);
        AgentRunner.runStreaming(convo.agent, convo.id, convo.channelType, convo.peerId, CURRENT,
                new AtomicBoolean(false), cb, null);
        return h;
    }

    private static int overflowBudget(List<ChatMessage> messages, LlmProvider provider) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("overflowBudget",
                List.class, Agent.class, Conversation.class, LlmProvider.class, List.class);
        m.setAccessible(true);
        return (int) m.invoke(null, messages, offlineAgent(), new Conversation(), provider, List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessage> trimToBudget(List<ChatMessage> messages, int budget) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("trimToBudget",
                List.class, Agent.class, Conversation.class, LlmProvider.class, List.class, int.class);
        m.setAccessible(true);
        return (List<ChatMessage>) m.invoke(null, messages, offlineAgent(), new Conversation(), offlineProvider(),
                List.of(), budget);
    }

    private static Agent offlineAgent() {
        var agent = new Agent();
        agent.name = "jf127-offline-agent";
        agent.modelProvider = "jf127-offline";
        agent.modelId = MODEL;
        return agent;
    }
}
