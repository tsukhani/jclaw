import com.google.gson.JsonParser;
import controllers.ApiAppsController.AppEntry;
import controllers.AppPwa;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

class AppPwaTest extends UnitTest {

    private static AppEntry app(String name, String icon) {
        return new AppEntry("demo", "/apps/demo/", name, "1.0.0", null, icon, null, "Does things", null);
    }

    @Test
    void manifestIsScopedToTheAppWithAnSvgIconChromeAccepts() {
        var m = JsonParser.parseString(AppPwa.manifestJson(app("Demo", "/apps/demo/icon.svg"))).getAsJsonObject();
        assertEquals("/apps/demo/", m.get("id").getAsString());
        assertEquals("/apps/demo/", m.get("start_url").getAsString());
        assertEquals("/apps/demo/", m.get("scope").getAsString());
        assertEquals("standalone", m.get("display").getAsString());
        assertEquals("Demo", m.get("name").getAsString());
        var icon = m.getAsJsonArray("icons").get(0).getAsJsonObject();
        assertEquals("/apps/demo/icon.svg", icon.get("src").getAsString());
        assertEquals("any", icon.get("sizes").getAsString());
        assertEquals("image/svg+xml", icon.get("type").getAsString());
    }

    @Test
    void rasterIconCarriesNoGuessedSize() {
        var m = JsonParser.parseString(AppPwa.manifestJson(app("Demo", "/apps/demo/icon.png"))).getAsJsonObject();
        var icon = m.getAsJsonArray("icons").get(0).getAsJsonObject();
        assertFalse(icon.has("sizes"));
    }

    @Test
    void manifestLinkLandsBeforeHeadClose() {
        var out = AppPwa.inject("<html><HEAD><title>x</title></HEAD><body></body></html>", app("Demo", null), false);
        assertTrue(out.contains("<link rel=\"manifest\" href=\"/apps/demo/manifest.webmanifest\"></HEAD>"), out);
        assertFalse(out.contains("<script>"), "no banner without the install flag");
    }

    @Test
    void headlessDocumentGetsTagsPrepended() {
        var out = AppPwa.inject("<p>hi</p>", app("Demo", null), false);
        assertTrue(out.startsWith("<link rel=\"manifest\""), out);
    }

    @Test
    void anAppShippingItsOwnManifestIsLeftAlone() {
        var html = "<head><link rel=\"manifest\" href=\"mine.json\"></head>";
        assertEquals(html, AppPwa.inject(html, app("Demo", null), false));
    }

    @Test
    void installBannerCannotBeBrokenOutOfByTheAppName() {
        var out = AppPwa.inject("<head></head>", app("</script><img src=x onerror=alert(1)>", null), true);
        assertEquals(1, out.split("</script>", -1).length - 1, "only the injected block may close a script: " + out);
        assertTrue(out.contains("\\u003c/script\\u003e"), out);
    }
}
