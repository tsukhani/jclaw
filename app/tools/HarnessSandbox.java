package tools;

import services.ConfigService;
import services.ExecutableProbeSupport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JCLAW-672: opt-in OS sandbox around a coding-harness process. When enabled
 * ({@code subagent.acp.sandbox=true}, or JCLAW-709's {@code =untrusted} for
 * untrusted-origin runs only), the resolved harness argv is wrapped so
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

    /**
     * {@code subagent.acp.sandbox} — tri-state, {@code false} by default:
     * <ul>
     *   <li>{@code false} — never sandbox (the shipped default; the trusted
     *       operator's coding runs stay fast and unconfined).</li>
     *   <li>{@code true} — sandbox EVERY acp run.</li>
     *   <li>{@code untrusted} — JCLAW-709: sandbox only runs whose ORIGIN is not
     *       the operator's own web chat (inbound Telegram/Slack, the prompt-
     *       injection surface), leaving the operator's web runs unconfined.</li>
     * </ul>
     */
    public static final String ACP_SANDBOX_KEY = "subagent.acp.sandbox";

    private static final String BWRAP = "bwrap";

    /** Secret paths a coding run never needs to read (relative to $HOME). */
    private static final List<String> DENY_READ_HOME = List.of(
            ".ssh", ".aws", ".gnupg", ".config/gcloud", ".kube", ".netrc");

    private HarnessSandbox() {}

    /** JCLAW-709: how broadly the OS sandbox applies, from {@link #ACP_SANDBOX_KEY}. */
    public enum Scope {
        /** Never confine (shipped default). */
        OFF,
        /** Confine every acp run ({@code subagent.acp.sandbox=true}). */
        ALL,
        /** Confine only untrusted-origin runs ({@code subagent.acp.sandbox=untrusted}). */
        UNTRUSTED
    }

    /** Resolve the configured {@link Scope}. Unknown/empty/{@code false} → {@link Scope#OFF}. */
    public static Scope scope() {
        var raw = ConfigService.get(ACP_SANDBOX_KEY, "").strip();
        if ("untrusted".equalsIgnoreCase(raw)) {
            return Scope.UNTRUSTED;
        }
        return Boolean.parseBoolean(raw) ? Scope.ALL : Scope.OFF;
    }

    /** True when the sandbox confines EVERY run ({@link Scope#ALL}) — the meaning
     *  a context-less caller of the 3-arg {@link #wrap} gets. */
    public static boolean enabled() {
        return scope() == Scope.ALL;
    }

    /** Whether the configured {@link #scope()} confines a run with this origin trust. */
    private static boolean appliesTo(boolean trustedOrigin) {
        return switch (scope()) {
            case OFF -> false;
            case ALL -> true;
            case UNTRUSTED -> !trustedOrigin;
        };
    }

    /**
     * Back-compat 3-arg wrap for callers with no origin-trust context: confines
     * only when the sandbox is on for EVERY run ({@link Scope#ALL}). The
     * untrusted-only mode ({@link Scope#UNTRUSTED}) needs an origin signal, so a
     * context-less caller is treated as trusted (unconfined) under it.
     */
    public static List<String> wrap(List<String> argv, File session, HarnessAdapter adapter) {
        return wrap(argv, session, adapter, true);
    }

    /**
     * Wrap {@code argv} in the platform sandbox, confining writes to
     * {@code session} plus the adapter's declared allowances. No-op passthrough
     * when the sandbox does not apply to this run (see {@link Scope} and
     * {@code trustedOrigin}). Throws {@link SandboxUnavailableException} when the
     * sandbox applies but this platform is unsupported or the sandbox binary is
     * absent — the caller must treat that as fail-closed (abort the run).
     *
     * @param trustedOrigin JCLAW-709: whether the run originates from the trusted
     *                       operator (web chat / no channel). Ignored unless the
     *                       configured {@link Scope} is {@link Scope#UNTRUSTED},
     *                       where only {@code false} (untrusted origin) confines.
     */
    public static List<String> wrap(List<String> argv, File session, HarnessAdapter adapter,
                                    boolean trustedOrigin) {
        if (!appliesTo(trustedOrigin)) return argv;
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
            requireBinary(BWRAP);
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
                binary, binary.equals(BWRAP) ? "--version" : "-p", "harness-sandbox", "");
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
            sb.append("(deny file-read* (subpath \"")
                    .append(escapeSeatbelt(absHome(home, s)))
                    .append("\"))\n");
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
                BWRAP,
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
        return "(allow file-write* (subpath \"" + escapeSeatbelt(path) + "\"))\n";
    }

    /**
     * JCLAW-731: escape a path for safe embedding in a Seatbelt (SBPL /
     * TinyScheme) double-quoted string literal. Backslash and double-quote are
     * the only characters that can terminate or break out of a quoted string;
     * escaping them keeps a hostile session path (e.g. one containing
     * {@code "} and {@code )}) inside the literal, so it can never close the
     * intended {@code (subpath "...")} early and inject a wider grant. Parens
     * need no escaping — they are ordinary characters inside a quoted string.
     * Control characters can't appear in a valid one-line profile and signal a
     * malformed/hostile path, so they are rejected outright (fail closed).
     */
    private static String escapeSeatbelt(String path) {
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) < 0x20) {
                throw new SandboxUnavailableException(
                        "sandbox: refusing to build a profile for a path with control characters: "
                                + path);
            }
        }
        return path.replace("\\", "\\\\").replace("\"", "\\\"");
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
