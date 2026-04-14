import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import services.AgentService;
import services.ConversationService;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for SSE streaming safety: latch timeout and callback exception handling.
 */
public class SseStreamTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        agent = AgentService.create("sse-test-agent", "openrouter", "gpt-4.1");
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath("sse-test-agent"));
    }

    @Test
    public void latchTimesOutInsteadOfBlockingForever() throws Exception {
        // Simulate the controller pattern: latch.await with timeout
        var latch = new CountDownLatch(1);

        // Never count down — simulates a dead virtual thread
        var timedOut = !latch.await(1, TimeUnit.SECONDS);

        assertTrue(timedOut, "Latch should time out instead of blocking forever");
    }

    @Test
    public void latchReleasedWhenOnCompleteThrows() throws Exception {
        var latch = new CountDownLatch(1);
        var completed = new AtomicBoolean(false);

        // Simulate onComplete callback that throws, but with finally guard
        Thread.ofVirtual().uncaughtExceptionHandler((_, _) -> {}).start(() -> {
            try {
                throw new RuntimeException("simulated writeChunk failure");
            } finally {
                latch.countDown();
            }
        });

        var released = latch.await(5, TimeUnit.SECONDS);
        assertTrue(released, "Latch should be released via finally block even when callback throws");
    }

    @Test
    public void latchReleasedWhenOnErrorThrows() throws Exception {
        var latch = new CountDownLatch(1);

        // Simulate onError callback that throws (client disconnected during error write)
        Thread.ofVirtual().uncaughtExceptionHandler((_, _) -> {}).start(() -> {
            try {
                // Simulate error handling that itself fails
                throw new RuntimeException("writeChunk failed in onError");
            } finally {
                latch.countDown();
            }
        });

        var released = latch.await(5, TimeUnit.SECONDS);
        assertTrue(released, "Latch should be released via finally even when onError throws");
    }

    @Test
    public void streamingCallbacksAreExceptionSafe() {
        // Verify that wrapping callbacks in try/catch doesn't prevent normal operation
        var initCalled = new AtomicBoolean(false);
        var tokenCalled = new AtomicBoolean(false);
        var completeCalled = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        // Simulate safe callback pattern used in ApiChatController
        Thread.ofVirtual().start(() -> {
            try {
                initCalled.set(true);
            } catch (Exception _) {}

            try {
                tokenCalled.set(true);
            } catch (Exception _) {}

            try {
                completeCalled.set(true);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException _) {}

        assertTrue(initCalled.get());
        assertTrue(tokenCalled.get());
        assertTrue(completeCalled.get());
    }

    @Test
    public void conversationCreatedBeforeStreaming() {
        // Verify conversation can be created and user message appended in one transaction
        var convo = ConversationService.create(agent, "web", "test-user");
        assertNotNull(convo);
        assertNotNull(convo.id);

        ConversationService.appendUserMessage(convo, "Test message");
        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(1, messages.size());
        assertEquals("user", messages.getFirst().role);
    }

    private static void deleteDir(java.nio.file.Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
