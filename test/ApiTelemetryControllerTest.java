import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.telemetry.OtelRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /api/telemetry} (JCLAW-34): the runtime's view of the otel.* keys, and the delivery check. */
public class ApiTelemetryControllerTest extends FunctionalTest {

    @BeforeEach
    public void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        // After the wipe, so the reset re-applies the (now absent) keys.
        TelemetryTestSync.acquire();
        OtelRuntime.init();
    }

    @AfterEach
    public void unlock() {
        TelemetryTestSync.release();
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    @Test
    public void statusRequiresAuth() {
        assertEquals(401, GET("/api/telemetry").status.intValue());
        assertEquals(401, POST("/api/telemetry/test", "application/json", "{}").status.intValue());
    }

    @Test
    public void statusReportsExportOffByDefault() {
        login();
        var resp = GET("/api/telemetry");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"enabled\":false"), body);
        assertTrue(body.contains("\"exporting\":false"), body);
        assertTrue(body.contains("\"endpoint\":\"http://localhost:4318\""), body);
    }

    @Test
    public void testSpanSaysWhyItWasNotDeliveredWhenExportIsOff() {
        login();
        var resp = POST("/api/telemetry/test", "application/json", "{}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"delivered\":false"), body);
        assertTrue(body.contains("export is off"), body);
    }
}
