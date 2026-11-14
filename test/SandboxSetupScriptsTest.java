import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;
import tools.HarnessSandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * JF-413: install.sh and install-docker.sh put the agent sandbox's pieces in place. They carry one block word for
 * word, the bwrap AppArmor profile in it matches INSTALL.md's, and their probes are HarnessSandbox's. The blocks run
 * in /bin/sh with a PATH of stubs and the few real tools they use, so no host bwrap, sudo or docker is ever reached.
 */
class SandboxSetupScriptsTest extends UnitTest {

    private static final List<String> TOOLS = List.of("id", "cat", "tee", "head", "grep", "awk", "cp", "chmod");

    @TempDir Path tmp;
    private Path stubs;
    private Path tty;
    private Path restrict;
    private Path profile;
    private Path calls;

    private record Run(int exit, String out) {}

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(Play.applicationPath.getAbsolutePath()).resolve(file));
    }

    private static String block(String script, String name) throws IOException {
        var m = Pattern.compile("(?ms)^# >>> " + Pattern.quote(name) + " .*?^# <<< " + Pattern.quote(name) + "\\n").matcher(read(script));
        assertTrue(m.find(), script + " no longer has its " + name + " block");
        return m.group();
    }

    private static String quoted(String script, String variable) throws IOException {
        var m = Pattern.compile("(?ms)^" + variable + "='(.*?)'$").matcher(read(script));
        assertTrue(m.find(), script + " no longer sets " + variable);
        return m.group(1);
    }

    @BeforeEach
    void stubsAndTools() throws Exception {
        stubs = Files.createDirectories(tmp.resolve("stubs"));
        for (var tool : TOOLS) {
            var real = new ProcessBuilder("/bin/sh", "-c", "command -v " + tool).start();
            var path = new String(real.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            real.waitFor();
            assertFalse(path.isEmpty(), tool + " is not on this host");
            Files.createSymbolicLink(stubs.resolve(tool), Path.of(path));
        }
        tty = tmp.resolve("tty");
        restrict = tmp.resolve("restrict");
        profile = tmp.resolve("apparmor.d-bwrap");
        calls = tmp.resolve("calls");
        Files.writeString(calls, "");
        stub("parser", "echo \"apparmor_parser $*\" >>" + calls);
    }

    private void stub(String name, String body) throws IOException {
        var file = stubs.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Run run(String blocks, String call, Map<String, String> extraEnv) throws Exception {
        var harness = "step() { printf '==> %s\\n' \"$1\"; }\nsubstep() { printf '    %s\\n' \"$1\"; }\n"
                + "warn() { printf 'warning: %s\\n' \"$1\"; }\ndie() { printf 'error: %s\\n' \"$1\"; exit 1; }\n"
                + "set -eu\n" + blocks + call + "\n";
        var pb = new ProcessBuilder("/bin/sh", "-c", harness).directory(tmp.toFile()).redirectErrorStream(true);
        var env = pb.environment();
        env.clear();
        env.put("PATH", stubs.toString());
        env.put("JCLAW_TTY", tty.toString());
        env.put("JCLAW_USERNS_RESTRICT", restrict.toString());
        env.put("JCLAW_BWRAP_PROFILE_PATH", profile.toString());
        env.put("JCLAW_SUDO", "");
        env.put("JCLAW_APPARMOR_PARSER", stubs.resolve("parser").toString());
        env.putAll(extraEnv);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the harness hung");
        return new Run(p.exitValue(), out);
    }

    private Run docker(String call) throws Exception {
        return run(block("install-docker.sh", "agent sandbox") + block("install-docker.sh", "docker sandbox"), call, Map.of());
    }

    private Run nativeSetup(Map<String, String> env) throws Exception {
        return run(block("install.sh", "agent sandbox") + block("install.sh", "native sandbox") + "OS=linux\n", "setup_sandbox", env);
    }

    // ==================== one source of truth ====================

    @Test
    void bothScriptsCarryTheSameSandboxBlock() throws Exception {
        assertEquals(block("install.sh", "agent sandbox"), block("install-docker.sh", "agent sandbox"));
    }

    @Test
    void theProfileIsInstallsWordForWord() throws Exception {
        var m = Pattern.compile("(?ms)^sudo tee /etc/apparmor\\.d/bwrap >/dev/null <<'EOF'\\n(.*?)\\nEOF$").matcher(read("INSTALL.md"));
        assertTrue(m.find(), "INSTALL.md no longer gives the profile");
        assertEquals(m.group(1), quoted("install.sh", "BWRAP_PROFILE"));
    }

    @Test
    void theProbesAreJClawsOwn() throws Exception {
        assertEquals(String.join(" ", HarnessSandbox.BWRAP_PROBE.subList(1, HarnessSandbox.BWRAP_PROBE.size())),
                quoted("install.sh", "PROBE"));
        assertEquals(String.join(" ", HarnessSandbox.BWRAP_PROBE_AS_ROOT.subList(1, HarnessSandbox.BWRAP_PROBE_AS_ROOT.size())),
                quoted("install-docker.sh", "PROBE_AS_ROOT"));
    }

    // ==================== the host's AppArmor profile ====================

    @Test
    void aRestrictingHostGetsTheProfileWhenTheOperatorSaysYes() throws Exception {
        Files.writeString(restrict, "1\n");
        Files.writeString(tty, "y\n");

        var run = docker("ensure_bwrap_profile");

        assertEquals(0, run.exit(), run.out());
        assertEquals(quoted("install.sh", "BWRAP_PROFILE") + "\n", Files.readString(profile));
        assertEquals("apparmor_parser -r " + profile + "\n", Files.readString(calls));
        assertEquals("y\n    Add " + profile + " with sudo? [y/N] ", Files.readString(tty));
        assertEquals("    this host restricts user namespaces (Ubuntu 23.10 or later), so bwrap needs an AppArmor profile\n"
                + "    added " + profile + "\n", run.out());
    }

    @Test
    void aNoLeavesTheHostAloneAndSaysWhereTheCommandsAre() throws Exception {
        Files.writeString(restrict, "1\n");
        Files.writeString(tty, "n\n");

        var run = docker("ensure_bwrap_profile || echo refused");

        assertFalse(Files.exists(profile));
        assertEquals("", Files.readString(calls));
        assertEquals("    this host restricts user namespaces (Ubuntu 23.10 or later), so bwrap needs an AppArmor profile\n"
                + "    not added. INSTALL.md (Ubuntu 23.10 and later) gives the two commands that add it.\nrefused\n", run.out());
    }

    @Test
    void anEmptyAnswerTakesTheDefaultNo() throws Exception {
        Files.writeString(restrict, "1\n");
        Files.writeString(tty, "\n");

        docker("ensure_bwrap_profile || true");

        assertFalse(Files.exists(profile));
    }

    @Test
    void withNoTerminalNothingIsAdded() throws Exception {
        Files.writeString(restrict, "1\n");
        var run = run(block("install-docker.sh", "agent sandbox"), "ensure_bwrap_profile || echo refused",
                Map.of("JCLAW_TTY", tmp.resolve("no-terminal").toString()));

        assertFalse(Files.exists(profile));
        assertEquals("", Files.readString(calls));
        assertTrue(run.out().endsWith("refused\n"), run.out());
    }

    @Test
    void aHostThatDoesNotRestrictOrAlreadyHasTheProfileIsNotAsked() throws Exception {
        Files.writeString(tty, "y\n");
        Files.writeString(restrict, "0\n");
        assertEquals("", docker("ensure_bwrap_profile").out());

        Files.writeString(restrict, "1\n");
        Files.writeString(profile, "the operator's own\n");
        assertEquals("", docker("ensure_bwrap_profile").out());
        assertEquals("the operator's own\n", Files.readString(profile));
        assertEquals("y\n", Files.readString(tty), "no question was asked");
    }

    // ==================== the operator's compose file ====================

    private static final String SHIPPED = "services:\n  jclaw:\n    restart: unless-stopped\n    security_opt:\n"
            + "      - seccomp=./seccomp-bwrap.json\n    healthcheck:\n      test: [\"CMD\", \"true\"]\n";

    @Test
    void theShippedComposeGainsItsAppArmorLineAndKeepsTheOldFile() throws Exception {
        Files.writeString(tmp.resolve("docker-compose.yml"), SHIPPED);

        var run = docker("ensure_compose_apparmor");

        assertEquals(0, run.exit(), run.out());
        assertEquals(SHIPPED.replace("      - seccomp=./seccomp-bwrap.json\n",
                "      - seccomp=./seccomp-bwrap.json\n      - apparmor=unconfined\n"), Files.readString(tmp.resolve("docker-compose.yml")));
        assertEquals(SHIPPED, Files.readString(tmp.resolve("docker-compose.yml.bak")));
        assertEquals("    docker-compose.yml: added apparmor=unconfined under security_opt (the old file is docker-compose.yml.bak)\n",
                run.out());
    }

    @Test
    void aComposeThatAlreadyLiftsAppArmorIsUnchanged() throws Exception {
        var set = SHIPPED.replace("./seccomp-bwrap.json\n", "./seccomp-bwrap.json\n      - apparmor=unconfined\n");
        Files.writeString(tmp.resolve("docker-compose.yml"), set);

        var run = docker("ensure_compose_apparmor");

        assertEquals("    docker-compose.yml: apparmor=unconfined is set\n", run.out());
        assertEquals(set, Files.readString(tmp.resolve("docker-compose.yml")));
        assertFalse(Files.exists(tmp.resolve("docker-compose.yml.bak")));
    }

    @Test
    void aComposeLaidOutDifferentlyIsLeftAloneAndTheLinesAreNamed() throws Exception {
        var custom = "services:\n  jclaw:\n    security_opt: [\"seccomp=/etc/jclaw/seccomp.json\"]\n";
        Files.writeString(tmp.resolve("docker-compose.yml"), custom);

        var run = docker("ensure_compose_apparmor || echo refused");

        assertEquals(custom, Files.readString(tmp.resolve("docker-compose.yml")));
        assertEquals("warning: docker-compose.yml is not laid out as the shipped one, so it was left alone. Give the jclaw service:\n"
                + "    security_opt:\n      - seccomp=./seccomp-bwrap.json\n      - apparmor=unconfined\nrefused\n", run.out());
    }

    // ==================== the container check and the start ====================

    @Test
    void eachRefusalNamesTheLayerThatRefused() throws Exception {
        var hints = new HashMap<String, String>();
        hints.put("bwrap: setting up uid map: Permission denied",
                "the host restricts user namespaces: add the bwrap AppArmor profile (INSTALL.md, Ubuntu 23.10 and later)");
        hints.put("bwrap: Failed to make / slave: Permission denied",
                "Docker's own AppArmor profile refused it: the container needs apparmor=unconfined");
        hints.put("bwrap: No permissions to create a new namespace, likely because the kernel does not allow it",
                "Docker's default seccomp profile refused it: the container needs seccomp-bwrap.json");
        for (var e : hints.entrySet()) {
            stub("docker", "echo '" + e.getKey() + "' >&2; exit 1");
            var run = docker("probe_container jclaw:test /tmp/seccomp.json || echo refused");
            assertEquals("warning: the agent sandbox cannot start in this container: " + e.getKey() + "\n    " + e.getValue()
                    + "\nrefused\n", run.out());
        }
    }

    @Test
    void theContainerCheckRunsJClawsProbeUnderBothSettings() throws Exception {
        stub("docker", "echo \"docker $*\" >>" + calls);

        assertEquals(0, docker("probe_container jclaw:test /tmp/seccomp.json").exit());
        assertEquals("docker run --rm --security-opt seccomp=/tmp/seccomp.json --security-opt apparmor=unconfined --entrypoint sh"
                + " jclaw:test -c exec bwrap \"$@\" sh " + quoted("install-docker.sh", "PROBE_AS_ROOT") + "\n", Files.readString(calls));
    }

    @Test
    void aRunningJClawIsRestartedOnlyOnYes() throws Exception {
        stub("docker", "case \"$*\" in 'compose ps'*) echo jclaw ;; *) echo \"docker $*\" >>" + calls + " ;; esac");
        Files.writeString(tty, "n\n");

        var no = docker("VERSION=v1.0.15; start_compose");

        assertEquals("    start it with: docker compose up -d\n", no.out());
        assertEquals("", Files.readString(calls));
        assertEquals("n\n    Restart JClaw on this image now? This replaces the running container. [Y/n] ", Files.readString(tty));

        Files.writeString(tty, "y\n");
        var yes = docker("VERSION=v1.0.15; start_compose");

        assertEquals("==> Started v1.0.15\n", yes.out());
        assertEquals("docker compose up -d\n", Files.readString(calls));
    }

    @Test
    void aStoppedJClawStartsOnTheDefaultAnswerUnlessNoStartIsSet() throws Exception {
        stub("docker", "case \"$*\" in 'compose ps'*) ;; *) echo \"docker $*\" >>" + calls + " ;; esac");
        Files.writeString(tty, "\n");

        assertEquals("==> Started v1.0.15\n", docker("VERSION=v1.0.15; start_compose").out());
        assertEquals("\n    Start JClaw now? [Y/n] ", Files.readString(tty));

        Files.writeString(calls, "");
        var run = run(block("install-docker.sh", "agent sandbox") + block("install-docker.sh", "docker sandbox"),
                "VERSION=v1.0.15; start_compose", Map.of("JCLAW_NO_START", "1"));
        assertEquals("    start it with: docker compose up -d\n", run.out());
        assertEquals("", Files.readString(calls));
    }

    // ==================== the native install ====================

    @Test
    void aMissingBwrapIsInstalledOnYesAndTheCheckPasses() throws Exception {
        stub("apt-get", "echo \"apt-get $*\" >>" + calls + "; printf '#!/bin/sh\\nexit 0\\n' >" + stubs.resolve("bwrap")
                + "; chmod +x " + stubs.resolve("bwrap"));
        Files.writeString(tty, "y\n");

        var run = nativeSetup(Map.of());

        assertEquals("apt-get install -y bubblewrap\n", Files.readString(calls));
        assertEquals("==> Checking the agent sandbox\n    installed bubblewrap\n    sandbox ok\n", run.out());
    }

    @Test
    void aMissingBwrapWithNoTerminalIsNamedAndTheInstallGoesOn() throws Exception {
        stub("apt-get", "echo \"apt-get $*\" >>" + calls);

        var run = nativeSetup(Map.of("JCLAW_TTY", tmp.resolve("no-terminal").toString()));

        assertEquals(0, run.exit(), run.out());
        assertEquals("", Files.readString(calls));
        assertEquals("==> Checking the agent sandbox\nwarning: bubblewrap is not installed, so every agent's shell command will be"
                + " refused. Install it, then see INSTALL.md (The agent sandbox on Linux).\n", run.out());
    }

    @Test
    void aRefusedNativeCheckNamesTheUbuntuProfile() throws Exception {
        stub("bwrap", "echo 'bwrap: setting up uid map: Permission denied' >&2; exit 1");

        var run = nativeSetup(Map.of());

        assertEquals(0, run.exit(), run.out());
        assertEquals("==> Checking the agent sandbox\nwarning: the agent sandbox cannot start, so every agent's shell command will be"
                + " refused: bwrap: setting up uid map: Permission denied\n    this host restricts user namespaces: add the bwrap"
                + " AppArmor profile (INSTALL.md, Ubuntu 23.10 and later)\n", run.out());
    }

    @Test
    void macOSNeedsNothing() throws Exception {
        var run = run(block("install.sh", "agent sandbox") + block("install.sh", "native sandbox") + "OS=macos\n",
                "setup_sandbox", Map.of());
        assertEquals("", run.out());
    }

}
