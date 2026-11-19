import models.ApiToken;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.InternalApiTokenService;
import services.Tx;
import utils.TokenHasher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JCLAW-1034: the internal bearer has a lifecycle — it can expire, it can be revoked, and
 * revoking it sticks.
 *
 * <p>Scope note: the session half of the story (a cookie minted under an older credential
 * generation is refused) is NOT exercised end-to-end here. Advancing the generation writes a
 * process-global config row that {@link controllers.AuthCheck} reads on every authenticated
 * request, and play1 runs test classes concurrently — a test that bumped it would sign every
 * sibling class's session out mid-run. The comparison itself is one equality in AuthCheck; what
 * would need a live check is the reset path, and that belongs in a serialized harness rather
 * than bought at the cost of making the suite flaky.
 */
class CredentialLifecycleTest extends UnitTest {

    private record Seeded(String plaintext, ApiToken row) {}

    private static Seeded seedToken(Instant expiresAt, Instant revokedAt) {
        return Tx.run(() -> {
            var plaintext = TokenHasher.mint();
            var row = new ApiToken();
            row.ownerUsername = "lifecycle-test";
            row.secretHash = TokenHasher.hash(plaintext);
            row.expiresAt = expiresAt;
            row.revokedAt = revokedAt;
            row.save();
            return new Seeded(plaintext, row);
        });
    }

    @Test
    void anOpenEndedTokenStillAuthenticates() {
        // CONTROL: the columns are nullable and every row minted before they existed has both
        // null, so the common case must be untouched.
        var seeded = seedToken(null, null);
        assertNotNull(Tx.run(() -> ApiToken.findActiveByPlaintext(seeded.plaintext())),
                "a token with no expiry and no revocation must still authenticate");
    }

    @Test
    void anExpiredTokenIsRefused() {
        var seeded = seedToken(Instant.now().minus(1, ChronoUnit.MINUTES), null);
        assertNull(Tx.run(() -> ApiToken.findActiveByPlaintext(seeded.plaintext())),
                "an expired token must not authenticate");
    }

    @Test
    void aFutureExpiryStillAuthenticates() {
        var seeded = seedToken(Instant.now().plus(1, ChronoUnit.HOURS), null);
        assertNotNull(Tx.run(() -> ApiToken.findActiveByPlaintext(seeded.plaintext())),
                "a token expiring later must still authenticate now");
    }

    @Test
    void aRevokedTokenIsRefused() {
        var seeded = seedToken(null, Instant.now());
        assertNull(Tx.run(() -> ApiToken.findActiveByPlaintext(seeded.plaintext())),
                "a revoked token must not authenticate");
    }

    @Test
    void aRevokedTokenIsStillFindableSoRevocationCanBeDistinguishedFromAbsence() {
        // The whole point of recording revokedAt rather than deleting the row: ensureToken has
        // to tell "someone withdrew this" from "the row went missing", and it can only do that
        // if the withdrawn row is still there to find.
        var seeded = seedToken(null, Instant.now());
        var found = Tx.run(() -> ApiToken.findAnyByPlaintext(seeded.plaintext()));
        assertNotNull(found, "a revoked row must remain findable");
        assertNotNull(found.revokedAt, "and must carry its revocation timestamp");
        assertNull(Tx.run(() -> ApiToken.findAnyByPlaintext(TokenHasher.mint())),
                "an unknown plaintext still resolves to nothing");
    }

    @Test
    void isActiveAgreesWithTheLookup() {
        assertTrue(new ApiToken().isActive(), "a fresh row with neither column set is active");

        var expired = new ApiToken();
        expired.expiresAt = Instant.now().minusSeconds(1);
        assertTrue(!expired.isActive(), "an elapsed expiry is not active");

        var revoked = new ApiToken();
        revoked.revokedAt = Instant.now();
        assertTrue(!revoked.isActive(), "a revoked row is not active");
    }

    @Test
    void revokingTheInternalTokenIsNotUndoneByTheNextCall() {
        // The defect this story names: ensureToken re-minted whenever the lookup came back
        // empty, and a revoked row comes back empty — so withdrawing the internal credential
        // silently issued a replacement. Revocation must survive a subsequent token() call.
        var before = InternalApiTokenService.token();
        assertNotNull(before);

        Tx.run(() -> {
            var row = ApiToken.findAnyByPlaintext(before);
            row.revokedAt = Instant.now();
            row.save();
            return null;
        });

        // finally, not a trailing statement: this row is shared by the whole JVM and play1 runs
        // classes concurrently, so a failed assertion here must not leave every later test
        // holding a revoked internal credential.
        try {
            var after = InternalApiTokenService.token();
            assertEquals(before, after,
                    "a revoked internal token must not be replaced by a fresh one");
            assertNull(Tx.run(() -> ApiToken.findActiveByPlaintext(after)),
                    "and the token it hands back must still fail authentication");
        }
        finally {
            Tx.run(() -> {
                var row = ApiToken.findAnyByPlaintext(before);
                if (row != null) {
                    row.revokedAt = null;
                    row.save();
                }
                return null;
            });
        }
    }
}
