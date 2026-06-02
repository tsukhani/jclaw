import channels.TelegramChannel;
import channels.TelegramInboundTextBuffer;
import org.junit.jupiter.api.*;
import play.test.UnitTest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link TelegramInboundTextBuffer} (M2 inbound long-message
 * reassembly). Bypasses the scheduler via {@code flushForTest} so the tests
 * don't rely on timing. {@code resetForTest()} clears static buffer state
 * between cases.
 */
class TelegramInboundTextBufferTest extends UnitTest {

    /** A text body at/above the default 4000-char coalesce threshold. */
    private static String longText(char fill, int len) {
        return String.valueOf(fill).repeat(len);
    }

    @BeforeEach
    void reset() {
        TelegramInboundTextBuffer.resetForTest();
    }

    private static TelegramChannel.InboundMessage textMsg(String chatId, Integer threadId,
                                                          String fromId, String text,
                                                          Integer messageId, boolean botMentioned,
                                                          String replyContext) {
        return new TelegramChannel.InboundMessage(
                chatId, "private", text,
                fromId, "handle", "Display Name", botMentioned,
                List.of(), null, messageId, threadId, replyContext);
    }

    @Test
    void subThresholdMessageDispatchesImmediately() {
        var dispatched = new AtomicReference<TelegramChannel.InboundMessage>();
        var count = new AtomicInteger();
        var msg = textMsg("chat", null, "user", "a short normal message", 1, false, null);

        TelegramInboundTextBuffer.add(msg, m -> {
            count.incrementAndGet();
            dispatched.set(m);
        });

        assertEquals(1, count.get(),
                "a sub-threshold message must dispatch immediately, exactly once");
        assertSame(msg, dispatched.get(),
                "a sub-threshold message passes through unchanged (same instance)");
    }

    @Test
    void twoAboveThresholdPiecesCoalesceIntoOneDispatch() {
        var dispatched = new AtomicReference<TelegramChannel.InboundMessage>();
        var count = new AtomicInteger();

        var first = textMsg("chat", 7, "user", longText('a', 4096), 100, true, "in reply to: hi");
        var second = textMsg("chat", 7, "user", longText('b', 4096), 101, false, null);

        TelegramInboundTextBuffer.add(first, m -> { count.incrementAndGet(); dispatched.set(m); });
        TelegramInboundTextBuffer.add(second, m -> { count.incrementAndGet(); dispatched.set(m); });

        assertEquals(0, count.get(),
                "while the idle window is pending, nothing should dispatch yet");

        TelegramInboundTextBuffer.flushForTest(first);

        assertEquals(1, count.get(), "flush must produce exactly one merged dispatch");
        var merged = dispatched.get();
        assertNotNull(merged);
        assertEquals(longText('a', 4096) + longText('b', 4096), merged.text(),
                "merged text is the concatenation of the buffered pieces in arrival order");
        // First-piece metadata preserved verbatim.
        assertEquals(Integer.valueOf(100), merged.messageId(),
                "merged inbound keeps the FIRST piece's messageId");
        assertEquals(Integer.valueOf(7), merged.messageThreadId(),
                "merged inbound keeps the FIRST piece's messageThreadId");
        assertTrue(merged.botMentioned(),
                "merged inbound keeps the FIRST piece's botMentioned flag");
        assertEquals("in reply to: hi", merged.replyContext(),
                "merged inbound keeps the FIRST piece's replyContext");
        assertEquals("user", merged.fromId());
        assertEquals("handle", merged.fromUsername());
        assertEquals("Display Name", merged.fromDisplayName());
        assertEquals("chat", merged.chatId());
        assertEquals("private", merged.chatType());
        assertTrue(merged.attachments().isEmpty(), "eligible text pieces carry no attachments");
        assertNull(merged.mediaGroupId(), "merged text inbound has no media_group_id");
    }

