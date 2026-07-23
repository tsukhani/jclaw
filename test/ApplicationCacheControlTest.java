import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import controllers.Application;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit coverage for {@link controllers.Application#nuxtCacheControl}: Vite
 * content-hashes every chunk/font filename, so those are cached immutably for a
 * year; the stable-named build manifest ({@code builds/latest.json}) must
 * revalidate so a fresh deploy is detected, while {@code builds/meta/<id>.json}
 * carries the build id in its path and stays immutable like the chunks.
 */
class ApplicationCacheControlTest extends UnitTest {

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    @Test
    void hashedChunksAreImmutable() {
        assertEquals(IMMUTABLE, Application.nuxtCacheControl("MqmusUur2.js"));
        assertEquals(IMMUTABLE, Application.nuxtCacheControl("entry.CBcvBZtf.css"));
        assertEquals(IMMUTABLE, Application.nuxtCacheControl("inter-greek-wght-normal.CkhJZR-_.woff2"));
    }

    @Test
    void buildManifestRevalidates() {
        assertEquals("no-cache", Application.nuxtCacheControl("builds/latest.json"));
    }

    @Test
    void buildMetaIsImmutable() {
        // The build id lives in the path, so meta/<id>.json is content-addressed.
        assertEquals(IMMUTABLE,
                Application.nuxtCacheControl("builds/meta/bbfb416c-f3f4-488c-8e6c-0ddfff970281.json"));
    }
}
