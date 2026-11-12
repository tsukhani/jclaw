import jobs.TelegramStreamingRecoveryJob;
import models.Agent;
import models.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConversationService;
import services.RestartService;
import services.Tx;
import utils.AppClock;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JF-354 end to end: the record a restart's hand-off writes reaches the boot job, which tells the conversation that
 * asked. A functional class, because functional classes run alone: the record is one file, and a unit class beside
 * this one could take or delete it.
 */
class RestartNoticeBootTest extends FunctionalTest {

    private static final Instant JVM_START = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        RestartService.spawnerForTest = plan -> { };
    }

    @AfterEach
    void clearSeams() throws IOException {
        RestartService.spawnerForTest = null;
        RestartService.resetHandOffForTest();
        Files.deleteIfExists(RestartService.requestFile());
    }

    @Test
    void theBootJobTellsTheConversationThatAskedForTheRestartAndRemovesTheRecord() throws Exception {
        // The previous run: its rows and its restart, both from before this JVM started.
        var before = JVM_START.minusSeconds(1);
        var conversationId = commit(() -> {
            var agent = new Agent();
            agent.name = "jf354-boot-" + UUID.randomUUID().toString().substring(0, 8);
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var conversation = ConversationService.create(agent, "web", "admin");
            ConversationService.appendUserMessage(conversation, "restart JClaw");
            return conversation.id;
        });
        commit(() -> {
            JPA.em().createNativeQuery("UPDATE message SET created_at = ?1 WHERE conversation_id = ?2")
                    .setParameter(1, before).setParameter(2, conversationId).executeUpdate();
            JPA.em().createNativeQuery("UPDATE conversation SET updated_at = ?1 WHERE id = ?2")
                    .setParameter(1, before).setParameter(2, conversationId).executeUpdate();
            return null;
        });
        AppClock.callWith(Clock.fixed(before, ZoneOffset.UTC), () -> RestartService.requestRestart("agent main", conversationId));

        onFreshThread(() -> {
            new TelegramStreamingRecoveryJob().doJob();
            return null;
        });

        assertEquals(List.of("restart JClaw", "JClaw is back after the restart you asked for."), contents(conversationId));
        assertFalse(Files.exists(RestartService.requestFile()), "a second boot tells nobody");
    }

    private static List<String> contents(Long conversationId) {
        return commit(() -> {
            List<Message> messages = Message.find("conversation.id = ?1 ORDER BY id", conversationId).fetch();
            return messages.stream().map(m -> m.content).toList();
        });
    }

    /** The test body already holds a transaction that {@code Tx.run} would join, so seeds commit on a thread of their own. */
    private static <T> T commit(Supplier<T> block) {
        return onFreshThread(() -> Tx.run(block::get));
    }

    private static <T> T onFreshThread(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var thread = Thread.ofPlatform().start(() -> {
            try {
                ref.set(block.get());
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
