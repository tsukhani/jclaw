package controllers;

import models.ApiToken;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;
import services.ConfigService;
import services.Tx;

/**
 * Auth interceptor. Use @With(AuthCheck.class) on controllers that require authentication.
 *
 * <p>Two auth paths are accepted, in this order:
 *
 * <ul>
 *   <li><b>Bearer token</b> — {@code Authorization: Bearer <plaintext>}
 *       resolves to an {@link ApiToken} row (JCLAW-282). The token's
 *       owner becomes the session username so downstream identity reads
 *       see a stable value. After the external-surface drop, the only
 *       bearer issuer in-tree is
 *       {@link services.InternalApiTokenService} (the {@code jclaw_api}
 *       tool's loopback-auth pathway), so every bearer admission is
 *       effectively the system principal calling its own API.</li>
 *   <li><b>Session cookie</b> — Play 1.x's stateless session, populated by
 *       {@code POST /api/auth/login}. Cross-checked against the DB to catch
 *       stale cookies that survived a password wipe (see comment in body).</li>
 * </ul>
 */
public class AuthCheck extends Controller {

    @Before
    static void checkAuthentication() {
        var path = Http.Request.current().path;

        // Webhook endpoints are verified by their own signature mechanisms.
        //
        // Belt-and-braces: this branch does not currently fire. The webhook controllers
        // carry no @With(AuthCheck.class), and Play builds a request's @Before set solely
        // from the resolved controller's own class hierarchy plus its @With chain
        // (ActionInvoker.handleBefores -> Java.findAllAnnotatedMethods), so this
        // interceptor is not in their chain to begin with. Kept because it is the
        // cheap half of a fail-safe: it only becomes load-bearing if a webhook
        // controller is ever annotated, and then it is the difference between a
        // working webhook and a 401 nobody can explain. The surface it guards is
        // pinned by WebhookControllerTest.theUnauthenticatedWebhookSurfaceIsExactlyTheseRoutes.
        if (path.startsWith("/api/webhooks/")) {
            return;
        }

        // JCLAW-1034: the checks that apply to EVERY principal run before the bearer branch.
        // It used to return first, which meant a bearer call skipped both the password
        // cross-check and AppOriginGate — the credential with the widest authority was the
        // one exempt from the instance-wide gates.
        requireProvisionedInstance();
        if (AppOriginGate.isBlocked()) {
            response.status = 403;
            renderJSON("{\"error\":\"App may only invoke its own agent\",\"code\":\"app_scope\"}");
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

        // JCLAW-1034: a cookie minted under an older credential is no longer honored, so
        // changing or resetting the password logs every other session out. Checked only on
        // the cookie path — a bearer carries no generation and is revoked through its own row.
        switch (ApiAuthController.sessionRejection()) {
            case null -> { /* live operator session */ }
            case CREDENTIALS_CHANGED -> {
                session.clear();
                response.status = 401;
                renderJSON("{\"error\":\"Authentication required\",\"code\":\"credentials_changed\"}");
            }
            case NOT_AUTHENTICATED -> {
                response.status = 401;
                renderJSON("{\"error\":\"Authentication required\"}");
            }
        }

    }

    /**
     * Refuse everything until an admin password exists. Play 1.x sessions are stateless
     * cookies signed with application.secret — nothing about DB state goes into the
     * signature — so a cookie minted before a password reset (or surviving a fresh install
     * where the password row was wiped) stays cryptographically valid forever. The
     * password_unset code lets the SPA tell "DB has no admin yet" from a generic "needs
     * login". ConfigService.get is TTL-cached, so this is essentially free per request.
     *
     * <p>JCLAW-1034: applies to the bearer principal too. An unprovisioned instance has no
     * operator, so nothing on it should be driving the API on that operator's behalf.
     */
    private static void requireProvisionedInstance() {
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

    /** Resolve a bearer token to its row and populate the session so
     *  downstream code reads identity the same way it does after a
     *  cookie login. Renders 401 directly on rejection. */
    private static void authenticateByBearer(String plaintext) {
        // JCLAW-849: this interceptor runs before actions that opt out of
        // Play's per-request transaction with @NoTransaction — streamChat and
        // the other SSE endpoints on ApiChatController, ApiAppInvokeController
        // and ApiEventsController. Without an explicit transaction the lookup
        // below threw "No active EntityManager" and every bearer-authenticated
        // call to those routes 500'd. Tx.run opens one only when none is
        // already open, so transactional routes keep sharing the request's tx
        // exactly as before.
        //
        // The lookup, the usage audit and the save must sit inside the SAME
        // block: markUsed()/save() need `token` to still be managed. Only the
        // owner name escapes, so nothing is read off a detached entity. The 60s
        // throttle in ApiToken.markUsed() keeps most calls in a burst no-ops at
        // the Hibernate dirty-check level, which is what keeps the
        // findActiveByPlaintext query-cache entry valid across the burst.
        var ownerUsername = Tx.run(() -> {
            var token = ApiToken.findActiveByPlaintext(plaintext);
            if (token == null) return null;
            token.markUsed();
            token.save();
            return token.ownerUsername;
        });

        // Rejection renders outside the block — a Result thrown from inside
        // would unwind through the transaction on its way out.
        if (ownerUsername == null) {
            response.status = 401;
            renderJSON("{\"error\":\"Invalid token\",\"code\":\"invalid_token\"}");
            return;
        }

        // Stash identity in the request-local session so controllers that
        // read session.get("username") keep working transparently. Play 1.x
        // will emit a Set-Cookie on the response — harmless for the bearer
        // case (the jclaw_api tool doesn't keep a cookie jar) and the
        // cheapest way to avoid sprinkling "bearer or cookie?" branches
        // everywhere.
        session.put("authenticated", "true");
        session.put("username", ownerUsername);
        // JCLAW-1023: record HOW this request authenticated, not just as whom. The two lines
        // above make a bearer call indistinguishable from an operator login downstream, which
        // is what let an agent reach operator-only writes. See RequestPrincipal.
        session.put(RequestPrincipal.PRINCIPAL_KEY, RequestPrincipal.AGENT);
    }
}
