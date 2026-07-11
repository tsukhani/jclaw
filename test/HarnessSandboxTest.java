import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import services.ConfigService;
import tools.ClaudeAdapter;
import tools.GenericAdapter;
import tools.HarnessSandbox;
import tools.PiAdapter;
import play.test.UnitTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


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
}
