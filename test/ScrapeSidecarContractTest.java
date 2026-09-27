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
        var documented = List.of("chromeMajor", "kind", "profile", "profileCount", "profileKnown",
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
        assertEquals(out.get("slots").getAsInt(), RenderedFetcher.RENDER_SLOTS,
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

        var sent = RenderedFetcher.renderRequest("https://8.8.8.8/", "en", new JsonObject());
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
                    "context": serve._CONTEXT_OPTIONS,
                }))
                """);
        assertEquals(List.of("--lang=de-DE", "--accept-lang=de-DE,de",
                        "--blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,"
                                + "availablePointerTypes=4",
                        "--disable-site-isolation-trials",
                        "--webrtc-ip-handling-policy=disable_non_proxied_udp",
                        "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
                        "--host-resolver-rules=MAP example.com 93.184.215.14"),
                keys(out, "pinned"),
                "each WebRTC spelling is ignored silently by one of the two builds a render may launch");
        assertEquals(List.of("--lang=en", "--accept-lang=en"), keys(out, "bare").subList(0, 2));
        assertFalse(keys(out, "bare").stream().anyMatch(a -> a.startsWith("--host-resolver-rules")),
                "no pins, no MAP clause");
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
                surfaces = {"userAgent": ua, "brands": ["Not=A?Brand", "Google Chrome", "Chromium"],
                            "language": "de-DE", "languages": ["de-DE", "de", "en-US", "en"],
                            "intl": "de-DE"}
                sent = {"user-agent": ua, "accept-language": "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7",
                        "sec-ch-ua": '"Not=A?Brand";v="99", "Google Chrome";v="153", "Chromium";v="153"'}
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

                def broken(path, value):
                    observed = copy.deepcopy(consistent)
                    target = observed
                    for key in path[:-1]:
                        target = target[key]
                    target[path[-1]] = value
                    return serve.fingerprint_problems(observed, "de-DE")

                print("PROBE:" + json.dumps({
                    "consistent": serve.fingerprint_problems(consistent, "de-DE"),
                    "caught": {
                        "a Worker on the host's languages": broken(["worker", "languages"], ["en-GB", "en"]),
                        "Intl on the host's locale": broken(["main", "intl"], "en-GB"),
                        "a header from a context locale": broken(
                            ["headers", "/echo/worker", "accept-language"], "de-DE, *;q=0.5"),
                        "a HeadlessChrome Worker": broken(
                            ["worker", "userAgent"], ua.replace("Chrome/", "HeadlessChrome/")),
                        "the Chromium brand on the wire": broken(["headers", "/", "sec-ch-ua"], '"Chromium";v="153"'),
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
                }))
                """);
        assertEquals("[]", out.get("consistent").toString(),
                "a consistent fingerprint must pass, or every case below is caught for the wrong reason");
        for (var caught : out.getAsJsonObject("caught").entrySet()) {
            assertFalse(caught.getValue().getAsJsonArray().isEmpty(), "the check misses " + caught.getKey());
        }
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
                            self.page.html = "<html><body>the article</body></html>"

                class Page:
                    def __init__(self, html, clock, frames=None, clears_on_click=False, clears_after_waits=None):
                        self.html, self.clock = html, clock
                        self.frames = [Frame(FRAME, BOX)] if frames is None else frames
                        self.clears_on_click, self.clears_after_waits = clears_on_click, clears_after_waits
                        self.mouse, self.waits = Mouse(self), []
                    def content(self):
                        return self.html
                    def wait_for_timeout(self, ms):
                        self.waits.append(ms)
                        self.clock.now += ms / 1000.0
                        if self.clears_after_waits == len(self.waits):
                            self.html = "<html><body>the article</body></html>"

                def gate(kind):
                    return "<title>Un instant</title><script>window._cf_chl_opt={cType: '%s'};</script>" % kind

                def run(html, solve, **kw):
                    clock = Clock()
                    page = Page(html, clock, **kw)
                    report = serve._settle(page, {"mitigated": None}, 4000, 45000, solve, clock, random.Random(7))
                    return {"report": report, "clicks": page.mouse.clicks, "waited": sum(page.waits)}

                inside = all(
                    box["x"] < p[0] < box["x"] + box["width"] and box["y"] < p[1] < box["y"] + box["height"]
                    for box in [BOX, {"x": 0, "y": 0, "width": 10, "height": 12}, {"x": -5, "y": 3, "width": 2, "height": 2}]
                    for p in [serve._click_point(box, random.Random(seed)) for seed in range(200)])

                print("PROBE:" + json.dumps({
                    "cap": run(gate("interactive"), True),
                    "off": run(gate("interactive"), False),
                    "clickClears": run(gate("managed"), True, clears_on_click=True),
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

    // ==================== Browser sessions (JCLAW-1307) ====================

    @Test
    void theSessionRegistryCapsIsolatesAndReapsItsSessions() throws Exception {
        // A fake session with an injected clock: what is asserted is the bookkeeping, which no browser changes.
        var out = probe("sidecar/stealth", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve

                class Clock:
                    now = 1000.0
                    def __call__(self):
                        return self.now

                built = []

                class FakeSession:
                    def __init__(self, host, pins, language, proxy):
                        if host == "fails.example":
                            raise RuntimeError("launch failed")
                        self.host, self.closed, self.joined = host, False, False
                        built.append(self)
                    def close(self):
                        self.closed = True
                    def join(self, timeout):
                        self.joined = True

                clock = Clock()
                reg = serve.SessionRegistry(2, 300, FakeSession, clock)
                a = reg.open("a.example", {}, "en", None)
                twin = reg.open("a.example", {}, "en", None)
                refused = reg.open("b.example", {}, "en", None)
                out = {"distinct": a != twin, "idLength": len(a), "refused": refused}

                def outcome(sid, host):
                    try:
                        reg.acquire(sid, host)
                        reg.release(sid)
                        return "served"
                    except KeyError:
                        return "unknown"
                    except PermissionError:
                        return "refused"

                out["own"] = outcome(a, "a.example")
                out["otherHost"] = outcome(a, "b.example")
                out["forged"] = outcome("not-an-id", "a.example")

                reg.close(twin)
                try:
                    reg.open("fails.example", {}, "en", None)
                    out["launchFailure"] = None
                except RuntimeError as e:
                    out["launchFailure"] = str(e)
                b = reg.open("b.example", {}, "en", None)
                out["afterFailure"] = b is not None
                out["closedTwin"] = outcome(twin, "a.example")

                # Idle expiry: a is busy across the idle window, b sits unused.
                reg.acquire(a, "a.example")
                clock.now += 301
                out["reapedWhileBusy"] = sorted(reg.reap()) == [b]
                reg.release(a)
                clock.now += 299
                out["reapedEarly"] = reg.reap()
                clock.now += 2
                out["reapedIdle"] = reg.reap() == [a]
                out["afterReap"] = outcome(a, "a.example")
                out["allClosed"] = all(s.closed for s in built)

                # The sidecar exits: every session closes first.
                exits = []
                serve.os._exit = exits.append
                last = serve.SessionRegistry(4, 300, FakeSession, clock)
                for host in ("c.example", "d.example"):
                    last.open(host, {}, "en", None)
                serve._exit(last)
                out["exitCode"] = exits
                out["exitClosed"] = [s.closed and s.joined for s in built[-2:]]
                out["exitLive"] = len(last)
                out["defaults"] = [serve.DEFAULT_MAX_SESSIONS, serve.DEFAULT_SESSION_IDLE_MIN]
                print("PROBE:" + json.dumps(out))
                """);
        assertTrue(out.get("distinct").getAsBoolean(), "two opens for one host are two sessions, never one shared");
        assertTrue(out.get("idLength").getAsInt() >= 22, "an id another crawl could guess is not a boundary");
        assertTrue(out.get("refused").isJsonNull(), "past the cap a session is refused, not queued");
        assertEquals("served", out.get("own").getAsString());
        assertEquals("refused", out.get("otherHost").getAsString(), "a session serves only its own host");
        assertEquals("unknown", out.get("forged").getAsString());
        assertEquals("launch failed", out.get("launchFailure").getAsString());
        assertTrue(out.get("afterFailure").getAsBoolean(), "a launch that failed gives its slot back");
        assertEquals("unknown", out.get("closedTwin").getAsString(), "a closed session serves nothing");

        assertTrue(out.get("reapedWhileBusy").getAsBoolean(), "an idle session is reaped, one rendering is not");
        assertEquals("[]", out.get("reapedEarly").toString(), "the idle clock restarts when a render ends");
        assertTrue(out.get("reapedIdle").getAsBoolean());
        assertEquals("unknown", out.get("afterReap").getAsString());
        assertTrue(out.get("allClosed").getAsBoolean(), "every session that left the registry was closed");

        assertEquals("[0]", out.get("exitCode").toString());
        assertEquals("[true,true]", out.get("exitClosed").toString(), "an exiting sidecar closes each browser first");
        assertEquals(0, out.get("exitLive").getAsInt());
        assertEquals(RenderedFetcher.RENDER_SLOTS, out.getAsJsonArray("defaults").get(0).getAsInt(),
                "no more idle session browsers than rendering ones");
    }

    @Test
    void aSessionLaunchesOnceWithItsPinAndKeepsTheGatesOnItsContext() throws Exception {
        // Patchright stubbed at the seams the session calls, so the launch, the context and the gate
        // are the session's own code path; the loopback fixture run is the live half.
        var out = probe("sidecar/stealth", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve

                calls = {"launches": [], "routes": [], "contexts": [], "navigated": [], "closed": 0}
                handlers = {}

                class Route:
                    def __init__(self, url):
                        self.request = type("Request", (), {"url": url})()
                        self.outcome = None
                    def abort(self):
                        self.outcome = "abort"
                    def continue_(self):
                        self.outcome = "continue"

                class Page:
                    def close(self):
                        pass

                class Context:
                    def __init__(self):
                        self.pages = []
                    def route(self, pattern, handler):
                        calls["routes"].append("route " + pattern)
                        handlers["route"] = handler
                    def route_web_socket(self, pattern, handler):
                        calls["routes"].append("ws " + pattern)
                    def new_page(self):
                        page = Page()
                        self.pages.append(page)
                        return page

                class Browser:
                    def new_context(self, **options):
                        calls["contexts"].append(sorted(options))
                        return Context()
                    def is_connected(self):
                        return True
                    def close(self):
                        calls["closed"] += 1

                class Playwright:
                    def __enter__(self):
                        return self
                    def __exit__(self, *exc):
                        return False

                def launch(p, args, proxy=None):
                    calls["launches"].append({"args": [a for a in args if a.startswith(("--host-resolver", "--lang"))],
                                              "proxy": proxy})
                    return Browser()

                def navigate(context, page, url, *rest):
                    calls["navigated"].append(url)
                    if url.endswith("/a"):
                        # The page reaches loopback and its own pinned host during the first render only.
                        for target in ("http://127.0.0.1/admin", "https://example.com/style.css"):
                            route = Route(target)
                            handlers["route"](route)
                            calls.setdefault("outcomes", []).append([target, route.outcome])
                    return {"html": "<p>ok</p>", "status": 200, "settledStatus": 200, "url": url, "challenge": None}

                serve.sync_playwright = Playwright
                serve._launch = launch
                serve._navigate = navigate
                serve._host_allowed = lambda host, budget=None: False

                session = serve.BrowserSession("example.com", {"example.com": "93.184.215.14"}, "de-DE",
                                               {"server": "http://127.0.0.1:3128"})
                first = session.render("https://example.com/a", 1000, 0, 0, False, "domcontentloaded", False)
                second = session.render("https://example.com/b", 1000, 0, 0, False, "domcontentloaded", False)
                session.close()
                session.join(5)
                try:
                    session.render("https://example.com/c", 1000, 0, 0, False, "domcontentloaded", False)
                    lost = False
                except serve._SessionLost:
                    lost = True
                print("PROBE:" + json.dumps(dict(calls, first=sorted(first["blocked"]), second=sorted(second["blocked"]),
                                               alive=session._thread.is_alive(), lost=lost)))
                """);
        assertEquals(1, out.getAsJsonArray("launches").size(), "two renders, one browser: " + out);
        var launch = out.getAsJsonArray("launches").get(0).getAsJsonObject();
        assertEquals("[\"--lang=de-DE\",\"--host-resolver-rules=MAP example.com 93.184.215.14\"]",
                launch.get("args").toString(), "the pin validated at open is the launch's, for its whole life");
        assertEquals("{\"server\":\"http://127.0.0.1:3128\"}", launch.get("proxy").toString());
        assertEquals("[\"route **/*\",\"ws **/*\"]", out.get("routes").toString(),
                "both gates on the context, once, so every page and popup of the session passes them");
        assertEquals("[[\"screen\",\"service_workers\",\"viewport\"]]", out.get("contexts").toString(),
                "one context, and no storage state or profile handed to it");
        assertEquals("[[\"http://127.0.0.1/admin\",\"abort\"],[\"https://example.com/style.css\",\"continue\"]]",
                out.get("outcomes").toString(), "the gate still aborts loopback and exempts only the pinned host");
        assertEquals("[\"127.0.0.1\"]", out.get("first").toString());
        assertEquals("[]", out.get("second").toString(), "each render reports only the hosts it was refused");
        assertEquals(1, out.get("closed").getAsInt(), "closing the session closes its browser");
        assertFalse(out.get("alive").getAsBoolean(), "and ends its thread");
        assertTrue(out.get("lost").getAsBoolean(), "a closed session refuses a render rather than hanging it");
    }

    @Test
    void theSessionRoutesKeepEveryScreenAndLetTheJvmRecover() throws Exception {
        // The real handler on a spare port, with sessions that launch nothing.
        var out = probe("sidecar/stealth", """
                import sys, json, threading, urllib.request, urllib.error
                sys.path.insert(0, sys.argv[1])
                import serve
                from http.server import ThreadingHTTPServer

                renders = []

                class FakeSession:
                    def __init__(self, host, pins, language, proxy):
                        self.host = host
                        self.closed = False
                    def render(self, url, timeout_ms, settle_ms, challenge_ms, solve, wait_until, clearance):
                        renders.append({"url": url, "clearance": clearance})
                        if url.endswith("/crashed"):
                            raise serve._SessionLost("the browser exited")
                        result = {"html": "<p>ok</p>", "status": 200, "settledStatus": 200, "url": url,
                                  "challenge": "managed; cleared", "blocked": set()}
                        if clearance:
                            result.update({"clearance": "c1e4r", "userAgent": "Mozilla/5.0 Chrome/146.0.0.0"})
                        return result
                    def close(self):
                        self.closed = True
                    def join(self, timeout):
                        pass

                serve.sync_playwright = object()  # the fake sessions launch nothing
                serve.Handler.token = None
                serve.Handler.state = serve.SidecarState("patchright-chromium", 0, 4)
                serve.Handler.sessions = serve.SessionRegistry(1, 300, FakeSession)
                server = ThreadingHTTPServer(("127.0.0.1", 0), serve.Handler)
                threading.Thread(target=server.serve_forever, daemon=True).start()

                def post(path, body):
                    req = urllib.request.Request("http://127.0.0.1:%d%s" % (server.server_address[1], path),
                                                 data=json.dumps(body).encode(), method="POST",
                                                 headers={"Content-Type": "application/json"})
                    try:
                        with urllib.request.urlopen(req) as r:
                            return {"status": r.status, "headers": {k: v for k, v in r.headers.items() if k.startswith("X-")},
                                    "body": r.read().decode()}
                    except urllib.error.HTTPError as e:
                        return {"status": e.code, "body": e.read().decode()}

                out = {
                    "foreignPin": post("/session/open", {"host": "example.com", "pins": {"other.example": "93.184.215.14"}})["status"],
                    "privatePin": post("/session/open", {"host": "example.com", "pins": {"example.com": "10.0.0.1"}})["status"],
                    "noHost": post("/session/open", {"pins": {}})["status"],
                }
                opened = post("/session/open", {"host": "Example.com", "pins": {"example.com": "93.184.215.14"}})
                sid = json.loads(opened["body"])["id"]
                out["opened"] = opened["status"]
                out["atCap"] = post("/session/open", {"host": "other.example"})["status"]
                out["unknown"] = post("/render", {"url": "https://example.com/", "session": "nope"})["status"]
                out["otherHost"] = post("/render", {"url": "https://other.example/", "session": sid})["status"]
                out["withPins"] = post("/render", {"url": "https://example.com/", "session": sid,
                                                   "pins": {"example.com": "93.184.215.14"}})["status"]
                out["plain"] = post("/render", {"url": "https://example.com/p", "session": sid})
                out["asked"] = post("/render", {"url": "https://example.com/q", "session": sid, "clearance": True})
                out["crashed"] = post("/render", {"url": "https://example.com/crashed", "session": sid})["status"]
                out["afterCrash"] = post("/render", {"url": "https://example.com/r", "session": sid})["status"]
                out["liveAfterCrash"] = len(serve.Handler.sessions)
                again = json.loads(post("/session/open", {"host": "example.com"})["body"])["id"]
                out["closed"] = json.loads(post("/session/close", {"id": again})["body"])["closed"]
                out["closedTwice"] = json.loads(post("/session/close", {"id": again})["body"])["closed"]
                out["renders"] = renders
                server.shutdown()
                print("PROBE:" + json.dumps(out))
                """);
        assertEquals(400, out.get("foreignPin").getAsInt(), "a session pins its own host and no other");
        assertEquals(400, out.get("privatePin").getAsInt(), "a pin exempts its host from the gate, so it must be public");
        assertEquals(400, out.get("noHost").getAsInt());
        assertEquals(200, out.get("opened").getAsInt());
        assertEquals(429, out.get("atCap").getAsInt(), "past the cap the JVM is told to render per page");
        assertEquals(404, out.get("unknown").getAsInt(), "404 is what makes the JVM open a new session");
        assertEquals(400, out.get("otherHost").getAsInt(), "a session serves only its own host");
        assertEquals(400, out.get("withPins").getAsInt(), "a session render cannot bring a pin of its own");

        var plain = out.getAsJsonObject("plain");
        assertEquals(200, plain.get("status").getAsInt());
        assertEquals("managed; cleared", plain.getAsJsonObject("headers").get("X-Challenge").getAsString());
        assertFalse(plain.getAsJsonObject("headers").has("X-Clearance"), "the cookie leaves only when asked for");
        var asked = out.getAsJsonObject("asked").getAsJsonObject("headers");
        assertEquals("c1e4r", asked.get("X-Clearance").getAsString());
        assertEquals("Mozilla/5.0 Chrome/146.0.0.0", asked.get("X-Clearance-User-Agent").getAsString());
        assertEquals(List.of(false, true, false), out.getAsJsonArray("renders").asList().stream()
                .map(r -> r.getAsJsonObject().get("clearance").getAsBoolean()).toList(),
                "the flag reaches the session exactly as the JVM sent it");

        assertEquals(404, out.get("crashed").getAsInt(), "a session whose browser died is gone, not failing");
        assertEquals(404, out.get("afterCrash").getAsInt());
        assertEquals(0, out.get("liveAfterCrash").getAsInt(), "and its slot is free for the reopen");
        assertTrue(out.get("closed").getAsBoolean());
        assertFalse(out.get("closedTwice").getAsBoolean(), "closing twice is harmless: a crawl's finally may");
    }

    @Test
    void theFetchSidecarReportsTheChromeVersionItsProfileImpersonates() throws Exception {
        // The resolver is passed in, so the answer does not depend on which curl_cffi this host has.
        var out = probe("sidecar/fetch", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                import serve
                aliases = {"chrome": "chrome146", "chrome_android": "chrome131_android", "safari": "safari2601"}
                resolve = lambda profile: aliases.get(profile, profile)
                print("PROBE:" + json.dumps({p: serve.chrome_major(p, resolve) for p in
                    ["chrome", "chrome146", "chrome133a", "chrome_android", "chrome131_android", "safari",
                     "edge101", "firefox147", "chrome146x2"]}))
                """);
        assertEquals("{\"chrome\":146,\"chrome146\":146,\"chrome133a\":133,\"chrome_android\":null,"
                        + "\"chrome131_android\":null,\"safari\":null,\"edge101\":null,\"firefox147\":null,"
                        + "\"chrome146x2\":null}",
                out.toString(), "only a desktop Chrome profile has a version a Chrome browser's clearance can match");
    }
}
