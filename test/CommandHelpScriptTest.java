import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs jclaw.sh's help routing: {@code help <cmd>} prints the same help as {@code <cmd> --help} for every
 * command {@code usage_for} has an arm for, and the banner for any other name. The PATH holds only the
 * system directories, so no {@code jclaw} shim turns {@code ./jclaw.sh} in the usage lines into {@code jclaw}.
 */
class CommandHelpScriptTest extends UnitTest {

    private static final Path REPO = Path.of(Play.applicationPath.getAbsolutePath());

    @Test
    void helpGraphevalPrintsGraphevalsOwnUsage() throws Exception {
        var help = jclaw("help", "grapheval");

        assertEquals("Usage: ./jclaw.sh grapheval run --agent NAME --decision-model ID [options]",
                help.lines().findFirst().orElseThrow());
        assertEquals(jclaw("grapheval", "--help"), help);
    }

    @Test
    void helpForEveryCommandUsageForKnowsPrintsThatCommandsOwnHelp() throws Exception {
        var banner = jclaw("help");
        for (var command : caseLabels("usage_for")) {
            var help = jclaw("help", command);

            assertEquals(jclaw(command, "--help"), help, "help " + command);
            assertNotEquals(banner, help, "help " + command + " printed the banner");
        }
    }

    @Test
    void isKnownCommandNamesExactlyTheCommandsUsageForHasAnArmFor() throws Exception {
        assertEquals(new TreeSet<>(caseLabels("usage_for")), new TreeSet<>(caseLabels("is_known_command")));
    }

    @Test
    void helpForANameThatIsNoCommandPrintsTheBanner() throws Exception {
        var banner = jclaw("help");

        assertEquals("Usage: ./jclaw.sh [options] <https|no-https|secret|setup|init-worktree|reset|start|stop|"
                + "restart|status|logs|loadtest|scrapetest|grapheval|evals|diagnostics|check|check-arch|test|e2e|"
                + "dist|bundle|backup|restore|repair|compact|db-clean|db-status|completion|shim|uninstall|help>",
                banner.lines().findFirst().orElseThrow());
        assertEquals(banner, jclaw("help", "no-such-command"));
    }

    // --- helpers ---

    /** The command names a {@code case} in the jclaw.sh function {@code name} matches, its {@code *)} arm aside. */
    private static List<String> caseLabels(String name) throws Exception {
        var script = Files.readString(REPO.resolve("jclaw.sh"));
        var function = Pattern.compile("(?ms)^" + name + "\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
        assertTrue(function.find(), "jclaw.sh no longer defines " + name);
        var labels = new ArrayList<String>();
        var arm = Pattern.compile("(?m)^[ \\t]+([^\\s()*#][^\\s()]*)\\)").matcher(function.group());
        while (arm.find()) {
            labels.addAll(Arrays.asList(arm.group(1).split("\\|")));
        }
        assertFalse(labels.isEmpty(), name + " has no case arms");
        return labels;
    }

    private static String jclaw(String... args) throws Exception {
        var command = new ArrayList<>(List.of("/bin/bash", REPO.resolve("jclaw.sh").toString()));
        command.addAll(List.of(args));
        var pb = new ProcessBuilder(command).directory(REPO.toFile()).redirectErrorStream(true);
        pb.environment().keySet().removeIf(variable -> variable.startsWith("GIT_"));
        pb.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "jclaw.sh " + String.join(" ", args) + " hung");
        assertEquals(0, p.exitValue(), "jclaw.sh " + String.join(" ", args) + ": " + out);
        return out;
    }
}
