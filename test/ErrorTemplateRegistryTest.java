import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

/**
 * JCLAW-60: the registry is partitioned one file per surface, so the stories that register
 * templates land on disjoint paths.
 *
 * <p>Partitioning adds a failure mode a single table did not have: a surface file that exists
 * but is never merged into {@code ErrorTemplates} registers nothing and nothing complains. The
 * codes those files will carry are not {@code ApiResponses} constants, so
 * {@code ErrorTemplateTest.everyCentralisedApiResponsesCodeHasItsOwnTemplate} — the reflection
 * guard that covers the API surface — cannot see them, and the templates would silently fall
 * back instead.
 */
class ErrorTemplateRegistryTest extends UnitTest {

    @Test
    void everySurfaceTableIsMergedIntoTheRegistry() throws IOException {
        var utils = Play.applicationPath.toPath().resolve("app").resolve("utils");
        var registry = Files.readString(utils.resolve("ErrorTemplates.java"));

        var surfaces = new ArrayList<String>();
        try (Stream<Path> files = Files.list(utils)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("ErrorTemplates.java") && !n.equals("ErrorTemplates.java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .sorted()
                    .forEach(surfaces::add);
        }

        // The assertion below is an ABSENCE, so pin that the listing found the surfaces at all.
        assertTrue(surfaces.size() >= 5, "expected one file per surface, saw " + surfaces);

        var unmerged = surfaces.stream().filter(s -> !registry.contains(s + ".templates()")).toList();
        assertTrue(unmerged.isEmpty(),
                "a surface table nothing merges registers nothing; add it to the merge in "
                        + "ErrorTemplates: " + unmerged);
    }
}
