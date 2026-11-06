import agents.AgentRunner;
import agents.ToolContext;
import agents.TurnBroadcast;
import agents.TurnFrames;
import com.google.gson.JsonParser;
import models.Agent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AcpCapabilityCatalog;
import services.AgentService;
import services.ConfigService;
import services.ConversationQueue;
import services.ConversationService;
import services.ConversationTurnStream;
import services.SubagentRegistry;
import services.Tx;
import tools.SubagentSpawnTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class SubagentChatBridgeTest extends UnitTest {

    private static final AtomicInteger IDS = new AtomicInteger();

    // Ids far from any row a concurrent class creates; the registry is process-wide.
    private static long freshConversationId() {
        return Long.MAX_VALUE - 1_000 - IDS.incrementAndGet();
    }

    private static AgentRunner.StreamingCallbacks callbacks(Object owner) {
        return new AgentRunner.StreamingCallbacks(_ -> owner.hashCode(), _ -> owner.hashCode(), _ -> owner.hashCode(),
                _ -> owner.hashCode(), _ -> owner.hashCode(), _ -> owner.hashCode(), _ -> owner.hashCode(),
                owner::hashCode);
    }

    @Test
    void aStoppedTurnClosingLateLeavesTheNextTurnsCallbacksRegistered() {
        long conversationId = freshConversationId();
        var stopped = callbacks("stopped turn");
        var next = callbacks("next turn");
        SubagentSpawnTool.registerChatCallbacks(conversationId, stopped);
        SubagentSpawnTool.registerChatCallbacks(conversationId, next);

        SubagentSpawnTool.unregisterChatCallbacks(conversationId, stopped);

        assertSame(next, SubagentSpawnTool.chatCallbacksForTest(conversationId));
        SubagentSpawnTool.unregisterChatCallbacks(conversationId, next);
        assertNull(SubagentSpawnTool.chatCallbacksForTest(conversationId));
    }

    @Test
    void anEqualButDistinctCallbackSetDoesNotUnregisterTheTurn() {
        long conversationId = freshConversationId();
        var registered = callbacks("same");
        SubagentSpawnTool.registerChatCallbacks(conversationId, registered);

        SubagentSpawnTool.unregisterChatCallbacks(conversationId,
                new AgentRunner.StreamingCallbacks(registered.onInit(), registered.onToken(), registered.onReasoning(),
                        registered.onStatus(), registered.onToolCall(), registered.onComplete(), registered.onError(),
                        registered.onCancel()));

        assertSame(registered, SubagentSpawnTool.chatCallbacksForTest(conversationId));
        SubagentSpawnTool.unregisterChatCallbacks(conversationId, registered);
    }

    // The chat stream is closed after 12 minutes without a frame, and a turn waiting on a subagent sends none.
    @Test
    void aWaitingTurnHearsFromItsSubagentOnlyWhileTheSubagentWorks() throws Exception {
        long conversationId = freshConversationId();
        long runId = freshConversationId();
        var statuses = new CopyOnWriteArrayList<String>();
        var watching = new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, statuses::add, _ -> { },
                _ -> { }, _ -> { }, () -> { });
        SubagentSpawnTool.registerChatCallbacks(conversationId, watching);
        SubagentRegistry.register(runId, new CompletableFuture<Void>());
        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(runId), Duration.ofMillis(50)));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (statuses.isEmpty() && System.nanoTime() < deadline) {
                SubagentRegistry.touch(runId);
                Thread.sleep(10);
            }
            assertEquals("Subagent still working", statuses.isEmpty() ? null : statuses.getFirst());

            Thread.sleep(200);
            int whenIdle = statuses.size();
            Thread.sleep(300);
            assertEquals(whenIdle, statuses.size(), "a subagent that does nothing sends nothing");
        } finally {
            relay.cancel(false);
            SubagentRegistry.unregister(runId);
            SubagentSpawnTool.unregisterChatCallbacks(conversationId, watching);
        }
    }

    @Test
    void aWaitingTurnHearsFromASubagentWhoseOwnSubagentWorks() throws Exception {
        long conversationId = freshConversationId();
        long child = freshConversationId();
        long grandchild = freshConversationId();
        var statuses = new CopyOnWriteArrayList<String>();
        var watching = new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, statuses::add, _ -> { },
                _ -> { }, _ -> { }, () -> { });
        SubagentSpawnTool.registerChatCallbacks(conversationId, watching);
        SubagentRegistry.register(child, new CompletableFuture<Void>());
        SubagentRegistry.register(grandchild, new CompletableFuture<Void>());
        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(child), Duration.ofMillis(50)));
        try {
            SubagentRegistry.callAsRun(child, () -> {
                try (var _ = SubagentRegistry.waitingOn(List.of(grandchild))) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (statuses.isEmpty() && System.nanoTime() < deadline) {
                        SubagentRegistry.touch(grandchild);
                        Thread.sleep(10);
                    }
                }
                return null;
            });
            assertEquals("Subagent still working", statuses.isEmpty() ? null : statuses.getFirst());
        } finally {
            relay.cancel(false);
            SubagentRegistry.unregister(child);
            SubagentRegistry.unregister(grandchild);
            SubagentSpawnTool.unregisterChatCallbacks(conversationId, watching);
        }
    }

    // --- JF-202: a run's frames go to its turn on the conversation's stream, whatever queues behind it ---

    private static final String STILL_WORKING = status("Subagent still working");
    private static final String MARK = "{\"type\":\"status\",\"content\":\"marker turn\"}";

    @Test
    void aWaitingTurnsWatchersHearItsSubagentStillWorkingOnlyWhileTheSubagentWorks() throws Exception {
        long conversationId = freshConversationId();
        long runId = freshConversationId();
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversationId, null, sink);
        var turn = ConversationTurnStream.begin(conversationId, ConversationTurnStreamTest.generation());
        SubagentRegistry.register(runId, new CompletableFuture<Void>());
        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(runId), Duration.ofMillis(50)));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (sink.raw().isEmpty() && System.nanoTime() < deadline) {
                SubagentRegistry.touch(runId);
                Thread.sleep(10);
            }
            assertEquals(List.of(STILL_WORKING), ConversationTurnStreamTest.data(sink.awaitData(STILL_WORKING)));

            Thread.sleep(200);
            int whenIdle = sink.raw().size();
            Thread.sleep(300);
            assertEquals(whenIdle, sink.raw().size(), "a subagent that does nothing sends nothing");
        } finally {
            relay.cancel(false);
            SubagentRegistry.unregister(runId);
            turn.end(MARK.getBytes(StandardCharsets.UTF_8));
            unsubscribe.run();
        }
    }

    @Test
    void aWaitingTurnsWatchersHearFromASubagentWhoseOwnSubagentWorks() throws Exception {
        long conversationId = freshConversationId();
        long child = freshConversationId();
        long grandchild = freshConversationId();
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversationId, null, sink);
        var turn = ConversationTurnStream.begin(conversationId, ConversationTurnStreamTest.generation());
        SubagentRegistry.register(child, new CompletableFuture<Void>());
        SubagentRegistry.register(grandchild, new CompletableFuture<Void>());
        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(child), Duration.ofMillis(50)));
        try {
            SubagentRegistry.callAsRun(child, () -> {
                try (var _ = SubagentRegistry.waitingOn(List.of(grandchild))) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (sink.raw().isEmpty() && System.nanoTime() < deadline) {
                        SubagentRegistry.touch(grandchild);
                        Thread.sleep(10);
                    }
                }
                return null;
            });
            assertEquals(STILL_WORKING, ConversationTurnStreamTest.data(sink.awaitData(STILL_WORKING)).getFirst());
        } finally {
            relay.cancel(false);
            SubagentRegistry.unregister(child);
            SubagentRegistry.unregister(grandchild);
            turn.end(MARK.getBytes(StandardCharsets.UTF_8));
            unsubscribe.run();
        }
    }

    @Test
    void aRelayStaysWithTheTurnThatWaitedAndSendsTheNextTurnNothing() throws Exception {
        long conversationId = freshConversationId();
        long runId = freshConversationId();
        var waiting = ConversationTurnStream.begin(conversationId, ConversationTurnStreamTest.generation());
        SubagentRegistry.register(runId, new CompletableFuture<Void>());
        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(runId), Duration.ofMillis(50)));
        var sink = new ConversationTurnStreamTest.RecordingSink();
        Runnable unsubscribe = () -> { };
        try {
            waiting.stop();
            unsubscribe = ConversationTurnStream.subscribe(conversationId, null, sink);
            var next = ConversationTurnStream.begin(conversationId, ConversationTurnStreamTest.generation());
            for (int i = 0; i < 30; i++) {
                SubagentRegistry.touch(runId);
                Thread.sleep(10);
            }
            next.end(MARK.getBytes(StandardCharsets.UTF_8));

            assertEquals(List.of(MARK), ConversationTurnStreamTest.data(sink.awaitData(MARK)));
        } finally {
            relay.cancel(false);
            SubagentRegistry.unregister(runId);
            unsubscribe.run();
        }
    }

    @Test
    void aRelayInAChatStreamTurnReachesItsTabAndTheConversationStreamOnceEach() throws Exception {
        long conversationId = freshConversationId();
        long runId = freshConversationId();
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversationId, null, sink);
        var turn = ConversationTurnStream.begin(conversationId, ConversationTurnStreamTest.generation());
        var tabStatuses = new CopyOnWriteArrayList<String>();
        var tab = TurnBroadcast.alsoPublishing(conversationId, new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { },
                _ -> { }, tabStatuses::add, _ -> { }, _ -> { }, _ -> { }, () -> { }));
        SubagentSpawnTool.registerChatCallbacks(conversationId, tab);
        SubagentRegistry.register(runId, new CompletableFuture<Void>());
        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(runId), Duration.ofMillis(50)));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (tabStatuses.size() < 2 && System.nanoTime() < deadline) {
                SubagentRegistry.touch(runId);
                Thread.sleep(10);
            }
        } finally {
            relay.cancel(false);
            SubagentRegistry.unregister(runId);
            SubagentSpawnTool.unregisterChatCallbacks(conversationId, tab);
        }
        turn.end(MARK.getBytes(StandardCharsets.UTF_8));

        assertTrue(tabStatuses.size() >= 2, "the tab heard the wait: " + tabStatuses);
        assertEquals(List.of("Subagent still working"), tabStatuses.stream().distinct().toList());
        var heard = ConversationTurnStreamTest.data(sink.awaitData(MARK));
        assertEquals(tabStatuses.size(), heard.stream().filter(STILL_WORKING::equals).count(),
                "the stream got each status the tab got, once: " + heard);
        assertEquals(tabStatuses.size() + 1, heard.size(), "and nothing else: " + heard);
        unsubscribe.run();
    }

    @Test
    void aRelayStillReachesTheConversationStreamOnceAfterAQueuedTabDroppedTheRunningTabsRegistration()
            throws Exception {
        long conversationId = freshConversationId();
        long runId = freshConversationId();
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversationId, null, sink);
        var turn = ConversationTurnStream.begin(conversationId, ConversationTurnStreamTest.generation());
        var running = TurnBroadcast.alsoPublishing(conversationId, callbacks("running tab"));
        var queued = TurnBroadcast.alsoPublishing(conversationId, callbacks("queued tab"));
        SubagentSpawnTool.registerChatCallbacks(conversationId, running);
        // A second chat stream request that queued registers at its init and drops its own entry at its canned end.
        SubagentSpawnTool.registerChatCallbacks(conversationId, queued);
        SubagentSpawnTool.unregisterChatCallbacks(conversationId, queued);
        assertNull(SubagentSpawnTool.chatCallbacksForTest(conversationId), "test precondition: no tab is registered");
        SubagentRegistry.register(runId, new CompletableFuture<Void>());
        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(runId), Duration.ofMillis(50)));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (sink.raw().isEmpty() && System.nanoTime() < deadline) {
                SubagentRegistry.touch(runId);
                Thread.sleep(10);
            }
        } finally {
            relay.cancel(false);
            SubagentRegistry.unregister(runId);
        }
        int relayed = sink.raw().size();
        turn.end(MARK.getBytes(StandardCharsets.UTF_8));

        var heard = ConversationTurnStreamTest.data(sink.awaitData(MARK));
        assertTrue(relayed >= 1, "the watchers heard the wait");
        assertEquals(java.util.Collections.nCopies(relayed, STILL_WORKING), heard.subList(0, relayed));
        assertEquals(List.of(MARK), heard.subList(relayed, heard.size()));
        unsubscribe.run();
    }

    @Test
    void nothingIsRelayedWhenTheConversationHasNoTurnOpen() {
        long conversationId = freshConversationId();

        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(freshConversationId()), Duration.ofMillis(50)));

        assertTrue(relay.isDone());
        assertFalse(ConversationTurnStream.holdsState(conversationId));
    }

    @Test
    void aCodingRunsStepsReachItsTurnsWatchersWithASecondRequestQueuedBehindIt() throws Exception {
        var nonce = "bridge-" + UUID.randomUUID().toString().substring(0, 8);
        var harness = Files.createTempFile("jclaw-bridge-harness-", ".sh");
        Files.writeString(harness, "#!/bin/sh\ncat >/dev/null\n"
                + "printf '%s\\n' '" + nonce + "-0'\nprintf '%s\\n' '" + nonce + "-1'\nprintf '%s\\n' '" + nonce + "-2'\n");
        assertTrue(harness.toFile().setExecutable(true, false));
        Long conversationId = null;
        try {
            ConfigService.set(SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
            ConfigService.set(SubagentSpawnTool.ACP_HARNESS_KEY, "generic");
            ConfigService.set(SubagentSpawnTool.ACP_MODE_KEY, "json");
            var parent = AgentService.create("bridge-parent-" + nonce, "bridge-provider-" + nonce, "bridge-model");
            parent.enabled = true;
            parent.acpAllowed = true;
            parent.save();
            var conversation = ConversationService.create(parent, "web", "admin-" + nonce);
            conversationId = conversation.id;
            JPA.em().getTransaction().commit();
            JPA.em().getTransaction().begin();

            long running = ConversationQueue.tryAcquireOwnership(conversation.id,
                    new ConversationQueue.QueuedMessage("Write it", "web", conversation.peerId, parent), null);
            assertTrue(running >= 0, "test precondition: the running turn holds the conversation");
            var turn = TurnBroadcast.owning(conversation.id, running);
            var sink = new ConversationTurnStreamTest.RecordingSink();
            var unsubscribe = ConversationTurnStream.subscribe(conversation.id, null, sink);
            try {
                var queued = new CountDownLatch(1);
                var reply = new AtomicReference<String>();
                AgentRunner.runStreaming(parent, conversation.id, "web", conversation.peerId, "And now?",
                        new AtomicBoolean(), new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, _ -> { },
                                _ -> { }, content -> {
                                    reply.set(content);
                                    queued.countDown();
                                }, _ -> queued.countDown(), queued::countDown), null);
                assertTrue(queued.await(30, TimeUnit.SECONDS));
                assertEquals("Your message has been queued and will be processed shortly.", reply.get(),
                        "test precondition: the second request queued behind the running turn");

                var spawned = spawnInTurn(conversation.id, parent.id, "{\"task\":\"write it\",\"runtime\":\"acp\"}");
                assertEquals("COMPLETED", JsonParser.parseString(spawned).getAsJsonObject().get("status").getAsString(),
                        spawned);
                turn.complete("done");

                var done = new String(TurnFrames.json(TurnFrames.complete("done")), StandardCharsets.UTF_8);
                assertEquals(List.of(status(nonce + "-0"), status(nonce + "-1"), status(nonce + "-2"), done),
                        ConversationTurnStreamTest.data(sink.awaitData(done)));
            } finally {
                unsubscribe.run();
            }
        } finally {
            if (conversationId != null) ConversationQueue.forgetForTest(conversationId);
            for (var key : new String[] {SubagentSpawnTool.ACP_COMMAND_KEY, SubagentSpawnTool.ACP_HARNESS_KEY,
                    SubagentSpawnTool.ACP_MODE_KEY}) {
                ConfigService.delete(key);
            }
            Files.deleteIfExists(harness);
        }
    }

    @Test
    void aStartedTurnsCodingRunStreamsItsTokensAndToolCallsToTheWatchers() throws Exception {
        Assumptions.assumeTrue(AcpCapabilityCatalog.stdioAcpLaunchCommand("pi") == null,
                "a pi-acp adapter on PATH would route this run over ACP instead of the wrapper");
        var nonce = "pi-" + UUID.randomUUID().toString().substring(0, 8);
        var harness = piHarness(nonce, null);
        try (var chat = PiChat.open(nonce, harness)) {
            var spawned = spawnInTurn(chat.conversationId, chat.parentId, "{\"task\":\"write it\",\"runtime\":\"acp\"}");
            assertEquals("COMPLETED", JsonParser.parseString(spawned).getAsJsonObject().get("status").getAsString(),
                    spawned);
            chat.turn.complete("done");

            var heard = ConversationTurnStreamTest.data(chat.sink.awaitData(complete("done")));
            assertEquals(6, heard.size(), "four tokens, one tool call, the end: " + heard);
            var first = JsonParser.parseString(heard.get(0)).getAsJsonObject();
            assertEquals(java.util.Set.of("type", "content", "timestamp"), first.keySet(), "the turn's first token is stamped");
            assertEquals(nonce + "-Hel", first.get("content").getAsString());
            assertEquals(List.of(delta("lo "), WRITE_CALL, delta("wor"), delta("ld"), complete("done")),
                    heard.subList(1, heard.size()));
        } finally {
            Files.deleteIfExists(harness);
        }
    }

    @Test
    void aCodingRunOutlivingItsStoppedTurnSendsTheNextTurnNoneOfItsFrames() throws Exception {
        Assumptions.assumeTrue(AcpCapabilityCatalog.stdioAcpLaunchCommand("pi") == null,
                "a pi-acp adapter on PATH would route this run over ACP instead of the wrapper");
        var nonce = "pi-" + UUID.randomUUID().toString().substring(0, 8);
        var go = Files.createTempDirectory("jclaw-bridge-go-").resolve("go");
        var harness = piHarness(nonce, go);
        try (var chat = PiChat.open(nonce, harness)) {
            var result = new AtomicReference<String>();
            var failure = new AtomicReference<Exception>();
            var spawn = Thread.ofVirtual().start(() -> {
                try {
                    result.set(spawnInTurn(chat.conversationId, chat.parentId,
                            "{\"task\":\"write it\",\"runtime\":\"acp\"}"));
                } catch (Exception e) {
                    failure.set(e);
                }
            });
            chat.sink.awaitUntil(events -> !events.isEmpty(), "heard the run's first token");
            chat.turn.stopped();
            var next = ConversationTurnStream.begin(chat.conversationId, Long.MAX_VALUE);
            Files.writeString(go, "go");
            spawn.join(60_000);
            if (failure.get() != null) throw failure.get();
            assertEquals("COMPLETED", JsonParser.parseString(result.get()).getAsJsonObject().get("status").getAsString(),
                    result.get());
            next.end(MARK.getBytes(StandardCharsets.UTF_8));

            var heard = ConversationTurnStreamTest.data(chat.sink.awaitData(MARK));
            assertEquals(List.of("token", "stopped", "status"), heard.stream()
                    .map(f -> JsonParser.parseString(f).getAsJsonObject().get("type").getAsString()).toList(),
                    "the run's first token, the stop, then only the next turn's own frame: " + heard);
            assertEquals(MARK, heard.getLast());
        } finally {
            Files.writeString(go, "go");
            Files.deleteIfExists(harness);
            Files.deleteIfExists(go);
        }
    }

    @Test
    void aRelayOutlivingItsStoppedTurnSendsTheNextTurnsTabNothing() throws Exception {
        long conversationId = freshConversationId();
        long runId = freshConversationId();
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversationId, null, sink);
        var waiting = ConversationTurnStream.begin(conversationId, ConversationTurnStreamTest.generation());
        var waitingStatuses = new CopyOnWriteArrayList<String>();
        var waitingTab = TurnBroadcast.alsoPublishing(conversationId, new AgentRunner.StreamingCallbacks(_ -> { },
                _ -> { }, _ -> { }, waitingStatuses::add, _ -> { }, _ -> { }, _ -> { }, () -> { }));
        SubagentSpawnTool.registerChatCallbacks(conversationId, waitingTab);
        SubagentRegistry.register(runId, new CompletableFuture<Void>());
        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(runId), Duration.ofMillis(50)));
        var nextStatuses = new CopyOnWriteArrayList<String>();
        AgentRunner.StreamingCallbacks nextTab = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (waitingStatuses.isEmpty() && System.nanoTime() < deadline) {
                SubagentRegistry.touch(runId);
                Thread.sleep(10);
            }
            assertEquals(List.of("Subagent still working"), waitingStatuses.stream().distinct().toList(),
                    "test precondition: the relay reaches the waiting turn's tab");
            waiting.stop();
            SubagentSpawnTool.unregisterChatCallbacks(conversationId, waitingTab);
            var next = ConversationTurnStream.begin(conversationId, ConversationTurnStreamTest.generation());
            nextTab = TurnBroadcast.alsoPublishing(conversationId, new AgentRunner.StreamingCallbacks(_ -> { },
                    _ -> { }, _ -> { }, nextStatuses::add, _ -> { }, _ -> { }, _ -> { }, () -> { }));
            SubagentSpawnTool.registerChatCallbacks(conversationId, nextTab);
            for (int i = 0; i < 30; i++) {
                SubagentRegistry.touch(runId);
                Thread.sleep(10);
            }
            next.end(MARK.getBytes(StandardCharsets.UTF_8));

            assertEquals(List.of(), nextStatuses, "the next turn's tab hears nothing of the stopped turn's wait");
            var heard = ConversationTurnStreamTest.data(sink.awaitData(MARK));
            var stopped = heard.indexOf("{\"type\":\"stopped\"}");
            assertEquals(List.of(MARK), heard.subList(stopped + 1, heard.size()), "nor do its watchers: " + heard);
        } finally {
            relay.cancel(false);
            SubagentRegistry.unregister(runId);
            SubagentSpawnTool.unregisterChatCallbacks(conversationId, nextTab);
            unsubscribe.run();
        }
    }

    private static final String WRITE_CALL = new String(TurnFrames.json(TurnFrames.toolCall(new AgentRunner.ToolCallEvent(
            null, "write", "harness", "{\"type\":\"tool_execution_start\",\"toolName\":\"write\"}", "", null, null,
            List.of()))), StandardCharsets.UTF_8);

    /**
     * A pi JSONL harness: a token, then (once {@code go} exists, when one is given) another, a {@code write} tool
     * call and two more. The wait gives up after 30 s, so a test that fails first leaves no harness behind for long.
     */
    private static java.nio.file.Path piHarness(String nonce, java.nio.file.@Nullable Path go) throws Exception {
        var script = new StringBuilder("#!/bin/sh\ncat >/dev/null\n").append(piToken(nonce + "-Hel"));
        if (go != null) {
            script.append("i=0; while [ ! -f '").append(go).append("' ] && [ $i -lt 600 ]; do sleep 0.05; i=$((i+1)); done\n");
        }
        script.append(piToken("lo "))
                .append("printf '%s\\n' '{\"type\":\"tool_execution_start\",\"toolName\":\"write\"}'\n")
                .append(piToken("wor")).append(piToken("ld"));
        var harness = Files.createTempFile("jclaw-bridge-pi-", ".sh");
        Files.writeString(harness, script.toString());
        assertTrue(harness.toFile().setExecutable(true, false));
        return harness;
    }

    /** One pi JSONL text_delta line printing {@code text}, as {@code HarnessStreamingRunTest} writes one. */
    private static String piToken(String text) {
        return "printf '%s\\n' '{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"delta\":\""
                + text + "\"}}'\n";
    }

    /**
     * A web chat of an acp-allowed agent whose turn holds its conversation, as a started turn would, watched by
     * {@link #sink}, with the pi harness configured; closing it releases the conversation and the config.
     */
    private static final class PiChat implements AutoCloseable {
        final Long conversationId;
        final Long parentId;
        final TurnBroadcast turn;
        final ConversationTurnStreamTest.RecordingSink sink = new ConversationTurnStreamTest.RecordingSink();
        private final Runnable unsubscribe;

        private PiChat(Long conversationId, Long parentId, long running) {
            this.conversationId = conversationId;
            this.parentId = parentId;
            // Subscribed before the turn begins, so the watchers hear it from its start.
            this.unsubscribe = ConversationTurnStream.subscribe(conversationId, null, sink);
            this.turn = TurnBroadcast.owning(conversationId, running);
        }

        static PiChat open(String nonce, java.nio.file.Path harness) {
            ConfigService.set(SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
            ConfigService.set(SubagentSpawnTool.ACP_HARNESS_KEY, "pi");
            ConfigService.set(SubagentSpawnTool.ACP_MODE_KEY, "json");
            var parent = AgentService.create("bridge-pi-" + nonce, "bridge-provider-" + nonce, "bridge-model");
            parent.enabled = true;
            parent.acpAllowed = true;
            parent.save();
            var conversation = ConversationService.create(parent, "web", "admin-" + nonce);
            JPA.em().getTransaction().commit();
            JPA.em().getTransaction().begin();
            long running = ConversationQueue.tryAcquireOwnership(conversation.id,
                    new ConversationQueue.QueuedMessage("Write it", "web", conversation.peerId, parent), null);
            assertTrue(running >= 0, "test precondition: the running turn holds the conversation");
            return new PiChat(conversation.id, parent.id, running);
        }

        @Override
        public void close() {
            unsubscribe.run();
            ConversationQueue.forgetForTest(conversationId);
            for (var key : new String[] {SubagentSpawnTool.ACP_COMMAND_KEY, SubagentSpawnTool.ACP_HARNESS_KEY,
                    SubagentSpawnTool.ACP_MODE_KEY}) {
                ConfigService.delete(key);
            }
        }
    }

    private static String delta(String content) {
        return new String(TurnFrames.chunk(new byte[0], TurnFrames.Chunk.TOKEN, content, new byte[0]), StandardCharsets.UTF_8);
    }

    private static String complete(String content) {
        return new String(TurnFrames.json(TurnFrames.complete(content)), StandardCharsets.UTF_8);
    }

    private static String status(String content) {
        return new String(TurnFrames.json(TurnFrames.status(content)), StandardCharsets.UTF_8);
    }

    /** The spawn a tool call in {@code conversationId}'s turn makes: on its own thread, outside any transaction. */
    private static String spawnInTurn(Long conversationId, Long parentAgentId, String args) throws Exception {
        var result = new AtomicReference<String>();
        var failure = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var parent = Tx.run(() -> (Agent) Agent.findById(parentAgentId));
                result.set(ToolContext.withScope(conversationId, null, () -> new SubagentSpawnTool().execute(args, parent)));
            } catch (Exception e) {
                failure.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "the coding run must finish within 60 s");
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    @Test
    void nothingIsRelayedOutsideAChatTurn() {
        assertTrue(SubagentSpawnTool.relayActivityForTest(List.of(freshConversationId()), Duration.ofMillis(50)).isDone());
    }
}
