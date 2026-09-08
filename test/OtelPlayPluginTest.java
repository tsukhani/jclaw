import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.semconv.HttpAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.telemetry.OtelRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin registered in {@code conf/play.plugins} produces one SERVER span per HTTP
 * invocation, named from the route and closed with the response status (JCLAW-34).
 * Goes through the real framework — {@code GET} here runs the invocation hooks — so
 * this proves the registration and the hook order, not just the class.
 */
public class OtelPlayPluginTest extends FunctionalTest {

    @BeforeEach
    public void lock() {
        TelemetryTestSync.acquire();
        OtelRuntime.init();
    }

    @AfterEach
    public void unlock() {
        TelemetryTestSync.release();
    }

    @Test
    public void aRequestYieldsOneServerSpanNamedFromTheRoute() {
        var spans = OtelRuntime.captureForTest(() -> assertIsOk(GET("/api/status")));
        var server = spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).toList();
        assertEquals(1, server.size(), () -> "server spans: " + spans);
        var span = server.getFirst();
        assertEquals("GET /api/status", span.getName());
        assertEquals("/api/status", span.getAttributes().get(HttpAttributes.HTTP_ROUTE));
        assertEquals("GET", span.getAttributes().get(HttpAttributes.HTTP_REQUEST_METHOD));
        assertEquals(200L, span.getAttributes().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE));
        assertTrue(span.hasEnded());
    }

    @Test
    public void withExportOffARequestRecordsNothing() {
        // Outside the capture seam the sampler is off: the same request must leave no span behind.
        assertIsOk(GET("/api/status"));
        var spans = OtelRuntime.captureForTest(() -> { });
        assertTrue(spans.isEmpty(), () -> "leaked spans: " + spans);
    }
}
