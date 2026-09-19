import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1232: the media bearers address the assembled message list by position, and both
 * {@code CompactionGate.maybeCompactAndRebuild} and
 * {@code ContextWindowManager.trimToContextWindow} replace that list between hydration (where the
 * positions are computed) and the capability rewrites (where they are used). Before the fix the
 * compaction case threw {@code IndexOutOfBoundsException} out of the turn and the trim case wrote
 * the rewritten user turn over an assistant row.
 *
 * <p>Both tests drive a real {@code AgentRunner.run} against a canned HTTP LLM and assert on the
 * chat call's request body, because the ordering bug is invisible at any smaller seam: the rewrite
 * is correct in isolation and only wrong relative to a list rebuilt under it.
 *
 * <p>The image rides with a pre-set caption so no caption backend is configured or called — a
 * non-vision model then turns the {@code image_url} part into an {@code [Image description: ...]}
 * text block, and which message carries that block is exactly the question under test.
 */
class MediaBearerRebaseTest extends UnitTest {

    private static final String CAPTION = "ORANGE-CAT-ON-A-FENCE";

    private com.sun.net.httpserver.HttpServer llmServer;
    private int port;

    @BeforeEach
    void setup() throws Exception {
        // 200ms gap so a prior test's lingering virtual-thread writes settle before the wipe —
        // same guard AgentRunnerCompactionTest uses.
        Thread.sleep(200);
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
        ConfigService.set("chat.compactionReserveTokens", "15000");
        ConfigService.set("chat.compactionReserveTokensFloor", "9000");
        ConfigService.set("chat.compactionMinTurns", "10");
        ConfigService.set("chat.compactionKeepMessages", "10");
    }

    /**
     * Compaction fires between hydration and the rewrite, so the rebuilt list is shorter than the
     * one the image bearer was computed against. The caption must still land on the turn that
     * actually carries the image.
     */
    @Test
    void captionLandsOnTheImageTurnWhenCompactionRebuildsTheList() throws Exception {
        var chatBody = new AtomicReference<String>();
        var callCount = new AtomicInteger();
        startLlmServer(exchange -> {
            var request = new String(exchange.getRequestBody().readAllBytes());
            var n = callCount.incrementAndGet();
            String response;
            if (n == 1) {
                response = simpleResponse("SUMMARY-OF-OLDER-TURNS");
            } else {
                chatBody.set(request);
                response = simpleResponse("Reply after compaction.");
            }
            respond(exchange, response);
        });
        // Compaction budget 20000-18000=2000 tokens; the trim's target is 20000-4096, far above
        // anything this fixture assembles, so only the rebuild moves the list.
        configureProvider(20000);
        ConfigService.set("chat.compactionReserveTokens", "18000");
        ConfigService.set("chat.compactionReserveTokensFloor", "18000");
        ConfigService.set("chat.compactionMinTurns", "2");
        ConfigService.set("chat.compactionKeepMessages", "4");

        var agent = createAgent("bearer-compaction-agent");
        var convo = ConversationService.create(agent, "web", "u-bearer-compact");

        // The bulk sits in the turns compaction will fold away, so the surviving tail stays well
        // inside the trim target and the rebuild is the only thing that moves the image's slot.
        var bulk = "z".repeat(3000);
        ConversationService.appendUserMessage(convo, "MARK-U1 " + bulk);
        ConversationService.appendAssistantMessage(convo, "MARK-A1 " + bulk, null);
        ConversationService.appendUserMessage(convo, "MARK-U2 " + bulk);
        ConversationService.appendAssistantMessage(convo, "MARK-A2 " + bulk, null);
        ConversationService.appendUserMessage(convo, "MARK-U3");
        ConversationService.appendAssistantMessage(convo, "MARK-A3", null);
        var imageTurn = ConversationService.appendUserMessage(convo, "MARK-U4");
        ConversationService.appendAssistantMessage(convo, "MARK-A4", null);
        persistImageAttachment(agent, convo, imageTurn);

        commit();
        var result = runOnVirtualThread(agent, convo, "MARK-NEW");

        assertEquals("Reply after compaction.", result.response(),
                "the turn must complete — a stale bearer index threw out of the rewrite");
        assertTrue(callCount.get() >= 2, "expected a summarize call then a chat call, got " + callCount.get());
        assertCaptionRidesTheImageTurn(chatBody.get());
    }

