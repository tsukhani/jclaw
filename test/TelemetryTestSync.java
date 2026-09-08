import services.ConfigService;
import services.telemetry.OtelConfig;
import services.telemetry.OtelRuntime;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialises tests that touch the process-global OpenTelemetry runtime (JCLAW-34): the
 * {@code otel.*} keys, {@link OtelRuntime#applyConfig()} and the capture seam. The play1
 * fork runs test classes concurrently, so two such tests interleaving would see each
 * other's sampler and exporter. The sibling of {@code ShellSandboxSync}.
 */
public final class TelemetryTestSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private TelemetryTestSync() {}

    public static void acquire() {
        LOCK.lock();
        try {
            reset();
        } catch (RuntimeException e) {
            LOCK.unlock();
            throw e;
        }
    }

    public static void release() {
        try {
            reset();
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    private static void reset() {
        ConfigService.delete(OtelConfig.KEY_ENABLED);
        ConfigService.delete(OtelConfig.KEY_ENDPOINT);
        ConfigService.delete(OtelConfig.KEY_PROTOCOL);
        ConfigService.delete(OtelConfig.KEY_SECRET_HEADERS);
        ConfigService.delete(OtelConfig.KEY_SAMPLER_RATIO);
        OtelRuntime.applyConfig();
    }
}
