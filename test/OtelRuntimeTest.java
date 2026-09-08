import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.telemetry.OtelConfig;
import services.telemetry.OtelRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runtime's contract with the Config DB (JCLAW-34): keys are validated at the write,
 * a write re-applies the leaves live, and with export off no span is recorded at all.
 * Holds {@link TelemetryTestSync} because the runtime is process-global.
 */
public class OtelRuntimeTest extends UnitTest {

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
    public void offByDefaultAndNothingIsRecorded() {
        var status = OtelRuntime.status();
        assertTrue(status.initialized());
        assertFalse(status.enabled());
        assertFalse(status.exporting());
        // With the sampler off, spanBuilder hands back a non-recording span: no processor, no export.
        var span = OtelRuntime.tracer().spanBuilder("probe").setSpanKind(SpanKind.INTERNAL).startSpan();
        assertFalse(span.isRecording());
        span.end();
    }

    @Test
    public void aBadEndpointIsRefusedAtTheWriteNotAtTheNextExport() {
        var rejection = ConfigService.setWithSideEffects(OtelConfig.KEY_ENDPOINT, "collector:4318");
        assertNotNull(rejection);
        assertTrue(rejection.contains("absolute http(s) URL"), rejection);
        assertEquals(OtelConfig.DEFAULT_ENDPOINT, OtelRuntime.status().endpoint());
    }

    @Test
    public void enablingThroughTheConfigWriteSwapsTheLeavesWithoutRestart() {
        assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENDPOINT, "http://127.0.0.1:1"));
        assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENABLED, "true"));
        var on = OtelRuntime.status();
        assertTrue(on.enabled());
        assertTrue(on.exporting());
        assertEquals("http://127.0.0.1:1", on.endpoint());
        assertTrue(OtelRuntime.tracer().spanBuilder("probe").startSpan().isRecording());

        assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENABLED, "false"));
        var off = OtelRuntime.status();
        assertFalse(off.enabled());
        assertFalse(off.exporting());
        assertFalse(OtelRuntime.tracer().spanBuilder("probe").startSpan().isRecording());
    }

    @Test
    public void testSpanReportsExportOffRatherThanPretendingDelivery() {
        var result = OtelRuntime.sendTestSpan();
        assertFalse(result.delivered());
        assertNotNull(result.error());
        assertTrue(result.error().contains("export is off"), result.error());
    }

    @Test
    public void testSpanAgainstAClosedPortReportsTheFailureNotSuccess() {
        assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENDPOINT, "http://127.0.0.1:1"));
        assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENABLED, "true"));
        var result = OtelRuntime.sendTestSpan();
        assertFalse(result.delivered());
        assertNotNull(result.error(), "a refused connection must surface as the reason");
    }

    @Test
    public void captureSeamSeesSpansWhileExportStaysOffAfterwards() {
        var spans = OtelRuntime.captureForTest(() ->
                OtelRuntime.tracer().spanBuilder("captured").startSpan().end());
        assertEquals(1, spans.size());
        assertEquals("captured", spans.getFirst().getName());
        assertFalse(OtelRuntime.status().enabled());
        assertFalse(OtelRuntime.tracer().spanBuilder("after").startSpan().isRecording());
    }

    @Test
    public void signalPathsAreAppendedOnceForOtlpHttp() {
        assertEquals("http://c:4318/v1/traces", OtelRuntime.signalUrl("http://c:4318", "traces"));
        assertEquals("http://c:4318/v1/traces", OtelRuntime.signalUrl("http://c:4318/", "traces"));
        assertEquals("http://c:4318/v1/traces", OtelRuntime.signalUrl("http://c:4318/v1/traces", "traces"));
        assertEquals("https://otlp.example/x/v1/metrics", OtelRuntime.signalUrl("https://otlp.example/x", "metrics"));
    }
}
