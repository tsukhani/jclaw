package services;

import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot health check for an LM Studio instance reachable at
 * {@code provider.lm-studio.baseUrl}. Mirrors {@link OllamaLocalProbe} —
 * LM Studio exposes the same OpenAI-compatible {@code /v1/models} shape on
 * {@code http://localhost:1234/v1} by default. {@link jobs.LmStudioProbeJob}
 * uses {@link ProbeResult#connectionRefused()} to demote "LM Studio not
 * running" failures to DEBUG so a fresh install without LM Studio doesn't
 * print a spurious WARN on every JVM start.
 */
public class LmStudioProbe {

    public record ProbeResult(boolean available, int modelCount, String reason, boolean connectionRefused) { }

    private static final ProbeResult UNRUN = new ProbeResult(false, 0,
            "lm-studio probe has not run yet", false);

    private static final AtomicReference<ProbeResult> result = new AtomicReference<>(UNRUN);

    public static ProbeResult probe(String baseUrl) {
        return setResult(fromShared(LocalProviderProbeSupport.probeModels(baseUrl, "LM Studio")));
    }

    public static ProbeResult lastResult() {
        return result.get();
    }

    public static void setForTest(ProbeResult forced) {
        result.set(forced == null ? UNRUN : forced);
    }

    private static ProbeResult setResult(ProbeResult r) {
        result.set(r);
        return r;
    }

    private static ProbeResult fromShared(LocalProviderProbeSupport.Result r) {
        return new ProbeResult(r.available(), r.modelCount(), r.reason(), r.connectionRefused());
    }
}
