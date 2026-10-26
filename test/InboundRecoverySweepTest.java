import agents.AgentRunner;
import agents.QueueDrainOrchestrator;
import channels.ChannelTransport;
import channels.TelegramChannel;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import jobs.TelegramStreamingRecoveryJob;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import models.PendingInbound;
import models.TelegramBinding;
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
import services.InboundRecoverySweep;
import services.Tx;
import tools.ConversationSendTool;
import utils.CircuitBreakers;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * JCLAW-1521: a channel message acknowledged to the platform and queued behind a busy turn survives a
 * restart, and a turn the restart cut off gets the interrupt note on its channel instead of a re-run.
 *
 * <p>Rows are moved to just after the JVM started and the sweep cut off a moment later: no other class
 * has written by then, and the boot job's own cutoff, the start itself, falls before them.
 */
class InboundRecoverySweepTest extends UnitTest {

    private static final String QUEUED = "Your message has been queued and will be processed shortly.";
    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"noted"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";

    private record UserRow(String content, List<String> attachmentIds) {}

    private record Row(String role, String content) {}

    private record Sent(String chatId, String text) {}

    private static final Instant PREVIOUS_PROCESS =
            Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).plusMillis(1);

    private final AtomicInteger llmCalls = new AtomicInteger();
    private HttpServer llmServer;
    private String provider;
    private final List<String> agentNames = new ArrayList<>();
    private final List<Long> conversationIds = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            llmCalls.incrementAndGet();
            LlmReplyFixture.send(exchange, REPLY);
        });
        llmServer.start();
        provider = "jclaw1521-" + UUID.randomUUID();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
    }

    @AfterEach
    void teardown() throws IOException {
        conversationIds.forEach(ConversationQueue::forgetForTest);
        for (var name : agentNames) ConfigService.delete("agent." + name + ".queue.mode");
        for (var suffix : List.of("baseUrl", "apiKey", "models")) {
            ConfigService.delete("provider." + provider + "." + suffix);
        }
        ProviderRegistry.refresh();
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
        llmServer.stop(0);
        for (var name : agentNames) deleteRecursive(AgentService.workspacePath(name));
    }

    @Test
    void aMessageQueuedBehindATurnRunsOnceAfterARestart() throws Exception {
        var agent = persistAgent();
        var convo = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        var photo = stage(agent, "photo-notes.txt", "what is in this picture");
        commit();
        hold(agent, convo);
        assertEquals(QUEUED, queueFromChannel(agent, convo, "are you still there?", photo));

        // The restart: the held turn and everything queued behind it existed only in memory.
        ConversationQueue.forgetForTest(convo.id);
        backdate(convo);
        var first = TelegramStreamingRecoveryJob.sweep(cutoff());
        awaitIdle(convo);

        assertEquals(List.of(new UserRow("are you still there?", List.of(photo.attachmentId()))), userRows(convo));
        assertEquals(List.of("noted"), assistantTexts(convo));
        assertEquals(1, llmCalls.get());
        assertEquals(1, first.requeued());
        assertEquals(List.of(), pendingTexts(convo), "the drain consumed the stored message with its USER row");

        var second = TelegramStreamingRecoveryJob.sweep(cutoff());
        awaitIdle(convo);

        assertEquals(0, second.requeued());
        assertEquals(1, llmCalls.get(), "the second sweep ran no turn");
        assertEquals(List.of(new UserRow("are you still there?", List.of(photo.attachmentId()))), userRows(convo));
    }

    @Test
    void anInterruptedTurnGetsTheNoteOnItsChannelOnceAndNoNewTurn() throws Exception {
        var agent = persistAgent();
        var peer = "1521" + (System.nanoTime() % 1_000_000);
        var convo = persistConversation(agent, "telegram", peer);
        var token = "jclaw1521-" + System.nanoTime();
        bindTelegram(agent, token, peer);
        appendUserRow(convo, "summarize my inbox");
        commit();
        backdate(convo);

        List<Sent> sent;
        try (var telegram = new MockTelegramServer()) {
            telegram.start();
            TelegramChannel.installForTest(token, telegram.telegramUrl());
            TelegramStreamingRecoveryJob.sweep(cutoff());
            TelegramStreamingRecoveryJob.sweep(cutoff());
            sent = sentMessages(telegram);
        } finally {
            TelegramChannel.clearForTest(token);
        }

        assertEquals(List.of(new Sent(peer, TelegramStreamingRecoveryJob.INTERRUPT_NOTE)), sent);
        assertEquals(List.of(new Row("user", "summarize my inbox"),
                new Row("assistant", TelegramStreamingRecoveryJob.INTERRUPT_NOTE)), rows(convo));
        assertEquals(0, llmCalls.get(), "the interrupted turn is not run again");
    }

    @Test
    void anEditedStreamingPlaceholderCarriesTheNoteAndNoSecondMessageIsSent() throws Exception {
        var agent = persistAgent();
        var peer = "1521" + (System.nanoTime() % 1_000_000);
        var convo = persistConversation(agent, "telegram", peer);
        var token = "jclaw1521-" + System.nanoTime();

        long edits;
        List<Sent> sent;
        try (var telegram = new MockTelegramServer()) {
            telegram.start();
            // JobLifecycleTest's recoverAll edits any placeholder it sees, so the token answers to the mock first.
            TelegramChannel.installForTest(token, telegram.telegramUrl());
            bindTelegram(agent, token, peer);
            appendUserRow(convo, "draft the newsletter");
            Conversation streaming = Conversation.findById(convo.id);
            streaming.activeStreamMessageId = 4242;
            streaming.activeStreamChatId = peer;
            streaming.save();
            commit();
            backdate(convo);

            TelegramStreamingRecoveryJob.sweep(cutoff());
            TelegramStreamingRecoveryJob.sweep(cutoff());
            edits = telegram.countRequests("editMessageText");
            sent = sentMessages(telegram);
        } finally {
            TelegramChannel.clearForTest(token);
        }

        assertEquals(1, edits, "the placeholder is edited once");
        assertEquals(List.of(), sent, "the edited placeholder already told the user");
        assertEquals(List.of(new Row("user", "draft the newsletter"),
                new Row("assistant", TelegramStreamingRecoveryJob.INTERRUPT_NOTE)), rows(convo));
    }

    @Test
    void aSubagentChildConversationGetsNoNoteSoTheUserHearsOnce() throws Exception {
        var agent = persistAgent();
        var peer = "1521" + (System.nanoTime() % 1_000_000);
        var parent = persistConversation(agent, "telegram", peer);
        // A session spawn naming its own agent inherits the parent's channel and peer, so it resolves the same bot.
        var child = persistConversation(agent, "telegram", peer);
        child.parentConversation = parent;
        child.save();
        var token = "jclaw1521-" + System.nanoTime();
        bindTelegram(agent, token, peer);
        appendUserRow(parent, "check the deploy logs");
        appendUserRow(child, "read the deploy log and list the errors");
        commit();
        backdate(parent);
        backdate(child);

        List<Sent> sent;
        try (var telegram = new MockTelegramServer()) {
            telegram.start();
            TelegramChannel.installForTest(token, telegram.telegramUrl());
            TelegramStreamingRecoveryJob.sweep(cutoff());
            sent = sentMessages(telegram);
        } finally {
            TelegramChannel.clearForTest(token);
        }

        assertEquals(List.of(new Sent(peer, TelegramStreamingRecoveryJob.INTERRUPT_NOTE)), sent);
        assertEquals(List.of(new Row("user", "check the deploy logs"),
                new Row("assistant", TelegramStreamingRecoveryJob.INTERRUPT_NOTE)), rows(parent));
        assertEquals(List.of(new Row("user", "read the deploy log and list the errors")), rows(child));
    }

    @Test
    void aChannelConversationWithNoPeerGetsNoNote() {
        var agent = persistAgent();
        // A task-started child takes the fire's channel and no peer: there is no one to tell.
        var convo = persistConversation(agent, "slack", null);
        appendUserRow(convo, "compile the weekly report");
        commit();
        backdate(convo);

        TelegramStreamingRecoveryJob.sweep(cutoff());

        assertEquals(List.of(new Row("user", "compile the weekly report")), rows(convo));
    }

    @Test
    void onlyAJvmRecordingTheAotCacheIsATrainingRun() {
        assertTrue(TelegramStreamingRecoveryJob.isAotTrainingRun(
                List.of("--enable-native-access=ALL-UNNAMED", "-XX:AOTCacheOutput=app.aot.new", "-Xmx2g")));
        assertTrue(TelegramStreamingRecoveryJob.isAotTrainingRun(
                List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=app.aotconf")));
        assertFalse(TelegramStreamingRecoveryJob.isAotTrainingRun(List.of("-XX:AOTCache=app.aot", "-Xmx2g")));
        assertFalse(TelegramStreamingRecoveryJob.isAotTrainingRun(List.of("-XX:AOTMode=off")));
        assertFalse(TelegramStreamingRecoveryJob.isAotTrainingRun(List.of()));
    }

    @Test
    void aWebChatConversationGetsNoNote() {
        var agent = persistAgent();
        var convo = persistConversation(agent, "web", "admin");
        appendUserRow(convo, "draft my reply");
        commit();
        backdate(convo);

        TelegramStreamingRecoveryJob.sweep(cutoff());

        assertEquals(List.of(new Row("user", "draft my reply")), rows(convo));
        assertEquals(0, llmCalls.get());
    }

    @Test
    void aTurnFromAfterTheCutoffGetsNoNote() {
        var agent = persistAgent();
        var convo = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        appendUserRow(convo, "still thinking about it");
        commit();

        TelegramStreamingRecoveryJob.sweep(cutoff());

        assertEquals(List.of(new Row("user", "still thinking about it")), rows(convo));
    }

    @Test
    void afterALongOutageOnlyARequestFromTheHourBeforeTheStopGetsTheNote() {
        assertEquals(Duration.ofHours(1), InboundRecoverySweep.NOTE_LOOKBACK, "a restart notes only the hour before the stop");
        var agent = persistAgent();
        // Web chat is never noted, so its row only marks when the previous run last wrote.
        var lastWrite = persistConversation(agent, "web", "admin");
        appendUserRow(lastWrite, "the last thing the previous run saved");
        var recent = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        appendUserRow(recent, "check my calendar");
        var stale = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        appendUserRow(stale, "check my calendar");
        commit();
        var stoppedAt = PREVIOUS_PROCESS.minus(Duration.ofHours(3));
        backdate(lastWrite, stoppedAt);
        backdate(recent, stoppedAt.minus(InboundRecoverySweep.NOTE_LOOKBACK).plusSeconds(60));
        backdate(stale, stoppedAt.minus(InboundRecoverySweep.NOTE_LOOKBACK).minusSeconds(60));

        TelegramStreamingRecoveryJob.sweep(cutoff());

        assertEquals(List.of(new Row("user", "check my calendar"),
                new Row("assistant", TelegramStreamingRecoveryJob.INTERRUPT_NOTE)), rows(recent));
        assertEquals(List.of(new Row("user", "check my calendar")), rows(stale));
        assertEquals(List.of(new Row("user", "the last thing the previous run saved")), rows(lastWrite));
        assertEquals(0, llmCalls.get(), "no turn is run again");
    }

    @Test
    void aDeliveredReportIsNoRequestAndGetsNoNote() {
        var agent = persistAgent();
        var convo = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        appendUserRow(convo, "here is what the child found");
        Message delivered = Message.find("conversation.id = ?1", convo.id).first();
        delivered.messageKind = ConversationSendTool.MESSAGE_KIND;
        delivered.save();
        commit();
        backdate(convo);

        TelegramStreamingRecoveryJob.sweep(cutoff());

        assertEquals(List.of(new Row("user", "here is what the child found")), rows(convo));
    }

    @Test
    void overflowDropsTheOldestMessageAndItsStoredRow() {
        var agent = persistAgent();
        var convo = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        commit();
        hold(agent, convo);

        for (int i = 0; i <= 20; i++) {
            assertFalse(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, "m" + i)));
        }

        assertEquals(20, ConversationQueue.getQueueSize(convo.id));
        assertEquals(IntStream.rangeClosed(1, 20).mapToObj(i -> "m" + i).toList(), pendingTexts(convo));
    }

    @Test
    void interruptModeKeepsOnlyTheReplacingMessagesRow() {
        var agent = persistAgent();
        ConfigService.set("agent." + agent.name + ".queue.mode", "interrupt");
        var convo = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        commit();
        hold(agent, convo);

        assertFalse(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, "first try")));
        assertFalse(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, "second try")));

        assertEquals(List.of("second try"), pendingTexts(convo));
    }

    @Test
    void aRestoredMessageNeitherCancelsALiveTurnNorDropsAMessageSentSinceTheRestart() throws Exception {
        var agent = persistAgent();
        var convo = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        commit();
        storeBeforeRestart(agent, convo, List.of("sent before the restart"));
        var liveTurnCancel = holdWithQueued(agent, convo, List.of("sent after the restart"));
        // Queued in interrupt mode, the fresh message would itself have cancelled the turn.
        interruptMode(agent);

        assertEquals(1, TelegramStreamingRecoveryJob.sweep(cutoff()).requeued());

        assertFalse(liveTurnCancel.get(), "the restored message did not cancel the live turn");
        assertEquals(2, ConversationQueue.getQueueSize(convo.id));
        assertEquals(List.of("sent before the restart", "sent after the restart"), pendingTexts(convo));
        finishHeldTurn(convo);
        assertEquals(List.of(new UserRow("sent before the restart", List.of()),
                new UserRow("sent after the restart", List.of())), userRows(convo));
        assertEquals(List.of(), pendingTexts(convo));
        assertEquals(2, llmCalls.get());
    }

    @Test
    void restoredMessagesKeepTheirOrderAheadOfAFreshOneInInterruptMode() throws Exception {
        var agent = persistAgent();
        var convo = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        commit();
        storeBeforeRestart(agent, convo, List.of("first before", "second before"));
        var liveTurnCancel = holdWithQueued(agent, convo, List.of("after"));
        interruptMode(agent);

        assertEquals(2, TelegramStreamingRecoveryJob.sweep(cutoff()).requeued());

        assertFalse(liveTurnCancel.get(), "no restored message cancelled the live turn");
        assertEquals(List.of("first before", "second before", "after"), pendingTexts(convo));
        finishHeldTurn(convo);
        assertEquals(List.of(new UserRow("first before", List.of()), new UserRow("second before", List.of()),
                new UserRow("after", List.of())), userRows(convo));
        assertEquals(List.of(), pendingTexts(convo));
    }

    @Test
    void inQueueModeRestoredMessagesRunBeforeOnesThatArrivedSinceTheRestart() throws Exception {
        var agent = persistAgent();
        var convo = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        commit();
        storeBeforeRestart(agent, convo, List.of("first before", "second before"));
        var liveTurnCancel = holdWithQueued(agent, convo, List.of("after"));

        assertEquals(2, TelegramStreamingRecoveryJob.sweep(cutoff()).requeued());

        assertFalse(liveTurnCancel.get());
        finishHeldTurn(convo);
        assertEquals(List.of(new UserRow("first before", List.of()), new UserRow("second before", List.of()),
                new UserRow("after", List.of())), userRows(convo));
        assertEquals(List.of(), pendingTexts(convo));
    }

    @Test
    void aRestoredMessageIsTheOldestSoAFullQueueDropsItWithItsRow() {
        var agent = persistAgent();
        var full = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        var roomy = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        commit();
        storeBeforeRestart(agent, full, List.of("sent before the restart"));
        storeBeforeRestart(agent, roomy, List.of("sent before the restart"));
        var twenty = IntStream.range(0, 20).mapToObj(i -> "m" + i).toList();
        holdWithQueued(agent, full, twenty);
        holdWithQueued(agent, roomy, twenty.subList(0, 19));

        assertEquals(2, TelegramStreamingRecoveryJob.sweep(cutoff()).requeued());

        assertEquals(20, ConversationQueue.getQueueSize(full.id));
        assertEquals(twenty, pendingTexts(full));
        assertEquals(List.of("m0"), drainedTexts(full));
        var roomyExpected = new ArrayList<String>();
        roomyExpected.add("sent before the restart");
        roomyExpected.addAll(twenty.subList(0, 19));
        assertEquals(20, ConversationQueue.getQueueSize(roomy.id));
        assertEquals(roomyExpected, pendingTexts(roomy));
        assertEquals(List.of("sent before the restart"), drainedTexts(roomy));
    }

    @Test
    void anOperatorStopDropsTheRowsOfTheMessagesItCleared() {
        var agent = persistAgent();
        var convo = persistConversation(agent, "telegram", "tg-1521-" + UUID.randomUUID());
        commit();
        assertNotEquals(ConversationQueue.NOT_ACQUIRED, ConversationQueue.tryAcquireOwnership(convo.id,
                channelMessage(agent, convo, "owner turn"), new AtomicBoolean()));
        assertFalse(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, "queued one")));
        assertFalse(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, "queued two")));

        assertTrue(ConversationQueue.stop(convo.id));

        assertEquals(List.of(), pendingTexts(convo));
    }

    @Test
    void aWebMessageQueuedBehindATurnIsNotStored() {
        var agent = persistAgent();
        var convo = persistConversation(agent, "web", "admin");
        commit();
        hold(agent, convo);

        assertFalse(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, "web follow-up")));

        assertEquals(List.of(), pendingTexts(convo));
    }

    @Test
    void deletingTheConversationOrTheAgentDeletesItsStoredMessages() {
        var kept = persistAgent();
        var keptConvo = persistConversation(kept, "telegram", "tg-1521-" + UUID.randomUUID());
        var deletedConvo = persistConversation(kept, "telegram", "tg-1521-" + UUID.randomUUID());
        var deletedAgent = persistAgent();
        var agentConvo = persistConversation(deletedAgent, "telegram", "tg-1521-" + UUID.randomUUID());
        commit();
        for (var convo : List.of(keptConvo, deletedConvo, agentConvo)) {
            var agent = convo == agentConvo ? deletedAgent : kept;
            hold(agent, convo);
            assertFalse(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, "waiting")));
        }

        ConversationService.deleteByIds(List.of(deletedConvo.id));
        AgentService.delete(Tx.run(() -> (Agent) Agent.findById(deletedAgent.id)));

        assertEquals(List.of("waiting"), pendingTexts(keptConvo));
        assertEquals(List.of(), pendingTexts(deletedConvo));
        assertEquals(List.of(), pendingTexts(agentConvo));
    }

    private Agent persistAgent() {
        var agent = new Agent();
        agent.name = "jclaw1521-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = provider;
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();
        agentNames.add(agent.name);
        return agent;
    }

    private Conversation persistConversation(Agent agent, String channelType, String peerId) {
        var convo = ConversationService.create(agent, channelType, peerId);
        conversationIds.add(convo.id);
        return convo;
    }

    private static void bindTelegram(Agent agent, String token, String ownerUserId) {
        var binding = new TelegramBinding();
        binding.agent = agent;
        binding.botToken = token;
        binding.telegramUserId = ownerUserId;
        binding.transport = ChannelTransport.POLLING;
        binding.enabled = true;
        binding.save();
    }

    private static void appendUserRow(Conversation convo, String text) {
        ConversationService.appendUserMessage((Conversation) Conversation.findById(convo.id), text);
    }

    private static ConversationQueue.QueuedMessage channelMessage(Agent agent, Conversation convo, String text) {
        return new ConversationQueue.QueuedMessage(text, convo.channelType, convo.peerId, agent);
    }

    private static AttachmentService.Input stage(Agent agent, String filename, String body) throws IOException {
        var id = UUID.randomUUID().toString();
        var staging = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        Files.createDirectories(staging);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        Files.write(staging.resolve(id + ".txt"), bytes);
        return new AttachmentService.Input(id, filename, "text/plain", bytes.length, MessageAttachment.KIND_FILE);
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private static Instant cutoff() {
        return PREVIOUS_PROCESS.plusMillis(1);
    }

    /** Moves the conversation's rows to before {@link #cutoff()}, as a previous process left them. */
    private static void backdate(Conversation convo) {
        backdate(convo, PREVIOUS_PROCESS);
    }

    private static void backdate(Conversation convo, Instant then) {
        var em = JPA.em();
        em.createNativeQuery("UPDATE message SET created_at = ?1 WHERE conversation_id = ?2")
                .setParameter(1, then).setParameter(2, convo.id).executeUpdate();
        em.createNativeQuery("UPDATE conversation SET updated_at = ?1 WHERE id = ?2")
                .setParameter(1, then).setParameter(2, convo.id).executeUpdate();
        em.createNativeQuery("UPDATE pending_inbound SET created_at = ?1 WHERE conversation_id = ?2")
                .setParameter(1, then).setParameter(2, convo.id).executeUpdate();
        commit();
        em.clear();
    }

    /** Stands in for a turn already running on the conversation. */
    private static void hold(Agent agent, Conversation convo) {
        assertTrue(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, "owner turn")));
    }

    /** Stores {@code texts} behind a turn of a process that then stopped, as the boot sweep finds them. */
    private static void storeBeforeRestart(Agent agent, Conversation convo, List<String> texts) {
        hold(agent, convo);
        for (var text : texts) assertFalse(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, text)));
        ConversationQueue.forgetForTest(convo.id);
        backdate(convo);
    }

    /** Holds the conversation for a live turn with {@code texts} queued behind it, returning the turn's cancel flag. */
    private static AtomicBoolean holdWithQueued(Agent agent, Conversation convo, List<String> texts) {
        hold(agent, convo);
        // A channel turn polls the conversation's flag, the one interrupt mode sets.
        var turnCancel = ConversationQueue.cancellationFlag(convo.id);
        for (var text : texts) assertFalse(ConversationQueue.tryAcquire(convo.id, channelMessage(agent, convo, text)));
        return turnCancel;
    }

    private static void interruptMode(Agent agent) {
        ConfigService.set("agent." + agent.name + ".queue.mode", "interrupt");
    }

    /** Stands in for the held turn ending: its drain runs what queued behind it, one turn at a time. */
    private static void finishHeldTurn(Conversation convo) throws InterruptedException {
        long generation = ConversationQueue.currentGeneration(convo.id);
        QueueDrainOrchestrator.runOwned(convo.id, generation, ConversationQueue.drain(convo.id, generation));
        awaitIdle(convo);
    }

    private static List<String> drainedTexts(Conversation convo) {
        return ConversationQueue.drain(convo.id).stream().map(ConversationQueue.QueuedMessage::text).toList();
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

    private static void awaitIdle(Conversation convo) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (ConversationQueue.isBusy(convo.id) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(ConversationQueue.isBusy(convo.id), "the re-queued turn finished and released the conversation");
    }

    private static List<String> pendingTexts(Conversation convo) {
        JPA.em().clear();
        return Tx.run(() -> {
            List<PendingInbound> pending = PendingInbound.find("conversation.id = ?1 ORDER BY id", convo.id).fetch();
            return pending.stream().map(p -> p.text).toList();
        });
    }

    private static List<Row> rows(Conversation convo) {
        JPA.em().clear();
        return Tx.run(() -> {
            List<Message> messages = Message.find("conversation.id = ?1 ORDER BY id", convo.id).fetch();
            return messages.stream().map(m -> new Row(m.role, m.content)).toList();
        });
    }

    private static List<String> assistantTexts(Conversation convo) {
        return rows(convo).stream().filter(r -> MessageRole.ASSISTANT.value.equals(r.role())).map(Row::content).toList();
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

    private static List<Sent> sentMessages(MockTelegramServer telegram) {
        return telegram.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .map(r -> JsonParser.parseString(r.body()).getAsJsonObject())
                .map(json -> new Sent(json.get("chat_id").getAsString(), json.get("text").getAsString()))
                .toList();
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
