import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;

class ApiControllerTest extends FunctionalTest {

    @Test
    void statusReturnsOkAndApplicationMetadata() {
        var resp = GET("/api/status");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"status\":\"ok\""), "must report ok: " + body);
        assertTrue(body.contains("\"application\""), "must echo app name: " + body);
        assertTrue(body.contains("\"mode\""), "must echo play mode: " + body);
        assertTrue(body.contains("\"applicationVersion\""), "must echo version: " + body);
    }

    @Test
    void statusNamesTheFreeBuildAndTheProductTheSpaRenders() {
        var resp = GET("/api/status");
        assertIsOk(resp);
        var body = com.google.gson.JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals("free", body.get("build").getAsString());
        assertEquals("JClaw", body.get("productName").getAsString());
    }

    /**
     * The build id is the first triage question on any "I'm seeing stale JS"
     * report (JCLAW-886): it separates a caching problem from a deploy that
     * never landed. The field is always present — GsonHolder.GSON serializes
     * nulls — so an unbuilt SPA reports null rather than omitting the key.
     */
    @Test
    void statusReportsSpaBuildId() throws java.io.IOException {
        var resp = GET("/api/status");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"spaBuildId\""),
                "must expose the deployed SPA build id: " + body);

        var manifest = play.Play.getFile("public/spa/_nuxt/builds/latest.json");
        if (!manifest.isFile()) {
            assertTrue(body.contains("\"spaBuildId\":null"),
                    "unbuilt SPA must report a null build id, not a stale one: " + body);
            return;
        }
        var raw = java.nio.file.Files.readString(manifest.toPath());
        var id = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
        assertTrue(id.find(), "Nuxt build manifest must carry an id: " + raw);
        assertTrue(body.contains("\"spaBuildId\":\"" + id.group(1) + "\""),
                "reported build id must match the deployed manifest (" + id.group(1) + "): " + body);
    }
}
