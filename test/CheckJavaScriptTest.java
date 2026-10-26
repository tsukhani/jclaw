import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs jclaw.sh's {@code check_java} against a stub {@code java} that prints a given
 * {@code java -version} banner, under the script's own {@code set -euo pipefail}, so every way the
 * version check passes or refuses is tested as written rather than restated (JF-30).
 */
class CheckJavaScriptTest extends UnitTest {

    private static final List<String> FUNCTIONS = List.of("java_version_line", "check_java");

    private static final String TOOL_OPTIONS = "Picked up JAVA_TOOL_OPTIONS: -Xms128m -Xmx2g -XX:+UseZGC"
            + " -XX:HeapDumpPath=/app/logs/heap-oom-20261026T040415Z-%p.hprof";

    @TempDir Path tmp;

    record Result(int exit, String out) {}

    @Test
    void aJava25UnderJavaToolOptionsPassesTheCheck() throws Exception {
        var java = stubJava(TOOL_OPTIONS + "\n" + """
                openjdk version "25.0.5" 2026-10-20 LTS
                OpenJDK Runtime Environment Zulu25.38+107-CA (build 25.0.5+8-LTS)
                """, 0);

        assertEquals(new Result(0, "check passed\n"), checkJava(java));
    }

    @Test
    void aJava21UnderJavaToolOptionsIsRefusedForItsVersion() throws Exception {
        var java = stubJava(TOOL_OPTIONS + "\n" + """
                openjdk version "21.0.9" 2025-10-21 LTS
                OpenJDK Runtime Environment Zulu21.46+19-CA (build 21.0.9+10-LTS)
                """, 0);

        assertEquals(new Result(1, """
                Error: Java 21 found, but JDK 25+ is required.
                       Set JAVA_HOME or use jenv to configure JDK 25.
                """), checkJava(java));
    }

    @Test
    void aJavaThatPrintsNoVersionSaysSoInsteadOfExitingSilently() throws Exception {
        var java = stubJava(TOOL_OPTIONS + "\n" + """
                Error occurred during initialization of VM
                agent library failed to init: instrument
                """, 1);

        assertEquals(new Result(1, "Error: " + java + "/java -version printed no version: "
                + "Error occurred during initialization of VM\n"
                + "       JDK 25+ is required.\n"), checkJava(java));
    }

    @Test
    void withNoJavaOnThePathTheCheckSaysJavaIsMissing() throws Exception {
        assertEquals(new Result(1, "Error: Java not found. JDK 25+ is required.\n"), run(tools(), "check_java"));
    }

    @Test
    void everyOptionsNoticeTheLauncherAndTheJvmPrintIsSkipped() throws Exception {
        // As JDK 25.0.5 prints them with JDK_JAVA_OPTIONS, JAVA_TOOL_OPTIONS and _JAVA_OPTIONS all set.
        var java = stubJava("""
                NOTE: Picked up JDK_JAVA_OPTIONS: -Dx=1
                Picked up JAVA_TOOL_OPTIONS: -Dy=2
                Picked up _JAVA_OPTIONS: -Dz=3
                openjdk version "25.0.5" 2026-10-20 LTS
                """, 0);

        assertEquals(new Result(0, "check passed\n"), checkJava(java));
    }

    @Test
    void aJavaagentsWarningAndLogLineBeforeTheVersionDoNotFailTheCheck() throws Exception {
        // As JDK 25.0.5 prints them with INSTALL.md's OpenTelemetry agent in JAVA_TOOL_OPTIONS.
        var java = stubJava("""
                Picked up JAVA_TOOL_OPTIONS: -Xmx2g -javaagent:/opt/otel/opentelemetry-javaagent.jar
                OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes \
                because bootstrap classpath has been appended
                [otel.javaagent 2026-10-26 13:58:11:402 +0800] [main] INFO \
                io.opentelemetry.javaagent.tooling.VersionLogger - opentelemetry-javaagent - version: 2.24.0
                openjdk version "25.0.5" 2026-10-20 LTS
                OpenJDK Runtime Environment Zulu25.38+107-CA (build 25.0.5+8-LTS)
                """, 0);

        assertEquals(new Result(0, "check passed\n"), checkJava(java));
    }

    @Test
    void theVersionLineIsTheOneAfterTheBanner() throws Exception {
        var java = stubJava(TOOL_OPTIONS + "\n" + """
                openjdk version "25.0.5" 2026-10-20 LTS
                OpenJDK Runtime Environment Zulu25.38+107-CA (build 25.0.5+8-LTS)
                """, 0);

        assertEquals(new Result(0, "openjdk version \"25.0.5\" 2026-10-20 LTS\n"),
                run(java + File.pathSeparator + tools(), "java_version_line"));
    }

    // --- helpers ---

    private Result checkJava(Path javaDir) throws Exception {
        return run(javaDir + File.pathSeparator + tools(), "check_java; echo 'check passed'");
    }

    private Result run(String path, String commands) throws Exception {
        var script = Files.readString(repo().resolve("jclaw.sh"));
        var harness = new StringBuilder("set -euo pipefail\n");
        for (var name : FUNCTIONS) {
            var m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
            assertTrue(m.find(), "jclaw.sh no longer defines " + name);
            harness.append(m.group());
        }
        harness.append(commands).append('\n');
        var pb = new ProcessBuilder("/bin/bash", "-c", harness.toString()).redirectErrorStream(true);
        pb.environment().put("PATH", path);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the harness hung");
        return new Result(p.exitValue(), out);
    }

    /** A directory holding only a {@code java} that prints {@code banner} on stderr and exits {@code exit}. */
    private Path stubJava(String banner, int exit) throws Exception {
        var bin = Files.createDirectories(tmp.resolve("java-bin"));
        var java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\ncat >&2 <<'EOF'\n" + banner + "EOF\nexit " + exit + "\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    /** Links to the tools the check pipes through, and no {@code java}. */
    private String tools() throws Exception {
        var bin = tmp.resolve("tools");
        if (!Files.isDirectory(bin)) {
            Files.createDirectories(bin);
            for (var tool : List.of("grep", "head", "tr", "cat")) {
                Files.createSymbolicLink(bin.resolve(tool), which(tool));
            }
        }
        return bin.toString();
    }

    private static Path which(String tool) {
        for (var dir : System.getenv("PATH").split(File.pathSeparator)) {
            var candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) return candidate;
        }
        throw new IllegalStateException("this host has no " + tool);
    }

    private static Path repo() {
        return Path.of(Play.applicationPath.getAbsolutePath());
    }
}
