import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.semconv.HttpAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.telemetry.OtelPlayPlugin;
import services.telemetry.OtelRuntime;


/**
 * The plugin registered in {@code conf/play.plugins} produces one SERVER span per HTTP
 * invocation, named from the route and closed with the response status (JCLAW-34).
 * Goes through the real framework — {@code GET} here runs the invocation hooks — so
 * this proves the registration and the hook order, not just the class.
 */
class OtelPlayPluginTest extends FunctionalTest {

    @BeforeEach
    void lock() {
        TelemetryTestSync.acquire();
        OtelRuntime.init();
    }

    @AfterEach
    void unlock() {
        TelemetryTestSync.release();
    }

    @Test
    void aRequestYieldsOneServerSpanNamedFromTheRoute() {
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
    void withTheAgentAttachedTheRequestNamesTheAgentsSpanInsteadOfOpeningItsOwn() {
        // The hook is driven directly: a FunctionalTest request runs on another thread, and the
        // agent's context reaches the invocation thread only through the agent's own instrumentation.
        var request = newRequest();
        request.method = "GET";
        request.path = "/api/status";
        request.action = "ApiController.status";
        OtelRuntime.agentAttachedForTest(true);
        Http.Request.current.set(request);
        try {
            var spans = OtelRuntime.captureForTest(() -> {
                var agentSpan = OtelRuntime.tracer().spanBuilder("GET").setSpanKind(SpanKind.SERVER).startSpan();
                try (var _ = agentSpan.makeCurrent()) {
                    var plugin = new OtelPlayPlugin();
                    plugin.beforeActionInvocation(null);
                    plugin.onActionInvocationFinally();
                } finally {
                    agentSpan.end();
                }
            });
            var server = spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).toList();
            assertEquals(1, server.size(), () -> "one server span, the agent's, not two: " + spans);
            assertEquals("GET /api/status", server.getFirst().getName(), "renamed from the route");
            assertEquals("/api/status", server.getFirst().getAttributes().get(HttpAttributes.HTTP_ROUTE));
        } finally {
            Http.Request.current.remove();
            OtelRuntime.agentAttachedForTest(false);
        }
    }

    @Test
    void withExportOffARequestRecordsNothing() {
        // Outside the capture seam the sampler is off: the same request must leave no span behind.
        assertIsOk(GET("/api/status"));
        var spans = OtelRuntime.captureForTest(() -> { });
        assertTrue(spans.isEmpty(), () -> "leaked spans: " + spans);
    }
}
