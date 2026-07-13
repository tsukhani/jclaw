import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

import java.util.regex.Pattern;

/**
 * JCLAW-707 branch-coverage companion for {@code ApiAgentsController}. The
 * primary suites ({@code ControllerApiTest}, {@code ApiAgentsControllerTest})
 * cover the slug matrix, basic CRUD, reserved-name conflicts, main-agent
 * invariants, workspace files, and the memory-autocapture round-trip. This
 * suite fills the remaining branches:
 * <ul>
 *   <li>{@code create} — the duplicate-name 409 (Agent.findByName != null),
 *       the malformed-body null guard, and a present optional {@code description}
 *       (readOptionalString's non-null arm on the create path).</li>
 *   <li>{@code update} — the malformed-body null guard, {@code applyCompressionSettings}
 *       (all five per-agent compression fields), and the {@code acpAllowed} grant.</li>
 * </ul>
 */
class ApiAgentsControllerCoverageTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(resp);
    }

    private String createAgent(String name) {
        var resp = POST("/api/agents", "application/json", """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(name));
        assertIsOk(resp);
        return extractId(getContent(resp));
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void createRejectsDuplicateNameWith409() {
        // First create succeeds; the second collides on the unique name and the
        // controller pre-checks Agent.findByName to return an actionable 409
        // rather than letting the DB unique-constraint surface as a 500.
        createAgent("dup-name-707");
        var resp = POST("/api/agents", "application/json", """
                {"name": "dup-name-707", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertEquals(409, resp.status.intValue());
        assertTrue(getContent(resp).contains("already exists"),
                "409 body should name the collision: " + getContent(resp));
    }

    @Test
    void createRejectsMalformedJsonBodyWith400() {
        // A JSON array parses but isn't an object → readJsonBody() returns null →
        // create()'s `body == null` badRequest branch.
        var resp = POST("/api/agents", "application/json", "[]");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void createWithDescriptionRoundTrips() {
        // Exercises readOptionalString's present (non-null) arm on the create
        // path — the create suite otherwise always omits description.
        var resp = POST("/api/agents", "application/json", """
                {"name": "desc-on-create-707", "modelProvider": "openrouter",
                 "modelId": "gpt-4.1", "description": "seeded with a description"}
                """);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"description\":\"seeded with a description\""),
                "description must round-trip on create: " + getContent(resp));
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    void updateRejectsMalformedJsonBodyWith400() {
        var id = createAgent("cov-agent-badbody-707");
        var resp = PUT("/api/agents/" + id, "application/json", "[]");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void updateCompressionSettingsRoundTrip() {
        var id = createAgent("cov-agent-compress-707");
        // Master on + explicit per-type toggles + a raw target ratio. The view
        // reports the *effective* per-type values, which (master on) equal the
        // raw toggles here; the target ratio passes through verbatim.
        var resp = PUT("/api/agents/" + id, "application/json", """
                {"compressionEnabled": true, "compressionJson": true,
                 "compressionCode": false, "compressionText": true,
                 "compressionTargetRatio": 0.42}
                """);
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"compressionEnabled\":true"), body);
        assertTrue(body.contains("\"compressionJson\":true"), body);
        assertTrue(body.contains("\"compressionCode\":false"),
                "code toggle off must survive even with the master on: " + body);
        assertTrue(body.contains("\"compressionText\":true"), body);
        assertTrue(body.contains("\"compressionTargetRatio\":0.42"),
                "raw target ratio must round-trip: " + body);
    }

    @Test
    void updateAcpAllowedGrantRoundTrips() {
        var id = createAgent("cov-agent-acp-707");
        // acpAllowed defaults false; the update path flips it via the JCLAW-500
        // per-agent grant branch.
        var resp = PUT("/api/agents/" + id, "application/json", """
                {"acpAllowed": true}
                """);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"acpAllowed\":true"),
                "acpAllowed grant must round-trip: " + getContent(resp));
    }
}
