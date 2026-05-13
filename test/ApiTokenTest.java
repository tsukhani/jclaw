import models.ApiToken;
import org.junit.jupiter.api.*;
import play.test.*;
import utils.TokenHasher;

/**
 * Model-level + helper coverage for the in-process API-token
 * persistence layer (JCLAW-282, post-external-surface-drop).
 *
 * <p>Pre-cleanup this file also covered scope enforcement, revocation
 * idempotence, and the {@code listForOwner} sort/scoping contract.
 * Those tests came out with the surface they were guarding — there's
 * no longer a way to mint a {@code READ_ONLY} or
 * {@code ownerUsername="admin"} token, and the listing endpoint that
 * called {@code listForOwner} is gone. Git history retains them in
 * case external tokens come back as an explicit ticket.
 */
class ApiTokenTest extends UnitTest {

    // ==================== TokenHasher ====================

    @Test
    void mintReturnsPrefixedToken() {
        var t = TokenHasher.mint();
        assertTrue(t.startsWith(TokenHasher.TOKEN_PREFIX),
                "minted token should carry the jcl_ prefix for log/secret-scanner recognition; got: " + t);
        // 32 bytes encoded as base64url-without-padding = 43 chars + 4-char prefix
        assertTrue(t.length() > 30, "minted token suspiciously short: " + t);
    }

    @Test
    void mintReturnsDistinctValues() {
        // Sanity check on the SecureRandom path — two consecutive mints
        // colliding would suggest the underlying generator is broken.
        var a = TokenHasher.mint();
        var b = TokenHasher.mint();
        assertNotSame(a, b);
        assertNotEquals(a, b);
    }

    @Test
    void hashIsDeterministic() {
        // The whole point of SHA-256 over PBKDF2 here is O(1) bearer
        // lookup, which only works if hash(token) is the same every time.
        var t = TokenHasher.mint();
        assertEquals(TokenHasher.hash(t), TokenHasher.hash(t));
    }

    @Test
    void hashDifferentForDifferentTokens() {
        var a = TokenHasher.mint();
        var b = TokenHasher.mint();
        assertNotEquals(TokenHasher.hash(a), TokenHasher.hash(b));
    }

    @Test
    void hashRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TokenHasher.hash(null));
    }

    // ==================== ApiToken model ====================

    @BeforeEach
    void clearTokens() {
        ApiToken.deleteAll();
    }

    @Test
    void findActiveByPlaintextResolvesNewToken() {
        var plaintext = TokenHasher.mint();
        var row = freshRow(plaintext);
        row.save();

        var resolved = ApiToken.findActiveByPlaintext(plaintext);
        assertNotNull(resolved, "minted token should round-trip via findActiveByPlaintext");
        assertEquals(row.id, resolved.id);
        assertEquals("system", resolved.ownerUsername);
    }

    @Test
    void findActiveByPlaintextReturnsNullForUnknownToken() {
        assertNull(ApiToken.findActiveByPlaintext("jcl_not-a-real-token"));
        assertNull(ApiToken.findActiveByPlaintext(""));
        assertNull(ApiToken.findActiveByPlaintext(null));
    }

    @Test
    void markUsedUpdatesLastUsedAt() {
        var row = freshRow(TokenHasher.mint());
        row.save();
        assertNull(row.lastUsedAt);

        row.markUsed();
        assertNotNull(row.lastUsedAt);
    }

    @Test
    void markUsedIsThrottledWithinWindow() {
        // Bearer auth fires markUsed() on every /api/** call. Without
        // throttling, every request would UPDATE this row and invalidate
        // the query-cache entry that the same request just populated.
        // The throttle is what keeps the cache effective.
        var row = freshRow(TokenHasher.mint());
        row.save();

        row.markUsed();
        var firstUseAt = row.lastUsedAt;
        assertNotNull(firstUseAt);

        // Immediate second call inside the window must be a no-op so
        // Hibernate's dirty-check skips the UPDATE on save().
        row.markUsed();
        assertEquals(firstUseAt, row.lastUsedAt,
                "second markUsed() within the throttle window must not advance lastUsedAt");
    }

    @Test
    void markUsedUpdatesAfterThrottleExpires() {
        var row = freshRow(TokenHasher.mint());
        row.save();

        // Simulate a prior call that landed before the throttle window.
        // Subtracting 2× the throttle ensures we're past it regardless of
        // clock drift between this line and the markUsed() call.
        row.lastUsedAt = java.time.Instant.now()
                .minusSeconds(ApiToken.MARK_USED_THROTTLE_SECONDS * 2);
        var staleUseAt = row.lastUsedAt;

        row.markUsed();
        assertTrue(row.lastUsedAt.isAfter(staleUseAt),
                "markUsed() outside the throttle window must update lastUsedAt");
    }

    private static ApiToken freshRow(String plaintext) {
        var row = new ApiToken();
        row.ownerUsername = "system";
        row.secretHash = TokenHasher.hash(plaintext);
        return row;
    }
}
