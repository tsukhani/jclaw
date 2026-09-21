import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.StealthSidecarManager;
import services.scrape.BlockClassifier;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.scrape.RenderedFetcher;
import utils.SsrfGuard;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Rung 3 — the stealth rendering sidecar (JCLAW-1088).
 *
 * <p>The load-bearing test here is the SSRF parity one. Moving the browser launch out of
 * the JVM moved {@code --host-resolver-rules} with it, and the sidecar had to gain its
 * own IP-range check for the hosts a page reaches on its own. That is a second
 * implementation of a security check, so it is pinned against the first rather than
 * trusted to stay in step.
 */
class StealthBrowserTest extends UnitTest {

    /** Deliberately spans both families and every category the guard rejects. */
    private static final List<String> ADDRESSES = List.of(
            "8.8.8.8", "1.1.1.1", "93.184.215.14",       // public v4
            "2606:4700:4700::1111",                       // public v6
            "127.0.0.1", "::1",                           // loopback
            "10.0.0.1", "172.16.0.1", "192.168.1.1",      // private v4
            "fd00::1",                                    // private v6
            "169.254.169.254", "fe80::1",                 // link-local (incl. cloud metadata)
            "224.0.0.1", "ff02::1",                       // multicast
            "0.0.0.0", "::",                              // unspecified
            // Classes the two implementations once disagreed on. The table held none
            // of them, so it passed while asserting an equality it could not have
            // caught a drift in — and the divergences ran in both directions:
            // ipaddress admitted fec0::/10 that Java blocked, while Java admitted the
            // five below that ipaddress blocked.
            "fec0::1",                                    // v6 site-local
            "240.0.0.1", "192.0.2.1", "198.18.0.1",       // v4 reserved/doc/benchmark
            "255.255.255.255",                            // broadcast
            "64:ff9b::7f00:1",                            // NAT64 wrapping 127.0.0.1
            "100.64.0.1");                                // CGNAT

    private final ScrapeConfigGuard config = new ScrapeConfigGuard();

    @AfterEach
    void clearOverrides() {
        config.restore();
    }

    // ==================== The duplicated guard ====================

    @Test
    void theSidecarsAddressCheckAgreesWithSsrfGuard() throws Exception {
        // Both copies. The fetch sidecar gained one when rung 2 started validating pin
        // targets, and two files that must agree are exactly what this test is for.
        for (var relative : List.of("sidecar/stealth/ssrf.py", "sidecar/fetch/ssrf.py")) {
            assertGuardIsNoMorePermissiveThanJava(new File(Play.applicationPath, relative));
        }
    }

