import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.libs.Crypto;
import play.mvc.CookieDataCodec;
import play.test.UnitTest;
import utils.RestartNarrator;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs jclaw.sh's production restart, {@code do_restart_prod} through {@code do_stop_prod} and {@code do_start_prod}
 * as written, against a scratch install whose {@code play}, {@code pnpm} and {@code npx} are stubs and whose old
 * JVM is a {@code sleep}. The narrator is the real one, run on the compiled class, except on the Windows branch.
 * The stubs ask the narrator what it serves at each phase, so the phases, their order, and when the narrator
 * starts and stops are all read from the outside.
 */
class RestartProgressScriptTest extends UnitTest {

    private static final List<String> FUNCTIONS = List.of("native_path", "installed_classes", "port_answers",
            "wait_for_port_free", "secret_var_name", "env_file_value", "load_env_file", "require_application_secret",
            "jre_javas_in", "use_bundled_jre", "jre_status", "apply_staged_jre",
            "upgrade_install_kind", "aot_cache_file", "aot_store_dir", "aot_java_version", "aot_jdk_has_fix",
            "aot_spare_port", "aot_status", "aot_fingerprint", "aot_prepare",
            "print_startup_error_block", "wait_for_backend_ready",
            "restart_status", "restart_phase_end", "restart_phase", "restart_begin", "restart_finish", "restart_on_exit",
            "narrator_running", "narrator_bound", "restart_shell_pid", "narrator_start", "narrator_stop", "narrator_is",
            "narrator_reap_stale", "restart_narrate", "do_restart_prod", "do_stop_prod", "do_start_prod");

    /** What the stubs leave out of a real start; wait_for_backend_ready stays real where a test asks for it. */
    private static final String STUBBED = """
            # Runs between the narrator's launch and narrator_stop, whose 5 s wait a stub slowed by a loaded host outran.
            check_stale_h2_lock_or_exit() {
                [ -n "${STUB_AWAIT_NARRATOR_START:-}" ] || return 0
                for _ in $(seq 1 300); do [ -s "$STUB_PIDFILE" ] && break; sleep 0.1; done
            }
            ensure_env_for_start() { :; }
            validate_pnpm_pin() { :; }
            prune_heap_dumps() { :; }
            kill_orphan_jvms() { :; }
            aot_check_used() { :; }
            certs_valid() { [[ -n "${STUB_HTTPS:-}" ]]; }
            jre_install_staged() { probe java; echo "java install" >> "$STUB_CALLS"; echo "upgraded 25.0.4 → 25.0.5"; }
            # The narrator's own line lands whenever its process gets to run; this one is when jclaw.sh launched it.
            eval "jclaw_$(declare -f narrator_start)"
            narrator_start() {
                local before="${NARRATOR_JOB:-}"
                jclaw_narrator_start
                if [[ -n "${NARRATOR_JOB:-}" && "$NARRATOR_JOB" != "$before" ]]; then echo "launch narrator" >> "$STUB_CALLS"; fi
            }
            """;

    private static final String READY = "wait_for_backend_ready() { echo ready >> \"$STUB_CALLS\"; }\n";

    // A JVM that reports 25.0.5 for the AOT gate and runs everything else; on Windows the narrator is a sleep.
    private static final String JAVA = """
            #!/bin/bash
            for a in "$@"; do
                if [ "$a" = "-XshowSettings:properties" ]; then echo "    java.version = 25.0.5" >&2; exit 0; fi
            done
            case " $* " in
                *" utils.RestartNarrator "*)
                    printf 'narrator %s\\n' "$*" >> "$STUB_CALLS"
                    printf '%s\\n' "$$" > "$STUB_NARRATOR_JVM"
                    if [ -n "${STUB_FAKE_NARRATOR:-}" ]; then
                        prev=""; for a in "$@"; do [ "$prev" = --pid-file ] && pidfile="${a#W:}"; prev="$a"; done
                        printf '%s\\nbound\\n' "$$" > "$pidfile"
                        exec sleep 300
                    fi ;;
            esac
            exec "$REAL_JAVA" "$@"
            """;

