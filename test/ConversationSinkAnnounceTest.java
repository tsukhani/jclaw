import agents.ConversationSink;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import models.Conversation;
import models.Message;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationQueue;
import services.ConversationQueue.QueuedMessage;
import services.ConversationService;
import services.NotificationBus;
import services.Tx;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** A turn no chat tab streams tells an open chat page each time one of its commits writes rows. */
class ConversationSinkAnnounceTest extends UnitTest {

    @Test
    void aWrittenCommitIsAnnouncedOnceForItsConversation() {
        var conversation = seed("announce-written");
        var heard = new CopyOnWriteArrayList<Map<String, Object>>();
        var unsubscribe = listen(conversation.id, heard);
        try {
            onItsOwnThread(() -> {
                var gen = acquire(conversation);
                try {
                    var sink = ConversationSink.announcing(conversation, true, gen, null, List.of());
                    assertTrue(sink.commit(() -> sink.appendAssistantMessage("round one", null)));
                } finally {
                    ConversationQueue.releaseOwnership(conversation.id, gen);
                }
                return null;
            });
        } finally {
            unsubscribe.run();
        }

        assertEquals(List.of(Map.of("conversationId", conversation.id)), heard);
        assertEquals(1L, rowsIn(conversation.id));
    }

    @Test
    void eachCommitOfAnUnfencedAnnouncingSinkIsAnnounced() {
        var conversation = seed("announce-unfenced");
        var heard = new CopyOnWriteArrayList<Map<String, Object>>();
        var unsubscribe = listen(conversation.id, heard);
        try {
            onItsOwnThread(() -> {
                var sink = ConversationSink.announcing(conversation, false, 0, null, List.of());
                assertTrue(sink.commit(() -> sink.appendAssistantMessage("first", null)));
                assertTrue(sink.commit(() -> sink.appendAssistantMessage("second", null)));
                return null;
            });
        } finally {
            unsubscribe.run();
        }

        assertEquals(List.of(Map.of("conversationId", conversation.id), Map.of("conversationId", conversation.id)),
                heard);
        assertEquals(2L, rowsIn(conversation.id));
    }

    @Test
    void aCommitInsideAnOpenTransactionIsAnnouncedOnlyOnceTheTransactionCommits() {
        var conversation = seed("announce-in-tx");
        var heard = new CopyOnWriteArrayList<Map<String, Object>>();
        var unsubscribe = listen(conversation.id, heard);
        try {
            var sink = ConversationSink.announcing(conversation, false, 0, null, List.of());
            assertTrue(sink.commit(() -> sink.appendAssistantMessage("inside", null)));
            assertEquals(List.of(), heard, "a row the page cannot read yet is not announced");

            JPA.em().getTransaction().commit();
            JPA.em().getTransaction().begin();
        } finally {
            unsubscribe.run();
        }

        assertEquals(List.of(Map.of("conversationId", conversation.id)), heard);
    }

    @Test
    void aCommitRefusedAfterTheTurnLostTheConversationIsNotAnnounced() {
        var conversation = seed("announce-refused");
        var heard = new CopyOnWriteArrayList<Map<String, Object>>();
        var unsubscribe = listen(conversation.id, heard);
        try {
            onItsOwnThread(() -> {
                var gen = acquire(conversation);
                ConversationQueue.releaseOwnership(conversation.id, gen);
                var sink = ConversationSink.announcing(conversation, true, gen, null, List.of());
                assertFalse(sink.commit(() -> sink.appendAssistantMessage("late", null)));
                assertFalse(sink.commit(() -> sink.appendAssistantMessage("later", null)));
                return null;
            });
        } finally {
            unsubscribe.run();
        }

        assertEquals(List.of(), heard);
        assertEquals(0L, rowsIn(conversation.id));
    }

    @Test
    void aCommitAfterTheTurnWasStoppedIsRefusedAndNotAnnounced() {
        var conversation = seed("announce-stopped");
        var heard = new CopyOnWriteArrayList<Map<String, Object>>();
        var unsubscribe = listen(conversation.id, heard);
        var turnCancel = new AtomicBoolean();
        try {
            onItsOwnThread(() -> {
                var gen = acquire(conversation);
                try {
                    var sink = ConversationSink.announcing(conversation, true, gen, turnCancel, List.of());
                    assertTrue(sink.commit(() -> sink.appendAssistantMessage("before the stop", null)));
                    turnCancel.set(true);
                    assertFalse(sink.commit(() -> sink.appendAssistantMessage("after the stop", null)));
                } finally {
                    ConversationQueue.releaseOwnership(conversation.id, gen);
                }
                return null;
            });
        } finally {
            unsubscribe.run();
        }

        assertEquals(List.of(Map.of("conversationId", conversation.id)), heard);
        assertEquals(1L, rowsIn(conversation.id));
    }

    @Test
    void anEventClientIsToldOfACommitOnlyOnceItsTransactionCommits() {
        var conversation = seed("announce-sse");
        var lines = new CopyOnWriteArrayList<String>();
        var unsubscribe = NotificationBus.subscribe(line -> {
            if (line.contains("\"type\":\"" + NotificationBus.BUS_CONVERSATION_UPDATED + "\"")
                    && line.contains("\"conversationId\":" + conversation.id + "}")) {
                lines.add(line);
            }
        });
        try {
            var sink = ConversationSink.announcing(conversation, false, 0, null, List.of());
            assertTrue(sink.commit(() -> sink.appendAssistantMessage("inside", null)));
            NotificationBus.awaitDetachedForTest();
            assertEquals(List.of(), lines, "a row the page cannot read yet is not announced");

            JPA.em().getTransaction().commit();
            JPA.em().getTransaction().begin();
            NotificationBus.awaitDetachedForTest();
        } finally {
            unsubscribe.run();
        }

        assertEquals(List.of(updatedEvent(conversation.id)), events(lines));
    }

