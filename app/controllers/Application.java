package controllers;

import play.*;
import play.mvc.*;

import java.io.*;

public class Application extends Controller {

    public static void index() {
        // Serve the SPA if it's been built
        File spaIndex = Play.getFile("public/spa/index.html");
        if (spaIndex.exists()) {
            response.setContentTypeIfNotSet("text/html");
            renderBinary(spaIndex);
        }
        // SPA not built — return a simple HTML page instead of the legacy Groovy template
        response.setContentTypeIfNotSet("text/html; charset=" + play.Play.defaultWebEncoding);
        renderHtml("<html><body><h1>JClaw</h1><p>SPA not built. Run: cd frontend &amp;&amp; pnpm generate</p></body></html>");
    }

    /**
     * SPA catch-all: serves static files from the Nuxt build if they exist,
     * otherwise falls back to index.html for client-side routing.
     * Production build lives in public/spa/ (output of: nuxi generate).
     */
    public static void spa(String path) {
        File spaRoot = Play.getFile("public/spa");

        // Check if path matches a static file in the SPA build (e.g., avatar.png)
        try {
            if (path != null && !path.contains("..")) {
                File staticFile = new File(spaRoot, path);
                if (staticFile.exists() && staticFile.isFile()
                        && staticFile.getCanonicalPath().startsWith(spaRoot.getCanonicalPath())) {
                    renderBinary(staticFile);
                }
            }
        } catch (IOException _) {}


        // Fall back to index.html for client-side routing
        File index = new File(spaRoot, "index.html");
        if (!index.exists()) {
            notFound("SPA not built. Run: cd frontend && pnpm generate, then copy .output/public/* to public/spa/");
        }
        response.setContentTypeIfNotSet("text/html");
        renderBinary(index);
    }

}