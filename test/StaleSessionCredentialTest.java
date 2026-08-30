import controllers.ApiAuthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConfigService;

import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1121: a session cookie is usable only while it matches the live credential
 * generation, on every surface that reads one.
 *
 * <p>Play 1.x sessions are stateless cookies signed with {@code application.secret} — no DB
 * state enters the signature — so a cookie stays cryptographically valid forever. JCLAW-1034
 * added the {@code auth.credentialVersion} counter so a password change or reset can revoke
 * sessions that already exist, but applied it only in {@link controllers.AuthCheck}. The two
 * readers that cannot sit behind that interceptor — {@code POST /api/auth/reset-password},
 * which must stay reachable pre-login, and the voice WebSocket, which Play does not route
 * through {@code @With} — checked the flag alone, so a superseded cookie could still wipe a
 * newly set password.
 *
 * <p>Bumping the counter directly is the faithful simulation: it is what a reset performed
 * from another browser does to <em>this</em> session, and unlike a real reset+setup cycle it
 * leaves the stale session in place to be replayed.
 */
class StaleSessionCredentialTest extends FunctionalTest {

    /**
     * The generation counter's config key. Under the reserved {@code auth.} prefix the
     * constant is package-private on the controller, so it is spelled literally here — which
     * also pins the persisted key name against a rename.
     */
    private static final String CREDENTIAL_VERSION_KEY = "auth.credentialVersion";

    private static final String PASSWORD = "correcthorsebattery";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        // ConfigService caches rows across tests; without this the controller reads a hash
        // (or a generation) seeded by an earlier test against the now-empty Config table.
        ConfigService.clearCache();
    }

    /** Commit in a fresh tx so the row is visible to the HTTP calls that follow. */
    private static void commitConfig(String key, String value) {
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try { services.Tx.run(() -> ConfigService.set(key, value)); }
            catch (Throwable ex) { err.set(ex); }
        });
        try { t.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        if (err.get() != null) throw new RuntimeException(err.get());
        ConfigService.clearCache();
    }

    private void seedPassword() {
        commitConfig(ApiAuthController.PASSWORD_HASH_KEY, utils.PasswordHasher.hash(PASSWORD));
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"%s\"}".formatted(PASSWORD)));
    }

    /** What a password reset performed from another browser does to this session's cookie. */
    private void credentialGenerationMovesOn() {
        commitConfig(CREDENTIAL_VERSION_KEY, "9999");
    }

    @Test
    void aStaleCookieCannotWipeANewlySetPassword() {
        seedPassword();
        login();
        credentialGenerationMovesOn();

        var resp = POST("/api/auth/reset-password", "application/json", "{}");

        assertEquals(401, resp.status.intValue());
        assertTrue(getContent(resp).contains("credentials_changed"), getContent(resp));
        ConfigService.clearCache();
        var hash = ConfigService.get(ApiAuthController.PASSWORD_HASH_KEY);
        assertTrue(hash != null && !hash.isBlank(),
                "the refused reset must leave the stored password intact — wiping it here is "
                        + "the whole defect: the instance drops back to the unprovisioned setup flow");
    }

    @Test
    void aStaleCookieIsRefusedOnInterceptedEndpointsToo() {
        seedPassword();
        login();
        assertIsOk(GET("/api/config"));

        credentialGenerationMovesOn();

        var resp = GET("/api/config");
        assertEquals(401, resp.status.intValue());
        assertTrue(getContent(resp).contains("credentials_changed"), getContent(resp));
    }

    @Test
    void aCurrentSessionStillResets() {
        seedPassword();
        login();

        var resp = POST("/api/auth/reset-password", "application/json", "{}");

        assertIsOk(resp);
        ConfigService.clearCache();
        var hash = ConfigService.get(ApiAuthController.PASSWORD_HASH_KEY);
        assertTrue(hash == null || hash.isBlank(), "a live session must still be able to reset");
    }

    @Test
    void anUnauthenticatedResetStillReportsAuthenticationRequired() {
        seedPassword();

        var resp = POST("/api/auth/reset-password", "application/json", "{}");

        assertEquals(401, resp.status.intValue());
        // Distinct from credentials_changed: never logged in at all, so the SPA routes to
        // login rather than telling the operator their session was revoked.
        assertTrue(getContent(resp).contains("authentication_required"), getContent(resp));
    }
}