    private static final String PLAY = """
            #!/bin/bash
            echo "play $1" >> "$STUB_CALLS"
            case "$1" in
                stop)
                    # A real stop takes seconds: time for the narrator's JVM to load its classes and write its pid
                    # file, or to fail and exit. On a loaded host the stub's instant stop outran it.
                    if [ -n "${STUB_AWAIT_NARRATOR:-}" ]; then
                        for _ in $(seq 1 100); do [ -s "$STUB_NARRATOR_JVM" ] && break; sleep 0.1; done
                        jvm=$(cat "$STUB_NARRATOR_JVM" 2>/dev/null || true)
                        for _ in $(seq 1 100); do
                            { [ -e "$STUB_PIDFILE" ] || ! kill -0 "$jvm" 2>/dev/null; } && break
                            sleep 0.1
                        done
                    fi
                    if [ -e "$STUB_PIDFILE" ]; then echo "stop finds a narrator" >> "$STUB_CALLS"
                    else echo "stop finds no narrator" >> "$STUB_CALLS"; fi
                    kill "$(cat server.pid)" 2>/dev/null || true
                    rm -f server.pid ;;
                precompile)
                    # As playPrecompile does: the narrator keeps serving with its class files gone.
                    rm -rf precompiled; mkdir -p precompiled/java
                    probe precompile ;;
                aot-gen)
                    probe aot
                    mkdir -p "$(dirname "$STUB_CACHE")"; printf cache > "$STUB_CACHE" ;;
                start)
                    [ -e "$STUB_PIDFILE" ] && echo "a narrator pid file at start" >> "$STUB_CALLS"
                    if (exec 3<>"/dev/tcp/127.0.0.1/$STUB_PORT") 2>/dev/null; then echo "port held at start" >> "$STUB_CALLS"
                    else echo "port free at start" >> "$STUB_CALLS"; fi
                    [ -n "${STUB_START_FAIL:-}" ] && exit 1
                    echo $$ > server.pid ;;
            esac
            """;

    // The narrator's answers once it holds the port: /api/status, then the progress route with the operator's cookie.
    private static final String PROBE = """
            #!/bin/bash
            [ -n "${STUB_FAKE_NARRATOR:-}" ] && { echo "probe $1" >> "$STUB_CALLS"; exit 0; }
            for _ in $(seq 1 150); do grep -qx bound "$STUB_PIDFILE" 2>/dev/null && break; sleep 0.1; done
            status=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$STUB_PORT/api/status")
            progress=$(curl -s -H "Cookie: $STUB_COOKIE" "http://127.0.0.1:$STUB_PORT/api/system/restart/progress")
            printf 'probe %s %s %s\\n' "$1" "$status" "$progress" >> "$STUB_CALLS"
            """;

    private static final String NPX = """
            #!/bin/bash
            echo "npx $*" >> "$STUB_CALLS"
            probe spa
            mkdir -p .output/public && echo spa > .output/public/index.html
            """;

    private static final String SECRET = "a-test-application-secret";

    @TempDir Path tmp;

