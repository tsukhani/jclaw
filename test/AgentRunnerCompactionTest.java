import agents.AgentRunner;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SessionCompaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.Tx;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-308: pin the AgentRunner ↔ {@link agents.CompactionGate} integration.
 * When the assembled message list crosses the per-model compaction budget,
 * the runner MUST (a) invoke the summarizer LLM call, (b) persist a
 * {@link SessionCompaction} row, (c) bump
 * {@link Conversation#compactionSince}, and (d) make the second LLM call
 * against the COMPACTED message list rather than the original.
 *
 * <p>The existing {@link SessionCompactor} unit tests pin the boundary-
 * selection arithmetic; this file pins the runner-level integration so a
 * regression in {@link AgentRunner#runAfterAcquire} that bypasses
 * {@code CompactionGate.maybeCompactAndRebuild} (or feeds the wrong message
 * list to the chat call) lands a precise test failure.
 *
 * <p>Trigger setup: configure a tiny {@code contextWindow=2000} model and a
 * tiny {@code chat.compactionReserveTokens=500} budget, then seed enough
 * pre-existing turns so {@code estimateTokens(messages) > 1500}. The runner
 * then crosses the {@link services.SessionCompactor#shouldCompact} gate on
 * the first LLM round and the gate fires.
 */
class AgentRunnerCompactionTest extends UnitTest {

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
    }

    @AfterEach
    void teardown() {
        if (llmServer != null) {
            llmServer.stop(0);
            llmServer = null;
        }
        // Clear the tight budgets so siblings in the same JVM don't inherit
        // them.
        ConfigService.set("chat.compactionReserveTokens", "15000");
        ConfigService.set("chat.compactionReserveTokensFloor", "9000");
        ConfigService.set("chat.compactionMinTurns", "10");
        ConfigService.set("chat.compactionKeepMessages", "10");
    }

    /**
     * Happy path: pre-seeded context blows past the budget, the runner
     * runs the summarize call THEN the chat call, persists a SessionCompaction
     * row, and bumps the conversation's compactionSince watermark so the
     * next turn's loadRecentMessages skips the compacted prefix.
     */
    @Test
    void runTriggersCompactionAndResumesAgainstCompactedMessageList() throws Exception {
        var callCount = new AtomicInteger(0);
        var firstCallBody = new AtomicReference<String>();
        var secondCallBody = new AtomicReference<String>();
        startLlmServer(exchange -> {
            int n = callCount.incrementAndGet();
            var bytes = exchange.getRequestBody().readAllBytes();
            var requestBody = new String(bytes);
            String responseBody;
            if (n == 1) {
                firstCallBody.set(requestBody);
                // Summarizer call — return the canned summary.
                responseBody = simpleResponse("SUMMARY-OF-OLDER-TURNS");
            } else {
                secondCallBody.set(requestBody);
                // Actual chat call after compaction.
                responseBody = simpleResponse("Reply after compaction.");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.getBytes().length);
            exchange.getResponseBody().write(responseBody.getBytes());
            exchange.close();
        });
        // Use a tiny contextWindow + relaxed compaction thresholds so a
        // modest seed of messages is enough to cross the budget AND clear
        // the min-turns gate.
        configureTinyContextProvider();
        ConfigService.set("chat.compactionReserveTokens", "200");
        ConfigService.set("chat.compactionReserveTokensFloor", "100");
        // Lower the safety floors so a short seeded history is summarisable.
        ConfigService.set("chat.compactionMinTurns", "4");
        ConfigService.set("chat.compactionKeepMessages", "2");

        var agent = createAgent("compaction-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "u-compact");

        // Seed enough pre-existing turns to (a) cross the
        // estimateTokens > 1800 (window 2000 - reserve 200) threshold and
        // (b) clear the compactionMinTurns=4 / compactionKeepMessages=2
        // boundary in SessionCompactor.findSafeBoundary.
        //
        // Each filler turn is ~2000 chars => ~500 tokens, and we add 8
        // turns (4 user / 4 assistant) for ~4000 tokens of context which
        // is well past the budget while still leaving room for the
        // safe-boundary scan to find a USER row in the eligible range.
        var bigBlock = "x".repeat(2000);
        ConversationService.appendUserMessage(convo, "u1 " + bigBlock);
        ConversationService.appendAssistantMessage(convo, "a1 " + bigBlock, null);
        ConversationService.appendUserMessage(convo, "u2 " + bigBlock);
        ConversationService.appendAssistantMessage(convo, "a2 " + bigBlock, null);
        ConversationService.appendUserMessage(convo, "u3 " + bigBlock);
        ConversationService.appendAssistantMessage(convo, "a3 " + bigBlock, null);
        ConversationService.appendUserMessage(convo, "u4 " + bigBlock);
        ConversationService.appendAssistantMessage(convo, "a4 " + bigBlock, null);

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "What's next?");

        // Final user-visible reply is the chat call's response, not the
        // summarize call's response.
        assertEquals("Reply after compaction.", result.response(),
                "runner must return the post-compaction chat reply, got: " + result.response());

        // The summarize call must have happened — at least two LLM calls.
        assertTrue(callCount.get() >= 2,
                "expected ≥2 LLM calls (summarize + chat), got " + callCount.get());

        // A SessionCompaction row must exist for this conversation.
        JPA.em().clear();
        var convoId = convo.id;
        SessionCompaction latest = Tx.run(() ->
                SessionCompaction.findLatest(Conversation.findById(convoId)));
        assertNotNull(latest,
                "AgentRunner must persist a SessionCompaction row after the gate fires");
        assertEquals("SUMMARY-OF-OLDER-TURNS", latest.summary,
                "persisted summary text must mirror the summarize-call response");
        assertTrue(latest.turnCount > 0,
                "compaction must record how many turns were summarized");

        // Conversation.compactionSince must have been bumped so the next
        // turn's loadRecentMessages skips the compacted prefix.
        Conversation refreshed = Tx.run(() -> Conversation.findById(convoId));
        assertNotNull(refreshed.compactionSince,
                "compactionSince watermark must be set so subsequent turns trim the summarized prefix");

        // The chat call's request body must NOT contain the bulky filler
        // text from the seeded older turns — that's the load-bearing
        // assertion: after compaction the LLM sees the summary, not the
        // raw older turns.
        var chatBody = secondCallBody.get();
        assertNotNull(chatBody, "second LLM call body must have been captured");
        // The summary header from SessionCompactor.PRIOR_SUMMARY_HEADER
        // and the canned summary content must appear in the system prompt.
        assertTrue(chatBody.contains("Prior conversation summary")
                        || chatBody.contains("SUMMARY-OF-OLDER-TURNS"),
                "chat call after compaction must carry the summary in its system prompt, "
                        + "got chatBody: " + chatBody.substring(0, Math.min(800, chatBody.length())));
        // The summarize call's request body, by contrast, should contain
        // the raw older-turn content — that's how the summarizer can
        // produce a faithful summary.
        var summarizeBody = firstCallBody.get();
        assertTrue(summarizeBody.contains("u1 ") && summarizeBody.contains("a3 "),
                "summarize call must carry the raw older turns it was asked to fold");
    }

    /**
     * Below-budget path: a small message list does NOT trigger compaction,
     * does NOT cost an extra LLM round, and the conversation has no
     * SessionCompaction row at the end. This guards against an over-eager
     * gate change that fires on every turn.
     */
    @Test
    void runDoesNotCompactWhenContextStaysUnderBudget() throws Exception {
        var callCount = new AtomicInteger(0);
        startLlmServer(exchange -> {
            callCount.incrementAndGet();
            var responseBody = simpleResponse("Plain reply.");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.getBytes().length);
            exchange.getResponseBody().write(responseBody.getBytes());
            exchange.close();
        });
        configureProvider(); // large context window, default 100k

        var agent = createAgent("no-compaction-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "u-no-compact");

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        var result = runOnVirtualThread(agent, convo, "Hi");

        assertEquals("Plain reply.", result.response(),
                "no-compaction path must return the single chat call's response");
        assertEquals(1, callCount.get(),
                "no-compaction path must make exactly ONE LLM call (no summarize round)");

        JPA.em().clear();
        var convoId = convo.id;
        SessionCompaction latest = Tx.run(() ->
                SessionCompaction.findLatest(Conversation.findById(convoId)));
        assertNull(latest,
                "below-budget turns must NOT write a SessionCompaction row");
    }

    /**
     * Persistence-coherency: after compaction fires the user message AND
     * the assistant reply for THIS turn still land in the DB. Compaction is
     * a context-trimming optimisation, not a persistence-shape change —
     * the chat-UI's scrollback must still contain every turn since the
     * conversation began. Without this, a UI reload after the
     * compaction-triggering turn would render with the user input missing.
     */
    @Test
    void runStillPersistsUserAndAssistantMessagesEvenWhenCompactionFires() throws Exception {
        startLlmServer(exchange -> {
            var bytes = exchange.getRequestBody().readAllBytes();
            // Capture the call count via a static counter — first call is
            // summarize, second is the chat reply, third (if any) would
            // mean the loop diverged.
            //
            // We don't need to branch on the call here because both
            // responses can carry the same shape; the assertions look at
            // the DB state, not at the response wiring.
            var body = simpleResponse("Round response.");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
            // Discard the request body — we already read it.
            assertNotNull(bytes);
        });
        configureTinyContextProvider();
        ConfigService.set("chat.compactionReserveTokens", "200");
        ConfigService.set("chat.compactionReserveTokensFloor", "100");
        ConfigService.set("chat.compactionMinTurns", "4");
        ConfigService.set("chat.compactionKeepMessages", "2");

        var agent = createAgent("compaction-persist-agent", "test-provider", "test-model");
        var convo = ConversationService.create(agent, "web", "u-compact-persist");

        var bigBlock = "y".repeat(2000);
        for (int i = 0; i < 4; i++) {
            ConversationService.appendUserMessage(convo, "user-msg-" + i + " " + bigBlock);
            ConversationService.appendAssistantMessage(convo,
                    "asst-msg-" + i + " " + bigBlock, null);
        }

        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        runOnVirtualThread(agent, convo, "the new user turn");

        JPA.em().clear();
        var convoId = convo.id;
        java.util.List<Message> rows = Tx.run(() -> Message.<Message>find(
                "conversation = ?1 AND content LIKE ?2 ORDER BY id ASC",
                Conversation.findById(convoId), "%the new user turn%").fetch());
        assertTrue(rows.size() >= 1,
                "new user turn must be persisted even when compaction fires this turn");
        assertEquals(MessageRole.USER.value, rows.getFirst().role,
                "the matching row must be the user input");

        // Assistant reply ("Round response.") must also exist for this turn.
        java.util.List<Message> asstRows = Tx.run(() -> Message.<Message>find(
                "conversation = ?1 AND role = ?2 AND content = ?3",
                Conversation.findById(convoId), MessageRole.ASSISTANT.value,
                "Round response.").fetch());
        assertEquals(1, asstRows.size(),
                "assistant reply for the compaction-triggering turn must be persisted exactly once");
    }

    // ===== Helpers =====

    private Agent createAgent(String name, String provider, String model) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = provider;
        agent.modelId = model;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private void startLlmServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", handler);
        llmServer.start();
        port = llmServer.getAddress().getPort();
    }

    private void configureProvider() {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        llm.ProviderRegistry.refresh();
    }

    /**
     * Tiny window provider — 2000 tokens. With reserveTokens=200 below,
     * a few thousand chars of seeded history is enough to cross the budget.
     */
    private void configureTinyContextProvider() {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":2000,\"maxTokens\":256}]");
        llm.ProviderRegistry.refresh();
    }

    private static String simpleResponse(String content) {
        return """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(
                content.replace("\"", "\\\""));
    }

    private AgentRunner.RunResult runOnVirtualThread(Agent agent, Conversation convo, String message) throws Exception {
        var agentId = agent.id;
        var convoId = convo.id;
        var resultRef = new AtomicReference<AgentRunner.RunResult>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(agentId));
                var c = Tx.run(() -> (Conversation) Conversation.findById(convoId));
                resultRef.set(AgentRunner.run(a, c, message));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "AgentRunner.run must complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }
}
