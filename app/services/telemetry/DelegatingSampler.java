package services.telemetry;

import com.google.errorprone.annotations.MustBeClosed;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

import java.util.List;

/**
 * The sampler the tracer provider is built with, forwarding to whatever leaf
 * {@link OtelRuntime#applyConfig()} last installed. Starts at {@code alwaysOff}, so until
 * the operator enables export no span is ever recorded — the disabled cost is this one
 * volatile read per span start.
 */
final class DelegatingSampler implements Sampler {

    private volatile Sampler delegate = Sampler.alwaysOff();

    @MustBeClosed
    DelegatingSampler() {}

    void swap(Sampler next) {
        delegate = next;
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                                       SpanKind spanKind, Attributes attributes,
                                       List<LinkData> parentLinks) {
        return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    @Override
    public String getDescription() {
        return "DelegatingSampler{" + delegate.getDescription() + "}";
    }
}
