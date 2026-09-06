package tools;

import agents.DangerousActionGate;
import agents.ToolContext;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.ExecutableProbeSupport;
import utils.ChannelOriginTrust;

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
 *   <li><b>Linux</b> — {@code bwrap --ro-bind / / --dev /dev --tmpfs /tmp --proc /proc
 *       --tmpfs $HOME --bind <writeRoot> <writeRoot> [--ro-bind-try <allowance> <allowance>]*
 *       <argv>}: the visible filesystem is built from nothing, so secrets are ABSENT
 *       rather than merely denied (allowlist-by-construction). Mount order is
 *       load-bearing — see {@link #linuxArgv}.</li>
 * </ul>
 *
 * <p><b>Fails closed.</b> When the sandbox is enabled but this platform has no
 * supported mechanism (native Windows, WSL1) or its binary is missing/broken
 * (a WSL2 kernel with unprivileged user namespaces disabled), {@link #wrap}
 * throws — the caller aborts the run rather than launching unsandboxed.
 *
 * <p>Network egress stays open (the harness needs its API); a deny+allowlist
 * variant is future work, noted in the JCLAW-671 spike.
 *
 * <p>JCLAW-1153: the same two profile builders also confine native tools that
 * spawn processes, under their own tri-state key — see {@link #SHELL_SANDBOX_KEY}
 * and the {@code allowances}-taking {@link #wrap} overload. Those callers pass an
 * explicit allowance list instead of a {@link HarnessAdapter}; everything below
 * the key is shared, so the two boundaries cannot drift apart.
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

    /**
     * {@code shell.sandbox} — JCLAW-1153: the same tri-state, same {@code false}
     * default, for native tools that spawn processes ({@code exec}, and
     * {@code diarize_audio}'s ffmpeg extraction). Deliberately a SECOND key rather
     * than a reuse of {@link #ACP_SANDBOX_KEY}: an operator confining a coding
     * harness has not thereby asked to confine every shell command, and a shell run
     * writes to the agent workspace where a harness run writes to a session dir.
     */
    public static final String SHELL_SANDBOX_KEY = "shell.sandbox";

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

    /** Resolve {@link #ACP_SANDBOX_KEY}'s {@link Scope}. Unknown/empty/{@code false} → {@link Scope#OFF}. */
    public static Scope scope() {
        return scope(ACP_SANDBOX_KEY);
    }

    /** Resolve the {@link Scope} configured under {@code configKey}. Unknown/empty/{@code false} → {@link Scope#OFF}. */
    public static Scope scope(String configKey) {
        var raw = ConfigService.get(configKey, "").strip();
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

    /**
     * JCLAW-1153: the origin trust of the tool call running on this thread, for native
     * tools that have no run id to resolve one from. Reuses the dangerous-tool gate's
     * verdict rather than defining "the operator" a second time — {@code untrusted} mode
     * must confine exactly the turns {@code tool.approval.offChannelPolicy} treats as
     * off-channel, or the two knobs disagree about the same turn. An unrecorded origin
     * classifies as untrusted, so a caller with no provenance is confined, not exempted.
     */
    public static boolean nativeToolTrustedOrigin() {
        return ChannelOriginTrust.isOperatorOrigin(
                DangerousActionGate.effectiveOrigin(ToolContext.conversationId()));
    }

    /** Whether {@code configKey}'s {@link #scope(String)} confines a run with this origin trust. */
    private static boolean appliesTo(String configKey, boolean trustedOrigin) {
        return switch (scope(configKey)) {
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
    public static List<String> wrap(List<String> argv, @Nullable File session, HarnessAdapter adapter,
                                    boolean trustedOrigin) {
        return wrap(argv, session, adapter.sandboxAllowances(), ACP_SANDBOX_KEY, trustedOrigin);
    }

    /**
     * JCLAW-1153: wrap {@code argv} for a caller that has no {@link HarnessAdapter} —
     * a native tool that spawns a process. Identical to the adapter overload except
     * that the extra write grants come from {@code allowances} directly and the
     * tri-state is read from {@code configKey}.
     *
     * @param writeRoot   the one directory the process may write to (plus the
     *                    profile's fixed temp/dev grants). For a shell run this is
     *                    the agent's resolved workspace root.
     * @param allowances  extra paths to grant, absolute or {@code $HOME}-relative,
     *                    the way {@link HarnessAdapter#sandboxAllowances} declares
     *                    a harness's own state.
     */
    public static List<String> wrap(List<String> argv, @Nullable File writeRoot, List<String> allowances,
                                    String configKey, boolean trustedOrigin) {
        if (!appliesTo(configKey, trustedOrigin)) return argv;
        if (writeRoot == null) {
            throw new SandboxUnavailableException(
                    "sandboxing requires a session working directory, but none was resolved");
        }
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            requireBinary("sandbox-exec", configKey);
            return macos(argv, writeRoot, allowances);
        }
        if (os.contains("linux")) {
            requireBinary(BWRAP, configKey);
            return linuxArgv(argv, writeRoot, allowances);
        }
        throw new SandboxUnavailableException(
                ("%s is enabled but this platform (%s) has no supported sandbox "
                        + "— native Windows needs AppContainer/Job Objects (unimplemented); on Windows "
                        + "run JClaw under WSL2. Disable the sandbox or move to a supported host.")
                        .formatted(configKey, System.getProperty("os.name")));
    }

    private static void requireBinary(String binary, String configKey) {
        var probe = ExecutableProbeSupport.probeOnPath(
                binary, binary.equals(BWRAP) ? "--version" : "-p", "harness-sandbox", "");
        // sandbox-exec has no --version and exits non-zero on a bare -p; treat a
        // clean "not found on PATH" as the only fatal signal for it.
        if (!probe.available() && probe.reason().contains("not found on PATH")) {
            throw new SandboxUnavailableException(
                    ("%s is enabled but '%s' is not available (%s). On WSL2 this often "
                            + "means unprivileged user namespaces are disabled (kernel.unprivileged_userns_clone). "
                            + "Install/enable it or disable the sandbox — the run is aborted rather than launched "
                            + "unsandboxed.").formatted(configKey, binary, probe.reason()));
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

    /**
     * The Linux argv, public so a test can pin the mount order on any host. bwrap applies
     * mounts in argument order and a tmpfs over an ancestor hides every earlier bind beneath
     * it, so the {@code $HOME} tmpfs must precede the write-root bind: the workspace lives
     * under {@code $HOME} on every non-container install.
     */
    public static List<String> linuxArgv(List<String> argv, File writeRoot, List<String> allowances) {
        var home = System.getProperty("user.home", "");
        var out = new ArrayList<String>(List.of(
                BWRAP,
                "--ro-bind", "/", "/",
                "--dev", "/dev",
                "--tmpfs", "/tmp",
                "--proc", "/proc",
                // an empty HOME by default; the write root and the harness's own state
                // paths are bound back below (secrets not listed stay absent).
                "--tmpfs", home,
                // rebind the write root read-write over the read-only root
                "--bind", writeRoot.getAbsolutePath(), writeRoot.getAbsolutePath()));
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
