import org.junit.jupiter.api.*;
import play.test.*;
import utils.SsrfGuard;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Unit tests for {@link SsrfGuard}. The IP-range checks are pure functions
 * of {@link InetAddress} predicates, so these don't need any network: we
 * construct addresses directly from literals and assert the classification.
 */
public class SsrfGuardTest extends UnitTest {

    // --- isUnsafe: blocked ranges ---

    @Test
    public void isUnsafeRejectsLoopbackIpv4() throws Exception {
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("127.0.0.1")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("127.255.255.254")));
    }

    @Test
    public void isUnsafeRejectsLoopbackIpv6() throws Exception {
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("::1")));
    }

    @Test
    public void isUnsafeRejectsLinkLocalAwsMetadata() throws Exception {
        // 169.254.169.254 is the AWS/Azure/GCP instance metadata endpoint —
        // the Capital One 2019 breach URL. This test is the canary.
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("169.254.169.254")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("169.254.0.1")));
    }

    @Test
    public void isUnsafeRejectsRfc1918Ranges() throws Exception {
        // 10/8
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("10.0.0.1")));
        // 172.16/12
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("172.16.0.1")));
        // 192.168/16
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("192.168.1.1")));
    }

    @Test
    public void isUnsafeRejectsAnyLocal() throws Exception {
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("0.0.0.0")));
    }

    @Test
    public void isUnsafeRejectsMulticast() throws Exception {
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("224.0.0.1")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("239.255.255.255")));
    }

    // --- isUnsafe: routable public IPs ---

    @Test
    public void isUnsafeAcceptsPublicIpv4() throws Exception {
        // 8.8.8.8 (Google DNS) — a known public routable address.
        assertFalse(SsrfGuard.isUnsafe(InetAddress.getByName("8.8.8.8")));
        // 1.1.1.1 (Cloudflare DNS).
        assertFalse(SsrfGuard.isUnsafe(InetAddress.getByName("1.1.1.1")));
    }

    // --- SAFE_DNS: one-unsafe-record poisons the whole lookup ---

    @Test
    public void safeDnsRejectsLoopbackHostname() {
        // "localhost" resolves to 127.0.0.1 — must throw UnknownHostException
        // with our SSRF marker, not open a socket.
        var ex = assertThrows(UnknownHostException.class,
                () -> SsrfGuard.SAFE_DNS.lookup("localhost"));
        assertTrue(ex.getMessage().contains("SSRF guard"),
                "exception must identify the guard: " + ex.getMessage());
    }

    // --- assertSafeScheme ---

    @Test
    public void assertSafeSchemeAcceptsHttpAndHttps() {
        SsrfGuard.assertSafeScheme(URI.create("http://example.com/"));
        SsrfGuard.assertSafeScheme(URI.create("https://example.com/path?q=1"));
        // Case-insensitive: uppercase scheme must also pass.
        SsrfGuard.assertSafeScheme(URI.create("HTTPS://example.com/"));
    }

    @Test
    public void assertSafeSchemeRejectsFileScheme() {
        var uri = URI.create("file:///etc/passwd");
        var ex = assertThrows(SecurityException.class,
                () -> SsrfGuard.assertSafeScheme(uri));
        assertTrue(ex.getMessage().contains("scheme not allowed"),
                "exception must explain: " + ex.getMessage());
    }

    @Test
    public void assertSafeSchemeRejectsExoticSchemes() {
        // gopher is a classic SSRF-amplifier (Redis RCE via gopher://),
        // data: can embed payloads, ftp: is legacy and widely forbidden.
        for (var scheme : new String[] {"gopher", "ftp", "data", "jar", "ldap"}) {
            var uri = URI.create(scheme + "://evil/");
            assertThrows(SecurityException.class,
                    () -> SsrfGuard.assertSafeScheme(uri),
                    "scheme " + scheme + " must be rejected");
        }
    }

    @Test
    public void assertSafeSchemeRejectsHostlessUri() {
        var uri = URI.create("http:///no-host");
        var ex = assertThrows(SecurityException.class,
                () -> SsrfGuard.assertSafeScheme(uri));
        assertTrue(ex.getMessage().contains("no host"),
                "exception must identify the missing host: " + ex.getMessage());
    }

    // --- buildGuardedClient ---

    @Test
    public void buildGuardedClientHasRedirectsDisabled() {
        // Manual redirect handling is load-bearing: callers must walk each
        // hop through assertSafeScheme, which the client skips itself.
        var client = SsrfGuard.buildGuardedClient(5, 10);
        assertFalse(client.followRedirects(),
                "guarded client must NOT auto-follow redirects");
        assertFalse(client.followSslRedirects(),
                "guarded client must NOT auto-follow SSL redirects");
    }

    @Test
    public void buildGuardedClientUsesSafeDns() {
        var client = SsrfGuard.buildGuardedClient(5, 10);
        assertSame(SsrfGuard.SAFE_DNS, client.dns(),
                "guarded client must wire SAFE_DNS — this is the whole point");
    }

    // ── JCLAW-116: full-URL helpers for callers outside the OkHttp path ──

    @Test
    public void assertUrlSafeRejectsLoopbackLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://127.0.0.1:9000/admin"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://[::1]/"));
    }

    @Test
    public void assertUrlSafeRejectsCloudMetadataLiteral() {
        // The classic EC2/GCP metadata IP — MUST stay blocked via the
        // literal-IP path in assertSafeScheme.
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe(
                        "http://169.254.169.254/latest/meta-data/iam/security-credentials/"));
    }

    @Test
    public void assertUrlSafeRejectsPrivateNetworkLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://10.0.0.5/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://192.168.1.1/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://172.16.0.1/"));
    }

    @Test
    public void assertUrlSafeRejectsFileScheme() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("file:///etc/passwd"));
    }

    @Test
    public void assertUrlSafeAcceptsPublicUrl() {
        // localhost name and loopback IPs have been ruled out; a well-known
        // public hostname should pass. Use example.com which resolves to
        // public IPs across test environments.
        SsrfGuard.assertUrlSafe("https://example.com/");
    }

    @Test
    public void isUrlSafeNonThrowingReturnsFalseForUnsafe() {
        // Hot-path variant used by route interceptors — never throws.
        assertFalse(SsrfGuard.isUrlSafe("http://127.0.0.1/"));
        assertFalse(SsrfGuard.isUrlSafe("http://169.254.169.254/"));
        assertFalse(SsrfGuard.isUrlSafe("file:///etc/passwd"));
        assertFalse(SsrfGuard.isUrlSafe("not-a-valid-url"));
    }

    @Test
    public void isUrlSafeNonThrowingReturnsTrueForPublic() {
        assertTrue(SsrfGuard.isUrlSafe("https://example.com/"));
    }

    // ── JCLAW-145: IPv6 link-local / ULA, IPv4 decimal integer form ──

    @Test
    public void isUnsafeRejectsIpv6LinkLocal() throws Exception {
        // fe80::/10 — JDK's isLinkLocalAddress covers this range.
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("fe80::1")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("fe80::dead:beef")));
    }

    @Test
    public void assertUrlSafeRejectsIpv6LinkLocalLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://[fe80::1]/"));
    }

    @Test
    public void isUnsafeRejectsIpv6UniqueLocal() throws Exception {
        // fc00::/7 — Unique Local Address (RFC 4193). JDK's predicate
        // coverage misses this: isSiteLocalAddress only matches the
        // deprecated fec0::/10. SsrfGuard must add an explicit prefix
        // check. If this test regresses, a ULA-reachable service inside
        // the host's network becomes SSRF-reachable.
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("fc00::1")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("fd00::1")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("fcde:ad:be:ef::1")));
    }

    @Test
    public void isUnsafeDoesNotRejectNonUlaIpv6() throws Exception {
        // 2001:db8::/32 is documentation-reserved — non-ULA, non-link-local,
        // non-loopback. Must NOT be blocked (false positives break real
        // IPv6 endpoints).
        assertFalse(SsrfGuard.isUnsafe(InetAddress.getByName("2001:db8::1")));
        // fe00::1 is near the ULA range but above it (starts with 0xFE,
        // not 0xFC or 0xFD). Must not be captured by the ULA check.
        assertFalse(SsrfGuard.isUnsafe(InetAddress.getByName("fe00::1")));
    }

    @Test
    public void assertUrlSafeRejectsIpv6UlaLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://[fc00::1]/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://[fd12::1]/"));
    }

    @Test
    public void assertUrlSafeRejectsIpv4DecimalLoopback() {
        // 2130706433 == 0x7F000001 == 127.0.0.1. Java's InetAddress resolves
        // bare decimal integers as packed IPv4. isLikelyIpLiteral misses
        // this form (no dots), so the guard relies on the subsequent
        // DNS-resolution pass in assertUrlSafe to catch the resolved
        // loopback via isUnsafe.
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://2130706433/"));
    }

    @Test
    public void ipv4OctalPrefixDoesNotCollideWithLoopback() throws Exception {
        // Pin: modern JDKs do NOT interpret leading-zero octets as octal per
        // RFC 5735 hardening. InetAddress.getByName("0177.0.0.1") yields
        // 177.0.0.1, NOT 127.0.0.1. If this assertion ever fails, the JDK
        // has re-introduced octal parsing and SsrfGuard needs an explicit
        // pre-DNS normaliser to catch `0177` → 127 before resolution.
        var resolved = InetAddress.getByName("0177.0.0.1");
        assertEquals("177.0.0.1", resolved.getHostAddress(),
                "JDK must not interpret leading-zero octets as octal");
        assertFalse(resolved.isLoopbackAddress(),
                "0177.0.0.1 resolved to a loopback — JDK regression, SsrfGuard "
                        + "needs explicit octal handling");
    }
}
