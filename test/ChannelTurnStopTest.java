import agents.AgentRunner;
import agents.ToolContext;
import agents.ToolRegistry;
import channels.ChannelStreamingSink;
import channels.TelegramChannel;
import channels.TelegramStreamingSink;
import channels.WhatsAppStreamingSink;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.SubagentRun;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationQueue;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import services.voice.VoiceTurnSpeaker;
import slash.Commands;
import tools.SubagentSpawnTool;
import tools.SubagentYieldTool;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * JF-197b: the operator's Stop, and a {@code /stop}, on a Telegram, Slack, WhatsApp or voice conversation. The stopped
 * turn keeps what it streamed, its channel's sink is told once, the next queued message runs, and a stop during
 * {@code subagent_yield} kills the runs the turn waited on. Each channel's sink turns the stop into its own wire calls,
 * which its own test pins; here a recording sink stands in for it.
 */
class ChannelTurnStopTest extends UnitTest {

    private static final String MODEL = "channel-stop-model";
    private static final String ASK = "What time is it?";
    private static final String NEXT = "And the date?";
    private static final String REPLY = "It is now.";
    private static final String QUEUED = "Your message has been queued and will be processed shortly.";
    private static final String NOTICE = "This reply was stopped.";
    private static final String DROPPED = "Dropped 1 message rows a stopped turn wrote";
    private static final String FINAL_REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"It is now."},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":120,"completion_tokens":4,"total_tokens":124}}""";

    private interface Responder {
        void respond(HttpExchange exchange) throws Exception;
    }

    private volatile Responder first;
    private final AtomicBoolean firstTaken = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer llmServer;
    private String provider;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        first = exchange -> LlmReplyFixture.send(exchange, FINAL_REPLY);
        provider = "channel-stop-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            try {
                exchange.getRequestBody().readAllBytes();
                if (firstTaken.compareAndSet(false, true)) first.respond(exchange);
                else LlmReplyFixture.send(exchange, FINAL_REPLY);
            } catch (Exception e) {
                exchange.close();
            }
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"Channel stop\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");
    }

    @AfterEach
    void tearDown() {
        try {
            if (llmServer != null) llmServer.stop(0);
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
            ProviderRegistry.refresh();
            CircuitBreakers.remove(LlmResilience.breakerName(provider));
        } finally {
            ToolRegistrySync.release();
        }
    }

    // --- a streamed channel turn ---

    @Test
    void aStopMidStreamKeepsWhatStreamedTellsThePersonOnceAndRunsTheQueuedMessage() throws Exception {
        var convo = seedConversation("telegram");
        var release = new CountDownLatch(1);
        first = heldAfterFirstToken(release);
        var turn = new RecordingSink();
        var queued = new RecordingSink();
        streamOnChannel(convo, ASK, turn);
        turn.awaitEvent("update Hel");
        streamOnChannel(convo, NEXT, queued);
        queued.awaitEnd();

        assertEquals(new ConversationQueue.Snapshot(true, 1, true, false,
                        List.of(new ConversationQueue.PendingView(NEXT, List.of(), 1))),
                ConversationQueue.snapshot(convo.id, "telegram"), "Stop shows, and keeps the message waiting");
        try {
            assertEquals(ConversationQueue.OperatorStop.STOPPED, stopFromTheChatPage(convo.id, "telegram"));
        } finally {
            release.countDown();
        }
        turn.awaitEnd();
        awaitIdle(convo.id);

        assertEquals(List.of("update Hel", "stop " + NOTICE), turn.events());
        assertEquals(List.of("seal " + QUEUED), queued.events());
        assertEquals(List.of("user " + ASK, "assistant " + ConversationService.STOP_MARKER_CONTENT, "user " + NEXT,
                "assistant " + REPLY), rows(convo.id));
    }

    @Test
    void aStopThatLandsAtTheRepliesCommitSendsTheNoticeAndNotTheReply() throws Exception {
        var convo = seedConversation("telegram");
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        first = exchange -> {
            reached.countDown();
            release.await(30, TimeUnit.SECONDS);
            LlmReplyFixture.send(exchange, FINAL_REPLY);
        };
        var turn = new RecordingSink();
        var stop = new AtomicReference<ConversationQueue.OperatorStop>();
        var seen = EventLogger.captureMatchingForTest(e -> convo.agent.name.equals(e.agentId()), heard -> {
            try {
                streamOnChannel(convo, ASK, turn);
                assertTrue(reached.await(30, TimeUnit.SECONDS), "test precondition: the turn reached the model");
                // Holding the queue's lock, so the reply's commit waits behind it, after the turn's last cancel check.
                var holder = Thread.ofPlatform().start(() -> ConversationQueue.commitIfOwner(convo.id,
                        ConversationQueue.currentGeneration(convo.id), null, () -> {
                            release.countDown();
                            try {
                                turn.awaitEvent("update " + REPLY);
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            stop.set(ConversationQueue.stopOperatorTurn(convo.id, "telegram"));
                        }));
                holder.join(30_000);
                turn.awaitEnd();
                awaitIdle(convo.id);
                // Logged in the turn's finally, after its sink was stopped.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (heard.stream().noneMatch(e -> e.message().startsWith(DROPPED)) && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                release.countDown();
            }
        });

        assertEquals(ConversationQueue.OperatorStop.STOPPED, stop.get());
        assertTrue(seen.stream().anyMatch(e -> e.message().startsWith(DROPPED)),
                "test precondition: the stop landed after the last check, so the reply's commit was refused: " + seen);
        assertEquals(List.of("update " + REPLY, "stop " + NOTICE), turn.events());
        assertEquals(List.of("user " + ASK, "assistant " + ConversationService.STOP_MARKER_CONTENT), rows(convo.id));
    }

    @Test
    void aStopAfterTheReplyIsSavedDeliversTheReplyAndNoNotice() throws Exception {
        var convo = seedConversation("telegram");
        var saved = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var turn = new RecordingSink();
        var stop = new AtomicReference<ConversationQueue.OperatorStop>();
        // Logged on the turn's thread once its reply is committed, before the reply is handed to the sink.
        EventLogger.captureMatchingForTest(e -> {
            if (convo.agent.name.equals(e.agentId()) && e.message().startsWith("Streaming complete")) {
                saved.countDown();
                await(stopped);
            }
            return false;
        }, _ -> {
            try {
                streamOnChannel(convo, ASK, turn);
                assertTrue(saved.await(30, TimeUnit.SECONDS), "test precondition: the turn saved its reply");
                stop.set(stopFromTheChatPage(convo.id, "telegram"));
                stopped.countDown();
                turn.awaitEnd();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                stopped.countDown();
            }
        });

        assertEquals(ConversationQueue.OperatorStop.STOPPED, stop.get(), "the turn still held its conversation");
        assertEquals(List.of("update " + REPLY, "seal " + REPLY), turn.events());
        assertEquals(List.of("user " + ASK, "assistant " + REPLY), rows(convo.id));
    }

    // --- /stop ---

    @Test
    void aStopSentOnTheChannelStopsTheTurnAnswersItselfAndKeepsTheQueue() throws Exception {
        var convo = seedConversation("telegram");
        var release = new CountDownLatch(1);
        first = heldAfterFirstToken(release);
        var turn = new RecordingSink();
        var queued = new RecordingSink();
        var stopReply = new RecordingSink();
        streamOnChannel(convo, ASK, turn);
        turn.awaitEvent("update Hel");
        streamOnChannel(convo, NEXT, queued);
        queued.awaitEnd();
        try {
            streamOnChannel(convo, "/stop", stopReply);
        } finally {
            release.countDown();
        }
        turn.awaitEnd();
        awaitIdle(convo.id);

        assertEquals(List.of("seal Stopped."), stopReply.events());
        assertEquals(List.of("update Hel", "stop null"), turn.events(), "the /stop's own reply is the only notice");
        assertEquals(List.of("user " + ASK, "assistant " + ConversationService.STOP_MARKER_CONTENT, "user " + NEXT,
                "assistant " + REPLY), rows(convo.id));
    }

    @Test
    void aStopSentOnTheChannelWithNothingRunningSaysSo() throws Exception {
        var convo = seedConversation("telegram");
        var stopReply = new RecordingSink();

        streamOnChannel(convo, "/stop", stopReply);

        assertEquals(List.of("seal " + Commands.NOTHING_TO_STOP), stopReply.events());
        assertEquals(0, calls.get());
    }

    @Test
    void aStopTypedInChatOnAChannelConversationIsTheStopButton() throws Exception {
        var convo = seedConversation("telegram");
        var release = new CountDownLatch(1);
        first = heldAfterFirstToken(release);
        var turn = new RecordingSink();
        streamOnChannel(convo, ASK, turn);
        turn.awaitEvent("update Hel");
        var reply = new AtomicReference<String>();
        try {
            onFreshThread(() -> reply.set(Commands.execute(Commands.Command.STOP, convo.agent, "web", "admin", convo,
                    null, true).responseText()));
        } finally {
            release.countDown();
        }
        turn.awaitEnd();

        assertEquals("Stopped.", reply.get());
        assertEquals(List.of("update Hel", "stop " + NOTICE), turn.events());
    }

    // --- voice ---

    @Test
    void aStopOfAVoiceTurnFlushesPlaybackEndsTheOverlaysTurnAndDropsTheQueuedUtterance() throws Exception {
        var convo = seedConversation("voice");
        var release = new CountDownLatch(1);
        first = heldAfterFirstToken(release);
        var cancel = new AtomicBoolean();
        var current = new AtomicReference<>(cancel);
        var overlay = new RecordingVoiceSink();
        var answered = new CountDownLatch(1);
        var queuedAnswer = new AtomicReference<String>();
        try (var _ = VoiceTurnSpeaker.endOnStop(current, cancel, 7, overlay)) {
            var tokens = new CountDownLatch(1);
            AgentRunner.runStreaming(convo.agent, convo.id, "voice", convo.peerId, ASK, cancel,
                    callbacks(_ -> tokens.countDown(), _ -> { }), null, null);
            assertTrue(tokens.await(30, TimeUnit.SECONDS), "test precondition: the answer is streaming");
            AgentRunner.runStreaming(convo.agent, convo.id, "voice", convo.peerId, NEXT, new AtomicBoolean(),
                    callbacks(_ -> { }, content -> {
                        queuedAnswer.set(content);
                        answered.countDown();
                    }), null, null);
            assertTrue(answered.await(30, TimeUnit.SECONDS), "test precondition: the utterance queued");
            assertEquals(new ConversationQueue.Snapshot(true, 1, true, true,
                            List.of(new ConversationQueue.PendingView(NEXT, List.of(), 1))),
                    ConversationQueue.snapshot(convo.id, "voice"), "Stop shows, and says it drops the utterance");
            try {
                assertEquals(ConversationQueue.OperatorStop.STOPPED, stopFromTheChatPage(convo.id, "voice"));
            } finally {
                release.countDown();
            }
            overlay.awaitFrames(2);
        }
        awaitIdle(convo.id);

        assertEquals(QUEUED, queuedAnswer.get());
        assertEquals(List.of(Map.of("type", "flush"), Map.of("type", "turn_complete", "turn", 7)), overlay.frames());
        assertNull(current.get(), "the stopped turn is no longer the overlay's");
        assertEquals(0, ConversationQueue.getQueueSize(convo.id));
        assertEquals(List.of("user " + ASK, "assistant " + ConversationService.STOP_MARKER_CONTENT), rows(convo.id),
                "the queued utterance never ran: its reply would never be spoken");
        assertEquals(1, calls.get());
    }

    // --- a stop while the turn waits on subagent_yield (JF-219) ---

    @Test
    void aStopDuringASyncTurnsYieldKillsTheRunItWaitedOn() throws Exception {
        var convo = seedConversation("web");
        var runId = seedRunningRun(convo);
        var started = new CountDownLatch(1);
        ToolRegistry.publish(List.of(new SubagentYieldTool(), holdAfterYield(runId, started)));
        first = exchange -> LlmReplyFixture.send(exchange, yieldAndHold(runId));
        var result = new AtomicReference<AgentRunner.RunResult>();
        var run = Thread.ofVirtual().start(() -> result.set(AgentRunner.run(convo.agent, convo, ASK)));

        assertTrue(started.await(30, TimeUnit.SECONDS), "test precondition: the turn yielded on the run");
        assertEquals(ConversationQueue.OperatorStop.STOPPED, stopFromTheChatPage(convo.id, "web"));
        run.join(60_000);
        assertFalse(run.isAlive(), "the stopped turn never ended");

        assertTrue(result.get().stopped());
        assertEquals(KILLED_FOR_THE_STOP, awaitRunState(runId, KILLED_FOR_THE_STOP));
        assertEquals(1, calls.get(), "the stopped turn made no round after the stop");
    }

    @Test
    void aStopDuringAStreamedTurnsYieldKillsTheRunItWaitedOn() throws Exception {
        var convo = seedConversation("web");
        var runId = seedRunningRun(convo);
        var started = new CountDownLatch(1);
        ToolRegistry.publish(List.of(new SubagentYieldTool(), holdAfterYield(runId, started)));
        first = exchange -> LlmReplyFixture.send(exchange, yieldAndHold(runId));
        var ended = new CountDownLatch(1);
        AgentRunner.runStreaming(convo.agent, convo.id, "web", convo.peerId, ASK, new AtomicBoolean(),
                new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, _ -> { }, _ -> { },
                        _ -> ended.countDown(), _ -> ended.countDown(), ended::countDown), null, null);

        assertTrue(started.await(30, TimeUnit.SECONDS), "test precondition: the turn yielded on the run");
        assertEquals(ConversationQueue.OperatorStop.STOPPED, stopFromTheChatPage(convo.id, "web"));
        assertTrue(ended.await(30, TimeUnit.SECONDS), "the stopped turn never ended");
        awaitIdle(convo.id);

        assertEquals(KILLED_FOR_THE_STOP, awaitRunState(runId, KILLED_FOR_THE_STOP));
        assertEquals(1, calls.get(), "the stopped turn made no round after the stop");
    }

    @Test
    void aStopAfterASyncYieldRoundsResultsAreInStillKillsTheRunItWaitsOn() throws Exception {
        var convo = seedConversation("web");
        var runId = seedRunningRun(convo);
        ToolRegistry.publish(List.of(new SubagentYieldTool()));
        first = exchange -> LlmReplyFixture.send(exchange, yieldOnly(runId));
        var stop = stopWhenTheRoundYields(convo, () -> AgentRunner.run(convo.agent, convo, ASK));

        assertEquals(ConversationQueue.OperatorStop.STOPPED, stop, "the turn still held its conversation");
        assertEquals(KILLED_FOR_THE_STOP, awaitRunState(runId, KILLED_FOR_THE_STOP));
    }

    @Test
    void aStopAfterAStreamedYieldRoundsResultsAreInStillKillsTheRunItWaitsOn() throws Exception {
        var convo = seedConversation("web");
        var runId = seedRunningRun(convo);
        ToolRegistry.publish(List.of(new SubagentYieldTool()));
        first = exchange -> LlmReplyFixture.send(exchange, yieldOnly(runId));
        var ended = new CountDownLatch(1);
        var stop = stopWhenTheRoundYields(convo, () -> {
            AgentRunner.runStreaming(convo.agent, convo.id, "web", convo.peerId, ASK, new AtomicBoolean(),
                    new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, _ -> { }, _ -> { },
                            _ -> ended.countDown(), _ -> ended.countDown(), ended::countDown), null, null);
            await(ended);
        });

        assertEquals(ConversationQueue.OperatorStop.STOPPED, stop, "the turn still held its conversation");
        assertEquals(KILLED_FOR_THE_STOP, awaitRunState(runId, KILLED_FOR_THE_STOP));
    }

    @Test
    void anInterruptModeArrivalDuringAYieldRoundKillsTheRunItWaitedOn() throws Exception {
        var convo = seedConversation("telegram");
        warmQueue(convo);
        interruptMode(convo);
        try {
            var runId = seedRunningRun(convo);
            var started = new CountDownLatch(1);
            ToolRegistry.publish(List.of(new SubagentYieldTool(), holdAfterYield(runId, started)));
            first = exchange -> LlmReplyFixture.send(exchange, yieldAndHold(runId));
            var turn = new RecordingSink();
            streamOnChannel(convo, ASK, turn);
            assertTrue(started.await(30, TimeUnit.SECONDS), "test precondition: the turn yielded on the run");

            streamOnChannel(convo, NEXT, new RecordingSink());
            turn.awaitEnd();
            awaitIdle(convo.id);

            assertEquals(KILLED_FOR_THE_STOP, awaitRunState(runId, KILLED_FOR_THE_STOP));
        } finally {
            ConfigService.delete("agent." + convo.agent.name + ".queue.mode");
        }
    }

    @Test
    void aStopAfterASyncYieldRoundReturnedBeforeTheReleaseStillKillsTheRunItWaitsOn() throws Exception {
        var convo = seedConversation("web");
        var runId = seedRunningRun(convo);
        ToolRegistry.publish(List.of(new SubagentYieldTool()));
        first = exchange -> LlmReplyFixture.send(exchange, yieldOnly(runId));
        // Logged once the round has returned yielded, before the turn lets go of the conversation.
        var stop = stopWhenLogged(convo, "Parent turn suspended via subagent_yield",
                () -> AgentRunner.run(convo.agent, convo, ASK));

        assertEquals(ConversationQueue.OperatorStop.STOPPED, stop, "the turn still held its conversation");
        assertEquals(KILLED_FOR_THE_STOP, awaitRunState(runId, KILLED_FOR_THE_STOP));
    }

    @Test
    void aStopAfterAStreamedYieldRoundReturnedBeforeTheReleaseStillKillsTheRunItWaitsOn() throws Exception {
        var convo = seedConversation("web");
        var runId = seedRunningRun(convo);
        ToolRegistry.publish(List.of(new SubagentYieldTool()));
        first = exchange -> LlmReplyFixture.send(exchange, yieldOnly(runId));
        var ended = new CountDownLatch(1);
        var stop = stopWhenLogged(convo, "Streaming parent turn suspended via subagent_yield", () -> {
            AgentRunner.runStreaming(convo.agent, convo.id, "web", convo.peerId, ASK, new AtomicBoolean(),
                    new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, _ -> { }, _ -> { },
                            _ -> ended.countDown(), _ -> ended.countDown(), ended::countDown), null, null);
            await(ended);
        });

        assertEquals(ConversationQueue.OperatorStop.STOPPED, stop, "the turn still held its conversation");
        assertEquals(KILLED_FOR_THE_STOP, awaitRunState(runId, KILLED_FOR_THE_STOP));
    }

    // --- decision 6: the stop sends the notice, before the next queued message runs ---

    @Test
    void aStopDuringASlowToolRoundTellsTheTelegramSenderOnceAndBeforeTheNextMessagesReply() throws Exception {
        var convo = seedConversation("telegram");
        var token = "channel-stop-" + System.nanoTime();
        bindTelegram(convo, token);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ToolRegistry.publish(List.of(slowStep(entered, release)));
        first = exchange -> LlmReplyFixture.send(exchange, oneCall("slow_step"));
        try (var telegram = new MockTelegramServer()) {
            telegram.start();
            TelegramChannel.installForTest(token, telegram.telegramUrl());
            try {
                EventLogger.captureMatchingForTest(e -> convo.agent.name.equals(e.agentId()), heard -> {
                    try {
                        streamOnChannel(convo, ASK, new TelegramStreamingSink(token, convo.peerId, convo.agent,
                                convo.id, "private", 4321, null));
                        assertTrue(entered.await(30, TimeUnit.SECONDS), "test precondition: the slow step runs");
                        var queued = new RecordingSink();
                        streamOnChannel(convo, NEXT, queued);
                        queued.awaitEnd();
                        try {
                            assertEquals(ConversationQueue.OperatorStop.STOPPED,
                                    stopFromTheChatPage(convo.id, "telegram"));
                            awaitSentTexts(telegram, 2);
                            assertEquals(List.of(NOTICE, REPLY), sentTexts(telegram),
                                    "while the stopped turn is still in its slow step");
                        } finally {
                            release.countDown();
                        }
                        awaitLogged(heard, CANCEL_CHECK_LOG);
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                });
                assertEquals(List.of(NOTICE, REPLY), sentTexts(telegram), "the stopped turn sent nothing more");
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    @Test
    void aStopDuringASlowToolRoundTellsTheWhatsAppSenderOnceAndBeforeTheNextMessagesReply() throws Exception {
        var convo = seedConversation("whatsapp");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ToolRegistry.publish(List.of(slowStep(entered, release)));
        first = exchange -> LlmReplyFixture.send(exchange, oneCall("slow_step"));
        var whatsapp = new RecordingWhatsApp();
        EventLogger.captureMatchingForTest(e -> convo.agent.name.equals(e.agentId()), heard -> {
            try {
                streamOnChannel(convo, ASK, new WhatsAppStreamingSink(whatsapp, convo.peerId, convo.agent, "wamid.A"));
                assertTrue(entered.await(30, TimeUnit.SECONDS), "test precondition: the slow step runs");
                var queued = new RecordingSink();
                streamOnChannel(convo, NEXT, queued);
                queued.awaitEnd();
                try {
                    assertEquals(ConversationQueue.OperatorStop.STOPPED, stopFromTheChatPage(convo.id, "whatsapp"));
                    // The next message's reply is sent once its row is saved, so the notice came first if it is here.
                    awaitRow(convo.id, "assistant " + REPLY);
                    assertEquals(List.of(NOTICE), whatsapp.sent(), "while the stopped turn is still in its slow step");
                } finally {
                    release.countDown();
                }
                awaitLogged(heard, CANCEL_CHECK_LOG);
                Thread.sleep(300);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });

        assertEquals(List.of(NOTICE), whatsapp.sent(), "the stopped turn sent nothing more");
    }

    @Test
    void aStopThatOwesANoticeReturnsAtOnceAndAMessageArrivingWhileItIsSentRunsAfterIt() throws Exception {
        var convo = seedConversation("telegram");
        var release = new CountDownLatch(1);
        first = heldAfterFirstToken(release);
        var turn = new RecordingSink();
        var notice = new CountDownLatch(1);
        turn.holdStop = notice;
        streamOnChannel(convo, ASK, turn);
        turn.awaitEvent("update Hel");
        var arrival = new RecordingSink();
        try {
            assertEquals(ConversationQueue.OperatorStop.STOPPED, stopFromTheChatPage(convo.id, "telegram"),
                    "the stop returned while its notice was still being sent");
            turn.awaitEvent("stop " + NOTICE);
            assertEquals(ConversationQueue.OperatorStop.IDLE, stopFromTheChatPage(convo.id, "telegram"),
                    "a second stop while the notice goes out finds nothing running");
            release.countDown();
            streamOnChannel(convo, NEXT, arrival);
            arrival.awaitEnd();

            assertEquals(List.of("seal " + QUEUED), arrival.events(), "it queued behind the notice");
            assertEquals(List.of("user " + ASK, "assistant " + ConversationService.STOP_MARKER_CONTENT),
                    rows(convo.id), "and has not run");
        } finally {
            notice.countDown();
            release.countDown();
        }
        awaitIdle(convo.id);

        assertEquals(List.of("update Hel", "stop " + NOTICE), turn.events());
        assertEquals(List.of("user " + ASK, "assistant " + ConversationService.STOP_MARKER_CONTENT, "user " + NEXT,
                "assistant " + REPLY), rows(convo.id));
    }

    // --- interrupt mode, the stopped turn's error, and its tool frames ---

    @Test
    void anInterruptModeArrivalOnlyCancelsTheStreamedChannelTurnAndSendsNoNotice() throws Exception {
        var convo = seedConversation("telegram");
        warmQueue(convo);
        interruptMode(convo);
        try {
            var release = new CountDownLatch(1);
            first = heldAfterFirstToken(release);
            var turn = new RecordingSink();
            var arrival = new RecordingSink();
            streamOnChannel(convo, ASK, turn);
            turn.awaitEvent("update Hel");
            try {
                streamOnChannel(convo, NEXT, arrival);
                arrival.awaitEnd();
            } finally {
                release.countDown();
            }
            turn.awaitEnd();
            awaitIdle(convo.id);

            assertEquals(List.of("update Hel", "update lo", "cancel"), turn.events());
            assertEquals(List.of("seal " + QUEUED), arrival.events());
            assertEquals(List.of("user " + ASK, "assistant " + ConversationService.STOP_MARKER_CONTENT,
                    "user " + NEXT, "assistant " + REPLY), rows(convo.id));
        } finally {
            ConfigService.delete("agent." + convo.agent.name + ".queue.mode");
        }
    }

    @Test
    void aStoppedChannelTurnThatThenFailsEndsStoppedNotWithTheErrorReply() throws Exception {
        // No provider: the turn fails, naming it, just after the prompt is assembled.
        var convo = seedConversation("telegram", "channel-stop-none-" + UUID.randomUUID().toString().substring(0, 8));
        var turn = new RecordingSink();
        var assembling = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var stop = new AtomicReference<ConversationQueue.OperatorStop>();
        EventLogger.captureMatchingForTest(e -> {
            if (convo.agent.name.equals(e.agentId()) && e.message().startsWith("Streaming: assembling prompt")) {
                assembling.countDown();
                await(stopped);
            }
            return false;
        }, _ -> {
            try {
                streamOnChannel(convo, ASK, turn);
                assertTrue(assembling.await(30, TimeUnit.SECONDS), "test precondition: the turn got past its checks");
                stop.set(stopFromTheChatPage(convo.id, "telegram"));
                stopped.countDown();
                turn.awaitEnd();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                stopped.countDown();
            }
        });

        assertEquals(ConversationQueue.OperatorStop.STOPPED, stop.get());
        assertEquals(List.of("stop " + NOTICE), turn.events());
    }

    @Test
    void aStopThatLandsAsAToolRoundsResultsReachTheSinkShowsNoMoreToolProgressAndOnlyTheNotice() throws Exception {
        // A stop while the tools run refuses the round's save, so no frame of it is ever sent; one after the save
        // meets the frames on their way to the sink. The first frame stops the turn, and the second must not show.
        var convo = seedConversation("telegram");
        ToolRegistry.publish(List.of(noop("noop_a"), noop("noop_b")));
        first = exchange -> LlmReplyFixture.send(exchange, twoNoops());
        var turn = new RecordingSink();
        var stop = new AtomicReference<ConversationQueue.OperatorStop>();
        turn.onFirstToolProgress = () -> stop.set(ConversationQueue.stopOperatorTurn(convo.id, "telegram"));

        streamOnChannel(convo, ASK, turn);
        turn.awaitEnd();
        awaitIdle(convo.id);

        assertEquals(ConversationQueue.OperatorStop.STOPPED, stop.get());
        assertEquals(List.of("tool noop_a", "stop " + NOTICE), turn.events(),
                "no tool progress and no fallback text after the stop");
        assertEquals(1, calls.get());
    }

    // --- a message that read the conversation's flag before a stop renewed it ---

    @Test
    void aMessageHoldingTheFlagAStopRenewedRunsOnAFreshOneAndGetsItsReply() throws Exception {
        var convo = seedConversation("telegram");
        warmQueue(convo);
        var release = new CountDownLatch(1);
        first = heldAfterFirstToken(release);
        var turn = new RecordingSink();
        streamOnChannel(convo, ASK, turn);
        turn.awaitEvent("update Hel");
        // What a message arriving now reads before its turn takes the conversation.
        var stale = ConversationQueue.cancellationFlag(convo.id);
        try {
            assertEquals(ConversationQueue.OperatorStop.STOPPED, stopFromTheChatPage(convo.id, "telegram"));
        } finally {
            release.countDown();
        }
        turn.awaitEnd();
        awaitIdle(convo.id);
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var ended = new CountDownLatch(1);

        onFreshThread(() -> AgentRunner.runStreaming(convo.agent, convo.id, "telegram", convo.peerId, NEXT, stale,
                new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, _ -> { }, _ -> { },
                        content -> {
                            events.add("complete " + content);
                            ended.countDown();
                        },
                        e -> {
                            events.add("error " + e.getMessage());
                            ended.countDown();
                        },
                        () -> {
                            events.add("cancel");
                            ended.countDown();
                        }), null, null));
        assertTrue(ended.await(30, TimeUnit.SECONDS), "the message never ended its turn");
        awaitIdle(convo.id);

        assertEquals(List.of("complete " + QUEUED), events, "never ended as stopped, so its channel is told nothing");
        assertEquals(List.of("user " + ASK, "assistant " + ConversationService.STOP_MARKER_CONTENT, "user " + NEXT,
                "assistant " + REPLY), rows(convo.id));
    }

    // --- helpers ---

    private static final RunState KILLED_FOR_THE_STOP =
            new RunState(SubagentRun.Status.KILLED, "Killed because the parent turn was stopped");

    /** The kill is the turn flag's cancel action, which runs off the stopping thread: polls up to 10 s. */
    private static RunState awaitRunState(Long runId, RunState expected) throws InterruptedException {
        var state = runState(runId);
        for (int i = 0; i < 200 && !expected.equals(state); i++) {
            Thread.sleep(50);
            state = runState(runId);
        }
        return state;
    }

    /**
     * Runs {@code turn} on its own thread and stops it from the chat page while it logs that its round yielded:
     * after the round's results are saved and its cancel checks are done, before the round ends.
     */
    private ConversationQueue.OperatorStop stopWhenTheRoundYields(Conversation convo, Runnable turn)
            throws InterruptedException {
        return stopWhenLogged(convo, "subagent_yield invoked", turn);
    }

    /** Runs {@code turn} on its own thread and stops it from the chat page while it logs {@code fragment}. */
    private ConversationQueue.OperatorStop stopWhenLogged(Conversation convo, String fragment, Runnable turn)
            throws InterruptedException {
        var yielding = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var stop = new AtomicReference<ConversationQueue.OperatorStop>();
        EventLogger.captureMatchingForTest(e -> {
            if (convo.agent.name.equals(e.agentId()) && e.message().contains(fragment)) {
                yielding.countDown();
                await(stopped);
            }
            return false;
        }, _ -> {
            var run = Thread.ofVirtual().start(turn);
            try {
                assertTrue(yielding.await(30, TimeUnit.SECONDS), "test precondition: never logged " + fragment);
                stop.set(stopFromTheChatPage(convo.id, "web"));
                stopped.countDown();
                run.join(60_000);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                stopped.countDown();
            }
            assertFalse(run.isAlive(), "the turn never ended");
        });
        return stop.get();
    }

    /** What a streamed turn logs at the cancel check that ends it. */
    private static final String CANCEL_CHECK_LOG = "Stream cancelled by client disconnect";

    /** A WhatsApp transport that records every text sent through it. */
    static final class RecordingWhatsApp implements channels.Channel {
        private final List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public String channelName() {
            return "whatsapp";
        }

        @Override public SendResult trySend(String peerId, String text) {
            sent.add(text);
            return SendResult.OK;
        }

        List<String> sent() {
            return List.copyOf(sent);
        }
    }

    /** A step that takes as long as the test says, whatever happens to its turn meanwhile. */
    private static ToolRegistry.Tool slowStep(CountDownLatch entered, CountDownLatch release) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return "slow_step"; }
            @Override public String description() { return "Takes as long as the test says."; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String argsJson, Agent agent) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return "slow result";
            }
        };
    }

    private static String oneCall(String tool) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[
                {"id":"call_1","type":"function","function":{"name":"%s","arguments":"{}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}""".formatted(tool);
    }

    private void bindTelegram(Conversation convo, String token) {
        var binding = new models.TelegramBinding();
        binding.agent = convo.agent;
        binding.botToken = token;
        binding.telegramUserId = convo.peerId;
        binding.transport = channels.ChannelTransport.POLLING;
        binding.enabled = true;
        binding.save();
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private static List<String> sentTexts(MockTelegramServer telegram) {
        return telegram.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .map(r -> com.google.gson.JsonParser.parseString(r.body()).getAsJsonObject().get("text").getAsString())
                .toList();
    }

    private static void awaitSentTexts(MockTelegramServer telegram, int count) throws InterruptedException {
        for (int i = 0; i < 200 && sentTexts(telegram).size() < count; i++) Thread.sleep(50);
    }

    private static void awaitRow(Long conversationId, String row) throws InterruptedException {
        for (int i = 0; i < 200 && !rows(conversationId).contains(row); i++) Thread.sleep(50);
        assertTrue(rows(conversationId).contains(row), "never saved: " + row);
    }

    private static void awaitLogged(List<EventLogger.Captured> seen, String prefix) throws InterruptedException {
        for (int i = 0; i < 200 && seen.stream().noneMatch(e -> e.message().startsWith(prefix)); i++) Thread.sleep(50);
        assertTrue(seen.stream().anyMatch(e -> e.message().startsWith(prefix)), "never logged: " + prefix);
    }

    /**
     * The conversation's queue state, as any earlier turn leaves it. A streamed channel turn reads the conversation's
     * flag before it takes the conversation, and with no state yet it reads one no other turn or interrupt shares.
     */
    private static void warmQueue(Conversation convo) {
        var earlier = new ConversationQueue.QueuedMessage("earlier", convo.channelType, convo.peerId, convo.agent);
        ConversationQueue.releaseOwnership(convo.id, ConversationQueue.tryAcquireOwnership(convo.id, earlier, null));
    }

    private static void interruptMode(Conversation convo) {
        ConfigService.set("agent." + convo.agent.name + ".queue.mode", "interrupt");
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private static String yieldOnly(long runId) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[
                {"id":"call_yield","type":"function","function":{"name":"subagent_yield","arguments":"{\\"runId\\":\\"%d\\"}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}""".formatted(runId);
    }

    private static String twoNoops() {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[
                {"id":"call_a","type":"function","function":{"name":"noop_a","arguments":"{}"}},
                {"id":"call_b","type":"function","function":{"name":"noop_b","arguments":"{}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}""";
    }

    private static ToolRegistry.Tool noop(String name) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Does nothing, for the test."; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String argsJson, Agent agent) {
                return "done";
            }
        };
    }

    /** A channel's sink, as the turn drives it: every call, in order. */
    static final class RecordingSink implements ChannelStreamingSink {
        private final List<String> events = new ArrayList<>(); // guarded by this
        private final CountDownLatch ended = new CountDownLatch(1);
        /** Run once, on the turn's thread, at the first tool progress the sink is shown. */
        volatile @Nullable Runnable onFirstToolProgress;
        /** When set, a stop of the sink holds until it opens, as a slow channel send does. */
        volatile @Nullable CountDownLatch holdStop;

        @Override public void startTypingHeartbeat() {
            // the cue is the channel's own
        }

        @Override public void update(String token) {
            record("update " + token);
        }

        @Override public void seal(String fullText) {
            record("seal " + fullText);
            ended.countDown();
        }

        @Override public void errorFallback(Exception e) {
            record("error " + e.getMessage());
            ended.countDown();
        }

        @Override public void cancel() {
            record("cancel");
            ended.countDown();
        }

        @Override public void stop(@Nullable String notice) {
            record("stop " + notice);
            var hold = holdStop;
            if (hold != null) await(hold);
            ended.countDown();
        }

        @Override public void toolProgress(@Nullable String toolName) {
            record("tool " + toolName);
            var action = onFirstToolProgress;
            onFirstToolProgress = null;
            if (action != null) action.run();
        }

        @Override public void collectGeneratedAttachments(List<String> attachmentUuids) {
            record("media " + attachmentUuids);
        }

        private synchronized void record(String event) {
            events.add(event);
            notifyAll();
        }

        synchronized List<String> events() {
            return List.copyOf(events);
        }

        synchronized void awaitEvent(String event) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!events.contains(event)) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                assertTrue(remainingMs > 0, "never heard " + event + "; heard " + events);
                wait(remainingMs);
            }
        }

        void awaitEnd() throws InterruptedException {
            assertTrue(ended.await(30, TimeUnit.SECONDS), "the turn never ended its sink; heard " + events());
        }
    }

    /** The voice overlay's socket: every frame sent to it. */
    static final class RecordingVoiceSink implements VoiceTurnSpeaker.Sink {
        private final List<Map<String, Object>> frames = new ArrayList<>(); // guarded by this

        @Override public boolean isOpen() {
            return true;
        }

        @Override public synchronized void send(Map<String, Object> frame) {
            frames.add(frame);
            notifyAll();
        }

        synchronized List<Map<String, Object>> frames() {
            return List.copyOf(frames);
        }

        synchronized void awaitFrames(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (frames.size() < count) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                assertTrue(remainingMs > 0, "the overlay got " + frames);
                wait(remainingMs);
            }
        }
    }

    private record RunState(SubagentRun.Status status, String outcome) {}

    private static RunState runState(Long runId) throws InterruptedException {
        var state = new AtomicReference<RunState>();
        onFreshThread(() -> state.set(Tx.run(() -> {
            SubagentRun run = SubagentRun.findById(runId);
            return new RunState(run.status, run.outcome);
        })));
        return state.get();
    }

    /** Runs a message in as a channel's inbound handler does, on a thread with no transaction of the test's. */
    private static void streamOnChannel(Conversation convo, String text, ChannelStreamingSink sink)
            throws InterruptedException {
        onFreshThread(() -> AgentRunner.processInboundForAgentStreaming(convo.agent, convo.channelType, convo.peerId,
                text, _ -> sink));
    }

    private static void onFreshThread(Runnable body) throws InterruptedException {
        var failure = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "the call never returned");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static AgentRunner.StreamingCallbacks callbacks(Consumer<String> onToken, Consumer<String> onComplete) {
        return new AgentRunner.StreamingCallbacks(_ -> { }, onToken, _ -> { }, _ -> { }, _ -> { }, onComplete,
                _ -> { }, () -> { });
    }

    /** A streamed reply whose first token goes out at once and whose rest waits for {@code release}. */
    private static Responder heldAfterFirstToken(CountDownLatch release) {
        return exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(deltaChunk("Hel"));
                out.flush();
                release.await(30, TimeUnit.SECONDS);
                out.write(deltaChunk("lo"));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static byte[] deltaChunk(String content) {
        return ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"}}]}\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** One round that yields on {@code runId} beside a call that holds the round open until the turn is stopped. */
    private static String yieldAndHold(long runId) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[
                {"id":"call_yield","type":"function","function":{"name":"subagent_yield","arguments":"{\\"runId\\":\\"%d\\"}"}},
                {"id":"call_hold","type":"function","function":{"name":"hold_after_yield","arguments":"{}"}}]},
                "finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}""".formatted(runId);
    }

    /** A tool that waits until the run is marked waited on, opens {@code started}, then waits for its turn's stop. */
    private static ToolRegistry.Tool holdAfterYield(long runId, CountDownLatch started) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return "hold_after_yield"; }
            @Override public String description() { return "Holds its round open until the turn is stopped, for the test."; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String argsJson, Agent agent) {
                var stopped = new CountDownLatch(1);
                try (var _ = ToolContext.onCancel(stopped::countDown)) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!Tx.run(() -> ((SubagentRun) SubagentRun.findById(runId)).yielded)
                            && System.nanoTime() < deadline) {
                        Thread.sleep(20);
                    }
                    started.countDown();
                    stopped.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return "held";
            }
        };
    }

    /** A run this conversation's agent started from it, still running. */
    private Long seedRunningRun(Conversation parent) {
        var child = AgentService.create(provider + "-child-" + UUID.randomUUID().toString().substring(0, 6),
                provider, MODEL);
        child.parentAgent = parent.agent;
        child.save();
        var childChat = ConversationService.create(child, SubagentSpawnTool.SUBAGENT_CHANNEL, null);
        childChat.parentConversation = parent;
        childChat.save();
        var run = new SubagentRun();
        run.parentAgent = parent.agent;
        run.childAgent = child;
        run.parentConversation = parent;
        run.childConversation = childChat;
        run.status = SubagentRun.Status.RUNNING;
        run.save();
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return run.id;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The operator's Stop, run as its route runs it: on a thread of its own, its marker committed before it returns. */
    private static ConversationQueue.OperatorStop stopFromTheChatPage(Long conversationId, String channelType)
            throws InterruptedException {
        var outcome = new AtomicReference<ConversationQueue.OperatorStop>();
        var stopper = Thread.ofPlatform().start(() ->
                outcome.set(ConversationQueue.stopOperatorTurn(conversationId, channelType)));
        stopper.join(10_000);
        assertFalse(stopper.isAlive(), "the stop never returned");
        return outcome.get();
    }

    /** The conversation's rows in order, each as its role and content, read in a transaction of their own. */
    private static List<String> rows(Long conversationId) throws InterruptedException {
        var rows = new AtomicReference<List<String>>();
        onFreshThread(() -> rows.set(Tx.run(() -> {
            List<Message> messages = Message.find("conversation.id = ?1 ORDER BY id", conversationId).fetch();
            return messages.stream().map(m -> m.role + " " + m.content).toList();
        })));
        return rows.get();
    }

    private static void awaitIdle(Long conversationId) throws InterruptedException {
        for (int i = 0; i < 200 && ConversationQueue.isBusy(conversationId); i++) Thread.sleep(50);
        assertFalse(ConversationQueue.isBusy(conversationId), "the conversation's turns never ended");
    }

    private Conversation seedConversation(String channelType) {
        return seedConversation(channelType, provider);
    }

    private Conversation seedConversation(String channelType, String modelProvider) {
        var agent = new Agent();
        agent.name = "channel-stop-agent-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = modelProvider;
        agent.modelId = MODEL;
        agent.enabled = true;
        agent.save();
        var convo = ConversationService.create(agent, channelType, channelType + "-peer-" + UUID.randomUUID());
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return convo;
    }
}
