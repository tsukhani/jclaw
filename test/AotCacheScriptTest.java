import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs jclaw.sh's AOT cache functions against stub {@code java} and {@code play} executables, so
 * the gate, the fingerprint and the training decisions are tested as written rather than restated.
 */
class AotCacheScriptTest extends UnitTest {

    private static final List<String> FUNCTIONS = List.of("port_answers", "upgrade_install_kind", "aot_cache_file",
            "aot_store_dir", "aot_java_version", "aot_jdk_has_fix", "aot_spare_port", "aot_status", "aot_fingerprint", "aot_prepare", "aot_check_used");

    private static final String JAVA_STUB = """
            #!/bin/sh
            for a in "$@"; do
                if [ "$a" = "-XshowSettings:properties" ]; then
                    [ -n "$STUB_JAVA_VERSION" ] && echo "    java.version = $STUB_JAVA_VERSION" >&2
                    exit 0
                fi
            done
            [ -n "${JAVA_TOOL_OPTIONS:-}" ] && echo "Picked up JAVA_TOOL_OPTIONS: $JAVA_TOOL_OPTIONS" >&2
            echo "openjdk version \\"$STUB_JAVA_VERSION\\"" >&2
            """;

    // aot-gen writes a new file over the cache path, replacing a link as the launcher's mv -f does.
    private static final String PLAY_STUB = """
            #!/bin/sh
            echo "$*" >> "$STUB_CALLS"
            if [ "$1" = aot-gen ]; then
                if [ "${STUB_PLAY_FAIL:-}" = 1 ]; then echo "the training run broke"; exit 1; fi
                mkdir -p "$(dirname "$STUB_CACHE")"
                rm -f "$STUB_CACHE"
                printf 'cache' > "$STUB_CACHE"
            fi
            """;

    @TempDir Path tmp;

    @Test
    void theGateOpensOnlyOnJdksWithTheStaleJarFix() throws Exception {
        var home = gradleHome();
        Map<String, Boolean> expected = Map.ofEntries(
                Map.entry("25.0.2", false), Map.entry("25.0.3", false), Map.entry("25.0.4", true),
                Map.entry("25.0.4.1", true), Map.entry("25.1.0", true), Map.entry("26", false),
                Map.entry("26.0.1", false), Map.entry("26.0.2", true), Map.entry("27", true),
                Map.entry("27-ea", true), Map.entry("28.0.1", true), Map.entry("21.0.9", false),
                Map.entry("", false));
        for (var e : expected.entrySet()) {
            var out = run(home, Map.of("STUB_JAVA_VERSION", e.getKey()), "aot_jdk_has_fix && echo open || echo closed");
            assertEquals(e.getValue() ? "open" : "closed", out.strip(), () -> "java.version=" + e.getKey());
        }
    }

    @Test
    void aStartTrainsOnceAndThenReusesTheCache() throws Exception {
        var home = gradleHome();
        var first = prepare(home, Map.of(), "--http.port=9000 -Xmx2g -XX:HeapDumpPath=logs/heap-1-%p.hprof");
        assertTrue(first.contains("Training the AOT cache (there is none yet)"), first);
        assertTrue(first.contains("AOT cache: trained"), first);
        assertEquals(1, calls().size());
        assertTrue(calls().getFirst().matches("aot-gen --%prod --http\\.port=1\\d{4} -Xmx2g -XX:HeapDumpPath=logs/heap-1-%p\\.hprof"),
                calls().getFirst());

        // A start's own heap dump name differs every time and must not retrain.
        var second = prepare(home, Map.of(), "--http.port=9000 -Xmx2g -XX:HeapDumpPath=logs/heap-2-%p.hprof");
        assertTrue(second.contains("AOT cache: up to date"), second);
        assertEquals(1, calls().size());
    }

