import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import tools.BrowserScreenLog;
import tools.BrowserScreenProxy;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rung 3's browser is screened below the page (JCLAW-1315). The two paths the JCLAW-1305 review saw
 * reach around the sidecar's route gates — a dedicated Worker's WebSocket and TURN over TCP — are run
 * through the sidecar's own render path (launch, User-Agent probe, context, gates, navigation) against a
 * route-fulfilled page, once with no proxy and once per shape behind its own {@link BrowserScreenProxy},
 * the proxy {@code RenderedFetcher} hands every render.
 *
 * <p>Live-browser, so it runs only under {@code JCLAW_PLAYWRIGHT_TEST}.
 */
class StealthNetworkScreenTest extends UnitTest {

    private static final String REFUSED_LOOPBACK = "WARN Browser refused a request to blocked host 127.0.0.1";

    /**
     * Each run renders {@code http://hostile.test/} through {@code serve.Handler._render}, the page answered
     * by a route registered after the gates — so it answers first and the page itself touches no network.
     */
    private static final String DRIVER = """
            import sys, json
            sys.path.insert(0, sys.argv[1])
            import serve

            install_gates = serve._install_gates
            page = {}

            def gates_then_page(context, pins, scope):
                install_gates(context, pins, scope)
                context.route("http://hostile.test/", lambda route: route.fulfill(
                    status=200, content_type="text/html", body=page["html"]))

            serve._install_gates = gates_then_page
            out = {}
            try:
                for run in json.loads(sys.argv[2]):
                    page["html"] = run["html"]
                    result = serve.Handler._render(None, "http://hostile.test/", {}, 15000, 3000, 0, False,
                                                   "domcontentloaded", "en", serve._launch_proxy(run.get("proxy")))
                    out[run["name"]] = {"status": result["status"], "blocked": sorted(result["blocked"])}
            except Exception as exc:
                out = {"error": "%s: %s" % (type(exc).__name__, exc)}
            print("PROBE:" + json.dumps(out))
            """;

    private static boolean isPlaywrightTestEnabled() {
        var v = System.getenv("JCLAW_PLAYWRIGHT_TEST");
        return v != null && !v.isBlank() && !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    /** A loopback port that counts the TCP connections it accepts. */
    private static final class Listener implements AutoCloseable {

        private final ServerSocket socket;
        private final AtomicInteger connections = new AtomicInteger();

        Listener() throws IOException {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofPlatform().daemon().start(() -> {
                while (!socket.isClosed()) {
                    try (var _ = socket.accept()) {
                        connections.incrementAndGet();
                    } catch (IOException _) {
                        return;
                    }
                }
            });
        }

        int port() {
            return socket.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static String workerWebSocket(int port) {
        return "new Worker(URL.createObjectURL(new Blob([\"new WebSocket('ws://127.0.0.1:" + port + "/')\"],"
                + " {type: 'text/javascript'})));";
    }

    private static String turnOverTcp(int port) {
        return "const pc = new RTCPeerConnection({iceServers: [{urls: 'turn:127.0.0.1:" + port
                + "?transport=tcp', username: 'u', credential: 'p'}]});"
                + " pc.createDataChannel('probe'); pc.createOffer().then(o => pc.setLocalDescription(o));";
    }

    private static JsonObject run(String name, String script, BrowserScreenProxy screen) {
        var run = new JsonObject();
        run.addProperty("name", name);
        run.addProperty("html", "<!doctype html><title>hostile</title><script>" + script + "</script>");
        if (screen != null) {
            var proxy = new JsonObject();
            proxy.addProperty("url", "socks5://127.0.0.1:" + screen.port());
            run.add("proxy", proxy);
        }
        return run;
    }

    /** What each run reported, or a skip when uv or Patchright's Chromium is absent. */
    private static JsonObject render(JsonArray runs) throws Exception {
        var dir = new File(Play.applicationPath, "sidecar/stealth");
        // A file rather than a pipe, which the child would block on once it filled while nothing read it.
        var output = Files.createTempFile("stealth-screen", ".out");
        try {
            Process proc;
            try {
                proc = new ProcessBuilder("uv", "run", "python", "-c", DRIVER, dir.getAbsolutePath(), runs.toString())
                        .directory(dir).redirectOutput(output.toFile())
                        .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            } catch (IOException e) {
                proc = Assumptions.abort("uv is not on PATH: " + e.getMessage());
            }
            // The first run in a fresh checkout installs Patchright before it launches anything.
            if (!proc.waitFor(240, TimeUnit.SECONDS)) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly); // Python and its browsers outlive uv
                proc.destroyForcibly();
                fail("the stealth renders did not finish in 240 s");
            }
            var stdout = Files.readString(output, StandardCharsets.UTF_8);
            var answer = stdout.lines().filter(l -> l.startsWith("PROBE:")).reduce((a, b) -> b);
            assertTrue(answer.isPresent(), "the driver printed no answer; exit " + proc.exitValue() + ": " + stdout);
            var result = JsonParser.parseString(answer.get().substring("PROBE:".length())).getAsJsonObject();
            if (result.has("error")) {
                var error = result.get("error").getAsString();
                Assumptions.assumeFalse(error.contains("Executable doesn't exist"),
                        "Patchright's Chromium is not installed; run 'uv run patchright install chromium' in sidecar/stealth");
                fail("the stealth renders could not run: " + error);
            }
            return result;
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void aWorkersWebSocketAndTurnOverTcpReachLoopbackOnlyWithoutTheScreen() throws Exception {
        Assumptions.assumeTrue(isPlaywrightTestEnabled(), "JCLAW_PLAYWRIGHT_TEST is not set");
        var workerLog = new CopyOnWriteArrayList<String>();
        var turnLog = new CopyOnWriteArrayList<String>();
        try (var controlWs = new Listener(); var controlTurn = new Listener();
             var screenedWs = new Listener(); var screenedTurn = new Listener();
             // One screen per shape, so each refusal is attributable: the log names hosts, not ports.
             var workerScreen = new BrowserScreenProxy(new BrowserScreenLog((l, m) -> workerLog.add(l + " " + m)));
             var turnScreen = new BrowserScreenProxy(new BrowserScreenLog((l, m) -> turnLog.add(l + " " + m)))) {
            var runs = new JsonArray();
            runs.add(run("control", workerWebSocket(controlWs.port()) + turnOverTcp(controlTurn.port()), null));
            runs.add(run("worker", workerWebSocket(screenedWs.port()), workerScreen));
            runs.add(run("turn", turnOverTcp(screenedTurn.port()), turnScreen));

            var result = render(runs);

            for (var name : List.of("control", "worker", "turn")) {
                assertEquals(200, result.getAsJsonObject(name).get("status").getAsInt(),
                        name + " rendered the page, so its script ran: " + result);
            }
            Assumptions.assumeTrue(controlWs.connections() > 0 && controlTurn.connections() > 0,
                    "without the screen both shapes must reach loopback, or the zeros below prove nothing: "
                            + "worker " + controlWs.connections() + ", turn " + controlTurn.connections());
            assertEquals(0, screenedWs.connections(), "a Worker's WebSocket reached loopback through the screen");
            assertEquals(0, screenedTurn.connections(), "TURN over TCP reached loopback through the screen");
            assertTrue(workerLog.contains(REFUSED_LOOPBACK), "the screen saw the Worker's socket: " + workerLog);
            assertTrue(turnLog.contains(REFUSED_LOOPBACK), "the screen saw the TURN connection: " + turnLog);
        }
    }
}
