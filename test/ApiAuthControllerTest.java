import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import controllers.ApiAuthController;
import services.ConfigService;

class ApiAuthControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        // ConfigService caches the password-hash row across test runs; without
        // an invalidate after deleteDatabase the controller would see a stale
        // hash from a previously-seeded test and return 409 instead of letting
        // the setup endpoint proceed against the now-empty Config table.
        ConfigService.clearCache();
    }

    private void seedPassword(String plain) {
        // Mirror AuthFixture.seedAdminPassword in a fresh tx so the row commits
        // before subsequent HTTP calls in the same test.
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try { services.Tx.run(() ->
                ConfigService.set(ApiAuthController.PASSWORD_HASH_KEY,
                        utils.PasswordHasher.hash(plain)));
            } catch (Throwable ex) { err.set(ex); }
        });
        try { t.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    @Test
    void statusReturnsFalseWhenPasswordNotSet() {
        var resp = GET("/api/auth/status");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"passwordSet\":false"));
    }

    @Test
    void statusReturnsTrueWhenPasswordSet() {
        seedPassword("changeme");
        var resp = GET("/api/auth/status");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"passwordSet\":true"));
    }

    @Test
    void setupSucceedsOnFreshInstall() {
        var resp = POST("/api/auth/setup", "application/json",
                "{\"password\":\"goodpassword123\"}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""));
        // Subsequent status should now report passwordSet=true.
        assertTrue(getContent(GET("/api/auth/status")).contains("\"passwordSet\":true"));
    }

    @Test
    void setupRejectsShortPasswordWith400() {
        var resp = POST("/api/auth/setup", "application/json",
                "{\"password\":\"short\"}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("password_too_short"));
    }

    @Test
    void setupRejectsPasswordUnderTwelveChars() {
        // JCLAW-741: the floor rose from 8 to 12; an 11-char password is now
        // rejected where it once would have been accepted.
        var resp = POST("/api/auth/setup", "application/json",
                "{\"password\":\"elevenchar1\"}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("password_too_short"), getContent(resp));
    }

    @Test
    void setupRejectsKnownBreachedPasswordWith400() {
        // JCLAW-741: online HIBP is disabled under %test, so this exercises the
        // offline-list fallback. 1qaz2wsx3edc (12 chars, passes the length
        // floor) is a keyboard-walk seeded in conf/common-passwords.txt.
        var resp = POST("/api/auth/setup", "application/json",
                "{\"password\":\"1qaz2wsx3edc\"}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("password_breached"), getContent(resp));
    }

    @Test
    void setupReturns409WhenPasswordAlreadySet() {
        seedPassword("changeme");
        var resp = POST("/api/auth/setup", "application/json",
                "{\"password\":\"otherpass1\"}");
        assertEquals(409, resp.status.intValue());
        assertTrue(getContent(resp).contains("already_set"));
    }

    @Test
    void setupReturns409ForWellFormedRetryOnAlreadySetInstall() {
        // JCLAW-782: the setIfAbsent-backed write must preserve the 409 already_set
        // shape even for a body that clears every validation gate (>=12 chars, not
        // breached) — a well-formed second bootstrap must not overwrite the first.
        seedPassword("changeme");
        var resp = POST("/api/auth/setup", "application/json",
                "{\"password\":\"validlongpass123\"}");
        assertEquals(409, resp.status.intValue());
        assertTrue(getContent(resp).contains("already_set"), getContent(resp));
    }

    @Test
    void setupReturns400OnMalformedBody() {
        var resp = POST("/api/auth/setup", "application/json", "not-a-json-body");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void loginRejectsWhenNoPasswordIsSet() {
        // Fresh install — login should 401 even with otherwise-valid creds so a
        // curler can't enumerate account state.
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"anything\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void loginRejectsWrongPassword() {
        seedPassword("rightpass1");
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"wrongpass\"}");
        assertEquals(401, resp.status.intValue());
        // Canonical error envelope (JCLAW-155): {"type":"error","code":...,"message":...}.
        assertTrue(getContent(resp).contains("invalid_credentials"), getContent(resp));
    }

    @Test
    void loginReturns400OnMalformedBody() {
        var resp = POST("/api/auth/login", "application/json", "{not json");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void logoutAlwaysSucceeds() {
        // No auth required — logout is idempotent and clears whatever session
        // state may exist (or doesn't).
        var resp = POST("/api/auth/logout", "application/json", "{}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""));
    }

    @Test
    void resetPasswordRequiresAuth() {
        seedPassword("changeme");
        // No login() first → 401.
        var resp = POST("/api/auth/reset-password", "application/json", "{}");
        assertEquals(401, resp.status.intValue());
        // Canonical error envelope (JCLAW-155): {"type":"error","code":...,"message":...}.
        assertTrue(getContent(resp).contains("authentication_required"), getContent(resp));
    }

    @Test
    void resetPasswordClearsHashAndSession() {
        seedPassword("changeme");
        // Authenticate first so the controller proceeds past the gate.
        var loginResp = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}");
        assertIsOk(loginResp);

        var resp = POST("/api/auth/reset-password", "application/json", "{}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""));
        // After reset the password is no longer set.
        assertTrue(getContent(GET("/api/auth/status")).contains("\"passwordSet\":false"));
    }
}
