package services.telemetry;

import com.google.errorprone.annotations.MustBeClosed;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * The span exporter the batch processor is built with, forwarding to the OTLP leaf
 * {@link OtelRuntime#applyConfig()} last installed. With no leaf every export succeeds
 * trivially, which is the disabled state; swapping in a new leaf shuts the old one down
 * so an endpoint change never leaves a connection behind.
 */
final class DelegatingSpanExporter implements SpanExporter {

    private volatile @Nullable SpanExporter delegate;
    private final Consumer<String> onFailure;

    /** @param onFailure receives a one-line description of each failed export batch */
    @MustBeClosed
    DelegatingSpanExporter(Consumer<String> onFailure) {
        this.onFailure = onFailure;
    }

    void swap(@Nullable SpanExporter next) {
        var previous = delegate;
        delegate = next;
        if (previous != null) {
            previous.shutdown();
        }
    }

    boolean hasDelegate() {
        return delegate != null;
    }

    /**
     * A view for a throwaway probe provider: exports through this leaf, but the probe's
     * own shutdown never reaches it — a {@code SimpleSpanProcessor} shuts its exporter
     * down with the provider, and this leaf outlives any probe.
     */
    SpanExporter probeView() {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return DelegatingSpanExporter.this.export(spans);
            }

            @Override
            public CompletableResultCode flush() {
                return DelegatingSpanExporter.this.flush();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        var d = delegate;
        if (d == null) {
            return CompletableResultCode.ofSuccess();
        }
        var result = d.export(spans);
        result.whenComplete(() -> {
            if (!result.isSuccess()) {
                var cause = result.getFailureThrowable();
                onFailure.accept(cause != null ? cause.toString() : "export of " + spans.size() + " span(s) failed");
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
}
