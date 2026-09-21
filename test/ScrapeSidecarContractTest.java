import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Properties of the scrape sidecars that only their own interpreter can answer
 * (JCLAW-1087, JCLAW-1088).
 *
 * <p>Same shape as {@link StealthBrowserTest}'s guard-parity probe: run the module under
 * {@code python3} and assert on what it prints. Everything asserted here is reachable
 * with neither {@code curl_cffi} nor Patchright installed — both are optional imports —
 * so this measures the sidecar's own logic rather than the host's Python environment.
 */
class ScrapeSidecarContractTest extends UnitTest {

    /** Marks the probe's answer inside merged stdout+stderr: an optional import failing
     *  writes to stderr, and a sidecar is expected to survive that rather than be silent. */
    private static final String MARKER = "PROBE:";

    /** Run {@code script} with the sidecar directory on {@code sys.path} and parse the
     *  marked JSON object it prints. */
    private static JsonObject probe(String sidecar, String script) throws Exception {
        var dir = new File(Play.applicationPath, sidecar);
        assertTrue(new File(dir, "serve.py").isFile(), sidecar + " has moved or gone");

        var proc = new ProcessBuilder(List.of("python3", "-c", script, dir.getAbsolutePath()))
                .redirectErrorStream(true).start();
        assertTrue(proc.waitFor(90, TimeUnit.SECONDS), sidecar + " probe timed out");
        var stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, proc.exitValue(), sidecar + " probe failed: " + stdout);

