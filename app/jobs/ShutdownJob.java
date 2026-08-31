package jobs;

import channels.SlackSocketModeRunner;
import channels.TelegramPollingRunner;
import channels.TelegramStreamingSink;
import channels.WhatsAppCobaltRunner;
import mcp.McpConnectionManager;
import play.jobs.Job;
import play.jobs.OnApplicationStop;
import services.EventLogger;
import services.FetchSidecarManager;
import services.StealthSidecarManager;
import services.TailscaleFunnel;
import services.imagegen.LocalImageSidecarManager;
import services.search.LuceneIndexer;
import services.transcription.AsrSidecarManager;
import services.transcription.DiarizeSidecarManager;
import services.tts.TtsJvmEngine;
import services.tts.TtsSidecarManager;
import services.videogen.LocalVideoSidecarManager;
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
 * Play's 30-second scheduler-shutdown budget.
 *
 * <p>JCLAW-1143: the fan-out is safe because no component depends on another's
 * teardown, not because nothing shared is in play. Play stops plugins in reverse
 * registration order and runs {@code @OnApplicationStop} jobs synchronously, so
 * JobsPlugin (700) completes this method before JPAPlugin (400) and DBPlugin (300)
 * close — the datasource is open throughout. Teardown still avoids the database:
 * a component that needs a connection has no useful recovery when it cannot get
 * one, and the one observed failure here was a shutdown-time config read.
 *
 * <p>The dependency that does bite lives inside a component rather than between
 * them. db-scheduler's stop interrupts its own executor threads, and a JDK NIO
 * interrupt closes H2's FileChannel for every caller, so its in-flight task
 * executions can surface as MVStoreException reads at length -1.
 *
 * <p>Any individual component failure is logged but doesn't block the others;
 * the JVM exits whatever the outcome.
 */
@OnApplicationStop
public class ShutdownJob extends Job<Void> {

    /** Hard upper bound on total shutdown time. Each component's internal
     *  timeout is shorter than this; the bound is a defensive ceiling for
     *  the case where one component is wedged. Stays well under Play's 30s
     *  scheduler-shutdown budget so {@link Job}'s parent timeout is the
     *  one that fires if anything goes wrong, not Play's. */
    private static final long OVERALL_TIMEOUT_SECONDS = 15;

    /** EventLogger category for all messages emitted from this shutdown hook. */
    private static final String CATEGORY = "shutdown";

    /** Cap on the rendered cause chain, so one wedged component cannot flood the log. */
    private static final int MAX_CAUSE_CHARS = 500;

    /** Named subsystem-stop. The name drives the per-component progress
     *  logging so operators can see what is stopping and when (and which
     *  one is wedged if the overall timeout fires). */
    private record Component(String name, Runnable action) {}

    /**
     * Render the whole cause chain. A shutdown failure often carries a null or empty
     * message at the top — {@code getMessage()} alone reported "JDBC begin transaction
     * failed: " with nothing after it, which is not enough to diagnose from (JCLAW-1143).
     * Stacks stay out of the shutdown log to keep it readable.
     */
    private static String describe(Throwable t) {
        var sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < MAX_CAUSE_CHARS; c = c.getCause()) {
            if (!sb.isEmpty()) sb.append(" <- ");
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null && !c.getMessage().isBlank()) {
                sb.append(": ").append(c.getMessage());
            }
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }

    @Override
    public void doJob() {
        // From here on EventLogger is file-only: the JPA layer is tearing down,
        // so the shutdown logging below would otherwise trip a batched DB flush
        // into a doomed "begin transaction failed" WARN. (Set in every
        // @OnApplicationStop hook — whichever runs first wins; the rest no-op.)
        EventLogger.markShuttingDown();
        var components = List.of(
                new Component("db-scheduler", DbSchedulerBootstrapJob::shutdownGracefully),
                new Component("playwright-browser", PlaywrightBrowserTool::closeAllSessions),
                new Component("telegram-polling", TelegramPollingRunner::stop),
                new Component("slack-socket-mode", SlackSocketModeRunner::stop),
                new Component("whatsapp-cobalt", WhatsAppCobaltRunner::stop),
                new Component("telegram-streaming-sink", TelegramStreamingSink::shutdown),
                new Component("asr-sidecar", AsrSidecarManager::stop),
                new Component("diarize-sidecar", DiarizeSidecarManager::stop),
                new Component("tts-sidecar", TtsSidecarManager::stop),
                new Component("tts-jvm-engine", TtsJvmEngine::release),
                new Component("imagegen-sidecar", LocalImageSidecarManager::stop),
                new Component("videogen-sidecar", LocalVideoSidecarManager::stop),
                new Component("fetch-sidecar", FetchSidecarManager::stop),
                new Component("stealth-sidecar", StealthSidecarManager::stop),
                new Component("mcp-connections", McpConnectionManager::shutdown),
                new Component("lucene-index", LuceneIndexer::close),
                new Component("tailscale-funnel", TailscaleFunnel::disableIfEnabled)
        );

        EventLogger.info(CATEGORY,
                "Graceful shutdown: stopping %d subsystems".formatted(components.size()));
        long startedAt = System.currentTimeMillis();

        var latch = new CountDownLatch(components.size());
        for (var component : components) {
            Thread.ofVirtual().name("shutdown-" + component.name()).start(() -> {
                long t0 = System.currentTimeMillis();
                EventLogger.info(CATEGORY, "stopping %s".formatted(component.name()));
                try {
                    component.action().run();
                    EventLogger.info(CATEGORY,
                            "%s stopped (%dms)".formatted(
                                    component.name(), System.currentTimeMillis() - t0));
                } catch (@SuppressWarnings("java:S1181") Throwable t) {
                    // Top-level guard for shutdown VT — one component's failure must never break the latch
                    EventLogger.warn(CATEGORY,
                            "%s FAILED after %dms: %s".formatted(
                                    component.name(), System.currentTimeMillis() - t0, describe(t)));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean allStopped = latch.await(OVERALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - startedAt;
            if (allStopped) {
                EventLogger.info(CATEGORY,
                        "Graceful shutdown complete: %d/%d subsystems stopped in %dms"
                                .formatted(components.size(), components.size(), elapsed));
            } else {
                EventLogger.warn(CATEGORY,
                        "Graceful shutdown timed out after %ds — proceeding anyway; "
                                .formatted(OVERALL_TIMEOUT_SECONDS)
                                + "a 'stopping X' with no matching 'X stopped' line is the wedged subsystem");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
