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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every sidecar manager compiles in a default port, and two sharing one means whichever spawns
 * second dies on the bind with a "port squatted" diagnosis that blames the wrong process
 * (JCLAW-1172). A source scan, because the managers build their daemons in static initializers
 * that a test should not have to trigger.
 */
class SidecarDefaultPortsConformanceTest extends UnitTest {

    private static final Pattern DAEMON_CONFIG = Pattern.compile(
            "new LocalSidecarDaemon\\.Config\\(\\s*\"(sidecar/[^\"]+)\"\\s*,\\s*\"[^\"]+\"\\s*,\\s*[^,]+,\\s*(\\d+)");

    // Seven managers exist today; a regex that silently stopped matching must not pass by finding none.
    private static final int KNOWN_MANAGERS = 7;

    @Test
    public void everySidecarManagerDeclaresADistinctDefaultPort() throws IOException {
        var root = Path.of(Play.applicationPath.getAbsolutePath());
        Map<String, Integer> ports = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root.resolve("app/services"))) {
            for (var file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                var m = DAEMON_CONFIG.matcher(Files.readString(file));
                while (m.find()) {
                    ports.put(m.group(1), Integer.parseInt(m.group(2)));
                }
            }
        }
        assertTrue(ports.size() >= KNOWN_MANAGERS, () -> "scan found only " + ports);
        for (var dir : ports.keySet()) {
            assertTrue(Files.isDirectory(root.resolve(dir)), () -> dir + " has no sidecar directory");
        }
        Map<Integer, List<String>> byPort = new HashMap<>();
        ports.forEach((dir, port) -> byPort.computeIfAbsent(port, _ -> new ArrayList<>()).add(dir));
        var shared = byPort.entrySet().stream().filter(e -> e.getValue().size() > 1).toList();
        assertEquals(List.of(), shared, () -> "default ports shared between sidecars: " + shared);
    }
}
