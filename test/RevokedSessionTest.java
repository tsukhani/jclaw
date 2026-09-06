import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.libs.Crypto;
import play.libs.Time;
import play.mvc.CookieDataCodec;
import play.mvc.Http;
import play.mvc.Scope;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConfigService;
import utils.AppClock;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1159: signing out revokes the session by id, so a copy of the cookie that survives
 * the sign-out is refused.
 *
 * <p>Play re-issues the session cookie on every authenticated response. A dashboard request
 * still in flight when the operator signs out therefore restores the cookie the logout just
 * cleared when it completes — observed in the UAT suite as a 200 from {@code /api/agents}
 * 42ms after a successful logout, with the trace showing {@code /api/metrics/cost} landing
 * 96ms after the clearing response. Replaying the pre-logout cookie is the faithful
 * simulation: it is exactly what that late response hands back to the browser.
 */
class RevokedSessionTest extends FunctionalTest {

    private static final String PASSWORD = "correcthorsebattery";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        AuthFixture.seedAdminPassword(PASSWORD);
    }

    /** Log in on a fresh cookie jar and hand back the cookie the login minted. */
    private static Http.Cookie loginAndCaptureCookie() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"%s\"}".formatted(PASSWORD));
        assertIsOk(resp);
        var cookie = resp.cookies.get("PLAY_SESSION");
        assertNotNull(cookie, "login must mint a session cookie");
        return cookie;
    }

    /** Send {@code cookie} alone — not whatever the jar holds now — the way a late response re-sets it. */
    private Http.Response replay(Http.Cookie cookie, String url) {
        clearCookies();
        var request = newRequest();
        request.cookies = new HashMap<>();
        request.cookies.put("PLAY_SESSION", cookie);
        return GET(request, url);
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

    @Test
    void aCookieReplayedAfterSignOutIsRefused() {
        var cookie = loginAndCaptureCookie();
        assertIsOk(GET("/api/config"));

        assertIsOk(POST("/api/auth/logout", "application/json", "{}"));

        var resp = replay(cookie, "/api/config");
        assertEquals(401, resp.status.intValue(),
                "a cookie restored by a late response must be refused after sign-out: " + getContent(resp));
        assertTrue(getContent(resp).contains("session_revoked"), getContent(resp));
    }

    @Test
    void signingOutOneSessionLeavesTheOthersLive() {
        // Per session, not a generation bump: the e2e suite's parallel workers share one
        // cookie from global-setup, and a global revocation would 401 every one of them the
        // moment the sign-out spec ran.
        var first = loginAndCaptureCookie();
        clearCookies();
        var second = loginAndCaptureCookie();

        assertIsOk(POST("/api/auth/logout", "application/json", "{}"));

        assertEquals(200, replay(first, "/api/config").status.intValue(),
                "signing out the second session must not touch the first");
        assertEquals(401, replay(second, "/api/config").status.intValue());
    }

    @Test
    void aRevocationIsForgottenOnceTheCookieItNamesHasExpired() {
        // Entries are pruned on the next sign-out; one whose expiry is already past goes,
        // one still inside its lifetime stays, and the new sign-out is recorded with an
        // expiry one session lifetime out (application.session.maxAge is 1h here).
        long now = AppClock.now().toEpochMilli();
        commitConfig("auth.revokedSessions",
                "{\"expired-sid\":%d,\"live-sid\":%d}".formatted(now - 1, now + 3_600_000L));

        loginAndCaptureCookie();
        assertIsOk(POST("/api/auth/logout", "application/json", "{}"));

        ConfigService.clearCache();
        var stored = ConfigService.get("auth.revokedSessions");
        assertNotNull(stored);
        assertFalse(stored.contains("expired-sid"), "an expired entry must be pruned: " + stored);
        assertTrue(stored.contains("live-sid"), "a live entry must survive the prune: " + stored);
        assertEquals(2, stored.split("\":").length - 1, "live-sid plus the new sign-out, nothing else: " + stored);
    }

    @Test
    void aCookieWithoutAnIdIsNotALogin() throws Exception {
        // A cookie minted before ids existed is validly signed and carries the current
        // generation, but no id. Nothing revoked it, so it is refused as a plain
        // "not authenticated" rather than as revoked. Signed the way the load-test harness
        // signs its own cookie, so the signature is genuine and only the id is absent.
        var data = new HashMap<String, String>();
        data.put("authenticated", "true");
        data.put("username", "admin");
        data.put("cv", ConfigService.get("auth.credentialVersion", "0"));
        if (Scope.COOKIE_EXPIRE != null) {
            data.put("___TS", Long.toString(AppClock.now().toEpochMilli()
                    + Time.parseDuration(Scope.COOKIE_EXPIRE) * 1000L));
        }
        var encoded = CookieDataCodec.encode(data);
        var cookie = new Http.Cookie();
        cookie.name = "PLAY_SESSION";
        cookie.value = Crypto.sign(encoded, Play.secretKey.getBytes()) + "-" + encoded;

        var resp = replay(cookie, "/api/config");
        assertEquals(401, resp.status.intValue(), getContent(resp));
        assertFalse(getContent(resp).contains("session_revoked"),
                "never revoked, so must not be reported as revoked: " + getContent(resp));
    }
}