    @Test
    void trainingNeverAnswersOnTheInstancesOwnPorts() throws Exception {
        var home = gradleHome();
        // An occupied candidate is skipped rather than shared.
        try (var held = new java.net.ServerSocket()) {
            boolean holding;
            try {
                held.bind(new java.net.InetSocketAddress("127.0.0.1", 10000));
                holding = true;
            } catch (java.io.IOException alreadyTaken) {
                holding = false;
            }
            prepare(home, Map.of(), "--http.port=9000 -Xmx2g -Dhttps.port=9443");
            var m = java.util.regex.Pattern.compile("--http\\.port=(\\d+) -Xmx2g -Dhttps\\.port=(\\d+)").matcher(calls().getFirst());
            assertTrue(m.find(), calls().getFirst());
            int http = Integer.parseInt(m.group(1));
            int https = Integer.parseInt(m.group(2));
            assertTrue(http != 9000 && https != 9443 && http != https, calls().getFirst());
            if (holding) assertTrue(http > 10000, "training took the held port " + http);
        }
        // Ports change nothing the cache holds, and HTTPS switched off stays off for training.
        assertTrue(prepare(home, Map.of(), "--http.port=9100 -Xmx2g -Dhttps.port=8443").contains("AOT cache: up to date"));
        Files.delete(gradleCache(home));
        prepare(home, Map.of(), "--http.port=9000 -Xmx2g -Dhttps.port=-1");
        assertTrue(calls().getLast().endsWith(" -Dhttps.port=-1"), calls().getLast());
        assertEquals(2, calls().size());
    }

    @Test
    void aChangedOptionOrLibraryRetrains() throws Exception {
        var home = gradleHome();
        prepare(home, Map.of(), "--http.port=9000 -Xmx2g");
        var heap = prepare(home, Map.of(), "--http.port=9000 -Xmx4g");
        assertTrue(heap.contains("Training the AOT cache (the JDK, the JVM options or the libraries changed)"), heap);
        Files.writeString(home.resolve("build.gradle.kts"), "dependencies { implementation(\"x:y:2\") }\n");
        var library = prepare(home, Map.of(), "--http.port=9000 -Xmx4g");
        assertTrue(library.contains("Training the AOT cache"), library);
        var jdk = prepare(home, Map.of("STUB_JAVA_VERSION", "25.0.5"), "--http.port=9000 -Xmx4g");
        assertTrue(jdk.contains("Training the AOT cache"), jdk);
        assertEquals(4, calls().size());
    }

    @Test
    void aFailedTrainingRunNeverStopsTheStart() throws Exception {
        var home = gradleHome();
        var out = prepare(home, Map.of("STUB_PLAY_FAIL", "1"), "--http.port=9000");
        assertTrue(out.contains("training failed, so this start runs without one"), out);
        assertTrue(out.contains("the training run broke"), out);
        assertFalse(Files.exists(gradleCache(home)));
        assertFalse(Files.exists(gradleCache(home).resolveSibling("app.aot.fingerprint")));
    }

    @Test
    void anOldJdkOrTheSwitchRemovesTheCache() throws Exception {
        var home = gradleHome();
        prepare(home, Map.of(), "--http.port=9000");
        var old = prepare(home, Map.of("STUB_JAVA_VERSION", "25.0.2"), "--http.port=9000");
        assertTrue(old.contains("off: needs Java 25.0.4, 26.0.2 or 27, and this is 25.0.2 (JDK-8377932)"), old);
        assertFalse(Files.exists(gradleCache(home)));

        prepare(home, Map.of(), "--http.port=9000");
        var off = prepare(home, Map.of("JCLAW_AOT", "off"), "--http.port=9000");
        assertTrue(off.contains("AOT cache: off (JCLAW_AOT=off)"), off);
        assertFalse(Files.exists(gradleCache(home)));
        assertEquals(2, calls().size());
    }

    @Test
    void theSettingsSwitchTurnsTheCacheOffUntilItIsSwitchedBackOn() throws Exception {
        var home = gradleHome();
        prepare(home, Map.of(), "--http.port=9000");
        Files.createDirectories(home.resolve("data"));
        Files.writeString(home.resolve("data/aot-cache.off"), "switched off\n");
        var off = prepare(home, Map.of(), "--http.port=9000");
        assertTrue(off.contains("AOT cache: off (switched off in Settings)"), off);
        assertFalse(Files.exists(gradleCache(home)));
        assertEquals("off (switched off in Settings)", status(home));

        Files.delete(home.resolve("data/aot-cache.off"));
        var on = prepare(home, Map.of(), "--http.port=9000");
        assertTrue(on.contains("Training the AOT cache (there is none yet)"), on);
        assertTrue(status(home).startsWith("trained"), status(home));
        assertEquals(2, calls().size());
    }

