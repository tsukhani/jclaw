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
import tools.ClaudeAdapter;
import tools.GenericAdapter;
import tools.HarnessSandbox;
import tools.PiAdapter;
import utils.SandboxHiddenRoots;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;


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

    @Test
    void availabilityNamesThePlatformsMechanism() {
        var a = HarnessSandbox.availability();
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) assertEquals("bwrap", a.mechanism());
        if (os.contains("mac")) assertEquals("sandbox-exec", a.mechanism());
        if (!a.available()) assertFalse(a.reason().isBlank(), "an unavailable mechanism must say why");
        if (!a.mechanism().isEmpty()) {
            var onPath = Arrays.stream(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                    .anyMatch(dir -> !dir.isEmpty() && Files.isExecutable(Path.of(dir, a.mechanism())));
            assertEquals(onPath, a.available(), () -> a.mechanism() + " on PATH=" + onPath + ", reported " + a);
        }
    }

    /**
     * bwrap applies mounts in argument order and a tmpfs over an ancestor hides every earlier
     * bind beneath it, so a write root under $HOME is reachable only if it is bound after the
     * $HOME tmpfs. Pinned on every host because no Linux test can run the real thing here.
     */
    @Test
    void linuxBindsTheWriteRootAfterTheHomeTmpfs() {
        var home = System.getProperty("user.home");
        var writeRoot = Path.of(home, "workspace", "agent").toFile();
        var argv = HarnessSandbox.linuxArgv(List.of("/bin/sh", "-c", "true"), writeRoot, List.of("/opt/state"));

        int homeTmpfs = indexOfPair(argv, "--tmpfs", home);
        assertTrue(homeTmpfs >= 0, "the $HOME tmpfs is missing, argv=" + argv);
        assertTrue(indexOfPair(argv, "--bind", writeRoot.getAbsolutePath()) > homeTmpfs,
                "the write-root bind must follow the $HOME tmpfs, argv=" + argv);
        assertTrue(indexOfPair(argv, "--ro-bind-try", "/opt/state") > homeTmpfs,
                "allowances must follow the $HOME tmpfs, argv=" + argv);
        assertEquals(List.of("/bin/sh", "-c", "true"), argv.subList(argv.size() - 3, argv.size()));
    }

    private static int indexOfPair(List<String> argv, String flag, String value) {
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (flag.equals(argv.get(i)) && value.equals(argv.get(i + 1))) return i;
        }
        return -1;
    }

    /** A fake install at {@code root}: its three state directories, canonical as production passes them. */
    private static List<Path> stateOf(Path root) {
        return List.of(root.resolve("data"), root.resolve("certs"), root.resolve("conf"));
    }

    /**
     * JCLAW-1452 AC3: the $HOME tmpfs hides a checkout under the home directory but nothing
     * outside it, so data/, certs/ and conf/ each get their own tmpfs, after the $HOME one, in
     * both layouts. The write root outside them is still bound.
     */
    @Test
    void linuxHidesJClawStateWhetherOrNotTheCheckoutIsUnderHome() {
        var base = SandboxHiddenRoots.canonical(session);
        var home = base.resolve("home/operator");
        for (var root : List.of(home.resolve("jclaw"), base.resolve("opt/jclaw"))) {
            var writeRoot = root.resolve("workspace/main");
            var argv = HarnessSandbox.linuxArgv(List.of("/bin/sh", "-c", "true"), writeRoot.toFile(),
                    List.of(".claude"), home.toString(), stateOf(root));

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

    /** A write root above data/ (a coding run continuing in the checkout itself) is bound first, so data/'s tmpfs still covers it. */
    @Test
    void linuxHidesStateInsideAnAncestorWriteRoot() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw");
        var argv = HarnessSandbox.linuxArgv(List.of("true"), root.toFile(), List.of(), "/home/operator",
                stateOf(root));
        int bind = indexOfPair(argv, "--bind", root.toString());
        assertTrue(bind >= 0, "argv=" + argv);
        for (var hidden : stateOf(root)) {
            assertTrue(indexOfPair(argv, "--tmpfs", hidden.toString()) > bind,
                    hidden + " must be hidden after the ancestor bind that would re-expose it, argv=" + argv);
        }
    }

    /** A write root or allowance beneath a hidden root is bound back after that root's tmpfs, or the tmpfs would cover it. */
    @Test
    void linuxReopensAGrantBeneathAHiddenRootAfterHidingIt() {
        var root = SandboxHiddenRoots.canonical(session).resolve("jclaw");
        var writeRoot = root.resolve("data/coding/run");
        var allowance = root.resolve("conf/extra");
        var argv = HarnessSandbox.linuxArgv(List.of("true"), writeRoot.toFile(), List.of(allowance.toString()),
                "/home/operator", stateOf(root));
        assertTrue(indexOfPair(argv, "--bind", writeRoot.toString())
                        > indexOfPair(argv, "--tmpfs", root.resolve("data").toString()),
                "argv=" + argv);
        assertTrue(indexOfPair(argv, "--ro-bind-try", allowance.toString())
                        > indexOfPair(argv, "--tmpfs", root.resolve("conf").toString()),
                "argv=" + argv);
    }

    /** The production argv hides this install's own state directories at their canonical paths. */
    @Test
    void linuxProductionArgvHidesThisInstallsState() {
        var argv = HarnessSandbox.linuxArgv(List.of("true"), session.toFile(), List.of());
        var appRoot = Play.applicationPath.toPath();
        assertTrue(Files.isDirectory(appRoot.resolve("conf")), "a checkout always has conf/");
        for (var dir : List.of("data", "certs", "conf")) {
            var path = appRoot.resolve(dir);
            if (!Files.isDirectory(path)) continue;
            assertTrue(indexOfPair(argv, "--tmpfs", SandboxHiddenRoots.canonical(path).toString()) >= 0,
                    dir + "/ must be hidden, argv=" + argv);
        }
    }

    @Test
    void hiddenRootsAreThisInstallsStateAtCanonicalPaths() throws Exception {
        var hidden = SandboxHiddenRoots.current();
        var appRoot = Play.applicationPath.toPath();
        for (var dir : List.of("data", "certs", "conf")) {
            assertTrue(hidden.contains(SandboxHiddenRoots.canonical(appRoot.resolve(dir))), dir + " in " + hidden);
        }
        var link = Files.createSymbolicLink(session.resolve("link"), Files.createDirectory(session.resolve("target")));
        assertEquals(session.toRealPath().resolve("target/data"), SandboxHiddenRoots.canonical(link.resolve("data")),
                "Seatbelt matches resolved paths, so a root reached through a symlink must be denied at its target");
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
                List.of(".claude", root.resolve("workspace").toString()), "/Users/operator", stateOf(root));

        int lastWriteGrant = profile.indexOf("(allow file-write* (subpath \"" + root.resolve("workspace") + "\"))");
        int denyData = profile.indexOf("(deny file-read* file-write* (subpath \"" + root.resolve("data") + "\"))");
        int reopen = profile.indexOf("(allow file-read* file-write* (subpath \"" + writeRoot + "\"))");
        int denySsh = profile.indexOf("(deny file-read* (subpath \"/Users/operator/.ssh\"))");
        assertTrue(lastWriteGrant >= 0 && denyData > lastWriteGrant, profile);
        assertTrue(profile.contains("(deny file-read* file-write* (subpath \"" + root.resolve("certs") + "\"))"), profile);
        assertTrue(profile.contains("(deny file-read* file-write* (subpath \"" + root.resolve("conf") + "\"))"), profile);
        assertTrue(reopen > denyData, "the write root beneath data/ must be reopened after the deny: " + profile);
        assertTrue(denySsh > reopen, "the $HOME secrets must come last: " + profile);
        assertFalse(profile.contains("(allow file-read* file-write* (subpath \"" + root.resolve("workspace") + "\"))"),
                "a grant outside the hidden roots needs no reopen: " + profile);

        var ancestor = HarnessSandbox.macosProfile(root.toFile(), List.of(), "/Users/operator", stateOf(root));
        assertFalse(ancestor.contains("(allow file-read* file-write* (subpath \"" + root + "\"))"),
                "a write root above data/ must not reopen it: " + ancestor);
    }

    /** Sentinel files of this test's own under the app root's data/ and certs/; the caller deletes them. */
    private static List<Path> writeStateSentinels(String content) throws Exception {
        var appRoot = Play.applicationPath.toPath();
        var out = new java.util.ArrayList<Path>();
        for (var dir : List.of("data", "certs")) {
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
     * read the app root's data/ or certs/, while its session folder stays readable and writable.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void macAcpRunCannotReadJClawState() throws Exception {
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
        var agent = "harness-sbx-1452";
        var coding = AgentService.workspacePath(agent).resolve("coding").toAbsolutePath();
        var secret = "jclaw-1452-secret-" + java.util.UUID.randomUUID();
        var sentinels = writeStateSentinels(secret);
        try {
            var folder = CodingRunFolders.open(coding, "read jclaw state", null).dir();
            var env = Play.applicationPath.toPath().resolve("certs/.env");
            var script = "cat '%s' '%s' 2>/dev/null || echo STATE_DENIED; ".formatted(sentinels.get(0), sentinels.get(1))
                    + "cat '%s' >/dev/null 2>&1 && echo ENV_READABLE || echo ENV_DENIED; ".formatted(env)
                    + "echo session-ok > note.txt && cat note.txt";
            var command = List.of("/bin/sh", "-c", script);

            var control = run(command, folder);
            assertTrue(control.contains(secret), "unconfined, the sentinels must be readable or the test proves nothing: " + control);
            Files.delete(folder.resolve("note.txt"));

            var wrapped = HarnessSandbox.wrap(command, folder.toFile(), new ClaudeAdapter(), false);
            assertEquals("sandbox-exec", wrapped.get(0));
            var confined = run(wrapped, folder);
            assertFalse(confined.contains(secret), "data/ and certs/ must be unreadable: " + confined);
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
     * reads and writes both, and still cannot read their sibling in data/.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void macReopensAWriteRootAndAllowanceBeneathData() throws Exception {
        ConfigService.set(HarnessSandbox.ACP_SANDBOX_KEY, "true");
        var data = Files.createDirectories(Play.applicationPath.toPath().resolve("data"));
        var mine = Files.createDirectory(data.resolve("jclaw-1452-sbx-" + java.util.UUID.randomUUID()));
        try {
            var writeRoot = Files.createDirectory(mine.resolve("run"));
            var allowance = Files.createDirectory(mine.resolve("allow"));
            Files.writeString(allowance.resolve("allowed.txt"), "allowance-readable");
            Files.writeString(mine.resolve("sibling.txt"), "sibling-secret");
            var script = "echo written > out.txt && cat out.txt; cat '%s'; ".formatted(allowance.resolve("allowed.txt"))
                    + "cat '%s' 2>/dev/null || echo SIBLING_DENIED".formatted(mine.resolve("sibling.txt"));

            var wrapped = HarnessSandbox.wrap(List.of("/bin/sh", "-c", script), writeRoot.toFile(),
                    List.of(allowance.toString()), HarnessSandbox.ACP_SANDBOX_KEY, false);
            var out = run(wrapped, writeRoot);
            assertTrue(out.contains("written"), "the write root beneath data/ must be writable and readable: " + out);
            assertTrue(Files.exists(writeRoot.resolve("out.txt")), out);
            assertTrue(out.contains("allowance-readable"), "the allowance beneath data/ must be readable: " + out);
            assertFalse(out.contains("sibling-secret"), "the rest of data/ must stay hidden: " + out);
            assertTrue(out.contains("SIBLING_DENIED"), out);
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
}