    @Test
    void aCommitDoesNotWaitForAnEventClientWhoseWritesBlock() {
        var conversation = seed("announce-blocked-client");
        var unblock = new CountDownLatch(1);
        var lines = new CopyOnWriteArrayList<String>();
        var unsubscribe = NotificationBus.subscribe(line -> {
            if (!line.contains("\"type\":\"" + NotificationBus.BUS_CONVERSATION_UPDATED + "\"")
                    || !line.contains("\"conversationId\":" + conversation.id + "}")) {
                return;
            }
            // Recorded before the wait: past the bus's deadline the wait is interrupted.
            lines.add(line);
            try {
                unblock.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        long commitMs;
        try {
            commitMs = onItsOwnThread(() -> {
                var gen = acquire(conversation);
                try {
                    var sink = ConversationSink.announcing(conversation, true, gen, null, List.of());
                    long start = System.nanoTime();
                    assertTrue(sink.commit(() -> sink.appendAssistantMessage("round one", null)));
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                } finally {
                    ConversationQueue.releaseOwnership(conversation.id, gen);
                }
            });
            unblock.countDown();
            NotificationBus.awaitDetachedForTest();
        } finally {
            unblock.countDown();
            unsubscribe.run();
        }

        assertTrue(commitMs < NotificationBus.LISTENER_TIMEOUT_MS / 2,
                "the commit waited " + commitMs + " ms on a blocked client");
        assertEquals(List.of(updatedEvent(conversation.id)), events(lines));
    }

    @Test
    void aCommitWhoseTransactionRollsBackIsNeverAnnounced() {
        var conversation = seed("announce-rollback");
        var heard = new CopyOnWriteArrayList<Map<String, Object>>();
        var lines = new CopyOnWriteArrayList<String>();
        var unsubscribe = listen(conversation.id, heard);
        var unsubscribeClient = NotificationBus.subscribe(line -> {
            if (line.contains("\"conversationId\":" + conversation.id + "}")) lines.add(line);
        });
        try {
            var sink = ConversationSink.announcing(conversation, false, 0, null, List.of());
            assertTrue(sink.commit(() -> sink.appendAssistantMessage("rolled back", null)));

            JPA.em().getTransaction().rollback();
            JPA.em().clear();
            JPA.em().getTransaction().begin();
            NotificationBus.awaitDetachedForTest();
        } finally {
            unsubscribe.run();
            unsubscribeClient.run();
        }

        assertEquals(List.of(), heard);
        assertEquals(List.of(), lines);
        assertEquals(0L, rowsIn(conversation.id));
    }

    @Test
    void theWebStreamedTurnsSinkIsNotAnnounced() {
        var conversation = seed("announce-streamed");
        var heard = new CopyOnWriteArrayList<Map<String, Object>>();
        var unsubscribe = listen(conversation.id, heard);
        try {
            onItsOwnThread(() -> {
                var gen = acquire(conversation);
                try {
                    var sink = new ConversationSink(conversation, gen, new AtomicBoolean());
                    assertTrue(sink.commit(() -> sink.appendAssistantMessage("streamed", null)));
                } finally {
                    ConversationQueue.releaseOwnership(conversation.id, gen);
                }
                return null;
            });
        } finally {
            unsubscribe.run();
        }

        assertEquals(List.of(), heard, "the tab streaming the turn already shows its rows");
        assertEquals(1L, rowsIn(conversation.id));
    }

    private static Runnable listen(Long conversationId, List<Map<String, Object>> heard) {
        return NotificationBus.subscribeEvents((type, data) -> {
            if (NotificationBus.BUS_CONVERSATION_UPDATED.equals(type) && conversationId.equals(data.get("conversationId"))) {
                heard.add(data);
            }
        });
    }

    private static JsonElement updatedEvent(Long conversationId) {
        return JsonParser.parseString(
                "{\"type\":\"conversation.updated\",\"data\":{\"conversationId\":" + conversationId + "}}");
    }

    /** Each SSE line's event, parsed: the order of its keys is not fixed. */
    private static List<JsonElement> events(List<String> lines) {
        return lines.stream().map(line -> {
            assertTrue(line.startsWith("data: ") && line.endsWith("\n\n"), line);
            return JsonParser.parseString(line.substring("data: ".length()).strip());
        }).toList();
    }

    private static long acquire(Conversation conversation) {
        long gen = ConversationQueue.tryAcquireOwnership(conversation.id,
                new QueuedMessage("go", "web", "admin", conversation.agent), null);
        assertNotEquals(ConversationQueue.NOT_ACQUIRED, gen, "test precondition: the conversation is idle");
        return gen;
    }

    private static Conversation seed(String name) {
        var unique = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        return onItsOwnThread(() -> Tx.run(() -> ConversationService.create(
                AgentService.create(unique, "announce-provider", "announce-model", null, null, false), "web", "admin")));
    }

    private static long rowsIn(Long conversationId) {
        return onItsOwnThread(() -> Tx.run(() -> Message.count("conversation.id = ?1", conversationId)));
    }

    private static <T> T onItsOwnThread(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(block.get());
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
