package controllers;

import play.*;
import play.mvc.*;

import java.io.*;

public class Application extends Controller {

    public static void index() {
        // In production, serve the SPA if it's been built
        File spaIndex = Play.getFile("public/spa/index.html");
        if (spaIndex.exists()) {
            response.setContentTypeIfNotSet("text/html");
            renderBinary(spaIndex);
        }
        render();
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