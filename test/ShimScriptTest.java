import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the {@code jclaw} command {@code jclaw.sh shim} writes, from the deleted directory a shell is left in when an
 * upgrade replaces the install it sat in, with its {@code exec} pointed at a stub that prints the directory it started
 * in and its arguments.
 */
class ShimScriptTest extends UnitTest {

    private static final Path REPO = Path.of(Play.applicationPath.getAbsolutePath());

    @TempDir
    Path tmp;

    @Test
    void aShimStartedInADeletedDirectoryRunsJclawFromTheRoot() throws Exception {
        var run = shimFromADeletedDirectory();

        assertEquals("/\n", run.out(), () -> "stderr:\n" + run.err());
    }

    @Test
    void aShimStartedInADeletedDirectoryPrintsNoShellError() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/zsh")) || Files.isExecutable(Path.of("/usr/bin/zsh")),
                "without zsh the shim runs under /bin/sh, which prints the getcwd error before its first line");

        assertEquals("", shimFromADeletedDirectory().err());
    }

    @Test
    void aShimStartedInALiveDirectoryStaysThereAndPassesItsArgumentsIntact() throws Exception {
        var live = Files.createDirectory(tmp.resolve("live")).toRealPath();

        var run = shim("cd \"$1\" && shift && exec \"$@\"", live, "a b", "", "$HOME");

        assertEquals(live + "\n[a b]\n[]\n[$HOME]\n", run.out());
        assertEquals("", run.err());
    }

    // --- helpers ---

    private record Run(String out, String err) {}

    private Run shimFromADeletedDirectory() throws Exception {
        var gone = Files.createDirectory(tmp.resolve("gone"));
        return shim("cd \"$1\" && rmdir \"$PWD\" && shift && exec \"$@\"", gone);
    }

    /** Starts the shim through {@code launcher}, a bash script given the directory, the shim and the arguments. */
    private Run shim(String launcher, Path dir, String... args) throws Exception {
        var bin = Files.createDirectories(tmp.resolve("bin"));
        var write = new ProcessBuilder("/bin/bash", REPO.resolve("jclaw.sh").toString(), "shim")
                .directory(REPO.toFile()).redirectErrorStream(true);
        write.environment().put("JCLAW_BIN_DIR", bin.toString());
        assertEquals(0, finish(write).exitCode, "jclaw.sh shim failed");

        var stub = tmp.resolve("stub");
        Files.writeString(stub, "#!/bin/sh\n/bin/pwd -P\nfor a in \"$@\"; do printf '[%s]\\n' \"$a\"; done\n");
        assertTrue(stub.toFile().setExecutable(true));
        var shim = bin.resolve("jclaw");
        var text = Files.readString(shim);
        var target = "\"" + REPO.resolve("jclaw.sh") + "\"";
        assertTrue(text.contains(target), () -> "the shim no longer execs " + target + ":\n" + text);
        Files.writeString(shim, text.replace(target, "\"" + stub + "\""));

        var command = new ArrayList<>(List.of("/bin/bash", "-c", launcher, "launcher", dir.toString(), shim.toString()));
        command.addAll(List.of(args));
        var done = finish(new ProcessBuilder(command).directory(tmp.toFile()));
        assertEquals(0, done.exitCode, () -> "the shim exited non-zero:\n" + done.run.err());
        return done.run;
    }

    private record Finished(int exitCode, Run run) {}

    private static Finished finish(ProcessBuilder pb) throws Exception {
        var p = pb.start();
        p.getOutputStream().close();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the process hung");
        return new Finished(p.exitValue(), new Run(out, err));
    }
}
