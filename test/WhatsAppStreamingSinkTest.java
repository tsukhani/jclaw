import channels.Channel;
import channels.WhatsAppStreamingSink;
import llm.LlmProvider;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.LlmErrorTemplates;

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

    /** The customer reading this is not the operator: the classified remedy stays in the log. */
    @Test
    void errorFallbackNeverShowsTheCustomerTheProviderOrItsBilling() {
        var ch = new RecordingChannel();
        var sink = new WhatsAppStreamingSink(ch, "447911111111", null);
        var failure = new LlmErrorTemplates.Failure(
                LlmErrorTemplates.Remedy.QUOTA_EXHAUSTED, "openrouter", "gpt-4.1", null, null);
        sink.errorFallback(new LlmProvider.LlmException.ClientError("HTTP 402 from openrouter", failure));
        var sent = ch.sent.get(0);
        assertFalse(sent.contains("openrouter"), sent);
        assertFalse(sent.contains("gpt-4.1"), sent);
        assertFalse(sent.toLowerCase().contains("credit"), sent);
    }

    @Test
    void errorFallbackSendsAnApology() {
        var ch = new RecordingChannel();
        var sink = new WhatsAppStreamingSink(ch, "447911111111", null);
        sink.errorFallback(new RuntimeException("boom"));
        assertEquals(1, ch.sent.size());
        // JCLAW-1133: a 3-part notice, plain — WhatsApp formatting is its own dialect, so the
        // renderer emits none. The old assertion pinned the word "error", which the template
        // deliberately avoids: naming what broke beats labelling it.
        var sent = ch.sent.get(0);
        assertTrue(sent.contains("What broke"), sent);
        assertTrue(sent.contains("How to retry"), sent);
        assertFalse(sent.contains("**"), "no markdown on the plain path: " + sent);
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
