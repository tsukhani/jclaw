import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs jclaw.sh's {@code funnel_reset_kept} and {@code restart_on_exit} against a fake {@code tailscale} (JF-269).
 * A restart's stop leaves the Tailscale Funnel on and records it in {@code data/tailscale-funnel.kept}; a restart
 * that then fails, with no JVM of the install running, must take it down.
 *
 * <p>{@code tailscale funnel reset} clears the whole machine's serve config, and this machine may run the real
 * binary, so the harness points the macOS app fallback at nothing, refuses to run when {@code tailscale} resolves to
 * anything but the fake, and runs on a PATH of one directory: the fake and links to {@link #TOOLS}. The system
 * directories stay off it because a Linux host keeps the real binary in {@code /usr/bin} (Jenkins build #1404).
 */
class FunnelKeptScriptTest extends UnitTest {
    private static final List<String> FUNCTIONS = List.of("funnel_reset_kept", "restart_status", "restart_on_exit");
    /** The external commands {@link #FUNCTIONS} run; the rest are bash builtins. */
    private static final List<String> TOOLS = List.of("cat", "rm", "date", "mv");

    @TempDir Path tmp;

    private record Run(int exit, String out, String calls) {}

    @Test
    void aKeptFunnelWithNoJvmRunningIsResetAndItsRecordRemoved() throws Exception {
        var kept = keep();

        var run = run("funnel_reset_kept", 0, true);

        assertEquals("==> Switched the Tailscale Funnel off, as JClaw is not running\n"
                + "note=[the Tailscale Funnel was switched off]\n", run.out());
        assertEquals("tailscale funnel reset\n", run.calls());
        assertFalse(Files.exists(kept));
    }

    @Test
    void aPidFileWhoseJvmHasExitedCountsAsNoJvm() throws Exception {
        var kept = keep();
        var gone = new ProcessBuilder("true").start();
        assertTrue(gone.waitFor(10, TimeUnit.SECONDS));
        Files.writeString(tmp.resolve("server.pid"), gone.pid() + "\n");

        var run = run("funnel_reset_kept", 0, true);

        assertEquals("tailscale funnel reset\n", run.calls());
        assertFalse(Files.exists(kept));
    }

    @Test
    void aJvmStillRunningKeepsTheFunnelItWillTakeOver() throws Exception {
        var kept = keep();
        Files.writeString(tmp.resolve("server.pid"), ProcessHandle.current().pid() + "\n");

        var run = run("funnel_reset_kept", 0, true);

        assertEquals("note=[]\n", run.out());
        assertEquals("", run.calls());
        assertTrue(Files.exists(kept));
    }

    @Test
    void aResetThatFailsKeepsTheRecordAndSaysSo() throws Exception {
        var kept = keep();

        var run = run("funnel_reset_kept", 1, true);

        assertEquals("Warning: JClaw is not running, but the Tailscale Funnel could not be switched off.\n"
                + "         Run 'tailscale funnel reset' to stop publishing this machine.\n"
                + "note=[the Tailscale Funnel could not be switched off]\n", run.out());
        assertEquals("tailscale funnel reset\n", run.calls());
        assertTrue(Files.exists(kept), "kept, so the next stop or boot still knows the funnel is on");
    }

    @Test
    void noTailscaleToRunIsAFailedReset() throws Exception {
        var kept = keep();

        var run = run("funnel_reset_kept", 0, false);

        assertEquals("Warning: JClaw is not running, but the Tailscale Funnel could not be switched off.\n"
                + "         Run 'tailscale funnel reset' to stop publishing this machine.\n"
                + "note=[the Tailscale Funnel could not be switched off]\n", run.out());
        assertTrue(Files.exists(kept));
    }

    @Test
    void nothingKeptIsANoOp() throws Exception {
        var run = run("funnel_reset_kept", 0, true);

        assertEquals("note=[]\n", run.out());
        assertEquals("", run.calls());
    }

    @Test
    void aFailedRestartResetsTheFunnelAndItsStatusSaysSo() throws Exception {
        var kept = keep();
        var status = tmp.resolve("logs/restart-status.json");

        var run = run("false || restart_on_exit", 0, true);

        assertEquals("tailscale funnel reset\n", run.calls());
        assertFalse(Files.exists(kept));
        assertEquals("{\"startedAt\":1800000000,\"state\":\"failed\","
                        + "\"reason\":\"JClaw did not start; the Tailscale Funnel was switched off\",\"finishedAt\":",
                Files.readString(status).replaceAll("\"finishedAt\":\\d+.*", "\"finishedAt\":").strip());
    }

    @Test
    void aRestartThatSucceededLeavesTheFunnelToTheNewJvm() throws Exception {
        var kept = keep();

        var run = run("true && restart_on_exit", 0, true);

        assertEquals("", run.calls());
        assertTrue(Files.exists(kept));
    }

    private Path keep() throws Exception {
        return Files.writeString(Files.createDirectories(tmp.resolve("data")).resolve("tailscale-funnel.kept"), "");
    }

    private Run run(String command, int tailscaleExit, boolean withTailscale) throws Exception {
        var bin = Files.createDirectories(tmp.resolve("bin"));
        for (var tool : TOOLS) {
            var link = bin.resolve(tool);
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) continue;
            var target = Stream.of("/bin", "/usr/bin").map(dir -> Path.of(dir, tool)).filter(Files::isExecutable)
                    .findFirst().orElseThrow(() -> new AssertionError("no " + tool + " in /bin or /usr/bin"));
            Files.createSymbolicLink(link, target);
        }
        var calls = tmp.resolve("calls.log");
        Files.writeString(calls, "");
        if (withTailscale) {
            var fake = bin.resolve("tailscale");
            Files.writeString(fake, "#!/bin/sh\necho \"tailscale $*\" >> \"" + calls + "\"\nexit " + tailscaleExit + "\n");
            Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Files.createDirectories(tmp.resolve("logs"));
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath()).resolve("jclaw.sh"));
        var harness = new StringBuilder("set -euo pipefail\n")
                .append("found=$(command -v tailscale || true)\n")
                .append("[ \"$found\" = \"").append(withTailscale ? bin.resolve("tailscale") : "").append("\" ] || { echo \"refusing: tailscale is $found\"; exit 99; }\n")
                .append("SCRIPT_DIR='").append(tmp).append("'\n")
                .append("TAILSCALE_MAC_APP='").append(tmp.resolve("no-such-app/Tailscale")).append("'\n")
                .append("FUNNEL_RESET_NOTE=\"\"\n")
                .append("RESTART_STATUS_FILE='").append(tmp.resolve("logs/restart-status.json")).append("'\n")
                .append("RESTART_STARTED=1800000000\nRESTART_DONE=\"\"\nRESTART_PHASE_ID=\"\"\n")
                .append("RESTART_PHASE_FAILS=\"JClaw did not start\"\nRESTART_FAIL_REASON=\"\"\n")
                .append("narrator_start() { :; }\nnarrator_stop() { :; }\n");
        for (var name : FUNCTIONS) {
            var m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
            assertTrue(m.find(), "jclaw.sh no longer defines " + name);
            harness.append(m.group());
        }
        harness.append(command).append('\n');
        if (command.equals("funnel_reset_kept")) harness.append("printf 'note=[%s]\\n' \"$FUNNEL_RESET_NOTE\"\n");
        var pb = new ProcessBuilder("/bin/bash", "-c", harness.toString()).redirectErrorStream(true);
        pb.environment().put("PATH", bin.toString());
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the harness hung");
        assertNotEquals(99, p.exitValue(), out);
        return new Run(p.exitValue(), out, Files.readString(calls));
    }
}
