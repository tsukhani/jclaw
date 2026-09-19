import models.ApiToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.InternalApiTokenService;
import utils.TokenHasher;

/**
 * Verify the auto-bootstrap and self-healing behavior of
 * {@link InternalApiTokenService} (JCLAW-282).
 *
 * <p>Since JCLAW-1266 the plaintext is held in the process and never persisted, so the
 * invariants are:
 * <ol>
 *   <li>A mint writes the hash and <em>only</em> the hash — no config row carries the
 *       credential, because a database copy is recoverable by a shell query or a backup.</li>
 *   <li>Subsequent calls reuse the held token rather than minting per boot.</li>
 *   <li>A wiped {@link ApiToken} row still self-heals, so the tool cannot be left
 *       authenticating against nothing (JCLAW-852).</li>
 *   <li>A revocation outlives the process: the plaintext no longer does, so without a
 *       row-level check a restart would mint a replacement and undo it (JCLAW-1034).</li>
 *   <li>An upgrade deletes the legacy plaintext row <em>and</em> the credential it named.</li>
 * </ol>
 */
class InternalApiTokenServiceTest extends UnitTest {

    private void resetState() {
        ApiToken.deleteAll();
        ConfigService.delete(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);
        InternalApiTokenService.resetForTest();
    }

    @BeforeEach
    void clearState() { resetState(); }

    @AfterEach
    void cleanup() { resetState(); }

    @Test
    void firstCallMintsTokenAndPersists() {
        var token = InternalApiTokenService.token();
        assertNotNull(token);
        assertTrue(token.startsWith(TokenHasher.TOKEN_PREFIX),
                "minted token should carry the jcl_ prefix so it's recognizable in logs; got: " + token);

        // The credential must exist nowhere in the database (JCLAW-1266).
        assertNull(ConfigService.get(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY),
                "the plaintext must not be persisted — a config row is recoverable by a shell "
                        + "query, the database file, or a backup archive");

        // ApiToken row carries the hash for AuthCheck to validate against.
        var row = ApiToken.findActiveByPlaintext(token);
        assertNotNull(row);
        assertEquals(InternalApiTokenService.SYSTEM_OWNER, row.ownerUsername);
    }

    @Test
    void subsequentCallsReuseStoredToken() {
        var first = InternalApiTokenService.token();
        var second = InternalApiTokenService.token();
        assertEquals(first, second,
                "second call should reuse the token held in the process, not mint fresh");
        // And only ONE ApiToken row exists.
        long rows = ApiToken.count();
        assertEquals(1L, rows,
                "expected exactly one ApiToken row after bootstrap+reuse; got: " + rows);
    }

    @Test
    void reMintsWhenApiTokenRowMissing() {
        var first = InternalApiTokenService.token();
        // Simulate: operator manually deleted the ApiToken row (e.g. cleanup
        // script, restore from backup) but the config row survives. The next
        // call must repair the gap instead of leaving the tool unable to auth.
        //
        // JCLAW-852: this test used to call invalidateCache() here, which is the
        // only reason it passed — production never invalidated, so the repair
        // below was unreachable and a deleted row broke jclaw_api until the JVM
        // restarted. The absence of that call is now the regression guard.
        ApiToken.deleteAll();

        var second = InternalApiTokenService.token();
        assertNotEquals(first, second,
                "stale plaintext should be replaced when its row is gone");

        // And the new row really exists.
        var row = ApiToken.findActiveByPlaintext(second);
        assertNotNull(row, "self-healing path must create a fresh ApiToken row");
    }

    @Test
    void anUpgradeDeletesTheLegacyPlaintextRowAndTheCredentialItNamed() {
        // The pre-JCLAW-1266 shape: plaintext in config, hash in the row. Deleting the config
        // row alone would leave that credential working for anyone who copied it while it was
        // readable, so the ApiToken row must go with it.
        var legacy = TokenHasher.mint();
        ConfigService.set(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY, legacy);
        var legacyRow = new ApiToken();
        legacyRow.ownerUsername = InternalApiTokenService.SYSTEM_OWNER;
        legacyRow.secretHash = TokenHasher.hash(legacy);
        legacyRow.save();

        var fresh = InternalApiTokenService.token();

        assertNotEquals(legacy, fresh, "the legacy credential must not be handed back");
        assertNull(ConfigService.get(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY),
                "the legacy config row must be gone");
        assertNull(ApiToken.findAnyByPlaintext(legacy),
                "the legacy credential must stop authenticating, not merely stop being stored");
        assertNotNull(ApiToken.findActiveByPlaintext(fresh));
    }

    @Test
    void aRevocationSurvivesTheProcessThatHeldTheToken() {
        var token = InternalApiTokenService.token();
        var row = ApiToken.findActiveByPlaintext(token);
        assertNotNull(row);
        row.revokedAt = utils.AppClock.now();
        row.save();

        // The plaintext no longer survives a restart, so without a row-level check the mint
        // below would hand out a working replacement and quietly undo the revocation.
        InternalApiTokenService.resetForTest();

        assertEquals("", InternalApiTokenService.token(),
                "a revoked system token must not be replaced by a restart; an empty bearer "
                        + "keeps the tool 401ing, which is what revocation means");
    }

    @Test
    void tokenResolvesWithoutAnAmbientTransaction() {
        // JCLAW-852: outside boot the only production caller is JClawApiTool,
        // which runs in the agent tool loop with no transaction open by design.
        // Now that every call re-reads the config and token rows, that path must
        // carry its own transaction or it throws "No active EntityManager" —
        // the same failure JCLAW-849 fixed in the bearer filter.
        //
        // Run on a fresh virtual thread so the test's own transaction is not
        // inherited; an inline call would pass for the wrong reason.
        var result = new String[1];
        var error = new Throwable[1];
        var t = Thread.ofVirtual().start(() -> {
            try {
                result[0] = InternalApiTokenService.token();
            } catch (Throwable e) {
                error[0] = e;
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for token()", e);
        }

        // Assert on the SHAPE of any failure, not on success. This class's
        // @BeforeEach deletes from config and api_token inside the test's own
        // uncommitted transaction, so a genuinely independent transaction can
        // legitimately hit a lock timeout on those rows — that is the harness's
        // state, not a defect. Reaching SQL execution at all is itself proof a
        // transaction was open. The defect this guards against fails earlier and
        // differently, with no EntityManager to execute against.
        var failure = error[0] == null ? "" : error[0].toString();
        assertFalse(failure.contains("No active EntityManager"),
                "token() must carry its own transaction when called off the request path; got: " + failure);
    }
}
