import agents.CurrentTimeInjector;
import llm.LlmTypes.ChatMessage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.TimezoneResolver;

import java.util.List;
import java.util.Map;

/**
 * Tests for {@link CurrentTimeInjector}, which carries the per-turn clock as its
 * own trailing message.
 *
 * <p>The invariant under test throughout, and the one JCLAW-900 turns on: every
 * message that already existed must come out BYTE-IDENTICAL. The clock may only
 * ever be added as a new element.
 *
 * <p>That is stricter than what this file asserted before. The previous contract
 * merged the block into the last user message and only required earlier history to
 * be stable — which held, right up until that message became history itself and
 * shipped without the block. A block-caching provider's prefix then broke at the
 * first history message on every turn. A test that merely checks "the clock is
 * present somewhere" passes on both designs and catches none of it.
 */
class CurrentTimeInjectorTest extends UnitTest {

    @Test
    void blockCarriesHeadingAndConfiguredTimezone() {
        ConfigService.set(TimezoneResolver.APP_CONFIG_KEY, "Asia/Kuala_Lumpur");
        try {
            var block = CurrentTimeInjector.block();
            assertTrue(block.contains(CurrentTimeInjector.HEADING));
            assertTrue(block.contains("- Timezone: Asia/Kuala_Lumpur"),
                    "clock must reflect the configured app.timezone");
        } finally {
            ConfigService.delete(TimezoneResolver.APP_CONFIG_KEY);
            ConfigService.clearCache();
        }
    }

    @Test
    void appendsClockAsItsOwnTrailingMessage() {
        var out = CurrentTimeInjector.inject(List.of(
                ChatMessage.system("static prefix"),
                ChatMessage.user("hello")));

        assertEquals(3, out.size(), "the clock is an added message, not a merge");
        assertEquals("static prefix", out.getFirst().content());
        assertEquals("hello", out.get(1).content(),
                "the user's message must not be touched — it becomes history next turn");
        assertTrue(((String) out.get(2).content()).contains(CurrentTimeInjector.HEADING));
    }

    @Test
    void everyPreExistingMessageIsLeftByteIdentical() {
        // The core JCLAW-900 invariant. If any of these strings can differ between
        // the turn a message is newest and the turn it is history, a breakpoint
        // cache breaks there and never recovers.
        var input = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("turn one"),
                ChatMessage.assistant("reply"),
                ChatMessage.user("turn two"));

        var out = CurrentTimeInjector.inject(input);

        for (int i = 0; i < input.size(); i++) {
            assertEquals(input.get(i).content(), out.get(i).content(),
                    "message " + i + " must survive injection unchanged");
            assertEquals(input.get(i).role(), out.get(i).role());
        }
        assertEquals(input.size() + 1, out.size(), "exactly one message added");
    }

    @Test
    void multimodalContentIsLeftAloneEntirely() {
        // Vision/audio turns carry a List of parts rather than a String. The clock
        // used to be spliced in as an extra part; now it must not touch them at
        // all, or the same mutate-then-ship-without problem returns for media turns.
        var parts = List.<Object>of(Map.of("type", "text", "text", "describe this"));
        var multimodal = new ChatMessage("user", parts, null, null, null);

        var out = CurrentTimeInjector.inject(List.of(multimodal));

        assertEquals(2, out.size());
        assertSame(parts, out.getFirst().content(),
                "a multimodal message must be passed through untouched");
        assertTrue(CurrentTimeInjector.isClockBlock(out.get(1).content()));
    }

    @Test
    void anEmptyListIsLeftAlone() {
        var input = List.<ChatMessage>of();
        assertSame(input, CurrentTimeInjector.inject(input),
                "nothing to attach a clock to");
    }

    @Test
    void theClockBlockIsRecognizable() {
        // The provider uses this to avoid anchoring its cache breakpoint to the one
        // message that changes every turn. If it stops matching, the breakpoint
        // silently lands on the clock and the cache goes dead again.
        assertTrue(CurrentTimeInjector.isClockBlock(CurrentTimeInjector.block()));
        assertFalse(CurrentTimeInjector.isClockBlock("what time is it?"));
        assertFalse(CurrentTimeInjector.isClockBlock(null));
        assertFalse(CurrentTimeInjector.isClockBlock(List.of("not a string")));
    }

    @Test
    void doesNotMutateTheInputList() {
        var input = List.of(ChatMessage.system("sys"), ChatMessage.user("hi"));
        CurrentTimeInjector.inject(input);
        assertEquals(2, input.size(), "callers reuse the pre-injection list");
        assertEquals("hi", input.get(1).content());
    }
}
