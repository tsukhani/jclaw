import channels.Channel;
import channels.WhatsAppStreamingSink;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit coverage for {@link WhatsAppStreamingSink} (JCLAW-446) — the buffer-and-
 * send-once sink. WhatsApp can't stream, so per-token updates are ignored and the
 * full reply is delivered at {@code seal}; the sealed-guard makes seal/error/cancel
 * idempotent so a late callback can't double-send.
 */
class WhatsAppStreamingSinkTest extends UnitTest {

    /** Records every text the sink hands to the channel. */
    private static final class RecordingChannel implements Channel {
        final List<String> sent = new ArrayList<>();

        @Override
        public String channelName() {
            return "whatsapp";
        }

        @Override
        public SendResult trySend(String peerId, String text) {
            sent.add(text);
            return SendResult.OK;
        }
    }

    @Test
    void sealSendsTheFullTextOnce() {
        var ch = new RecordingChannel();
        var sink = new WhatsAppStreamingSink(ch, "447911111111", null);
        sink.update("ignored ");
        sink.update("tokens");
        sink.seal("the complete reply");
        assertEquals(List.of("the complete reply"), ch.sent,
                "only the sealed full text is sent; streamed tokens are ignored");
    }

    @Test
    void sealIsIdempotent() {
        var ch = new RecordingChannel();
        var sink = new WhatsAppStreamingSink(ch, "447911111111", null);
        sink.seal("first");
        sink.seal("second");
        assertEquals(List.of("first"), ch.sent, "a second seal is a no-op");
    }

    @Test
    void blankSealSendsNothing() {
        var ch = new RecordingChannel();
        var sink = new WhatsAppStreamingSink(ch, "447911111111", null);
        sink.seal("   ");
        assertTrue(ch.sent.isEmpty(), "a blank reply is not sent");
    }

    @Test
    void errorFallbackSendsAnApology() {
        var ch = new RecordingChannel();
        var sink = new WhatsAppStreamingSink(ch, "447911111111", null);
        sink.errorFallback(new RuntimeException("boom"));
        assertEquals(1, ch.sent.size());
        assertTrue(ch.sent.get(0).toLowerCase().contains("error"),
                "the user gets an error notice");
    }

    @Test
    void cancelSuppressesLaterSeal() {
        var ch = new RecordingChannel();
        var sink = new WhatsAppStreamingSink(ch, "447911111111", null);
        sink.cancel();
        sink.seal("too late");
        assertTrue(ch.sent.isEmpty(), "a cancelled sink sends nothing on a later seal");
    }
}
