package jobs;

import channels.TelegramPollingRunner;
import channels.TelegramStreamingSink;
import controllers.ApiChatController;
import play.jobs.Job;
import play.jobs.OnApplicationStop;
import services.EventLogger;
import tools.PlaywrightBrowserTool;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Stop all long-running jclaw subsystems on JVM shutdown. Runs as
 * {@code @OnApplicationStop} via Play's job scheduler.
 *
 * <p>JCLAW-191: components fan out across virtual threads instead of
 * running serially. Each component has its own internal timeout (5–10s);
 * running them in parallel means the total wall-clock is bounded by
 * {@code max(component)} rather than {@code sum(component)}, well inside
 * Play's 30-second scheduler-shutdown budget. The previous serial
 * structure could exceed 30s when {@code TaskPollerJob.shutdownGracefully}
 * alone was given 30s, surfacing the "Jobs scheduler did not terminate
 * within 30000 ms" warn on every restart.
 *
 * <p>Components are independent — {@link TaskPollerJob} doesn't share
 * state with {@link TelegramPollingRunner}, etc. — so order doesn't
 * matter and concurrency is safe. Any individual component failure is
 * logged but doesn't block the others; the JVM exits whatever the
 * outcome.
 */
@OnApplicationStop
public class ShutdownJob extends Job<Void> {

    /** Hard upper bound on total shutdown time. Each component's internal
     *  timeout is shorter than this; the bound is a defensive ceiling for
     *  the case where one component is wedged. Stays well under Play's 30s
     *  scheduler-shutdown budget so {@link Job}'s parent timeout is the
     *  one that fires if anything goes wrong, not Play's. */
    private static final long OVERALL_TIMEOUT_SECONDS = 15;

    @Override
    public void doJob() {
        var components = List.<Runnable>of(
                TaskPollerJob::shutdownGracefully,
                PlaywrightBrowserTool::closeAllSessions,
                TelegramPollingRunner::stop,
                TelegramStreamingSink::shutdown,
                ApiChatController::shutdown
        );

        var latch = new CountDownLatch(components.size());
        for (var component : components) {
            Thread.ofVirtual().name("shutdown-component").start(() -> {
                try {
                    component.run();
                } catch (Throwable t) {
                    EventLogger.warn("shutdown",
                            "Shutdown component failed: %s".formatted(t.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            if (!latch.await(OVERALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                EventLogger.warn("shutdown",
                        "Shutdown components did not all finish within %ds — proceeding anyway"
                                .formatted(OVERALL_TIMEOUT_SECONDS));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
