import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs jclaw.sh's {@code check_node} against a stub {@code node} that prints a given {@code node -v}, under
 * the script's own {@code set -euo pipefail}. The range it accepts is the pinned Nuxt 4.6's {@code engines}
 * ({@code ^22.22.3 || ^24.15.0 || >=26.0.0}) above this project's Node 24 floor.
 */
class CheckNodeScriptTest extends UnitTest {

    private static final String REFUSED = """
            Error: Node %s found, but Node.js 24.15+ or 26+ is required.
                   Nuxt's engines field rejects every other version, Node 25 included.
                   Install from https://nodejs.org/ (or use nvm/fnm/asdf).
            """;

    @TempDir Path tmp;

    record Result(int exit, String out) {}

    @Test
    void aNode24Point15PassesTheCheck() throws Exception {
        assertEquals(new Result(0, "check passed\n"), checkNode("v24.15.0"));
    }

    @Test
    void aNode24Point14IsRefusedForItsVersion() throws Exception {
        assertEquals(new Result(1, REFUSED.formatted("v24.14.0")), checkNode("v24.14.0"));
    }

    @Test
    void aNode26Point0PassesTheCheck() throws Exception {
        assertEquals(new Result(0, "check passed\n"), checkNode("v26.0.0"));
    }

    @Test
    void aNode25IsRefusedBecauseNuxtExcludesIt() throws Exception {
        assertEquals(new Result(1, REFUSED.formatted("v25.9.0")), checkNode("v25.9.0"));
    }

    @Test
    void aNode22NuxtAcceptsIsRefusedBelowTheProjectsFloor() throws Exception {
        assertEquals(new Result(1, REFUSED.formatted("v22.22.3")), checkNode("v22.22.3"));
    }

    @Test
    void aNodeThatPrintsNoVersionSaysSo() throws Exception {
        assertEquals(new Result(1, "Error: could not read a version from 'node -v' output 'not a version'.\n"),
                checkNode("not a version"));
    }

    @Test
    void withNoNodeOnThePathTheCheckSaysNodeIsMissing() throws Exception {
        assertEquals(new Result(1, """
                Error: node not found. Node.js 24.15+ or 26+ is required.
                       Install from https://nodejs.org/ (or use nvm/fnm/asdf).
                """), run(tools()));
    }

    // --- helpers ---

    private Result checkNode(String version) throws Exception {
        var bin = Files.createDirectories(tmp.resolve("node-bin"));
        var node = bin.resolve("node");
        Files.writeString(node, "#!/bin/sh\necho '" + version + "'\n");
        Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("rwxr-xr-x"));
        return run(bin + File.pathSeparator + tools());
    }

    private Result run(String path) throws Exception {
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "jclaw.sh"));
        var m = Pattern.compile("(?ms)^check_node\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
        assertTrue(m.find(), "jclaw.sh no longer defines check_node");
        var pb = new ProcessBuilder("/bin/bash", "-c",
                "set -euo pipefail\n" + m.group() + "check_node; echo 'check passed'\n").redirectErrorStream(true);
        pb.environment().put("PATH", path);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the harness hung");
        return new Result(p.exitValue(), out);
    }

    /** A link to {@code sed}, which the check reads the version through, and no {@code node}. */
    private String tools() throws Exception {
        var bin = tmp.resolve("tools");
        if (!Files.isDirectory(bin)) {
            Files.createDirectories(bin);
            Files.createSymbolicLink(bin.resolve("sed"), which("sed"));
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
}
