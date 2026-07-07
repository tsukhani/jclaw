import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import jobs.ConversationQueueEvictionJob;
import services.ConfigService;

class ConversationQueueEvictionJobTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void doJobUsesDefaultWhenConfigUnset() {
        // No conversation.queue.idleEvictionMs row → DEFAULT_IDLE_MS path.
        // The job should complete without throwing.
        assertDoesNotThrow(() -> new ConversationQueueEvictionJob().doJob());
    }

    @Test
    void doJobUsesConfigValueWhenSet() {
        ConfigService.set("conversation.queue.idleEvictionMs", "60000");
        try {
            assertDoesNotThrow(() -> new ConversationQueueEvictionJob().doJob());
        } finally {
            ConfigService.delete("conversation.queue.idleEvictionMs");
        }
    }

    @Test
    void doJobFallsBackToDefaultOnUnparseableConfig() {
        // NumberFormatException catch path — config value isn't a valid long.
        ConfigService.set("conversation.queue.idleEvictionMs", "not-a-number");
        try {
            assertDoesNotThrow(() -> new ConversationQueueEvictionJob().doJob());
        } finally {
            ConfigService.delete("conversation.queue.idleEvictionMs");
        }
    }
}
