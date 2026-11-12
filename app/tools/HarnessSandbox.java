package tools;

import agents.DangerousActionGate;
import agents.ToolContext;
import org.jspecify.annotations.Nullable;
import play.Play;
import services.ConfigService;
import services.EventLogger;
import services.ExecutableProbeSupport;
import services.database.DatabaseService;
import utils.ChannelOriginTrust;
import utils.SandboxHiddenRoots;
import utils.SubprocessEnv;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 *       {@code /dev}, deny reads of the enumerated secret paths, and deny reads and
 *       writes of JClaw's own state ({@link SandboxHiddenRoots}).</li>
 *   <li><b>Linux</b> — {@code bwrap --ro-bind / / --dev /dev --tmpfs /tmp --proc /proc
 *       --tmpfs $HOME --bind <writeRoot> <writeRoot> [--ro-bind-try <allowance> <allowance>]*
 *       [--tmpfs <hidden root>]* <argv>}: the visible filesystem is built from nothing, so
 *       secrets are ABSENT rather than merely denied (allowlist-by-construction). Mount order is
 *       load-bearing — see {@link #linuxArgv}.</li>
 * </ul>
 *
 * <p><b>Fails closed.</b> When the sandbox applies but cannot start here (native Windows, WSL1,
 * {@code sandbox-exec} missing from PATH, or a {@code bwrap} that cannot create its namespaces —
 * {@link #availability} launches it to find out), {@link #wrap} throws and the caller aborts the run
 * rather than launching unsandboxed.
 *
 * <p>A coding harness keeps the network, since it needs its model API. A native tool's run loses it
 * under {@link #SHELL_NETWORK_KEY}{@code =deny}: bwrap's {@code --unshare-net}, or a Seatbelt
 * {@code (deny network*)}, which refuses local Unix sockets too because no confined tool needs one.
 *
 * <p>JCLAW-1153: the same two profile builders also confine native tools that
 * spawn processes, under their own tri-state key — see {@link #SHELL_SANDBOX_KEY}
 * and the {@code allowances}-taking {@link #wrap} overload. Those callers pass an
 * explicit allowance list instead of a {@link HarnessAdapter}; everything below
 * the key is shared, so the two boundaries cannot drift apart.
 *
 * <p>JF-165: an agent whose parent chain does not end at {@code main} runs {@code exec} through
 * {@link #wrapCustomAgentShell} whatever the key says, and on macOS its profile hides {@code $HOME}
 * as well; {@link #SHELL_SANDBOX_KEY} governs only {@code main}'s and its copies' {@code exec}. That run
 * also cannot attach to a JVM outside it, and with the network allowed it still reaches no local service
 * through a socket file outside its workspace and its own temp and runtime folders, nor moves one into
 * them: {@link #customAgentMacosProfile}, {@link #inOwnUserNamespace}, {@link #linuxOwnRuntime}. Nor does a
 * signal from it reach a process outside the run (JF-303): a Seatbelt rule on macOS, a PID namespace and a
 * session of its own on Linux ({@link LinuxPids}).
 */
public final class HarnessSandbox {

    /**
     * {@code subagent.acp.sandbox} — tri-state, {@code false} by default:
     * <ul>
     *   <li>{@code false} — never sandbox (the shipped default; the trusted
     *       operator's coding runs stay fast and unconfined).</li>
     *   <li>{@code true} — sandbox EVERY acp run.</li>
     *   <li>{@code untrusted} — JCLAW-709: sandbox only runs whose ORIGIN is not
     *       the operator's own web chat or voice mode (inbound Telegram/Slack, the
     *       prompt-injection surface), leaving the operator's own runs unconfined.</li>
     * </ul>
     */
    public static final String ACP_SANDBOX_KEY = "subagent.acp.sandbox";

    /**
     * {@code shell.sandbox} — JCLAW-1153: the same tri-state, same {@code false}
     * default, for native tools that spawn processes ({@code main}'s {@code exec} — a custom
     * agent's is always confined — and the {@code diarize_audio} and {@code LlmAudio} ffmpeg runs). Deliberately a SECOND key rather
     * than a reuse of {@link #ACP_SANDBOX_KEY}: an operator confining a coding
     * harness has not thereby asked to confine every shell command, and a shell run
     * writes to the agent workspace where a harness run writes to a session dir.
     */
    public static final String SHELL_SANDBOX_KEY = "shell.sandbox";

    /**
     * {@code shell.sandbox.network} — {@code allow} (also absent or blank) or {@code deny}. Read only for a
     * run confined under {@link #SHELL_SANDBOX_KEY} or by {@link #wrapCustomAgentShell}: a native tool needs
     * no network, and with it a guest's {@code exec} could send the workspace out.
     */
    public static final String SHELL_NETWORK_KEY = "shell.sandbox.network";

    /**
     * {@code shell.sandbox.homeReads} — extra folders under {@code $HOME}, comma-separated ({@code ~/} allowed),
     * that a custom agent's {@code exec} may read, read-only, though its home folder is hidden: a toolchain
     * whose real files sit outside the {@code PATH} folders, such as {@code ~/.local/share/uv}. Empty by default.
     * The secret folders and JClaw's own directories stay denied whatever it lists.
     */
    public static final String HOME_READS_KEY = "shell.sandbox.homeReads";

    private static final String BWRAP = "bwrap";
    private static final String SANDBOX_EXEC = "sandbox-exec";
    private static final String UNSHARE_USER = "--unshare-user";

    /** What keeps a run's signals inside it: see {@link LinuxPids}. */
    private static final List<String> OWN_PIDS_AND_SESSION = List.of("--unshare-pid", "--new-session");

    /**
     * A signal from the run reaches only the processes of that run: {@code same-sandbox} is one
     * {@code sandbox-exec} launch. So {@code kill} fails with EPERM for every signal, 0 included, at a
     * process of the operator's, at another agent's, and at one an earlier run of the same agent left in the
     * background, which {@code exec} stops for the agent from outside the sandbox
     * ({@code services.BackgroundProcesses}). SIGQUIT is among them, the first step of HotSpot's attach
     * handshake: it starts a JVM's attach listener.
     */
    private static final String MAC_DENY_SIGNALS_OUTSIDE = "(deny signal (require-not (target same-sandbox)))\n";

    /**
     * The second step of HotSpot's attach handshake. The listener's socket, {@code .java_pid<pid>} in the
     * temp directory, takes no connection, and no rename, unlink or hard link that would give it a name the
     * rule misses. The rule goes by name, not owner, so a JVM started inside the sandbox cannot be attached
     * to either.
     */
    private static final String MAC_DENY_JVM_ATTACH =
            "(deny network-outbound file-write* file-link (regex #\"/\\.java_pid[^/]*$\"))\n";

    /** The one socket outside a run's own folders that its commands need: macOS resolves names through it. */
    private static final String MAC_MDNS_SOCKET = "/private/var/run/mDNSResponder";

    /**
     * The temp folders a confined run may write to besides its write root. macOS shares them between every
     * program the user runs, their control sockets included.
     */
    private static final List<String> MAC_SHARED_TEMP = List.of("/private/tmp", "/private/var/folders");

    /**
     * LaunchServices starts an app outside the sandbox for whoever asks ({@code open}), which would run a
     * program the run wrote with none of this profile's rules.
     */
    private static final String MAC_DENY_LAUNCH_SERVICES = "(deny lsopen)\n";
    /** The setuid process viewers the Main Agent's fence runs outside the sandbox, where alone macOS starts them. */
    private static final List<String> MAC_SETUID_VIEWERS = List.of("/bin/ps", "/usr/bin/top");

    /**
     * Where Linux services keep their control sockets: a container runtime's, the session and system D-Bus,
     * the systemd user instance. A custom agent's run gets empty ones of its own.
     */
    private static final List<String> LINUX_RUNTIME_DIRS = List.of("/run", "/var/run");

    private static final String RESOLV_CONF = "/etc/resolv.conf";

    /** Secret paths a coding run never needs to read (relative to $HOME). */
    private static final List<String> DENY_READ_HOME = List.of(
            ".ssh", ".aws", ".gnupg", ".config/gcloud", ".kube", ".netrc");

    /**
     * The probe {@link #bwrapLaunchFailure} runs: the smallest sandbox {@link #linuxArgv} builds on.
     * INSTALL.md's sandbox check gives operators this exact command, so its "ok" means JClaw's probe passes.
     */
    public static final List<String> BWRAP_PROBE = List.of(
            BWRAP, "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp", "true");

    /**
     * What a root bwrap needs and gets: its own user namespace, and no capabilities in it. A process that keeps
     * them could, with a full bounding set (a root JClaw outside Docker), unmount the tmpfs that hides $HOME
     * and the state folders.
     */
    private static final List<String> ROOT_FLAGS = List.of(UNSHARE_USER, "--cap-drop", "ALL");

    /**
     * {@link #BWRAP_PROBE} for a process running as root. Started as root, bwrap makes no user namespace of its
     * own and needs CAP_SYS_ADMIN for the mount namespace, which Docker drops; {@code --unshare-user} makes it
     * create one, which the container's seccomp profile allows, and {@code --cap-drop ALL} leaves the process
     * none of its capabilities. The container runs as root, so INSTALL.md
     * gives operators this command.
     */
    public static final List<String> BWRAP_PROBE_AS_ROOT = withUserNamespace(BWRAP_PROBE);

    private static final boolean RUNS_AS_ROOT = detectRoot();

    private static final int PROBE_LINE_LIMIT = 200;

    /** Only a success is kept: a host whose namespaces are fixed later is re-probed rather than refused for the JVM's life. */
    private static volatile boolean bwrapLaunched;

    /** Which PID namespace a custom agent's run gets here, once a launch has shown it; a failure is probed again. */
    private static volatile @Nullable LinuxPids customAgentPids;

    private static final java.util.concurrent.atomic.AtomicReference<CustomAgentOverride> CUSTOM_AGENT_OVERRIDE =
            new java.util.concurrent.atomic.AtomicReference<>(CustomAgentOverride.NONE);

    private HarnessSandbox() {}

    private static List<String> withUserNamespace(List<String> bwrapCommand) {
        var out = new ArrayList<String>(bwrapCommand);
        out.addAll(1, ROOT_FLAGS);
        return List.copyOf(out);
    }

    /** bwrap is Linux-only, so no other OS asks; {@code UnixSystem} reads the uid without touching a path. */
    private static boolean detectRoot() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return false;
        try {
            return new com.sun.security.auth.module.UnixSystem().getUid() == 0;
        } catch (LinkageError | RuntimeException _) {
            return false;
        }
    }

    /**
     * The processes a bwrap run can signal. Linux has no rule for a signal's target, so a run that must reach
     * none outside it gets a PID namespace of its own: no process outside has a pid there, and {@code kill}
     * answers "No such process". Each run is its own namespace, so it does not reach what an earlier run of
     * the same agent left in the background either; {@code exec} stops that from outside
     * ({@code services.BackgroundProcesses}), where the namespace's processes have ordinary pids.
     *
     * <p>A PID namespace does not bound a process group. JClaw starts the run, so the run is born into
     * JClaw's group, and a signal to the caller's own group ({@code kill 0}) reaches every member of it,
     * whatever namespace each is in (measured). So a run in a PID namespace of its own is also in a session
     * of its own ({@code --new-session}), whose process groups hold only the run.
     */
    public enum LinuxPids {
        /** JClaw's own namespace: {@code main}'s {@code exec} and a coding harness, as before. */
        SHARED,
        /** A namespace of the run's own, with that namespace's procfs on {@code /proc}. */
        OWN,
        /**
         * A namespace of the run's own under the {@code /proc} the root bind brings. A container runtime mounts
         * over parts of {@code /proc}, and the kernel then refuses a new procfs to a user namespace (bwrap:
         * {@code Can't mount proc on /newroot/proc}). That {@code /proc} is read-only and lists the processes
         * outside by pids that mean nothing inside, so {@code ps} fails there and {@code pgrep} and
         * {@code pkill} match nothing of the run's own; a JVM, node and python3 start as usual (measured).
         */
        OWN_UNDER_OUTER_PROC
    }

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

    /** Whether {@link #wrap(List, File, List, String, boolean)} confines a run under {@code configKey} with this origin trust. */
    public static boolean confines(String configKey, boolean trustedOrigin) {
        return appliesTo(configKey, trustedOrigin);
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
     * that the extra write grants come from {@code allowances} directly, the
     * tri-state is read from {@code configKey}, and under {@link #SHELL_SANDBOX_KEY}
     * {@link #SHELL_NETWORK_KEY} can cut the network.
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
            requireBinary(SANDBOX_EXEC, configKey);
            return macos(argv, writeRoot, allowances, configKey);
        }
        if (os.contains("linux")) {
            requireBinary(BWRAP, configKey);
            return linuxArgv(argv, writeRoot, allowances, configKey);
        }
        throw new SandboxUnavailableException(
                ("%s is enabled but this platform (%s) has no supported sandbox "
                        + "— native Windows needs AppContainer/Job Objects (unimplemented); on Windows "
                        + "run JClaw under WSL2. Disable the sandbox or move to a supported host.")
                        .formatted(configKey, System.getProperty("os.name")));
    }

    /**
     * Wrap a custom agent's {@code exec} run: confined whatever {@link #SHELL_SANDBOX_KEY} says, with
     * writes limited to {@code writeRoot} and the network cut under {@link #SHELL_NETWORK_KEY}{@code =deny}.
     * On macOS the profile is {@link #customAgentMacosProfile}; on Linux it is {@link #linuxArgv}, which
     * already hides {@code $HOME}, {@link #inOwnUserNamespace in a user namespace of its own}, with
     * {@link #linuxOwnRuntime runtime folders of its own} and in a
     * {@link LinuxPids PID namespace and session of its own}.
     *
     * @throws SandboxUnavailableException with the bare reason when no sandbox can start here
     */
    public static ConfinedShell wrapCustomAgentShell(List<String> argv, File writeRoot) {
        var override = java.util.Objects.requireNonNull(CUSTOM_AGENT_OVERRIDE.get());
        if (override == CustomAgentOverride.PASSTHROUGH) return new ConfinedShell(argv, Map.of());
        if (override.unavailableReason() != null) throw new SandboxUnavailableException(override.unavailableReason());
        var ready = availability();
        if (!ready.available()) throw new SandboxUnavailableException(ready.reason());
        var home = System.getProperty("user.home", "");
        var extraReads = extraHomeReads(home);
        var pathEnv = SubprocessEnv.filteredHostEnv().get("PATH");
        if (ready.mechanism().equals(BWRAP)) {
            var resolvConf = realPath(RESOLV_CONF);
            var ownRuntime = linuxOwnRuntime(linuxRuntimeDirs(), resolvConf == null ? null : resolvConf.toString(), pathEnv);
            return new ConfinedShell(inOwnUserNamespace(linuxArgv(argv, writeRoot, extraReads, home,
                    linuxHiddenRoots(DatabaseService.backupsDir(), SandboxHiddenRoots.current()),
                    deniesNetwork(SHELL_SANDBOX_KEY), RUNS_AS_ROOT, ownRuntime, customAgentPids())), Map.of());
        }
        if (home.isBlank()) throw new SandboxUnavailableException("the home folder (user.home) is not set");
        var temp = customAgentTempDir(writeRoot);
        var profile = customAgentMacosProfile(writeRoot, home, SandboxHiddenRoots.current(),
                deniesNetwork(SHELL_SANDBOX_KEY), homeReadReopens(pathEnv, home, extraReads), plainFolderRealPath(temp));
        var out = new ArrayList<String>(List.of(SANDBOX_EXEC, "-p", profile));
        out.addAll(argv);
        return new ConfinedShell(List.copyOf(out), Map.of("TMPDIR", temp.toString()));
    }

    /**
     * A custom agent's run as {@link #wrapCustomAgentShell} confines it: the argv to start, and the variables
     * its rules count on, which go into the environment after the caller's own.
     */
    public record ConfinedShell(List<String> argv, Map<String, String> env) {}

    /**
     * What the Main Agent's fence protects (JF-325), as canonical paths, the form Seatbelt and bwrap match.
     *
     * @param readOnly JClaw's program files: the checkout, the Play framework and the Gradle cache a restart loads
     *                 its jars from, readable and never writable
     * @param writable the agents' workspaces, writable inside the read-only checkout
     * @param hidden   JClaw's secret folders, neither readable nor writable: {@link SandboxHiddenRoots#current()}
     *                 but {@code conf/}, which holds no secret
     * @param readable folders inside a hidden one that stay readable, never writable: the backup folder, which the
     *                 operator's backup task uploads (JF-325)
     * @param jvmPid   JClaw's own process, whose memory holds what {@code hidden} keeps off disk: the fenced shell
     *                 attaches to any JVM but this one
     */
    public record MainFence(List<Path> readOnly, Path writable, List<Path> hidden, List<Path> readable, long jvmPid) {

        /** The fence for this install, around the workspaces under {@code workspaceRoot}. */
        public static MainFence current(File workspaceRoot) {
            var home = System.getProperty("user.home", "");
            var readOnly = new ArrayList<Path>();
            readOnly.add(SandboxHiddenRoots.canonical(Play.applicationPath.toPath()));
            if (Play.frameworkPath != null) readOnly.add(SandboxHiddenRoots.canonical(Play.frameworkPath.toPath()));
            if (!home.isBlank()) readOnly.add(SandboxHiddenRoots.canonical(absHome(home, ".gradle")));
            var backups = SandboxHiddenRoots.canonical(DatabaseService.backupsDir());
            // The backup folder leaves the list so it can be reopened for reading; inside data/ it is still hidden
            // first. conf/ leaves it too: it holds no secret, and the checkout's read-only rule covers it.
            var hidden = SandboxHiddenRoots.current().stream()
                    .filter(p -> !p.equals(backups) && (p.getFileName() == null
                            || !p.getFileName().toString().equals("conf"))).toList();
            return new MainFence(List.copyOf(readOnly), SandboxHiddenRoots.canonical(workspaceRoot.toPath()), hidden,
                    List.of(backups), ProcessHandle.current().pid());
        }
    }

    /**
     * Wrap the Main Agent's {@code exec} when {@link #SHELL_SANDBOX_KEY} does not confine it (JF-325). The command
     * keeps everything it had, network, home folder and paths outside the workspace included, except JClaw's own
     * files: {@link MainFence}. It fails closed: where no sandbox starts the run is refused, never launched unfenced.
     * The {@link #setCustomAgentOverrideForTest} override applies here as it does to a custom agent's run.
     *
     * @throws SandboxUnavailableException with the bare reason when no sandbox can start here
     */
    public static List<String> wrapMainFence(List<String> argv, File workspaceRoot) {
        var override = java.util.Objects.requireNonNull(CUSTOM_AGENT_OVERRIDE.get());
        if (override == CustomAgentOverride.PASSTHROUGH) return argv;
        if (override.unavailableReason() != null) throw new SandboxUnavailableException(override.unavailableReason());
        var ready = availability();
        if (!ready.available()) throw new SandboxUnavailableException(ready.reason());
        var fence = MainFence.current(workspaceRoot);
        if (ready.mechanism().equals(BWRAP)) {
            // A tmpfs needs its mount point, so only the secret folders that exist are hidden.
            var present = new MainFence(fence.readOnly(), fence.writable(),
                    linuxHiddenRoots(DatabaseService.backupsDir(), fence.hidden()), fence.readable(), fence.jvmPid());
            return mainFenceLinuxArgv(argv, present, RUNS_AS_ROOT);
        }
        var out = new ArrayList<String>(List.of(SANDBOX_EXEC, "-p", mainFenceMacosProfile(fence)));
        out.addAll(argv);
        return List.copyOf(out);
    }

    /**
     * The Main Agent's Seatbelt profile, public so a test can pin its text. The last matching rule wins: the program
     * files lose their writes, the workspaces get them back, the secret folders lose everything after that, and a
     * readable folder inside one gets its reads back.
     *
     * <p>macOS runs no setuid program inside a sandbox (measured: {@code ps}, {@code top}, {@code sudo},
     * {@code crontab}, {@code su} and {@code traceroute} all fail with EPERM under a bare {@code (allow default)}), so
     * the two read-only process viewers run outside it. The last rule refuses JClaw's own attach socket, by its pid,
     * for connecting, renaming and linking, as {@link #MAC_DENY_JVM_ATTACH} does for every JVM in a custom agent's
     * run: {@code jcmd} against JClaw would otherwise read from memory what the fence hides on disk.
     */
    public static String mainFenceMacosProfile(MainFence fence) {
        var sb = new StringBuilder("(version 1)\n(allow default)\n");
        for (var p : fence.readOnly()) sb.append(rule("deny", "file-write*", p.toString()));
        sb.append(writeAllow(fence.writable().toString()));
        for (var p : fence.hidden()) sb.append(rule("deny", "file-read* file-write*", p.toString()));
        for (var p : fence.readable()) {
            var reopened = SandboxHiddenRoots.beneath(p.toString(), fence.hidden());
            if (reopened != null) sb.append(rule("allow", "file-read*", reopened.toString()));
        }
        for (var viewer : MAC_SETUID_VIEWERS) {
            sb.append("(allow process-exec (literal \"").append(viewer).append("\") (with no-sandbox))\n");
        }
        sb.append("(deny network-outbound file-write* file-link (regex #\"/\\.java_pid").append(fence.jvmPid())
                .append("$\"))\n");
        return sb.toString();
    }

    /**
     * The Main Agent's bwrap argv, public so a test can pin its order. bwrap applies mounts in argument order: the
     * whole filesystem as it is, the program files read-only over it, the workspaces writable inside them, a tmpfs
     * over each secret folder, and a readable folder bound back read-only over its tmpfs. A root bwrap runs in a user
     * namespace of its own, so its commands have no real root.
     */
    public static List<String> mainFenceLinuxArgv(List<String> argv, MainFence fence, boolean asRoot) {
        var out = new ArrayList<String>(List.of(BWRAP));
        if (asRoot) out.addAll(ROOT_FLAGS);
        out.addAll(List.of("--dev-bind", "/", "/"));
        for (var p : fence.readOnly()) out.addAll(List.of("--ro-bind-try", p.toString(), p.toString()));
        out.addAll(List.of("--bind", fence.writable().toString(), fence.writable().toString()));
        for (var p : fence.hidden()) out.addAll(List.of("--tmpfs", p.toString()));
        for (var p : fence.readable()) {
            var reopened = SandboxHiddenRoots.beneath(p.toString(), fence.hidden());
            if (reopened != null) out.addAll(List.of("--ro-bind", reopened.toString(), reopened.toString()));
        }
        out.addAll(argv);
        return List.copyOf(out);
    }

    /**
     * The temp folder of a custom agent's run on macOS, one per write root, inside this user's temp folder.
     * macOS shares that folder between every program the user runs, their control sockets included, so the
     * run's {@code TMPDIR} is this one and a socket in it is the only one in a temp folder the run reaches.
     * The name is short because a socket's path holds 103 bytes.
     */
    public static Path customAgentTempDir(File writeRoot) {
        var temp = SandboxHiddenRoots.lexical(System.getProperty("java.io.tmpdir", ""));
        if (temp == null || !temp.isAbsolute()) {
            throw new SandboxUnavailableException("the temp folder (java.io.tmpdir) is not an absolute path");
        }
        return temp.resolve("jclaw-%08x".formatted(writeRoot.getAbsolutePath().hashCode()));
    }

    /**
     * {@code temp} as a plain folder, at its path with the symlinks above it resolved: Seatbelt matches
     * {@code /private/var}, never {@code /var}. Its own name is never resolved. The folder that holds it is
     * writable to every run, so a link can stand at that name, and the profile would open the sockets of
     * whatever folder a followed link led to. Anything there that is not a folder is replaced by one.
     */
    private static String plainFolderRealPath(Path temp) {
        var shared = temp.getParent();
        var name = temp.getFileName();
        try {
            if (shared == null || name == null) throw new IOException("it has no folder above it");
            if (!Files.isDirectory(temp, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(temp);
                Files.createDirectory(temp);
            }
            return shared.toRealPath().resolve(name).toString();
        } catch (IOException e) {
            throw new SandboxUnavailableException("the run's temp folder %s could not be made: %s".formatted(temp, e));
        }
    }

    /** {@code path} with its symlinks resolved, or null when nothing is there. */
    private static @Nullable Path realPath(String path) {
        var lexical = SandboxHiddenRoots.lexical(path);
        try {
            return lexical == null ? null : lexical.toRealPath();
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * What a test puts in place of the sandbox for {@link #wrapCustomAgentShell} alone: the binary
     * ({@link #PASSTHROUGH}, the argv unwrapped) or the readiness probe ({@link #unavailable}).
     */
    public record CustomAgentOverride(boolean passthrough, @Nullable String unavailableReason) {
        public static final CustomAgentOverride NONE = new CustomAgentOverride(false, null);
        public static final CustomAgentOverride PASSTHROUGH = new CustomAgentOverride(true, null);

        public static CustomAgentOverride unavailable(String reason) {
            return new CustomAgentOverride(false, reason);
        }
    }

    /** Test seam: ShellSandboxSync sets it while it holds its lock and resets it to {@link CustomAgentOverride#NONE}. */
    public static void setCustomAgentOverrideForTest(CustomAgentOverride override) {
        CUSTOM_AGENT_OVERRIDE.set(override);
    }

    /**
     * The directories of {@code pathEnv} a hidden-home profile reopens for reading: each absolute entry
     * strictly below {@code home} on a path-segment boundary, then its canonical path when that differs
     * and is also below {@code home}, once each in PATH order.
     */
    public static List<String> homeReadReopens(@Nullable String pathEnv, String home) {
        return homeReadReopens(pathEnv, home, List.of());
    }

    /**
     * {@link #homeReadReopens(String, String)} with the operator's {@link #HOME_READS_KEY} folders after the
     * {@code PATH} ones, each held to the same rule: strictly below {@code home}, plus its canonical path when
     * that is also below it.
     */
    public static List<String> homeReadReopens(@Nullable String pathEnv, String home, List<String> extra) {
        if (home.isBlank()) return List.of();
        var homePath = SandboxHiddenRoots.lexical(home);
        if (homePath == null) return List.of();
        var entries = new ArrayList<String>();
        if (pathEnv != null) entries.addAll(List.of(pathEnv.split(File.pathSeparator)));
        entries.addAll(extra);
        var out = new LinkedHashSet<String>();
        for (var entry : entries) {
            var p = SandboxHiddenRoots.lexical(entry);
            if (entry.isEmpty() || p == null || !p.isAbsolute() || !strictlyBelow(p, homePath)) continue;
            out.add(p.toString());
            var canonical = SandboxHiddenRoots.canonical(p);
            if (strictlyBelow(canonical, homePath)) out.add(canonical.toString());
        }
        return List.copyOf(out);
    }

    private static boolean strictlyBelow(Path p, Path home) {
        return p.startsWith(home) && !p.equals(home);
    }

    /** Whether this host has the OS sandbox mechanism {@link #wrap} would use, and why not when it lacks one. */
    public record Availability(boolean available, String mechanism, String reason) {}

    /**
     * Whether the sandbox can start on this host: on Linux a real {@code bwrap} launch, on macOS the
     * {@code sandbox-exec} lookup. The one readiness check behind {@code GET /api/sandbox} and every
     * {@link #wrap} that confines.
     */
    public static Availability availability() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String binary;
        if (os.contains("mac") || os.contains("darwin")) {
            binary = SANDBOX_EXEC;
        } else if (os.contains("linux")) {
            binary = BWRAP;
        } else {
            return new Availability(false, "",
                    "this platform (%s) has no supported sandbox".formatted(System.getProperty("os.name")));
        }
        var failure = binary.equals(BWRAP) ? bwrapLaunchFailure() : missingSandboxExec();
        return failure == null
                ? new Availability(true, binary, "")
                : new Availability(false, binary, "'%s' is not available: %s".formatted(binary, failure));
    }

    /**
     * Why {@code bwrap} cannot start a sandbox here, or null when it can. {@code bwrap} on PATH is not
     * enough: a kernel without unprivileged user namespaces, or Docker's default seccomp profile, lets
     * it start and then refuses its namespaces.
     */
    private static @Nullable String bwrapLaunchFailure() {
        if (bwrapLaunched) return null;
        var probe = ExecutableProbeSupport.probeCapturing(RUNS_AS_ROOT ? BWRAP_PROBE_AS_ROOT : BWRAP_PROBE, "");
        if (probe.available()) {
            bwrapLaunched = true;
            return null;
        }
        var reason = failureLine(probe);
        if ("1".equals(System.getenv("JCLAW_CONTAINER"))) {
            reason += "; in Docker, start the container with the seccomp profile docker-compose.yml names"
                    + " (seccomp-bwrap.json), as INSTALL.md describes";
        }
        return reason;
    }

    /** What a failed probe printed first, cut to {@link #PROBE_LINE_LIMIT}, or the probe's own reason when it printed nothing. */
    private static String failureLine(ExecutableProbeSupport.CapturedResult probe) {
        var raw = probe.output();
        var firstLine = (raw == null ? "" : raw.strip()).lines().findFirst().orElse("").strip();
        return firstLine.isEmpty() ? probe.reason()
                : firstLine.substring(0, Math.min(firstLine.length(), PROBE_LINE_LIMIT));
    }

    /**
     * The PID namespace a custom agent's run gets on this host: {@link LinuxPids#OWN} where a launch shows the
     * kernel mounts its procfs, else {@link LinuxPids#OWN_UNDER_OUTER_PROC}. Never {@link LinuxPids#SHARED}:
     * where neither starts, the run is refused.
     */
    private static LinuxPids customAgentPids() {
        var known = customAgentPids;
        if (known != null) return known;
        var failure = "";
        for (var pids : List.of(LinuxPids.OWN, LinuxPids.OWN_UNDER_OUTER_PROC)) {
            var probe = ExecutableProbeSupport.probeCapturing(pidNamespaceProbe(pids, RUNS_AS_ROOT), "");
            if (probe.available()) {
                if (pids == LinuxPids.OWN_UNDER_OUTER_PROC) {
                    EventLogger.info("tool", "Sandbox: no new /proc can be mounted here (" + failure + "), so a custom"
                            + " agent's commands run in their own PID namespace under this host's /proc: ps fails in them");
                }
                customAgentPids = pids;
                return pids;
            }
            failure = failureLine(probe);
        }
        throw new SandboxUnavailableException(
                "'%s' cannot start a custom agent's namespaces: %s".formatted(BWRAP, failure));
    }

    /**
     * {@link #BWRAP_PROBE} as a custom agent's run starts it: in a user namespace, the PID namespace
     * {@code pids} names and a session of its own. Public so a test can pin it on any host.
     */
    public static List<String> pidNamespaceProbe(LinuxPids pids, boolean asRoot) {
        var out = new ArrayList<String>(inOwnUserNamespace(asRoot ? BWRAP_PROBE_AS_ROOT : BWRAP_PROBE));
        out.addAll(out.indexOf("--ro-bind"), OWN_PIDS_AND_SESSION);
        if (pids == LinuxPids.OWN_UNDER_OUTER_PROC) {
            int proc = out.indexOf("--proc");
            out.subList(proc, proc + 2).clear();
        }
        return List.copyOf(out);
    }

    /** Whether a run confined under {@code configKey} loses the network: only a native tool's, and only on {@code deny}. */
    private static boolean deniesNetwork(String configKey) {
        return configKey.equals(SHELL_SANDBOX_KEY)
                && "deny".equalsIgnoreCase(ConfigService.get(SHELL_NETWORK_KEY, "").strip());
    }

    /**
     * The folders {@link #HOME_READS_KEY} lists, absolute and valid: an entry {@link #homeReadsRejection} would
     * refuse is skipped, so a value stored before a rule tightened opens nothing it should not.
     */
    public static List<String> extraHomeReads(String home) {
        var value = ConfigService.get(HOME_READS_KEY, "");
        var out = new ArrayList<String>();
        for (var entry : value.split(",")) {
            var path = entry.strip();
            if (path.isEmpty() || homeReadsRejection(path, home) != null) continue;
            var absolute = expandHome(path, home);
            if (absolute != null) out.add(absolute);
        }
        return List.copyOf(out);
    }

    /** {@code path} made absolute (a leading {@code ~/} is {@code home}) and normalized, or null when it is no valid path. */
    private static @Nullable String expandHome(String path, String home) {
        if (!path.startsWith("~/")) {
            var lexical = SandboxHiddenRoots.lexical(path);
            return lexical == null ? null : lexical.toString();
        }
        var homePath = SandboxHiddenRoots.lexical(home);
        return homePath == null ? null : homePath.resolve(path.substring(2)).normalize().toString();
    }

    /** Why one {@link #HOME_READS_KEY} entry is refused, or null when it is a folder strictly below {@code home}. */
    static @Nullable String homeReadsRejection(String entry, String home) {
        if (!entry.startsWith("/") && !entry.startsWith("~/")) {
            return "'%s' is not an absolute folder: start it with / or ~/".formatted(entry);
        }
        if (entry.chars().anyMatch(Character::isISOControl) || entry.contains("\"") || entry.contains("\\")) {
            return "'%s' has a quote, a backslash or a control character".formatted(entry);
        }
        var homePath = SandboxHiddenRoots.lexical(home);
        var expanded = expandHome(entry, home);
        var path = expanded == null ? null : SandboxHiddenRoots.lexical(expanded);
        if (homePath == null || path == null || !strictlyBelow(path, homePath)) {
            return "'%s' is not a folder inside your home folder (%s)".formatted(entry, home);
        }
        for (var secret : DENY_READ_HOME) {
            if (path.startsWith(homePath.resolve(secret))) {
                return "'%s' is inside %s, which sandboxed commands never read".formatted(entry, "~/" + secret);
            }
        }
        return null;
    }

    /**
     * The refusal for a write of {@code value} to one of the sandbox keys, or null when the key is none of
     * them or the value is one its reader understands as intended.
     */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        if (key.equals(HOME_READS_KEY)) {
            if (value == null || value.isBlank()) return null;
            var home = System.getProperty("user.home", "");
            for (var entry : value.split(",")) {
                var rejected = entry.isBlank() ? null : homeReadsRejection(entry.strip(), home);
                if (rejected != null) return key + ": " + rejected + ".";
            }
            return null;
        }
        if (!key.equals(SHELL_SANDBOX_KEY) && !key.equals(ACP_SANDBOX_KEY) && !key.equals(SHELL_NETWORK_KEY)) {
            return null;
        }
        if (value == null || value.isBlank()) return null;
        var v = value.strip();
        if (key.equals(SHELL_NETWORK_KEY)) {
            return v.equalsIgnoreCase("allow") || v.equalsIgnoreCase("deny")
                    ? null
                    : key + " must be 'allow' or 'deny' (any case); blank means allow.";
        }
        if (v.equalsIgnoreCase("false") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("untrusted")) {
            return null;
        }
        return key + " must be 'false', 'untrusted' or 'true' (any case); blank means off.";
    }

    /** Why {@code sandbox-exec} is unusable, or null when it is on PATH. */
    private static @Nullable String missingSandboxExec() {
        var probe = ExecutableProbeSupport.probeOnPath(SANDBOX_EXEC, "-p", "harness-sandbox", "");
        // sandbox-exec has no --version and exits non-zero on a bare -p; treat a
        // clean "not found on PATH" as the only fatal signal for it.
        return !probe.available() && probe.reason().contains("not found on PATH") ? probe.reason() : null;
    }

    private static void requireBinary(String binary, String configKey) {
        var failure = binary.equals(BWRAP) ? bwrapLaunchFailure() : missingSandboxExec();
        if (failure != null) {
            throw new SandboxUnavailableException(
                    ("%s is enabled but '%s' is not available (%s). On WSL2 this often "
                            + "means unprivileged user namespaces are disabled (kernel.unprivileged_userns_clone). "
                            + "Install/enable it or disable the sandbox — the run is aborted rather than launched "
                            + "unsandboxed.").formatted(configKey, binary, failure));
        }
    }

    private static List<String> macos(List<String> argv, File session, List<String> allowances, String configKey) {
        var profile = macosProfile(session, allowances, System.getProperty("user.home", ""),
                SandboxHiddenRoots.current(), deniesNetwork(configKey));
        var out = new ArrayList<String>();
        out.add("sandbox-exec");
        out.add("-p");
        out.add(profile);
        out.addAll(argv);
        return List.copyOf(out);
    }

    /** {@link #macosProfile(File, List, String, List, boolean, List)} with {@code $HOME} readable. */
    public static String macosProfile(File writeRoot, List<String> allowances, String home, List<Path> hidden,
                                      boolean denyNetwork) {
        return macosProfile(writeRoot, allowances, home, hidden, denyNetwork, null);
    }

    /**
     * The Seatbelt profile, public so a test can pin its rule order on any host. The last
     * matching rule wins, so each {@code hidden} root is denied after every write grant (hiding
     * it even inside a grant above it), a grant beneath one is reopened after that, and the
     * {@code $HOME} secrets come last so no reopen can reach them.
     *
     * @param homeReads null keeps {@code $HOME} readable. Otherwise {@code $HOME} is denied for reading
     *                  after the write grants, then the write root and these directories are reopened, and
     *                  {@code $HOME} and each directory between it and a reopen get their metadata back so
     *                  {@code getcwd} and {@code realpath} resolve; only the write root is reopened beneath a
     *                  hidden root.
     */
    public static String macosProfile(File writeRoot, List<String> allowances, String home, List<Path> hidden,
                                      boolean denyNetwork, @Nullable List<String> homeReads) {
        var sb = new StringBuilder("(version 1)\n(allow default)\n");
        if (denyNetwork) sb.append("(deny network*)\n");
        sb.append("(deny file-write* (subpath \"/\"))\n");
        sb.append(writeAllow(writeRoot.getAbsolutePath()));
        for (var temp : MAC_SHARED_TEMP) {
            sb.append(writeAllow(temp));
        }
        sb.append(writeAllow("/dev"));
        var grants = new ArrayList<String>();
        grants.add(writeRoot.getAbsolutePath());
        for (var a : allowances) {
            var abs = absHome(home, a);
            sb.append(writeAllow(abs));
            grants.add(abs);
        }
        if (homeReads != null) appendHiddenHome(sb, writeRoot.getAbsolutePath(), home, homeReads);
        for (var root : hidden) {
            sb.append(rule("deny", "file-read* file-write*", root.toString()));
        }
        for (var g : homeReads == null ? grants : List.of(writeRoot.getAbsolutePath())) {
            var reopened = SandboxHiddenRoots.beneath(g, hidden);
            if (reopened != null) sb.append(rule("allow", "file-read* file-write*", reopened.toString()));
        }
        for (var s : DENY_READ_HOME) {
            sb.append(rule("deny", "file-read*", absHome(home, s)));
        }
        return sb.toString();
    }

    /**
     * A custom agent's Seatbelt profile, public so a test can pin its text on any host: {@code $HOME} hidden
     * except the write root and {@code homeReads} (the {@code $HOME} directories on JClaw's own PATH,
     * {@link #homeReadReopens}), then the local services outside the sandbox refused, and last, where no grant
     * outranks them, every signal to a process outside the run and the JVM attach socket.
     *
     * <p>A service that runs unconfined as the operator acts outside the sandbox for whoever connects: a
     * container runtime mounts any host folder. So with the network, a Unix socket takes the run's connection
     * only under the write root, under {@code ownTemp} and at mDNSResponder's path. Under {@code denyNetwork}
     * the one network rule already refuses every socket, and an allow after it would reopen one.
     * {@link #sharedTempStaysInPlace} keeps the sockets of the shared temp folders out of those two folders
     * under either setting, so none is waiting there when the network is allowed again.
     *
     * @param ownTemp {@link #customAgentTempDir} with the symlinks above it resolved, which is how Seatbelt
     *                matches it
     */
    public static String customAgentMacosProfile(File writeRoot, String home, List<Path> hidden,
                                                 boolean denyNetwork, List<String> homeReads, String ownTemp) {
        var sockets = denyNetwork ? "" : "(deny network-outbound (remote unix-socket))\n"
                + socketAllow("subpath", writeRoot.getAbsolutePath())
                + socketAllow("subpath", ownTemp)
                + socketAllow("path-literal", MAC_MDNS_SOCKET);
        return macosProfile(writeRoot, List.of(), home, hidden, denyNetwork, homeReads)
                + sockets + sharedTempStaysInPlace(writeRoot.getAbsolutePath(), ownTemp)
                + MAC_DENY_LAUNCH_SERVICES + MAC_DENY_SIGNALS_OUTSIDE + MAC_DENY_JVM_ATTACH;
    }

    private static String socketAllow(String filter, String path) {
        return "(allow network-outbound (remote unix-socket (" + filter + " \"" + escapeSeatbelt(path) + "\")))\n";
    }

    /**
     * The socket rules go by path, and the run may write to the shared temp folders: a socket there, or a
     * folder holding one, renamed under the write root or {@code ownTemp} would take the run's connection at
     * its new path. So the run renames no socket and no folder in them. Seatbelt has one operation for rename
     * and removal, so it removes none there either; a file it still renames and removes, and whatever is
     * inside the write root and {@code ownTemp} is its own. {@code ownTemp} itself stays, so the run puts
     * nothing else at the name its next profile opens. A hard link needs no rule: under this profile Seatbelt
     * already refuses one from a shared temp folder into either of the two (measured).
     */
    private static String sharedTempStaysInPlace(String writeRoot, String ownTemp) {
        var sb = new StringBuilder("(deny file-write-unlink (require-all (vnode-type DIRECTORY SOCKET) (require-any");
        for (var temp : MAC_SHARED_TEMP) {
            sb.append(' ').append(subpath(temp));
        }
        return sb.append(") (require-not ").append(subpath(writeRoot)).append(") (require-not ").append(subpath(ownTemp))
                .append(")))\n(deny file-write-unlink (literal \"").append(escapeSeatbelt(ownTemp)).append("\"))\n")
                .toString();
    }

    private static void appendHiddenHome(StringBuilder sb, String workspace, String home, List<String> homeReads) {
        var homePath = SandboxHiddenRoots.lexical(home);
        if (homePath == null) throw new SandboxUnavailableException("sandbox: the home folder is not a valid path: " + home);
        sb.append(rule("deny", "file-read*", homePath.toString()));
        sb.append(rule("allow", "file-read*", workspace));
        for (var r : homeReads) {
            sb.append(rule("allow", "file-read*", r));
        }
        var metadata = new LinkedHashSet<String>();
        metadata.add(homePath.toString());
        var reopened = new ArrayList<String>();
        reopened.add(workspace);
        reopened.addAll(homeReads);
        for (var r : reopened) {
            var p = SandboxHiddenRoots.lexical(r);
            if (p == null || !strictlyBelow(p, homePath)) continue;
            var between = new ArrayList<String>();
            for (var dir = p.getParent(); dir != null && strictlyBelow(dir, homePath); dir = dir.getParent()) {
                between.addFirst(dir.toString());
            }
            metadata.addAll(between);
        }
        for (var dir : metadata) {
            sb.append("(allow file-read-metadata (literal \"").append(escapeSeatbelt(dir)).append("\"))\n");
        }
    }

    /**
     * The Linux argv for a run confined under {@code configKey}, public so a test can pin the mount
     * order and the network choice on any host. bwrap applies mounts in argument order and a tmpfs
     * over an ancestor hides every earlier bind beneath it, so the {@code $HOME} tmpfs must precede
     * the write-root bind: the workspace lives under {@code $HOME} on every non-container install.
     */
    public static List<String> linuxArgv(List<String> argv, File writeRoot, List<String> allowances, String configKey) {
        return linuxArgv(argv, writeRoot, allowances, System.getProperty("user.home", ""),
                linuxHiddenRoots(DatabaseService.backupsDir(), SandboxHiddenRoots.current()), deniesNetwork(configKey));
    }

    /**
     * The {@code hidden} roots a tmpfs can cover. bwrap cannot create a mount point under the
     * read-only root, so a missing root hides nothing; {@code backups} is JClaw's own and is
     * created first, or a backup made after a long-running harness started would be readable to it.
     */
    public static List<Path> linuxHiddenRoots(Path backups, List<Path> hidden) {
        try {
            Files.createDirectories(backups);
        } catch (IOException e) {
            EventLogger.warn("tool", "Sandbox: could not create the backup directory " + backups
                    + " to hide it from confined processes", e.toString());
        }
        return hidden.stream().filter(Files::isDirectory).toList();
    }

    /**
     * {@link #linuxArgv(List, File, List, String)} with the home, hidden roots and network choice given. Each
     * {@code hidden} root gets a tmpfs after the grants outside it, so an ancestor write root
     * cannot re-expose it, and before the grants beneath it. Every grant is bound at its
     * canonical path, where the tmpfs mounts go, so one reached through a symlink cannot show a
     * hidden root at a path its tmpfs does not cover.
     */
    public static List<String> linuxArgv(List<String> argv, File writeRoot, List<String> allowances,
                                         String home, List<Path> hidden, boolean denyNetwork) {
        return linuxArgv(argv, writeRoot, allowances, home, hidden, denyNetwork, RUNS_AS_ROOT);
    }

    /**
     * {@link #linuxArgv(List, File, List, String, List, boolean)} with whether the process runs as root given:
     * a root bwrap gets {@code --unshare-user}, see {@link #BWRAP_PROBE_AS_ROOT}.
     */
    public static List<String> linuxArgv(List<String> argv, File writeRoot, List<String> allowances,
                                         String home, List<Path> hidden, boolean denyNetwork, boolean asRoot) {
        return linuxArgv(argv, writeRoot, allowances, home, hidden, denyNetwork, asRoot, List.of());
    }

    /**
     * {@link #linuxArgv(List, File, List, String, List, boolean, boolean)} with {@code ownRuntime}, the
     * {@link #linuxOwnRuntime} mounts of a custom agent's run. They go before the {@code $HOME} tmpfs, so
     * every grant still lands on top of them.
     */
    @SuppressWarnings("java:S107") // one sandbox's whole shape, each part resolved by the caller
    public static List<String> linuxArgv(List<String> argv, File writeRoot, List<String> allowances, String home,
                                         List<Path> hidden, boolean denyNetwork, boolean asRoot, List<String> ownRuntime) {
        return linuxArgv(argv, writeRoot, allowances, home, hidden, denyNetwork, asRoot, ownRuntime, LinuxPids.SHARED);
    }

    /**
     * {@link #linuxArgv(List, File, List, String, List, boolean, boolean, List)} with the run's PID namespace
     * given. A run in a namespace of its own is in a session of its own too. {@code --proc /proc} is the
     * namespace's own procfs under {@code --unshare-pid}, and is left out for
     * {@link LinuxPids#OWN_UNDER_OUTER_PROC}, where bwrap could not mount one and would start nothing.
     */
    @SuppressWarnings("java:S107") // one sandbox's whole shape, each part resolved by the caller
    public static List<String> linuxArgv(List<String> argv, File writeRoot, List<String> allowances, String home,
                                         List<Path> hidden, boolean denyNetwork, boolean asRoot, List<String> ownRuntime,
                                         LinuxPids pids) {
        var out = new ArrayList<String>(List.of(BWRAP));
        if (asRoot) out.addAll(ROOT_FLAGS);
        if (denyNetwork) out.add("--unshare-net");
        if (pids != LinuxPids.SHARED) out.addAll(OWN_PIDS_AND_SESSION);
        out.addAll(List.of(
                "--ro-bind", "/", "/",
                "--dev", "/dev",
                "--tmpfs", "/tmp"));
        if (pids != LinuxPids.OWN_UNDER_OUTER_PROC) out.addAll(List.of("--proc", "/proc"));
        out.addAll(ownRuntime);
        // an empty HOME by default; the write root and the harness's own state
        // paths are bound back below (secrets not listed stay absent).
        out.addAll(List.of("--tmpfs", home));
        var reopened = new ArrayList<String>();
        // rebind the write root read-write over the read-only root
        addBind(out, reopened, "--bind", writeRoot.getAbsolutePath(), hidden);
        for (var a : allowances) {
            addBind(out, reopened, "--ro-bind-try", absHome(home, a), hidden);
        }
        for (var root : hidden) {
            out.add("--tmpfs");
            out.add(root.toString());
        }
        out.addAll(reopened);
        out.addAll(argv);
        return List.copyOf(out);
    }

    /**
     * The mounts that give a custom agent's run runtime folders of its own, public so a test can pin them on
     * any host: an empty tmpfs over each of {@code runtimeDirs}, where the services outside the sandbox keep
     * their control sockets, then read-only what its commands still need from under them. That is the file
     * {@code /etc/resolv.conf} points at ({@code resolvConf}: systemd-resolved and NetworkManager keep it
     * there, and without it no name resolves) and each {@code pathEnv} folder (NixOS keeps its programs
     * there). A file is bound, never its folder, so no socket beside it comes back.
     */
    public static List<String> linuxOwnRuntime(List<String> runtimeDirs, @Nullable String resolvConf,
                                               @Nullable String pathEnv) {
        var out = new ArrayList<String>();
        var dirs = new ArrayList<Path>();
        for (var dir : runtimeDirs) {
            var path = SandboxHiddenRoots.lexical(dir);
            if (path == null) continue;
            out.addAll(List.of("--tmpfs", dir));
            dirs.add(path);
        }
        var needed = new ArrayList<String>();
        if (resolvConf != null) needed.add(resolvConf);
        if (pathEnv != null) needed.addAll(List.of(pathEnv.split(File.pathSeparator)));
        var kept = new LinkedHashSet<String>();
        for (var entry : needed) {
            var p = SandboxHiddenRoots.lexical(entry);
            if (entry.isEmpty() || p == null || !p.isAbsolute()) continue;
            if (dirs.stream().anyMatch(dir -> strictlyBelow(p, dir))) kept.add(p.toString());
        }
        for (var path : kept) {
            out.addAll(List.of("--ro-bind-try", path, path));
        }
        return List.copyOf(out);
    }

    /** {@link #LINUX_RUNTIME_DIRS} as this host has them: each real directory once, {@code /var/run} being {@code /run} on most. */
    private static List<String> linuxRuntimeDirs() {
        var out = new LinkedHashSet<String>();
        for (var dir : LINUX_RUNTIME_DIRS) {
            var real = realPath(dir);
            if (real != null && Files.isDirectory(real)) out.add(real.toString());
        }
        return List.copyOf(out);
    }

    /**
     * {@code bwrapArgv} asking for a user namespace of its own, once, whoever runs it. A setuid bwrap makes
     * none unless asked, and without one the sandbox reaches {@code /proc/<pid>/cwd} and
     * {@code /proc/<pid>/root} of the user's other processes: where a JVM looks for its attach trigger file
     * and binds its attach socket, which the sandbox's read-only root and own {@code /tmp} otherwise hide.
     */
    public static List<String> inOwnUserNamespace(List<String> bwrapArgv) {
        if (bwrapArgv.get(1).equals(UNSHARE_USER)) return bwrapArgv;
        var out = new ArrayList<>(bwrapArgv);
        out.add(1, UNSHARE_USER);
        return List.copyOf(out);
    }

    private static void addBind(List<String> out, List<String> reopened, String flag, String path,
                                List<Path> hidden) {
        var canonical = SandboxHiddenRoots.canonical(path).toString();
        var after = SandboxHiddenRoots.beneath(path, hidden) != null;
        (after ? reopened : out).addAll(List.of(flag, canonical, canonical));
    }

    private static String writeAllow(String path) {
        return rule("allow", "file-write*", path);
    }

    private static String rule(String action, String operations, String path) {
        return "(" + action + " " + operations + " " + subpath(path) + ")\n";
    }

    private static String subpath(String path) {
        return "(subpath \"" + escapeSeatbelt(path) + "\")";
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
