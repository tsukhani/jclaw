import channels.TelegramChannel;
import channels.TelegramMediaGroupBuffer;
import models.MessageAttachment;
import org.junit.jupiter.api.*;
import play.test.UnitTest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link TelegramMediaGroupBuffer} (JCLAW-136). Bypasses the
 * scheduler via {@code flushForTest} so the tests don't rely on timing.
 */
class TelegramMediaGroupBufferTest extends UnitTest {

    @Test
    void passesThroughMessagesWithoutMediaGroupId() {
        var dispatched = new AtomicReference<TelegramChannel.InboundMessage>();
        var msg = new TelegramChannel.InboundMessage(
                "chat", "private", "hello", "user", "user",
                List.of(), null);

        TelegramMediaGroupBuffer.add(msg, dispatched::set);

        assertNotNull(dispatched.get(),
                "non-group messages must dispatch synchronously, not buffer");
        assertSame(msg, dispatched.get());
    }

    @Test
    void reassemblesPhotosInSameMediaGroup() {
        var dispatched = new AtomicReference<TelegramChannel.InboundMessage>();

        var first = new TelegramChannel.InboundMessage(
                "chat", "private", "album caption", "user", "user",
                List.of(new TelegramChannel.PendingAttachment(
                        "F1", null, "image/jpeg", 100L, MessageAttachment.KIND_IMAGE)),
                "group-A");
        var second = new TelegramChannel.InboundMessage(
                "chat", "private", "", "user", "user",
                List.of(new TelegramChannel.PendingAttachment(
                        "F2", null, "image/jpeg", 200L, MessageAttachment.KIND_IMAGE)),
                "group-A");
        var third = new TelegramChannel.InboundMessage(
                "chat", "private", "", "user", "user",
                List.of(new TelegramChannel.PendingAttachment(
                        "F3", null, "image/jpeg", 300L, MessageAttachment.KIND_IMAGE)),
                "group-A");

        TelegramMediaGroupBuffer.add(first, dispatched::set);
        TelegramMediaGroupBuffer.add(second, dispatched::set);
        TelegramMediaGroupBuffer.add(third, dispatched::set);

        assertNull(dispatched.get(),
                "while idle window is pending, nothing should dispatch yet");

        TelegramMediaGroupBuffer.flushForTest("group-A");

        var merged = dispatched.get();
        assertNotNull(merged, "flush must produce a merged dispatch");
        assertEquals(3, merged.attachments().size(),
                "merged inbound must carry all 3 photos");
        assertEquals("album caption", merged.text(),
                "merged inbound uses the first non-empty caption encountered");
        assertNull(merged.mediaGroupId(),
                "merged inbound drops the media_group_id — it's been consumed");
    }

    @Test
    void capturesCaptionFromWhicheverMessageHasIt() {
        // Telegram usually puts the caption on the first message in an album,
        // but operators have observed it occasionally arriving on a later one.
        // The buffer picks whichever arrives first with non-empty text.
        var dispatched = new AtomicReference<TelegramChannel.InboundMessage>();

        var noCaption = new TelegramChannel.InboundMessage(
                "chat", "private", "", "user", "user",
                List.of(new TelegramChannel.PendingAttachment(
                        "F1", null, "image/jpeg", 100L, MessageAttachment.KIND_IMAGE)),
                "group-B");
        var withCaption = new TelegramChannel.InboundMessage(
                "chat", "private", "describe these", "user", "user",
                List.of(new TelegramChannel.PendingAttachment(
                        "F2", null, "image/jpeg", 200L, MessageAttachment.KIND_IMAGE)),
                "group-B");

        TelegramMediaGroupBuffer.add(noCaption, dispatched::set);
        TelegramMediaGroupBuffer.add(withCaption, dispatched::set);
        TelegramMediaGroupBuffer.flushForTest("group-B");

        assertEquals("describe these", dispatched.get().text());
    }

    @Test
    void distinctGroupsDoNotInterfere() {
        var dispatchedA = new AtomicReference<TelegramChannel.InboundMessage>();
        var dispatchedB = new AtomicReference<TelegramChannel.InboundMessage>();

        var groupA = new TelegramChannel.InboundMessage(
                "chat", "private", "A-caption", "user", "user",
                List.of(new TelegramChannel.PendingAttachment(
                        "A1", null, "image/jpeg", 100L, MessageAttachment.KIND_IMAGE)),
                "group-C");
        var groupB = new TelegramChannel.InboundMessage(
                "chat", "private", "B-caption", "user", "user",
                List.of(new TelegramChannel.PendingAttachment(
                        "B1", null, "image/jpeg", 100L, MessageAttachment.KIND_IMAGE)),
                "group-D");

        TelegramMediaGroupBuffer.add(groupA, dispatchedA::set);
        TelegramMediaGroupBuffer.add(groupB, dispatchedB::set);
        TelegramMediaGroupBuffer.flushForTest("group-C");

        assertEquals("A-caption", dispatchedA.get().text(),
                "group C flush dispatches only its own bucket");
        assertNull(dispatchedB.get(),
                "group D remains buffered — distinct groups don't interfere");

        TelegramMediaGroupBuffer.flushForTest("group-D");
        assertEquals("B-caption", dispatchedB.get().text());
    }
}
