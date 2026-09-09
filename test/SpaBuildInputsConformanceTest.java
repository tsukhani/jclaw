import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SPA staleness gate in {@code jclaw.sh} decides whether a restart rebuilds the bundle, and
 * it decides from file mtimes under a fixed set of roots. A build input outside those roots is
 * invisible to it, so an edit ships nothing while the gate reports "up to date": the guide's
 * markdown was exactly that until JCLAW-1178. This pins that every import escaping
 * {@code frontend/} resolves under a root the gate probes.
 */
class SpaBuildInputsConformanceTest extends UnitTest {

    /** The roots on the gate's own `find` line, as `$SCRIPT_DIR`-relative paths. */
    private static final Pattern GATE_ROOT = Pattern.compile("\\$SCRIPT_DIR/([A-Za-z0-9_./-]+)");

    /** A static import whose specifier climbs out of its own directory. */
    private static final Pattern ESCAPING_IMPORT = Pattern.compile("from\\s+'((?:\\.\\./)+[^']+)'");

    private static final Set<String> NOT_BUNDLE_INPUT =
            Set.of("node_modules", ".nuxt", ".output", ".vite", "dist", "test");

    // The guide's fourteen markdown files are the escaping imports today; a regex that stopped
    // matching must fail rather than pass by finding nothing.
    private static final int KNOWN_ESCAPING_IMPORTS = 14;

    private static Path repo() {
        return Path.of(Play.applicationPath.getAbsolutePath());
    }

    /** The directories the gate probes for staleness, read from the script itself. */
    private static List<Path> gateRoots() throws IOException {
        var script = Files.readString(repo().resolve("jclaw.sh"));
        int start = script.indexOf("spa_stale_file=\"$(find ");
        assertTrue(start >= 0, "the SPA staleness gate no longer assigns spa_stale_file — update this test with it");
        var findLine = script.substring(start, script.indexOf('\n', start));
        var roots = new ArrayList<Path>();
        var m = GATE_ROOT.matcher(findLine);
        while (m.find()) {
            roots.add(repo().resolve(m.group(1)).normalize());
        }
        assertFalse(roots.isEmpty(), () -> "no roots parsed from the gate line: " + findLine);
        for (var root : roots) {
            assertTrue(Files.isDirectory(root), () -> "the gate probes a directory that does not exist: " + root);
        }
        return roots;
    }

    /** Every file the SPA build reads from outside `frontend/`, resolved and de-queried. */
    private static Set<Path> escapingImports() throws IOException {
        var frontend = repo().resolve("frontend");
        var escaping = new TreeSet<Path>();
        Files.walkFileTree(frontend, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return NOT_BUNDLE_INPUT.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                var name = file.toString();
                if (!name.endsWith(".ts") && !name.endsWith(".vue") && !name.endsWith(".js")) {
                    return FileVisitResult.CONTINUE;
                }
                var m = ESCAPING_IMPORT.matcher(Files.readString(file));
                while (m.find()) {
                    // Vite query suffixes (?raw, ?url) are not part of the path on disk.
                    var specifier = m.group(1).split("\\?", 2)[0];
                    var resolved = file.getParent().resolve(specifier).normalize();
                    if (!resolved.startsWith(frontend)) {
                        escaping.add(resolved);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return escaping;
    }

    @Test
    public void everyBuildInputOutsideTheFrontendSitsUnderARootTheStalenessGateProbes() throws IOException {
        var roots = gateRoots();
        var inputs = escapingImports();
        assertTrue(inputs.size() >= KNOWN_ESCAPING_IMPORTS,
                () -> "expected at least " + KNOWN_ESCAPING_IMPORTS + " imports reaching outside frontend/, found " + inputs);
        for (var input : inputs) {
            assertTrue(Files.exists(input), () -> "imported file does not exist: " + input);
            assertTrue(roots.stream().anyMatch(input::startsWith),
                    () -> "the SPA staleness gate cannot see " + repo().relativize(input)
                            + ", so editing it would not trigger a rebuild (JCLAW-1178). Gate roots: " + roots);
        }
    }
}
