import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.compression.ContentHash;

/**
 * JCLAW-468: ContentHash — the content-addressed handle that ties a compressed
 * marker to the durable original for CCR retrieval.
 */
class ContentHashTest extends UnitTest {

    @Test
    void sha256HexIsDeterministicAnd64Chars() {
        var a = ContentHash.sha256Hex("hello");
        assertEquals(a, ContentHash.sha256Hex("hello"), "same input -> same hash");
        assertEquals(64, a.length(), "sha-256 hex is 64 chars");
        assertNotEquals(a, ContentHash.sha256Hex("world"), "different input -> different hash");
    }

    @Test
    void sha256HexMatchesKnownVectorLowercase() {
        // Locks the byte-for-byte lowercase encoding after the HexFormat.of()
        // migration: the well-known SHA-256("hello"). The digest carries high-bit
        // bytes (0x82, 0xba, ...) and a leading-zero byte, so this also guards the
        // sign-extension / zero-padding the hand-rolled loop handled.
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                ContentHash.sha256Hex("hello"));
    }

    @Test
    void handleIsTheLeading16HexOfTheFullHash() {
        var content = "the quick brown fox jumps over the lazy dog";
        var handle = ContentHash.handle(content);
        assertEquals(16, handle.length(), "handle is 16 hex chars");
        assertTrue(ContentHash.sha256Hex(content).startsWith(handle),
                "the handle is the leading 16 hex of the full hash — so ccr_retrieve's prefix match works");
    }
}
