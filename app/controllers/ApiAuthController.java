package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import play.Play;
import play.mvc.Controller;
import services.BreachedPasswordChecker;
import services.ConfigService;
import services.EventLogger;
import utils.ApiResponses;
import utils.PasswordHasher;
import utils.PlayConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static utils.GsonHolder.INSTANCE;

/**
 * Admin authentication.
 *
 * <p>The admin password lives in the Config table under
 * {@link #PASSWORD_HASH_KEY}, hashed via {@link PasswordHasher}. If the row
 * is absent, the application is either freshly installed or the operator
 * deliberately cleared it — both cases surface the same "setup password"
 * flow in the frontend.
 *
 * <p>The {@code jclaw.admin.username} config in application.conf is still
 * read as a display default; the password key there is ignored post-setup.
 */
public class ApiAuthController extends Controller {

    private static final Gson gson = INSTANCE;
    public static final String PASSWORD_HASH_KEY = "auth.admin.passwordHash";

    // JCLAW-741: password policy (length over complexity, NIST 800-63B) — a
    // minimum length and a sane maximum that bounds per-attempt PBKDF2 cost,
    // no composition rules. The floor is enforced here (authoritative); the
    // frontend strength meter is advisory above it.
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;

    // JCLAW-741: failed-login throttle tunables (application.conf overridable).
    private static final String CFG_LOGIN_MAX_FAILURES = "auth.login.rate-limit.max-failures";
    private static final String CFG_LOGIN_WINDOW_SECONDS = "auth.login.rate-limit.window-seconds";
    private static final int DEFAULT_LOGIN_MAX_FAILURES = 10;
    private static final long DEFAULT_LOGIN_WINDOW_SECONDS = 300L;

    public record AuthStatusResponse(boolean passwordSet) {}

    public record SetupRequest(String password) {}

    public record SetupOkResponse(String status) {}

    public record LoginRequest(String username, String password) {}

    public record LoginResponse(String status, String username) {}

    public record LogoutResponse(String status) {}

    public record ResetPasswordResponse(String status) {}

