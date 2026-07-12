package services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.Play;
import utils.JsonArgs;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tailscale Funnel integration (JCLAW-84): expose JClaw's local HTTP port to the
 * public internet over HTTPS so webhook-mode channels (e.g. the Slack Events API)
 * get a reachable Request URL without a manually-configured tunnel.
 *
 * <p>There is no Java SDK for Tailscale — its only embeddable library, {@code tsnet},
 * is Go-only, and the local API socket is explicitly unstable. So, like OpenClaw's
 * implementation, this shells out to the {@code tailscale} CLI via {@link ProcessBuilder}.
 * The CLI drives a locally-running {@code tailscaled}; {@code funnel --bg} hands the
 * config to that daemon (it persists across this JVM), so callers must explicitly
 * {@link #disable()} it on teardown.
 *
 * <p>Commands used:
 * <ul>
 *   <li>{@code tailscale status --json} — preflight (is the node connected?) and to
 *       derive the public URL from {@code .Self.DNSName}.</li>
 *   <li>{@code tailscale funnel --bg --yes <port>} — start funnelling the local port
 *       (public listener defaults to 443; Funnel only allows 443/8443/10000).</li>
 *   <li>{@code tailscale funnel reset} — stop funnelling.</li>
 * </ul>
 *
 * <p>The command runner is injectable ({@link Runner}) so the parsing and dispatch
 * logic is unit-testable without a real {@code tailscale} binary.
 */
public final class TailscaleFunnel {

    private TailscaleFunnel() {}

    private static final String CATEGORY = "tailscale";
    private static final String FUNNEL = "funnel";
    private static final String HTTPS_SCHEME = "https://";
    private static final String TAILSCALE_IPS = "TailscaleIPs";
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(5);
    /** {@code funnel --bg} can return before the node's funnel capability has synced
     *  (e.g. right after a Tailscale reconnect), so confirm-and-retry (JCLAW-337). */
    private static final int ENABLE_ATTEMPTS = 3;
    private static final long ENABLE_RETRY_MILLIS = 2000;
    /** macOS GUI-app install location, checked when {@code tailscale} isn't on PATH. */
    private static final String MAC_APP_BINARY = "/Applications/Tailscale.app/Contents/MacOS/Tailscale";

    /** Runs an external command and returns its result. Injectable for tests. */
    @FunctionalInterface
    public interface Runner {
        ExecResult run(List<String> command, Duration timeout);
    }

    public record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() { return exitCode == 0 && !timedOut; }
    }

    /** Snapshot for the admin UI / API. */
    public record Status(boolean available, String publicUrl, String error) {}

    private static final Runner PROCESS_RUNNER = TailscaleFunnel::execProcess;
    private static volatile String cachedBinary;

    /** Short-TTL cache for the real-process {@link #status()} read path: the probe
     *  shells out to {@code tailscale status --json} (~400ms) and the channel pages
     *  read it on every load. Funnel state changes are rare and explicitly drop the
     *  cache ({@link #invalidateStatusCache()} on enable/disable), so the TTL only
     *  bounds staleness from out-of-band changes (daemon down, manual CLI). */
    private static final Duration STATUS_CACHE_TTL = Duration.ofSeconds(10);
    private static volatile CachedStatus statusCache;
    private record CachedStatus(Status status, long atNanos) {}

    // ===================== public API (real process runner) =====================

    /** Probe whether Funnel can be used here, and the node's public base URL.
     *  Cached for {@link #STATUS_CACHE_TTL} so repeated reads (channel pages load
     *  this on mount) don't each pay the ~400ms {@code tailscale status} shell-out. */
    public static Status status() {
        var cached = statusCache;
        if (cached != null && System.nanoTime() - cached.atNanos() < STATUS_CACHE_TTL.toNanos()) {
            return cached.status();
        }
        var fresh = status(PROCESS_RUNNER);
        statusCache = new CachedStatus(fresh, System.nanoTime());
        return fresh;
    }

    /** The node's public HTTPS base URL (e.g. {@code https://host.tailnet.ts.net}), or null. */
    public static String publicBaseUrl() { return publicBaseUrl(PROCESS_RUNNER); }

    /** Start funnelling {@code localPort} to the public internet (idempotent). */
    public static boolean enable(int localPort) {
        boolean ok = enable(localPort, PROCESS_RUNNER);
        invalidateStatusCache();  // state changed — the next status() must re-probe
        return ok;
    }

    /** Stop funnelling (idempotent — safe even when nothing is configured). */
    public static boolean disable() {
        boolean ok = disable(PROCESS_RUNNER);
        invalidateStatusCache();
        return ok;
    }

    /** Drop the cached status so the next {@link #status()} re-probes immediately,
     *  rather than serving a pre-toggle snapshot for up to the TTL. */
    private static void invalidateStatusCache() { statusCache = null; }

    // ===================== config-driven orchestration =====================

    public static final String CFG_ENABLED = "tailscale.funnel.enabled";
    public static final String CFG_PORT = "tailscale.funnel.port";

    /** Whether the operator switched Funnel on for this JClaw instance. */
    public static boolean isFunnelEnabled() {
        return Boolean.parseBoolean(ConfigService.get(CFG_ENABLED, "false"));
    }

    /** Local port to funnel — the configured override, else JClaw's http.port (9000). */
    public static int configuredPort() {
        int fallback;
        try {
            fallback = Integer.parseInt(Play.configuration.getProperty("http.port", "9000"));
        } catch (NumberFormatException _) {
            fallback = 9000;
        }
        return ConfigService.getInt(CFG_PORT, fallback);
    }

    /**
     * Align the live funnel with config — called at boot and after a toggle-on.
     * No-op (no process spawned) when disabled, so the common "off" case is free.
     * When enabled and Tailscale is usable, (re)establishes the funnel; otherwise
     * returns the unavailable {@link Status} so the caller can surface why.
     */
    public static Status reconcile() {
        if (!isFunnelEnabled()) {
            return new Status(false, null, "Tailscale Funnel is disabled");
        }
        var st = status();
        if (st.available()) {
            enable(configuredPort());
            return status();
        }
        return st;
    }

    /** Tear down the funnel on shutdown, but only if this instance enabled it. */
    public static void disableIfEnabled() {
        if (isFunnelEnabled()) disable();
    }

    // ===================== command builders (pure) =====================

    public static List<String> statusCmd(String bin) { return List.of(bin, "status", "--json"); }

    public static List<String> enableCmd(String bin, int port) {
        return List.of(bin, FUNNEL, "--bg", "--yes", Integer.toString(port));
    }

    public static List<String> resetCmd(String bin) { return List.of(bin, FUNNEL, "reset"); }

    public static List<String> funnelStatusCmd(String bin) { return List.of(bin, FUNNEL, "status"); }

    // ===================== pure parsing =====================

    /**
     * Derive the public HTTPS base URL from {@code tailscale status --json}: the
     * MagicDNS name ({@code Self.DNSName}, trailing dot stripped), or the first
     * Tailscale IP as a fallback. Null when neither is present.
     */
    public static String publicBaseUrlFrom(String statusJson) {
        var self = selfObject(statusJson);
        if (self == null) return null;
        var dns = JsonArgs.optString(self, "DNSName");
        if (dns != null && !dns.isBlank()) {
            return HTTPS_SCHEME + dns.replaceAll("\\.$", "");
        }
        if (self.has(TAILSCALE_IPS) && self.get(TAILSCALE_IPS).isJsonArray()) {
            var ips = self.getAsJsonArray(TAILSCALE_IPS);
            if (!ips.isEmpty()) return HTTPS_SCHEME + ips.get(0).getAsString();
        }
        return null;
    }

    public static String backendStateFrom(String statusJson) {
        var obj = parseNoisyJson(statusJson);
        return obj != null ? JsonArgs.optString(obj, "BackendState") : null;
    }

    private static JsonObject selfObject(String statusJson) {
        var obj = parseNoisyJson(statusJson);
        if (obj != null && obj.has("Self") && obj.get("Self").isJsonObject()) {
            return obj.getAsJsonObject("Self");
        }
        return null;
    }

    /** Parse a JSON object out of stdout that may carry leading/trailing noise. */
    static JsonObject parseNoisyJson(String stdout) {
        if (stdout == null) return null;
        var s = stdout.strip();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) s = s.substring(start, end + 1);
        try {
            var el = JsonParser.parseString(s);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (var v : vals) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return "";
    }

    // ===================== exec-driven (runner injected) =====================

    static String detectBinary(Runner runner) {
        // Cache only the real-process resolution so an injected test runner can
        // never poison the binary used by production calls.
        if (runner == PROCESS_RUNNER && cachedBinary != null) return cachedBinary;
        String resolved = "tailscale";
        var which = runner.run(List.of("which", "tailscale"), STATUS_TIMEOUT);
        var path = which.stdout() != null ? which.stdout().strip() : "";
        if (which.ok() && !path.isBlank()) {
            resolved = path;
        } else if (new File(MAC_APP_BINARY).canExecute()) {
            resolved = MAC_APP_BINARY;
        }
        if (runner == PROCESS_RUNNER) cachedBinary = resolved;
        return resolved;
    }

    public static Status status(Runner runner) {
        var bin = detectBinary(runner);
        var res = runner.run(statusCmd(bin), STATUS_TIMEOUT);
        if (!res.ok()) {
            return new Status(false, null, firstNonBlank(res.stderr(), res.stdout(),
                    "tailscale CLI not available or tailscaled not running"));
        }
        var state = backendStateFrom(res.stdout());
        if (!"Running".equals(state)) {
            return new Status(false, null,
                    "Tailscale is installed but not connected (state: " + (state != null ? state : "unknown") + ")");
        }
        return new Status(true, publicBaseUrlFrom(res.stdout()), null);
    }

    static String publicBaseUrl(Runner runner) {
        var bin = detectBinary(runner);
        var res = runner.run(statusCmd(bin), STATUS_TIMEOUT);
        return res.ok() ? publicBaseUrlFrom(res.stdout()) : null;
    }

    public static boolean enable(int localPort, Runner runner) {
        return enable(localPort, runner, ENABLE_ATTEMPTS, ENABLE_RETRY_MILLIS);
    }

    /**
     * Enable Funnel and confirm it is actually serving (JCLAW-337). {@code funnel --bg}
     * can return without the funnel established — the node's funnel capability may still
     * be syncing right after a Tailscale reconnect, and the command can even exit 0 while
     * nothing is served — so we verify via {@code funnel status} and retry a few times
     * before giving up, reporting a clear error rather than a false success. {@code attempts}
     * and {@code retryMillis} are parameters so tests run without sleeping.
     */
    public static boolean enable(int localPort, Runner runner, int attempts, long retryMillis) {
        var bin = detectBinary(runner);
        String lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            var res = runner.run(enableCmd(bin, localPort), EXEC_TIMEOUT);
            if (res.ok() && funnelServing(bin, runner)) {
                EventLogger.info(CATEGORY, null, null, "Funnel enabled on local port " + localPort
                        + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                return true;
            }
            lastError = res.ok()
                    ? "command exited 0 but funnel is not serving (node may be re-syncing)"
                    : firstNonBlank(res.stderr(), res.stdout(), "exit " + res.exitCode());
            if (attempt < attempts && retryMillis > 0) sleepQuietly(retryMillis);
        }
        EventLogger.warn(CATEGORY, null, null, "Funnel enable failed (port " + localPort
                + ") after " + attempts + " attempt(s): " + lastError);
        return false;
    }

    /** True when {@code tailscale funnel status} reports an active funnel (vs "No serve config"). */
    private static boolean funnelServing(String bin, Runner runner) {
        var res = runner.run(funnelStatusCmd(bin), STATUS_TIMEOUT);
        if (!res.ok() || res.stdout() == null) return false;
        var out = res.stdout().strip().toLowerCase();
        return out.contains(HTTPS_SCHEME) && !out.contains("no serve config");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean disable(Runner runner) {
        var bin = detectBinary(runner);
        var res = runner.run(resetCmd(bin), EXEC_TIMEOUT);
        if (res.ok()) {
            EventLogger.info(CATEGORY, null, null, "Funnel reset");
            return true;
        }
        EventLogger.warn(CATEGORY, null, null, "Funnel reset failed: "
                + firstNonBlank(res.stderr(), res.stdout(), "exit " + res.exitCode()));
        return false;
    }

    // ===================== real ProcessBuilder runner =====================

    private static ExecResult execProcess(List<String> command, Duration timeout) {
        Process proc;
        try {
            proc = new ProcessBuilder(command).start();
        } catch (IOException e) {
            // Binary not found / not executable — treated as "not available".
            return new ExecResult(-1, "", e.getMessage() != null ? e.getMessage() : "exec failed", false);
        }
        // StringBuffer (not StringBuilder): the drain virtual threads write
        // concurrently with the waiter's toString() read on the timeout path,
        // where joinQuietly's 2s cap can elapse before a wedged drainer
        // finishes. The synchronized append/toString closes that data-race
        // window — without it the read is not guaranteed to see the writes.
        var out = new StringBuffer();
        var err = new StringBuffer();
        var tOut = Thread.ofVirtual().start(() -> drain(proc.getInputStream(), out));
        var tErr = Thread.ofVirtual().start(() -> drain(proc.getErrorStream(), err));
        try {
            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                // destroyForcibly closes the process's stdout/stderr, which
                // normally unblocks the drainers' readAllBytes(). Close them
                // explicitly too so a drainer can't stay parked on the pipe
                // holding an FD past joinQuietly's cap (best effort — drain's
                // try-with-resources also closes on its own exit).
                proc.destroyForcibly();
                closeQuietly(proc.getInputStream());
                closeQuietly(proc.getErrorStream());
                joinQuietly(tOut);
                joinQuietly(tErr);
                return new ExecResult(-1, out.toString(), err.toString(), true);
            }
            joinQuietly(tOut);
            joinQuietly(tErr);
            return new ExecResult(proc.exitValue(), out.toString(), err.toString(), false);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            closeQuietly(proc.getInputStream());
            closeQuietly(proc.getErrorStream());
            return new ExecResult(-1, out.toString(), err.toString(), true);
        }
    }

    private static void drain(InputStream in, StringBuffer sink) {
        try (in) {
            sink.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException _) {
            // best effort — partial output is acceptable for diagnostics
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException _) {
            // best effort — we're already tearing the process down
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            if (!t.join(Duration.ofSeconds(2))) {
                // A drainer outliving the cap means the pipe never closed —
                // log so the FD/VT leak is observable rather than silent.
                EventLogger.warn(CATEGORY, null, null,
                        "tailscale drain thread did not finish within 2s; output may be truncated");
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
