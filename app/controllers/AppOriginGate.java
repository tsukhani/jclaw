package controllers;

import play.mvc.Http;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * JCLAW-764 / AD-1: browser-attested provenance gate for hosted-app JavaScript.
 *
 * <p>A hosted app at {@code /apps/<slug>/} is served same-origin with {@code /api},
 * so its {@code fetch} rides the operator's session cookie — ambient full-privilege
 * access to every endpoint. This gate demotes an <em>app-originated</em> request to
 * that app's own invoke route only.
 *
 * <p>A request is app-originated when it carries {@code Sec-Fetch-Site: same-origin}
 * — a browser-set forbidden header an app's {@code fetch} cannot forge or remove —
 * AND a {@code Referer} whose path is under {@code /apps/<slug>/}. Such a request is
 * permitted only against {@code POST /api/apps/<slug>/invoke} for the matching slug;
 * anything else the caller rejects with 403. Authentication is unchanged — only
 * app-origin authority is narrowed. The loopback {@code jclaw_api} bearer and normal
 * SPA calls (no {@code /apps/} Referer) are never app-originated.
 *
 * <p>The Referer signal is strippable by a <em>deliberately hostile</em> in-app
 * script ({@code fetch(..., {referrerPolicy:'no-referrer'})}), so this closes
 * accidental / buggy over-reach, matching the trusted-app model. Hostile-app closure
 * is JCLAW-786 (a login-issued anti-forgery token). See the epic spine's Deferred.
 */
public final class AppOriginGate {

    private AppOriginGate() {}

    /** A hosted-app Referer path: {@code /apps/<slug>/...} (slug per ApiAppsController.SLUG). */
    private static final Pattern APP_REFERER_PATH = Pattern.compile("^/apps/([a-z0-9][a-z0-9-]*)(?:/|$)");

    /**
     * The owning-app slug when {@code (secFetchSite, referer)} identify an
     * app-originated request, else {@code null}. Pure and side-effect-free — the
     * predicate {@link #isBlocked()} and {@link #currentSlug()} build on it, and it
     * is directly unit-tested. A malformed {@code Referer} is treated as
     * not-app-originated (a well-formed one is what accidental over-reach carries).
     */
    public static String appOriginSlug(String secFetchSite, String referer) {
        if (secFetchSite == null || !secFetchSite.trim().equalsIgnoreCase("same-origin")) {
            return null;
        }
        if (referer == null || referer.isBlank()) {
            return null;
        }
        String path;
        try {
            path = URI.create(referer.trim()).getPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (path == null) {
            return null;
        }
        var m = APP_REFERER_PATH.matcher(path);
        return m.find() ? m.group(1) : null;
    }

    /** The current request's owning-app slug, or {@code null} when it is not app-originated. */
    public static String currentSlug() {
        var req = Http.Request.current();
        if (req == null) {
            return null;
        }
        return appOriginSlug(header(req, "sec-fetch-site"), header(req, "referer"));
    }

    /**
     * True when the current request is app-originated AND is not a {@code POST} to
     * that app's own invoke route — i.e. the caller must reject it with 403 (AD-1).
     * False for every non-app-originated request (SPA, loopback bearer, server-side),
     * which pass through with full authority.
     */
    public static boolean isBlocked() {
        var req = Http.Request.current();
        if (req == null) {
            return false;
        }
        var slug = appOriginSlug(header(req, "sec-fetch-site"), header(req, "referer"));
        if (slug == null) {
            return false;
        }
        return !("POST".equalsIgnoreCase(req.method) && ("/api/apps/" + slug + "/invoke").equals(req.path));
    }

    private static String header(Http.Request req, String name) {
        var h = req.headers.get(name);
        return h == null ? null : h.value();
    }
}