    /** GET /api/auth/status — unauthenticated. Returns whether a password
     *  has been set, so the login/setup routing decision lives client-side. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AuthStatusResponse.class)))
    public static void status() {
        var hash = ConfigService.get(PASSWORD_HASH_KEY);
        var passwordSet = hash != null && !hash.isBlank();
        renderJSON(gson.toJson(new AuthStatusResponse(passwordSet)));
    }

    /** POST /api/auth/setup — unauthenticated but only accepted when no
     *  password has been set yet. Body: {@code {"password":"..."}}.
     *  Returning 409 when a password already exists closes a race where a
     *  second actor could overwrite the first install's credentials. */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SetupRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SetupOkResponse.class)))
    public static void setup() {
        // JCLAW-764 / AD-1 (defense-in-depth, VulnHunter follow-up): setup() is
        // unauthenticated and not behind @With(AuthCheck.class), so the provenance gate
        // never runs here. It is currently safe only because it 409s once a password
        // exists (below); gate it too — consistently with resetPassword/logout — so its
        // safety no longer depends on that precondition surviving a future refactor. An
        // app-originated caller must never reach the credential-bootstrap path.
        if (AppOriginGate.isBlocked()) {
            ApiResponses.error(403, "app_scope", "App-originated request may not set up the password");
        }
        var existing = ConfigService.get(PASSWORD_HASH_KEY);
        if (existing != null && !existing.isBlank()) {
            ApiResponses.error(409, "already_set", "Password is already set");
        }
        // JCLAW-674: parse via the shared JsonBodyReader so ONLY a malformed
        // body maps to 400. The prior broad catch (Exception) also turned a
        // genuine infra failure in ConfigService.set / PasswordHasher.hash into
        // a misleading 400; keeping those outside any catch lets them surface
        // as Play's 500 instead.
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();
        var password = JsonBodyReader.optString(body, "password", false);
        if (password == null) badRequest();
        if (password.length() < MIN_PASSWORD_LENGTH) {
            ApiResponses.error(400, "password_too_short",
                    "Password must be at least %d characters".formatted(MIN_PASSWORD_LENGTH));
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            ApiResponses.error(400, "password_too_long",
                    "Password must be at most %d characters".formatted(MAX_PASSWORD_LENGTH));
        }
        // JCLAW-741: reject passwords found in a known breach. Never blocks on
        // the network — a slow/unreachable HIBP lookup degrades to the offline
        // list (see BreachedPasswordChecker).
        if (BreachedPasswordChecker.isBreached(password)) {
            ApiResponses.error(400, "password_breached",
                    "This password appears in a known data breach. Choose a different one.");
        }
        ConfigService.set(PASSWORD_HASH_KEY, PasswordHasher.hash(password));
        EventLogger.info("auth", "Admin password set for the first time");
        renderJSON(gson.toJson(new SetupOkResponse("ok")));
    }

    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = LoginRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = LoginResponse.class)))
    public static void login() {
        // JCLAW-741: throttle brute-force / PBKDF2 CPU-exhaustion before any
        // work. Keyed on the socket peer; a source with too many recent
        // failures is rejected with 429 without touching the DB or the
        // 600k-iteration verify. A stale lock-out window auto-clears.
        // request.remoteAddress is null in the functional-test harness (Netty
        // always populates it in production). Coalesce so the throttle's
        // ConcurrentHashMap key is never null.
        String clientIp = request.remoteAddress != null ? request.remoteAddress : "unknown";
        if (!LoginRateLimiter.allow(clientIp, loginMaxFailures(), loginWindowSeconds())) {
            EventLogger.warn("auth", "Login throttled for %s (too many failed attempts)".formatted(clientIp));
            ApiResponses.error(429, "too_many_attempts", "Too many login attempts. Try again later.");
        }

        // JCLAW-674: same migration as setup() — malformed body → 400 via
        // JsonBodyReader; ConfigService.get / PasswordHasher.verify run outside
        // any catch so a genuine infra failure surfaces as 500, not a
        // misleading 400.
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();
        var username = JsonBodyReader.optString(body, "username", false);
        var password = JsonBodyReader.optString(body, "password", false);
        if (username == null || password == null) badRequest();

        var expectedUser = Play.configuration.getProperty("jclaw.admin.username", "admin");
        var storedHash = ConfigService.get(PASSWORD_HASH_KEY);

        if (storedHash == null || storedHash.isBlank()) {
            // Fresh install (or post-reset) — clients should route to
            // /setup-password via /api/auth/status. Surface the same
            // 401 as an invalid login so a curler can't tell whether
            // any account exists.
            ApiResponses.error(401, "invalid_credentials", "Invalid credentials");
        }

        if (constantTimeEquals(expectedUser, username)
                && PasswordHasher.verify(password, storedHash)) {
            LoginRateLimiter.recordSuccess(clientIp);
            session.put("authenticated", "true");
            session.put("username", username);
            // JCLAW-731: transparent rehash-on-login. We hold the plaintext and
            // the verify just succeeded, so upgrade a hash written at an older,
            // weaker PBKDF2 work factor to the current one. Best-effort — a
            // ConfigService hiccup must never fail an otherwise-valid login.
            if (PasswordHasher.needsRehash(storedHash)) {
                try {
                    ConfigService.set(PASSWORD_HASH_KEY, PasswordHasher.hash(password));
                    EventLogger.info("auth", "Upgraded admin password hash to the current work factor");
                }
                catch (Exception e) {
                    EventLogger.warn("auth", "Password-hash upgrade failed (login still succeeds): " + e.getMessage());
                }
            }
            EventLogger.info("auth", "Admin login successful");
            renderJSON(gson.toJson(new LoginResponse("ok", username)));
        }
        else {
            LoginRateLimiter.recordFailure(clientIp, loginWindowSeconds());
            EventLogger.warn("auth", "Admin login failed for username: %s".formatted(username));
            ApiResponses.error(401, "invalid_credentials", "Invalid credentials");
        }
    }

    private static int loginMaxFailures() {
        return PlayConfig.intOr(CFG_LOGIN_MAX_FAILURES, DEFAULT_LOGIN_MAX_FAILURES);
    }

    private static long loginWindowSeconds() {
        return PlayConfig.intOr(CFG_LOGIN_WINDOW_SECONDS, (int) DEFAULT_LOGIN_WINDOW_SECONDS);
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = LogoutResponse.class)))
    public static void logout() {
        // JCLAW-764 / AD-1: same rationale as resetPassword — an app-originated
        // caller must not be able to log the operator out via the ambient cookie.
        if (AppOriginGate.isBlocked()) {
            ApiResponses.error(403, "app_scope", "App-originated request may not log the operator out");
        }
        session.clear();
        EventLogger.info("auth", "Admin logged out");
        renderJSON(gson.toJson(new LogoutResponse("ok")));
    }

    /**
     * POST /api/auth/reset-password — authenticated. Wipes the stored hash
     * and clears the current session so the user is immediately signed out.
     * Next access routes through the /setup-password flow via the frontend
     * middleware's {@code checkPasswordSet()} gate.
     *
     * <p>This is deliberately not the same shape as {@link #setup} — setup
     * is first-install (unauthenticated, 409s if already set); reset is
     * "I'm the current admin, I want to start over" (authenticated, always
     * succeeds). Separate endpoints keep the authentication-required gate
     * on reset and the no-auth gate on setup from getting tangled.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ResetPasswordResponse.class)))
    public static void resetPassword() {
        // JCLAW-764 / AD-1: this endpoint is session-sensitive but not behind
        // @With(AuthCheck.class) (setup/login must stay unauthenticated), so the
        // AuthCheck provenance gate never runs here. A same-origin app rides the
        // operator cookie — block an app-originated call before it can wipe the
        // admin credential.
        if (AppOriginGate.isBlocked()) {
            ApiResponses.error(403, "app_scope", "App-originated request may not reset the password");
        }
        var authed = session.get("authenticated");
        if (!"true".equals(authed)) {
            ApiResponses.error(401, "authentication_required", "Authentication required");
        }
        ConfigService.delete(PASSWORD_HASH_KEY);
        session.clear();
        EventLogger.info("auth", "Admin password reset — user signed out");
        renderJSON(gson.toJson(new ResetPasswordResponse("ok")));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
