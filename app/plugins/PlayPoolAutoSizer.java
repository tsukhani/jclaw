package plugins;

import play.Play;
import play.PlayPlugin;

/**
 * Auto-sizes Play's invocation pool ({@code play.pool}) at startup based on the
 * JVM's available processor count. JClaw's request path is IO-heavy — LLM HTTP
 * calls dominate wall time, and the streaming path hands work to a virtual
 * thread via {@code await()} so the Play worker thread is released during the
 * long-running part. The worker pool only needs to absorb request dispatch
 * bursts, for which {@code max(8, cores*2)} is a reasonable default on any host.
 *
 * <p>Fires in {@link #onConfigurationRead()}, which runs at
 * {@code Play.readConfiguration()} — well before {@code Invoker.init()} reads
 * the pool size. Any explicit value in {@code application.conf} or
 * {@code -Dplay.pool=N} wins; the auto-size only kicks in when the key is unset.
 *
 * <p>{@link Runtime#availableProcessors()} honors cgroup CPU limits inside
 * containers (JDK 10+ with {@code UseContainerSupport}, on by default), so a
 * Docker/Kubernetes pod with a 2-CPU quota sees 2 processors here instead of
 * the host's full count — no extra plumbing required.
 */
public class PlayPoolAutoSizer extends PlayPlugin {

    private static final int MIN_POOL = 8;
    private static final int CORES_MULTIPLIER = 2;

    @Override
    public void onConfigurationRead() {
        // Respect any explicit override — either from a loaded .conf file or a
        // -Dplay.pool=N JVM flag. Play 1.x merges system properties into
        // Play.configuration at load time, so the first check normally covers
        // both; the second is a belt-and-braces guard for any path that sets
        // the system property without re-reading configuration.
        String configured = Play.configuration.getProperty("play.pool");
        if (configured != null && !configured.isBlank()) return;
        if (System.getProperty("play.pool") != null) return;

        int cores = Runtime.getRuntime().availableProcessors();
        int pool = Math.max(MIN_POOL, cores * CORES_MULTIPLIER);
        Play.configuration.setProperty("play.pool", String.valueOf(pool));
        play.Logger.info("[JClaw] Auto-sized play.pool=%d (availableProcessors=%d)", pool, cores);
    }
}