    @Test
    void subThresholdTailPieceAppendsToOpenBuffer() {
        var dispatched = new AtomicReference<TelegramChannel.InboundMessage>();
        var count = new AtomicInteger();
        java.util.function.Consumer<TelegramChannel.InboundMessage> sink =
                m -> { count.incrementAndGet(); dispatched.set(m); };

        // A ~9000-char paste the Telegram client auto-splits into 4096 + 4096 +
        // a short remainder (808). The remainder is BELOW the 4000 threshold but
        // arrives while the bucket is open, so it must append — not dispatch as
        // its own turn (which would drop the end of the user's message).
        var first = textMsg("chat", 7, "user", longText('a', 4096), 100, true, "ctx");
        var second = textMsg("chat", 7, "user", longText('b', 4096), 101, false, null);
        var tail = textMsg("chat", 7, "user", longText('c', 808), 102, false, null);

        TelegramInboundTextBuffer.add(first, sink);
        TelegramInboundTextBuffer.add(second, sink);
        TelegramInboundTextBuffer.add(tail, sink);

        assertEquals(0, count.get(),
                "the sub-threshold tail must append to the open buffer, not dispatch on its own");

        TelegramInboundTextBuffer.flushForTest(first);

        assertEquals(1, count.get(), "all three pieces flush as exactly one merged dispatch");
        assertEquals(longText('a', 4096) + longText('b', 4096) + longText('c', 808),
                dispatched.get().text(),
                "merged text includes the short tail piece appended in arrival order");
        assertEquals(Integer.valueOf(100), dispatched.get().messageId(),
                "merged inbound keeps the FIRST piece's metadata even with a tail");
    }

    @Test
    void differentKeysDoNotMerge() {
        var dispatchedUserA = new AtomicReference<TelegramChannel.InboundMessage>();
        var dispatchedUserB = new AtomicReference<TelegramChannel.InboundMessage>();

        // Same chat + thread, different sender → distinct keys.
        var userA = textMsg("chat", 7, "userA", longText('a', 4096), 100, false, null);
        var userB = textMsg("chat", 7, "userB", longText('b', 4096), 200, false, null);

        TelegramInboundTextBuffer.add(userA, dispatchedUserA::set);
        TelegramInboundTextBuffer.add(userB, dispatchedUserB::set);

        TelegramInboundTextBuffer.flushForTest(userA);
        assertNotNull(dispatchedUserA.get(), "userA's bucket flushes on its own key");
        assertEquals(longText('a', 4096), dispatchedUserA.get().text(),
                "userA's merged text contains only userA's piece");
        assertNull(dispatchedUserB.get(),
                "userB's bucket remains buffered — distinct keys don't interfere");

        TelegramInboundTextBuffer.flushForTest(userB);
        assertEquals(longText('b', 4096), dispatchedUserB.get().text());
    }

    @Test
    void isEligibleRejectsAttachmentsAndMediaGroups() {
        var plain = textMsg("chat", null, "user", "hello", 1, false, null);
        assertTrue(TelegramInboundTextBuffer.isEligible(plain),
                "plain text with no attachments and no media group is eligible");

        var withAttachment = new TelegramChannel.InboundMessage(
                "chat", "private", "caption", "user", "handle",
                List.of(new TelegramChannel.PendingAttachment(
                        "F1", null, "image/jpeg", 100L, models.MessageAttachment.KIND_IMAGE)),
                null);
        assertFalse(TelegramInboundTextBuffer.isEligible(withAttachment),
                "a message carrying attachments is NOT eligible for text reassembly");

        var withMediaGroup = new TelegramChannel.InboundMessage(
                "chat", "private", "caption", "user", "handle",
                List.of(), "group-A");
        assertFalse(TelegramInboundTextBuffer.isEligible(withMediaGroup),
                "a message with a media_group_id is NOT eligible for text reassembly");

        var nullText = new TelegramChannel.InboundMessage(
                "chat", "private", null, "user", "handle",
                List.of(), null);
        assertFalse(TelegramInboundTextBuffer.isEligible(nullText),
                "a null-text (media-only) message is NOT eligible");
    }
}
