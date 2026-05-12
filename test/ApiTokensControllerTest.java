import com.google.gson.JsonParser;
import models.ApiToken;
import org.junit.jupiter.api.*;
import play.test.*;

/**
 * Functional coverage for the bearer-auth path through {@code AuthCheck}
 * and the {@code ApiTokensController} CRUD surface (JCLAW-282).
 *
 * <p>The bearer path matters because it's how the JClaw MCP server (and
 * any other out-of-process API client) authenticates to JClaw — every
 * downstream agent capability the MCP server eventually exposes routes
 * through this filter, so the auth boundary needs explicit coverage.
 */
public class ApiTokensControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-123";

    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        ApiTokenFixture.clearAll();
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
        ApiTokenFixture.clearAll();
    }

    // ==================== bearer auth path ====================

    @Test
    public void validBearerTokenGrantsAccess() {
        var plaintext = ApiTokenFixture.seedReadOnly();
        var response = getWithBearer("/api/config", plaintext);
        assertIsOk(response);
    }

    @Test
    public void unknownBearerTokenRejectedWith401() {
        var response = getWithBearer("/api/config", "jcl_not-real-token-abc123");
        assertEquals(401, response.status.intValue());
        assertTrue(getContent(response).contains("invalid_token"),
                "expected invalid_token code so clients can distinguish "
                        + "bearer rejection from generic auth failure; got: " + getContent(response));
    }

    @Test
    public void revokedBearerTokenRejectedWith401() {
        var plaintext = ApiTokenFixture.seedReadOnly();
        ApiTokenFixture.revokeByPlaintext(plaintext);
        var response = getWithBearer("/api/config", plaintext);
        assertEquals(401, response.status.intValue());
        // Revoked vs unknown intentionally collapses to the same error
        // — don't reveal that a token once existed.
        assertTrue(getContent(response).contains("invalid_token"),
                "got: " + getContent(response));
    }

    @Test
    public void readOnlyTokenBlocksPost() {
        var plaintext = ApiTokenFixture.seedReadOnly();
        var response = postWithBearer("/api/config", "{\"key\":\"x\",\"value\":\"y\"}", plaintext);
        assertEquals(403, response.status.intValue());
        assertTrue(getContent(response).contains("token_read_only"),
                "expected token_read_only code so the caller knows scope is the cause; got: "
                        + getContent(response));
    }

    @Test
    public void fullScopeTokenAllowsPost() {
        var plaintext = ApiTokenFixture.seedFull();
        // A full-scope token must reach the controller so the request
        // executes the mutation. /api/config POST is the easiest sentinel
        // here — it's a simple JSON body and any 2xx is sufficient proof
        // that the auth filter let the request through.
        var response = postWithBearer(
                "/api/config",
                "{\"key\":\"jclaw282.test\",\"value\":\"ok\"}",
                plaintext);
        assertIsOk(response);
    }

    @Test
    public void missingBearerFallsBackToSessionAuth() {
        // No bearer header — request should hit the session-cookie path
        // exactly as it did before JCLAW-282 (covered already by AuthTest;
        // this case verifies the bearer branch is opt-in, not mandatory).
        var response = GET("/api/config");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void malformedBearerHeaderFallsBackToSession() {
        // Header present but doesn't look like "Bearer <token>" — treat
        // as no bearer credential at all and try the session path. The
        // result is still 401 (no session either), but via the
        // session-required code, not the invalid_token code.
        var response = getWithRawAuth("/api/config", "Basic abc:def");
        assertEquals(401, response.status.intValue());
        assertFalse(getContent(response).contains("invalid_token"),
                "Basic auth should be treated as 'no bearer supplied', not invalid_token");
    }

    @Test
    public void bearerAuthSetsLastUsedAt() {
        var plaintext = ApiTokenFixture.seedReadOnly();
        ApiToken before = ApiToken.find(
                "secretHash = ?1", utils.TokenHasher.hash(plaintext)).first();
        assertNull(before.lastUsedAt, "freshly minted token should not have lastUsedAt set");

        var response = getWithBearer("/api/config", plaintext);
        assertIsOk(response);

        // lastUsedAt is updated synchronously in the auth filter's tx,
        // so by the time the response returns the audit write is
        // already committed. Read it back on a fresh em via a forced
        // re-query (the FunctionalTest carrier-thread em may have a
        // stale L1-cache copy from before the request).
        play.db.jpa.JPA.em().clear();
        ApiToken after = ApiToken.find(
                "secretHash = ?1", utils.TokenHasher.hash(plaintext)).first();
        assertNotNull(after);
        assertNotNull(after.lastUsedAt,
                "lastUsedAt should be populated after a successful bearer call");
    }

    // ==================== ApiTokensController CRUD ====================

    @Test
    public void mintReturnsPlaintextOnce() {
        login();
        var body = "{\"name\":\"claude-desktop\",\"scope\":\"READ_ONLY\"}";
        var response = POST("/api/api-tokens", "application/json", body);
        assertIsOk(response);

        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals("claude-desktop", json.get("name").getAsString());
        assertEquals("READ_ONLY", json.get("scope").getAsString());
        assertFalse(json.get("plaintext").isJsonNull(),
                "mint response must carry the plaintext (the one and only time it surfaces)");
        var plaintext = json.get("plaintext").getAsString();
        assertTrue(plaintext.startsWith("jcl_"),
                "plaintext should be prefixed; got: " + plaintext);
        assertEquals(plaintext.substring(0, 12), json.get("displayPrefix").getAsString());
    }

    @Test
    public void mintRejectsBlankName() {
        login();
        var response = POST("/api/api-tokens", "application/json",
                "{\"name\":\"  \",\"scope\":\"READ_ONLY\"}");
        assertEquals(400, response.status.intValue());
    }

    @Test
    public void mintDefaultsToReadOnly() {
        login();
        var response = POST("/api/api-tokens", "application/json",
                "{\"name\":\"defaulted\"}");
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"scope\":\"READ_ONLY\""),
                "missing scope should default to READ_ONLY (least privilege); got: "
                        + getContent(response));
    }

    @Test
    public void listExcludesPlaintext() {
        login();
        POST("/api/api-tokens", "application/json", "{\"name\":\"a\"}");
        POST("/api/api-tokens", "application/json", "{\"name\":\"b\"}");

        var response = GET("/api/api-tokens");
        assertIsOk(response);
        var body = getContent(response);
        // Each row's plaintext must be JSON null — the secret is
        // unrecoverable after mint.
        assertTrue(body.contains("\"plaintext\":null"),
                "listing must not expose plaintext bearer; got: " + body);
    }

    @Test
    public void revokeMakesTokenInvalid() {
        login();
        // Mint via API so the test covers the same flow the UI uses.
        var mintBody = getContent(
                POST("/api/api-tokens", "application/json", "{\"name\":\"to-revoke\"}"));
        var mintJson = JsonParser.parseString(mintBody).getAsJsonObject();
        var id = mintJson.get("id").getAsLong();
        var plaintext = mintJson.get("plaintext").getAsString();

        // Sanity: works before revoke.
        var ok = getWithBearer("/api/config", plaintext);
        assertIsOk(ok);

        // Revoke through the API.
        var revoke = DELETE("/api/api-tokens/" + id);
        assertIsOk(revoke);

        // Subsequent bearer calls 401.
        var after = getWithBearer("/api/config", plaintext);
        assertEquals(401, after.status.intValue());
    }

    @Test
    public void revokeIsIdempotent() {
        login();
        var mintBody = getContent(
                POST("/api/api-tokens", "application/json", "{\"name\":\"idem\"}"));
        var id = JsonParser.parseString(mintBody).getAsJsonObject().get("id").getAsLong();

        // First revoke succeeds.
        assertIsOk(DELETE("/api/api-tokens/" + id));
        // Second revoke is a no-op rather than 409 — the operator's
        // intent ("this token must stop working") is already satisfied.
        var second = DELETE("/api/api-tokens/" + id);
        assertIsOk(second);
        assertTrue(getContent(second).contains("\"revoked\":true"));
    }

    @Test
    public void tokenCrudRejectsBearerAuth() {
        // Privilege-escalation hardening: a stolen full-scope token
        // must not be usable to mint another token before revocation.
        var plaintext = ApiTokenFixture.seedFull();
        var response = postWithBearer(
                "/api/api-tokens", "{\"name\":\"escalate\"}", plaintext);
        assertEquals(403, response.status.intValue());
        assertTrue(getContent(response).contains("session_required"),
                "expected session_required code; got: " + getContent(response));
    }

    // ==================== helpers ====================

    private void login() {
        var body = "{\"username\":\"admin\",\"password\":\"%s\"}".formatted(TEST_PASSWORD);
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    private static play.mvc.Http.Response getWithBearer(String url, String plaintext) {
        return getWithRawAuth(url, "Bearer " + plaintext);
    }

    private static play.mvc.Http.Response getWithRawAuth(String url, String authValue) {
        var req = newRequest();
        req.method = "GET";
        req.url = url;
        req.path = url;
        setHeader(req, "authorization", authValue);
        return makeRequest(req);
    }

    private static play.mvc.Http.Response postWithBearer(String url, String body, String plaintext) {
        var req = newRequest();
        req.method = "POST";
        req.contentType = "application/json";
        req.url = url;
        req.path = url;
        req.body = new java.io.ByteArrayInputStream(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        setHeader(req, "authorization", "Bearer " + plaintext);
        return makeRequest(req);
    }

    private static void setHeader(play.mvc.Http.Request req, String name, String value) {
        var h = new play.mvc.Http.Header();
        h.name = name;
        h.values = java.util.List.of(value);
        req.headers.put(name, h);
    }
}
