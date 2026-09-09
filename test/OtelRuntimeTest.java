import io.opentelemetry.api.trace.SpanKind;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.telemetry.OtelConfig;
import services.telemetry.OtelRuntime;
import utils.LatencyStats;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;


/**
 * The runtime's contract with the Config DB (JCLAW-34): keys are validated at the write,
 * a write re-applies the leaves live, and with export off no span is recorded at all.
 * Holds {@link TelemetryTestSync} because the runtime is process-global.
 */
class OtelRuntimeTest extends UnitTest {

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
    void offByDefaultAndNothingIsRecorded() {
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
    void aBadEndpointIsRefusedAtTheWriteNotAtTheNextExport() {
        var rejection = ConfigService.setWithSideEffects(OtelConfig.KEY_ENDPOINT, "collector:4318");
        assertNotNull(rejection);
        assertTrue(rejection.contains("absolute http(s) URL"), rejection);
        assertEquals(OtelConfig.DEFAULT_ENDPOINT, OtelRuntime.status().endpoint());
    }

    @Test
    void enablingThroughTheConfigWriteSwapsTheLeavesWithoutRestart() {
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
    void turningExportOffFlushesWhatTheOldEndpointHasNotSeenYet() throws Exception {
        try (var collector = new MockWebServer()) {
            collector.start();
            for (int i = 0; i < 4; i++) {
                collector.enqueue(new MockResponse.Builder().code(200).build());
            }
            var endpoint = collector.url("/").toString().replaceAll("/$", "");
            assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENDPOINT, endpoint));
            assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENABLED, "true"));
            int seenBeforeOff;
            try {
                LatencyStats.record("web", "seg-" + System.nanoTime(), 12, "main");
            } finally {
                seenBeforeOff = collector.getRequestCount();
                assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENABLED, "false"));
            }
            var targets = new ArrayList<String>();
            RecordedRequest request;
            while ((request = collector.takeRequest(2, TimeUnit.SECONDS)) != null) {
                targets.add(request.getTarget());
            }
            var afterOff = targets.subList(Math.min(seenBeforeOff, targets.size()), targets.size());
            assertTrue(afterOff.contains("/v1/metrics"),
                    () -> "the off-swap collects and exports before the leaf goes; after off: " + afterOff);
        }
    }

    @Test
    void withTheAgentAttachedTheHttpClientIsHandedBackUnwrapped() {
        assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENDPOINT, "http://127.0.0.1:1"));
        assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENABLED, "true"));
        var client = new OkHttpClient();
        try {
            assertNotSame(client, OtelRuntime.traced(client), "export on wraps the client");
            OtelRuntime.agentAttachedForTest(true);
            assertSame(client, OtelRuntime.traced(client), "the agent instruments OkHttp itself");
        } finally {
            OtelRuntime.agentAttachedForTest(false);
            assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENABLED, "false"));
        }
    }

    @Test
    void testSpanReportsExportOffRatherThanPretendingDelivery() {
        var result = OtelRuntime.sendTestSpan();
        assertFalse(result.delivered());
        assertNotNull(result.error());
        assertTrue(result.error().contains("export is off"), result.error());
    }

    @Test
    void testSpanAgainstAClosedPortReportsTheFailureNotSuccess() {
        assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENDPOINT, "http://127.0.0.1:1"));
        assertNull(ConfigService.setWithSideEffects(OtelConfig.KEY_ENABLED, "true"));
        var result = OtelRuntime.sendTestSpan();
        assertFalse(result.delivered());
        assertNotNull(result.error(), "a refused connection must surface as the reason");
    }

    @Test
    void captureSeamSeesSpansWhileExportStaysOffAfterwards() {
        var spans = OtelRuntime.captureForTest(() ->
                OtelRuntime.tracer().spanBuilder("captured").startSpan().end());
        assertEquals(1, spans.size());
        assertEquals("captured", spans.getFirst().getName());
        assertFalse(OtelRuntime.status().enabled());
        assertFalse(OtelRuntime.tracer().spanBuilder("after").startSpan().isRecording());
    }

    @Test
    void signalPathsAreAppendedOnceForOtlpHttp() {
        assertEquals("http://c:4318/v1/traces", OtelRuntime.signalUrl("http://c:4318", "traces"));
        assertEquals("http://c:4318/v1/traces", OtelRuntime.signalUrl("http://c:4318/", "traces"));
        assertEquals("http://c:4318/v1/traces", OtelRuntime.signalUrl("http://c:4318/v1/traces", "traces"));
        assertEquals("https://otlp.example/x/v1/metrics", OtelRuntime.signalUrl("https://otlp.example/x", "metrics"));
    }
}
