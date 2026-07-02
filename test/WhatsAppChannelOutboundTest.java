import channels.WhatsAppChannel;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * Unit coverage for {@link WhatsAppChannel}'s pure outbound helpers (JCLAW-447):
 * the 4096-char text chunker and the MIME → media-message-type mapping. The live
 * HTTP send paths (sendMedia upload two-step, sendReaction, the window-gated text
 * send) post to the fixed Graph host through {@code HttpFactories.general()} and
 * are exercised by the integrator's full suite; here we pin the deterministic
 * splitting + type-selection logic that those paths depend on.
 */
class WhatsAppChannelOutboundTest extends UnitTest {

    // ── chunkText ──

    @Test
    void shortTextIsASingleChunk() {
        var chunks = WhatsAppChannel.chunkText("hello world", WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.get(0));
    }

    @Test
    void emptyOrNullTextYieldsNoChunks() {
        assertTrue(WhatsAppChannel.chunkText("", WhatsAppChannel.MAX_TEXT_CHARS).isEmpty());
        assertTrue(WhatsAppChannel.chunkText(null, WhatsAppChannel.MAX_TEXT_CHARS).isEmpty());
    }

    @Test
    void longTextSplitsAtTheLimitAndPreservesContent() {
        // 10000 'a's with no break points → hard cuts at the limit.
        var text = "a".repeat(10_000);
        var chunks = WhatsAppChannel.chunkText(text, WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(3, chunks.size(), "10000 / 4096 → 3 chunks");
        for (var c : chunks) {
            assertTrue(c.length() <= WhatsAppChannel.MAX_TEXT_CHARS,
                    "no chunk may exceed the limit: " + c.length());
        }
        assertEquals(text, String.join("", chunks), "chunks must reassemble to the original");
    }

    @Test
    void prefersBreakingAtWhitespaceWithinTheWindow() {
        // A space just before the limit should be the break point (not a hard cut).
        var head = "x".repeat(WhatsAppChannel.MAX_TEXT_CHARS - 1);
        var text = head + " tail";
        var chunks = WhatsAppChannel.chunkText(text, WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).endsWith(" "),
                "first chunk should break at the space (inclusive)");
        assertEquals("tail", chunks.get(1));
        assertEquals(text, String.join("", chunks));
    }

    @Test
    void breaksAtNewlineWhenPresent() {
        var head = "y".repeat(100);
        var tail = "z".repeat(50);
        var text = head + "\n" + tail;
        // Limit larger than the whole string → single chunk (no split needed).
        var single = WhatsAppChannel.chunkText(text, WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(1, single.size());
        // Tight limit forces a split; the newline is the preferred break.
        var split = WhatsAppChannel.chunkText(text, 120);
        assertTrue(split.size() >= 2);
        assertEquals(text, String.join("", split));
    }

    // ── mediaMessageType ──

    @Test
    void mediaMessageTypeMapsByMimePrefix() {
        assertEquals("image", WhatsAppChannel.mediaMessageType("image/png"));
        assertEquals("image", WhatsAppChannel.mediaMessageType("IMAGE/JPEG"));
        assertEquals("audio", WhatsAppChannel.mediaMessageType("audio/ogg"));
        assertEquals("video", WhatsAppChannel.mediaMessageType("video/mp4"));
        assertEquals("document", WhatsAppChannel.mediaMessageType("application/pdf"));
        assertEquals("document", WhatsAppChannel.mediaMessageType(null),
                "unknown/null MIME falls back to document");
    }

    @Test
    void chunkerNeverProducesEmptyTrailingChunk() {
        var text = "a".repeat(WhatsAppChannel.MAX_TEXT_CHARS); // exactly the limit
        List<String> chunks = WhatsAppChannel.chunkText(text, WhatsAppChannel.MAX_TEXT_CHARS);
        assertEquals(1, chunks.size(), "an exactly-limit-length text is one chunk, no empty tail");
    }

    @Test
    void breakpointExactlyAtTheWindowEdgeStaysWithinTheLimit() {
        // Regression for the JCLAW-447 off-by-one: a space AT index i+limit
        // used to be matched by lastIndexOf(ch, end) (inclusive fromIndex),
        // producing a limit+1-length chunk — a 4097-char body at the real
        // 4096 cap, which Meta's API rejects (the chunk was lost after the
        // single retry). chunkText now searches from end-1, so no chunk can
        // exceed the limit; the edge space starts the next chunk instead.
        var chunks = WhatsAppChannel.chunkText("aaaa b", 4);
        assertEquals(List.of("aaaa", " b"), chunks,
                "the window-edge space must start the next chunk, not overflow this one");
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 4),
                "no chunk may ever exceed the limit");
    }
}
