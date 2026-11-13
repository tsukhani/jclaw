import agents.SkillLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import play.Play;
import play.test.UnitTest;
import services.AgentService;
import services.CodingRunFolders;
import services.ConfigService;
import services.EventLogger;
import services.KeptSkillFiles;
import services.WorkspaceFiles;
import services.database.DatabaseService;
import tools.ClaudeAdapter;
import tools.GenericAdapter;
import tools.HarnessSandbox;
import tools.PiAdapter;
import utils.BuiltInSkills;
import utils.SandboxHiddenRoots;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;


/**
 * JCLAW-672: the opt-in coding-harness sandbox — disabled passthrough,
 * fail-closed on an unavailable mechanism, adapter allowance composition,
 * and (on macOS, with sandbox-exec present) a real confined run: writes land
 * only inside the session directory, and a write outside it is blocked.
 */
class HarnessSandboxTest extends UnitTest {

    private Path session;

    @BeforeEach
    void setup() throws Exception {
        ConfigService.clearCache();
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "");
        session = Files.createTempDirectory("jclaw-sbx-session-");
    }

    @AfterEach
    void teardown() throws Exception {
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "");
        if (session != null) {
            try (var walk = Files.walk(session)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void disabledIsPassthrough() {
        var argv = List.of("claude", "-p");
        assertEquals(argv, HarnessSandbox.wrap(argv, session.toFile(), new ClaudeAdapter()));
    }

    @Test
    void claudeAllowancesAreItsOwnStateOnly() {
        assertEquals(List.of(".claude", ".claude.json"), new ClaudeAdapter().sandboxAllowances());
        assertTrue(new GenericAdapter().sandboxAllowances().isEmpty(),
                "the generic harness declares no HOME allowances");
        assertEquals(List.of(".pi", ".config/pi"), new PiAdapter().sandboxAllowances());
    }

    @Test
    void enabledButNoWorkdirFailsClosed() {
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
        var e = assertThrows(HarnessSandbox.SandboxUnavailableException.class,
                () -> HarnessSandbox.wrap(List.of("claude"), null, new ClaudeAdapter()));
        assertTrue(e.getMessage().contains("session working directory"), e.getMessage());
    }

    // JCLAW-709: the tri-state config parses to the right Scope, back-compat intact.
    @Test
    void scopeParsesTriState() {
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "");
        assertEquals(HarnessSandbox.Scope.OFF, HarnessSandbox.scope());
        assertFalse(HarnessSandbox.enabled());
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
        assertEquals(HarnessSandbox.Scope.ALL, HarnessSandbox.scope());
        assertTrue(HarnessSandbox.enabled());
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "untrusted");
        assertEquals(HarnessSandbox.Scope.UNTRUSTED, HarnessSandbox.scope());
        // untrusted is NOT "confine everything", so enabled() (== ALL) stays false.
        assertFalse(HarnessSandbox.enabled());
    }

    // JCLAW-709: untrusted mode confines ONLY untrusted-origin runs. A trusted
    // origin passes through even with the mode on; an untrusted origin takes the
    // sandbox path (proven cross-platform by the null-workdir fail-closed guard).
    @Test
    void untrustedModeConfinesOnlyUntrustedOrigin() {
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "untrusted");
        var argv = List.of("claude", "-p");
        // trustedOrigin=true → not confined even though the mode is on.
        assertEquals(argv, HarnessSandbox.wrap(argv, session.toFile(), new ClaudeAdapter(), true));
        // trustedOrigin=false → the sandbox applies; a null workdir fails closed
        // (same guard as the always-on mode), proving the confine path is taken.
        var e = assertThrows(HarnessSandbox.SandboxUnavailableException.class,
                () -> HarnessSandbox.wrap(List.of("claude"), null, new ClaudeAdapter(), false));
        assertTrue(e.getMessage().contains("session working directory"), e.getMessage());
    }

    /**
     * On Linux, available means a real bwrap launch succeeds, not that bwrap is on PATH: a kernel without
     * unprivileged user namespaces, or Docker's default seccomp profile, lets bwrap start and then refuses it.
     */
    @Test
    void availabilityNamesThePlatformsMechanism() throws Exception {
        var a = HarnessSandbox.availability();
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) assertEquals("bwrap", a.mechanism());
        if (os.contains("mac")) assertEquals("sandbox-exec", a.mechanism());
        if (!a.available()) assertFalse(a.reason().isBlank(), "an unavailable mechanism must say why");
        if (os.contains("linux")) {
            assertEquals(bwrapLaunches(), a.available(), () -> "reported " + a);
        } else if (!a.mechanism().isEmpty()) {
            var onPath = Arrays.stream(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                    .anyMatch(dir -> !dir.isEmpty() && Files.isExecutable(Path.of(dir, a.mechanism())));
            assertEquals(onPath, a.available(), () -> a.mechanism() + " on PATH=" + onPath + ", reported " + a);
        }
    }

    private static boolean bwrapLaunches() throws Exception {
        return launches(List.of("bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc",
                "--tmpfs", "/tmp", "true"));
    }

    private static boolean launches(List<String> command) throws Exception {
        try {
            var proc = new ProcessBuilder(command).redirectErrorStream(true).start();
            proc.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            assertTrue(proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "the probe finished");
            return proc.exitValue() == 0;
        } catch (java.io.IOException _) {
            return false;
        }
    }

    /** A host whose bwrap cannot start says so with bwrap's own first line, so the operator sees the cause. */
    @Test
    void anUnavailableBwrapReportsItsOwnFirstLine() throws Exception {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        org.junit.jupiter.api.Assumptions.assumeTrue(os.contains("linux") && !bwrapLaunches(),
                "bwrap launches here, or this is not Linux");
        var proc = new ProcessBuilder("bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc",
                "--tmpfs", "/tmp", "true").redirectErrorStream(true).start();
        var first = new String(proc.getInputStream().readAllBytes()).strip().lines().findFirst().orElse("").strip();
        proc.waitFor();
        org.junit.jupiter.api.Assumptions.assumeFalse(first.isEmpty(), "bwrap printed nothing");
        var expected = first.length() > 200 ? first.substring(0, 200) : first;
        var suffix = "1".equals(System.getenv("JCLAW_CONTAINER"))
                ? "; in Docker, start the container with the seccomp profile docker-compose.yml names"
                        + " (seccomp-bwrap.json), as INSTALL.md describes"
                : "";
        assertEquals(new HarnessSandbox.Availability(false, "bwrap", "'bwrap' is not available: " + expected + suffix),
                HarnessSandbox.availability());
    }

    /**
     * On Linux a custom agent's run is linuxArgv in its own user and PID namespaces and its own session, under
     * shell.sandbox's network rule, with the key itself unset, plus runtime folders of its own before the $HOME
     * tmpfs. Its /proc is the PID namespace's own where this host mounts one, and the one the root bind brings
     * where it does not. It has a /tmp of its own already, so its TMPDIR is left alone.
     */
    @Test
    void linuxCustomAgentWrapIsTheShellArgvInItsOwnNamespacesAndSessionUnderTheNetworkRule() throws Exception {
        var a = HarnessSandbox.availability();
        org.junit.jupiter.api.Assumptions.assumeTrue(a.available() && a.mechanism().equals("bwrap"),
                "no working bwrap here: " + a);
        ShellSandboxSync.acquire();
        try {
            var argv = List.of("/bin/sh", "-c", "true");
            var root = session.toFile();
            for (var network : List.of("deny", "allow")) {
                ConfigService.set(HarnessSandbox.SHELL_NETWORK_KEY, network);
                var confined = HarnessSandbox.wrapCustomAgentShell(argv, root);
                var wrapped = confined.argv();
                assertEquals(Map.of(), confined.env(), "network=" + network);
                assertEquals("--unshare-user", wrapped.get(1), "network=" + network);
                assertEquals(network.equals("deny"), wrapped.contains("--unshare-net"), "network=" + network);
                int rootBind = wrapped.indexOf("--ro-bind");
                assertEquals(List.of("--unshare-pid", "--new-session"), wrapped.subList(rootBind - 2, rootBind),
                        "argv=" + wrapped);

                boolean ownProc = indexOfPair(wrapped, "--proc", "/proc") >= 0;
                assertEquals(launches(HarnessSandbox.pidNamespaceProbe(HarnessSandbox.LinuxPids.OWN,
                        wrapped.contains("--cap-drop"))), ownProc, "a procfs of its own exactly where one mounts, argv=" + wrapped);
                int afterMounts = indexOfPair(wrapped, "--tmpfs", "/tmp") + (ownProc ? 4 : 2);
                int homeTmpfs = indexOfPair(wrapped, "--tmpfs", System.getProperty("user.home"));
                assertEquals(List.of("--tmpfs", "/run"), wrapped.subList(afterMounts, afterMounts + 2), "argv=" + wrapped);
                var asMains = new ArrayList<>(wrapped);
                asMains.subList(afterMounts, homeTmpfs).clear();
                asMains.remove("--unshare-pid");
                asMains.remove("--new-session");
                if (!ownProc) asMains.addAll(indexOfPair(asMains, "--tmpfs", "/tmp") + 2, List.of("--proc", "/proc"));
                int writeBind = indexOfPair(asMains, "--bind", SandboxHiddenRoots.canonical(session).toString());
                int pinned = indexOfPair(asMains, "--bind-try", SandboxHiddenRoots.canonical(session).resolve("skills").toString());
                assertTrue(pinned > writeBind && writeBind >= 0, "skills/ is bound onto itself after the workspace, argv=" + wrapped);
                asMains.subList(pinned, pinned + 3).clear();
                for (var builtIn : HarnessSandbox.builtInSkillFolders(root)) {
                    int at = indexOfPair(asMains, "--ro-bind-try", builtIn);
                    assertTrue(at > writeBind && writeBind >= 0, "a built-in skill is bound read-only after the workspace, argv=" + wrapped);
                    asMains.subList(at, at + 3).clear();
                }
                for (var own : HarnessSandbox.customAgentHiddenRoots()) {
                    int at = SandboxHiddenRoots.current().contains(own) ? -1 : indexOfPair(asMains, "--tmpfs", own.toString());
                    if (at >= 0) asMains.subList(at, at + 2).clear();
                }
                assertEquals(HarnessSandbox.inOwnUserNamespace(
                        HarnessSandbox.linuxArgv(argv, root, List.of(), HarnessSandbox.SHELL_SANDBOX_KEY)), asMains,
                        "network=" + network);
            }
        } finally {
            ShellSandboxSync.release();
        }
    }

    /** main's profile, byte for byte as before JF-165: the home folder stays readable. */
    @Test
    void macProfileForMainIsUnchanged() {
        var profile = HarnessSandbox.macosProfile(new File("/h/u/jc/workspace/a"), List.of(".claude"), "/h/u",
                List.of(Path.of("/h/u/jc/data"), Path.of("/h/u/jc/certs")), false);
        assertEquals("""
                (version 1)
                (allow default)
                (deny file-write* (subpath "/"))
                (allow file-write* (subpath "/h/u/jc/workspace/a"))
                (allow file-write* (subpath "/private/tmp"))
                (allow file-write* (subpath "/private/var/folders"))
                (allow file-write* (subpath "/dev"))
                (allow file-write* (subpath "/h/u/.claude"))
                (deny file-read* file-write* (subpath "/h/u/jc/data"))
                (deny file-read* file-write* (subpath "/h/u/jc/certs"))
                (deny file-read* (subpath "/h/u/.ssh"))
                (deny file-read* (subpath "/h/u/.aws"))
                (deny file-read* (subpath "/h/u/.gnupg"))
                (deny file-read* (subpath "/h/u/.config/gcloud"))
                (deny file-read* (subpath "/h/u/.kube"))
                (deny file-read* (subpath "/h/u/.netrc"))
                """, profile);
        assertEquals(profile, HarnessSandbox.macosProfile(new File("/h/u/jc/workspace/a"), List.of(".claude"), "/h/u",
                List.of(Path.of("/h/u/jc/data"), Path.of("/h/u/jc/certs")), false, null));
    }

    /**
     * A custom agent's profile denies $HOME after the write grants, reopens the workspace and the PATH
     * directories, gives back the metadata between, and only then hides JClaw's state and the $HOME secrets:
     * a reopen above data/ or above .config/gcloud reopens neither.
     */
    @Test
    void macProfileForACustomAgentHidesTheHomeFolder() {
        var profile = HarnessSandbox.macosProfile(new File("/h/u/jc/workspace/a"), List.of(), "/h/u",
                List.of(Path.of("/h/u/jc/data"), Path.of("/h/u/jc/certs")), false,
                List.of("/h/u/.local/bin", "/h/u/jc", "/h/u/.config"));
        assertEquals("""
                (version 1)
                (allow default)
                (deny file-write* (subpath "/"))
                (allow file-write* (subpath "/h/u/jc/workspace/a"))
                (allow file-write* (subpath "/private/tmp"))
                (allow file-write* (subpath "/private/var/folders"))
                (allow file-write* (subpath "/dev"))
                (deny file-read* (subpath "/h/u"))
                (allow file-read* (subpath "/h/u/jc/workspace/a"))
                (allow file-read* (subpath "/h/u/.local/bin"))
                (allow file-read* (subpath "/h/u/jc"))
                (allow file-read* (subpath "/h/u/.config"))
                (allow file-read-metadata (literal "/h/u"))
                (allow file-read-metadata (literal "/h/u/jc"))
                (allow file-read-metadata (literal "/h/u/jc/workspace"))
                (allow file-read-metadata (literal "/h/u/.local"))
                (deny file-read* file-write* (subpath "/h/u/jc/data"))
                (deny file-read* file-write* (subpath "/h/u/jc/certs"))
                (deny file-read* (subpath "/h/u/.ssh"))
                (deny file-read* (subpath "/h/u/.aws"))
                (deny file-read* (subpath "/h/u/.gnupg"))
                (deny file-read* (subpath "/h/u/.config/gcloud"))
                (deny file-read* (subpath "/h/u/.kube"))
                (deny file-read* (subpath "/h/u/.netrc"))
                """, profile);
    }

    /** Beneath a hidden root, a custom agent's profile reopens its write root and nothing else. */
    @Test
    void macProfileForACustomAgentReopensOnlyTheWriteRootBeneathAHiddenRoot() {
        var profile = HarnessSandbox.macosProfile(new File("/h/u/jc/data/run"), List.of("/h/u/jc/data/allow"), "/h/u",
                List.of(Path.of("/h/u/jc/data")), false, List.of());
        var deny = profile.indexOf("(deny file-read* file-write* (subpath \"/h/u/jc/data\"))\n");
        var reopen = profile.indexOf("(allow file-read* file-write* (subpath \"/h/u/jc/data/run\"))\n");
        assertTrue(deny >= 0 && reopen > deny, profile);
        assertFalse(profile.contains("(allow file-read* file-write* (subpath \"/h/u/jc/data/allow\"))"), profile);
    }

    /**
     * JF-391: a custom agent's wrap hides every workspace and the shared skills folder after its write grants, then
     * reopens its own workspace, so an install outside the home folder shows it nothing of another agent's.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void macACustomAgentsWrapHidesEveryWorkspaceAndTheSharedSkillsFolder() throws Exception {
        var writeRoot = WorkspaceFiles.workspaceRoot().resolve("jf391-" + java.util.UUID.randomUUID()).toFile();
        var temp = HarnessSandbox.customAgentTempDir(writeRoot);
        ShellSandboxSync.acquire();
        try {
            var profile = HarnessSandbox.wrapCustomAgentShell(List.of("true"), writeRoot).argv().get(2);
            var workspaces = SandboxHiddenRoots.canonical(WorkspaceFiles.workspaceRoot());
            var skills = SandboxHiddenRoots.canonical(SkillLoader.globalSkillsPath());
            var own = SandboxHiddenRoots.canonical(writeRoot.toPath());
            int hideWorkspaces = profile.indexOf("(deny file-read* file-write* (subpath \"" + workspaces + "\"))\n");
            int hideSkills = profile.indexOf("(deny file-read* file-write* (subpath \"" + skills + "\"))\n");
            int reopenOwn = profile.indexOf("(allow file-read* file-write* (subpath \"" + own + "\"))\n");
            assertTrue(hideWorkspaces >= 0, "every workspace is hidden: " + profile);
            assertTrue(hideSkills >= 0, "the skills folder is hidden: " + profile);
            assertTrue(reopenOwn > hideWorkspaces && reopenOwn > hideSkills, "its own workspace comes back after: " + profile);
        } finally {
            Files.deleteIfExists(temp);
            ShellSandboxSync.release();
        }
    }

    /**
     * JF-391: the container image installs JClaw at /app with the home folder at /root, so hiding the home folder
     * hides nothing of JClaw's. A custom agent's argv covers every workspace and the skills folder, and binds its own
     * workspace back after them, since a later mount hides an earlier one beneath it.
     */
    @Test
    void linuxCustomAgentArgvOutsideTheHomeFolderHidesTheOtherWorkspacesAndTheSkillsFolder() {
        var state = List.of(Path.of("/app/data"), Path.of("/app/certs"), Path.of("/app/conf"), Path.of("/app/logs"));
        var hidden = HarnessSandbox.customAgentHiddenRoots(state, Path.of("/app/workspace"), Path.of("/app/skills"));
        assertEquals(List.of(Path.of("/app/data"), Path.of("/app/certs"), Path.of("/app/conf"), Path.of("/app/logs"),
                Path.of("/app/workspace"), Path.of("/app/skills")), hidden);
        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--unshare-pid", "--new-session",
                        "--ro-bind", "/", "/", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/root",
                        "--tmpfs", "/app/data", "--tmpfs", "/app/certs", "--tmpfs", "/app/conf", "--tmpfs", "/app/logs",
                        "--tmpfs", "/app/workspace", "--tmpfs", "/app/skills",
                        "--bind", "/app/workspace/agent-x", "/app/workspace/agent-x", "/bin/sh", "-c", "true"),
                HarnessSandbox.linuxArgv(List.of("/bin/sh", "-c", "true"), new File("/app/workspace/agent-x"), List.of(),
                        "/root", hidden, false, true, List.of(), HarnessSandbox.LinuxPids.OWN_UNDER_OUTER_PROC));
    }

    /**
     * JF-392: a custom agent's built-in skill folders are bound read-only after its workspace's read-write bind, with
     * skills/ bound onto itself just before them, so the run can neither write them nor move skills/ aside.
     */
    @Test
    void linuxCustomAgentArgvBindsTheBuiltInSkillsReadOnlyAfterItsWorkspace() {
        var writeRoot = new File("/app/workspace/agent-x");
        var hidden = HarnessSandbox.customAgentHiddenRoots(List.of(Path.of("/app/data")), Path.of("/app/workspace"),
                Path.of("/app/skills"));
        var builtIns = HarnessSandbox.builtInSkillFolders(writeRoot);
        assertEquals(List.of("/app/workspace/agent-x/skills/skill-creator", "/app/workspace/agent-x/skills/jclaw-api",
                "/app/workspace/agent-x/skills/task-creator"), builtIns);
        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--unshare-pid", "--new-session",
                        "--ro-bind", "/", "/", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/root",
                        "--tmpfs", "/app/data", "--tmpfs", "/app/workspace", "--tmpfs", "/app/skills",
                        "--bind", "/app/workspace/agent-x", "/app/workspace/agent-x",
                        "--bind-try", "/app/workspace/agent-x/skills", "/app/workspace/agent-x/skills",
                        "--ro-bind-try", builtIns.get(0), builtIns.get(0), "--ro-bind-try", builtIns.get(1), builtIns.get(1),
                        "--ro-bind-try", builtIns.get(2), builtIns.get(2), "/bin/sh", "-c", "true"),
                HarnessSandbox.pinSkillsFolder(HarnessSandbox.linuxArgv(List.of("/bin/sh", "-c", "true"), writeRoot,
                        builtIns, "/root", hidden, false, true, List.of(), HarnessSandbox.LinuxPids.OWN_UNDER_OUTER_PROC),
                        writeRoot));
    }

    /** JF-392: a custom agent's wrap denies every built-in skill folder after it reopens its own workspace. */
    @Test
    @EnabledOnOs(OS.MAC)
    void macACustomAgentsWrapKeepsItsBuiltInSkillsReadOnly() throws Exception {
        var writeRoot = WorkspaceFiles.workspaceRoot().resolve("jf392-" + java.util.UUID.randomUUID()).toFile();
        var temp = HarnessSandbox.customAgentTempDir(writeRoot);
        ShellSandboxSync.acquire();
        try {
            var profile = HarnessSandbox.wrapCustomAgentShell(List.of("true"), writeRoot).argv().get(2);
            var root = SandboxHiddenRoots.canonical(writeRoot.toPath());
            int reopen = profile.indexOf("(allow file-read* file-write* (subpath \"" + root + "\"))\n");
            assertTrue(reopen >= 0, "its own workspace is reopened: " + profile);
            for (var name : BuiltInSkills.NAMES) {
                var deny = "(deny file-write* file-link (subpath \"" + root.resolve("skills").resolve(name) + "\"))\n";
                assertTrue(profile.indexOf(deny) > reopen, name + " is denied after the reopen: " + profile);
            }
            assertTrue(profile.contains("(deny file-write-unlink (literal \"" + root.resolve("skills") + "\"))\n"), profile);
            assertTrue(profile.contains("(deny file-write-unlink (literal \"" + root + "\"))\n"), profile);
        } finally {
            Files.deleteIfExists(temp);
            ShellSandboxSync.release();
        }
    }

    /**
     * JF-392, measured: a custom agent's shell reads its copy of a built-in skill but changes it by no route tried here
     * (a write, a removal, a new file, a case variant, moving the folder, skills/ or the whole workspace, a hard or a
     * symbolic link), while copying it out and writing a skill of its own still work.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void macACustomAgentCannotChangeItsBuiltInSkillByAnyRouteTried() throws Exception {
        var base = SandboxHiddenRoots.canonical(Files.createTempDirectory("jf392-install-"));
        try {
            var own = Files.createDirectories(base.resolve("workspace/agent-x"));
            var builtIn = Files.createDirectories(own.resolve("skills/task-creator"));
            Files.createDirectories(own.resolve("skills/my-skill"));
            var ownTemp = Files.createDirectories(base.resolve("own-temp"));
            Files.writeString(builtIn.resolve("SKILL.md"), "shipped\n");
            var hidden = HarnessSandbox.customAgentHiddenRoots(SandboxHiddenRoots.current(), base.resolve("workspace"),
                    base.resolve("skills"));
            var profile = HarnessSandbox.customAgentMacosProfile(own.toFile(), System.getProperty("user.home"), hidden,
                    false, List.of(), ownTemp.toString());
            var script = String.join("\n",
                    "cat skills/task-creator/SKILL.md",
                    "{ echo x >> skills/task-creator/SKILL.md; } 2>/dev/null || echo NO-APPEND",
                    "rm skills/task-creator/SKILL.md 2>/dev/null || echo NO-REMOVE",
                    "touch skills/task-creator/new.md 2>/dev/null || echo NO-NEW-FILE",
                    "{ echo x >> skills/Task-Creator/SKILL.md; } 2>/dev/null || echo NO-CASE-VARIANT",
                    "mv skills/task-creator moved 2>/dev/null || echo NO-MOVE-FOLDER",
                    "mv skills skills-moved 2>/dev/null || echo NO-MOVE-SKILLS",
                    "mv '%s' '%s' 2>/dev/null || echo NO-MOVE-WORKSPACE".formatted(own, base.resolve("away")),
                    "ln skills/task-creator/SKILL.md hard.md 2>/dev/null || echo NO-HARD-LINK",
                    "ln -s skills/task-creator/SKILL.md soft.md && { { echo x >> soft.md; } 2>/dev/null || echo NO-WRITE-VIA-SYMLINK; }",
                    "cp skills/task-creator/SKILL.md copy.md && echo COPIED",
                    "echo mine > skills/my-skill/SKILL.md && cat skills/my-skill/SKILL.md");
            var proc = new ProcessBuilder("sandbox-exec", "-p", profile, "/bin/sh", "-c", script)
                    .directory(own.toFile()).redirectErrorStream(true).start();
            var out = new String(proc.getInputStream().readAllBytes());
            assertTrue(proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "the run finished");
            assertEquals("""
                    shipped
                    NO-APPEND
                    NO-REMOVE
                    NO-NEW-FILE
                    NO-CASE-VARIANT
                    NO-MOVE-FOLDER
                    NO-MOVE-SKILLS
                    NO-MOVE-WORKSPACE
                    NO-HARD-LINK
                    NO-WRITE-VIA-SYMLINK
                    COPIED
                    mine
                    """, out);
            assertEquals("shipped\n", Files.readString(builtIn.resolve("SKILL.md")));
        } finally {
            deleteTree(base);
        }
    }

    /**
     * JF-391, measured: with JClaw outside the home folder, a custom agent can neither read nor write another agent's
     * workspace or the skills folder, while its own workspace stays readable, writable and resolvable by path.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void macACustomAgentOutsideTheHomeFolderReachesOnlyItsOwnWorkspace() throws Exception {
        var base = SandboxHiddenRoots.canonical(Files.createTempDirectory("jf391-install-"));
        try {
            var own = Files.createDirectories(base.resolve("workspace/agent-x"));
            var other = Files.createDirectories(base.resolve("workspace/main"));
            var skill = Files.createDirectories(base.resolve("skills/probe-skill"));
            var ownTemp = Files.createDirectories(base.resolve("own-temp"));
            Files.writeString(own.resolve("mine.txt"), "mine\n");
            Files.writeString(other.resolve("marker.txt"), "theirs\n");
            Files.writeString(skill.resolve("SKILL.md"), "skill\n");
            var hidden = HarnessSandbox.customAgentHiddenRoots(SandboxHiddenRoots.current(), base.resolve("workspace"),
                    base.resolve("skills"));
            var profile = HarnessSandbox.customAgentMacosProfile(own.toFile(), System.getProperty("user.home"), hidden,
                    false, List.of(), ownTemp.toString());
            var script = String.join("\n",
                    "cat '%s' 2>/dev/null || echo NO-READ-OTHER".formatted(other.resolve("marker.txt")),
                    "ls '%s' >/dev/null 2>&1 || echo NO-LIST-WORKSPACES".formatted(base.resolve("workspace")),
                    "cat '%s' 2>/dev/null || echo NO-READ-SKILLS".formatted(skill.resolve("SKILL.md")),
                    "touch '%s' 2>/dev/null || echo NO-WRITE-OTHER".formatted(other.resolve("written")),
                    "touch '%s' 2>/dev/null || echo NO-WRITE-SKILLS".formatted(skill.resolve("written")),
                    "cat mine.txt",
                    "touch own.txt && echo WROTE-OWN",
                    "[ \"$(pwd -P)\" = '%s' ] && echo PWD-RESOLVES".formatted(own),
                    "[ \"$(realpath own.txt)\" = '%s' ] && echo REALPATH-RESOLVES".formatted(own.resolve("own.txt")));
            var proc = new ProcessBuilder("sandbox-exec", "-p", profile, "/bin/sh", "-c", script)
                    .directory(own.toFile()).redirectErrorStream(true).start();
            var out = new String(proc.getInputStream().readAllBytes());
            assertTrue(proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "the run finished");
            assertEquals("""
                    NO-READ-OTHER
                    NO-LIST-WORKSPACES
                    NO-READ-SKILLS
                    NO-WRITE-OTHER
                    NO-WRITE-SKILLS
                    mine
                    WROTE-OWN
                    PWD-RESOLVES
                    REALPATH-RESOLVES
                    """, out);
        } finally {
            deleteTree(base);
        }
    }

    /**
     * A custom agent's profile is the hidden-home profile, then the local services outside it refused, then two
     * rules last so that no grant outranks them: a signal only to its own sandbox, and a JVM's attach socket
     * neither connected to nor renamed, removed or linked. With the network it connects to no Unix socket but
     * those in its workspace and its own temp folder, and mDNSResponder's, which resolves names. No socket and
     * no folder of the shared temp folders is renamed into those two, and its own temp folder stays at its name.
     */
    @Test
    void macProfileForACustomAgentRefusesLocalServicesAndEndsWithTheSignalAndJvmAttachDenies() {
        var root = new File("/h/u/jc/workspace/a");
        var hidden = List.of(Path.of("/h/u/jc/data"));
        var homeReads = List.of("/h/u/.local/bin");
        var ownTemp = "/private/var/folders/x/T/jclaw-0a1b2c3d";
        assertEquals(HarnessSandbox.macosProfile(root, List.of(), "/h/u", hidden, false, homeReads) + """
                (deny network-outbound (remote unix-socket))
                (allow network-outbound (remote unix-socket (subpath "/h/u/jc/workspace/a")))
                (allow network-outbound (remote unix-socket (subpath "/private/var/folders/x/T/jclaw-0a1b2c3d")))
                (allow network-outbound (remote unix-socket (path-literal "/private/var/run/mDNSResponder")))
                (deny file-write-unlink (require-all (vnode-type DIRECTORY SOCKET) (require-any (subpath "/private/tmp") (subpath "/private/var/folders")) (require-not (subpath "/h/u/jc/workspace/a")) (require-not (subpath "/private/var/folders/x/T/jclaw-0a1b2c3d"))))
                (deny file-write-unlink (literal "/private/var/folders/x/T/jclaw-0a1b2c3d"))
                (deny file-write* file-link (subpath "/h/u/jc/workspace/a/skills/skill-creator"))
                (deny file-write* file-link (subpath "/h/u/jc/workspace/a/skills/jclaw-api"))
                (deny file-write* file-link (subpath "/h/u/jc/workspace/a/skills/task-creator"))
                (deny file-write-unlink (literal "/h/u/jc/workspace/a/skills"))
                (deny file-write-unlink (literal "/h/u/jc/workspace/a"))
                (deny lsopen)
                (deny signal (require-not (target same-sandbox)))
                (deny network-outbound file-write* file-link (regex #"/\\.java_pid[^/]*$"))
                """, HarnessSandbox.customAgentMacosProfile(root, "/h/u", hidden, false, homeReads, ownTemp));
    }

    /**
     * Under network=deny the one network rule refuses every socket, a Unix one included. The socket rules are
     * left out, since an allow after that deny would reopen the workspace's sockets and mDNSResponder's. The
     * rule that keeps sockets and folders in the shared temp folders stays: one moved now would be waiting in
     * the run's own folders when the network is allowed again.
     */
    @Test
    void macProfileForACustomAgentUnderNetworkDenyReopensNoSocket() {
        var root = new File("/h/u/jc/workspace/a");
        var hidden = List.of(Path.of("/h/u/jc/data"));
        var homeReads = List.of("/h/u/.local/bin");
        var denied = HarnessSandbox.customAgentMacosProfile(root, "/h/u", hidden, true, homeReads, "/private/var/folders/x/T/jclaw-0a1b2c3d");
        assertEquals(HarnessSandbox.macosProfile(root, List.of(), "/h/u", hidden, true, homeReads) + """
                (deny file-write-unlink (require-all (vnode-type DIRECTORY SOCKET) (require-any (subpath "/private/tmp") (subpath "/private/var/folders")) (require-not (subpath "/h/u/jc/workspace/a")) (require-not (subpath "/private/var/folders/x/T/jclaw-0a1b2c3d"))))
                (deny file-write-unlink (literal "/private/var/folders/x/T/jclaw-0a1b2c3d"))
                (deny file-write* file-link (subpath "/h/u/jc/workspace/a/skills/skill-creator"))
                (deny file-write* file-link (subpath "/h/u/jc/workspace/a/skills/jclaw-api"))
                (deny file-write* file-link (subpath "/h/u/jc/workspace/a/skills/task-creator"))
                (deny file-write-unlink (literal "/h/u/jc/workspace/a/skills"))
                (deny file-write-unlink (literal "/h/u/jc/workspace/a"))
                (deny lsopen)
                (deny signal (require-not (target same-sandbox)))
                (deny network-outbound file-write* file-link (regex #"/\\.java_pid[^/]*$"))
                """, denied);
        assertTrue(denied.startsWith("(version 1)\n(allow default)\n(deny network*)\n"), denied);
    }

    /** A path with a quote in it stays inside its string in a socket rule, as it does in a file rule. */
    @Test
    void macSocketRulesEscapeTheirPaths() {
        var profile = HarnessSandbox.customAgentMacosProfile(new File("/h/u/w\"s"), "/h/u", List.of(), false, List.of(),
                "/private/t\"mp");
        assertTrue(profile.contains("(allow network-outbound (remote unix-socket (subpath \"/h/u/w\\\"s\")))\n"), profile);
        assertTrue(profile.contains("(allow network-outbound (remote unix-socket (subpath \"/private/t\\\"mp\")))\n"), profile);
        assertTrue(profile.contains("(require-not (subpath \"/h/u/w\\\"s\")) (require-not (subpath \"/private/t\\\"mp\"))))\n"
                + "(deny file-write-unlink (literal \"/private/t\\\"mp\"))\n"), profile);
    }

    /** One temp folder per write root, in this user's temp folder, under a name short enough to leave a socket path room. */
    @Test
    void aCustomAgentsTempFolderIsNamedAfterItsWriteRoot() {
        var temp = Path.of(System.getProperty("java.io.tmpdir"));
        var a = new File("/h/u/jc/workspace/a");
        assertEquals(temp.resolve("jclaw-%08x".formatted("/h/u/jc/workspace/a".hashCode())), HarnessSandbox.customAgentTempDir(a));
        assertEquals(HarnessSandbox.customAgentTempDir(a), HarnessSandbox.customAgentTempDir(new File("/h/u/jc/workspace/a")));
        assertNotEquals(HarnessSandbox.customAgentTempDir(a), HarnessSandbox.customAgentTempDir(new File("/h/u/jc/workspace/b")));
        assertEquals(14, HarnessSandbox.customAgentTempDir(a).getFileName().toString().length());
    }

    /**
     * The local-service, signal and JVM-attach rules reach a custom agent's run alone: main's key-confined exec and a
     * coding harness keep their profile. The custom run's TMPDIR is the folder its profile names, made by the wrap.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void macOnlyACustomAgentsRunCarriesTheLocalServiceSignalAndJvmAttachDenies() throws Exception {
        var temp = HarnessSandbox.customAgentTempDir(session.toFile());
        ShellSandboxSync.acquire();
        try {
            // Set right before the wraps: another class's table wipe can clear a key.
            ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
            ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
            var mainProfile = HarnessSandbox.wrap(List.of("true"), session.toFile(), List.of(),
                    HarnessSandbox.SHELL_SANDBOX_KEY, false).get(2);
            var harnessProfile = HarnessSandbox.wrap(List.of("true"), session.toFile(), new ClaudeAdapter(), false).get(2);
            var custom = HarnessSandbox.wrapCustomAgentShell(List.of("true"), session.toFile());
            var customProfile = custom.argv().get(2);
            // Read after the wrap: once any class in this JVM starts the secrets proxy its trust folder stays, and every
            // profile then ends with that folder's write deny.
            var trust = services.secrets.SecretProxy.trustFolder();
            var trustRule = trust == null ? ""
                    : "(deny file-write* (subpath \"%s\"))\n".formatted(SandboxHiddenRoots.canonical(trust));
            if (!trustRule.isEmpty() && customProfile.endsWith(trustRule)) {
                customProfile = customProfile.substring(0, customProfile.length() - trustRule.length());
            }

            for (var rule : List.of("(deny signal", ".java_pid", "unix-socket", "lsopen", "file-write-unlink")) {
                assertFalse(mainProfile.contains(rule), mainProfile);
                assertFalse(harnessProfile.contains(rule), harnessProfile);
            }
            assertEquals(Map.of("TMPDIR", temp.toString()), custom.env());
            assertTrue(Files.isDirectory(temp), "the wrap makes the folder: " + temp);
            assertTrue(customProfile.endsWith("""
                    (deny file-read* (subpath "%s/.netrc"))
                    (deny network-outbound (remote unix-socket))
                    (allow network-outbound (remote unix-socket (subpath "%s")))
                    (allow network-outbound (remote unix-socket (subpath "%s")))
                    (allow network-outbound (remote unix-socket (path-literal "/private/var/run/mDNSResponder")))
                    (deny file-write-unlink (require-all (vnode-type DIRECTORY SOCKET) (require-any (subpath "/private/tmp") (subpath "/private/var/folders")) (require-not (subpath "%2$s")) (require-not (subpath "%3$s"))))
                    (deny file-write-unlink (literal "%3$s"))
                    (deny file-write* file-link (subpath "%4$s/skills/skill-creator"))
                    (deny file-write* file-link (subpath "%4$s/skills/jclaw-api"))
                    (deny file-write* file-link (subpath "%4$s/skills/task-creator"))
                    (deny file-write-unlink (literal "%4$s/skills"))
                    (deny file-write-unlink (literal "%4$s"))
                    (deny lsopen)
                    (deny signal (require-not (target same-sandbox)))
                    (deny network-outbound file-write* file-link (regex #"/\\.java_pid[^/]*$"))
                    """.formatted(System.getProperty("user.home"), session.toFile().getAbsolutePath(), temp.toRealPath(),
                    SandboxHiddenRoots.canonical(session))),
                    customProfile);
        } finally {
            Files.deleteIfExists(temp);
            ShellSandboxSync.release();
        }
    }

    /** With the override in place of the sandbox, the argv comes back unwrapped and no variable is set. */
    @Test
    void theCustomAgentPassthroughOverrideSetsNoVariable() {
        ShellSandboxSync.acquireUnconfined();
        try {
            assertEquals(new HarnessSandbox.ConfinedShell(List.of("true"), Map.of()),
                    HarnessSandbox.wrapCustomAgentShell(List.of("true"), session.toFile()));
        } finally {
            ShellSandboxSync.release();
        }
    }

    /** Only absolute entries strictly below home on a segment boundary are reopened, once each. */
    @Test
    void homeReadReopensKeepsOnlyEntriesBelowHome() {
        assertEquals(List.of("/h/x/.local/bin"),
                HarnessSandbox.homeReadReopens("/h/x/.local/bin:/h/x:/h/xy/bin:bin::/h/x/.local/bin:/usr/bin", "/h/x"));
        assertEquals(List.of("/h/x/.local/bin"), HarnessSandbox.homeReadReopens("/h/x/:/h/x/.local/bin/", "/h/x/"));
        assertEquals(List.of(), HarnessSandbox.homeReadReopens("/h/x/../y/bin", "/h/x"), "normalized first");
        assertEquals(List.of(), HarnessSandbox.homeReadReopens("", "/h/x"));
        assertEquals(List.of(), HarnessSandbox.homeReadReopens(null, "/h/x"));
    }

    /** The operator's folders join PATH's under the same rule: strictly below home, once each, PATH first. */
    @Test
    void homeReadReopensTakesTheOperatorsFoldersAfterPathUnderTheSameRule() {
        assertEquals(List.of("/h/x/.local/bin", "/h/x/.local/share/uv"),
                HarnessSandbox.homeReadReopens("/h/x/.local/bin:/usr/bin", "/h/x",
                        List.of("/h/x/.local/share/uv", "/h/x", "/etc", "/h/x/.local/share/uv")));
        assertEquals(List.of("/h/x/tools"), HarnessSandbox.homeReadReopens(null, "/h/x", List.of("/h/x/tools")));
    }

    /** shell.sandbox.homeReads takes folders inside home and says why it refuses anything else. */
    @Test
    void theHomeReadsKeyTakesFoldersInsideHomeOnly() {
        var key = HarnessSandbox.HOME_READS_KEY;
        var home = System.getProperty("user.home");
        assertNull(HarnessSandbox.rejectionFor(key, ""));
        assertNull(HarnessSandbox.rejectionFor(key, "~/.local/share/uv, " + home + "/tools,"));
        assertEquals("shell.sandbox.homeReads: 'tools' is not an absolute folder: start it with / or ~/.",
                HarnessSandbox.rejectionFor(key, "tools"));
        assertEquals("shell.sandbox.homeReads: '/etc' is not a folder inside your home folder (" + home + ").",
                HarnessSandbox.rejectionFor(key, "~/ok, /etc"));
        assertEquals("shell.sandbox.homeReads: '~/../x' is not a folder inside your home folder (" + home + ").",
                HarnessSandbox.rejectionFor(key, "~/../x"));
        assertEquals("shell.sandbox.homeReads: '" + home + "' is not a folder inside your home folder (" + home + ").",
                HarnessSandbox.rejectionFor(key, home));
        assertEquals("shell.sandbox.homeReads: '~/.ssh/keys' is inside ~/.ssh, which sandboxed commands never read.",
                HarnessSandbox.rejectionFor(key, "~/.ssh/keys"));
        assertEquals("shell.sandbox.homeReads: '~/a\"b' has a quote, a backslash or a control character.",
                HarnessSandbox.rejectionFor(key, "~/a\"b"));
    }

    /** An entry through a symlink adds its canonical path when that is also below home, and only then. */
    @Test
    void homeReadReopensAddsACanonicalPathBelowHome() throws Exception {
        var base = SandboxHiddenRoots.canonical(session);
        var home = Files.createDirectories(base.resolve("home"));
        var real = Files.createDirectories(home.resolve("real/bin"));
        Files.createSymbolicLink(home.resolve("link"), real.getParent());
        var outside = Files.createDirectories(base.resolve("outside/bin"));
        Files.createSymbolicLink(home.resolve("away"), outside.getParent());

        assertEquals(List.of(home.resolve("link/bin").toString(), real.toString(), home.resolve("away/bin").toString()),
                HarnessSandbox.homeReadReopens(home.resolve("link/bin") + ":" + real + ":" + home.resolve("away/bin"),
                        home.toString()));
    }

    /** A script in a $HOME directory on PATH still runs under a custom agent's profile, which hides the rest of $HOME. */
    @Test
    @EnabledOnOs(OS.MAC)
    void macCustomAgentProfileRunsAScriptFromAHomePathEntry() throws Exception {
        var home = System.getProperty("user.home");
        var scratch = Files.createDirectories(Path.of(home, "jclaw-jf165-" + java.util.UUID.randomUUID()));
        try {
            var bin = Files.createDirectories(scratch.resolve("bin"));
            var script = Files.writeString(bin.resolve("probe.sh"), "#!/bin/sh\necho ran\n");
            assertTrue(script.toFile().setExecutable(true));
            var hiddenFile = Files.writeString(scratch.resolve("hidden.txt"), "hidden");
            var profile = HarnessSandbox.macosProfile(session.toFile(), List.of(), home, SandboxHiddenRoots.current(),
                    false, HarnessSandbox.homeReadReopens(bin + ":/usr/bin:/bin", home));
            var proc = new ProcessBuilder("sandbox-exec", "-p", profile, "/bin/sh", "-c",
                    "'%s' && { cat '%s' 2>/dev/null || echo HIDDEN; }".formatted(script, hiddenFile))
                    .directory(session.toFile()).redirectErrorStream(true).start();
            var out = new String(proc.getInputStream().readAllBytes());
            assertTrue(proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "the run finished");
            assertEquals(0, proc.exitValue(), out);
            assertEquals("ran\nHIDDEN\n", out);
        } finally {
            deleteTree(scratch);
        }
    }

    /**
     * bwrap applies mounts in argument order and a tmpfs over an ancestor hides every earlier
     * bind beneath it, so a write root under $HOME is reachable only if it is bound after the
     * $HOME tmpfs. Pinned on every host because no Linux test can run the real thing here.
     */
    /**
     * JF-387: a homeReads folder bound back after the $HOME tmpfs brings back every secret folder inside it, so each
     * such secret is hidden again after every bind, as macOS denies them last.
     */
    @Test
    void linuxHidesASecretFolderInsideAnAllowedHomeFolderAgain() throws Exception {
        var home = Files.createTempDirectory("jf387-home");
        try {
            var gcloud = Files.createDirectories(home.resolve(".config/gcloud"));
            var argv = HarnessSandbox.linuxArgv(List.of("/bin/sh", "-c", "true"), home.resolve("ws").toFile(),
                    List.of(home.resolve(".config").toString()), home.toString(), List.of(), false);

            int bind = indexOfPair(argv, "--ro-bind-try", SandboxHiddenRoots.canonical(home.resolve(".config")).toString());
            int mask = indexOfPair(argv, "--tmpfs", SandboxHiddenRoots.canonical(gcloud).toString());
            assertTrue(bind >= 0, "the allowed folder is bound, argv=" + argv);
            assertTrue(mask > bind, "the secret folder is hidden after the folder holding it, argv=" + argv);
            assertEquals(List.of("/bin/sh", "-c", "true"), argv.subList(argv.size() - 3, argv.size()));
        } finally {
            deleteTree(home);
        }
    }

    @Test
    void linuxCoversASecretFileInsideAnAllowedHomeFolderWithDevNull() throws Exception {
        var home = Files.createTempDirectory("jf387-home");
        try {
            var gcloud = home.resolve(".config/gcloud");
            Files.createDirectories(gcloud.getParent());
            Files.writeString(gcloud, "not a folder");
            var argv = HarnessSandbox.linuxArgv(List.of("/bin/sh", "-c", "true"), home.resolve("ws").toFile(),
                    List.of(home.resolve(".config").toString()), home.toString(), List.of(), false);

            var masked = SandboxHiddenRoots.canonical(gcloud).toString();
            int at = argv.indexOf(masked);
            assertTrue(at >= 2, "argv=" + argv);
            assertEquals(List.of("--ro-bind", "/dev/null", masked), argv.subList(at - 2, at + 1));
        } finally {
            deleteTree(home);
        }
    }

    @Test
    void linuxAddsNoMountForASecretThatDoesNotExist() throws Exception {
        var home = Files.createTempDirectory("jf387-home");
        try {
            Files.createDirectories(home.resolve(".config"));
            var withSecretAbsent = HarnessSandbox.linuxArgv(List.of("/bin/sh", "-c", "true"), home.resolve("ws").toFile(),
                    List.of(home.resolve(".config").toString()), home.toString(), List.of(), false);

            assertFalse(withSecretAbsent.contains(SandboxHiddenRoots.canonical(home.resolve(".config/gcloud")).toString()),
                    "nothing to hide, and a mount point bwrap cannot create would stop the run: " + withSecretAbsent);
        } finally {
            deleteTree(home);
        }
    }

    @Test
    void linuxBindsTheWriteRootAfterTheHomeTmpfs() {
        var home = System.getProperty("user.home");
        var writeRoot = Path.of(home, "workspace", "agent").toFile();
        var argv = HarnessSandbox.linuxArgv(List.of("/bin/sh", "-c", "true"), writeRoot, List.of("/opt/state"),
                HarnessSandbox.ACP_SANDBOX_KEY);

        int homeTmpfs = indexOfPair(argv, "--tmpfs", home);
        assertTrue(homeTmpfs >= 0, "the $HOME tmpfs is missing, argv=" + argv);
        assertTrue(indexOfPair(argv, "--bind", SandboxHiddenRoots.canonical(writeRoot.toPath()).toString()) > homeTmpfs,
                "the write-root bind must follow the $HOME tmpfs, argv=" + argv);
        assertTrue(indexOfPair(argv, "--ro-bind-try", SandboxHiddenRoots.canonical("/opt/state").toString()) > homeTmpfs,
                "allowances must follow the $HOME tmpfs, argv=" + argv);
        assertEquals(List.of("/bin/sh", "-c", "true"), argv.subList(argv.size() - 3, argv.size()));
    }

    /**
     * Deny unshares the network for a native tool's run and changes nothing else, while allow, blank and
     * a coding harness (the key the adapter overload passes) keep it: the harness needs its model API.
     */
    @Test
    void linuxUnsharesTheNetworkOnlyForANativeToolRunUnderDeny() {
        ShellSandboxSync.acquire();
        try {
            var command = List.of("/bin/sh", "-c", "true");
            var root = session.toFile();
            var harnessAllowances = new ClaudeAdapter().sandboxAllowances();
            var open = HarnessSandbox.linuxArgv(command, root, List.of(), HarnessSandbox.SHELL_SANDBOX_KEY);
            var harness = HarnessSandbox.linuxArgv(command, root, harnessAllowances, HarnessSandbox.ACP_SANDBOX_KEY);
            assertFalse(open.contains("--unshare-net"), "blank means allow, argv=" + open);
            assertFalse(harness.contains("--unshare-net"), "argv=" + harness);

            ConfigService.set(HarnessSandbox.SHELL_NETWORK_KEY, "allow");
            assertEquals(open, HarnessSandbox.linuxArgv(command, root, List.of(), HarnessSandbox.SHELL_SANDBOX_KEY));

            ConfigService.set(HarnessSandbox.SHELL_NETWORK_KEY, "deny");
            var cut = new java.util.ArrayList<>(open);
            cut.add(1, "--unshare-net");
            assertEquals(cut, HarnessSandbox.linuxArgv(command, root, List.of(), HarnessSandbox.SHELL_SANDBOX_KEY));
            assertEquals(harness, HarnessSandbox.linuxArgv(command, root, harnessAllowances, HarnessSandbox.ACP_SANDBOX_KEY),
                    "a coding harness keeps the network under deny");
        } finally {
            ConfigService.set(HarnessSandbox.SHELL_NETWORK_KEY, "");
            ShellSandboxSync.release();
        }
    }

    /** The Seatbelt network deny is one rule, added only when asked; every file rule is unchanged. */
    @Test
    void macProfileDeniesTheNetworkOnlyWhenAsked() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw");
        var writeRoot = root.resolve("workspace/main").toFile();
        var open = HarnessSandbox.macosProfile(writeRoot, List.of(), "/Users/operator", stateOf(root), false);
        assertFalse(open.contains("(deny network*)"), open);
        assertEquals(open.replace("(allow default)\n", "(allow default)\n(deny network*)\n"),
                HarnessSandbox.macosProfile(writeRoot, List.of(), "/Users/operator", stateOf(root), true));
    }

    private static int indexOfPair(List<String> argv, String flag, String value) {
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (flag.equals(argv.get(i)) && value.equals(argv.get(i + 1))) return i;
        }
        return -1;
    }

    /** A fake install at {@code root}: its state directories, canonical as production passes them. */
    private static List<Path> stateOf(Path root) {
        return List.of(root.resolve("data"), root.resolve("certs"), root.resolve("conf"), root.resolve("logs"));
    }

    /**
     * JCLAW-1452 AC3: the $HOME tmpfs hides a checkout under the home directory but nothing
     * outside it, so data/, certs/, conf/ and logs/ each get their own tmpfs, after the $HOME one,
     * in both layouts. The write root outside them is still bound.
     */
    @Test
    void linuxHidesJClawStateWhetherOrNotTheCheckoutIsUnderHome() {
        var base = SandboxHiddenRoots.canonical(session);
        var home = base.resolve("home/operator");
        for (var root : List.of(home.resolve("jclaw"), base.resolve("opt/jclaw"))) {
            var writeRoot = root.resolve("workspace/main");
            var argv = HarnessSandbox.linuxArgv(List.of("/bin/sh", "-c", "true"), writeRoot.toFile(),
                    List.of(".claude"), home.toString(), stateOf(root), false);

            int homeTmpfs = indexOfPair(argv, "--tmpfs", home.toString());
            assertTrue(homeTmpfs >= 0, "the $HOME tmpfs is missing, argv=" + argv);
            for (var hidden : stateOf(root)) {
                assertTrue(indexOfPair(argv, "--tmpfs", hidden.toString()) > homeTmpfs,
                        hidden + " must get a tmpfs after the $HOME one, argv=" + argv);
            }
            assertTrue(indexOfPair(argv, "--bind", writeRoot.toString()) > homeTmpfs,
                    "the write root must still be bound, argv=" + argv);
            assertEquals(List.of("/bin/sh", "-c", "true"), argv.subList(argv.size() - 3, argv.size()));
        }
    }

    /** Started as root (the container), bwrap makes no user namespace and needs CAP_SYS_ADMIN, which Docker drops: it must be asked for one. */
    @Test
    void aRootBwrapAsksForItsOwnUserNamespace() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw").toFile();
        var asRoot = HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/root", List.of(), false, true);
        var notRoot = HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/home/operator", List.of(), false, false);
        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--ro-bind", "/", "/"), asRoot.subList(0, 7));
        assertEquals(List.of("bwrap", "--ro-bind", "/", "/"), notRoot.subList(0, 4));
        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc",
                "--tmpfs", "/tmp", "true"), HarnessSandbox.BWRAP_PROBE_AS_ROOT);
        assertEquals(List.of("bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc",
                "--tmpfs", "/tmp", "true"), HarnessSandbox.BWRAP_PROBE);
    }

    /**
     * A custom agent's bwrap is asked for a user namespace even when not root, which a setuid bwrap would
     * otherwise not make; a root argv already asks and is not asked twice. main's and a harness's argv are as before.
     */
    @Test
    void linuxCustomAgentArgvAlwaysAsksForItsOwnUserNamespace() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw").toFile();
        var notRoot = HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/home/operator", List.of(), true, false);
        var asRoot = HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/root", List.of(), true, true);

        var expected = new ArrayList<>(notRoot);
        expected.add(1, "--unshare-user");
        assertEquals(expected, HarnessSandbox.inOwnUserNamespace(notRoot));
        assertEquals(List.of("bwrap", "--unshare-user", "--unshare-net", "--ro-bind", "/", "/"),
                HarnessSandbox.inOwnUserNamespace(notRoot).subList(0, 6));
        assertEquals(asRoot, HarnessSandbox.inOwnUserNamespace(asRoot));
        assertFalse(notRoot.contains("--unshare-user"), notRoot.toString());
    }

    /**
     * Only a custom agent's run is in a PID namespace and a session of its own, where a signal finds no process
     * outside it by pid or through the process group JClaw started it in: --unshare-pid and --new-session go
     * before the mounts, and --proc /proc is then that namespace's procfs, or is left out where the kernel
     * refuses one. main's and a harness's argv have none of these changes.
     */
    @Test
    void linuxOnlyACustomAgentsArgvIsInItsOwnPidNamespaceAndSession() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw").toFile();
        var shared = HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/home/operator", List.of(), true, true,
                List.of());
        var own = HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/home/operator", List.of(), true, true,
                List.of(), HarnessSandbox.LinuxPids.OWN);
        var underOuterProc = HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/home/operator", List.of(), true,
                true, List.of(), HarnessSandbox.LinuxPids.OWN_UNDER_OUTER_PROC);

        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--unshare-net", "--unshare-pid",
                "--new-session", "--ro-bind", "/", "/", "--dev", "/dev", "--tmpfs", "/tmp", "--proc", "/proc",
                "--tmpfs", "/home/operator"), own.subList(0, 18));
        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--unshare-net", "--unshare-pid",
                "--new-session", "--ro-bind", "/", "/", "--dev", "/dev", "--tmpfs", "/tmp",
                "--tmpfs", "/home/operator"), underOuterProc.subList(0, 16));
        var ownWithoutTheFlags = new ArrayList<>(own);
        ownWithoutTheFlags.removeAll(List.of("--unshare-pid", "--new-session"));
        assertEquals(shared, ownWithoutTheFlags);
        var outerWithProcBack = new ArrayList<>(underOuterProc);
        outerWithProcBack.removeAll(List.of("--unshare-pid", "--new-session"));
        outerWithProcBack.addAll(12, List.of("--proc", "/proc"));
        assertEquals(shared, outerWithProcBack);
        assertEquals(shared, HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/home/operator", List.of(), true,
                true, List.of(), HarnessSandbox.LinuxPids.SHARED));

        for (var key : List.of(HarnessSandbox.SHELL_SANDBOX_KEY, HarnessSandbox.ACP_SANDBOX_KEY)) {
            var argv = HarnessSandbox.linuxArgv(List.of("true"), session.toFile(), List.of(), key);
            assertFalse(argv.contains("--unshare-pid"), key + " shares JClaw's PID namespace, argv=" + argv);
            assertFalse(argv.contains("--new-session"), key + " stays in JClaw's session, argv=" + argv);
            assertTrue(indexOfPair(argv, "--proc", "/proc") >= 0, key + " keeps --proc /proc, argv=" + argv);
        }
    }

    /**
     * The launch that picks a custom agent's PID namespace is the readiness probe as that run starts: in a user
     * namespace, a PID namespace and a session of its own, first with a procfs of its own, then without one.
     */
    @Test
    void thePidNamespaceProbeIsTheReadinessProbeInItsOwnNamespacesAndSession() {
        assertEquals(List.of("bwrap", "--unshare-user", "--unshare-pid", "--new-session", "--ro-bind", "/", "/",
                "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp", "true"),
                HarnessSandbox.pidNamespaceProbe(HarnessSandbox.LinuxPids.OWN, false));
        assertEquals(List.of("bwrap", "--unshare-user", "--unshare-pid", "--new-session", "--ro-bind", "/", "/",
                "--dev", "/dev", "--tmpfs", "/tmp", "true"),
                HarnessSandbox.pidNamespaceProbe(HarnessSandbox.LinuxPids.OWN_UNDER_OUTER_PROC, false));
        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--unshare-pid", "--new-session",
                "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp", "true"),
                HarnessSandbox.pidNamespaceProbe(HarnessSandbox.LinuxPids.OWN, true));
        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--unshare-pid", "--new-session",
                "--ro-bind", "/", "/", "--dev", "/dev", "--tmpfs", "/tmp", "true"),
                HarnessSandbox.pidNamespaceProbe(HarnessSandbox.LinuxPids.OWN_UNDER_OUTER_PROC, true));
    }

    /**
     * A custom agent's Linux run gets empty runtime folders of its own, where the services outside it keep their
     * control sockets. The mounts sit between /proc and the $HOME tmpfs, and main's and a harness's argv have none.
     */
    @Test
    void linuxCustomAgentArgvHidesTheRuntimeFoldersBeforeTheHomeTmpfs() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw").toFile();
        var plain = HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/home/operator", List.of(), false, false);
        var ownRuntime = List.of("--tmpfs", "/run", "--ro-bind-try", "/run/systemd/resolve/stub-resolv.conf",
                "/run/systemd/resolve/stub-resolv.conf");
        var custom = HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/home/operator", List.of(), false, false,
                ownRuntime);

        assertEquals(List.of("bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--tmpfs", "/tmp", "--proc", "/proc",
                "--tmpfs", "/run", "--ro-bind-try", "/run/systemd/resolve/stub-resolv.conf",
                "/run/systemd/resolve/stub-resolv.conf", "--tmpfs", "/home/operator"), custom.subList(0, 17));
        var without = new ArrayList<>(custom);
        without.subList(10, 15).clear();
        assertEquals(plain, without);
        assertEquals(plain, HarnessSandbox.linuxArgv(List.of("true"), root, List.of(), "/home/operator", List.of(), false,
                false, List.of()));
        assertEquals(-1, indexOfPair(plain, "--tmpfs", "/run"), "main's and a harness's argv keep /run, argv=" + plain);
    }

    /**
     * Each runtime folder gets a tmpfs, and only what a command needs from under one comes back, read-only:
     * the file /etc/resolv.conf points at and the PATH folders, once each. A path that merely starts with the
     * same letters, the folder itself, a relative entry and anything outside are left alone.
     */
    @Test
    void linuxOwnRuntimeBringsBackOnlyTheResolverFileAndPathFoldersUnderARuntimeFolder() {
        assertEquals(List.of("--tmpfs", "/run"), HarnessSandbox.linuxOwnRuntime(List.of("/run"), null, null));
        assertEquals(List.of(), HarnessSandbox.linuxOwnRuntime(List.of(), "/run/systemd/resolve/stub-resolv.conf", "/run/bin"));
        assertEquals(List.of("--tmpfs", "/run"),
                HarnessSandbox.linuxOwnRuntime(List.of("/run"), "/etc/resolv.conf", "/usr/bin:/bin"),
                "a resolver file and a PATH outside /run need nothing brought back");
        assertEquals(List.of("--tmpfs", "/run",
                        "--ro-bind-try", "/run/systemd/resolve/stub-resolv.conf", "/run/systemd/resolve/stub-resolv.conf",
                        "--ro-bind-try", "/run/current-system/sw/bin", "/run/current-system/sw/bin",
                        "--ro-bind-try", "/run/wrappers/bin", "/run/wrappers/bin"),
                HarnessSandbox.linuxOwnRuntime(List.of("/run"), "/run/systemd/resolve/stub-resolv.conf",
                        "/run/current-system/sw/bin:/usr/bin::/run/wrappers/bin/:/run/current-system/sw/bin:/run:/runner/bin:run/bin:/run/../opt/bin"));
        assertEquals(List.of("--tmpfs", "/run", "--tmpfs", "/var/run",
                        "--ro-bind-try", "/var/run/NetworkManager/resolv.conf", "/var/run/NetworkManager/resolv.conf"),
                HarnessSandbox.linuxOwnRuntime(List.of("/run", "/var/run"), "/var/run/NetworkManager/resolv.conf", null));
    }

    /** A write root above data/ (a coding run continuing in the checkout itself) is bound first, so data/'s tmpfs still covers it. */
    @Test
    void linuxHidesStateInsideAnAncestorWriteRoot() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw");
        var argv = HarnessSandbox.linuxArgv(List.of("true"), root.toFile(), List.of(), "/home/operator",
                stateOf(root), false);
        int bind = indexOfPair(argv, "--bind", root.toString());
        assertTrue(bind >= 0, "argv=" + argv);
        for (var hidden : stateOf(root)) {
            assertTrue(indexOfPair(argv, "--tmpfs", hidden.toString()) > bind,
                    hidden + " must be hidden after the ancestor bind that would re-expose it, argv=" + argv);
        }
    }

    /** A home read is bound read-only: only the write root gets a read-write bind, so a homeReads folder cannot be written. */
    @Test
    void linuxBindsEveryAllowanceReadOnlyAndOnlyTheWriteRootReadWrite() {
        var home = "/home/operator";
        var writeRoot = new File(home + "/workspace/agent");
        var reads = List.of(home + "/tools", home + "/notes");
        var argv = HarnessSandbox.linuxArgv(List.of("true"), writeRoot, reads, home, List.of(), false, false);
        var readWrite = new ArrayList<String>();
        for (int i = 0; i + 2 < argv.size(); i++) {
            if (argv.get(i).equals("--bind")) readWrite.add(argv.get(i + 1));
        }
        assertEquals(List.of(SandboxHiddenRoots.canonical(writeRoot.toPath()).toString()), readWrite, "argv=" + argv);
        for (var read : reads) {
            var canonical = SandboxHiddenRoots.canonical(java.nio.file.Path.of(read)).toString();
            assertTrue(indexOfPair(argv, "--ro-bind-try", canonical) > 0, read + " must be a read-only bind, argv=" + argv);
        }
    }

    /** A write root or allowance beneath a hidden root is bound back after that root's tmpfs, or the tmpfs would cover it. */
    @Test
    void linuxReopensAGrantBeneathAHiddenRootAfterHidingIt() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw");
        var writeRoot = root.resolve("data/coding/run");
        var allowance = root.resolve("conf/extra");
        var argv = HarnessSandbox.linuxArgv(List.of("true"), writeRoot.toFile(), List.of(allowance.toString()),
                "/home/operator", stateOf(root), false);
        assertTrue(indexOfPair(argv, "--bind", writeRoot.toString())
                        > indexOfPair(argv, "--tmpfs", root.resolve("data").toString()),
                "argv=" + argv);
        assertTrue(indexOfPair(argv, "--ro-bind-try", allowance.toString())
                        > indexOfPair(argv, "--tmpfs", root.resolve("conf").toString()),
                "argv=" + argv);
    }

    /**
     * A tmpfs needs an existing mount point, so the backup directory, which JClaw owns, is
     * created and then hidden; a backup made after a long-running harness started would
     * otherwise be readable to it. Any other missing root is skipped, and a directory that
     * cannot be created is logged rather than thrown.
     */
    /** The production argv creates each before hiding it, as the test below shows for one. */
    @Test
    void jclawsOwnSecretFoldersAreTheBackupsAndTheFilesSkillUpdatesKept() {
        assertEquals(List.of(DatabaseService.backupsDir(), KeptSkillFiles.root()), HarnessSandbox.ownSecretFolders());
    }

    @Test
    void linuxCreatesAMissingBackupDirectoryAndHidesIt() throws Exception {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw");
        var backups = root.resolve("backups");
        assertFalse(Files.exists(backups));

        var hidden = HarnessSandbox.linuxHiddenRoots(List.of(backups), List.of(root.resolve("certs"), backups));
        assertTrue(Files.isDirectory(backups), "the backup directory must be created");
        assertEquals(List.of(backups), hidden, "a missing root that is not the backup directory gets no tmpfs");
        var argv = HarnessSandbox.linuxArgv(List.of("true"), session.toFile(), List.of(), "/home/operator", hidden, false);
        assertTrue(indexOfPair(argv, "--tmpfs", backups.toString()) >= 0, "argv=" + argv);

        var blocked = Files.writeString(session.resolve("a-file"), "x").resolve("backups");
        var events = EventLogger.captureForTest(() ->
                assertEquals(List.of(), HarnessSandbox.linuxHiddenRoots(List.of(blocked), List.of(blocked))));
        assertTrue(events.stream().anyMatch(e -> "WARN".equals(e.level()) && e.message().contains(blocked.toString())),
                "the failure must be logged, events=" + events);
    }

    /**
     * Every grant is bound at its canonical path, where the tmpfs mounts go. A write root above
     * data/ reached through a symlink, bound at the link, would show data/ at a path no tmpfs covers.
     */
    @Test
    void linuxBindsAGrantReachedThroughASymlinkAtItsCanonicalPath() throws Exception {
        var real = Files.createDirectories(SandboxHiddenRoots.canonical(session).resolve("real/jclaw"));
        var link = Files.createSymbolicLink(session.resolve("link"), real.getParent());
        var throughLink = link.resolve("jclaw");
        var argv = HarnessSandbox.linuxArgv(List.of("true"), throughLink.toFile(),
                List.of(throughLink.resolve("workspace").toString()), "/home/operator", stateOf(real), false);

        int bind = indexOfPair(argv, "--bind", real.toString());
        assertTrue(bind >= 0, "the write root must be bound at its canonical path, argv=" + argv);
        assertTrue(indexOfPair(argv, "--ro-bind-try", real.resolve("workspace").toString()) >= 0,
                "an allowance too, argv=" + argv);
        for (var hidden : stateOf(real)) {
            assertTrue(indexOfPair(argv, "--tmpfs", hidden.toString()) > bind, hidden + " must cover the bind, argv=" + argv);
        }
        assertTrue(argv.stream().noneMatch(a -> a.startsWith(link.toString())),
                "nothing may be bound at the symlinked path, argv=" + argv);
    }

    /** The production argv hides this install's own state directories at their canonical paths. */
    @Test
    void linuxProductionArgvHidesThisInstallsState() {
        var argv = HarnessSandbox.linuxArgv(List.of("true"), session.toFile(), List.of(), HarnessSandbox.ACP_SANDBOX_KEY);
        var appRoot = Play.applicationPath.toPath();
        assertTrue(Files.isDirectory(appRoot.resolve("conf")), "a checkout always has conf/");
        for (var dir : List.of("data", "certs", "conf", "logs")) {
            var path = appRoot.resolve(dir);
            if (!Files.isDirectory(path)) continue;
            assertTrue(indexOfPair(argv, "--tmpfs", SandboxHiddenRoots.canonical(path).toString()) >= 0,
                    dir + "/ must be hidden, argv=" + argv);
        }
        assertTrue(indexOfPair(argv, "--tmpfs", SandboxHiddenRoots.canonical(KeptSkillFiles.root()).toString()) >= 0,
                "the files skill updates kept, outside data/ here, must be created and hidden, argv=" + argv);
    }

    @Test
    void hiddenRootsAreThisInstallsStateAtCanonicalPaths() throws Exception {
        var hidden = SandboxHiddenRoots.current();
        var appRoot = Play.applicationPath.toPath();
        for (var dir : List.of("data", "certs", "conf", "logs")) {
            assertTrue(hidden.contains(SandboxHiddenRoots.canonical(appRoot.resolve(dir))), dir + " in " + hidden);
        }
        var link = Files.createSymbolicLink(session.resolve("link"), Files.createDirectory(session.resolve("target")));
        assertEquals(session.toRealPath().resolve("target/data"), SandboxHiddenRoots.canonical(link.resolve("data")),
                "Seatbelt matches resolved paths, so a root reached through a symlink must be denied at its target");
    }

    /** The test install keeps them under tmp/, outside data/, as an operator who moves them does. */
    @Test
    void theFilesSkillUpdatesKeptAreHiddenWhereverTheyAreConfigured() {
        var kept = SandboxHiddenRoots.canonical(KeptSkillFiles.root());
        var data = SandboxHiddenRoots.canonical(Play.applicationPath.toPath().resolve("data"));
        assertFalse(kept.startsWith(data), kept + " must lie outside data/ for this test to measure anything");

        assertTrue(SandboxHiddenRoots.current().contains(kept), kept + " in " + SandboxHiddenRoots.current());
    }

    /**
     * Seatbelt's last matching rule wins: the hidden roots are denied after every write grant,
     * a grant beneath one is reopened after that, an ancestor grant is not, and the $HOME
     * secrets come last so a reopen cannot reach them. Pinned on every host.
     */
    @Test
    void macProfileHidesStateAfterTheGrantsAndReopensBeneathIt() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw");
        var writeRoot = root.resolve("data/coding/run");
        var profile = HarnessSandbox.macosProfile(writeRoot.toFile(),
                List.of(".claude", root.resolve("workspace").toString()), "/Users/operator", stateOf(root), false);

        int lastWriteGrant = profile.indexOf("(allow file-write* (subpath \"" + root.resolve("workspace") + "\"))");
        int denyData = profile.indexOf("(deny file-read* file-write* (subpath \"" + root.resolve("data") + "\"))");
        int reopen = profile.indexOf("(allow file-read* file-write* (subpath \"" + writeRoot + "\"))");
        int denySsh = profile.indexOf("(deny file-read* (subpath \"/Users/operator/.ssh\"))");
        assertTrue(lastWriteGrant >= 0 && denyData > lastWriteGrant, profile);
        assertTrue(profile.contains("(deny file-read* file-write* (subpath \"" + root.resolve("certs") + "\"))"), profile);
        assertTrue(profile.contains("(deny file-read* file-write* (subpath \"" + root.resolve("conf") + "\"))"), profile);
        assertTrue(profile.contains("(deny file-read* file-write* (subpath \"" + root.resolve("logs") + "\"))"), profile);
        assertTrue(reopen > denyData, "the write root beneath data/ must be reopened after the deny: " + profile);
        assertTrue(denySsh > reopen, "the $HOME secrets must come last: " + profile);
        assertFalse(profile.contains("(allow file-read* file-write* (subpath \"" + root.resolve("workspace") + "\"))"),
                "a grant outside the hidden roots needs no reopen: " + profile);

        var ancestor = HarnessSandbox.macosProfile(root.toFile(), List.of(), "/Users/operator", stateOf(root), false);
        assertFalse(ancestor.contains("(allow file-read* file-write* (subpath \"" + root + "\"))"),
                "a write root above data/ must not reopen it: " + ancestor);
    }

    /**
     * A grant equal to a hidden root, or holding a nested one (the default data/backups), stays
     * hidden: a coding run whose parent is the checkout and whose model-chosen folder is "data"
     * would otherwise reopen the whole database directory.
     */
    @Test
    void aGrantEqualToOrHoldingAHiddenRootIsNotReopened() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw");
        var data = root.resolve("data");
        var hidden = List.of(data, root.resolve("certs"), root.resolve("conf"), data.resolve("runs/backups"));

        assertNull(SandboxHiddenRoots.beneath(data.toString(), hidden), "a grant equal to data/");
        assertNull(SandboxHiddenRoots.beneath(data.resolve("runs").toString(), hidden), "a grant holding data/runs/backups");
        assertNull(SandboxHiddenRoots.beneath(data.resolve("runs/backups").toString(), hidden), "a grant equal to the nested root");
        assertEquals(data.resolve("runs/other"), SandboxHiddenRoots.beneath(data.resolve("runs/other").toString(), hidden));

        var profile = HarnessSandbox.macosProfile(data.toFile(), List.of(data.resolve("runs").toString()),
                "/Users/operator", hidden, false);
        assertFalse(profile.contains("(allow file-read* file-write* (subpath \"" + data + "\"))"), profile);
        assertFalse(profile.contains("(allow file-read* file-write* (subpath \"" + data.resolve("runs") + "\"))"), profile);

        var argv = HarnessSandbox.linuxArgv(List.of("true"), data.toFile(), List.of(), "/home/operator", hidden, false);
        int bind = indexOfPair(argv, "--bind", data.toString());
        int tmpfs = indexOfPair(argv, "--tmpfs", data.toString());
        assertTrue(bind >= 0 && bind < tmpfs,
                "a write root equal to data/ must be bound before data/'s tmpfs covers it, argv=" + argv);
        assertEquals(-1, indexOfPair(argv.subList(tmpfs, argv.size()), "--bind", data.toString()),
                "and never bound back after it, argv=" + argv);
    }

    /** Sentinel files of this test's own under the app root's data/, certs/ and logs/; the caller deletes them. */
    private static List<Path> writeStateSentinels(String content) throws Exception {
        var appRoot = Play.applicationPath.toPath();
        var out = new java.util.ArrayList<Path>();
        for (var dir : List.of("data", "certs", "logs")) {
            var sentinel = Files.createDirectories(appRoot.resolve(dir))
                    .resolve("jclaw-1452-sentinel-" + java.util.UUID.randomUUID() + ".txt");
            Files.writeString(sentinel, content);
            out.add(sentinel);
        }
        return out;
    }

    private static String run(List<String> argv, Path cwd) throws Exception {
        var proc = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start();
        assertTrue(proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "the run finished");
        return new String(proc.getInputStream().readAllBytes());
    }

    /**
     * JCLAW-1452 AC2: a confined harness run, launched the way the ACP runner launches one
     * (the session folder as write root, the adapter's allowances, an untrusted origin), cannot
     * read the app root's data/, certs/ or logs/, while its session folder stays readable and writable.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void macAcpRunCannotReadJClawState() throws Exception {
        var agent = "harness-sbx-1452";
        var coding = AgentService.workspacePath(agent).resolve("coding").toAbsolutePath();
        var secret = "jclaw-1452-secret-" + java.util.UUID.randomUUID();
        var sentinels = writeStateSentinels(secret);
        try {
            var folder = CodingRunFolders.open(coding, "read jclaw state", null).dir();
            var env = Play.applicationPath.toPath().resolve("certs/.env");
            var files = String.join(" ", sentinels.stream().map(s -> "'" + s + "'").toList());
            var script = "cat %s 2>/dev/null || echo STATE_DENIED; ".formatted(files)
                    + "cat '%s' >/dev/null 2>&1 && echo ENV_READABLE || echo ENV_DENIED; ".formatted(env)
                    + "echo session-ok > note.txt && cat note.txt";
            var command = List.of("/bin/sh", "-c", script);

            var control = run(command, folder);
            assertTrue(control.contains(secret), "unconfined, the sentinels must be readable or the test proves nothing: " + control);
            Files.delete(folder.resolve("note.txt"));

            // Set right before the wrap: another class's table wipe can clear the key.
            ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
            var wrapped = HarnessSandbox.wrap(command, folder.toFile(), new ClaudeAdapter(), false);
            assertEquals("sandbox-exec", wrapped.get(0));
            var confined = run(wrapped, folder);
            assertFalse(confined.contains(secret), "data/, certs/ and logs/ must be unreadable: " + confined);
            assertTrue(confined.contains("STATE_DENIED"), confined);
            if (Files.isReadable(env)) assertTrue(confined.contains("ENV_DENIED"), "certs/.env must be unreadable: " + confined);
            assertTrue(confined.contains("session-ok"), "the session folder must stay writable and readable: " + confined);
            assertTrue(Files.exists(folder.resolve("note.txt")));
        } finally {
            for (var s : sentinels) Files.deleteIfExists(s);
            deleteTree(AgentService.workspacePath(agent));
        }
    }

    /**
     * A write root and an allowance beneath data/ are reopened after the deny: a confined run
     * reads and writes both, and still cannot read their sibling in data/. A write root that is
     * data/ itself reopens nothing.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void macReopensAWriteRootAndAllowanceBeneathData() throws Exception {
        var data = Files.createDirectories(Play.applicationPath.toPath().resolve("data"));
        var mine = Files.createDirectory(data.resolve("jclaw-1452-sbx-" + java.util.UUID.randomUUID()));
        try {
            var writeRoot = Files.createDirectory(mine.resolve("run"));
            var allowance = Files.createDirectory(mine.resolve("allow"));
            Files.writeString(allowance.resolve("allowed.txt"), "allowance-readable");
            Files.writeString(mine.resolve("sibling.txt"), "sibling-secret");
            var script = "echo written > out.txt && cat out.txt; cat '%s'; ".formatted(allowance.resolve("allowed.txt"))
                    + "cat '%s' 2>/dev/null || echo SIBLING_DENIED".formatted(mine.resolve("sibling.txt"));

            ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
            var wrapped = HarnessSandbox.wrap(List.of("/bin/sh", "-c", script), writeRoot.toFile(),
                    List.of(allowance.toString()), HarnessSandbox.ACP_SANDBOX_KEY, false);
            assertEquals("sandbox-exec", wrapped.get(0));
            var out = run(wrapped, writeRoot);
            assertTrue(out.contains("written"), "the write root beneath data/ must be writable and readable: " + out);
            assertTrue(Files.exists(writeRoot.resolve("out.txt")), out);
            assertTrue(out.contains("allowance-readable"), "the allowance beneath data/ must be readable: " + out);
            assertFalse(out.contains("sibling-secret"), "the rest of data/ must stay hidden: " + out);
            assertTrue(out.contains("SIBLING_DENIED"), out);

            ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
            var whole = HarnessSandbox.wrap(List.of("/bin/sh", "-c",
                            "cat '%s' 2>/dev/null || echo SIBLING_DENIED".formatted(mine.resolve("sibling.txt"))),
                    data.toFile(), List.of(), HarnessSandbox.ACP_SANDBOX_KEY, false);
            assertEquals("sandbox-exec", whole.get(0));
            var wholeOut = run(whole, session);
            assertFalse(wholeOut.contains("sibling-secret"), "a write root equal to data/ must not reopen it: " + wholeOut);
            assertTrue(wholeOut.contains("SIBLING_DENIED"), wholeOut);
        } finally {
            deleteTree(mine);
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** On macOS too, deny reaches a native tool's profile through {@link HarnessSandbox#wrap} but never a harness's. */
    @Test
    @EnabledOnOs(OS.MAC)
    void macDeniesTheNetworkToANativeToolRunButNotToAHarness() {
        ShellSandboxSync.acquire();
        try {
            // Set right before the wraps: another class's table wipe can clear a key.
            ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, "true");
            ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
            ConfigService.set(HarnessSandbox.SHELL_NETWORK_KEY, "deny");
            var nativeProfile = HarnessSandbox.wrap(List.of("true"), session.toFile(), List.of(),
                    HarnessSandbox.SHELL_SANDBOX_KEY, false).get(2);
            var harnessProfile = HarnessSandbox.wrap(List.of("true"), session.toFile(), new ClaudeAdapter(), false).get(2);
            assertTrue(nativeProfile.contains("(deny network*)\n"), nativeProfile);
            assertFalse(harnessProfile.contains("(deny network*)"), harnessProfile);
        } finally {
            ConfigService.set(HarnessSandbox.SHELL_NETWORK_KEY, "");
            ShellSandboxSync.release();
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macProfileWrapsAndConfinesWrites() throws Exception {
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
        var wrapped = HarnessSandbox.wrap(List.of("/bin/sh"), session.toFile(), new GenericAdapter());
        assertEquals("sandbox-exec", wrapped.get(0));
        assertEquals("-p", wrapped.get(1));
        assertTrue(wrapped.get(2).contains(session.toAbsolutePath().toString()),
                "the profile grants the session dir");
        assertTrue(wrapped.get(2).contains(".ssh"), "the profile denies ~/.ssh reads");

        // Run a real confined shell: write inside (must succeed) and outside
        // (must fail). "Outside" must be a genuinely-denied path — NOT the temp
        // tree (/var/folders is an intentional write allowance for TMPDIR), so
        // target the home root, which the profile denies.
        var inside = session.resolve("out.txt");
        var outside = Path.of(System.getProperty("user.home"), "jclaw-sbx-escape-probe.txt");
        Files.deleteIfExists(outside);
        var script = "echo in > " + inside + "; echo out > " + outside + " 2>/dev/null; true";
        var full = new java.util.ArrayList<>(wrapped);
        full.add("-c");
        full.add(script);
        var proc = new ProcessBuilder(full).redirectErrorStream(true).start();
        assertTrue(proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "sandboxed run finished");

        assertTrue(Files.exists(inside), "write inside the session dir is allowed");
        assertFalse(Files.exists(outside),
                "write to the home root (a denied path) must be blocked by the sandbox");
        Files.deleteIfExists(outside);
    }

    // JCLAW-731: a session path containing a double-quote and parens cannot
    // break out of the Seatbelt (subpath "...") string literal to widen the
    // profile. The quote is backslash-escaped, so the injected clause stays
    // inert text inside the literal rather than a new s-expression.
    @Test
    @EnabledOnOs(OS.MAC)
    void macProfileEscapesHostileSessionPath() {
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
        // The '"' would close the intended subpath early; the ')(injected...'
        // that follows would open a fresh grant if it escaped the literal.
        var hostile = new java.io.File("/tmp/jclaw-evil\")(injected-grant");
        var wrapped = HarnessSandbox.wrap(List.of("/bin/sh"), hostile, new GenericAdapter());
        var profile = wrapped.get(2);

        // The quote after 'evil' must be escaped (\"), keeping ')(injected...'
        // inside the string literal.
        assertTrue(profile.contains("evil\\\")(injected"),
                "hostile quote must be backslash-escaped: " + profile);
        // The UNescaped breakout form must be absent — its presence would mean
        // the literal closed early and the injected clause became live syntax.
        assertFalse(profile.contains("evil\")(injected"),
                "an unescaped quote let the path break out of the string literal");
        // The legit session grant survives escaping intact.
        assertTrue(profile.contains("(allow file-write* (subpath \"/tmp/jclaw-evil"),
                "the session path must still be granted (escaping must not corrupt it)");
    }

    /**
     * A script's profile: no network, writes to its scratch folder alone, $HOME hidden but for the scratch and the
     * interpreter folder, JClaw's folders hidden after that, the interpreter folder beneath data/ reopened for
     * reading only, the $HOME secrets last, then the LaunchServices and signal denies.
     */
    @Test
    void scriptMacosProfileReadsOnlyItsInterpreterFolderUnderData() {
        var hidden = List.of(Path.of("/h/u/jc/data"), Path.of("/h/u/jc/certs"), Path.of("/h/u/jc/logs"),
                Path.of("/h/u/jc/data/backups"));
        assertEquals("""
                (version 1)
                (allow default)
                (deny network*)
                (deny file-write* (subpath "/"))
                (allow file-write* (subpath "/private/var/folders/x/T/jclaw-script-1"))
                (deny file-read* (subpath "/h/u"))
                (allow file-read* (subpath "/private/var/folders/x/T/jclaw-script-1"))
                (allow file-read* (subpath "/h/u/jc/data/playwright-node/24.22.1-darwin-arm64"))
                (allow file-read-metadata (literal "/h/u"))
                (allow file-read-metadata (literal "/h/u/jc"))
                (allow file-read-metadata (literal "/h/u/jc/data"))
                (allow file-read-metadata (literal "/h/u/jc/data/playwright-node"))
                (deny file-read* file-write* (subpath "/h/u/jc/data"))
                (deny file-read* file-write* (subpath "/h/u/jc/certs"))
                (deny file-read* file-write* (subpath "/h/u/jc/logs"))
                (deny file-read* file-write* (subpath "/h/u/jc/data/backups"))
                (allow file-read* (subpath "/h/u/jc/data/playwright-node/24.22.1-darwin-arm64"))
                (deny file-read* (subpath "/h/u/.ssh"))
                (deny file-read* (subpath "/h/u/.aws"))
                (deny file-read* (subpath "/h/u/.gnupg"))
                (deny file-read* (subpath "/h/u/.config/gcloud"))
                (deny file-read* (subpath "/h/u/.kube"))
                (deny file-read* (subpath "/h/u/.netrc"))
                (deny lsopen)
                (deny signal (require-not (target same-sandbox)))
                """, HarnessSandbox.scriptMacosProfile(Path.of("/private/var/folders/x/T/jclaw-script-1"),
                Path.of("/h/u/jc/data/playwright-node/24.22.1-darwin-arm64"), "/h/u", hidden));
    }

    /** A scratch folder beneath data/ gets its reads and writes back after data/ is hidden; an interpreter outside it needs no reopen. */
    @Test
    void scriptMacosProfileReopensAScratchFolderBeneathData() {
        var hidden = List.of(Path.of("/h/u/jc/data"), Path.of("/h/u/jc/certs"));
        assertEquals("""
                (version 1)
                (allow default)
                (deny network*)
                (deny file-write* (subpath "/"))
                (allow file-write* (subpath "/h/u/jc/data/scripts/run-1"))
                (deny file-read* (subpath "/h/u"))
                (allow file-read* (subpath "/h/u/jc/data/scripts/run-1"))
                (allow file-read* (subpath "/opt/node/24.22.1"))
                (allow file-read-metadata (literal "/h/u"))
                (allow file-read-metadata (literal "/h/u/jc"))
                (allow file-read-metadata (literal "/h/u/jc/data"))
                (allow file-read-metadata (literal "/h/u/jc/data/scripts"))
                (deny file-read* file-write* (subpath "/h/u/jc/data"))
                (deny file-read* file-write* (subpath "/h/u/jc/certs"))
                (allow file-read* file-write* (subpath "/h/u/jc/data/scripts/run-1"))
                (deny file-read* (subpath "/h/u/.ssh"))
                (deny file-read* (subpath "/h/u/.aws"))
                (deny file-read* (subpath "/h/u/.gnupg"))
                (deny file-read* (subpath "/h/u/.config/gcloud"))
                (deny file-read* (subpath "/h/u/.kube"))
                (deny file-read* (subpath "/h/u/.netrc"))
                (deny lsopen)
                (deny signal (require-not (target same-sandbox)))
                """, HarnessSandbox.scriptMacosProfile(Path.of("/h/u/jc/data/scripts/run-1"), Path.of("/opt/node/24.22.1"),
                "/h/u", hidden));
    }

    /**
     * A script's bwrap argv is a custom agent's: its own user, PID and network namespaces and session, its own
     * runtime folders before the $HOME tmpfs, the scratch folder its one read-write bind, and the interpreter folder
     * bound read-only after data/'s tmpfs. As root it also drops its capabilities, and where no procfs mounts it
     * keeps the outer /proc.
     */
    @Test
    void scriptLinuxArgvHasNoNetworkAndBindsTheInterpreterFolderAfterDataIsHidden() {
        var hidden = List.of(Path.of("/srv/jc/data"), Path.of("/srv/jc/certs"));
        var scratch = Path.of("/srv/scripts/run-1");
        var interpreter = Path.of("/srv/jc/data/playwright-node/24.22.1-linux-x64");
        var node = List.of("/srv/jc/data/playwright-node/24.22.1-linux-x64/bin/node", "-");
        assertEquals(List.of("bwrap", "--unshare-user", "--unshare-net", "--unshare-pid", "--new-session",
                        "--ro-bind", "/", "/", "--dev", "/dev", "--tmpfs", "/tmp", "--proc", "/proc", "--tmpfs", "/run",
                        "--tmpfs", "/home/op", "--bind", "/srv/scripts/run-1", "/srv/scripts/run-1",
                        "--tmpfs", "/srv/jc/data", "--tmpfs", "/srv/jc/certs",
                        "--ro-bind-try", "/srv/jc/data/playwright-node/24.22.1-linux-x64",
                        "/srv/jc/data/playwright-node/24.22.1-linux-x64",
                        "/srv/jc/data/playwright-node/24.22.1-linux-x64/bin/node", "-"),
                HarnessSandbox.scriptLinuxArgv(node, scratch, interpreter, "/home/op", hidden, false,
                        List.of("--tmpfs", "/run"), HarnessSandbox.LinuxPids.OWN));
        assertEquals(List.of("bwrap", "--unshare-user", "--cap-drop", "ALL", "--unshare-net", "--unshare-pid",
                        "--new-session", "--ro-bind", "/", "/", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/run",
                        "--tmpfs", "/root", "--bind", "/srv/scripts/run-1", "/srv/scripts/run-1",
                        "--ro-bind-try", "/opt/node/24.22.1", "/opt/node/24.22.1",
                        "--tmpfs", "/srv/jc/data", "--tmpfs", "/srv/jc/certs", "/opt/node/24.22.1/bin/node", "-"),
                HarnessSandbox.scriptLinuxArgv(List.of("/opt/node/24.22.1/bin/node", "-"), scratch,
                        Path.of("/opt/node/24.22.1"), "/root", hidden, true, List.of("--tmpfs", "/run"),
                        HarnessSandbox.LinuxPids.OWN_UNDER_OUTER_PROC));
    }

    /**
     * A scratch or interpreter folder that holds the home folder, one of JClaw's hidden folders or a secret folder,
     * or lies in a secret folder, would show a script what its sandbox hides, so it is refused.
     */
    @Test
    void aScriptFolderThatWouldReopenWhatTheSandboxHidesIsRefused() {
        var hidden = List.of(Path.of("/h/u/jc/data"), Path.of("/h/u/jc/certs"));
        assertNull(HarnessSandbox.scriptFolderRejection("interpreter folder",
                Path.of("/h/u/jc/data/playwright-node/24.22.1-darwin-arm64"), "/h/u", hidden));
        assertNull(HarnessSandbox.scriptFolderRejection("scratch folder", Path.of("/h/u/jc/workspace/a/run-1"), "/h/u", hidden));
        assertNull(HarnessSandbox.scriptFolderRejection("scratch folder", Path.of("/private/var/folders/x/T/run-1"), "/h/u", hidden));
        assertEquals("the interpreter folder /h/u/jc/data holds /h/u/jc/data, which a script never reads",
                HarnessSandbox.scriptFolderRejection("interpreter folder", Path.of("/h/u/jc/data"), "/h/u", hidden));
        assertEquals("the interpreter folder /h/u/jc holds /h/u/jc/data, which a script never reads",
                HarnessSandbox.scriptFolderRejection("interpreter folder", Path.of("/h/u/jc"), "/h/u", hidden));
        assertEquals("the scratch folder /h/u holds the home folder",
                HarnessSandbox.scriptFolderRejection("scratch folder", Path.of("/h/u"), "/h/u", hidden));
        assertEquals("the scratch folder / holds the home folder",
                HarnessSandbox.scriptFolderRejection("scratch folder", Path.of("/"), "/h/u", hidden));
        assertEquals("the interpreter folder /h/u/.ssh/node is or holds /h/u/.ssh, which a script never reads",
                HarnessSandbox.scriptFolderRejection("interpreter folder", Path.of("/h/u/.ssh/node"), "/h/u", hidden));
        assertEquals("the scratch folder /h/u/.config is or holds /h/u/.config/gcloud, which a script never reads",
                HarnessSandbox.scriptFolderRejection("scratch folder", Path.of("/h/u/.config"), "/h/u", hidden));
        assertEquals("the scratch folder run-1 is not an absolute path",
                HarnessSandbox.scriptFolderRejection("scratch folder", Path.of("run-1"), "/h/u", hidden));
    }

    /** The folders are checked before the sandbox is: a refused folder is refused with the sandbox replaced too. */
    @Test
    void wrapScriptRefusesAFolderThatHoldsWhatItHidesEvenWithTheSandboxReplaced() throws Exception {
        var interpreter = Files.createDirectory(session.resolve("node"));
        ShellSandboxSync.acquireUnconfined();
        try {
            var home = SandboxHiddenRoots.canonical(System.getProperty("user.home"));
            var e = assertThrows(HarnessSandbox.SandboxUnavailableException.class,
                    () -> HarnessSandbox.wrapScript(List.of("node"), home, interpreter));
            assertEquals("the scratch folder " + home + " holds the home folder", e.getMessage());
            var data = SandboxHiddenRoots.canonical(Play.applicationPath.toPath().resolve("data"));
            e = assertThrows(HarnessSandbox.SandboxUnavailableException.class,
                    () -> HarnessSandbox.wrapScript(List.of("node"), session, data));
            assertEquals("the interpreter folder " + data + " holds " + data + ", which a script never reads", e.getMessage());
            assertEquals(List.of("node"), HarnessSandbox.wrapScript(List.of("node"), session, interpreter));
        } finally {
            ShellSandboxSync.release();
        }
    }

    /**
     * A script is confined the same for every value of shell.sandbox and shell.sandbox.network: on macOS by exactly
     * scriptMacosProfile, on Linux with the network unshared, and where no sandbox starts it is refused with the
     * readiness probe's reason.
     */
    @Test
    void aScriptIsConfinedTheSameWhateverTheShellKeysSay() throws Exception {
        var scratch = Files.createDirectory(session.resolve("scratch"));
        var interpreter = Files.createDirectory(session.resolve("node"));
        var argv = List.of("/usr/bin/true");
        ShellSandboxSync.acquire();
        try {
            var ready = HarnessSandbox.availability();
            List<String> first = null;
            for (var shell : List.of("", "false", "true", "untrusted")) {
                for (var network : List.of("", "allow", "deny")) {
                    ConfigService.set(HarnessSandbox.SHELL_SANDBOX_KEY, shell);
                    ConfigService.set(HarnessSandbox.SHELL_NETWORK_KEY, network);
                    var keys = "shell.sandbox=" + shell + ", network=" + network;
                    if (!ready.available()) {
                        var e = assertThrows(HarnessSandbox.SandboxUnavailableException.class,
                                () -> HarnessSandbox.wrapScript(argv, scratch, interpreter), keys);
                        assertEquals(ready.reason(), e.getMessage(), keys);
                        continue;
                    }
                    var wrapped = HarnessSandbox.wrapScript(argv, scratch, interpreter);
                    if (first == null) first = wrapped;
                    assertEquals(first, wrapped, keys);
                }
            }
            if (first == null) return;
            if (ready.mechanism().equals("sandbox-exec")) {
                assertEquals(List.of("sandbox-exec", "-p", HarnessSandbox.scriptMacosProfile(
                        SandboxHiddenRoots.canonical(scratch), SandboxHiddenRoots.canonical(interpreter),
                        System.getProperty("user.home"), SandboxHiddenRoots.current()), "/usr/bin/true"), first);
            } else {
                assertEquals(List.of("bwrap", "--unshare-user"), first.subList(0, 2), "argv=" + first);
                assertTrue(first.contains("--unshare-net"), "argv=" + first);
                assertEquals(argv, first.subList(first.size() - 1, first.size()), "argv=" + first);
            }
        } finally {
            ShellSandboxSync.release();
        }
    }

    /**
     * JF-324: the secrets proxy's trust folder lives in the temp folder, which a Linux sandbox replaces with an empty
     * one. It is passed as an allowance, so the allowance must be bound after that temp folder or it would be hidden.
     */
    @Test
    void aLinuxSandboxBindsAnAllowanceAfterItsOwnTempFolder() {
        var argv = HarnessSandbox.linuxArgv(List.of("true"), new File("/srv/ws"), List.of("/tmp/jclaw-secrets-proxy-1"),
                "/home/op", List.of(), false);
        int temp = argv.indexOf("/tmp");
        assertEquals("--tmpfs", argv.get(temp - 1));
        int bind = argv.indexOf("--ro-bind-try");
        assertTrue(bind > temp, "the allowance is bound before the temp folder that would hide it: " + argv);
        assertTrue(argv.get(bind + 1).endsWith("/jclaw-secrets-proxy-1"), argv.toString());
        assertEquals(argv.get(bind + 1), argv.get(bind + 2));
    }
}
