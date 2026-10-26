import agents.AgentRunner;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.AttachmentService;
import services.ConfigService;
import services.ConversationQueue;
import services.ConversationService;
import services.Tx;
import utils.CircuitBreakers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1514: a channel message that arrives while its conversation is running a turn is queued, and
 * the drained turn must save its staged attachments on the USER row exactly as an unqueued turn does.
 */
class QueuedAttachmentDrainTest extends UnitTest {

    private static final String QUEUED = "Your message has been queued and will be processed shortly.";
    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"noted"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";

    private record UserRow(String content, List<String> attachmentIds) {}

    private HttpServer llmServer;
    private String provider;
    private final List<String> agentNames = new ArrayList<>();
    private final List<Long> conversationIds = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> LlmReplyFixture.send(exchange, REPLY));
        llmServer.start();
        provider = "jclaw1514-" + UUID.randomUUID();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
    }

    @AfterEach
    void teardown() throws IOException {
        conversationIds.forEach(ConversationQueue::releaseOwnership);
        for (var suffix : List.of("baseUrl", "apiKey", "models")) {
            ConfigService.delete("provider." + provider + "." + suffix);
        }
        ProviderRegistry.refresh();
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
        llmServer.stop(0);
        for (var name : agentNames) deleteRecursive(AgentService.workspacePath(name));
    }

    @Test
    void aChannelMessageQueuedBehindARunningTurnKeepsItsAttachment() throws Exception {
        var agent = persistAgent();
        var convo = persistConversation(agent);
        var photo = stage(agent, "photo-notes.txt", "what is in this picture");
        commit();
        hold(agent, convo);

        assertEquals(QUEUED, queueFromChannel(agent, convo, "see attached", photo));
        finishOwnerTurn(agent, convo);

        assertEquals(List.of(new UserRow("owner turn", List.of()),
                        new UserRow("see attached", List.of(photo.attachmentId()))),
                userRows(convo));
        assertFalse(staged(agent, photo), "the staged file left staging");
        assertTrue(finalized(agent, convo, photo), "the staged file moved into the conversation's directory");
    }

    @Test
    void aSyncRunQueuedBehindARunningTurnKeepsItsAttachment() throws Exception {
        var agent = persistAgent();
        var convo = persistConversation(agent);
        var voice = stage(agent, "voice-note.txt", "a transcript of the voice note");
        commit();
        hold(agent, convo);

        assertEquals(QUEUED, AgentRunner.run(agent, convo, "listen to this", List.of(voice)).response());
        finishOwnerTurn(agent, convo);

        assertEquals(List.of(new UserRow("owner turn", List.of()),
                        new UserRow("listen to this", List.of(voice.attachmentId()))),
                userRows(convo));
        assertFalse(staged(agent, voice), "the staged file left staging");
        assertTrue(finalized(agent, convo, voice), "the staged file moved into the conversation's directory");
    }

    @Test
    void collectModeSavesEveryQueuedAttachmentOnOneRowInArrivalOrder() throws Exception {
        var agent = persistAgent();
        ConfigService.set("agent." + agent.name + ".queue.mode", "collect");
        var convo = persistConversation(agent);
        var first = stage(agent, "first.txt", "the first attachment");
        var second = stage(agent, "second.txt", "the second attachment");
        commit();
        hold(agent, convo);

        assertEquals(QUEUED, queueFromChannel(agent, convo, "first", first));
        assertEquals(QUEUED, queueFromChannel(agent, convo, "second", second));
        finishOwnerTurn(agent, convo);

        assertEquals(List.of(new UserRow("owner turn", List.of()),
                        new UserRow("[Queued messages while agent was busy]\n"
                                + "\n---\nMessage 1:\nfirst\n"
                                + "\n---\nMessage 2:\nsecond\n",
                                List.of(first.attachmentId(), second.attachmentId()))),
                userRows(convo));
        assertFalse(staged(agent, first), "the first staged file left staging");
        assertFalse(staged(agent, second), "the second staged file left staging");
    }

    @Test
    void interruptModeSavesTheReplacingMessagesAttachmentOnTheNextTurn() throws Exception {
        var agent = persistAgent();
        ConfigService.set("agent." + agent.name + ".queue.mode", "interrupt");
        var convo = persistConversation(agent);
        var replaced = stage(agent, "replaced.txt", "the replaced attachment");
        var replacing = stage(agent, "replacing.txt", "the replacing attachment");
        commit();
        hold(agent, convo);

        assertEquals(QUEUED, queueFromChannel(agent, convo, "first try", replaced));
        assertEquals(QUEUED, queueFromChannel(agent, convo, "second try", replacing));
        assertTrue(ConversationQueue.cancellationFlag(convo.id).get(), "the arrivals signalled the running turn");
        finishOwnerTurn(agent, convo);

        assertEquals(List.of(new UserRow("owner turn", List.of()),
                        new UserRow("second try", List.of(replacing.attachmentId()))),
                userRows(convo));
        assertFalse(staged(agent, replacing), "the replacing message's staged file left staging");
        assertTrue(finalized(agent, convo, replacing), "the replacing message's file moved into the conversation");
    }

    @Test
    void aYieldResumeDrainAppendsNothingAndSavesNoAttachment() throws Exception {
        var agent = persistAgent();
        var convo = persistConversation(agent);
        Tx.run(() -> ConversationService.appendMessage(
                (Conversation) Conversation.findById(convo.id), MessageRole.USER,
                "[subagent reply] the child's findings", null, null, null));
        commit();
        hold(agent, convo);

        assertEquals(QUEUED, AgentRunner.runYieldResume(agent, convo).response());
        finishOwnerTurn(agent, convo);

        assertEquals(List.of(new UserRow("[subagent reply] the child's findings", List.of()),
                        new UserRow("owner turn", List.of())),
                userRows(convo));
        assertEquals(0L, (long) Tx.run(() -> MessageAttachment.count("message.conversation.id = ?1", convo.id)));
    }

    private Agent persistAgent() {
        var agent = new Agent();
        agent.name = "jclaw1514-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = provider;
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();
        agentNames.add(agent.name);
        return agent;
    }

    private Conversation persistConversation(Agent agent) {
        var convo = ConversationService.create(agent, "telegram", "tg-1514-" + UUID.randomUUID());
        conversationIds.add(convo.id);
        return convo;
    }

    private static AttachmentService.Input stage(Agent agent, String filename, String body) throws IOException {
        var id = UUID.randomUUID().toString();
        var staging = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        Files.createDirectories(staging);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        Files.write(staging.resolve(id + ".txt"), bytes);
        return new AttachmentService.Input(id, filename, "text/plain", bytes.length, MessageAttachment.KIND_FILE);
    }

    private static boolean staged(Agent agent, AttachmentService.Input input) {
        return Files.exists(AgentService.acquireWorkspacePath(agent.name,
                "attachments/staging/" + input.attachmentId() + ".txt"));
    }

    private static boolean finalized(Agent agent, Conversation convo, AttachmentService.Input input) {
        return Files.exists(AgentService.acquireWorkspacePath(agent.name,
                "attachments/" + convo.id + "/" + input.attachmentId() + ".txt"));
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    /** Stands in for a turn already running on the conversation. */
    private static void hold(Agent agent, Conversation convo) {
        assertTrue(ConversationQueue.tryAcquire(convo.id,
                new ConversationQueue.QueuedMessage("owner turn", convo.channelType, convo.peerId, agent)));
    }

    /** Sends a message the way a channel's inbound turn does, returning what the sender is told. */
    private static String queueFromChannel(Agent agent, Conversation convo, String text,
                                           AttachmentService.Input attachment) throws InterruptedException {
        var completed = new AtomicReference<String>();
        var error = new AtomicReference<Exception>();
        var done = new CountDownLatch(1);
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {},
                content -> { completed.set(content); done.countDown(); },
                e -> { error.set(e); done.countDown(); },
                done::countDown);
        AgentRunner.runStreaming(agent, convo.id, convo.channelType, convo.peerId, text,
                new AtomicBoolean(false), cb, null, List.of(attachment));
        assertTrue(done.await(30, TimeUnit.SECONDS), "the channel turn answers within 30s");
        assertNull(error.get(), "the channel turn did not fail");
        return completed.get();
    }

    /** Runs the held turn to completion; its end drains the queue, and this waits for the drained turn. */
    private static void finishOwnerTurn(Agent agent, Conversation convo) throws InterruptedException {
        var error = new AtomicReference<Throwable>();
        var owner = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(agent.id));
                var c = Tx.run(() -> (Conversation) Conversation.findById(convo.id));
                AgentRunner.runWithOwnedQueue(a, c, "owner turn");
            } catch (Throwable t) {
                error.set(t);
            }
        });
        owner.join(30_000);
        assertFalse(owner.isAlive(), "the owner's turn completes within 30s");
        if (error.get() != null) throw new AssertionError("the owner's turn threw", error.get());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (ConversationQueue.isBusy(convo.id) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(ConversationQueue.isBusy(convo.id), "the drained turn finished and released the conversation");
    }

    private static List<UserRow> userRows(Conversation convo) {
        JPA.em().clear();
        return Tx.run(() -> {
            List<Message> messages = Message.find("conversation.id = ?1 AND role = ?2 ORDER BY id",
                    convo.id, MessageRole.USER.value).fetch();
            var rows = new ArrayList<UserRow>();
            for (var message : messages) {
                List<MessageAttachment> attachments = MessageAttachment.find("message = ?1 ORDER BY id", message).fetch();
                rows.add(new UserRow(message.content, attachments.stream().map(a -> a.uuid).toList()));
            }
            return rows;
        });
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
