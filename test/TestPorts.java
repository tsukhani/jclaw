import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/**
 * A port for a test to hand to a script that binds or probes it later (JF-167). Port 0 comes from the OS's
 * ephemeral range (49152-65535 on macOS, 32768-60999 by default on Linux), where any of the suite's other
 * port-0 binds can take a freed port before the script gets to it; this range sits below all of them.
 */
public final class TestPorts {

    /** Lowest port handed out: above PLAY_TEST_PORT (9100-9899), the sidecars (9527-9533) and AOT training's BACKEND_PORT + 1000. */
    public static final int FIRST = 20000;
    /** Highest port handed out, just below Linux's default ephemeral range. */
    public static final int LAST = 32767;

    private static final int ATTEMPTS = 64;
    /** Random draws already handed out in this JVM: concurrent classes hold a port unbound for a while. */
    private static final Set<Integer> HANDED_OUT = ConcurrentHashMap.newKeySet();
    private static final IntSupplier RANDOM = () -> ThreadLocalRandom.current().nextInt(FIRST, LAST + 1);

    private TestPorts() {}

    /** A port in {@link #FIRST}..{@link #LAST} that nothing was listening on when probed, never handed out before in this JVM. */
    public static int freeNonEphemeral() throws IOException {
        try (var socket = bind(RANDOM, true)) {
            return socket.getLocalPort();
        }
    }

    /** {@link #freeNonEphemeral()} trying exactly the candidates {@code candidates} gives, so a test can name them. */
    public static int freeNonEphemeral(IntSupplier candidates) throws IOException {
        try (var socket = bind(candidates, false)) {
            return socket.getLocalPort();
        }
    }

    /** A socket already listening on a port {@link #freeNonEphemeral()} would return, for a test that must hold it. */
    public static ServerSocket bindNonEphemeral() throws IOException {
        return bind(RANDOM, true);
    }

    private static ServerSocket bind(IntSupplier candidates, boolean random) throws IOException {
        IOException last = null;
        for (int i = 0; i < ATTEMPTS; i++) {
            int port = candidates.getAsInt();
            if (port < FIRST || port > LAST) throw new IllegalArgumentException("port " + port + " is outside " + FIRST + "-" + LAST);
            if (random && !HANDED_OUT.add(port)) continue;
            var socket = new ServerSocket();
            try {
                socket.setReuseAddress(false);
                // The wildcard, as the scripts and the restart narrator bind: a holder on any interface counts.
                socket.bind(new InetSocketAddress(port));
                return socket;
            } catch (IOException e) {
                socket.close();
                last = e;
            }
        }
        throw new IOException("no free port in " + FIRST + "-" + LAST + " after " + ATTEMPTS + " tries", last);
    }
}
