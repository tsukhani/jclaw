import memory.JpaMemoryStore;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * JCLAW-407: the embedding cache key is now a content hash of the memory text,
 * not the raw {@code @Column TEXT}. This pins the two correctness properties the
 * fix depends on:
 *
 * <ol>
 *   <li><b>Same input → same key:</b> the SHA-256 hash is deterministic, so two
 *       lookups for the identical {@code (model, text)} collapse to one cache
 *       entry (a hit). If the hash drifted, every lookup would miss and re-call
 *       the provider.</li>
 *   <li><b>Bounded key size:</b> the hash hex is a fixed 64 chars regardless of
 *       text length, so a 5-char text and a 5 MB text produce equal-length keys.
 *       This is what makes the documented {@code maximumSize=10_000} heap bound
 *       true — the unbounded text is no longer pinned as the key for the 24h
 *       {@code expireAfterWrite} window.</li>
 * </ol>
 *
 * <p>A weak/short hash would be unacceptable: a collision silently returns the
 * wrong embedding for a different text. SHA-256 (256-bit) makes that
 * astronomically unlikely; we also pin that distinct texts produce distinct
 * keys for representative inputs.
 *
 * <p>{@code hashText} and the {@code EmbeddingKey} record are private. Tests in
 * this codebase live in the default package per Play 1.x's test-runner
 * convention; reflection is the lightest-weight seam that respects that (see
 * {@code AudioRetryStrategyTest} for the same pattern).
 */
class JpaMemoryStoreTest extends UnitTest {

    /** SHA-256 hex is 32 bytes = 64 lowercase-hex characters. */
    private static final int SHA256_HEX_LEN = 64;

    private static Method hashText() throws Exception {
        var m = JpaMemoryStore.class.getDeclaredMethod("hashText", String.class);
        m.setAccessible(true);
        return m;
    }

    private static String hash(String text) throws Exception {
        return (String) hashText().invoke(null, text);
    }

    private static Constructor<?> embeddingKeyCtor() throws Exception {
        for (var dc : JpaMemoryStore.class.getDeclaredClasses()) {
            if ("EmbeddingKey".equals(dc.getSimpleName())) {
                var c = dc.getDeclaredConstructor(String.class, String.class);
                c.setAccessible(true);
                return c;
            }
        }
        throw new AssertionError("EmbeddingKey record not found on JpaMemoryStore");
    }

    private static Object key(String model, String text) throws Exception {
        return embeddingKeyCtor().newInstance(model, hash(text));
    }

    @Test
    void sameTextHashesToSameKeyComponent() throws Exception {
        var text = "the agent remembered the kitchen was painted blue";
        assertEquals(hash(text), hash(text));
    }

    @Test
    void differentTextHashesToDifferentKeyComponent() throws Exception {
        assertFalse(hash("memory A").equals(hash("memory B")));
    }

    @Test
    void hashIsFixedWidthRegardlessOfTextLength() throws Exception {
        var shortText = "hi";
        var longText = "x".repeat(5_000_000); // 5 MB of text

        assertEquals(SHA256_HEX_LEN, hash(shortText).length());
        assertEquals(SHA256_HEX_LEN, hash(longText).length());
        // The whole point of the fix: the key for a huge text is no bigger than
        // the key for a tiny text — the unbounded TEXT column is not retained.
        assertEquals(hash(shortText).length(), hash(longText).length());
    }

    @Test
    void hashIsLowercaseHex() throws Exception {
        assertTrue(hash("anything").matches("[0-9a-f]{64}"));
    }

    @Test
    void sameModelAndTextProduceEqualKeys() throws Exception {
        var k1 = key("text-embedding-3-small", "shared memory text");
        var k2 = key("text-embedding-3-small", "shared memory text");
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void differentModelSameTextProduceDistinctKeys() throws Exception {
        var k1 = key("text-embedding-3-small", "shared memory text");
        var k2 = key("text-embedding-3-large", "shared memory text");
        assertFalse(k1.equals(k2));
    }

    @Test
    void differentTextSameModelProduceDistinctKeys() throws Exception {
        var k1 = key("text-embedding-3-small", "memory one");
        var k2 = key("text-embedding-3-small", "memory two");
        assertFalse(k1.equals(k2));
    }

    @Test
    void keyDoesNotRetainRawText() throws Exception {
        var longText = "secret-pinned-text-" + "y".repeat(100_000);
        var k = key("text-embedding-3-small", longText);
        // The key's string form carries the 64-char hash, never the 100 KB text.
        var rendered = k.toString();
        assertFalse(rendered.contains(longText));
        assertTrue(rendered.contains(hash(longText)));
    }
}
