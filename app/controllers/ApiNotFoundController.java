package controllers;

import play.mvc.Controller;

/**
 * Clean 404 for unmatched {@code /api/*} paths (JCLAW-336).
 *
 * <p>Without this, Play's trailing {@code {controller}/{action}} catch-all turns
 * an unknown two-segment API path (e.g. a scanner's {@code /api/graphql}) into a
 * {@code controllers.api} {@link play.exceptions.ActionNotFoundException} —
 * logged at ERROR with a stack trace and an error response that fingerprints the
 * framework. Routed after all real {@code /api} routes and before the SPA +
 * generic catch-alls, so real endpoints and SPA routing are untouched.
 *
 * <p>No {@code @With(AuthCheck.class)}: an unknown path is a 404 regardless of
 * credentials, and not gating it avoids leaking (via a 401) that auth exists.
 */
public class ApiNotFoundController extends Controller {

    public static void handle() {
        response.status = 404;
        renderJSON("{\"error\":\"Not found\"}");
    }
}
