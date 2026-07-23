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
        response.setContentTypeIfNotSet(HTML_CONTENT_TYPE_PREFIX + Play.defaultWebEncoding);
        renderHtml("<html><body><h1>JClaw</h1><p>SPA not built. Run: cd frontend &amp;&amp; pnpm generate</p></body></html>");
    }

    /**
     * Serve a Nuxt build asset from {@code public/spa/_nuxt} with a cache policy
     * matched to the file's mutability (see {@link #nuxtCacheControl}).
     *
     * <p>Replaces a bare {@code staticDir} route. {@code staticDir} is resolved by
     * the router before any controller runs, so it can only apply Play's single
     * {@code http.cacheControl} (1 h) to every file — forcing an hourly
     * revalidation of chunks whose content-hashed names already make them
     * permanently immutable. Routing through an action is the only way to set the
     * per-file header {@code staticDir} can't.
     */
    @SuppressWarnings("java:S2259")
    public static void nuxtAsset(String path) {
        File nuxtRoot = Play.getFile("public/spa/_nuxt");
        try {
            if (path != null && !path.contains("..")) {
                File asset = new File(nuxtRoot, path);
                if (asset.exists() && asset.isFile()
                        && asset.getCanonicalPath().startsWith(nuxtRoot.getCanonicalPath() + File.separator)) {
                    response.setHeader("Cache-Control", nuxtCacheControl(path));
                    renderBinary(asset);
                }
            }
        } catch (IOException _) {}
        notFound();
    }

    /**
     * Cache-Control for a {@code _nuxt/}-relative asset path. Vite content-hashes
     * every chunk and font filename, so those are immutable and cached for a year
     * with no revalidation. The two exceptions carry no hash in their own name:
     * {@code builds/latest.json} advertises the current build id and MUST
     * revalidate (else a fresh deploy is never detected), while
     * {@code builds/meta/<id>.json} embeds the id in its path and is immutable
     * like the chunks. Mirrors Nitro's own default asset route rules.
     */
    public static String nuxtCacheControl(String relPath) {
        boolean revalidate = relPath.startsWith("builds/") && !relPath.startsWith("builds/meta/");
        return revalidate ? "no-cache" : "public, max-age=31536000, immutable";
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
        response.setContentTypeIfNotSet(HTML_CONTENT_TYPE_PREFIX + Play.defaultWebEncoding);
        // Always revalidate the SPA shell so a new build's chunk hashes are picked up
        // immediately (see index() above). Hashed _nuxt/ assets keep their long cache.
        response.setHeader("Cache-Control", "no-cache");
        renderBinary(index);
    }

}