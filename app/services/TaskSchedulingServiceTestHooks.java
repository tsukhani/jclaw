package services;

import com.github.kagkarlsson.scheduler.SchedulerClient;

/**
 * Package-internal access shim used exclusively by
 * {@code TaskSchedulingServiceTest} to inject a recording stub
 * {@link SchedulerClient} for unit-test isolation.
 *
 * <p>Play 1.x test classes live in the default package, so they
 * cannot reach a package-private setter on
 * {@link TaskSchedulingService} directly. This shim is the bridge:
 * the test calls into {@code services.TaskSchedulingServiceTestHooks}
 * (visible from the default package) which forwards to the
 * package-private setter on {@code TaskSchedulingService}.
 *
 * <p>Production code MUST NOT use this class. The setter it exposes
 * mutates a static reference inside
 * {@code TaskSchedulingService.clientSupplier}; using it from
 * production would silently swap out the real db-scheduler client
 * for whatever the most recent test installed.
 */
public final class TaskSchedulingServiceTestHooks {

    private TaskSchedulingServiceTestHooks() {}

    /**
     * Swap the {@link TaskSchedulingService}'s SchedulerClient
     * supplier for a fixed reference (typically a recording stub).
     * Tests should call {@link #reset()} in {@code @AfterEach} so
     * the next test doesn't observe stale stub state.
     */
    public static void setSchedulerClient(SchedulerClient client) {
        TaskSchedulingService.setSchedulerClientSupplierForTest(() -> client);
    }

    /**
     * Restore the production supplier ({@code DbSchedulerBootstrapJob.scheduler()}).
     */
    public static void reset() {
        TaskSchedulingService.setSchedulerClientSupplierForTest(null);
    }
}
