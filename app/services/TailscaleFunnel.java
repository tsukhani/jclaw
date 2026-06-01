package services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(5);
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

    // ===================== public API (real process runner) =====================

    /** Probe whether Funnel can be used here, and the node's public base URL. */
    public static Status status() { return status(PROCESS_RUNNER); }

    /** The node's public HTTPS base URL (e.g. {@code https://host.tailnet.ts.net}), or null. */
    public static String publicBaseUrl() { return publicBaseUrl(PROCESS_RUNNER); }

    /** Start funnelling {@code localPort} to the public internet (idempotent). */
    public static boolean enable(int localPort) { return enable(localPort, PROCESS_RUNNER); }

    /** Stop funnelling (idempotent — safe even when nothing is configured). */
    public static boolean disable() { return disable(PROCESS_RUNNER); }

    // ===================== command builders (pure) =====================

    public static List<String> statusCmd(String bin) { return List.of(bin, "status", "--json"); }

    public static List<String> enableCmd(String bin, int port) {
        return List.of(bin, "funnel", "--bg", "--yes", Integer.toString(port));
    }

    public static List<String> resetCmd(String bin) { return List.of(bin, "funnel", "reset"); }

    // ===================== pure parsing =====================

    /**
     * Derive the public HTTPS base URL from {@code tailscale status --json}: the
     * MagicDNS name ({@code Self.DNSName}, trailing dot stripped), or the first
     * Tailscale IP as a fallback. Null when neither is present.
     */
    public static String publicBaseUrlFrom(String statusJson) {
        var self = selfObject(statusJson);
        if (self == null) return null;
        var dns = optString(self, "DNSName");
        if (dns != null && !dns.isBlank()) {
            return "https://" + dns.replaceAll("\\.$", "");
        }
        if (self.has("TailscaleIPs") && self.get("TailscaleIPs").isJsonArray()) {
            var ips = self.getAsJsonArray("TailscaleIPs");
            if (!ips.isEmpty()) return "https://" + ips.get(0).getAsString();
        }
        return null;
    }

    public static String backendStateFrom(String statusJson) {
        var obj = parseNoisyJson(statusJson);
        return obj != null ? optString(obj, "BackendState") : null;
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
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String optString(JsonObject o, String key) {
        var el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
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
        } else if (new java.io.File(MAC_APP_BINARY).canExecute()) {
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
        var bin = detectBinary(runner);
        var res = runner.run(enableCmd(bin, localPort), EXEC_TIMEOUT);
        if (res.ok()) {
            EventLogger.info(CATEGORY, null, null, "Funnel enabled on local port " + localPort);
            return true;
        }
        EventLogger.warn(CATEGORY, null, null, "Funnel enable failed (port " + localPort + "): "
                + firstNonBlank(res.stderr(), res.stdout(), "exit " + res.exitCode()));
        return false;
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
        var out = new StringBuilder();
        var err = new StringBuilder();
        var tOut = Thread.ofVirtual().start(() -> drain(proc.getInputStream(), out));
        var tErr = Thread.ofVirtual().start(() -> drain(proc.getErrorStream(), err));
        try {
            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                joinQuietly(tOut);
                joinQuietly(tErr);
                return new ExecResult(-1, out.toString(), err.toString(), true);
            }
            joinQuietly(tOut);
            joinQuietly(tErr);
            return new ExecResult(proc.exitValue(), out.toString(), err.toString(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return new ExecResult(-1, out.toString(), err.toString(), true);
        }
    }

    private static void drain(InputStream in, StringBuilder sink) {
        try (in) {
            sink.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException _) {
            // best effort — partial output is acceptable for diagnostics
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
