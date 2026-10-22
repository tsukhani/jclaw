import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Every class that holds a sidecar daemon is stopped by {@code ShutdownJob}, which lists them by hand (JF-20). One it
 * misses keeps running after a stop, holding its memory and port until its idle timeout.
 */
class SidecarShutdownConformanceTest extends UnitTest {

    @Test
    void everySidecarManagerIsStoppedAtShutdown() throws IOException {
        var shutdownJob = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "app", "jobs", "ShutdownJob.java"));
        var managers = SidecarDefaultPortsConformanceTest.managerSources().keySet().stream()
                .map(file -> file.getFileName().toString().replace(".java", ""))
                .toList();
        assertTrue(managers.size() >= SidecarDefaultPortsConformanceTest.KNOWN_MANAGERS, () -> "scan found only " + managers);
        var unstopped = managers.stream().filter(manager -> !shutdownJob.contains(manager + "::stop")).toList();
        assertEquals(List.of(), unstopped, () -> "ShutdownJob does not stop " + unstopped);
    }
}
