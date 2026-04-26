package controllers;

import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;
import services.ConfigService;

/**
 * Auth interceptor. Use @With(AuthCheck.class) on controllers that require authentication.
 */
public class AuthCheck extends Controller {

    @Before
    static void checkAuthentication() {
        var path = Http.Request.current().path;

        // Webhook endpoints are verified by their own signature mechanisms
        if (path.startsWith("/api/webhooks/")) {
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
}
