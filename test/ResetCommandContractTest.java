import org.h2.tools.Shell;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * The contract {@code jclaw.sh reset} relies on when it clears the admin password hash.
 *
 * <p>Guards a defect that made every Windows reset a no-op reporting success. The script
 * drove a native java.exe through Git Bash with an absolute MSYS path
 * ({@code jdbc:h2:file:/c/Users/...}); java.exe reads the leading slash as the current
 * drive's root, so H2 auto-created a decoy database under {@code C:\c\Users\...} and
 * deleted from that. The real hash survived, the operator's session cookie stayed valid
 * and /setup-password never came up.
 *
 * <p>Two independent halves failed together, so both are pinned: the URL is relative, so
 * bash and java.exe name the same file, and success is read off H2's "Update count" line
 * rather than the exit status, which is 0 even for a statement that failed. Half these
 * tests assert on the shipped script and half execute H2, because the script's guards are
 * only worth having while H2 still behaves the way they assume — an upgrade that changed
 * either would otherwise leave them in place but inert. Mirrors
 * {@code LogRetentionConfigTest}, which fails the build on a shipped config that stopped
 * asking for what it needs.
 */
class ResetCommandContractTest extends UnitTest {

    private static final Pattern JDBC_URL =
            Pattern.compile("^\\s*local jdbc_url=\"([^\"]+)\"", Pattern.MULTILINE);

    private static String script() throws Exception {
        var file = new File(Play.applicationPath, "jclaw.sh");
        assertTrue(file.isFile(), "missing launcher: " + file);
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private static String shippedUrl() throws Exception {
        var matcher = JDBC_URL.matcher(script());
        assertTrue(matcher.find(), "jclaw.sh no longer declares a jdbc_url for the reset");
        return matcher.group(1);
    }

    /** The shipped URL with its relative database swapped for one under a temp directory,
     *  so every other connection setting stays exactly as the script ships it. */
    private static String retargeted(Path database) throws Exception {
        var url = shippedUrl();
        assertTrue(url.contains("./data/jclaw"), "unexpected reset URL shape: " + url);
        return url.replace("./data/jclaw", database.toString());
    }

    private static String runShell(String url, String sql) throws SQLException {
        var captured = new ByteArrayOutputStream();
        var shell = new Shell();
        try (var out = new PrintStream(captured, true, StandardCharsets.UTF_8);
             InputStream noStdin = InputStream.nullInputStream()) {
            shell.setOut(out);
            shell.setErr(out);
            shell.setIn(noStdin);
            shell.runTool("-url", url, "-sql", sql);
        }
        catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    @Test
    void resetUrlIsRelativeSoBashAndJavaNameTheSameFile() throws Exception {
        assertEquals("jdbc:h2:file:./data/jclaw", shippedUrl().split(";", 2)[0],
                "the reset URL must stay relative: an absolute $SCRIPT_DIR is an MSYS path under "
                        + "Git Bash, and the native java.exe resolves it against another drive root");
    }

    @Test
    void resetRunsTheShellFromTheInstallDirectory() throws Exception {
        var text = script();
        var start = text.indexOf("==> Clearing auth.admin.passwordHash");
        var end = text.indexOf("org.h2.tools.Shell", start);
        assertTrue(start >= 0 && end > start, "the reset's H2 invocation moved");
        assertTrue(text.substring(start, end).contains("cd \"$SCRIPT_DIR\""),
                "a relative jdbc_url names the install's database only while the shell runs there");
    }

    @Test
    void resetUrlRefusesToAutoCreateAMissingDatabase() throws Exception {
        var dir = Files.createTempDirectory("jclaw-reset-contract");
        try {
            var url = retargeted(dir.resolve("jclaw"));
            assertThrows(SQLException.class, () -> runShell(url, "SELECT 1;"),
                    "IFEXISTS=TRUE is what turns a mis-resolved path into a refused connect; "
                            + "without it H2 creates whatever database the URL names");
            assertFalse(Files.exists(dir.resolve("jclaw.mv.db")),
                    "a refused connect must leave no decoy database behind");
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void theH2ShellReportsSuccessForAFailedStatement() throws Exception {
        var dir = Files.createTempDirectory("jclaw-reset-contract");
        try {
            var database = dir.resolve("jclaw");
            runShell("jdbc:h2:file:" + database + ";MODE=MYSQL", "CREATE TABLE unrelated(x INT);");

            // Returning instead of throwing IS the finding: the CLI exits 0 here, which is why
            // the script cannot read its status as proof that the DELETE ran.
            var output = runShell(retargeted(database),
                    "DELETE FROM config WHERE config_key='auth.admin.passwordHash';");

            assertTrue(output.contains("not found"), "expected a failed statement, got: " + output);
            assertFalse(output.contains("Update count:"),
                    "a statement that failed must not report an update count: " + output);
        }
        finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void resetTreatsTheUpdateCountAsTheOnlyProof() throws Exception {
        assertTrue(script().contains("grep -q 'Update count:'"),
                "reset must assert on H2's update count; its exit status is 0 for a failed statement");
    }
}
