package controllers;

import play.Play;
import play.mvc.Controller;

import java.io.File;
import java.io.IOException;

public class Application extends Controller {

    private static final String HTML_CONTENT_TYPE_PREFIX = "text/html; charset=";

    public static void index() {
        // Serve the SPA if it's been built
        File spaIndex = Play.getFile("public/spa/index.html");
        if (spaIndex.exists()) {
            // The SPA shell references content-hashed _nuxt/ chunks, so it MUST always
            // revalidate — otherwise the browser keeps a cached index.html pointing at
            // stale chunk hashes and a new frontend build never reaches users (Play's
            // PlayHandler.addEtag would otherwise apply http.cacheControl=3600 here).
            // The hashed chunks themselves stay long-cached via their own static route.
            response.setHeader("Cache-Control", "no-cache");
            renderBinary(spaIndex);
        }
        // SPA not built — return a simple HTML page instead of the legacy Groovy template
        response.setContentTypeIfNotSet(HTML_CONTENT_TYPE_PREFIX + play.Play.defaultWebEncoding);
        renderHtml("<html><body><h1>JClaw</h1><p>SPA not built. Run: cd frontend &amp;&amp; pnpm generate</p></body></html>");
    }

    /**
     * SPA catch-all: serves static files from the Nuxt build if they exist,
     * otherwise falls back to index.html for client-side routing.
     * Production build lives in public/spa/ (output of: nuxi generate).
     */
    @SuppressWarnings("java:S2259")
    public static void spa(String path) {
        File spaRoot = Play.getFile("public/spa");

        // Check if path matches a static file in the SPA build (e.g., avatar.png)
        try {
            if (path != null && !path.contains("..")) {
                File staticFile = new File(spaRoot, path);
                if (staticFile.exists() && staticFile.isFile()
                        && staticFile.getCanonicalPath().startsWith(spaRoot.getCanonicalPath() + File.separator)) {
                    renderBinary(staticFile);
                }
            }
        } catch (IOException _) {}


        // Fall back to index.html for client-side routing
        File index = new File(spaRoot, "index.html");
        if (!index.exists()) {
            notFound("SPA not built. Run: cd frontend && pnpm generate, then copy .output/public/* to public/spa/");
        }
        response.setContentTypeIfNotSet(HTML_CONTENT_TYPE_PREFIX + play.Play.defaultWebEncoding);
        // Always revalidate the SPA shell so a new build's chunk hashes are picked up
        // immediately (see index() above). Hashed _nuxt/ assets keep their long cache.
        response.setHeader("Cache-Control", "no-cache");
        renderBinary(index);
    }

}