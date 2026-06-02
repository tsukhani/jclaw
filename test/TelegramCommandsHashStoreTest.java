import channels.TelegramCommandsHashStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import play.test.UnitTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Round-trip + change-detection coverage for {@link TelegramCommandsHashStore}
 * (JCLAW-387 D1). A pure file-store unit test: it redirects the store at a per-test
 * temp directory via the {@code jclaw.telegram.commandHashPath} override (mirrors
 * {@code TelegramOffsetStore}'s {@code jclaw.telegram.offsetPath} seam), so it
 * touches neither production state nor the DB, and the {@code shouldSkip} decision
 * is exercised entirely offline — no {@code setMyCommands} call.
 *
 * <p>Pins:
 * <ul>
 *   <li>{@code hash} is stable for the same ordered list and differs when a
 *       command's name, description, order, or count changes.</li>
 *   <li>record → load round-trips the hash; an absent hash loads as {@code null}.</li>
 *   <li>"unchanged" decision: persist the current list's hash, then
 *       {@code shouldSkip} returns {@code true} for the identical list.</li>
 *   <li>"changed" decision: a different list does NOT skip (fail toward
 *       re-registering).</li>
 *   <li>No persisted hash → don't skip (first boot / fail-open).</li>
 *   <li>Scoping is by the numeric bot id: a rotated secret half shares the hash;
 *       a different bot id is isolated.</li>
 * </ul>
 */
class TelegramCommandsHashStoreTest extends UnitTest {

    private Path tmp;

    @BeforeEach
    void setup() throws Exception {
        tmp = Files.createTempDirectory("jclaw-tg-cmdhash-test-");
        System.setProperty(TelegramCommandsHashStore.HASH_PATH_PROPERTY, tmp.toString());
    }

    @AfterEach
    void teardown() throws Exception {
        System.clearProperty(TelegramCommandsHashStore.HASH_PATH_PROPERTY);
        if (tmp != null && Files.exists(tmp)) {
            try (Stream<Path> walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception _) { /* best-effort */ }
                });
            }
        }
    }

    private static BotCommand cmd(String name, String description) {
        return new BotCommand(name, description);
    }

    private static List<BotCommand> sampleList() {
        return List.of(cmd("new", "Start a new conversation"),
                cmd("reset", "Reset the agent"),
                cmd("stop", "Stop the running task"));
    }

    // --- hash stability ----------------------------------------------------

    @Test
    void hashIsStableForSameOrderedList() {
        assertEquals(TelegramCommandsHashStore.hash(sampleList()),
                TelegramCommandsHashStore.hash(sampleList()),
                "identical ordered lists must hash identically");
    }

    @Test
    void hashDiffersWhenDescriptionChanges() {
        List<BotCommand> changed = List.of(cmd("new", "Start a new conversation"),
                cmd("reset", "Wipe the agent"),   // description changed
                cmd("stop", "Stop the running task"));
        assertNotEquals(TelegramCommandsHashStore.hash(sampleList()),
                TelegramCommandsHashStore.hash(changed));
    }

    @Test
    void hashDiffersWhenOrderChanges() {
        List<BotCommand> reordered = List.of(cmd("reset", "Reset the agent"),
                cmd("new", "Start a new conversation"),
                cmd("stop", "Stop the running task"));
        assertNotEquals(TelegramCommandsHashStore.hash(sampleList()),
                TelegramCommandsHashStore.hash(reordered));
    }

    @Test
    void hashDiffersWhenCommandAddedOrRemoved() {
        List<BotCommand> withExtra = List.of(cmd("new", "Start a new conversation"),
                cmd("reset", "Reset the agent"),
                cmd("stop", "Stop the running task"),
                cmd("help", "Show help"));
        assertNotEquals(TelegramCommandsHashStore.hash(sampleList()),
                TelegramCommandsHashStore.hash(withExtra));
    }

    @Test
    void hashSeparatorsPreventFieldBoundaryCollision() {
        // ("ab","c") vs ("a","bc") would collide under naive concatenation; the
        // unit separator between name and description must keep them distinct.
        assertNotEquals(TelegramCommandsHashStore.hash(List.of(cmd("ab", "c"))),
                TelegramCommandsHashStore.hash(List.of(cmd("a", "bc"))));
    }

    // --- store round-trip --------------------------------------------------

    @Test
    void absentHashLoadsAsNull() {
        assertNull(TelegramCommandsHashStore.load("123456:freshToken"));
    }

    @Test
    void recordThenLoadRoundTrips() {
        String token = "123456:tokenA";
        String h = TelegramCommandsHashStore.hash(sampleList());
        TelegramCommandsHashStore.record(token, h);
        assertEquals(h, TelegramCommandsHashStore.load(token));
    }

    // --- skip decision (pure, no network) ----------------------------------

    @Test
    void unchangedListSkips() {
        String token = "123456:tokenA";
        List<BotCommand> commands = sampleList();
        // Simulate a prior boot that registered + persisted this list's hash.
        TelegramCommandsHashStore.record(token, TelegramCommandsHashStore.hash(commands));
        assertTrue(TelegramCommandsHashStore.shouldSkip(token, commands),
                "an unchanged command list must skip the setMyCommands call");
    }

    @Test
    void changedListDoesNotSkip() {
        String token = "123456:tokenA";
        TelegramCommandsHashStore.record(token, TelegramCommandsHashStore.hash(sampleList()));
        List<BotCommand> changed = List.of(cmd("new", "Start a new conversation"),
                cmd("reset", "Reset the agent"),
                cmd("stop", "Halt everything"));   // description changed
        assertFalse(TelegramCommandsHashStore.shouldSkip(token, changed),
                "a changed command list must re-register (not skip)");
    }

    @Test
    void firstBootWithNoPersistedHashDoesNotSkip() {
        // Nothing recorded yet → fail open, re-register.
        assertFalse(TelegramCommandsHashStore.shouldSkip("999:nothingStored", sampleList()));
    }

    @Test
    void nullBotIdNeverSkips() {
        assertFalse(TelegramCommandsHashStore.shouldSkip(null, sampleList()));
        assertFalse(TelegramCommandsHashStore.shouldSkip("   ", sampleList()));
    }

    // --- bot-id scoping ----------------------------------------------------

    @Test
    void scopingIsByNumericBotIdNotFullToken() {
        // Same bot id (888), rotated secret half — both must share one hash entry.
        String original = "888:secretOne";
        String rotated = "888:secretTwo";
        List<BotCommand> commands = sampleList();
        TelegramCommandsHashStore.record(original, TelegramCommandsHashStore.hash(commands));
        assertTrue(TelegramCommandsHashStore.shouldSkip(rotated, commands),
                "rotated token (same bot id) should see the hash persisted under the old secret");
    }

    @Test
    void differentBotIdsDoNotCollide() {
        List<BotCommand> commands = sampleList();
        TelegramCommandsHashStore.record("111:tok", TelegramCommandsHashStore.hash(commands));
        // 222 never recorded — its decision is independent of 111's stored hash.
        assertTrue(TelegramCommandsHashStore.shouldSkip("111:tok", commands));
        assertFalse(TelegramCommandsHashStore.shouldSkip("222:tok", commands));
    }

    @Test
    void botIdDelegatesToOffsetStoreDerivation() {
        assertEquals("123456", TelegramCommandsHashStore.botId("123456:ABC-def"));
        assertNull(TelegramCommandsHashStore.botId(null));
        assertNull(TelegramCommandsHashStore.botId("   "));
    }
}
