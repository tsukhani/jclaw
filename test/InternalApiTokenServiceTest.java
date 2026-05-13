import models.ApiToken;
import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.InternalApiTokenService;
import utils.TokenHasher;

/**
 * Verify the auto-bootstrap and self-healing behavior of
 * {@link InternalApiTokenService} (JCLAW-282).
 *
 * <p>Three invariants this layer must guarantee:
 * <ol>
 *   <li>First call mints a token and persists both halves (config row
 *       carrying the plaintext, ApiToken row carrying the hash).</li>
 *   <li>Subsequent calls reuse the existing token instead of minting a
 *       new one on every boot (which would leave dead rows behind).</li>
 *   <li>If the ApiToken row is wiped but the config row survives (or
 *       vice-versa), the next call re-mints both — the system can't be
 *       left in a "stored plaintext but no row to validate against"
 *       state where {@code jclaw_api} starts hitting 401.</li>
 * </ol>
 */
public class InternalApiTokenServiceTest extends UnitTest {

    @BeforeEach
    void clearState() {
        ApiToken.deleteAll();
        ConfigService.delete(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);
        InternalApiTokenService.invalidateCache();
    }

    @AfterEach
    void cleanup() {
        ApiToken.deleteAll();
        ConfigService.delete(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);
        InternalApiTokenService.invalidateCache();
    }

    @Test
    public void firstCallMintsTokenAndPersists() {
        var token = InternalApiTokenService.token();
        assertNotNull(token);
        assertTrue(token.startsWith(TokenHasher.TOKEN_PREFIX),
                "minted token should carry the jcl_ prefix so it's recognizable in logs; got: " + token);

        // Config row carries the plaintext for the tool's HTTP call.
        var stored = ConfigService.get(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);
        assertEquals(token, stored);

        // ApiToken row carries the hash for AuthCheck to validate against.
        var row = ApiToken.findActiveByPlaintext(token);
        assertNotNull(row);
        assertEquals(InternalApiTokenService.SYSTEM_OWNER, row.ownerUsername);
    }

    @Test
    public void subsequentCallsReuseStoredToken() {
        var first = InternalApiTokenService.token();
        InternalApiTokenService.invalidateCache();
        var second = InternalApiTokenService.token();
        assertEquals(first, second,
                "second call should read the cached config row, not mint fresh");
        // And only ONE ApiToken row exists.
        long rows = ApiToken.count();
        assertEquals(1L, rows,
                "expected exactly one ApiToken row after bootstrap+reuse; got: " + rows);
    }

    @Test
    public void reMintsWhenApiTokenRowMissing() {
        var first = InternalApiTokenService.token();
        // Simulate: operator manually deleted the ApiToken row (e.g.
        // cleanup script) but the config row survives. The next boot
        // must repair the gap instead of leaving the tool unable to auth.
        ApiToken.deleteAll();
        InternalApiTokenService.invalidateCache();

        var second = InternalApiTokenService.token();
        assertNotEquals(first, second,
                "stale plaintext should be replaced when its row is gone");

        // And the new row really exists.
        var row = ApiToken.findActiveByPlaintext(second);
        assertNotNull(row, "self-healing path must create a fresh ApiToken row");
    }

    @Test
    public void mintsFreshWhenConfigRowMissing() {
        InternalApiTokenService.token();
        // Inverse scenario: config row wiped (e.g. via an admin's
        // /api/config DELETE before we filtered the prefix). Cache
        // invalidation forces a re-read; since the config row is gone
        // we mint a fresh one.
        ConfigService.delete(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);
        InternalApiTokenService.invalidateCache();

        var fresh = InternalApiTokenService.token();
        var stored = ConfigService.get(InternalApiTokenService.INTERNAL_TOKEN_CONFIG_KEY);
        assertEquals(fresh, stored,
                "missing config row should be repopulated by the bootstrap path");
    }
}
