import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.SsrfGuard;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Unit tests for {@link SsrfGuard}. The IP-range checks are pure functions
 * of {@link InetAddress} predicates, so these don't need any network: we
 * construct addresses directly from literals and assert the classification.
 */
class SsrfGuardTest extends UnitTest {

    // --- isUnsafe: blocked ranges ---

    @Test
    void isUnsafeRejectsLoopbackIpv4() throws Exception {
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("127.0.0.1")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("127.255.255.254")));
    }

    @Test
    void isUnsafeRejectsLoopbackIpv6() throws Exception {
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("::1")));
    }

    @Test
    void isUnsafeRejectsLinkLocalAwsMetadata() throws Exception {
        // 169.254.169.254 is the AWS/Azure/GCP instance metadata endpoint —
        // the Capital One 2019 breach URL. This test is the canary.
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("169.254.169.254")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("169.254.0.1")));
    }

    @Test
    void isUnsafeRejectsRfc1918Ranges() throws Exception {
        // 10/8
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("10.0.0.1")));
        // 172.16/12
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("172.16.0.1")));
        // 192.168/16
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("192.168.1.1")));
    }

    @Test
    void isUnsafeRejectsAnyLocal() throws Exception {
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("0.0.0.0")));
    }

    @Test
    void isUnsafeRejectsMulticast() throws Exception {
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("224.0.0.1")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("239.255.255.255")));
    }

    // --- isUnsafe: routable public IPs ---

    @Test
    void isUnsafeAcceptsPublicIpv4() throws Exception {
        // 8.8.8.8 (Google DNS) — a known public routable address.
        assertFalse(SsrfGuard.isUnsafe(InetAddress.getByName("8.8.8.8")));
        // 1.1.1.1 (Cloudflare DNS).
        assertFalse(SsrfGuard.isUnsafe(InetAddress.getByName("1.1.1.1")));
    }

    // --- SAFE_DNS: one-unsafe-record poisons the whole lookup ---

    @Test
    void safeDnsRejectsLoopbackHostname() {
        // "localhost" resolves to 127.0.0.1 — must throw UnknownHostException
        // with our SSRF marker, not open a socket.
        var ex = assertThrows(UnknownHostException.class,
                () -> SsrfGuard.SAFE_DNS.lookup("localhost"));
        assertTrue(ex.getMessage().contains("SSRF guard"),
                "exception must identify the guard: " + ex.getMessage());
    }

    // --- assertSafeScheme ---

    @Test
    void assertSafeSchemeAcceptsHttpAndHttps() {
        SsrfGuard.assertSafeScheme(URI.create("http://example.com/"));
        SsrfGuard.assertSafeScheme(URI.create("https://example.com/path?q=1"));
        // Case-insensitive: uppercase scheme must also pass.
        SsrfGuard.assertSafeScheme(URI.create("HTTPS://example.com/"));
    }

    @Test
    void assertSafeSchemeRejectsFileScheme() {
        var uri = URI.create("file:///etc/passwd");
        var ex = assertThrows(SecurityException.class,
                () -> SsrfGuard.assertSafeScheme(uri));
        assertTrue(ex.getMessage().contains("scheme not allowed"),
                "exception must explain: " + ex.getMessage());
    }

    @Test
    void assertSafeSchemeRejectsExoticSchemes() {
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
    void assertSafeSchemeRejectsHostlessUri() {
        var uri = URI.create("http:///no-host");
        var ex = assertThrows(SecurityException.class,
                () -> SsrfGuard.assertSafeScheme(uri));
        assertTrue(ex.getMessage().contains("no host"),
                "exception must identify the missing host: " + ex.getMessage());
    }

    // --- buildGuardedClient ---

    @Test
    void buildGuardedClientHasRedirectsDisabled() {
        // Manual redirect handling is load-bearing: callers must walk each
        // hop through assertSafeScheme, which the client skips itself.
        var client = SsrfGuard.buildGuardedClient(5, 10);
        assertFalse(client.followRedirects(),
                "guarded client must NOT auto-follow redirects");
        assertFalse(client.followSslRedirects(),
                "guarded client must NOT auto-follow SSL redirects");
    }

    @Test
    void buildGuardedClientUsesSafeDns() {
        var client = SsrfGuard.buildGuardedClient(5, 10);
        assertSame(SsrfGuard.SAFE_DNS, client.dns(),
                "guarded client must wire SAFE_DNS — this is the whole point");
    }

    // ── JCLAW-116: full-URL helpers for callers outside the OkHttp path ──

    @Test
    void assertUrlSafeRejectsLoopbackLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://127.0.0.1:9000/admin"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://[::1]/"));
    }

    @Test
    void assertUrlSafeRejectsCloudMetadataLiteral() {
        // The classic EC2/GCP metadata IP — MUST stay blocked via the
        // literal-IP path in assertSafeScheme.
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe(
                        "http://169.254.169.254/latest/meta-data/iam/security-credentials/"));
    }

    @Test
    void assertUrlSafeRejectsPrivateNetworkLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://10.0.0.5/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://192.168.1.1/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://172.16.0.1/"));
    }

    @Test
    void assertUrlSafeRejectsFileScheme() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("file:///etc/passwd"));
    }

    @Test
    void assertUrlSafeAcceptsPublicUrl() {
        // localhost name and loopback IPs have been ruled out; a well-known
        // public hostname should pass. Use example.com which resolves to
        // public IPs across test environments.
        SsrfGuard.assertUrlSafe("https://example.com/");
    }

    @Test
    void isUrlSafeNonThrowingReturnsFalseForUnsafe() {
        // Hot-path variant used by route interceptors — never throws.
        assertFalse(SsrfGuard.isUrlSafe("http://127.0.0.1/"));
        assertFalse(SsrfGuard.isUrlSafe("http://169.254.169.254/"));
        assertFalse(SsrfGuard.isUrlSafe("file:///etc/passwd"));
        assertFalse(SsrfGuard.isUrlSafe("not-a-valid-url"));
    }

    @Test
    void isUrlSafeNonThrowingReturnsTrueForPublic() {
        assertTrue(SsrfGuard.isUrlSafe("https://example.com/"));
    }

    // ── JCLAW-145: IPv6 link-local / ULA, IPv4 decimal integer form ──

    @Test
    void isUnsafeRejectsIpv6LinkLocal() throws Exception {
        // fe80::/10 — JDK's isLinkLocalAddress covers this range.
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("fe80::1")));
        assertTrue(SsrfGuard.isUnsafe(InetAddress.getByName("fe80::dead:beef")));
    }

    @Test
    void assertUrlSafeRejectsIpv6LinkLocalLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://[fe80::1]/"));
    }

    @Test
    void isUnsafeRejectsIpv6UniqueLocal() throws Exception {
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
    void isUnsafeDoesNotRejectNonUlaIpv6() throws Exception {
        // 2001:db8::/32 is documentation-reserved — non-ULA, non-link-local,
        // non-loopback. Must NOT be blocked (false positives break real
        // IPv6 endpoints).
        assertFalse(SsrfGuard.isUnsafe(InetAddress.getByName("2001:db8::1")));
        // fe00::1 is near the ULA range but above it (starts with 0xFE,
        // not 0xFC or 0xFD). Must not be captured by the ULA check.
        assertFalse(SsrfGuard.isUnsafe(InetAddress.getByName("fe00::1")));
    }

    @Test
    void assertUrlSafeRejectsIpv6UlaLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://[fc00::1]/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://[fd12::1]/"));
    }

    @Test
    void assertUrlSafeRejectsIpv4DecimalLoopback() {
        // 2130706433 == 0x7F000001 == 127.0.0.1. Java's InetAddress resolves
        // bare decimal integers as packed IPv4. isLikelyIpLiteral misses
        // this form (no dots), so the guard relies on the subsequent
        // DNS-resolution pass in assertUrlSafe to catch the resolved
        // loopback via isUnsafe.
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://2130706433/"));
    }

    // ── JCLAW-731: parser-differential hardening + literal-IP pinning ──

    @Test
    void assertUrlSafeRejectsEmbeddedCredentials() {
        // Userinfo blurs which token the host is across Java's URI parser and
        // Chromium's WHATWG parser. Reject it even when the parsed host is
        // itself public — LLM browse URLs never carry credentials.
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://user:pass@example.com/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://attacker@example.com/"));
        assertFalse(SsrfGuard.isUrlSafe("http://user:pass@example.com/"));
    }

    @Test
    void assertUrlSafeRejectsBackslashAuthority() {
        // Browsers remap '\' to '/', so the authority can end earlier than
        // java.net.URI thinks: http://internal.example\@evil/ connects to
        // internal.example in Chromium. Reject any backslash outright.
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://good.example\\@169.254.169.254/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://example.com\\..evil.com/"));
    }

    @Test
    void assertUrlSafeRejectsControlAndSpaceChars() {
        // Browsers strip tab/CR/LF mid-URL before re-parsing, moving the
        // authority boundary. Reject raw control chars and spaces.
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://exa\tmple.com/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://exa\nmple.com/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertUrlSafe("http://exam ple.com/"));
    }

    @Test
    void pinnedUrlRewritesHostToValidatedLiteralIp() throws Exception {
        // The pin resolves the host once, validates the IP, and hands back a
        // URL whose authority IS that literal IP — so the string validated is
        // byte-for-byte the string fetched (no hostname left to re-resolve).
        var pinned = SsrfGuard.pinnedUrl("https://example.com/path?q=1#frag");
        var pinnedHost = URI.create(pinned).getHost();
        assertNotNull(pinnedHost, "pinned URL must have a host");
        assertFalse(pinnedHost.contains("example.com"),
                "pinned host must be a literal IP, not the hostname: " + pinned);
        // The literal round-trips through InetAddress and is itself safe.
        assertFalse(SsrfGuard.isUnsafe(InetAddress.getByName(
                pinnedHost.replace("[", "").replace("]", ""))));
        assertTrue(SsrfGuard.isUrlSafe(pinned),
                "the pinned literal-IP URL must itself pass the guard");
        // Path, query, and fragment survive the rewrite.
        assertTrue(pinned.endsWith("/path?q=1#frag"),
                "pin must preserve path/query/fragment: " + pinned);
    }

    @Test
    void pinnedUrlLeavesApprovedLiteralIpUnchanged() {
        // A public literal IP is already pinned — nothing to resolve, returned
        // verbatim so port and path are untouched.
        assertEquals("http://8.8.8.8:8080/x?y=1",
                SsrfGuard.pinnedUrl("http://8.8.8.8:8080/x?y=1"));
    }

    @Test
    void pinnedUrlRejectsUnsafeAndDifferentialUrls() {
        // Pinning must fail closed on everything assertUrlSafe rejects.
        assertThrows(SecurityException.class,
                () -> SsrfGuard.pinnedUrl("http://169.254.169.254/latest/meta-data/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.pinnedUrl("http://127.0.0.1/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.pinnedUrl("http://localhost/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.pinnedUrl("http://user@example.com/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.pinnedUrl("file:///etc/passwd"));
    }

    @Test
    void ipv4OctalPrefixDoesNotCollideWithLoopback() throws Exception {
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

    // --- hostResolverRule: browser DNS pin (JCLAW-731) ---

    @Test
    void hostResolverRuleEmptyForApprovedLiteralIp() {
        // A public literal IP passes assertUrlSafe and needs no pin — it IS the IP.
        assertTrue(SsrfGuard.hostResolverRule("http://1.1.1.1/").isEmpty());
    }

    @Test
    void hostResolverRuleThrowsOnUnsafeLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.hostResolverRule("http://127.0.0.1/"));
    }

    @Test
    void hostResolverRuleThrowsOnUnsafeHostname() {
        // localhost resolves to loopback → rejected before any pin is emitted.
        assertThrows(SecurityException.class,
                () -> SsrfGuard.hostResolverRule("http://localhost:9000/"));
    }

    // ── JCLAW-778: relaxed provider/MCP guard (permits loopback/LAN) ──

    @Test
    void isBlockedForProviderBlocksLinkLocalAndMetadata() throws Exception {
        // The cloud-metadata IP and the rest of the link-local range are the
        // one escalation surface this relaxed guard must still close.
        assertTrue(SsrfGuard.isBlockedForProvider(InetAddress.getByName("169.254.169.254")));
        assertTrue(SsrfGuard.isBlockedForProvider(InetAddress.getByName("169.254.0.1")));
        assertTrue(SsrfGuard.isBlockedForProvider(InetAddress.getByName("fe80::1")));
    }

    @Test
    void isBlockedForProviderBlocksMulticastAndAnyLocal() throws Exception {
        assertTrue(SsrfGuard.isBlockedForProvider(InetAddress.getByName("224.0.0.1")));
        assertTrue(SsrfGuard.isBlockedForProvider(InetAddress.getByName("0.0.0.0")));
    }

    @Test
    void isBlockedForProviderPermitsLoopbackAndPrivate() throws Exception {
        // Local self-hosted inference (Ollama/LM Studio) and LAN hosts must NOT
        // be blocked — this is the whole reason for the relaxed variant.
        assertFalse(SsrfGuard.isBlockedForProvider(InetAddress.getByName("127.0.0.1")));
        assertFalse(SsrfGuard.isBlockedForProvider(InetAddress.getByName("::1")));
        assertFalse(SsrfGuard.isBlockedForProvider(InetAddress.getByName("10.0.0.5")));
        assertFalse(SsrfGuard.isBlockedForProvider(InetAddress.getByName("192.168.1.1")));
        assertFalse(SsrfGuard.isBlockedForProvider(InetAddress.getByName("172.16.0.1")));
        // Public IPs stay allowed too.
        assertFalse(SsrfGuard.isBlockedForProvider(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void providerSafeDnsAllowsLoopbackHostname() throws Exception {
        // Unlike the strict SAFE_DNS (which rejects localhost), the provider DNS
        // resolves loopback names without throwing.
        var addrs = SsrfGuard.PROVIDER_SAFE_DNS.lookup("localhost");
        assertFalse(addrs.isEmpty(), "localhost must resolve through the provider DNS");
    }

    @Test
    void assertProviderUrlSafeRejectsMetadataLiteral() {
        // http://169.254.169.254/... is the credential-theft primitive — must be
        // rejected before any connect on the agent-settable provider/MCP path.
        var ex = assertThrows(SecurityException.class,
                () -> SsrfGuard.assertProviderUrlSafe(
                        "http://169.254.169.254/latest/meta-data/iam/security-credentials/"));
        assertTrue(ex.getMessage().contains("SSRF guard"), "must identify the guard: " + ex.getMessage());
    }

    @Test
    void assertProviderUrlSafeRejectsIpv6LinkLocalLiteral() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertProviderUrlSafe("http://[fe80::1]/mcp"));
    }

    @Test
    void assertProviderUrlSafePermitsLoopbackAndPrivateLiterals() {
        // These must NOT throw — local inference + LAN MCP servers depend on it.
        SsrfGuard.assertProviderUrlSafe("http://127.0.0.1:11434/v1");
        SsrfGuard.assertProviderUrlSafe("http://[::1]/mcp");
        SsrfGuard.assertProviderUrlSafe("http://10.0.0.5:8080/v1");
        SsrfGuard.assertProviderUrlSafe("http://192.168.1.50/mcp");
    }

    @Test
    void assertProviderUrlSafePermitsHostnamesWithoutResolving() {
        // Hostnames are screened at connect by PROVIDER_SAFE_DNS, not here, so a
        // public host and even a not-yet-live host both pass validation offline.
        SsrfGuard.assertProviderUrlSafe("https://openrouter.ai/api/v1");
        SsrfGuard.assertProviderUrlSafe("http://not-a-real-host-xyz/mcp");
    }

    @Test
    void assertProviderUrlSafeRejectsNonHttpSchemeAndHostless() {
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertProviderUrlSafe("file:///etc/passwd"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertProviderUrlSafe("gopher://evil/"));
        assertThrows(SecurityException.class,
                () -> SsrfGuard.assertProviderUrlSafe("http:///no-host"));
    }
}
