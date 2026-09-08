package jobs;

import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.telemetry.OtelRuntime;

/**
 * Brings up the OpenTelemetry runtime once the application — and its Config DB — is
 * up (JCLAW-34). The request-span plugin itself is listed in {@code conf/play.plugins};
 * it cannot call {@code OtelRuntime.init()} from its own start hook without pulling the
 * app into ECJ's early plugin compile, so the boot lives here. Idempotent, so a
 * dev-mode reload re-running it is harmless.
 */
@OnApplicationStart
public class TelemetryBootstrapJob extends Job<Void> {

    @Override
    public void doJob() {
        OtelRuntime.init();
    }
}
