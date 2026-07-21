import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * JCLAW-818: a bare local date-time PATCH must resolve the fire instant in
 * the task's own timezone, not the server default. Regression guard for
 * {@code applyScheduleUpdate} switching from the 1-arg (server-zone)
 * {@link services.ScheduleShorthandParser#parse(String)} to the zone-aware
 * 2-arg overload {@code create()} already uses.
 */
class ApiTasksControllerScheduleTimezoneTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        var resp = POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """);
        assertIsOk(resp);
    }

    private Long seedAgent() {
        var resp = POST("/api/agents", "application/json", """
                {"name": "tz-sched-agent", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """);
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    @Test
    void bareDatetimeResolvesInTaskZoneNotServerZone() {
        var agent = seedAgent();

        // Pick a task zone whose offset at the target local time differs from
        // the server default, so a regression that resolved against the server
        // zone would compute a different instant and fail the assertion below.
        // Etc/GMT-14 (UTC+14) and Etc/GMT+12 (UTC-12) are 26h apart, so no
        // single server zone can match both — at least one always differs.
        var local = "2035-03-14T09:30";
        var ldt = LocalDateTime.parse(local);
        var serverInstant = ldt.atZone(ZoneId.systemDefault()).toInstant();
        var far = ZoneId.of("Etc/GMT-14");
        var taskZone = ldt.atZone(far).toInstant().equals(serverInstant)
                ? ZoneId.of("Etc/GMT+12") : far;
        var expected = ldt.atZone(taskZone).toInstant().toString();

        // Seed a task carrying the task zone (create persists timezone for all
        // types). Then PATCH only the bare local date-time schedule.
        var seed = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "tz-task", "schedule": "now", "timezone": "%s"}
                """.formatted(agent, taskZone.getId()));
        assertIsOk(seed);
        var taskId = extractId(getContent(seed));

        var resp = PATCH("/api/tasks/" + taskId, "application/json", """
                {"schedule": "%s"}
                """.formatted(local));
        assertIsOk(resp);
        var body = getContent(resp);
        assertContentMatch("\"type\":\"SCHEDULED\"", resp);
        // nextRunAt mirrors the resolved scheduledAt instant (UTC ISO). It must
        // equal the task-zone interpretation, not the server-zone one.
        assertTrue(body.contains("\"nextRunAt\":\"" + expected + "\""),
                "expected nextRunAt " + expected + " (task zone " + taskZone.getId()
                        + "); got: " + body);
    }

    /**
     * Play 1.x FunctionalTest ships no PATCH helper — mirror the PUT
     * implementation locally (same shape, different verb), reflecting the
     * private static {@code savedCookies} to carry the login session.
     */
    @SuppressWarnings("unchecked")
    private static Response PATCH(Object url, String contentType, String body) {
        Request request = newRequest();
        String turl = url.toString();
        int q = turl.indexOf('?');
        String path = (q >= 0) ? turl.substring(0, q) : turl;
        String qs = (q >= 0) ? turl.substring(q + 1) : "";
        request.method = "PATCH";
        request.contentType = contentType;
        request.url = turl;
        request.path = path;
        request.querystring = qs;
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
}
