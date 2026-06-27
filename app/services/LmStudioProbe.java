package services;

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

    private static final ProbeCache<ProbeResult> CACHE = new ProbeCache<>(
            new ProbeResult(false, 0, "lm-studio probe has not run yet", false));

    public static ProbeResult probe(String baseUrl) {
        // JCLAW-186: probeModels now uses OkHttp under the hood. OkHttp does
        // not attempt h2c upgrade on plain HTTP, so the LM Studio
        // Express/Node upgrade-event hang the JDK driver had to dodge is
        // structurally absent — no version pin needed.
        var r = LocalProviderProbeSupport.probeModels(baseUrl, "LM Studio");
        return CACHE.set(new ProbeResult(r.available(), r.modelCount(), r.reason(), r.connectionRefused()));
    }

    public static ProbeResult lastResult() {
        return CACHE.get();
    }

    public static void setForTest(ProbeResult forced) {
        CACHE.setForTest(forced);
    }
}