    private Path home;
    private int port;
    private Process oldJvm;
    private final List<Process> spares = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(which("curl") != null, "the probes need curl");
        home = Files.createDirectories(tmp.resolve("inst/jclaw"));
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(home.resolve("conf/application.conf"), "application.secret=${PLAY_SECRET}\njvm.memory=-Xss1m\n");
        Files.createDirectories(home.resolve("certs"));
        Files.writeString(home.resolve("certs/.env"), "PLAY_SECRET=" + SECRET + "\n");
        Files.createDirectories(home.resolve("logs"));
        port = TestPorts.freeNonEphemeral();
        oldJvm = new ProcessBuilder("sleep", "60").start();
        Files.writeString(home.resolve("server.pid"), oldJvm.pid() + "\n");
    }

    @AfterEach
    void stopWhatIsLeft() throws Exception {
        oldJvm.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        for (var p : spares) p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        // A failed restart's narrator outlives the harness by minutes; each test that leaves one waits for it to bind.
        var pidFile = home.resolve("logs/restart-narrator.pid");
        if (Files.exists(pidFile)) {
            ProcessHandle.of(Long.parseLong(Files.readAllLines(pidFile).getFirst().strip())).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void aPlainRestartIsTheStopThenTheStartAndTheNarratorIsGoneBeforeTheJvmBinds() throws Exception {
        distInstall();
        var run = restart(Map.of("JCLAW_AOT", "off", "STUB_HTTPS", "1", "STUB_AWAIT_NARRATOR", "1"), false);
        assertEquals(0, run.exit(), run.out());

        assertEquals(List.of("stop:Stopping JClaw", "start:Starting JClaw (connecting channels)"), phases(), status().toString());
        assertEquals("done", status().get("state").getAsString());
        var calls = calls();
        int narrator = indexOf(calls, "narrator ");
        // The narrator's own launch line can land anywhere around play stop's; what the stop saw is the fact.
        assertTrue(calls.contains("stop finds a narrator"), "it binds the moment the old JVM lets go: " + calls);
        assertTrue(calls.indexOf("play stop") < calls.indexOf("play start"), calls.toString());
        assertEquals("port free at start", calls.get(calls.indexOf("play start") + 1), "the JVM binds a free port: " + calls);
        assertFalse(calls.contains("a narrator pid file at start"), calls.toString());
        assertEquals(1, calls.stream().filter(c -> c.startsWith("narrator ")).count(), calls.toString());
        assertEquals("narrator -cp " + home.resolve("precompiled/classes.jar") + " utils.RestartNarrator --status "
                        + statusFile() + " --pid-file " + home.resolve("logs/restart-narrator.pid")
                        + " --parent " + run.pid() + " --now " + nowArg(calls) + " --http " + port
                        + " --https 9443 --cert " + home.resolve("certs/host.cert") + " --key " + home.resolve("certs/host.key"),
                calls.get(narrator));
        assertFalse(Files.exists(home.resolve("logs/restart-narrator.pid")));
        assertTrue(Files.readString(home.resolve("logs/restart.log")).contains("Restart progress: serving on port " + port), "the narrator logs to restart.log");
    }

    @Test
    void aStagedJavaAndACacheRebuildAppearInOrderAndTheNarratorServesEachAsItRuns() throws Exception {
        distInstall();
        stageJava();
        var run = restart(Map.of(), false);
        assertEquals(0, run.exit(), run.out());

        assertEquals(List.of("stop:Stopping JClaw", "java:Installing Java 25.0.5", "aot:Rebuilding the startup cache",
                "start:Starting JClaw (connecting channels)"), phases());
        var steps = status().getAsJsonArray("phases");
        for (var step : steps) {
            var s = step.getAsJsonObject();
            assertTrue(s.get("end").getAsLong() >= s.get("start").getAsLong(), s.toString());
        }

        var java = probe("java");
        assertEquals("503", java.status(), "the reconnect check never mistakes the narrator for JClaw");
        assertEquals("narrator", java.progress().get("source").getAsString());
        assertEquals("running", java.progress().get("state").getAsString());
        assertEquals(List.of("done:Stopping JClaw", "running:Installing Java 25.0.5"), steps(java.progress()));

        var aot = probe("aot");
        assertEquals(List.of("done:Stopping JClaw", "done:Installing Java 25.0.5", "running:Rebuilding the startup cache"), steps(aot.progress()));
        assertEquals("upgraded 25.0.4 → 25.0.5", Files.readString(home.resolve("data/java-runtime.status")).strip());
    }

    @Test
    void aSourceCheckoutAddsThePrecompileAndTheWebAppBuild() throws Exception {
        Files.createDirectories(home.resolve("precompiled/java/utils"));
        try (Stream<Path> classes = Files.list(Path.of("build/classes/java/main/utils"))) {
            for (var c : classes.filter(p -> p.getFileName().toString().startsWith("RestartNarrator")).toList()) {
                Files.copy(c, home.resolve("precompiled/java/utils").resolve(c.getFileName()));
            }
        }
        Files.createDirectories(home.resolve("app"));
        Files.createDirectories(home.resolve("frontend"));
        Files.createDirectories(home.resolve("public"));
        Thread.sleep(1100);
        Files.writeString(home.resolve("app/Changed.java"), "class Changed {}\n");

        var run = restart(Map.of("JCLAW_AOT", "off", "STUB_AWAIT_NARRATOR", "1"), false);
        assertEquals(0, run.exit(), run.out());

        assertEquals(List.of("stop:Stopping JClaw", "precompile:Precompiling", "spa:Building the web app",
                "start:Starting JClaw (connecting channels)"), phases());
        // Precompile deleted the class files it runs from; the narrator loaded them all at its start.
        assertEquals(List.of("done:Stopping JClaw", "running:Precompiling"), steps(probe("precompile").progress()));
        assertEquals(List.of("done:Stopping JClaw", "done:Precompiling", "running:Building the web app"), steps(probe("spa").progress()));
    }

    @Test
    void aFileAddedToTheFrontendRebuildsTheWebApp() throws Exception {
        builtSourceCheckout();
        Files.writeString(home.resolve("frontend/public/bass.mp3"), "mp3\n");

        assertTheWebAppIsRebuiltBecause("frontend/public/bass.mp3 changed since public/spa was built");
    }

    @Test
    void aFileDeletedFromTheFrontendRebuildsTheWebApp() throws Exception {
        builtSourceCheckout();
        Files.delete(home.resolve("frontend/public/favicon.ico"));

        assertTheWebAppIsRebuiltBecause("frontend/public/favicon.ico changed since public/spa was built");
    }

    @Test
    void aFileReplacedByOneWithAnOlderStampRebuildsTheWebApp() throws Exception {
        builtSourceCheckout();
        // As mv or a Finder copy leave it: new content under a stamp older than the build.
        var icon = Files.writeString(home.resolve("frontend/public/favicon.ico"), "another icon\n");
        Files.setLastModifiedTime(icon, FileTime.fromMillis(System.currentTimeMillis() - 86_400_000));

        assertTheWebAppIsRebuiltBecause("frontend/public/favicon.ico changed since public/spa was built");
    }

    @Test
    void ignoredFilesInTheFrontendKeepTheBuiltWebApp() throws Exception {
        builtSourceCheckout();
        Files.writeString(home.resolve("frontend/public/.DS_Store"), "finder\n");
        Files.createDirectories(home.resolve("frontend/test-report"));
        Files.writeString(home.resolve("frontend/test-report/index.html"), "<html></html>\n");

        var run = start();
        assertTrue(run.out().lines().toList().contains("==> Skipping the SPA build and frontend dependencies (public/spa is up to date)"), run.out());
        assertEquals(List.of(), calls().stream().filter(c -> c.startsWith("npx ") || c.startsWith("pnpm ")).toList());
    }

    private void assertTheWebAppIsRebuiltBecause(String reason) throws Exception {
        var run = start();
        assertTrue(run.out().lines().toList().contains("==> Generating static SPA (" + reason + ")..."), run.out());
        assertEquals(List.of("npx nuxi generate"), calls().stream().filter(c -> c.startsWith("npx ")).toList());
    }

    /** One start, with no calls left over from an earlier one. */
    private Run start() throws Exception {
        Files.deleteIfExists(tmp.resolve("calls.log"));
        var run = harness(Map.of("JCLAW_AOT", "off", "STUB_FAKE_NARRATOR", "1"), false, "do_start_prod");
        assertEquals(0, run.exit(), run.out());
        return run;
    }

    @Test
    void aFailedStartIsServedAfterJclawShHasExited() throws Exception {
        distInstall();
        var run = restart(Map.of("JCLAW_AOT", "off", "STUB_START_FAIL", "1"), false);
        assertNotEquals(0, run.exit(), run.out());

        assertEquals("failed", status().get("state").getAsString());
        assertEquals("JClaw did not start", status().get("reason").getAsString());
        assertEquals(0, status().getAsJsonArray("phases").get(1).getAsJsonObject().get("end").getAsLong(), "the phase it failed in stays open");

        // The trap starts a narrator once the JVM that failed has gone; it outlives the shell that started it.
        var pidFile = home.resolve("logs/restart-narrator.pid");
        waitFor(() -> lines(pidFile).contains("bound"));
        var answer = get(RestartNarrator.PROGRESS_PATH);
        assertEquals("failed", answer.get("state").getAsString());
        assertEquals("JClaw did not start", answer.get("failure").getAsString());
        assertEquals(List.of("done:Stopping JClaw", "failed:Starting JClaw (connecting channels)"), steps(answer));
        Thread.sleep(1500);
        assertTrue(ProcessHandle.of(Long.parseLong(lines(pidFile).getFirst())).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void theReadyWaitsReasonIsTheOneServed() throws Exception {
        distInstall();
        var run = restart(Map.of("JCLAW_AOT", "off"), true);
        assertNotEquals(0, run.exit(), run.out());
        assertEquals("JClaw exited while it was starting", status().get("reason").getAsString());
        waitFor(() -> lines(home.resolve("logs/restart-narrator.pid")).contains("bound"));
        assertEquals("JClaw exited while it was starting", get(RestartNarrator.PROGRESS_PATH).get("failure").getAsString());
    }

    @Test
    void onWindowsTheNarratorWaitsForAStagedJavaAndIsStartedAndStoppedNatively() throws Exception {
        distInstall();
        stageJava();
        var run = restart(Map.of("IS_WINDOWS", "1", "STUB_FAKE_NARRATOR", "1", "STUB_AWAIT_NARRATOR_START", "1"), false);
        assertEquals(0, run.exit(), run.out());

        // The JVM cache is off on Windows, so its rebuild never appears.
        assertEquals(List.of("stop:Stopping JClaw", "java:Installing Java 25.0.5", "start:Starting JClaw (connecting channels)"), phases());
        var calls = calls();
        int install = calls.indexOf("java install");
        int launch = calls.indexOf("launch narrator");
        int narrator = indexOf(calls, "narrator ");
        assertTrue(calls.contains("stop finds no narrator"), calls.toString());
        assertTrue(calls.indexOf("play stop") < install && install < launch && launch < calls.indexOf("play start"),
                "a running java.exe would keep jre/ from being renamed, so the narrator starts after the install: " + calls + "\n" + run.out());
        assertEquals("narrator -cp W:" + home.resolve("precompiled/classes.jar") + " utils.RestartNarrator --status W:"
                        + statusFile() + " --pid-file W:" + home.resolve("logs/restart-narrator.pid")
                        + " --parent " + run.pid() + " --now " + nowArg(calls) + " --http " + port,
                calls.get(narrator));
        int taskkill = indexOf(calls, "taskkill ");
        assertTrue(taskkill > narrator && taskkill < calls.indexOf("play start"), calls.toString());
        assertTrue(calls.get(taskkill).matches("taskkill //PID \\d+ //F"), calls.get(taskkill));
        assertEquals("port free at start", calls.get(calls.indexOf("play start") + 1));
    }

    @Test
    void aNarratorThatCannotStartLeavesTheRestartAsItWas() throws Exception {
        // An install whose compiled classes lack the narrator: java starts, finds no class, and exits.
        Files.createDirectories(home.resolve("public/spa"));
        Files.createDirectories(home.resolve("precompiled"));
        try (var jar = new JarOutputStream(Files.newOutputStream(home.resolve("precompiled/classes.jar")))) {
            jar.putNextEntry(new ZipEntry("utils/Other.class"));
            jar.closeEntry();
        }
        var run = restart(Map.of("JCLAW_AOT", "off", "STUB_AWAIT_NARRATOR", "1"), false);

        assertEquals(0, run.exit(), run.out());
        assertEquals(List.of("stop:Stopping JClaw", "start:Starting JClaw (connecting channels)"), phases());
        assertEquals("done", status().get("state").getAsString());
        assertTrue(indexOf(calls(), "narrator ") >= 0, calls().toString());
        assertEquals("port free at start", calls().get(calls().indexOf("play start") + 1));
        assertTrue(Files.readString(home.resolve("logs/restart.log")).contains("utils.RestartNarrator"), "java's refusal is in the log");
    }

    @Test
    void aNarratorAFailedRestartLeftIsStoppedByTheNextStart() throws Exception {
        distInstall();
        assertNotEquals(0, restart(Map.of("JCLAW_AOT", "off", "STUB_START_FAIL", "1"), true).exit());
        var pidFile = home.resolve("logs/restart-narrator.pid");
        waitFor(() -> lines(pidFile).contains("bound"));
        long lingering = Long.parseLong(lines(pidFile).getFirst());

        Files.deleteIfExists(home.resolve("server.pid"));
        var run = harness(Map.of("JCLAW_AOT", "off"), false, "do_start_prod");
        assertEquals(0, run.exit(), run.out());
        assertTrue(run.out().contains("==> Stopping the restart progress server a failed restart left running (pid: " + lingering + ")"), run.out());
        assertFalse(ProcessHandle.of(lingering).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(calls().contains("port free at start"));
    }

    @Test
    void aCheckThatFailsAfterTheStopIsTheStartFailing() throws Exception {
        distInstall();
        Files.delete(home.resolve("public/spa"));
        var run = restart(Map.of("JCLAW_AOT", "off"), false);
        assertNotEquals(0, run.exit(), run.out());
        assertTrue(run.out().contains("Error: dist install is missing public/spa."), run.out());

        assertEquals("failed", status().get("state").getAsString());
        assertEquals("JClaw did not start", status().get("reason").getAsString());
        var phases = status().getAsJsonArray("phases");
        assertEquals(1, phases.size(), phases.toString());
        var stop = phases.get(0).getAsJsonObject();
        assertEquals("stop", stop.get("id").getAsString());
        assertTrue(stop.get("end").getAsLong() >= stop.get("start").getAsLong() && stop.get("end").getAsLong() > 0, stop.toString());

        waitFor(() -> lines(home.resolve("logs/restart-narrator.pid")).contains("bound"));
        var answer = get(RestartNarrator.PROGRESS_PATH);
        assertEquals("JClaw did not start", answer.get("failure").getAsString());
        assertEquals(List.of("done:Stopping JClaw"), steps(answer));
    }

    @Test
    void aStopTakesBackThePortFromANarratorAFailedRestartLeft() throws Exception {
        distInstall();
        assertNotEquals(0, restart(Map.of("JCLAW_AOT", "off", "STUB_START_FAIL", "1"), false).exit());
        var pidFile = home.resolve("logs/restart-narrator.pid");
        waitFor(() -> lines(pidFile).contains("bound"));
        long lingering = Long.parseLong(lines(pidFile).getFirst());

        var run = harness(Map.of(), false, "do_stop_prod");
        assertEquals(0, run.exit(), run.out());
        assertTrue(run.out().contains("==> Stopping the restart progress server a failed restart left running (pid: " + lingering + ")"), run.out());
        assertFalse(ProcessHandle.of(lingering).map(ProcessHandle::isAlive).orElse(false));
        assertFalse(Files.exists(pidFile));
    }

    @Test
    void aPidFileWhoseProcessIsNoNarratorKillsNothing() throws Exception {
        distInstall();
        var unrelated = spare(new ProcessBuilder("sleep", "60"));
        Files.writeString(home.resolve("logs/restart-narrator.pid"), unrelated.pid() + "\nbound\n");
        Files.delete(home.resolve("server.pid"));

        var run = harness(Map.of("JCLAW_AOT", "off"), false, "do_start_prod");
        assertEquals(0, run.exit(), run.out());
        assertTrue(unrelated.isAlive(), "a process that took a narrator's old pid is not the narrator");
        assertFalse(Files.exists(home.resolve("logs/restart-narrator.pid")));
        assertFalse(run.out().contains("Stopping the restart progress server"), run.out());
    }

    @Test
    void onWindowsAJavaThatIsNoNarratorKillsNothing() throws Exception {
        distInstall();
        var source = Files.writeString(tmp.resolve("Wait.java"),
                "class Wait { public static void main(String[] a) throws Exception { Thread.sleep(60_000); } }\n");
        var otherJava = spare(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), source.toString()));
        Files.writeString(home.resolve("logs/restart-narrator.pid"), otherJava.pid() + "\nbound\n");
        Files.delete(home.resolve("server.pid"));

        var run = harness(Map.of("JCLAW_AOT", "off", "IS_WINDOWS", "1"), false, "do_start_prod");
        assertEquals(0, run.exit(), run.out());
        assertTrue(otherJava.isAlive(), "a java.exe that took a narrator's old pid is not the narrator");
        assertFalse(Files.exists(home.resolve("logs/restart-narrator.pid")));
        assertEquals(-1, indexOf(calls(), "taskkill "), calls().toString());
    }

    // --- harness ---

    private record Run(int exit, String out, long pid) {}

    private record Probe(String status, JsonObject progress) {}

    private Process spare(ProcessBuilder pb) throws IOException {
        var p = pb.start();
        spares.add(p);
        return p;
    }

    private Run restart(Map<String, String> env, boolean realReadyWait) throws Exception {
        return harness(env, realReadyWait, "do_restart_prod");
    }

    private Run harness(Map<String, String> env, boolean realReadyWait, String command) throws Exception {
        var script = Files.readString(repo().resolve("jclaw.sh"));
        var harness = new StringBuilder("set -euo pipefail\nSCRIPT_DIR='" + home + "'\nIS_WINDOWS=" + env.getOrDefault("IS_WINDOWS", "0")
                + "\nBACKEND_PORT=" + port + "\nINVOKE=./jclaw.sh\n");
        var globals = Pattern.compile("(?m)^RESTART_STATUS_FILE=\"\"\\n(?:[A-Z_]+=.*\\n)+").matcher(script);
        assertTrue(globals.find(), "jclaw.sh no longer declares the restart progress globals");
        harness.append(globals.group());
        for (var name : FUNCTIONS) {
            var m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
            assertTrue(m.find(), "jclaw.sh no longer defines " + name);
            harness.append(m.group());
        }
        harness.append(STUBBED);
        if (!realReadyWait) harness.append(READY);
        harness.append("cd '").append(home).append("'\n").append(command).append('\n');

        var pb = new ProcessBuilder("/bin/bash", "-c", harness.toString()).redirectErrorStream(true)
                .redirectOutput(tmp.resolve("harness.out").toFile());
        var penv = pb.environment();
        for (var name : List.of("JCLAW_AOT", "JCLAW_AOT_DIR", "JAVA_TOOL_OPTIONS", "JCLAW_JVM_OPTS", "JCLAW_JVM_HEAP",
                "JCLAW_JVM_XMS", "JCLAW_JVM_XMX", "JCLAW_JVM_SOFTMAX", "JCLAW_FORCE_SPA_BUILD", "JCLAW_JRE_SKIP", "PLAY_SECRET")) {
            penv.remove(name);
        }
        penv.put("PATH", stubs() + File.pathSeparator + System.getenv("PATH"));
        penv.put("REAL_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        penv.put("STUB_CALLS", tmp.resolve("calls.log").toString());
        penv.put("STUB_PORT", Integer.toString(port));
        penv.put("STUB_PIDFILE", home.resolve("logs/restart-narrator.pid").toString());
        penv.put("STUB_NARRATOR_JVM", tmp.resolve("narrator-jvm.pid").toString());
        penv.put("STUB_CACHE", home.resolve("build/play/aot/app.aot").toString());
        penv.put("STUB_COOKIE", cookie());
        for (var e : env.entrySet()) {
            if (!e.getKey().equals("IS_WINDOWS")) penv.put(e.getKey(), e.getValue());
        }
        var p = pb.start();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "the harness hung");
        return new Run(p.exitValue(), Files.readString(tmp.resolve("harness.out")), p.pid());
    }

    private Path stubs() throws IOException {
        var bin = tmp.resolve("bin");
        if (!Files.isDirectory(bin)) {
            Files.createDirectories(bin);
            executable(bin.resolve("java"), JAVA);
            executable(bin.resolve("play"), PLAY);
            executable(bin.resolve("probe"), PROBE);
            executable(bin.resolve("npx"), NPX);
            executable(bin.resolve("pnpm"), "#!/bin/sh\necho \"pnpm $*\" >> \"$STUB_CALLS\"\n");
            // Git Bash's cygpath -w and taskkill, visibly: a native path gets a drive, a kill gets logged.
            executable(bin.resolve("cygpath"), "#!/bin/sh\neval \"last=\\${$#}\"\nprintf 'W:%s\\n' \"$last\"\n");
            executable(bin.resolve("taskkill"), "#!/bin/sh\necho \"taskkill $*\" >> \"$STUB_CALLS\"\nkill \"$2\"\n");
            // Get-CimInstance's command line for the ProcessId the filter names, read here from ps.
            executable(bin.resolve("powershell.exe"),
                    "#!/bin/sh\npid=$(printf '%s' \"$*\" | sed -n 's/.*ProcessId=\\([0-9]*\\).*/\\1/p')\nps -o command= -p \"$pid\"\n");
        }
        return bin;
    }

    /** A dist install: the compiled classes as one jar, and a built SPA. */
    private void distInstall() throws IOException {
        Files.createDirectories(home.resolve("precompiled"));
        Files.createDirectories(home.resolve("public/spa"));
        try (var jar = new JarOutputStream(Files.newOutputStream(home.resolve("precompiled/classes.jar")));
             Stream<Path> classes = Files.list(Path.of("build/classes/java/main/utils"))) {
            for (var c : classes.filter(p -> p.getFileName().toString().startsWith("RestartNarrator")).toList()) {
                jar.putNextEntry(new ZipEntry("utils/" + c.getFileName()));
                jar.write(Files.readAllBytes(c));
                jar.closeEntry();
            }
        }
    }

    /** A source checkout whose web app predates the record of its sources, started once to build it and write one. */
    private void builtSourceCheckout() throws Exception {
        Files.createDirectories(home.resolve("app"));
        Files.createDirectories(home.resolve("precompiled/java"));
        Files.createDirectories(home.resolve("public/spa"));
        Files.createDirectories(home.resolve("frontend/public"));
        Files.writeString(home.resolve("frontend/.gitignore"), ".output\n.DS_Store\ntest-report/\n");
        Files.writeString(home.resolve("frontend/public/favicon.ico"), "icon\n");
        git("init", "-q");
        git("add", "frontend");
        Files.delete(home.resolve("server.pid"));
        assertTheWebAppIsRebuiltBecause("no record of the sources public/spa was built from");
    }

    private void git(String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("/usr/bin/git", "-C", home.toString()));
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    private void stageJava() throws IOException {
        var staged = Files.createDirectories(home.resolve("../jre.staged"));
        Files.writeString(staged.resolve("manifest"), "version=25.0.5\nsha256=" + "c3".repeat(32) + "\narchive=zulu.tar.gz\n");
    }

    private static String cookie() throws IOException {
        var session = new LinkedHashMap<String, String>();
        session.put("___TS", Long.toString(System.currentTimeMillis() + 3_600_000));
        session.put("authenticated", "true");
        session.put("username", "admin");
        session.put("sid", "harness-session");
        var data = CookieDataCodec.encode(session);
        return "PLAY_SESSION=" + Crypto.sign(data, SECRET.getBytes(StandardCharsets.UTF_8)) + "-" + data;
    }

    private JsonObject status() throws Exception {
        return JsonParser.parseString(Files.readString(statusFile())).getAsJsonObject();
    }

    /** Where JClaw reads the file in production, so what jclaw.sh writes and what JClaw reads are one path. */
    private Path statusFile() throws ReflectiveOperationException {
        var field = Class.forName("services.RestartService", true, Play.classloader).getDeclaredField("STATUS_FILE");
        field.setAccessible(true);
        return home.resolve((String) field.get(null));
    }

    private List<String> phases() throws Exception {
        var out = new ArrayList<String>();
        for (var phase : status().getAsJsonArray("phases")) {
            var p = phase.getAsJsonObject();
            out.add(p.get("id").getAsString() + ":" + p.get("label").getAsString());
        }
        return out;
    }

    private static List<String> steps(JsonObject progress) {
        var out = new ArrayList<String>();
        JsonArray steps = progress.getAsJsonArray("steps");
        for (var step : steps) {
            var s = step.getAsJsonObject();
            out.add(s.get("state").getAsString() + ":" + s.get("label").getAsString());
        }
        return out;
    }

    private Probe probe(String name) throws IOException {
        var prefix = "probe " + name + " ";
        var line = calls().stream().filter(c -> c.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError("no probe " + name));
        var rest = line.substring(prefix.length());
        return new Probe(rest.substring(0, 3), JsonParser.parseString(rest.substring(4)).getAsJsonObject());
    }

    private JsonObject get(String path) throws IOException {
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        connection.setRequestProperty("Cookie", cookie());
        assertEquals(200, connection.getResponseCode());
        var body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private List<String> calls() throws IOException {
        var log = tmp.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private static int indexOf(List<String> calls, String prefix) {
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).startsWith(prefix)) return i;
        return -1;
    }

    /** jclaw.sh's own clock reading, which the narrator carries forward. */
    private static String nowArg(List<String> calls) {
        var now = argAfter(calls, "--now");
        assertTrue(Math.abs(Long.parseLong(now) - System.currentTimeMillis() / 1000) < 120, now);
        return now;
    }

    private static String argAfter(List<String> calls, String flag) {
        var words = calls.get(indexOf(calls, "narrator ")).split(" ");
        for (int i = 0; i + 1 < words.length; i++) if (words[i].equals(flag)) return words[i + 1];
        throw new AssertionError("no " + flag);
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException _) {
            return List.of();
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out");
            Thread.sleep(50);
        }
    }

    private static Path which(String tool) {
        for (var dir : System.getenv("PATH").split(File.pathSeparator)) {
            var candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static void executable(Path file, String body) throws IOException {
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static Path repo() {
        return Path.of(Play.applicationPath.getAbsolutePath());
    }
}
