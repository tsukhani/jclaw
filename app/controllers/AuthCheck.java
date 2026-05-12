package controllers;

import models.ApiToken;
import play.Play;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;
import services.ConfigService;

/**
 * Auth interceptor. Use @With(AuthCheck.class) on controllers that require authentication.
 *
 * <p>Two auth paths are accepted, in this order:
 *
 * <ul>
 *   <li><b>Bearer token</b> — {@code Authorization: Bearer <plaintext>}
 *       resolves to an {@link ApiToken} row (JCLAW-282). The token's owner
 *       becomes the session username for downstream ownership checks.
 *       Read-only scoped tokens are restricted to GET; mutating verbs 403
 *       before the controller runs.</li>
 *   <li><b>Session cookie</b> — Play 1.x's stateless session, populated by
 *       {@code POST /api/auth/login}. Cross-checked against the DB to catch
 *       stale cookies that survived a password wipe (see comment in body).</li>
 * </ul>
 */
public class AuthCheck extends Controller {

    @Before
    static void checkAuthentication() {
        var path = Http.Request.current().path;

        // Webhook endpoints are verified by their own signature mechanisms
        if (path.startsWith("/api/webhooks/")) {
            return;
        }

        // Bearer-token path takes precedence over session cookie. If the
        // header is present but invalid we 401 here without falling through
        // to the session path — clients sending a token expect it to be the
        // identity, and silently honoring a stale session cookie they didn't
        // intend to use would be surprising.
        var bearer = readBearerToken();
        if (bearer != null) {
            authenticateByBearer(bearer);
            return;
        }

        var authenticated = session.get("authenticated");
        if (!"true".equals(authenticated)) {
            response.status = 401;
            renderJSON("{\"error\":\"Authentication required\"}");
        }

        // Session bit alone isn't sufficient. Play 1.x sessions are stateless
        // cookies signed with application.secret — nothing about DB state
        // goes into the signature. So a cookie minted before a password
        // reset (or surviving a fresh install where the password row was
        // wiped) is still cryptographically valid forever and would pass
        // the .equals("true") check above. Cross-check against the DB: if
        // no password is set, the session can't possibly be legitimate.
        // Clear the stale session so the next request arrives unauthenticated
        // and lands on the natural setup-password flow. The password_unset
        // code lets the SPA distinguish "DB has no admin yet" from generic
        // "needs login" if it ever wants to. ConfigService.get is cached
        // with TTL, so this is essentially free per request after the first.
        var hash = ConfigService.get(ApiAuthController.PASSWORD_HASH_KEY);
        if (hash == null || hash.isBlank()) {
            session.clear();
            response.status = 401;
            renderJSON("{\"error\":\"Authentication required\",\"code\":\"password_unset\"}");
        }
    }

    /** Pull the bearer token out of the Authorization header, if any.
     *  Returns null when no Authorization header is present so the
     *  caller can fall through to the session path. Empty/malformed
     *  Bearer headers still return null — the bearer path is opt-in
     *  per request, and a malformed header should be treated as "no
     *  bearer credential supplied" rather than rejected outright. */
    private static String readBearerToken() {
        var header = Http.Request.current().headers.get("authorization");
        if (header == null) return null;
        var value = header.value();
        if (value == null) return null;
        var trimmed = value.trim();
        var lower = trimmed.toLowerCase();
        if (!lower.startsWith("bearer ")) return null;
        var token = trimmed.substring("bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** Marker key set on {@code Http.Request.current().args} when the
     *  request was admitted via a bearer token rather than a session
     *  cookie. Controllers that should refuse the bearer path (e.g.
     *  token CRUD, password reset) check this before proceeding. */
    public static final String AUTH_METHOD_KEY = "jclaw.authMethod";
    public static final String AUTH_METHOD_BEARER = "bearer";

    /** True if the current request was admitted via a bearer token. */
    public static boolean isBearerRequest() {
        return AUTH_METHOD_BEARER.equals(Http.Request.current().args.get(AUTH_METHOD_KEY));
    }

    /** Resolve a bearer token to its owner + scope, populating the
     *  session so downstream code reads identity the same way it does
     *  after a cookie login. Renders 401/403 directly on rejection. */
    private static void authenticateByBearer(String plaintext) {
        var token = ApiToken.findActiveByPlaintext(plaintext);
        if (token == null) {
            response.status = 401;
            renderJSON("{\"error\":\"Invalid or revoked token\",\"code\":\"invalid_token\"}");
            return;
        }

        if (token.scope == ApiToken.Scope.READ_ONLY
                && !"GET".equalsIgnoreCase(Http.Request.current().method)) {
            response.status = 403;
            renderJSON("{\"error\":\"Token is read-only\",\"code\":\"token_read_only\"}");
            return;
        }

        // Stash identity in the request-local session so controllers that
        // read session.get("username") keep working transparently. Play 1.x
        // will emit a Set-Cookie on the response — harmless for the bearer
        // case (the MCP server doesn't keep a cookie jar) and the cheapest
        // way to avoid sprinkling "bearer or cookie?" branches everywhere.
        session.put("authenticated", "true");
        session.put("username", token.ownerUsername);
        Http.Request.current().args.put(AUTH_METHOD_KEY, AUTH_METHOD_BEARER);

        // Audit the usage in the same JPA tx as the request. The cost is
        // one extra UPDATE per bearer call — negligible for the admin-
        // grade token traffic this serves, and the synchronous write
        // keeps tests deterministic (an earlier async-on-virtual-thread
        // attempt raced with the test's subsequent read). Skip the
        // re-fetch — `token` is already the managed entity from the
        // findActiveByPlaintext query above.
        token.markUsed();
        token.save();
    }

    /** Read the configured admin username — the only valid {@link ApiToken#ownerUsername}
     *  in the single-admin model. Tokens minted by this admin should resolve
     *  to this same username on subsequent requests so downstream ownership
     *  checks line up with the cookie-auth path. */
    static String configuredAdminUsername() {
        return Play.configuration.getProperty("jclaw.admin.username", "admin");
    }
}