        var answer = stdout.lines().filter(l -> l.startsWith(MARKER)).reduce((a, b) -> b);
        assertTrue(answer.isPresent(), sidecar + " probe printed no answer: " + stdout);
        return JsonParser.parseString(answer.get().substring(MARKER.length())).getAsJsonObject();
    }

    /** The sorted key list the probe reported under {@code field}. */
    private static List<String> keys(JsonObject out, String field) {
        var keys = new ArrayList<String>();
        out.getAsJsonArray(field).forEach(e -> keys.add(e.getAsString()));
        return keys;
    }

    @Test
    void theFetchCapabilityKeepsItsShapeOnABrokenInstall() throws Exception {
        // /capability exists to report a broken install, and it used to answer a
        // different set of keys in exactly that case — so a consumer written against
        // the documented shape broke on the one response it was written for.
        var out = probe("sidecar/fetch", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve
                # capability() branches on curl_requests being None and reads nothing off it,
                # so a sentinel reaches the success branch on a host with no curl_cffi — where
                # asking for the real one answers the broken branch and compares it to itself.
                serve.curl_requests = object()
                live = serve.capability("chrome")
                serve.curl_requests = None
                broken = serve.capability("chrome")
                print("PROBE:" + json.dumps({"live": sorted(live), "broken": sorted(broken),
                                  "liveRunnable": live["runnable"],
                                  "brokenRunnable": broken["runnable"]}))
                """);
        var documented = List.of("kind", "profile", "profileCount", "profileKnown",
                "reason", "runnable");
        assertEquals(documented, keys(out, "broken"));
        assertEquals(documented, keys(out, "live"),
                "a usable install and a broken one must answer the same keys");
        assertTrue(out.get("liveRunnable").getAsBoolean(),
                "the success branch was never reached, so the shapes above are one branch twice");
        assertFalse(out.get("brokenRunnable").getAsBoolean(),
                "a sidecar with no curl_cffi must report itself unrunnable");
    }

    @Test
    void theFetchSidecarWaitsForAnInFlightFetchBeforeExiting() throws Exception {
        // A fetch killed mid-flight closes its connection with no status line at all,
        // which the JVM can only read as a transport failure — indistinguishable, in the
        // report, from an origin refusing us.
        var out = probe("sidecar/fetch", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve
                s = serve.SidecarState("chrome", 0)
                idle = s.await_drain(0.1)
                s.begin_fetch()
                busy = s.await_drain(0.2)
                s.end_fetch()
                print("PROBE:" + json.dumps({"idle": idle, "busy": busy, "drained": s.await_drain(1.0),
                                  "budget": serve.DRAIN_TIMEOUT_S}))
                """);
        assertTrue(out.get("idle").getAsBoolean(), "an idle sidecar exits at once");
        assertFalse(out.get("busy").getAsBoolean(), "a running fetch holds the exit open");
        assertTrue(out.get("drained").getAsBoolean(), "and releases it when it finishes");
        assertTrue(out.get("budget").getAsDouble() > 0,
                "the wait is bounded — a wedged fetch must not make the process unkillable");
    }

    @Test
    void theRenderRouteGateFailsClosedOnAResolverThatDoesNotAnswer() throws Exception {
        // getaddrinfo takes no timeout and the gate runs on the thread holding a render
        // permit, so one black-holed resolver would stall the render past the JVM's 120s
        // call timeout. Timing out must read as "not public", never as "allow".
        var out = probe("sidecar/stealth", """
                import sys, json, time
                sys.path.insert(0, sys.argv[1])
                import serve
                real = serve.is_public_host
                def never_answers(host):
                    time.sleep(30)
                    return True
                serve.is_public_host = never_answers
                started = time.monotonic()
                allowed = serve._host_allowed("black.hole.invalid")
                elapsed = time.monotonic() - started
                # Restored, so the loopback answer below is the range check's and not a
                # second timeout wearing the same result.
                serve.is_public_host = real
                print("PROBE:" + json.dumps({"allowed": allowed, "elapsed": elapsed,
                                  "budget": serve._RESOLVE_TIMEOUT_S,
                                  "loopback": serve._host_allowed("127.0.0.1")}))
                """);
        assertFalse(out.get("allowed").getAsBoolean(),
                "a lookup that never answers must not open the page a route");
        assertTrue(out.get("elapsed").getAsDouble()
                        < out.get("budget").getAsDouble() + 5.0,
                "and must give up on its own budget, not the resolver's: "
                        + out.get("elapsed"));
        assertFalse(out.get("loopback").getAsBoolean(),
                "the gate still refuses loopback — the deadline is not the only check");
    }

    @Test
    void theRenderRouteGateResolvesAHostOncePerPage() throws Exception {
        // A page pulling forty subresources from one CDN would otherwise pay the lookup
        // forty times, on the permit-holding thread. The decision expires, because an
        // allow held for the process lifetime is a standing rebinding window.
        var out = probe("sidecar/stealth", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve
                calls = []
                serve.is_public_host = lambda host: (calls.append(host), True)[1]
                first = serve._host_allowed("cdn.example.invalid")
                again = serve._host_allowed("cdn.example.invalid")
                print("PROBE:" + json.dumps({"first": first, "again": again, "lookups": len(calls),
                                  "ttl": serve._HOST_TTL_S}))
                """);
        assertTrue(out.get("first").getAsBoolean());
        assertTrue(out.get("again").getAsBoolean());
        assertEquals(1, out.get("lookups").getAsInt(), "the second request reused the decision");
        assertTrue(out.get("ttl").getAsDouble() > 0 && out.get("ttl").getAsDouble() <= 300,
                "a cached allow must expire well inside a crawl: " + out.get("ttl"));
    }

    @Test
    void aRenderRequestCannotAskForMoreThanTheSidecarWillSpend() throws Exception {
        // Each render holds one of four permits for its whole duration and the JVM
        // abandons the call at 120s, so a caller-supplied timeout, settle window or body
        // size is clamped here rather than trusted.
        var out = probe("sidecar/stealth", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve
                print("PROBE:" + json.dumps({"timeout": serve.MAX_TIMEOUT_MS, "settle": serve.MAX_SETTLE_MS,
                                  "bytes": serve.HARD_MAX_BYTES,
                                  "default_timeout": serve.DEFAULT_TIMEOUT_MS,
                                  "default_settle": serve.DEFAULT_SETTLE_MS}))
                """);
        assertTrue(out.get("timeout").getAsLong() + out.get("settle").getAsLong() < 120_000,
                "navigation and settle together must finish inside the JVM's call timeout");
        assertTrue(out.get("default_timeout").getAsLong() <= out.get("timeout").getAsLong(),
                "the default must sit under the ceiling, or the clamp lowers it");
        assertTrue(out.get("default_settle").getAsLong() <= out.get("settle").getAsLong(),
                "same for the settle window");
        assertEquals(25L * 1024 * 1024, out.get("bytes").getAsLong(),
                "the render body ceiling is the one the README publishes");
    }

    @Test
    void theFetchSidecarHandsTheOperatorsProxyToCurlAndRefusesALinkLocalOne() throws Exception {
        // JCLAW-1271. The real handler on a spare port, with curl stubbed: what reaches curl is
        // what the stub records, and a refused proxy never reaches it at all.
        var out = probe("sidecar/fetch", """
                import sys, json, threading, urllib.request, urllib.error
                sys.path.insert(0, sys.argv[1])
                import serve
                from http.server import ThreadingHTTPServer

                calls = []

                class FakeResponse:
                    status_code = 200
                    headers = {"Content-Type": "text/html"}
                    url = "http://93.184.215.14/"
                    def iter_content(self):
                        yield b"<html>ok</html>"
                    def close(self):
                        pass

                class FakeSession:
                    def __init__(self, curl=None):
                        pass
                    def get(self, url, **kw):
                        auth = kw.get("proxy_auth")
                        calls.append({"proxy": kw.get("proxy"), "proxy_auth": list(auth) if auth else None})
                        return FakeResponse()

                class FakeRequests:
                    Session = FakeSession

                class FakeCurl:
                    def setopt(self, *args):
                        pass

                serve.curl_requests = FakeRequests
                serve.Curl = FakeCurl
                serve.Handler.token = None
                serve.Handler.state = serve.SidecarState("chrome", 0)
                server = ThreadingHTTPServer(("127.0.0.1", 0), serve.Handler)
                threading.Thread(target=server.serve_forever, daemon=True).start()

                def post(body):
                    req = urllib.request.Request("http://127.0.0.1:%d/fetch" % server.server_address[1],
                                                 data=json.dumps(body).encode(), method="POST",
                                                 headers={"Content-Type": "application/json"})
                    try:
                        with urllib.request.urlopen(req) as r:
                            return r.status
                    except urllib.error.HTTPError as e:
                        return e.code

                target = "http://93.184.215.14/"
                out = {
                    "direct": post({"url": target}),
                    "http": post({"url": target, "proxy": {"url": "http://127.0.0.1:3128", "username": "u", "password": "p"}}),
                    "socks": post({"url": target, "proxy": {"url": "socks5://127.0.0.1:1080"}}),
                    "linkLocal": post({"url": target, "proxy": {"url": "http://169.254.169.254:80"}}),
                    "badScheme": post({"url": target, "proxy": {"url": "ftp://127.0.0.1:21"}}),
                }
                out["calls"] = calls
                server.shutdown()
                print("PROBE:" + json.dumps(out))
                """);
        assertEquals(200, out.get("direct").getAsInt());
        assertEquals(200, out.get("http").getAsInt());
        assertEquals(200, out.get("socks").getAsInt());
        assertEquals(400, out.get("linkLocal").getAsInt(), "a link-local proxy is never a proxy");
        assertEquals(400, out.get("badScheme").getAsInt());
        var calls = out.getAsJsonArray("calls");
        assertEquals(3, calls.size(), "only the accepted requests reach curl: " + calls);
        assertTrue(calls.get(0).getAsJsonObject().get("proxy").isJsonNull(), "no proxy, no proxy: " + calls);
        assertEquals("http://127.0.0.1:3128", calls.get(1).getAsJsonObject().get("proxy").getAsString());
        assertEquals("[\"u\",\"p\"]", calls.get(1).getAsJsonObject().get("proxy_auth").toString());
        assertEquals("socks5://127.0.0.1:1080", calls.get(2).getAsJsonObject().get("proxy").getAsString());
    }

    @Test
    void theRenderSidecarLaunchesEveryBrowserThroughTheOperatorsProxy() throws Exception {
        // Including the headless-shell fallback: a relaunch that dropped the proxy would render
        // that one page direct, from this host's address, without anyone noticing.
        var out = probe("sidecar/stealth", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve

                def refused(proxy):
                    try:
                        serve._launch_proxy(proxy)
                        return False
                    except ValueError:
                        return True

                launches = []

                class FakeChromium:
                    def __init__(self, fail_first):
                        self.fail_first = fail_first
                    def launch(self, **kw):
                        launches.append(kw.get("proxy"))
                        if self.fail_first:
                            self.fail_first = False
                            raise RuntimeError("full build missing")
                        return "browser"

                class FakePlaywright:
                    def __init__(self, fail_first):
                        self.chromium = FakeChromium(fail_first)

                settings = serve._launch_proxy({"url": "http://127.0.0.1:3128", "username": "u", "password": "p"})
                serve._launch(FakePlaywright(False), [], settings)
                serve._launch(FakePlaywright(True), [], settings)
                print("PROBE:" + json.dumps({
                    "none": serve._launch_proxy(None),
                    "http": settings,
                    "socks": serve._launch_proxy({"url": "socks5://127.0.0.1:1080"}),
                    "linkLocalRefused": refused({"url": "http://169.254.169.254:80"}),
                    "schemeRefused": refused({"url": "https://127.0.0.1:443"}),
                    "launches": launches,
                }))
                """);
        assertTrue(out.get("none").isJsonNull());
        assertEquals("{\"server\":\"http://127.0.0.1:3128\",\"username\":\"u\",\"password\":\"p\"}",
                out.get("http").toString());
        assertEquals("{\"server\":\"socks5://127.0.0.1:1080\"}", out.get("socks").toString());
        assertTrue(out.get("linkLocalRefused").getAsBoolean());
        assertTrue(out.get("schemeRefused").getAsBoolean());
        var launches = out.getAsJsonArray("launches");
        assertEquals(3, launches.size(), "one launch, then a failed launch and its fallback: " + launches);
        for (var launch : launches) {
            assertEquals(out.get("http"), launch, "every launch carries the proxy: " + launches);
        }
    }
}
