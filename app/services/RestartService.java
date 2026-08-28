package services;

import play.Play;
import utils.HandoffShell;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reboots this JClaw instance on operator request (the Settings → Restart
 * button, {@code POST /api/system/restart}).
 *
 * <p>A JVM cannot restart itself: once {@code System.exit} runs there is no
 * code left to bring it back. So the restart is a <em>handoff</em> — we spawn a
 * helper that outlives this process, and the helper stops us and starts our
 * replacement.
 *
 * <p>The helper is {@code jclaw.sh restart} rather than a hand-rolled
 * stop/start, because that script already encodes the sequencing this has to
 * get right: {@code play stop} deletes {@code server.pid} immediately but the
 * JVM keeps running shutdown hooks for up to 30s, so the only sound liveness
 * probe is {@code kill -0}; a wedged shutdown needs SIGKILL escalation; a stale
 * H2 lock has to be reaped before the next boot; and {@code do_start_prod}
 * refuses to bind while the old port is held. Reimplementing that here would
 * duplicate an invariant that is already tested and already drifts hard when
 * duplicated.
 */
public final class RestartService {

    private static final String CATEGORY = "restart";

    /**
     * Seconds the helper waits before signalling this JVM. Exists so the HTTP
     * 202 reaches the browser first — without it the SIGTERM races the response
     * write and the operator sees a connection reset instead of an ack. Two
     * seconds is ~1000× the loopback write it is covering; the cost is paid
     * once per restart and buys an unambiguous UI transition.
     */
    static final int RESPONSE_FLUSH_DELAY_SECONDS = 2;

    /** Relative path of the helper log, under the application root. */
    static final String RESTART_LOG = "logs/restart.log";

    /**
     * Test seam: when non-null, {@link #requestRestart()} hands the plan here
     * instead of spawning it. Lets tests assert the composed command without
     * rebooting the test JVM.
     */
    public static Consumer<Plan> spawnerForTest;

    /**
     * A restart that has been resolved but not yet launched.
     *
     * @param command         argv handed to {@link ProcessBuilder}
     * @param mode            {@code "DEV"} or {@code "PROD"}
     * @param backendOnly     true when the Nuxt dev server is being spared
     * @param rebuildExpected true when the restart <em>may</em> recompile
     *                        sources and rebuild the SPA. Deliberately coarse:
     *                        it answers "is this a source tree?", not "is
     *                        anything stale?" — jclaw.sh gates both steps on
     *                        staleness, so an unchanged checkout restarts about
     *                        as fast as a packaged install. Its real job is
     *                        sizing the UI's reconnect budget, where erring
     *                        long costs nothing; the copy beside it is worded
     *                        conditionally to match.
     */
    public record Plan(List<String> command, String mode, boolean backendOnly, boolean rebuildExpected) {}

    private RestartService() {}

    /** The {@code jclaw.sh} that manages this installation. */
    public static File script() {
        return new File(Play.applicationPath, "jclaw.sh");
    }

    /**
     * Why this instance cannot restart itself, or null when it can. Single
     * source for both the preflight's {@code available} flag and
     * {@link #requestRestart()}'s pre-spawn guard, so the UI never offers a
     * button that the POST would refuse.
     */
    public static String unavailableReason() {
        var script = script();
        if (!script.isFile()) {
            return "jclaw.sh not found at " + script.getAbsolutePath()
                    + " — this installation is not managed by jclaw.sh.";
        }
        if (!script.canExecute()) {
            return script.getAbsolutePath() + " is not executable.";
        }
        if (HandoffShell.locate() == null) {
            return "No shell was found to run jclaw.sh — " + HandoffShell.MISSING_SHELL_HINT;
        }
        return null;
    }

    /**
     * True when this tree carries sources, so a prod restart pays the
     * precompile + SPA-build passes. Mirrors {@code do_start_prod}'s own
     * {@code [[ ! -d app ]]} branch — a dist install has neither and boots
     * straight from {@code precompiled/}.
     */
    static boolean isDeveloperClone() {
        return new File(Play.applicationPath, "app").isDirectory();
    }

    /**
     * Resolve what a restart would run, without running it. Pure apart from
     * reading {@link Play} state and the filesystem, so the controller can use
     * it for its preflight response and tests can assert the argv.
     */
    public static Plan plan() {
        return planFor(script().getAbsolutePath(), Play.mode == Play.Mode.DEV, isDeveloperClone());
    }

    /**
     * The plan-composition rules, with every environment read passed in — so
     * both mode branches are assertable without a live Play in that mode.
     */
    public static Plan planFor(String scriptPath, boolean dev, boolean developerClone) {
        var args = new ArrayList<String>();
        args.add(scriptPath);
        if (dev) {
            // Dev runs two processes; only the Play backend is ours to bounce.
            // Restarting Nuxt would tear down the dev server that served the
            // page making this request, so the browser could never observe the
            // result. Prod has no second process — one JVM serves the SPA too.
            args.add("--dev");
            args.add("restart");
            args.add("--backend-only");
        } else {
            args.add("restart");
        }

        return new Plan(List.copyOf(args), dev ? "DEV" : "PROD", dev,
                !dev && developerClone);
    }

    /**
     * Launch the restart. Returns as soon as the helper is spawned — this JVM
     * keeps serving until the helper's {@code play stop} arrives a couple of
     * seconds later.
     *
     * @return the plan that was launched
     * @throws IllegalStateException if {@code jclaw.sh} is missing or not
     *         executable, checked <em>before</em> anything is spawned so a
     *         broken install fails loudly with the app still up
     * @throws IOException if the helper process cannot be started
     */
    public static Plan requestRestart() throws IOException {
        var unavailable = unavailableReason();
        if (unavailable != null) {
            throw new IllegalStateException("Cannot restart: " + unavailable);
        }

        var plan = plan();

        if (spawnerForTest != null) {
            spawnerForTest.accept(plan);
            return plan;
        }

        // WARN, not INFO: this is the last line the old JVM writes before it is
        // taken down, and it is the anchor an operator looks for when working
        // out why the instance went away.
        EventLogger.warn(CATEGORY, "Restarting on operator request — handing off to jclaw.sh",
                String.join(" ", plan.command()));
        spawn(plan);
        return plan;
    }

    /**
     * Spawn the helper so it survives this JVM's death.
     *
     * <p>{@code sh -c 'sleep N; exec …'} is the whole trick. The helper is a
     * child of this JVM, but POSIX reparents orphans to init rather than
     * killing them, and Java does not destroy children on exit — so once we
     * die it simply keeps running under PID 1. {@code exec} then replaces the
     * shell with {@code jclaw.sh}, leaving no idle shell behind.
     *
     * <p>Deliberately not {@code setsid}: it is absent on macOS, and it buys
     * nothing here. Both start paths already daemonise the JVM away from any
     * controlling terminal, so there is no SIGHUP to shield against; and
     * {@code play stop} signals {@code server.pid} specifically, never the
     * process group, so the helper is not in the blast radius of its own stop.
     */
    private static void spawn(Plan plan) throws IOException {
        var log = new File(Play.applicationPath, RESTART_LOG);
        //noinspection ResultOfMethodCallIgnored
        log.getParentFile().mkdirs();

        new ProcessBuilder(HandoffShell.detached(plan.command(), RESPONSE_FLUSH_DELAY_SECONDS))
                .directory(Play.applicationPath)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectErrorStream(true)
                .start();
    }
}