    @Test
    void aContainerKeepsTheCacheOnItsVolume() throws Exception {
        var home = bundleHome();
        var store = home.resolve("data/aot");
        var env = Map.of("JCLAW_AOT_DIR", store.toString(),
                "JAVA_TOOL_OPTIONS", "-Xmx2g -XX:HeapDumpPath=/app/logs/heap-oom-20261021T130016Z-%p.hprof"
                        + " -Dotel.exporter.otlp.headers=Authorization=Bearer-s3cr3t-token");
        var first = prepare(home, env, "--https.port=9443");
        assertTrue(first.contains("AOT cache: trained"), first);
        assertEquals(1, calls().size());
        assertTrue(calls().getFirst().matches("aot-gen --%prod --https\\.port=1\\d{4} --http\\.port=1\\d{4}"), calls().getFirst());
        var stamp = Files.readString(store.resolve("app.aot.fingerprint"));
        assertFalse(stamp.contains("s3cr3t") || stamp.contains("-Xmx2g"), () -> "the fingerprint kept the options in the clear: " + stamp);
        assertTrue(Files.isRegularFile(store.resolve("app.aot"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(home.resolve("app.aot")));

        // The next container start names its heap dump by its own time; a re-created container has no link yet.
        Files.delete(home.resolve("app.aot"));
        var restart = prepare(home, Map.of("JCLAW_AOT_DIR", store.toString(),
                "JAVA_TOOL_OPTIONS", "-Xmx2g -XX:HeapDumpPath=/app/logs/heap-oom-20261022T080000Z-%p.hprof"
                        + " -Dotel.exporter.otlp.headers=Authorization=Bearer-s3cr3t-token"), "--https.port=9443");
        assertTrue(restart.contains("AOT cache: up to date"), restart);
        assertTrue(Files.isSymbolicLink(home.resolve("app.aot")));
        assertEquals(1, calls().size());
    }

    @Test
    void aBundleInstallKeepsTheCacheInItsRootAndRetrainsAfterAnUpgrade() throws Exception {
        var home = bundleHome();
        var first = prepare(home, Map.of(), "--http.port=9000 -Xmx2g");
        assertTrue(first.contains("AOT cache: trained"), first);
        assertTrue(Files.isRegularFile(home.resolve("app.aot"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(home.resolve("app.aot.fingerprint")));
        assertTrue(prepare(home, Map.of(), "--http.port=9000 -Xmx2g").contains("AOT cache: up to date"));

        // An upgrade brings other jars.
        Files.writeString(home.resolve("lib/okhttp-5.4.0.jar"), "jar");
        var upgraded = prepare(home, Map.of(), "--http.port=9000 -Xmx2g");
        assertTrue(upgraded.contains("Training the AOT cache (the JDK, the JVM options or the libraries changed)"), upgraded);
        assertEquals(2, calls().size());
    }

    @Test
    void aRefusedCacheIsDroppedSoTheNextStartRetrains() throws Exception {
        var home = gradleHome();
        prepare(home, Map.of(), "--http.port=9000");
        Files.createDirectories(home.resolve("logs"));
        Files.writeString(home.resolve("logs/system.out"), "[0.017s][info   ][aot] Opened AOT cache\n");
        assertEquals("AOT cache: in use", run(home, Map.of(), "aot_check_used").strip());
        assertEquals("in use", status(home));

        Files.writeString(home.resolve("logs/system.out"),
                "[0.017s][error  ][aot] shared class paths mismatch (hint: enable -Xlog:class+path=info)\n");
        var refused = run(home, Map.of(), "aot_check_used");
        assertTrue(refused.contains("refused by the JVM"), refused);
        assertTrue(status(home).startsWith("refused by the JVM"), status(home));
        assertFalse(Files.exists(gradleCache(home)));
        assertTrue(prepare(home, Map.of(), "--http.port=9000").contains("Training the AOT cache (there is none yet)"));
    }

    @Test
    void everyProductionLaunchKeepsTheCacheCurrentFirst() throws Exception {
        var script = Files.readString(repo().resolve("jclaw.sh"));
        int prepare = script.indexOf("    aot_prepare --http.port=\"$BACKEND_PORT\" \"${jvm_opts[@]}\"\n");
        int start = script.indexOf("    play start --%prod --http.port=\"$BACKEND_PORT\" \"${jvm_opts[@]}\"\n");
        int ready = script.indexOf("    wait_for_backend_ready || return 1\n    aot_check_used\n");
        assertTrue(prepare > 0 && prepare < start && start < ready, "do_start_prod trains with the start's own arguments, then checks the cache was used");

        var dockerfile = Files.readString(repo().resolve("Dockerfile"));
        var cmd = Pattern.compile("(?m)^CMD .*$").matcher(dockerfile);
        assertTrue(cmd.find());
        var line = cmd.group();
        int train = line.indexOf("{ bash ./jclaw.sh aot-cache --https.port=${JCLAW_HTTPS_PORT:-9443} || true; } && exec ./play run --%prod --https.port=${JCLAW_HTTPS_PORT:-9443} ");
        assertTrue(train > line.indexOf("export JAVA_TOOL_OPTIONS="), "the image trains after JAVA_TOOL_OPTIONS, with the launch's arguments, and never fails the start");
        assertTrue(dockerfile.contains("JCLAW_AOT_DIR=/app/data/aot"));
        assertTrue(Files.readString(repo().resolve("docker-compose.yml")).contains("- ./data:/app/data"),
                "the cache's directory is on the data volume");
    }

    private String prepare(Path home, Map<String, String> env, String args) throws Exception {
        return run(home, env, "aot_prepare " + args);
    }

    private String run(Path home, Map<String, String> env, String commands) throws Exception {
        var script = Files.readString(repo().resolve("jclaw.sh"));
        var harness = new StringBuilder("set -euo pipefail\nSCRIPT_DIR='" + home + "'\nIS_WINDOWS=0\nBACKEND_PORT=9000\n");
        for (var name : FUNCTIONS) {
            var m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
            assertTrue(m.find(), "jclaw.sh no longer defines " + name);
            harness.append(m.group());
        }
        harness.append(commands).append('\n');
        var pb = new ProcessBuilder("bash", "-c", harness.toString()).redirectErrorStream(true);
        var penv = pb.environment();
        penv.remove("JCLAW_AOT");
        penv.remove("JCLAW_AOT_DIR");
        penv.remove("JAVA_TOOL_OPTIONS");
        penv.put("PATH", stubs() + ":" + System.getenv("PATH"));
        penv.put("STUB_JAVA_VERSION", "25.0.4.1");
        penv.put("STUB_CALLS", tmp.resolve("calls.log").toString());
        penv.put("STUB_CACHE", (Files.isDirectory(home.resolve("framework")) ? home.resolve("app.aot") : gradleCache(home)).toString());
        penv.putAll(env);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the harness hung");
        assertEquals(0, p.exitValue(), () -> "the harness exited non-zero:\n" + out);
        return out;
    }

    private static String status(Path home) throws Exception {
        return Files.readString(home.resolve("data/aot-cache.status")).strip();
    }

    private List<String> calls() throws Exception {
        var log = tmp.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private Path stubs() throws Exception {
        var bin = tmp.resolve("bin");
        if (!Files.isDirectory(bin)) {
            Files.createDirectories(bin);
            executable(bin.resolve("java"), JAVA_STUB);
            executable(bin.resolve("play"), PLAY_STUB);
        }
        return bin;
    }

    private Path gradleHome() throws Exception {
        var home = tmp.resolve("home");
        Files.createDirectories(home.resolve("conf"));
        Files.writeString(home.resolve("conf/application.conf"), "jvm.memory=--add-modules=jdk.incubator.vector\n");
        Files.writeString(home.resolve("build.gradle.kts"), "dependencies { implementation(\"x:y:1\") }\n");
        Files.writeString(home.resolve("settings.gradle.kts"), "rootProject.name = \"jclaw\"\n");
        Files.writeString(home.resolve(".play-version"), "1.13.84\n");
        return home;
    }

    private Path bundleHome() throws Exception {
        var home = tmp.resolve("bundle");
        Files.createDirectories(home.resolve("conf"));
        Files.createDirectories(home.resolve("framework/lib"));
        Files.createDirectories(home.resolve("lib"));
        Files.writeString(home.resolve("conf/application.conf"), "jvm.memory=--add-modules=jdk.incubator.vector\n");
        Files.writeString(home.resolve("lib/okhttp-5.3.2.jar"), "jar");
        Files.writeString(home.resolve("framework/play-1.13.84.jar"), "jar");
        executable(home.resolve("play"), "#!/bin/sh\n");
        return home;
    }

    private static Path gradleCache(Path home) {
        return home.resolve("build/play/aot/app.aot");
    }

    private static void executable(Path file, String body) throws Exception {
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static Path repo() {
        return Path.of(Play.applicationPath.getAbsolutePath());
    }
}
