import com.google.gson.JsonObject;
import models.Agent;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import tools.FileSystemTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** UnifiedPatchParser and FsPatchApplier round trips through the applyPatch action; FileSystemToolsPatchTest owns the named cases. */
class ApplyPatchPropertyTest extends UnitTest {

    private static final String AGENT_NAME = "patch-property-agent";

    /** A distinct file per input: an Add File op onto an existing path is a validation error, not a round trip. */
    private static final AtomicInteger FILE_SEQ = new AtomicInteger();

    /** The workspace outlives the JVM, so the counter alone would collide with an aborted run's files. */
    private static final String RUN_TAG = Long.toHexString(System.nanoTime());

    record UpdateCase(List<String> lines, int targetSeed, String replacement) {}

    private static final Generator<List<String>> ADDED_LINES =
            Generator.listsOf(IntDistribution.uniform(1, 6), PropertyInputs.letters(0, 16));

    private static final Generator<UpdateCase> UPDATES = Generator.from(env -> new UpdateCase(
            env.generate(Generator.listsOf(IntDistribution.uniform(2, 8), PropertyInputs.letters(1, 16))),
            env.generate(Generator.integers(0, 7)),
            env.generate(PropertyInputs.letters(1, 16))));

    private FileSystemTools tool;
    private Agent agent;

    @BeforeEach
    void seedWorkspace() {
        Fixtures.deleteDatabase();
        tool = new FileSystemTools();
        agent = AgentService.create(AGENT_NAME, "openrouter", "gpt-4.1");
    }

    @AfterAll
    static void removeGeneratedFiles() {
        deleteRecursively(AgentService.workspacePath(AGENT_NAME));
    }

    // 40 iterations: three small filesystem round trips each, about 1 ms.
    @Test
    void addFileReproducesTheIntendedContent() {
        PropertyInputs.check(40, ADDED_LINES, lines -> {
            var name = nextFileName();
            var body = new StringBuilder("*** Begin Patch\n*** Add File: ").append(name).append('\n');
            for (var line : lines) body.append('+').append(line).append('\n');
            body.append("*** End of File\n*** End Patch\n");

            var result = applyPatch(body.toString());
            assertEquals(String.join("\n", lines), readBack(name),
                    () -> "Add File round trip lost content. lines=" + lines + " applyPatch said: " + result);
        });
    }

    // 40 iterations: the same filesystem cost as the Add File property.
    @Test
    void updateChunkReplacesExactlyTheTargetedLine() {
        PropertyInputs.check(40, UPDATES, update -> {
            // An unanchored chunk must match exactly once, so an index prefix keeps the lines from
            // containing one another: "ab" and "xab" are distinct, but "ab\n" occurs twice on disk.
            var numbered = new ArrayList<String>(update.lines().size());
            for (int i = 0; i < update.lines().size(); i++) numbered.add("L" + i + "-" + update.lines().get(i));
            int target = update.targetSeed() % numbered.size();

            var name = nextFileName();
            AgentService.writeWorkspaceFile(AGENT_NAME, name, String.join("\n", numbered) + "\n");
            var result = applyPatch("*** Begin Patch\n*** Update File: %s\n-%s\n+%s\n*** End of File\n*** End Patch\n"
                    .formatted(name, numbered.get(target), update.replacement()));

            var expected = new ArrayList<>(numbered);
            expected.set(target, update.replacement());
            assertEquals(String.join("\n", expected) + "\n", readBack(name),
                    () -> "Update chunk round trip diverged. lines=" + numbered + " target=" + target
                            + " replacement=" + update.replacement() + " applyPatch said: " + result);
        });
    }

    private static String nextFileName() {
        return "prop-" + RUN_TAG + "-" + FILE_SEQ.incrementAndGet() + ".txt";
    }

    private String applyPatch(String patchBody) {
        var args = new JsonObject();
        args.addProperty("action", "applyPatch");
        args.addProperty("patch", patchBody);
        return tool.execute(args.toString(), agent);
    }

    /**
     * Reads through the filesystem rather than {@code AgentService.readWorkspaceFile}: the patch
     * applier writes via {@code FsWriter}, which does not invalidate the workspace file cache.
     */
    private static String readBack(String name) {
        try {
            return Files.readString(AgentService.acquireWorkspacePath(AGENT_NAME, name));
        } catch (IOException e) {
            throw new AssertionError("could not read back " + name + ": " + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException _) {
                    // best effort — a leftover file costs disk, never correctness
                }
            });
        } catch (IOException _) {
            // best effort
        }
    }
}
