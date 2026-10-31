package utils;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** How the app serves bytes an agent could have written, so that none of them run on its origin. */
public final class ServedFiles {

    /**
     * The app declares no global CSP (http.headers.contentSecurityPolicy is empty by design), so such
     * a response carries its own: {@code sandbox} alone drops the unique origin's scripts, forms and
     * plugins even if the type sniffs as something executable.
     */
    public static final String SANDBOX_CSP = "sandbox";

    private ServedFiles() {}

    /** {@code image/*} minus the vector types a browser executes: SVG runs script inline, and
     *  {@code image/svg+xml-compressed} is the same document gzipped. */
    public static boolean isRasterImage(@Nullable String contentType) {
        if (contentType == null) return false;
        var type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("image/") && !type.startsWith("image/svg");
    }
}
