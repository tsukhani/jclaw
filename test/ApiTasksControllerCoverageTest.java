import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.EventLogger;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * JCLAW-707 branch-coverage companion for {@code ApiTasksController}. Fills the
 * decision branches the split create/update/lifecycle suites don't reach:
 * <ul>
 *   <li>{@code create}/{@code update} malformed-body {@code null} guard → 400
 *       (the {@code JsonBodyReader.readJsonBody()} parse-failure path, distinct
 *       from a well-formed body that's missing a required field).</li>
 *   <li>{@code rejectInvalidTimezone} — a non-IANA {@code timezone} → 400, and
 *       a valid IANA {@code timezone} round-tripping into the persisted task's
 *       {@code timezone} + precomputed {@code effectiveTimezone}.</li>
 *   <li>{@code nextRunAtForDisplay}'s CRON arm — a live CRON task's list row
 *       carries a recomputed future {@code nextRunAt} (not the stored value),
 *       exercised end-to-end through {@code GET /api/tasks} rather than the pure
 *       INTERVAL unit test in {@code ApiTasksControllerNextRunTest}.</li>
 * </ul>
 * Mirrors the existing task suites' AuthFixture + login + POST-seed shape.
 */
class ApiTasksControllerCoverageTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
    }

    @AfterEach
    void teardown() {
        EventLogger.flush();
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(resp);
    }

    private Long seedAgent(String name) {
        var resp = POST("/api/agents", "application/json", """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(name));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private Long seedTask(Long agentId, String name, String schedule) {
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "%s", "schedule": "%s"}
                """.formatted(agentId, name, schedule));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    /** Play 1.x FunctionalTest ships no PATCH helper; mirror PUT with the verb
     *  swapped (same reflection to reuse the logged-in cookie jar as
     *  {@code ApiTasksControllerUpdateTest}). */
    @SuppressWarnings("unchecked")
    private static Response PATCH(Object url, String contentType, String body) {
        Request request = newRequest();
        String turl = url.toString();
        request.method = "PATCH";
        request.contentType = contentType;
        request.url = turl;
        request.path = turl;
        request.querystring = "";
        request.body = new ByteArrayInputStream(body.getBytes());
        try {
            var f = FunctionalTest.class.getDeclaredField("savedCookies");
            f.setAccessible(true);
            var sc = (java.util.Map<String, play.mvc.Http.Cookie>) f.get(null);
            if (sc != null) request.cookies = sc;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not read FunctionalTest.savedCookies", e);
        }
        return makeRequest(request);
    }

    // ── malformed-body null guard (body == null → 400) ───────────────────────

    @Test
    void createRejectsMalformedJsonBodyWith400() {
        // A well-formed-but-non-object body ([]) parses then fails getAsJsonObject,
        // so readJsonBody() returns null — the create()'s `body == null` branch,
        // separate from the missing-required-field 400s the create suite covers.
        var resp = POST("/api/tasks", "application/json", "[]");
        assertStatus(400, resp);
    }

    @Test
    void updateRejectsMalformedJsonBodyWith400() {
        var agent = seedAgent("cov-task-agent-badbody");
        var taskId = seedTask(agent, "cov-badbody", "now");
        var resp = PATCH("/api/tasks/" + taskId, "application/json", "[]");
        assertStatus(400, resp);
    }

    // ── timezone guard (rejectInvalidTimezone) ───────────────────────────────

    @Test
    void createRejectsInvalidTimezoneWith400() {
        var agent = seedAgent("cov-task-agent-badtz");
        // Valid agent/name/schedule but a non-IANA timezone: passes every earlier
        // guard (schedule parses under the fallback zone, no delivery, unique
        // recurring name) and halts at rejectInvalidTimezone.
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "cov-bad-tz", "schedule": "@daily", "timezone": "Mars/Phobos"}
                """.formatted(agent));
        assertStatus(400, resp);
        assertTrue(getContent(resp).contains("Invalid IANA timezone"),
                "400 body should name the invalid timezone: " + getContent(resp));
    }

    @Test
    void createAcceptsValidTimezoneAndReflectsEffectiveZone() {
        var agent = seedAgent("cov-task-agent-goodtz");
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "cov-good-tz", "schedule": "@daily", "timezone": "Asia/Tokyo"}
                """.formatted(agent));
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"timezone\":\"Asia/Tokyo\""),
                "per-task timezone must round-trip: " + body);
        // TaskView precomputes the effective zone (TimezoneResolver.resolve(t));
        // with a per-task override it must equal that override.
        assertTrue(body.contains("\"effectiveTimezone\":\"Asia/Tokyo\""),
                "effectiveTimezone must resolve to the per-task override: " + body);
    }

    // ── nextRunAtForDisplay CRON arm (via GET /api/tasks) ────────────────────

    @Test
    void listComputesCronNextRunFromExpression() {
        var agent = seedAgent("cov-task-agent-cron");
        var taskId = seedTask(agent, "cov-cron-next", "@daily");

        var resp = GET("/api/tasks");
        assertIsOk(resp);

        // Pull our specific row out of the array (the shared test DB may hold
        // rows from concurrently-running suites) and read its nextRunAt.
        var arr = JsonParser.parseString(getContent(resp)).getAsJsonArray();
        String nextRunAt = null;
        String type = null;
        for (var el : arr) {
            var o = el.getAsJsonObject();
            if (o.get("id").getAsLong() == taskId) {
                type = o.get("type").getAsString();
                nextRunAt = o.get("nextRunAt").isJsonNull() ? null : o.get("nextRunAt").getAsString();
                break;
            }
        }
        assertEquals("CRON", type, "seeded task must be a CRON task");
        assertNotNull(nextRunAt, "CRON task must surface a computed nextRunAt");
        // The CRON arm recomputes the next fire from the expression; @daily is the
        // next local midnight, always strictly in the future.
        assertTrue(Instant.parse(nextRunAt).isAfter(Instant.now()),
                "CRON nextRunAt must be a future fire, got: " + nextRunAt);
    }
}
