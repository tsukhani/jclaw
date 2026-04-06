package controllers;

import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;

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
            unauthorized("Authentication required");
        }
    }
}