    /**
     * No compaction; the context-window trim drops the oldest turn instead. Every surviving
     * position shifts down by one, so an un-rebased bearer writes the image turn over the
     * assistant row that follows it.
     */
    @Test
    void captionLandsOnTheImageTurnWhenTheTrimDropsTheOldestTurn() throws Exception {
        var chatBody = new AtomicReference<String>();
        var callCount = new AtomicInteger();
        startLlmServer(exchange -> {
            var request = new String(exchange.getRequestBody().readAllBytes());
            callCount.incrementAndGet();
            chatBody.set(request);
            respond(exchange, simpleResponse("Reply after trim."));
        });
        // Reserve above the whole window makes the compaction budget negative, so shouldCompact is
        // false and the trim is the only step that rebuilds. Trim target is 24000-4096=19904
        // tokens, which has to clear the system prompt AND the tool schemas: the drop loop
        // subtracts message tokens only, so a target below those drops the whole history.
        configureProvider(24000);
        ConfigService.set("chat.compactionReserveTokens", "50000");
        ConfigService.set("chat.compactionReserveTokensFloor", "50000");

        var agent = createAgent("bearer-trim-agent");
        var convo = ConversationService.create(agent, "web", "u-bearer-trim");

        // All the weight in the first turn: dropping it alone gets under the target, so the trim
        // drops exactly one message and every later position shifts by one.
        ConversationService.appendUserMessage(convo, "MARK-U1 " + "z".repeat(120_000));
        ConversationService.appendAssistantMessage(convo, "MARK-A1", null);
        ConversationService.appendUserMessage(convo, "MARK-U2");
        ConversationService.appendAssistantMessage(convo, "MARK-A2", null);
        var imageTurn = ConversationService.appendUserMessage(convo, "MARK-U4");
        ConversationService.appendAssistantMessage(convo, "MARK-A4", null);
        persistImageAttachment(agent, convo, imageTurn);

        commit();
        var result = runOnVirtualThread(agent, convo, "MARK-NEW");

        assertEquals("Reply after trim.", result.response(),
                "the turn must complete — a stale bearer index threw out of the rewrite");
        assertEquals(1, callCount.get(), "no compaction round: exactly one LLM call");
        var body = chatBody.get();
        assertNotNull(body, "chat call body must have been captured");
        assertFalse(body.contains("MARK-U1"), "the trim must have dropped the oldest turn");
        assertTrue(body.contains("MARK-A1"),
                "the trim must have dropped exactly one message — retune the fixture if it dropped more: "
                        + messagesOf(body));
        assertCaptionRidesTheImageTurn(body);
    }

    /**
     * The load-bearing assertion for both cases: the caption block replaced the image turn and only
     * the image turn, and no {@code image_url} part survived anywhere — a rewrite that missed its
     * slot leaves the real image turn untouched and clobbers whichever message the stale index hit.
     */
    private static void assertCaptionRidesTheImageTurn(String chatBody) {
        assertNotNull(chatBody, "chat call body must have been captured");
        assertFalse(chatBody.contains("image_url"),
                "a non-vision model must receive no image_url part — the caption rewrite missed the image turn: "
                        + chatBody);
        var messages = messagesOf(chatBody);
        var captioned = new ArrayList<Integer>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).contains("[Image description: " + CAPTION)) captioned.add(i);
        }
        assertEquals(1, captioned.size(),
                "exactly one message must carry the caption block, got " + captioned + " in " + messages);
        int slot = captioned.getFirst();
        assertTrue(messages.get(slot).contains("MARK-U4"),
                "the caption must ride the turn that carries the image, not a neighbour: " + messages.get(slot));
        assertTrue(slot + 1 < messages.size() && messages.get(slot + 1).contains("\"assistant\"")
                        && messages.get(slot + 1).contains("MARK-A4"),
                "the image turn must still be followed by its own assistant reply: " + messages);
    }

    /** Each request message as raw JSON, so a String content and a parts array read the same way. */
    private static List<String> messagesOf(String chatBody) {
        var root = JsonParser.parseString(chatBody).getAsJsonObject();
        var out = new ArrayList<String>();
        for (var el : root.getAsJsonArray("messages")) out.add(el.toString());
        return out;
    }

    // ===== Helpers =====

    private void persistImageAttachment(Agent agent, Conversation convo, Message message) throws Exception {
        var uuid = UUID.randomUUID().toString();
        var relPath = "attachments/" + convo.id + "/" + uuid + ".png";
        var full = AgentService.acquireWorkspacePath(agent.name, relPath);
        Files.createDirectories(full.getParent());
        Files.write(full, new byte[]{1, 2, 3, 4});

        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = "cat.png";
        att.storagePath = agent.name + "/" + relPath;
        att.mimeType = "image/png";
        att.sizeBytes = 4L;
        att.kind = MessageAttachment.KIND_IMAGE;
        att.caption = CAPTION;
        att.save();
    }

    private Agent createAgent(String name) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "test-provider";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private void startLlmServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        llmServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", handler);
        llmServer.start();
        port = llmServer.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.getBytes().length);
        exchange.getResponseBody().write(body.getBytes());
        exchange.close();
    }

    /** Non-vision model — the caption downgrade is what puts the image into a text part. */
    private void configureProvider(int contextWindow) {
        ConfigService.set("provider.test-provider.baseUrl", "http://127.0.0.1:" + port);
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":%d,\"maxTokens\":256}]"
                        .formatted(contextWindow));
        llm.ProviderRegistry.refresh();
    }

    private static String simpleResponse(String content) {
        return """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(content.replace("\"", "\\\""));
    }

    private agents.AgentRunner.RunResult runOnVirtualThread(Agent agent, Conversation convo, String message)
            throws Exception {
        var agentId = agent.id;
        var convoId = convo.id;
        var resultRef = new AtomicReference<agents.AgentRunner.RunResult>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(agentId));
                var c = Tx.run(() -> (Conversation) Conversation.findById(convoId));
                resultRef.set(agents.AgentRunner.run(a, c, message));
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
