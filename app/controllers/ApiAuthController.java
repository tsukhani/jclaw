package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import play.Play;

import static utils.GsonHolder.INSTANCE;
import play.mvc.Controller;
import play.mvc.Http;
import services.ConfigService;
import services.EventLogger;
import utils.PasswordHasher;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

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

    /** GET /api/auth/status — unauthenticated. Returns whether a password
     *  has been set, so the login/setup routing decision lives client-side. */
    public static void status() {
        var hash = ConfigService.get(PASSWORD_HASH_KEY);
        var passwordSet = hash != null && !hash.isBlank();
        renderJSON(gson.toJson(Map.of("passwordSet", passwordSet)));
    }

    /** POST /api/auth/setup — unauthenticated but only accepted when no
     *  password has been set yet. Body: {@code {"password":"..."}}.
     *  Returning 409 when a password already exists closes a race where a
     *  second actor could overwrite the first install's credentials. */
    public static void setup() {
        try {
            var existing = ConfigService.get(PASSWORD_HASH_KEY);
            if (existing != null && !existing.isBlank()) {
                response.status = 409;
                renderJSON(gson.toJson(Map.of(
                        "type", "error",
                        "code", "already_set",
                        "message", "Password is already set")));
                return;
            }
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            var body = JsonParser.parseReader(reader).getAsJsonObject();
            var password = body.get("password").getAsString();
            if (password.length() < 8) {
                response.status = 400;
                renderJSON(gson.toJson(Map.of(
                        "type", "error",
                        "code", "password_too_short",
                        "message", "Password must be at least 8 characters")));
                return;
            }
            ConfigService.set(PASSWORD_HASH_KEY, PasswordHasher.hash(password));
            EventLogger.info("auth", "Admin password set for the first time");
            renderJSON(gson.toJson(Map.of("status", "ok")));
        }
        catch (Exception e) {
            badRequest();
        }
    }

    public static void login() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            var body = JsonParser.parseReader(reader).getAsJsonObject();

            var username = body.get("username").getAsString();
            var password = body.get("password").getAsString();

            var expectedUser = Play.configuration.getProperty("jclaw.admin.username", "admin");
            var storedHash = ConfigService.get(PASSWORD_HASH_KEY);

            if (storedHash == null || storedHash.isBlank()) {
                // Fresh install (or post-reset) — clients should route to
                // /setup-password via /api/auth/status. Surface the same
                // 401 as an invalid login so a curler can't tell whether
                // any account exists.
                response.status = 401;
                renderJSON("{\"error\":\"Invalid credentials\"}");
                return;
            }

            if (constantTimeEquals(expectedUser, username)
                    && PasswordHasher.verify(password, storedHash)) {
                session.put("authenticated", "true");
                session.put("username", username);
                EventLogger.info("auth", "Admin login successful");
                var result = new HashMap<String, Object>();
                result.put("status", "ok");
                result.put("username", username);
                renderJSON(gson.toJson(result));
            }
            else {
                EventLogger.warn("auth", "Admin login failed for username: %s".formatted(username));
                response.status = 401;
                renderJSON("{\"error\":\"Invalid credentials\"}");
            }
        }
        catch (Exception e) {
            badRequest();
        }
    }

    public static void logout() {
        session.clear();
        EventLogger.info("auth", "Admin logged out");
        var result = new HashMap<String, Object>();
        result.put("status", "ok");
        renderJSON(gson.toJson(result));
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
    public static void resetPassword() {
        var authed = session.get("authenticated");
        if (!"true".equals(authed)) {
            response.status = 401;
            renderJSON("{\"error\":\"Authentication required\"}");
            return;
        }
        ConfigService.delete(PASSWORD_HASH_KEY);
        session.clear();
        EventLogger.info("auth", "Admin password reset — user signed out");
        renderJSON(gson.toJson(Map.of("status", "ok")));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
