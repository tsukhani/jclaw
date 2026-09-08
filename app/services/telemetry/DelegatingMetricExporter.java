package services.telemetry;

import com.google.errorprone.annotations.MustBeClosed;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * The metric exporter the periodic reader is built with; the metrics twin of
 * {@link DelegatingSpanExporter}. The reader keeps collecting on its schedule either way,
 * which is cheap — with no leaf the collected batch is simply dropped.
 */
final class DelegatingMetricExporter implements MetricExporter {

    private volatile @Nullable MetricExporter delegate;
    private final Consumer<String> onFailure;

    @MustBeClosed
    DelegatingMetricExporter(Consumer<String> onFailure) {
        this.onFailure = onFailure;
    }

    void swap(@Nullable MetricExporter next) {
        var previous = delegate;
        delegate = next;
        if (previous != null) {
            previous.shutdown();
        }
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        var d = delegate;
        if (d == null) {
            return CompletableResultCode.ofSuccess();
        }
        var result = d.export(metrics);
        result.whenComplete(() -> {
            if (!result.isSuccess()) {
                var cause = result.getFailureThrowable();
                onFailure.accept(cause != null ? cause.toString() : "metric export failed");
            }
        });
        return result;
    }

    @Override
    public CompletableResultCode flush() {
        var d = delegate;
        return d == null ? CompletableResultCode.ofSuccess() : d.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        var d = delegate;
        delegate = null;
        return d == null ? CompletableResultCode.ofSuccess() : d.shutdown();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        var d = delegate;
        // Cumulative is what the OTLP exporters default to; the reader asks once per
        // collection, so the answer only needs to be stable while a leaf is installed.
        return d == null ? AggregationTemporality.CUMULATIVE : d.getAggregationTemporality(instrumentType);
    }
}
