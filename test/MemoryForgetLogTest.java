import com.github.benmanes.caffeine.cache.Ticker;
import memory.MemoryForgetLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JCLAW-971: the forget window is per forgotten fact, not per agent.
 *
 * <p>It used to be a Caffeine {@code expireAfterWrite} entry holding a per-agent list. The
 * list was created once and mutated in place, so Caffeine saw no second write and never
 * refreshed the TTL — every fact an agent forgot inherited the remaining lifetime of the
 * FIRST one. Forget X at 10:00 and Y at 10:09:55 and the whole window evicted five seconds
 * later, so capture re-learned Y on the next turn: the JCLAW-919 failure mode returning
 * non-deterministically near the end of every window.
 */
class MemoryForgetLogTest extends UnitTest {

    private static final String AGENT = "42";
    private static final String FIRST = "The NAS lives in the basement";
    private static final String SECOND = "The espresso machine is a Silvia Pro";

    /** Manual clock — the window is ten minutes and no test should sleep through it. */
    private final AtomicLong nanos = new AtomicLong(0);

    @BeforeEach
    void setup() {
        MemoryForgetLog.setTickerForTest((Ticker) nanos::get);
        MemoryForgetLog.clearForTest();
    }

    @AfterEach
    void teardown() {
        MemoryForgetLog.setTickerForTest(null);
        MemoryForgetLog.clearForTest();
    }

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    void aLateForgetKeepsItsOwnFullWindow() {
        MemoryForgetLog.noteForgotten(AGENT, FIRST);
        advance(Duration.ofMinutes(9).plusSeconds(55));
        MemoryForgetLog.noteForgotten(AGENT, SECOND);

        // Five more seconds retires the FIRST fact's window. Under the per-agent entry that
        // evicted both, and capture re-stored the second fact on the very next turn.
        advance(Duration.ofSeconds(10));

        assertFalse(MemoryForgetLog.recentlyForgotten(AGENT, FIRST),
                "the first fact's window has genuinely elapsed");
        assertTrue(MemoryForgetLog.recentlyForgotten(AGENT, SECOND),
                "the second fact must keep its own full window, not inherit the first's");
    }

    @Test
    void aForgottenFactExpiresOnceItsOwnWindowElapses() {
        MemoryForgetLog.noteForgotten(AGENT, FIRST);
        assertTrue(MemoryForgetLog.recentlyForgotten(AGENT, FIRST));

        advance(Duration.ofMinutes(10).plusSeconds(1));

        // Deliberately a window, not a tombstone: re-learning a fact the operator raises
        // again later is correct behavior.
        assertFalse(MemoryForgetLog.recentlyForgotten(AGENT, FIRST),
                "the window must expire — this is not a permanent tombstone");
    }

    @Test
    void readingDoesNotExtendTheWindow() {
        // expireAfterWrite, not expireAfterAccess: capture probes this every turn, so
        // refreshing on read would keep an entry alive for as long as the agent stays busy.
        MemoryForgetLog.noteForgotten(AGENT, FIRST);
        for (int i = 0; i < 9; i++) {
            advance(Duration.ofMinutes(1));
            assertTrue(MemoryForgetLog.recentlyForgotten(AGENT, FIRST), "still inside the window");
        }
        advance(Duration.ofMinutes(2));
        assertFalse(MemoryForgetLog.recentlyForgotten(AGENT, FIRST),
                "repeated reads must not have pushed the expiry out");
    }

    @Test
    void anotherAgentsForgetsAreNotVisible() {
        MemoryForgetLog.noteForgotten(AGENT, FIRST);
        assertFalse(MemoryForgetLog.recentlyForgotten("99", FIRST),
                "the window is per agent — memory is partitioned on the agent id");
    }

    @Test
    void clearMatchingDropsOnlyTheMatchingFact() {
        MemoryForgetLog.noteForgotten(AGENT, FIRST);
        MemoryForgetLog.noteForgotten(AGENT, SECOND);

        // "Forget X" then "actually, remember X" has to work inside the window.
        MemoryForgetLog.clearMatching(AGENT, FIRST);

        assertFalse(MemoryForgetLog.recentlyForgotten(AGENT, FIRST), "the re-stored fact is cleared");
        assertTrue(MemoryForgetLog.recentlyForgotten(AGENT, SECOND), "the other fact is untouched");
    }

    @Test
    void suppressesAMemoryAboutHavingForgottenTheFact() {
        // Caught in UAT: forgetting a fact made the extractor record the request itself,
        // which put the forgotten content back in the store — inside the note of its own
        // deletion, and retrievable. Dedup scored it 0.75 against a 0.82 floor, because
        // dedup asks "same fact?" while this has to ask "restates the deleted one?".
        MemoryForgetLog.noteForgotten(AGENT, "The user's son Arjun plays the cello");

        assertTrue(MemoryForgetLog.recentlyForgotten(AGENT,
                "The user wants the memory that Arjun plays the cello to be forgotten"));
    }

    @Test
    void suppressesARewordingThatBuriesTheFactInExtraContext() {
        MemoryForgetLog.noteForgotten(AGENT, "The NAS lives in the basement");

        assertTrue(MemoryForgetLog.recentlyForgotten(AGENT,
                "The user mentioned in passing that the NAS lives in the basement of the house"));
    }

    @Test
    void doesNotSuppressADifferentFactAboutTheSameSubject() {
        // The window must not swallow genuinely new information about a forgotten subject.
        MemoryForgetLog.noteForgotten(AGENT, "The user's son Arjun plays the cello");

        assertFalse(MemoryForgetLog.recentlyForgotten(AGENT, "The user's son Arjun studies medicine in Leeds"));
    }
}
