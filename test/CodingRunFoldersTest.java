import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.CodingRunFolders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** One folder per coding run under a parent directory, and a named folder a run continues in. */
class CodingRunFoldersTest extends UnitTest {

    private Path parent;
    private Path outside;

    @BeforeEach
    void setup() throws IOException {
        parent = Files.createTempDirectory("jclaw-coding-parent-").toRealPath();
        outside = Files.createTempDirectory("jclaw-coding-outside-").toRealPath();
    }

    @AfterEach
    void teardown() throws IOException {
        for (var root : new Path[] {parent, outside}) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void anUnnamedRunGetsANewFolderNamedFromItsTask() throws IOException {
        var first = CodingRunFolders.open(parent, "Create Fibonacci program!", null);
        var second = CodingRunFolders.open(parent, "Create Fibonacci program!", null);

        assertEquals(parent.resolve("create-fibonacci-program"), first.dir());
        assertEquals(parent.resolve("create-fibonacci-program-2"), second.dir(),
                "a new folder never takes an existing name");
        assertFalse(first.existing());
        assertFalse(second.existing());
        assertTrue(Files.isDirectory(second.dir()));
    }

    @Test
    void aNamedFolderIsCreatedOnceAndContinuedInAfterwards() throws IOException {
        var created = CodingRunFolders.open(parent, "any task", "fibonacci");
        Files.writeString(created.dir().resolve("fib.py"), "print(1)\n");

        var continued = CodingRunFolders.open(parent, "a different task", " fibonacci ");

        assertFalse(created.existing(), "the first run makes the folder");
        assertTrue(continued.existing(), "the second run finds it");
        assertEquals(created.dir(), continued.dir());
        assertTrue(Files.exists(continued.dir().resolve("fib.py")), "the earlier run's file is still there");
    }

    @Test
    void theParentIsCreatedWhenItDoesNotExistYet() throws IOException {
        var fresh = parent.resolve("not/yet/there");

        var run = CodingRunFolders.open(fresh, "task one", null);

        assertEquals(fresh.resolve("task-one"), run.dir());
        assertTrue(Files.isDirectory(run.dir()));
    }

    @Test
    void aNameThatLeavesTheParentThroughASymlinkIsRefused() throws IOException {
        Files.createSymbolicLink(parent.resolve("escape"), outside);

        var e = assertThrows(IOException.class, () -> CodingRunFolders.open(parent, "task", "escape"));

        assertTrue(e.getMessage().contains("does not resolve inside"), e.getMessage());
        try (var listing = Files.list(outside)) {
            assertEquals(0, listing.count(), "nothing may be created through the link");
        }
    }

    @Test
    void aFolderNameIsOneNameNeverAPath() {
        for (var ok : new String[] {"fibonacci", "my project", "v2.1_final", " padded "}) {
            assertNull(CodingRunFolders.nameRejection(ok), ok);
        }
        for (var bad : new String[] {"", "  ", ".", "..", "a/b", "../up", "/abs", "a\\b", "~home", "x".repeat(101)}) {
            assertEquals("'folder' must be one folder name of at most 100 characters, not a path.",
                    CodingRunFolders.nameRejection(bad), "name: [" + bad + "]");
        }
    }

    @Test
    void theParentSettingTakesAnAbsolutePathOrOneUnderHome() throws IOException {
        for (var ok : new String[] {"", "  ", "~", "~/coding-runs", parent.toString(), parent.resolve("new").toString()}) {
            assertNull(CodingRunFolders.parentRejection(ok), ok);
        }
        assertEquals("subagent.acp.workdir must be an absolute path or start with ~/; blank means each agent's "
                + "own workspace.", CodingRunFolders.parentRejection("relative/coding"));
        var file = Files.createFile(parent.resolve("a-file"));
        assertEquals("subagent.acp.workdir names a file, not a directory.",
                CodingRunFolders.parentRejection(file.toString()));
    }

    @Test
    void aLeadingTildeIsTheHomeDirectory() {
        var home = Path.of("/home/operator");

        assertEquals(home, CodingRunFolders.expand("~", home));
        assertEquals(home.resolve("Programming"), CodingRunFolders.expand("~/Programming", home));
        assertEquals(Path.of("/srv/coding"), CodingRunFolders.expand("/srv/coding", home));
        assertEquals(Path.of("~odd"), CodingRunFolders.expand("~odd", home), "only ~ and ~/ are the home directory");
    }
}
