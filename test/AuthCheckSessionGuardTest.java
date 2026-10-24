import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;

/**
 * The operator's session serves only the SPA's own requests: one from a sandboxed frame ({@code Origin: null},
 * as an MCP Apps view sends) or from another site is refused, whatever cookie the browser attached.
 */
class AuthCheckSessionGuardTest extends FunctionalTest {

    private static final String REFUSED =
            "A request from another site or a sandboxed frame cannot use the operator's session";

    @BeforeEach
    void login() {
        AuthFixture.seedAdminPassword("guard-pass");
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"guard-pass\"}"));
    }

    @AfterEach
    void logout() {
        clearCookies();
        AuthFixture.clearAdminPassword();
    }

    private static Http.Request with(String header, String value) {
        var request = newRequest();
        request.headers.put(header, new Http.Header(header, value));
        return request;
    }

    private static void assertRefused(Http.Response response) {
        assertStatus(403, response);
        var body = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals("forbidden", body.get("code").getAsString());
        assertEquals(REFUSED, body.get("message").getAsString());
    }

    @Test
    void aSessionRequestFromASandboxedFrameIsRefused() {
        assertRefused(GET(with("origin", "null"), "/api/config"));
        assertRefused(POST(with("origin", "null"), "/api/mcp-servers", "application/json",
                "{\"name\":\"planted\",\"enabled\":false,\"transport\":\"HTTP\",\"url\":\"http://127.0.0.1:1/mcp\"}"));
        var names = new java.util.ArrayList<String>();
        JsonParser.parseString(getContent(GET("/api/mcp-servers"))).getAsJsonArray()
                .forEach(row -> names.add(row.getAsJsonObject().get("name").getAsString()));
        assertFalse(names.contains("planted"), "the refused write wrote nothing: " + names);
    }

    @Test
    void theCatchAllRouteIsGuardedToo() {
        assertRefused(POST(with("origin", "null"), "/ApiMcpServersController/create", "application/json",
                "{\"name\":\"caught\",\"enabled\":false,\"transport\":\"HTTP\",\"url\":\"http://127.0.0.1:1/mcp\"}"));
        var names = new java.util.ArrayList<String>();
        JsonParser.parseString(getContent(GET("/api/mcp-servers"))).getAsJsonArray()
                .forEach(row -> names.add(row.getAsJsonObject().get("name").getAsString()));
        assertFalse(names.contains("caught"), "the refused write wrote nothing: " + names);
    }

    @Test
    void theSessionAuthActionsOutsideAuthCheckAreGuarded() {
        assertRefused(POST(with("origin", "null"), "/api/auth/reset-password", "application/json", "{}"));
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"guard-pass\"}"));
        assertRefused(POST(with("origin", "null"), "/api/auth/logout", "application/json", "{}"));
        assertIsOk(GET("/api/config"));
    }

    @Test
    void aLoginFromASandboxedFrameOrAnotherSiteIsRefusedBeforeItCounts() {
        clearCookies();
        var body = "{\"username\":\"admin\",\"password\":\"wrong\"}";
        assertRefused(POST(with("origin", "null"), "/api/auth/login", "application/json", body));
        assertRefused(POST(with("sec-fetch-site", "cross-site"), "/api/auth/login", "application/json", body));
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"guard-pass\"}"));
    }

    @Test
    void aSessionRequestFromAnotherSiteIsRefused() {
        assertRefused(GET(with("sec-fetch-site", "cross-site"), "/api/config"));
        assertRefused(GET(with("sec-fetch-site", "Cross-Site"), "/api/config"));
    }

    @Test
    void theSpasOwnRequestsKeepTheirSession() {
        assertIsOk(GET(with("sec-fetch-site", "same-origin"), "/api/config"));
        assertIsOk(GET(with("sec-fetch-site", "none"), "/api/config"));
        assertIsOk(GET(with("sec-fetch-site", "same-site"), "/api/config"));
        assertIsOk(GET(with("origin", "http://localhost:9000"), "/api/config"));
        assertIsOk(GET("/api/config"));
    }

    @Test
    void aBearerRequestIsNotJudgedByItsOrigin() {
        clearCookies();
        var request = with("origin", "null");
        request.headers.put("sec-fetch-site", new Http.Header("sec-fetch-site", "cross-site"));
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        assertIsOk(GET(request, "/api/config"));
    }

    @Test
    void withoutASessionTheRefusalIsStillAuthenticationRequired() {
        clearCookies();
        var response = GET(with("origin", "null"), "/api/config");
        assertStatus(401, response);
        assertEquals("authentication_required",
                JsonParser.parseString(getContent(response)).getAsJsonObject().get("code").getAsString());
    }
}
