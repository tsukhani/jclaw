import channels.TelegramChannel;
import channels.TelegramForwardCoalesceBuffer;
import org.junit.jupiter.api.*;
import play.test.UnitTest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link TelegramForwardCoalesceBuffer} (JCLAW-387 B1 forwarded-
 * message coalescing). Bypasses the scheduler via {@code flushForTest} so the
 * tests don't rely on timing; {@code resetForTest()} clears static buffer state
 * between cases. Mirrors {@code TelegramInboundTextBufferTest}.
 */
class TelegramForwardCoalesceBufferTest extends UnitTest {

    @BeforeEach
    void reset() {
        TelegramForwardCoalesceBuffer.resetForTest();
    }

    private static TelegramChannel.InboundMessage fwd(String chatId, Integer threadId,
                                                      String fromId, String text,
                                                      Integer messageId, boolean botMentioned,
                                                      String replyContext) {
        return new TelegramChannel.InboundMessage(
                chatId, "private", text,
                fromId, "handle", "Display Name", botMentioned,
                List.of(), null, messageId, threadId, replyContext);
    }

    @Test
    void twoForwardsSameKeyWithinWindowMergeIntoOneDispatch() {
        var dispatched = new AtomicReference<TelegramChannel.InboundMessage>();
        var count = new AtomicInteger();
        java.util.function.Consumer<TelegramChannel.InboundMessage> sink =
                m -> { count.incrementAndGet(); dispatched.set(m); };

        var first = fwd("chat", 7, "user", "forwarded one", 100, true, "in reply to: hi");
        var second = fwd("chat", 7, "user", "forwarded two", 101, false, null);

        TelegramForwardCoalesceBuffer.add(first, sink);
        TelegramForwardCoalesceBuffer.add(second, sink);

        assertEquals(0, count.get(),
                "while the idle window is pending, nothing should dispatch yet");

        TelegramForwardCoalesceBuffer.flushForTest(first);

        assertEquals(1, count.get(), "a forward burst flushes as exactly one merged dispatch");
        var merged = dispatched.get();
        assertNotNull(merged);
        assertEquals("forwarded one\n\nforwarded two", merged.text(),
                "merged text joins the forwarded bodies (blank-line separated) in arrival order");
        // First-piece metadata preserved verbatim.
        assertEquals(Integer.valueOf(100), merged.messageId(),
                "merged inbound keeps the FIRST forward's messageId");
        assertEquals(Integer.valueOf(7), merged.messageThreadId(),
                "merged inbound keeps the FIRST forward's messageThreadId");
        assertTrue(merged.botMentioned(),
                "merged inbound keeps the FIRST forward's botMentioned flag");
        assertEquals("in reply to: hi", merged.replyContext(),
                "merged inbound keeps the FIRST forward's replyContext");
        assertEquals("user", merged.fromId());
        assertEquals("handle", merged.fromUsername());
        assertEquals("Display Name", merged.fromDisplayName());
        assertEquals("chat", merged.chatId());
        assertEquals("private", merged.chatType());
        assertNull(merged.mediaGroupId(), "a forward burst is not an album — no media_group_id");
    }

    @Test
    void differentKeysDoNotMerge() {
        var dispatchedA = new AtomicReference<TelegramChannel.InboundMessage>();
        var dispatchedB = new AtomicReference<TelegramChannel.InboundMessage>();

        // Same chat + thread, different forwarding sender → distinct keys.
        var userA = fwd("chat", 7, "userA", "A forward", 100, false, null);
        var userB = fwd("chat", 7, "userB", "B forward", 200, false, null);

        TelegramForwardCoalesceBuffer.add(userA, dispatchedA::set);
        TelegramForwardCoalesceBuffer.add(userB, dispatchedB::set);

        TelegramForwardCoalesceBuffer.flushForTest(userA);
        assertNotNull(dispatchedA.get(), "userA's bucket flushes on its own key");
        assertEquals("A forward", dispatchedA.get().text(),
                "userA's merged text contains only userA's forward");
        assertNull(dispatchedB.get(),
                "userB's bucket remains buffered — distinct keys don't interfere");

        TelegramForwardCoalesceBuffer.flushForTest(userB);
        assertEquals("B forward", dispatchedB.get().text());
    }

    @Test
    void singleForwardFlushesAsOne() {
        var dispatched = new AtomicReference<TelegramChannel.InboundMessage>();
        var count = new AtomicInteger();
        var only = fwd("chat", null, "user", "lone forward", 1, false, null);

        TelegramForwardCoalesceBuffer.add(only, m -> { count.incrementAndGet(); dispatched.set(m); });
        assertEquals(0, count.get(), "a single forward buffers until the idle window elapses");

        TelegramForwardCoalesceBuffer.flushForTest(only);
        assertEquals(1, count.get(), "a burst of one flushes as one dispatch");
        assertEquals("lone forward", dispatched.get().text(),
                "a single forward's text passes through unchanged (no separator added)");
    }

    @Test
    void forwardBurstAccumulatesAttachments() {
        var dispatched = new AtomicReference<TelegramChannel.InboundMessage>();
        java.util.function.Consumer<TelegramChannel.InboundMessage> sink = dispatched::set;

        var photo1 = new TelegramChannel.InboundMessage(
                "chat", "private", "cap one", "user", "handle", "Display Name", false,
                List.of(new TelegramChannel.PendingAttachment(
                        "F1", null, "image/jpeg", 100L, models.MessageAttachment.KIND_IMAGE)),
                null, 100, 7, null);
        var photo2 = new TelegramChannel.InboundMessage(
                "chat", "private", null, "user", "handle", "Display Name", false,
                List.of(new TelegramChannel.PendingAttachment(
                        "F2", null, "image/jpeg", 200L, models.MessageAttachment.KIND_IMAGE)),
                null, 101, 7, null);

        TelegramForwardCoalesceBuffer.add(photo1, sink);
        TelegramForwardCoalesceBuffer.add(photo2, sink);
        TelegramForwardCoalesceBuffer.flushForTest(photo1);

        var merged = dispatched.get();
        assertNotNull(merged);
        assertEquals(2, merged.attachments().size(),
                "a forward burst accumulates attachments from every piece");
        assertEquals("cap one", merged.text(),
                "only the non-empty caption contributes; a null-caption piece adds no separator");
    }

    @Test
    void noFlushWithoutBufferedBucket() {
        var count = new AtomicInteger();
        var stray = fwd("chat", null, "user", "x", 1, false, null);
        // flushForTest on a key with no open bucket must be a safe no-op.
        TelegramForwardCoalesceBuffer.flushForTest(stray);
        assertEquals(0, count.get(), "flushing an empty key dispatches nothing");
    }
}
