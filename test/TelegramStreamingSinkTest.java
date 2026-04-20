import channels.TelegramStreamingSink;
import org.junit.jupiter.api.*;
import play.test.*;

/**
 * Unit tests for {@link TelegramStreamingSink}. We exercise the pure-logic
 * paths — image stripping, state transitions, cap detection, sealed-idempotence
 * — without making Telegram HTTP calls. End-to-end edit-loop tests that need
 * a live Telegram API are deferred to a follow-up functional test; the
 * network seams here are thin enough that unit-level tests give us most of
 * the confidence with none of the flakiness.
 */
public class TelegramStreamingSinkTest extends UnitTest {

    // === stripImageRefs — the live-preview image filter ===

    @Test
    public void stripImageRefsRemovesMarkdownImages() {
        // Note: surrounding whitespace is preserved (single-pass regex over
        // the image token). Matches OpenClaw's behavior — the live preview
        // gets the text minus the image markup; seal-time delivery via the
        // planner is where images actually appear as media messages.
        assertEquals("before  after",
                TelegramStreamingSink.stripImageRefs("before ![](x.png) after"));
        assertEquals("Here: ",
                TelegramStreamingSink.stripImageRefs("Here: ![alt text](path/to/img.jpg)"));
    }

    @Test
    public void stripImageRefsRemovesMultipleImages() {
        var input = "One ![a](1.png) two ![b](2.png) three";
        assertEquals("One  two  three",
                TelegramStreamingSink.stripImageRefs(input));
    }

    @Test
    public void stripImageRefsLeavesRegularLinksAlone() {
        // Plain markdown links ([text](url)) — not images — must pass through
        // so the seal-time planner can see them.
        var input = "See [the docs](https://example.com/docs) for more.";
        assertEquals(input, TelegramStreamingSink.stripImageRefs(input));
    }

    @Test
    public void stripImageRefsLeavesFileReferencesAlone() {
        // JClaw workspace-file convention is [label](<path>) — the angle
        // brackets mean this isn't an image and must survive streaming so
        // the seal path's TelegramOutboundPlanner can still handle it.
        var input = "Check [report.pdf](<workspace/report.pdf>) for details.";
        assertEquals(input, TelegramStreamingSink.stripImageRefs(input));
    }

    @Test
    public void stripImageRefsHandlesNullAndEmpty() {
        assertEquals("", TelegramStreamingSink.stripImageRefs(null));
        assertEquals("", TelegramStreamingSink.stripImageRefs(""));
    }

    @Test
    public void stripImageRefsPreservesContentWithoutImages() {
        var input = "Plain text with **bold** and _italic_ and a `code` block.";
        assertEquals(input, TelegramStreamingSink.stripImageRefs(input));
    }

    // === update / state transitions ===

    @Test
    public void updateWithNullOrEmptyIsNoOp() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update(null);
        sink.update("");
        // No flush scheduled → no placeholder sent → messageId stays null
        assertNull(sink.messageIdForTest());
        assertFalse(sink.streamCapReachedForTest());
        assertFalse(sink.sealedForTest());
    }

    @Test
    public void updateBeyond4096CharsTripsStreamCap() {
        // Accumulating past the Telegram message cap stops live streaming.
        // seal() will then fall back to the planner path regardless of how
        // the final formatted response looks. This is the JCLAW-94 AC: "If
        // the response exceeds 4096 characters, streaming stops at the cap".
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update("x".repeat(4097));
        assertTrue(sink.streamCapReachedForTest(),
                "4097-char update must flip the cap flag");
    }

    @Test
    public void updateExactlyAtCapDoesNotTripFlag() {
        // Boundary: 4096 chars is the hard cap; at-or-below is fine.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update("x".repeat(4096));
        assertFalse(sink.streamCapReachedForTest(),
                "4096-char update must stay within the cap");
    }

    @Test
    public void updateIgnoredAfterCapReached() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update("x".repeat(4097));
        assertTrue(sink.streamCapReachedForTest());

        // Further updates must not change state — no retry, no reset.
        sink.update("more");
        assertTrue(sink.streamCapReachedForTest(),
                "cap is sticky: further updates must not clear it");
    }

    // === seal lifecycle ===

    @Test
    public void sealMarksSinkSealedEvenWhenNoFinalResponse() {
        // Defensive: a null/empty final text still has to terminate the
        // sink cleanly (otherwise a later update would still try to flush).
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal(null);
        assertTrue(sink.sealedForTest(),
                "seal(null) still flips the sealed flag");
    }

    @Test
    public void sealIsIdempotent() {
        // Two completion callbacks firing shouldn't double-send the error
        // message. The CAS-gated sealed flag guarantees at-most-once seal.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal(null);
        // Second seal is a no-op — no exception, flag stays set.
        sink.seal("something else");
        assertTrue(sink.sealedForTest());
    }

    @Test
    public void sealAfterCapGoesThroughPlannerPath() {
        // Preparation: trip the cap first. This sink never sent a
        // placeholder (messageId stayed null), so the planner path won't
        // try to delete anything — it'll just send the final response as
        // a fresh message via TelegramChannel.sendMessage.
        //
        // We can't assert the Telegram API was called here without a mock,
        // so this test documents the precondition: the sink reaches
        // seal() in its "cap tripped, never flushed" state with messageId
        // still null.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update("y".repeat(5000));
        assertTrue(sink.streamCapReachedForTest());
        assertNull(sink.messageIdForTest(),
                "overflow before any flush must leave messageId null — "
                        + "seal will deliver via the planner, no placeholder to delete");
    }

    // === errorFallback lifecycle ===

    @Test
    public void errorFallbackIsIdempotent() {
        // Two error callbacks shouldn't double-delete a placeholder or
        // double-send the user-facing error. Same CAS guarantee as seal.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        // Calling errorFallback without any prior activity — messageId is
        // null so no delete attempt, no network call; flips the sealed flag.
        sink.errorFallback(new RuntimeException("first"));
        assertTrue(sink.sealedForTest());
        // Second call: no-op.
        sink.errorFallback(new RuntimeException("second"));
        assertTrue(sink.sealedForTest());
    }

    @Test
    public void sealAndErrorFallbackAreMutuallyExclusive() {
        // If seal wins the CAS, a subsequent error callback is a no-op
        // (and vice versa). Prevents duplicate error messages to the user
        // when the LLM layer fires both onComplete and onError (which
        // shouldn't happen but has in past edge cases).
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal(null);
        sink.errorFallback(new RuntimeException("late"));
        assertTrue(sink.sealedForTest(),
                "sealed state holds against a trailing error callback");
    }
}
