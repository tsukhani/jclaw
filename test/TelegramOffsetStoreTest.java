import channels.TelegramOffsetStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Round-trip + bot-id-scoping coverage for {@link TelegramOffsetStore}
 * (JCLAW-361). A pure file-store unit test: it redirects the store at a
 * per-test temp directory via the {@code jclaw.telegram.offsetPath} override
 * (mirrors {@code LuceneIndexer.setIndexPathForTest}), so it touches neither
 * production state nor the DB.
 *
 * <p>Pins:
 * <ul>
 *   <li>Absent offset reads as 0 (the SDK's initial {@code lastReceivedUpdate}).</li>
 *   <li>persist → load round-trips the value.</li>
 *   <li>{@code record} is monotonic — a lower id never rewinds the stored one.</li>
 *   <li>Scoping is by the numeric bot id (prefix before {@code ':'}): a rotated
 *       secret half shares the offset; a different bot id is isolated.</li>
 *   <li>{@code botId} derivation handles missing colon / null / blank.</li>
 *   <li>A restart-equivalent reload returns the persisted high-water mark, so
 *       {@code getUpdates} resumes past already-consumed updates.</li>
 * </ul>
 */
class TelegramOffsetStoreTest extends UnitTest {

    private Path tmp;

    @BeforeEach
    void setup() throws Exception {
        tmp = Files.createTempDirectory("jclaw-tg-offset-test-");
        System.setProperty(TelegramOffsetStore.OFFSET_PATH_PROPERTY, tmp.toString());
    }

    @AfterEach
    void teardown() throws Exception {
        System.clearProperty(TelegramOffsetStore.OFFSET_PATH_PROPERTY);
        if (tmp != null && Files.exists(tmp)) {
            try (Stream<Path> walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception _) { /* best-effort */ }
                });
            }
        }
    }

    @Test
    void absentOffsetReadsAsZero() {
        assertEquals(0, TelegramOffsetStore.load("123456:freshToken"));
    }

    @Test
    void recordThenLoadRoundTrips() {
        String token = "123456:tokenA";
        TelegramOffsetStore.persist(token, 42);
        assertEquals(42, TelegramOffsetStore.load(token));
    }

    @Test
    void recordIsMonotonicAndNeverRewinds() {
        String token = "777:tok";
        TelegramOffsetStore.persist(token, 100);
        // A lower (stale / out-of-order) id must not lower the stored value.
        TelegramOffsetStore.persist(token, 50);
        assertEquals(100, TelegramOffsetStore.load(token));
        // A higher id advances it.
        TelegramOffsetStore.persist(token, 150);
        assertEquals(150, TelegramOffsetStore.load(token));
    }

    @Test
    void scopingIsByNumericBotIdNotFullToken() {
        // Same bot id (888), rotated secret half — both must share one offset.
        String original = "888:secretOne";
        String rotated = "888:secretTwo";
        TelegramOffsetStore.persist(original, 99);
        assertEquals(99, TelegramOffsetStore.load(rotated),
                "rotated token (same bot id) should see the offset persisted under the old secret");
    }

    @Test
    void differentBotIdsDoNotCollide() {
        TelegramOffsetStore.persist("111:tok", 10);
        TelegramOffsetStore.persist("222:tok", 20);
        assertEquals(10, TelegramOffsetStore.load("111:tok"));
        assertEquals(20, TelegramOffsetStore.load("222:tok"));
    }

    @Test
    void botIdDerivation() {
        assertEquals("123456", TelegramOffsetStore.botId("123456:ABC-def"));
        // No colon — fall back to the whole trimmed token (defensive).
        assertEquals("plainnocolon", TelegramOffsetStore.botId("  plainnocolon  "));
        assertNull(TelegramOffsetStore.botId(null));
        assertNull(TelegramOffsetStore.botId("   "));
    }

    @Test
    void reloadAfterRestartResumesPastConsumedUpdates() {
        // Simulate: a session consumed up to update_id 5000 and persisted it.
        String token = "555:botToken";
        TelegramOffsetStore.persist(token, 5000);
        // Restart = the SDK's in-memory AtomicInteger is back at 0; the runner
        // seeds the start offset from the store. The seeded getUpdates offset is
        // persisted + 1, so update 5000 and everything before it are skipped.
        int reloaded = TelegramOffsetStore.load(token);
        assertEquals(5000, reloaded,
                "restart must reload the persisted high-water update id");
        int seededOffset = Math.max(0, reloaded) + 1;
        assertEquals(5001, seededOffset,
                "seeded getUpdates offset must skip the already-consumed update 5000");
    }
}
