import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;
import services.evals.EvalDatasetLoader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs jclaw.sh's {@code do_evals} offline path in a scratch checkout (JF-301). A production start
 * runs from {@code precompiled/}, so the command must score from somewhere else and leave that tree
 * alone. Gradle is stubbed at the process boundary: the stub records its call and lays down the real
 * compiled offline classes, the set {@link EvalRunnerTest#OFFLINE_CLASSES} names.
 */
class EvalsCliScriptTest extends UnitTest {
    private static final List<String> FUNCTIONS = List.of("native_path", "locate_h2_jar", "do_evals");
    private static final String COMPILE = "gradlew --quiet compileJava";
    private static final FileTime BEFORE_THE_EDIT = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"));

    @TempDir Path home;
    @TempDir Path tmp;

    private record Run(int exit, List<String> lines) {}

    @BeforeEach
    void layOutACheckoutWithALiveBuild() throws IOException {
        // The running instance's classes: dated before the app/ edit below, as after a commit since the start.
        var live = Files.createDirectories(home.resolve("precompiled/java"));
        Files.writeString(live.resolve("LiveInstance.class"), "loaded by the running instance");
        Files.setLastModifiedTime(live.resolve("LiveInstance.class"), BEFORE_THE_EDIT);
        Files.setLastModifiedTime(live, BEFORE_THE_EDIT);
        Files.writeString(Files.createDirectories(home.resolve("app/services/evals")).resolve("EvalCase.java"),
                "// edited since the instance started\n");

        Files.writeString(Files.createDirectories(home.resolve("evals/suites")).resolve("mini.json"), """
                {"id": "mini", "description": "one case", "cases": [
                  {"id": "says-hello", "input": "hi", "rubric": "it answers",
                   "checks": [{"kind": "contains_all", "args": ["hello"]}]}]}
                """);
        // locate_h2_jar's distribution branch: framework/lib under the checkout, here the real one.
        Files.createDirectories(home.resolve("framework"));
        Files.createSymbolicLink(home.resolve("framework/lib"), new File(Play.frameworkPath, "framework/lib").toPath());

        stageOfflineClasses();
        executable(tmp.resolve("bin/gradlew"), """
                #!/bin/sh
                echo "gradlew $*" >> "$STUB_CALLS"
                if [ -n "${STUB_FAIL:-}" ]; then echo "stub: compileJava failed" >&2; exit 1; fi
                mkdir -p build/classes/java/main && cp -R "$STUB_COMPILED"/. build/classes/java/main/
                """);
        // do_evals runs ./gradlew from the checkout, as jclaw.sh check does.
        Files.createSymbolicLink(home.resolve("gradlew"), tmp.resolve("bin/gradlew"));
    }

    @Test
    void scoringAfterAnAppEditLeavesTheLiveBuildUntouched() throws Exception {
        var run = evals(false);

        assertEquals(0, run.exit(), String.join("\n", run.lines()));
        assertEquals(validated(), run.lines());
        assertEquals(List.of(COMPILE), calls());
        var live = home.resolve("precompiled/java");
        try (Stream<Path> files = Files.list(live)) {
            assertEquals(List.of(live.resolve("LiveInstance.class")), files.toList());
        }
        assertEquals("loaded by the running instance", Files.readString(live.resolve("LiveInstance.class")));
        assertEquals(BEFORE_THE_EDIT, Files.getLastModifiedTime(live));
        assertEquals(BEFORE_THE_EDIT, Files.getLastModifiedTime(live.resolve("LiveInstance.class")));
    }

    @Test
    void everyRunAsksGradleToBringTheClassesUpToDate() throws Exception {
        evals(false);
        var second = evals(false);

        assertEquals(validated(), second.lines());
        assertEquals(List.of(COMPILE, COMPILE), calls(), "the command never judges a build current on its own");
    }

    @Test
    void aFailedCompileStopsTheCommandBeforeItScoresAnything() throws Exception {
        var run = evals(true);

        assertEquals(1, run.exit());
        assertEquals(List.of("stub: compileJava failed",
                "Error: compileJava failed, so the eval CLI has no classes to run; see the output above."), run.lines());
    }

    /** What validating the one-suite dataset prints. */
    private List<String> validated() {
        var suite = EvalDatasetLoader.loadSuite(home.resolve("evals/suites/mini.json"));
        return List.of("%-28s %2d case(s), %3d check(s)".formatted(suite.qualifiedId(), 1, 1),
                "1 suite(s), 1 case(s), 1 check(s) — all valid");
    }

    /** The offline CLI's compiled classes, copied from this build for the stub to lay down. */
    private void stageOfflineClasses() throws IOException {
        var built = Path.of("build/classes/java/main");
        var offline = Pattern.compile(EvalRunnerTest.OFFLINE_CLASSES);
        try (Stream<Path> classes = Files.list(built.resolve("services/evals"))) {
            for (var c : classes.toList()) {
                var name = "services.evals." + c.getFileName().toString().replaceFirst("\\.class$", "");
                if (offline.matcher(name).matches()) {
                    Files.copy(c, Files.createDirectories(tmp.resolve("compiled/services/evals")).resolve(c.getFileName()));
                }
            }
        }
        Files.copy(built.resolve("tools/SchemaKeys.class"),
                Files.createDirectories(tmp.resolve("compiled/tools")).resolve("SchemaKeys.class"));
    }

    private List<String> calls() throws IOException {
        var log = tmp.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private Run evals(boolean compileFails) throws Exception {
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath()).resolve("jclaw.sh"));
        var harness = new StringBuilder("set -euo pipefail\nSCRIPT_DIR='" + home + "'\nIS_WINDOWS=0\nCP_SEP=':'\nEVAL_ARGS=()\n");
        for (var name : FUNCTIONS) {
            var m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
            assertTrue(m.find(), "jclaw.sh no longer defines " + name);
            harness.append(m.group());
        }
        harness.append("do_evals\n");
        var pb = new ProcessBuilder("bash", "-c", harness.toString(), "jclaw.sh").redirectErrorStream(true);
        var env = pb.environment();
        env.put("PATH", Path.of(System.getProperty("java.home"), "bin") + File.pathSeparator + System.getenv("PATH"));
        env.put("STUB_CALLS", tmp.resolve("calls.log").toString());
        env.put("STUB_COMPILED", tmp.resolve("compiled").toString());
        if (compileFails) env.put("STUB_FAIL", "1");
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the harness hung");
        return new Run(p.exitValue(), out.lines().toList());
    }

    private static void executable(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        if (!file.toFile().setExecutable(true)) throw new IOException("cannot mark " + file + " executable");
    }
}
