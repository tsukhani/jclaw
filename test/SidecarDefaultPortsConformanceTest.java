import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;


/**
 * Every sidecar manager compiles in a default port, and two sharing one means whichever spawns
 * second dies on the bind with a "port squatted" diagnosis that blames the wrong process
 * (JCLAW-1172). A source scan, because the managers build their daemons in static initializers
 * that a test should not have to trigger.
 */
class SidecarDefaultPortsConformanceTest extends UnitTest {

    private static final Pattern DAEMON = Pattern.compile("new LocalSidecarDaemon\\(");
    private static final Pattern DAEMON_CONFIG = Pattern.compile(
            "new LocalSidecarDaemon\\.Config\\(\\s*\"(sidecar/[^\"]+)\"\\s*,\\s*\"[^\"]+\"\\s*,\\s*[^,]+,\\s*(\\d+)");

    // Seven managers exist today; a regex that silently stopped matching must not pass by finding none.
    static final int KNOWN_MANAGERS = 7;

    /** The source of each file in {@code app/} that constructs a {@code LocalSidecarDaemon}, by path. */
    static Map<Path, String> managerSources() throws IOException {
        var sources = new TreeMap<Path, String>();
        try (Stream<Path> files = Files.walk(Path.of(Play.applicationPath.getAbsolutePath(), "app"))) {
            for (var file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                var source = Files.readString(file);
                if (DAEMON.matcher(source).find()) sources.put(file, source);
            }
        }
        return sources;
    }

    /** Each sidecar directory's literal default port, and the manager files whose port is not a literal. */
    private record Scan(Map<String, Integer> ports, List<String> unreadable) {}

    private static Scan scan() throws IOException {
        var root = Path.of(Play.applicationPath.getAbsolutePath());
        Map<String, Integer> ports = new LinkedHashMap<>();
        var unreadable = new ArrayList<String>();
        for (var source : managerSources().entrySet()) {
            var m = DAEMON_CONFIG.matcher(source.getValue());
            long read = 0;
            while (m.find()) {
                ports.put(m.group(1), Integer.parseInt(m.group(2)));
                read++;
            }
            // A port passed as a constant is not read, and the port checks would skip that sidecar.
            if (read < DAEMON.matcher(source.getValue()).results().count()) unreadable.add(root.relativize(source.getKey()).toString());
        }
        return new Scan(ports, unreadable);
    }

    @Test
    void everySidecarManagerDeclaresADistinctDefaultPort() throws IOException {
        var root = Path.of(Play.applicationPath.getAbsolutePath());
        var scan = scan();
        var ports = scan.ports();
        assertEquals(List.of(), scan.unreadable(), () -> "no literal default port to check in " + scan.unreadable());
        assertTrue(ports.size() >= KNOWN_MANAGERS, () -> "scan found only " + ports);
        for (var dir : ports.keySet()) {
            assertTrue(Files.isDirectory(root.resolve(dir)), () -> dir + " has no sidecar directory");
        }
        Map<Integer, List<String>> byPort = new HashMap<>();
        ports.forEach((dir, port) -> byPort.computeIfAbsent(port, _ -> new ArrayList<>()).add(dir));
        var shared = byPort.entrySet().stream().filter(e -> e.getValue().size() > 1).toList();
        assertEquals(List.of(), shared, () -> "default ports shared between sidecars: " + shared);
    }

    @Test
    void worktreeTestServersNeverTakeASidecarDefaultPort() throws IOException {
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "jclaw.sh"));
        var band = Pattern.compile("if \\(\\( port >= (\\d+) && port <= (\\d+) \\)\\)").matcher(script);
        assertTrue(band.find(), "jclaw.sh's ensure_play_test_port no longer skips the sidecar ports");
        int lo = Integer.parseInt(band.group(1));
        int hi = Integer.parseInt(band.group(2));
        var ports = scan().ports();
        assertTrue(ports.size() >= KNOWN_MANAGERS, () -> "scan found only " + ports);
        // ensure_play_test_port picks from 9100-9899, so a sidecar port there must be inside the band it skips.
        var exposed = ports.entrySet().stream()
                .filter(e -> e.getValue() >= 9100 && e.getValue() <= 9899 && (e.getValue() < lo || e.getValue() > hi))
                .map(Map.Entry::toString).toList();
        assertEquals(List.of(), exposed,
                () -> "a worktree's test server can take these sidecar ports; widen the band ensure_play_test_port skips ("
                        + lo + "-" + hi + "): " + exposed);
    }
}
