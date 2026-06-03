import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.PendingTranscripts;

import java.util.concurrent.CompletableFuture;

/**
 * JCLAW-405: the static {@code PendingTranscripts} map must not retain a
 * future once its resolved value has been consumed under the
 * single-reader contract. These tests pin the eviction behaviour added
 * to stop the slow heap leak — the value is still returned to the
 * consumer first, then the entry drops, covering both the transcript and
 * the empty-string failure sentinel.
 */
class PendingTranscriptsTest extends UnitTest {

    @BeforeEach
    void setUp() {
        PendingTranscripts.clearForTest();
    }

    @AfterEach
    void tearDown() {
        PendingTranscripts.clearForTest();
    }

    @Test
    void consumeRemovesEntryAfterReadingTranscript() {
        var future = CompletableFuture.completedFuture("hello world");
        PendingTranscripts.register(7L, future);

        // Consumer reads the resolved value first…
        var lookup = PendingTranscripts.lookup(7L);
        assertTrue(lookup.isPresent(), "registered future must be retrievable before consume");
        assertEquals("hello world", lookup.get().getNow(""),
                "consumer must still receive the correct transcript");

        // …then evicts the entry.
        PendingTranscripts.consume(7L);
        assertTrue(PendingTranscripts.lookup(7L).isEmpty(),
                "entry must be gone once consumed");
    }

    @Test
    void consumeRemovesEntryForFailureSentinel() {
        // The dispatcher resolves failed transcriptions with the
        // empty-string sentinel rather than completing exceptionally.
        var future = CompletableFuture.completedFuture("");
        PendingTranscripts.register(8L, future);

        var lookup = PendingTranscripts.lookup(8L);
        assertTrue(lookup.isPresent(), "sentinel future must be retrievable before consume");
        assertEquals("", lookup.get().getNow("unset"),
                "consumer must receive the empty-string failure sentinel");

        PendingTranscripts.consume(8L);
        assertTrue(PendingTranscripts.lookup(8L).isEmpty(),
                "sentinel entry must be evicted once consumed");
    }

    @Test
    void consumeIsSafeForNullAndUnknownIds() {
        // No registered entry: must not throw and must not affect others.
        var future = CompletableFuture.completedFuture("kept");
        PendingTranscripts.register(9L, future);

        PendingTranscripts.consume(null);
        PendingTranscripts.consume(424242L);

        assertTrue(PendingTranscripts.lookup(9L).isPresent(),
                "unrelated entry must survive a null / unknown-id consume");
    }
}
