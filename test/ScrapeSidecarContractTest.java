import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeObservation;
import services.scrape.ScrapeReason;
import tools.scrape.RenderedFetcher;
import utils.WebExtraction;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Run {@code script} with the sidecar directory as {@code sys.argv[1]}, then {@code args},
     *  and parse the marked JSON object it prints. */
    private static JsonObject probe(String sidecar, String script, String... args) throws Exception {
        var dir = new File(Play.applicationPath, sidecar);
        assertTrue(new File(dir, "serve.py").isFile(), sidecar + " has moved or gone");

        var argv = new ArrayList<>(List.of("python3", "-c", script, dir.getAbsolutePath()));
        argv.addAll(List.of(args));
        var proc = new ProcessBuilder(argv).redirectErrorStream(true).start();
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
                                  "challenge": serve.MAX_CHALLENGE_MS,
                                  "bytes": serve.HARD_MAX_BYTES,
                                  "default_timeout": serve.DEFAULT_TIMEOUT_MS,
                                  "default_settle": serve.DEFAULT_SETTLE_MS,
                                  "default_challenge": serve.DEFAULT_CHALLENGE_MS,
                                  "slots": serve.DEFAULT_MAX_CONCURRENT}))
                """);
        assertEquals(RenderedFetcher.RENDER_SLOTS, out.get("slots").getAsInt(),
                "the JVM must queue renders for the permits the sidecar has, or they queue inside its timeout");
        // A standing challenge is polled in place of the settle window, never after it.
        long wait = Math.max(out.get("settle").getAsLong(), out.get("challenge").getAsLong());
        assertTrue(out.get("timeout").getAsLong() + wait < 120_000,
                "navigation and the longer wait together must finish inside the JVM's call timeout");
        assertTrue(out.get("default_timeout").getAsLong() <= out.get("timeout").getAsLong(),
                "the default must sit under the ceiling, or the clamp lowers it");
        assertTrue(out.get("default_settle").getAsLong() <= out.get("settle").getAsLong(),
                "same for the settle window");
        assertTrue(out.get("default_challenge").getAsLong() <= out.get("challenge").getAsLong(),
                "and for the challenge budget");

        var sent = RenderedFetcher.renderRequest("https://8.8.8.8/", "en", new JsonObject(), 4711);
        assertTrue(sent.get("timeoutMs").getAsLong() <= out.get("timeout").getAsLong(),
                "the navigation timeout the JVM sends must survive the sidecar's clamp: " + sent);
        assertTrue(sent.get("challengeMs").getAsLong() <= out.get("challenge").getAsLong(),
                "and so must its challenge budget: " + sent);
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
        assertEquals("{\"server\":\"http://127.0.0.1:3128\",\"bypass\":\"<-loopback>\",\"username\":\"u\","
                + "\"password\":\"p\"}", out.get("http").toString());
        assertEquals("{\"server\":\"socks5://127.0.0.1:1080\",\"bypass\":\"<-loopback>\"}", out.get("socks").toString());
        assertTrue(out.get("linkLocalRefused").getAsBoolean());
        assertTrue(out.get("schemeRefused").getAsBoolean());
        var launches = out.getAsJsonArray("launches");
        assertEquals(3, launches.size(), "one launch, then a failed launch and its fallback: " + launches);
        for (var launch : launches) {
            assertEquals(out.get("http"), launch, "every launch carries the proxy: " + launches);
        }
    }

    @Test
    void theRenderHandlerLaunchesThroughTheProxyTheRequestNames() throws Exception {
        // JCLAW-1315: the JVM names its network screen here, so a handler that dropped the field would
        // launch the browser unscreened. The real handler on a spare port, with the render stubbed.
        var out = probe("sidecar/stealth", """
                import sys, json, threading, urllib.request, urllib.error
                sys.path.insert(0, sys.argv[1])
                import serve
                from http.server import ThreadingHTTPServer

                launched = []

                def fake_render(self, url, pins, timeout_ms, settle_ms, challenge_ms, solve, wait_until,
                                language, proxy=None):
                    launched.append(proxy)
                    return {"html": "<html>ok</html>", "status": 200, "settledStatus": 200, "mitigated": None,
                            "url": url, "challenge": None, "blocked": set()}

                serve.sync_playwright = object()  # the handler refuses before reading a body without it
                serve.Handler._render = fake_render
                serve.Handler.token = None
                serve.Handler.state = serve.SidecarState("patchright-chromium", 0, 1)
                server = ThreadingHTTPServer(("127.0.0.1", 0), serve.Handler)
                threading.Thread(target=server.serve_forever, daemon=True).start()
                body = {"url": "http://93.184.215.14/", "proxy": {"url": "socks5://127.0.0.1:4711"}}
                req = urllib.request.Request("http://127.0.0.1:%d/render" % server.server_address[1],
                                             data=json.dumps(body).encode(), method="POST",
                                             headers={"Content-Type": "application/json"})
                try:
                    with urllib.request.urlopen(req) as r:
                        status = r.status
                except urllib.error.HTTPError as e:
                    status = e.code
                server.shutdown()
                print("PROBE:" + json.dumps({"status": status, "launched": launched}))
                """);
        assertEquals(200, out.get("status").getAsInt(), out.toString());
        assertEquals("[{\"server\":\"socks5://127.0.0.1:4711\",\"bypass\":\"<-loopback>\"}]",
                out.get("launched").toString());
    }

    @Test
    void theRenderLaunchStatesItsLanguageAndFingerprintAsFlags() throws Exception {
        // JCLAW-1305. The context locale reached the main thread only, so a Worker kept the host's languages.
        var out = probe("sidecar/stealth", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve
                print("PROBE:" + json.dumps({
                    "pinned": serve._launch_args({"example.com": "93.184.215.14"}, "de-DE"),
                    "bare": serve._launch_args({}, "en"),
                    "agent": serve._launch_args({}, "en", "UA/1"),
                    "context": serve._CONTEXT_OPTIONS,
                }))
                """);
        assertEquals(List.of("--lang=de-DE", "--accept-lang=de-DE,de",
                        "--blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,"
                                + "availablePointerTypes=4",
                        "--webrtc-ip-handling-policy=disable_non_proxied_udp",
                        "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
                        "--host-resolver-rules=MAP example.com 93.184.215.14"),
                keys(out, "pinned"),
                "each WebRTC spelling is ignored silently by one of the two builds a render may launch");
        assertEquals(List.of("--lang=en", "--accept-lang=en"), keys(out, "bare").subList(0, 2));
        assertFalse(keys(out, "bare").stream().anyMatch(a -> a.startsWith("--host-resolver-rules")),
                "no pins, no MAP clause");
        assertEquals("--user-agent=UA/1", keys(out, "agent").getLast(),
                "a flag, not a page-level override, so a cross-site iframe carries it too");
        assertFalse(keys(out, "pinned").stream().anyMatch(a -> a.startsWith("--disable-site-isolation-trials")),
                "Cloudflare fails a managed challenge whenever this flag is set (sidecar README)");
        assertEquals("{\"service_workers\":\"block\",\"viewport\":{\"width\":1920,\"height\":1080},"
                        + "\"screen\":{\"width\":1920,\"height\":1080}}",
                out.get("context").toString(), "no locale and no Accept-Language: the flags carry both");
    }

    @Test
    void theFingerprintCheckNamesEachSurfaceThatDisagrees() throws Exception {
        // The live self-check only runs where a browser is installed; this holds the verdict it reaches.
        var out = probe("sidecar/stealth", """
                import sys, json, copy
                sys.path.insert(0, sys.argv[1])
                import serve
                ua = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36")
                surfaces = {"userAgent": ua, "brands": ["Chromium", "Not=A?Brand"],
                            "language": "de-DE", "languages": ["de-DE", "de", "en-US", "en"],
                            "intl": "de-DE"}
                sent = {"user-agent": ua, "accept-language": "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7",
                        "sec-ch-ua": '"Chromium";v="153", "Not=A?Brand";v="99"'}
                consistent = {
                    "main": dict(surfaces, screen=[1920, 1080], viewport=[1920, 1080],
                                 pointerFine=True, hover=True, navigatorOverrides=[]),
                    "worker": dict(surfaces),
                    "frame": dict(surfaces),
                    "frameWorker": dict(surfaces),
                    "headers": {"/": sent, "/echo/main": sent, "/frame": sent,
                                "/worker.js": dict(sent, **{"sec-ch-ua": None}),
                                "/echo/worker": dict(sent, **{"sec-ch-ua": None}),
                                "/frame-worker.js": dict(sent, **{"sec-ch-ua": None}),
                                "/echo/frame-worker": dict(sent, **{"sec-ch-ua": None})},
                    "webrtcUdp": False,
                }

                def broken(path, value, platform="linux"):
                    observed = copy.deepcopy(consistent)
                    target = observed
                    for key in path[:-1]:
                        target = target[key]
                    target[path[-1]] = value
                    return serve.fingerprint_problems(observed, "de-DE", platform)

                print("PROBE:" + json.dumps({
                    "consistent": serve.fingerprint_problems(consistent, "de-DE"),
                    "caught": {
                        "a Worker on the host's languages": broken(["worker", "languages"], ["en-GB", "en"]),
                        "Intl on the host's locale": broken(["main", "intl"], "en-GB"),
                        "a header from a context locale": broken(
                            ["headers", "/echo/worker", "accept-language"], "de-DE, *;q=0.5"),
                        "a HeadlessChrome Worker": broken(
                            ["worker", "userAgent"], ua.replace("Chrome/", "HeadlessChrome/")),
                        "a HeadlessChrome brand on the wire": broken(
                            ["headers", "/", "sec-ch-ua"], '"HeadlessChrome";v="153"'),
                        "a HeadlessChrome brand": broken(["main", "brands"], ["HeadlessChrome", "Not=A?Brand"]),
                        "on macOS, a page on the host's locale": broken(["main", "intl"], "en-GB", "darwin"),
                        "on macOS, a Worker on the host's locale": broken(["worker", "intl"], "en-GB", "darwin"),
                        "the default viewport": broken(["main", "viewport"], [1280, 720]),
                        "no fine pointer": broken(["main", "pointerFine"], False),
                        "a script override": broken(["main", "navigatorOverrides"], ["webdriver"]),
                        "WebRTC UDP": broken(["webrtcUdp"], True),
                        "a HeadlessChrome iframe": broken(
                            ["frame", "userAgent"], ua.replace("Chrome/", "HeadlessChrome/")),
                        "an iframe's Worker on the host's locale": broken(["frameWorker", "intl"], "en-GB"),
                        "an iframe that never answered": broken(["frame"], None),
                        "a Worker that never answered": broken(["worker"], None),
                        "a request that never arrived": broken(["headers", "/echo/worker"], None),
                    },
                    "macIframeIntl": broken(["frame", "intl"], "en-GB", "darwin")
                                     + broken(["frameWorker", "intl"], "en-GB", "darwin"),
                }))
                """);
        assertEquals("[]", out.get("consistent").toString(),
                "a consistent fingerprint must pass, or every case below is caught for the wrong reason");
        for (var caught : out.getAsJsonObject("caught").entrySet()) {
            assertFalse(caught.getValue().getAsJsonArray().isEmpty(), "the check misses " + caught.getKey());
        }
        assertEquals("[]", out.get("macIframeIntl").toString(),
                "macOS gives an isolated iframe the OS's Intl, and nothing reaches it without a worse split");
    }

    // ==================== Cloudflare challenges (JCLAW-1306) ====================

    /** A Cloudflare gate page as served: the type is named in the inline options, the visible
     *  text is in whatever language the visitor's locale picked. */
    private static String gate(String type, String title) {
        return ("<!DOCTYPE html><html><head><title>%s</title></head><body><script>(function(){"
                + "window._cf_chl_opt={cvId: '3',cZone: \"x.test\",cType: '%s',cRay: '8c4f0d2e9a1b2c3d'};"
                + "var cpo=document.createElement('script');"
                + "cpo.src='/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1';"
                + "document.head.appendChild(cpo);}());</script></body></html>").formatted(title, type);
    }

    private static final String TURNSTILE_SCRIPT =
            "<script src=\"https://challenges.cloudflare.com/turnstile/v0/api.js\"></script>";

    @Test
    void theSidecarDetectsAChallengeExactlyWhenTheClassifierDoes() throws Exception {
        // Two implementations of one detection, so they are held against each other on the same bodies.
        var fixtures = new LinkedHashMap<String, String[]>();
        fixtures.put("managed", new String[] {gate("managed", "Just a moment..."), null});
        fixtures.put("managed, French", new String[] {gate("managed", "Un instant…"), null});
        fixtures.put("interactive, Japanese", new String[] {gate("interactive", "しばらく"), null});
        fixtures.put("non-interactive", new String[] {gate("non-interactive", "Einen Moment…"), null});
        fixtures.put("cleared", new String[] {"<html><body><article>" + "Widgets. ".repeat(40)
                + "</article></body></html>", null});
        fixtures.put("detection script only", new String[] {"<html><body><script>var s=document.createElement"
                + "('script');s.src='/cdn-cgi/challenge-platform/scripts/jsd/main.js';</script></body></html>", null});
        fixtures.put("cType outside the options", new String[] {
                "<script>var config = {cType: 'managed'}; window._cf_chl_opt = {};</script>", null});
        fixtures.put("header only", new String[] {"<html></html>", "challenge"});
        fixtures.put("Turnstile script and header", new String[] {TURNSTILE_SCRIPT, "challenge"});
        fixtures.put("widget in a content page", new String[] {"<html><body>" + TURNSTILE_SCRIPT
                + "<div class=\"cf-turnstile\"></div><article>real</article></body></html>", null});
        fixtures.put("another mitigation", new String[] {"<html></html>", "block"});

        var bodies = new JsonArray();
        fixtures.values().forEach(f -> {
            var entry = new JsonArray();
            entry.add(f[0]);
            entry.add(f[1]);
            bodies.add(entry);
        });
        var out = probe("sidecar/stealth", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve
                fixtures = json.loads(sys.argv[2])
                print("PROBE:" + json.dumps({"types": [serve.challenge_type(b, m) for b, m in fixtures]}))
                """, bodies.toString());

        var types = out.getAsJsonArray("types");
        var names = new ArrayList<>(fixtures.keySet());
        var expected = List.of("managed", "managed", "interactive", "non-interactive", "", "", "",
                "unknown", "unknown", "", "");
        for (int i = 0; i < names.size(); i++) {
            var fixture = fixtures.get(names.get(i));
            var sidecar = types.get(i).isJsonNull() ? "" : types.get(i).getAsString();
            assertEquals(expected.get(i), sidecar, names.get(i));

            var headers = fixture[1] == null ? Map.<String, String>of() : Map.of("cf-mitigated", fixture[1]);
            var jvm = BlockClassifier.classify(ScrapeObservation.failed("https://x.test/",
                    new WebExtraction.HttpStatusException(403, "https://x.test/",
                            fixture[0].getBytes(StandardCharsets.UTF_8), "text/html", headers)));
            assertEquals(!sidecar.isEmpty(), jvm == ScrapeReason.JS_CHALLENGE || jvm == ScrapeReason.TURNSTILE,
                    "%s: the sidecar says %s where the classifier says %s".formatted(names.get(i), sidecar, jvm));
            if (sidecar.equals("interactive")) assertEquals(ScrapeReason.TURNSTILE, jvm, names.get(i));
        }
        var french = fixtures.get("managed, French")[0].toLowerCase(Locale.ROOT);
        for (var english : new String[] {"just a moment", "enable javascript", "verifying you are human"}) {
            assertFalse(french.contains(english), "the localized fixture must carry no English: " + english);
        }
    }

    @Test
    void theSolverClicksOnlyAGateCheckboxAndStopsAtTheCap() throws Exception {
        // Scrapling's tests/fetchers/test_cloudflare_solver.py shape: a page stub whose waits advance a fake clock.
        var out = probe("sidecar/stealth", """
                import sys, json, random
                sys.path.insert(0, sys.argv[1])
                import serve

                BOX = {"x": 100, "y": 200, "width": 300, "height": 65}
                FRAME = "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b/turnstile/if/ov2/"

                class Clock:
                    now = 0.0
                    def __call__(self):
                        return self.now

                class Element:
                    def __init__(self, box):
                        self.box = box
                    def is_visible(self):
                        return True
                    def bounding_box(self):
                        return self.box

                class Frame:
                    def __init__(self, url, box):
                        self.url, self.box = url, box
                    def frame_element(self):
                        return Element(self.box)

                class Mouse:
                    def __init__(self, page):
                        self.page, self.clicks = page, []
                    def click(self, x, y, delay=0):
                        self.clicks.append([x, y])
                        if self.page.clears_on_click:
                            self.page.html = PARTIAL if self.page.parse_ms else ARTICLE

                ARTICLE = "<html><body>the article</body></html>"
                # What the page behind a cleared challenge holds while its HTML is still arriving: no marker, no text.
                PARTIAL = "<html><head><title>the article</title></head><body>"

                class Page:
                    def __init__(self, html, clock, frames=None, clears_on_click=False, clears_after_waits=None,
                                 parse_ms=0):
                        self.html, self.clock = html, clock
                        self.frames = [Frame(FRAME, BOX)] if frames is None else frames
                        self.clears_on_click, self.clears_after_waits = clears_on_click, clears_after_waits
                        self.parse_ms, self.mouse, self.waits, self.events = parse_ms, Mouse(self), [], []
                    def content(self):
                        return self.html
                    def wait_for_timeout(self, ms):
                        self.waits.append(ms)
                        self.events.append("wait:%d" % ms)
                        self.clock.now += ms / 1000.0
                        if self.clears_after_waits == len(self.waits):
                            self.html = ARTICLE
                    def wait_for_load_state(self, state, timeout):
                        self.events.append("%s:%d" % (state, timeout))
                        if self.html != PARTIAL:
                            return
                        if self.parse_ms > timeout:
                            self.clock.now += timeout / 1000.0
                            raise TimeoutError("still loading")
                        self.clock.now += self.parse_ms / 1000.0
                        self.html = ARTICLE

                def gate(kind):
                    return "<title>Un instant</title><script>window._cf_chl_opt={cType: '%s'};</script>" % kind

                def run(html, solve, **kw):
                    clock = Clock()
                    page = Page(html, clock, **kw)
                    report = serve._settle(page, {"mitigated": None}, 4000, 45000, solve, clock, random.Random(7))
                    return {"report": report, "clicks": page.mouse.clicks, "waited": sum(page.waits),
                            "events": page.events, "captured": page.content(), "elapsed": clock.now * 1000}

                inside = all(
                    box["x"] < p[0] < box["x"] + box["width"] and box["y"] < p[1] < box["y"] + box["height"]
                    for box in [BOX, {"x": 0, "y": 0, "width": 10, "height": 12}, {"x": -5, "y": 3, "width": 2, "height": 2}]
                    for p in [serve._click_point(box, random.Random(seed)) for seed in range(200)])

                print("PROBE:" + json.dumps({
                    "cap": run(gate("interactive"), True),
                    "off": run(gate("interactive"), False),
                    "clickClears": run(gate("managed"), True, clears_on_click=True),
                    "clearsIntoLoadingPage": run(gate("interactive"), True, clears_on_click=True, parse_ms=6000),
                    "parseTimesOut": run(gate("interactive"), True, clears_on_click=True, parse_ms=120000),
                    "nonInteractive": run(gate("non-interactive"), True, clears_after_waits=3),
                    "noChallenge": run("<html><body>the article</body></html>", True),
                    "embeddedWidget": run('<script src="https://challenges.cloudflare.com/turnstile/v0/api.js">'
                                          '</script><div class="cf-turnstile"></div><article>real</article>', True),
                    "foreignFrame": run(gate("interactive"), True, frames=[
                        Frame("https://ads.example/cdn-cgi/challenge-platform/x", BOX),
                        Frame("https://challenges.cloudflare.com/turnstile/v0/", BOX)]),
                    "tinyFrame": run(gate("managed"), True, frames=[Frame(FRAME, {"x": 1, "y": 1, "width": 1, "height": 40})]),
                    "inside": inside,
                    "tooSmall": serve._click_point({"x": 0, "y": 0, "width": 1, "height": 1}, random.Random(1)),
                    "maxClicks": serve._MAX_CLICKS,
                }))
                """);

        var cap = out.getAsJsonObject("cap");
        assertEquals("interactive; unsolved; clicks=3", cap.get("report").getAsString());
        assertEquals(out.get("maxClicks").getAsInt(), cap.getAsJsonArray("clicks").size(),
                "a challenge that never clears is clicked up to the cap and no further");
        assertEquals(45_000, cap.get("waited").getAsDouble(), 1.0, "and polled until the budget ran out");
        for (var click : cap.getAsJsonArray("clicks")) {
            var xy = click.getAsJsonArray();
            assertTrue(xy.get(0).getAsDouble() > 100 && xy.get(0).getAsDouble() < 400
                            && xy.get(1).getAsDouble() > 200 && xy.get(1).getAsDouble() < 265,
                    "every click lands inside the challenge-platform frame: " + xy);
        }

        var off = out.getAsJsonObject("off");
        assertEquals("interactive; unsolved", off.get("report").getAsString());
        assertTrue(off.getAsJsonArray("clicks").isEmpty(), "with the switch off a challenge is waited on, never clicked");
        assertEquals(45_000, off.get("waited").getAsDouble(), 1.0);

        var cleared = out.getAsJsonObject("clickClears");
        assertEquals("managed; cleared; clicks=1", cleared.get("report").getAsString());
        assertTrue(cleared.get("waited").getAsDouble() >= 4_000,
                "the page behind a cleared challenge still gets the settle window to render");

        var loading = out.getAsJsonObject("clearsIntoLoadingPage");
        assertEquals("interactive; cleared; clicks=1", loading.get("report").getAsString());
        assertEquals("<html><body>the article</body></html>", loading.get("captured").getAsString(),
                "a clear that lands on a page still downloading waits for it to be parsed, then captures all of it");
        var events = loading.getAsJsonArray("events").toString();
        assertTrue(events.endsWith("\"domcontentloaded:44500\",\"wait:4000\"]"),
                "parsed within what was left of the budget, then given the full settle window: " + events);
        assertTrue(loading.get("elapsed").getAsDouble() <= 45_000, "inside the challenge budget: " + loading);

        var timedOut = out.getAsJsonObject("parseTimesOut");
        assertEquals("interactive; cleared; clicks=1", timedOut.get("report").getAsString(),
                "a page that never finishes parsing is no new failure: it is captured as it is");
        assertEquals("<html><head><title>the article</title></head><body>", timedOut.get("captured").getAsString());
        assertTrue(timedOut.get("elapsed").getAsDouble() <= 45_000 + 1, "and the wait stays inside the budget: " + timedOut);

        var nonInteractive = out.getAsJsonObject("nonInteractive");
        assertEquals("non-interactive; cleared", nonInteractive.get("report").getAsString());
        assertTrue(nonInteractive.getAsJsonArray("clicks").isEmpty(), "a non-interactive challenge is never clicked");

        for (var unchanged : List.of("noChallenge", "embeddedWidget")) {
            var run = out.getAsJsonObject(unchanged);
            assertTrue(run.get("report").isJsonNull(), unchanged + " stood no challenge: " + run);
            assertTrue(run.getAsJsonArray("clicks").isEmpty(), unchanged + " must never be clicked");
            assertEquals(4_000, run.get("waited").getAsDouble(), 0.0, unchanged + " keeps the plain settle window");
        }
        for (var unclickable : List.of("foreignFrame", "tinyFrame")) {
            assertTrue(out.getAsJsonObject(unclickable).getAsJsonArray("clicks").isEmpty(),
                    unclickable + ": only a challenge-platform frame with room for its checkbox is clicked");
        }
        assertTrue(out.get("inside").getAsBoolean(), "a click point outside its frame's box");
        assertTrue(out.get("tooSmall").isJsonNull());
    }
}
