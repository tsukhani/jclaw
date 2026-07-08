package tools;

import services.ConfigService;
import services.ExecutableProbeSupport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JCLAW-672: opt-in OS sandbox around a coding-harness process. When
 * {@code subagent.acp.sandbox=true}, the resolved harness argv is wrapped so
 * the process can write only inside its session directory and cannot read the
 * operator's secrets (~/.ssh, other apps' tokens, arbitrary home files). The
 * harness's own declared state paths (see {@link HarnessAdapter#sandboxAllowances})
 * are the only extra grants — so even the unavoidable "harness reads its own
 * credentials" exposure is scoped to exactly those paths.
 *
 * <p>Two platforms, one model (allow reads broadly, writes narrowly):
 * <ul>
 *   <li><b>macOS</b> — {@code sandbox-exec -p '<profile>' <argv>} with an inline
 *       Seatbelt profile (no temp file): allow-default, deny all writes except
 *       the session dir + {@code /private/tmp} + {@code /private/var/folders} +
 *       {@code /dev}, deny reads of the enumerated secret paths.</li>
 *   <li><b>Linux</b> — {@code bwrap --ro-bind / / --dev /dev --tmpfs /tmp
 *       --bind <session> <session> [--ro-bind <allowance> <allowance>]* <argv>}:
 *       the visible filesystem is built from nothing, so secrets are ABSENT
 *       rather than merely denied (allowlist-by-construction).</li>
 * </ul>
 *
 * <p><b>Fails closed.</b> When the sandbox is enabled but this platform has no
 * supported mechanism (native Windows, WSL1) or its binary is missing/broken
 * (a WSL2 kernel with unprivileged user namespaces disabled), {@link #wrap}
 * throws — the caller aborts the run rather than launching unsandboxed.
 *
 * <p>Network egress stays open (the harness needs its API); a deny+allowlist
 * variant is future work, noted in the JCLAW-671 spike.
 */
public final class HarnessSandbox {

    /** {@code subagent.acp.sandbox} — off by default. */
    public static final String ACP_SANDBOX_KEY = "subagent.acp.sandbox";

    /** Secret paths a coding run never needs to read (relative to $HOME). */
    private static final List<String> DENY_READ_HOME = List.of(
            ".ssh", ".aws", ".gnupg", ".config/gcloud", ".kube", ".netrc");

    private HarnessSandbox() {}

    public static boolean enabled() {
        return ConfigService.getBoolean(ACP_SANDBOX_KEY, false);
    }

    /**
     * Wrap {@code argv} in the platform sandbox, confining writes to
     * {@code session} plus the adapter's declared allowances. No-op passthrough
     * when the sandbox is disabled. Throws {@link SandboxUnavailableException}
     * when enabled on an unsupported platform or when the sandbox binary is
     * absent — the caller must treat that as fail-closed (abort the run).
     */
    public static List<String> wrap(List<String> argv, File session, HarnessAdapter adapter) {
        if (!enabled()) return argv;
        if (session == null) {
            throw new SandboxUnavailableException(
                    "sandboxing requires a session working directory, but none was resolved");
        }
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var allowances = adapter.sandboxAllowances();
        if (os.contains("mac") || os.contains("darwin")) {
            requireBinary("sandbox-exec");
            return macos(argv, session, allowances);
        }
        if (os.contains("linux")) {
            requireBinary("bwrap");
            return linux(argv, session, allowances);
        }
        throw new SandboxUnavailableException(
                ("subagent.acp.sandbox is enabled but this platform (%s) has no supported sandbox "
                        + "— native Windows needs AppContainer/Job Objects (unimplemented); on Windows "
                        + "run JClaw under WSL2. Disable the sandbox or move to a supported host.")
                        .formatted(System.getProperty("os.name")));
    }

    private static void requireBinary(String binary) {
        var probe = ExecutableProbeSupport.probeOnPath(
                binary, binary.equals("bwrap") ? "--version" : "-p", "harness-sandbox", "");
        // sandbox-exec has no --version and exits non-zero on a bare -p; treat a
        // clean "not found on PATH" as the only fatal signal for it.
        if (!probe.available() && probe.reason().contains("not found on PATH")) {
            throw new SandboxUnavailableException(
                    ("subagent.acp.sandbox is enabled but '%s' is not available (%s). On WSL2 this often "
                            + "means unprivileged user namespaces are disabled (kernel.unprivileged_userns_clone). "
                            + "Install/enable it or disable the sandbox — the run is aborted rather than launched "
                            + "unsandboxed.").formatted(binary, probe.reason()));
        }
    }

    private static List<String> macos(List<String> argv, File session, List<String> allowances) {
        var home = System.getProperty("user.home", "");
        var sb = new StringBuilder("(version 1)\n(allow default)\n");
        sb.append("(deny file-write* (subpath \"/\"))\n");
        sb.append(writeAllow(session.getAbsolutePath()));
        sb.append(writeAllow("/private/tmp"));
        sb.append(writeAllow("/private/var/folders"));
        sb.append(writeAllow("/dev"));
        for (var a : allowances) {
            sb.append(writeAllow(absHome(home, a)));
        }
        for (var s : DENY_READ_HOME) {
            sb.append("(deny file-read* (subpath \"").append(absHome(home, s)).append("\"))\n");
        }
        var out = new ArrayList<String>();
        out.add("sandbox-exec");
        out.add("-p");
        out.add(sb.toString());
        out.addAll(argv);
        return List.copyOf(out);
    }

    private static List<String> linux(List<String> argv, File session, List<String> allowances) {
        var home = System.getProperty("user.home", "");
        var out = new ArrayList<String>(List.of(
                "bwrap",
                "--ro-bind", "/", "/",
                "--dev", "/dev",
                "--tmpfs", "/tmp",
                "--proc", "/proc",
                // rebind the session dir read-write over the read-only root
                "--bind", session.getAbsolutePath(), session.getAbsolutePath()));
        // an empty HOME by default; the harness's own state paths are bound back
        // read-only one file/dir at a time (secrets not listed stay absent).
        out.add("--tmpfs");
        out.add(home);
        for (var a : allowances) {
            var abs = absHome(home, a);
            out.add("--ro-bind-try");
            out.add(abs);
            out.add(abs);
        }
        out.addAll(argv);
        return List.copyOf(out);
    }

    private static String writeAllow(String path) {
        return "(allow file-write* (subpath \"" + path + "\"))\n";
    }

    /** Resolve a possibly-$HOME-relative allowance path to absolute. */
    private static String absHome(String home, String path) {
        if (path.startsWith("/")) return path;
        return home.endsWith("/") ? home + path : home + "/" + path;
    }

    /** Thrown when the sandbox is enabled but cannot be applied — fail closed. */
    public static final class SandboxUnavailableException extends RuntimeException {
        public SandboxUnavailableException(String message) {
            super(message);
        }
    }
}
