import models.ApiToken;
import org.junit.jupiter.api.*;
import play.test.*;
import utils.TokenHasher;

/**
 * Model-level + helper coverage for the API-token persistence layer
 * (JCLAW-282). FunctionalTest coverage of the bearer auth path lives in
 * {@link AuthTest}; this file focuses on units that don't need a live
 * HTTP request.
 */
public class ApiTokenTest extends UnitTest {

    // ==================== TokenHasher ====================

    @Test
    public void mintReturnsPrefixedToken() {
        var t = TokenHasher.mint();
        assertTrue(t.startsWith(TokenHasher.TOKEN_PREFIX),
                "minted token should carry the jcl_ prefix for log/secret-scanner recognition; got: " + t);
        // 32 bytes encoded as base64url-without-padding = 43 chars + 4-char prefix
        assertTrue(t.length() > 30, "minted token suspiciously short: " + t);
    }

    @Test
    public void mintReturnsDistinctValues() {
        // Sanity check on the SecureRandom path — two consecutive mints
        // colliding would suggest the underlying generator is broken.
        var a = TokenHasher.mint();
        var b = TokenHasher.mint();
        assertNotSame(a, b);
        assertNotEquals(a, b);
    }

    @Test
    public void hashIsDeterministic() {
        // The whole point of SHA-256 over PBKDF2 here is O(1) bearer
        // lookup, which only works if hash(token) is the same every time.
        var t = TokenHasher.mint();
        assertEquals(TokenHasher.hash(t), TokenHasher.hash(t));
    }

    @Test
    public void hashDifferentForDifferentTokens() {
        var a = TokenHasher.mint();
        var b = TokenHasher.mint();
        assertNotEquals(TokenHasher.hash(a), TokenHasher.hash(b));
    }

    @Test
    public void hashRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TokenHasher.hash(null));
    }

    @Test
    public void prefixReturnsLeadingCharacters() {
        var t = "jcl_abcdefghijklmnopqrstuvwxyz";
        // 4-char "jcl_" plus 8 randoms = the 12-char fingerprint stored
        // alongside the row for the listing UI.
        assertEquals("jcl_abcdefgh", TokenHasher.prefix(t));
    }

    @Test
    public void prefixHandlesShortInput() {
        // Defensive: a fabricated token shorter than the display window
        // should round-trip unchanged rather than IOOB.
        assertEquals("jcl_x", TokenHasher.prefix("jcl_x"));
    }

    @Test
    public void hashesEqualIsConstantTime() {
        // Functional check only — timing-safe-ness is enforced by
        // MessageDigest.isEqual; we just verify the comparison itself
        // returns the right answer.
        assertTrue(TokenHasher.hashesEqual("abc", "abc"));
        assertFalse(TokenHasher.hashesEqual("abc", "abd"));
        assertFalse(TokenHasher.hashesEqual("abc", "abcd"));
        assertFalse(TokenHasher.hashesEqual(null, "abc"));
        assertFalse(TokenHasher.hashesEqual("abc", null));
    }

    // ==================== ApiToken model ====================

    @BeforeEach
    void clearTokens() {
        ApiToken.deleteAll();
    }

    @Test
    public void findActiveByPlaintextResolvesNewToken() {
        var plaintext = TokenHasher.mint();
        var row = freshRow("test-token", plaintext, ApiToken.Scope.READ_ONLY);
        row.save();

        var resolved = ApiToken.findActiveByPlaintext(plaintext);
        assertNotNull(resolved, "minted token should round-trip via findActiveByPlaintext");
        assertEquals(row.id, resolved.id);
        assertEquals("test-token", resolved.name);
    }

    @Test
    public void findActiveByPlaintextReturnsNullForRevokedToken() {
        var plaintext = TokenHasher.mint();
        var row = freshRow("revoked-token", plaintext, ApiToken.Scope.FULL);
        row.revoke();
        row.save();

        // Revoked rows are intentionally treated the same as unknown
        // ones at the bearer-resolve layer — see ApiToken.findActiveByPlaintext
        // javadoc on why we don't differentiate.
        assertNull(ApiToken.findActiveByPlaintext(plaintext));
    }

    @Test
    public void findActiveByPlaintextReturnsNullForUnknownToken() {
        assertNull(ApiToken.findActiveByPlaintext("jcl_not-a-real-token"));
        assertNull(ApiToken.findActiveByPlaintext(""));
        assertNull(ApiToken.findActiveByPlaintext(null));
    }

    @Test
    public void revokeIsIdempotent() {
        var row = freshRow("idempotent", TokenHasher.mint(), ApiToken.Scope.READ_ONLY);
        row.save();

        row.revoke();
        var firstAt = row.revokedAt;
        assertNotNull(firstAt);

        // Re-revoking must not move the timestamp — the audit record of
        // when the operator first pulled the plug should be stable.
        row.revoke();
        assertEquals(firstAt, row.revokedAt);
    }

    @Test
    public void markUsedUpdatesLastUsedAt() {
        var row = freshRow("usage", TokenHasher.mint(), ApiToken.Scope.READ_ONLY);
        row.save();
        assertNull(row.lastUsedAt);

        row.markUsed();
        assertNotNull(row.lastUsedAt);
    }

    @Test
    public void listForOwnerReturnsActiveBeforeRevoked() {
        var active = freshRow("active", TokenHasher.mint(), ApiToken.Scope.FULL);
        active.save();
        var revoked = freshRow("revoked", TokenHasher.mint(), ApiToken.Scope.READ_ONLY);
        revoked.revoke();
        revoked.save();

        // Sort spec is "active first, then by created_at DESC" so the
        // operator's freshest live token surfaces at the top of the
        // Settings UI without scrolling past dead history.
        var list = ApiToken.listForOwner("admin");
        assertEquals(2, list.size());
        assertEquals("active", list.get(0).name);
        assertEquals("revoked", list.get(1).name);
    }

    @Test
    public void listForOwnerScopesToOwner() {
        var mine = freshRow("mine", TokenHasher.mint(), ApiToken.Scope.READ_ONLY);
        mine.save();
        var someone = freshRow("someone", TokenHasher.mint(), ApiToken.Scope.READ_ONLY);
        someone.ownerUsername = "other-admin";
        someone.save();

        // Today JClaw is single-admin so this is precautionary, but the
        // ownerUsername scoping is the contract listForOwner promises and
        // future multi-tenancy depends on it staying correct.
        var list = ApiToken.listForOwner("admin");
        assertEquals(1, list.size());
        assertEquals("mine", list.get(0).name);
    }

    private static ApiToken freshRow(String name, String plaintext, ApiToken.Scope scope) {
        var row = new ApiToken();
        row.name = name;
        row.scope = scope;
        row.ownerUsername = "admin";
        row.secretHash = TokenHasher.hash(plaintext);
        row.displayPrefix = TokenHasher.prefix(plaintext);
        return row;
    }
}
