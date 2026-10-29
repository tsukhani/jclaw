import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestPortsTest extends UnitTest {

    @Test
    void randomPortsStayInTheRangeAndAreNeverHandedOutTwice() throws IOException {
        var seen = new HashSet<Integer>();
        for (int i = 0; i < 20; i++) {
            int port = TestPorts.freeNonEphemeral();
            assertTrue(port >= TestPorts.FIRST && port <= TestPorts.LAST, "port " + port + " is outside the range");
            assertTrue(seen.add(port), "port " + port + " was handed out twice");
        }
    }

    @Test
    void theHostsEphemeralRangeStartsAboveTheHelpersRange() throws Exception {
        Integer first = hostEphemeralFirst();
        assumeTrue(first != null, "this host does not say where its ephemeral range starts");
        assertTrue(first > TestPorts.LAST,
                "this host's ephemeral range starts at " + first + ", inside TestPorts' range: a freed port could be taken");
    }

    @Test
    void itSkipsAPortSomethingAlreadyListensOn() throws IOException {
        try (var held = TestPorts.bindNonEphemeral()) {
            int taken = held.getLocalPort();
            var calls = new int[1];
            // After the held port, fresh draws: the test must not depend on a port it probed staying free.
            int port = TestPorts.freeNonEphemeral(() -> calls[0]++ == 0 ? taken : randomInRange());

            assertTrue(calls[0] >= 2, "the held port was not tried first and skipped");
            assertNotEquals(taken, port);
        }
    }

    @Test
    void aFreeFirstCandidateIsReturnedAsIs() throws IOException {
        var calls = new int[1];
        int first = randomInRange();
        assumeTrue(isFree(first), "the drawn port is in use on this host");

        assertEquals(first, TestPorts.freeNonEphemeral(() -> calls[0]++ == 0 ? first : randomInRange()));
        assertEquals(1, calls[0]);
    }

    @Test
    void theRangesEdgesAreInsideAndTheirNeighboursOutside() throws IOException {
        assumeTrue(isFree(TestPorts.FIRST) && isFree(TestPorts.LAST), "an edge port is in use on this host");
        assertEquals(20000, TestPorts.freeNonEphemeral(() -> 20000));
        assertEquals(32767, TestPorts.freeNonEphemeral(() -> 32767));
        assertEquals("port 19999 is outside 20000-32767",
                assertThrows(IllegalArgumentException.class, () -> TestPorts.freeNonEphemeral(() -> 19999)).getMessage());
        assertEquals("port 32768 is outside 20000-32767",
                assertThrows(IllegalArgumentException.class, () -> TestPorts.freeNonEphemeral(() -> 32768)).getMessage());
    }

    @Test
    void sixtyFourTakenCandidatesFailWithTheLastBindErrorAsTheCause() throws IOException {
        try (var held = TestPorts.bindNonEphemeral()) {
            int taken = held.getLocalPort();
            var thrown = assertThrows(IOException.class, () -> TestPorts.freeNonEphemeral(() -> taken));

            assertEquals("no free port in 20000-32767 after 64 tries", thrown.getMessage());
            assertTrue(thrown.getCause() instanceof BindException, String.valueOf(thrown.getCause()));
        }
    }

    @Test
    void sixtyThreeTakenCandidatesAndOneFreeStillFindTheFreeOne() throws IOException {
        try (var held = TestPorts.bindNonEphemeral()) {
            int taken = held.getLocalPort();
            var calls = new int[1];
            int port = TestPorts.freeNonEphemeral(() -> calls[0]++ < 63 ? taken : randomInRange());

            assertNotEquals(taken, port);
            assertTrue(calls[0] >= 64, "only " + calls[0] + " candidates were tried");
        }
    }

    private static int randomInRange() {
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(TestPorts.FIRST, TestPorts.LAST + 1);
    }

    private static boolean isFree(int port) {
        try (var _ = new ServerSocket(port)) {
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    /** The first port of the host's ephemeral range (Linux procfs, else macOS sysctl), or null when neither says. */
    private static Integer hostEphemeralFirst() throws Exception {
        var linux = Path.of("/proc/sys/net/ipv4/ip_local_port_range");
        // procfs reports size 0, and Files.readString then returns only the first byte (JDK 21 and 25 alike).
        if (Files.isReadable(linux)) return Integer.parseInt(Files.readAllLines(linux).getFirst().trim().split("\\s+")[0]);
        try {
            var sysctl = new ProcessBuilder("sysctl", "-n", "net.inet.ip.portrange.first").redirectErrorStream(true).start();
            var out = new String(sysctl.getInputStream().readAllBytes()).trim();
            return sysctl.waitFor() == 0 && out.matches("\\d+") ? Integer.valueOf(out) : null;
        } catch (IOException _) {
            return null;
        }
    }
}
