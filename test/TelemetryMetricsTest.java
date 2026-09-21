import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.telemetry.GenAiSpans;
import services.telemetry.OtelRuntime;
import services.telemetry.TurnMetrics;
import utils.LatencyStats;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/** JVM metrics and the turn-segment histogram reach the metric exporter (JCLAW-34). */
class TelemetryMetricsTest extends UnitTest {

    @BeforeEach
    void lock() {
        TelemetryTestSync.acquire();
        OtelRuntime.init();
    }

    @AfterEach
    void unlock() {
        TelemetryTestSync.release();
    }

    private static List<String> names(Collection<MetricData> metrics) {
        return metrics.stream().map(MetricData::getName).distinct().sorted().toList();
    }

    private static Optional<HistogramPointData> segmentPoint(Collection<MetricData> metrics, String segment) {
        return metrics.stream()
                .filter(m -> m.getName().equals(TurnMetrics.SEGMENT_DURATION))
                .flatMap(m -> m.getHistogramData().getPoints().stream())
                .filter(p -> segment.equals(p.getAttributes().get(TurnMetrics.JCLAW_SEGMENT)))
                .findFirst();
    }

    @Test
    void aFlushThatMeetsAnExportInProgressStillDeliversWhatWasRecorded() throws InterruptedException {
        // The reader refuses a flush outright while another export holds it, so a flush that lands
        // during the periodic export returned before anything reached the exporter.
        var exporter = InMemoryMetricExporter.create();
        var collecting = new CountDownLatch(1);
        var holdOnce = new AtomicBoolean(true);
        try (var provider = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofHours(1)).build())
                .build()) {
            var meter = provider.get("flush-retry");
            try (var _ = meter.gaugeBuilder("slow.collection").ofLongs().buildWithCallback(m -> {
                if (holdOnce.compareAndSet(true, false)) {
                    collecting.countDown();
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                m.record(1);
            })) {
                var inFlight = Thread.ofPlatform().start(() -> provider.forceFlush().join(5, TimeUnit.SECONDS));
                assertTrue(collecting.await(5, TimeUnit.SECONDS), "the in-flight export never started collecting");
                meter.counterBuilder("recorded.meanwhile").build().add(1);

                OtelRuntime.flushMetrics(provider);

                assertTrue(exporter.getFinishedMetricItems().stream()
                                .anyMatch(m -> m.getName().equals("recorded.meanwhile")),
                        () -> "exported: " + names(exporter.getFinishedMetricItems()));
                inFlight.join();
            }
        }
    }

    @Test
    void aSegmentRecordingIsOneHistogramPointCarryingItsSeries() {
        var segment = "seg-" + System.nanoTime();
        var metrics = OtelRuntime.captureMetricsForTest(() -> LatencyStats.record("web", segment, 12, "main"));
        var point = segmentPoint(metrics, segment)
                .orElseThrow(() -> new AssertionError("no point for " + segment + " in " + names(metrics)));
        assertEquals(1, point.getCount());
        assertEquals(0.012, point.getSum(), 1e-9, "milliseconds in, seconds out");
        assertEquals("web", point.getAttributes().get(GenAiSpans.JCLAW_CHANNEL));
        assertEquals("main", point.getAttributes().get(GenAiSpans.JCLAW_AGENT));
        assertTrue(point.getBoundaries().contains(0.001),
                () -> "sub-10 ms segments need their own buckets: " + point.getBoundaries());
    }

    @Test
    void withExportOffASegmentRecordingRecordsNothing() {
        var segment = "seg-off-" + System.nanoTime();
        LatencyStats.record("web", segment, 12, "main");
        var metrics = OtelRuntime.captureMetricsForTest(() -> { });
        assertTrue(segmentPoint(metrics, segment).isEmpty(), () -> "leaked point for " + segment);
    }

    @Test
    void jvmMetricsAreRegisteredWithTheSdk() {
        var names = names(OtelRuntime.captureMetricsForTest(() -> { }));
        assertTrue(names.contains("jvm.memory.used"), names::toString);
        assertTrue(names.contains("jvm.thread.count"), names::toString);
    }
}
