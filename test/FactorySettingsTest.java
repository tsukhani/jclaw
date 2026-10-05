import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.factory.FactoryHome;
import services.factory.FactoryProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;

/** {@link FactoryHome}'s settings grammar, validation, atomic rewrite and sandbox-to-story lookup (JCLAW-1392). */
class FactorySettingsTest extends UnitTest {

    @TempDir
    Path dir;

    private static String check(String key, String value, OptionalInt cpus) {
        return FactoryHome.validate(Map.of(key, value), cpus);
    }

    @Test
    void integerKeysAcceptOneAndRefuseZero() {
        for (var key : new String[] {"FACTORY_MAX_PARALLEL", "FACTORY_CPUS", "FACTORY_POLL_SECONDS"}) {
            assertNull(check(key, "1", OptionalInt.empty()), key);
            assertNull(check(key, "2147483647", OptionalInt.empty()), key);
            for (var bad : new String[] {"0", "-1", "1.5", "abc", " 2", "2 ", "", "+2", "2147483648"}) {
                var err = check(key, bad, OptionalInt.empty());
                assertNotNull(err, () -> key + "=" + bad);
                assertTrue(err.contains(key), err);
            }
        }
    }

    @Test
    void cpusIsCappedByTheDockerCountWhenKnown() {
        assertNull(check("FACTORY_CPUS", "8", OptionalInt.of(8)));
        assertNull(check("FACTORY_CPUS", "1", OptionalInt.of(8)));
        assertNotNull(check("FACTORY_CPUS", "9", OptionalInt.of(8)));
        assertNull(check("FACTORY_CPUS", "64", OptionalInt.empty()));
        // The cap is CPUS-only.
        assertNull(check("FACTORY_MAX_PARALLEL", "9", OptionalInt.of(8)));
    }

    @Test
    void modelMustBeNonBlankAndUnknownKeysAreRefused() {
        assertNull(check("FACTORY_MODEL", "claude-opus-5-5", OptionalInt.empty()));
        assertNotNull(check("FACTORY_MODEL", "", OptionalInt.empty()));
        assertNotNull(check("FACTORY_MODEL", "   ", OptionalInt.empty()));
        assertNotNull(check("FACTORY_MODEL", "a\nFACTORY_CPUS=99", OptionalInt.empty()));
        var err = check("FACTORY_HOME", "/tmp", OptionalInt.empty());
        assertNotNull(err);
        assertTrue(err.contains("FACTORY_HOME"), err);
        assertNotNull(FactoryHome.validate(Map.of(), OptionalInt.empty()));
    }

    @Test
    void readSettingsFollowsTheHarnessGrammar() throws Exception {
        assertTrue(FactoryHome.readSettings(dir.resolve("missing.env")).isEmpty());

        var file = dir.resolve("settings.env");
        Files.writeString(file, """
                # FACTORY_CPUS=1
                  FACTORY_CPUS=4
                not a setting line
                FACTORY_MODEL=a=b
                FACTORY_CPUS=5
                """);
        var read = FactoryHome.readSettings(file);
        assertEquals("5", read.get("FACTORY_CPUS"));
        assertEquals("a=b", read.get("FACTORY_MODEL"));
        assertEquals(2, read.size(), read::toString);
    }

    @Test
    void writeSettingsKeepsOtherLinesReplacesInPlaceAndAppends() throws Exception {
        var file = dir.resolve("settings.env");
        Files.writeString(file, """
                # my settings
                FACTORY_CPUS=4
                OTHER=kept

                FACTORY_MODEL=claude-x
                """);
        var updates = new java.util.LinkedHashMap<String, String>();
        updates.put("FACTORY_CPUS", "3");
        updates.put("FACTORY_MAX_PARALLEL", "5");
        FactoryHome.writeSettings(file, updates);

        assertEquals("""
                # my settings
                FACTORY_CPUS=3
                OTHER=kept

                FACTORY_MODEL=claude-x
                FACTORY_MAX_PARALLEL=5
                """, Files.readString(file));
        try (var listing = Files.list(dir)) {
            assertEquals(1, listing.count(), "a temp file was left behind");
        }
    }

    @Test
    void writeSettingsCreatesAMissingFile() throws Exception {
        var file = dir.resolve("settings.env");
        FactoryHome.writeSettings(file, Map.of("FACTORY_POLL_SECONDS", "60"));
        assertEquals("FACTORY_POLL_SECONDS=60\n", Files.readString(file));
    }

    @Test
    void tailKeepsTheLastFortyLinesWithinFourThousandCharacters() {
        var sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) sb.append("line ").append(i).append('\n');
        var tail = new FactoryProcess.ExecResult(0, sb.toString(), false).tail();
        assertEquals(40, tail.split("\n").length);
        assertTrue(tail.startsWith("line 61\n"), tail);
        assertTrue(tail.endsWith("line 100"), tail);

        var longTail = new FactoryProcess.ExecResult(0, "x".repeat(10_000), false).tail();
        assertEquals(4000, longTail.length());
    }

    @Test
    void theStoryIsReadFromTheWorktreeTheSandboxMounts() {
        var wt = "/Users/op/.jclaw-factory/jclaw/.sandcastle/worktrees/";
        assertEquals("JCLAW-1392", FactoryHome.storyFromMounts(wt + "agent-JCLAW-1392\n/Users/op/.jclaw-factory/jclaw/.git\n"));
        assertEquals("GH-12", FactoryHome.storyFromMounts("/host_mnt" + wt + "factory-land-GH-12\n"));
        assertNull(FactoryHome.storyFromMounts(wt + "factory-plan\n"), "the planning sandbox holds no story");
        assertNull(FactoryHome.storyFromMounts(wt + "agent-JCLAW-1392/sub\n"), "only the worktree root counts");
        assertNull(FactoryHome.storyFromMounts("/elsewhere/agent-JCLAW-1392\n"));
        assertNull(FactoryHome.storyFromMounts(""));
    }
}