    @Test
    void theSidecarsProxyCheckIsNoMorePermissiveThanTheProviderGuard() throws Exception {
        // JCLAW-1271: the operator's proxy is judged on the provider rule, not the public one,
        // so loopback and the LAN pass and only link-local, multicast and unspecified do not.
        var table = new java.util.ArrayList<>(ADDRESSES);
        // IPv4 written as IPv6: Java unwraps these to v4, so the Python side must block them too.
        table.addAll(List.of("::ffff:169.254.169.254", "::ffff:224.0.0.1", "::ffff:0.0.0.0", "::ffff:10.0.0.1"));
        for (var relative : List.of("sidecar/stealth/ssrf.py", "sidecar/fetch/ssrf.py")) {
            var script = new File(Play.applicationPath, relative);
            var cmd = new java.util.ArrayList<>(List.of("python3", "-c", """
                    import sys, json
                    sys.path.insert(0, sys.argv[1])
                    from ssrf import is_allowed_proxy_ip
                    print(json.dumps({a: is_allowed_proxy_ip(a) for a in sys.argv[2:]}))
                    """, script.getParent()));
            cmd.addAll(table);
            var proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "python3 proxy probe timed out");
            var stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(0, proc.exitValue(), "python3 proxy probe failed: " + stdout);
            var python = JsonParser.parseString(stdout).getAsJsonObject();

            // Positive controls: a check that refused everything would pass every line below.
            for (var allowed : List.of("127.0.0.1", "10.0.0.1", "8.8.8.8")) {
                assertTrue(python.get(allowed).getAsBoolean(), relative + " refuses proxy address " + allowed);
            }
            for (var address : table) {
                if (python.get(address).getAsBoolean()) {
                    assertFalse(SsrfGuard.isBlockedForProvider(InetAddress.getByName(address)),
                            relative + " admits " + address + " as a proxy, which the JVM blocks");
                }
            }
            for (var mustBlock : List.of("169.254.169.254", "fe80::1", "224.0.0.1", "0.0.0.0", "::",
                                         "::ffff:169.254.169.254")) {
                assertFalse(python.get(mustBlock).getAsBoolean(), relative + " admits proxy " + mustBlock);
            }
        }
    }

    private static void assertGuardIsNoMorePermissiveThanJava(File script) throws Exception {
        assertTrue(script.isFile(), script + " missing — a sidecar's guard has moved or gone");

        var cmd = new java.util.ArrayList<>(List.of("python3", "-c", """
                import sys, json
                sys.path.insert(0, sys.argv[1])
                from ssrf import is_public_ip
                print(json.dumps({a: is_public_ip(a) for a in sys.argv[2:]}))
                """, script.getParent()));
        cmd.addAll(ADDRESSES);

        var proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "python3 parity probe timed out");
        var stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, proc.exitValue(), "python3 parity probe failed: " + stdout);

        var python = JsonParser.parseString(stdout).getAsJsonObject();

        // Positive control. Without it a guard that answered false for everything —
        // which would silently stop rung 3 loading any subresource — satisfies every
        // assertion below and reads as a pass.
        assertTrue(python.get("8.8.8.8").getAsBoolean(),
                script + " rejects a public address — the guard is refusing everything");

        // Both guards must reject these outright. The one-directional check below is
        // the security invariant, but on its own it cannot see the JVM growing a hole:
        // 64:ff9b::7f00:1 is NAT64-wrapped loopback, and SsrfGuard admitted it while
        // this table sat here asserting nothing about it.
        for (var mustBlock : List.of("127.0.0.1", "::1", "169.254.169.254", "10.0.0.1",
                                     "fec0::1", "64:ff9b::7f00:1")) {
            assertFalse(python.get(mustBlock).getAsBoolean(),
                    script + " admits " + mustBlock);
            assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName(mustBlock)),
                    "SsrfGuard admits " + mustBlock);
        }

        for (var address : ADDRESSES) {
            boolean pythonSaysPublic = python.get(address).getAsBoolean();
            boolean javaSaysPublic = !SsrfGuard.isUnsafe(InetAddress.getByName(address));
            // Not equality. The security property is one-directional: the sidecar must
            // never admit what the JVM would reject, and being stricter costs only
            // reach. Asserting equality made the stricter-side differences look like
            // failures, which is why the divergent addresses were absent from the table
            // and the fail-open one went unnoticed.
            if (pythonSaysPublic) {
                assertTrue(javaSaysPublic, script + " admits " + address
                        + " while SsrfGuard rejects it — the duplicated guard has drifted open");
            }
        }
    }

    // ==================== Containment that survives the move ====================

    @Test
    void anUnsafeEntryUrlNeverReachesTheBrowser() {
        // The JVM stays authoritative for the entry URL: hostResolverRule throws
        // everything assertUrlSafe does, so the sidecar is never even contacted.
        for (var url : List.of("http://127.0.0.1:9000/api/status",
                               "http://169.254.169.254/latest/meta-data/",
                               "http://[::1]:9000/")) {
            assertThrows(SecurityException.class, () -> RenderedFetcher.fetch(url),
                    "expected an SsrfGuard refusal for " + url);
        }
    }

    // ==================== Feature detection ====================

    @Test
    void disablingTheRungDegradesRatherThanErroring() {
        config.set(StealthSidecarManager.CFG_ENABLED, "false");
        assertFalse(StealthSidecarManager.available());
        assertFalse(RenderedFetcher.available());
    }

    // ==================== Ladder position ====================

    @Test
    void thinContentEscalatesToTheBrowserWithoutABlockBeingDetected() {
        // The rung must be reachable for a rendering failure, not only for a block:
        // a client-rendered page at zero protection is THIN_CONTENT, and BROWSER is
        // what fixes it.
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.THIN_CONTENT, ScrapeRung.PLAIN));
        assertEquals(ScrapeRung.BROWSER,
                BlockClassifier.nextRung(ScrapeReason.JS_CHALLENGE, ScrapeRung.PLAIN));
    }
}
