package llm;

import services.ConfigService;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Whether a provider runs on the operator's own hardware (JCLAW-939, JCLAW-1102).
 *
 * <p>Answered by the operator's Remote/Local classification, {@code provider.<name>.local} —
 * the same key that groups the Settings cards and gates the chat prefill label. Absent means
 * remote. Embedding a memory sends its full text to the provider, so this decides where that
 * text may go.
 *
 * <p>Not derived from the base URL, and deliberately not merely supplemented by one. A cloud
 * API behind a loopback proxy has a local address and a remote model: an address check that
 * could only ever <em>add</em> locality would offer it for embedding and ship the corpus
 * onward, because the declaration would have no way to say no. A URL cannot answer the
 * question in the other direction either — a tailnet peer sits in {@code 100.64.0.0/10},
 * shared carrier-NAT space that {@code SsrfGuard.isNonRoutableIpv4} separately refuses as
 * reaching the ISP's own equipment, so no range can be blessed on its behalf.
 *
 * <p>That makes locality an assertion rather than a proof. It is the operator's to make:
 * JClaw cannot tell a tailnet address from a carrier-NAT neighbor, and refusing the whole
 * class would rule out self-hosting over a VPN entirely.
 *
 * <p>{@link PaymentModality} is deliberately not used for this. Its empty supported-set marks
 * both free-at-point-of-use providers and unrecognised ones, so an unknown cloud provider
 * would read as local.
 *
 * <p>{@link #isLocalUrl} survives as a pure address predicate for callers asking where a
 * socket goes rather than whether a provider is trusted — the residency pin in
 * {@code EmbeddingModelKeepAlive} is the one such caller. It never resolves hostnames: a DNS
 * lookup on the settings path would be slow, and a name that resolves to loopback today can
 * resolve elsewhere tomorrow.
 */
public final class ProviderLocality {

    private ProviderLocality() {}

    /** Config key suffix for the operator's Remote/Local classification. */
    public static final String DECLARED_LOCAL_SUFFIX = ".local";

    /**
     * True when the operator classified {@code providerName} as self-hosted.
     *
     * <p>Reads config directly rather than going through {@link ProviderRegistry}, which is
     * populated from a sync and can lag a just-saved provider — that would read as remote and
     * refuse a provider the operator has in fact just declared their own.
     */
    public static boolean isLocal(String providerName) {
        if (providerName == null || providerName.isBlank()) return false;
        return ConfigService.getBoolean("provider." + providerName + DECLARED_LOCAL_SUFFIX, false);
    }

    public static boolean isLocalUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        String host;
        try {
            host = URI.create(baseUrl.trim()).getHost();
        } catch (IllegalArgumentException _) {
            return false;
        }
        return host != null && isLocalHost(host);
    }

    static boolean isLocalHost(String host) {
        var h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);   // URI keeps the brackets on IPv6 literals
        }
        if (h.isEmpty()) return false;
        // Local by definition, whatever a resolver would say.
        if (h.equals("localhost") || h.endsWith(".localhost")
                || h.endsWith(".local") || h.equals("host.docker.internal")) {
            return true;
        }
        InetAddress addr;
        try {
            addr = InetAddress.ofLiteral(h);      // never performs a DNS lookup
        } catch (IllegalArgumentException _) {
            return false;                          // a hostname we refuse to resolve
        }
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
            return true;
        }
        // IPv6 unique-local (fc00::/7). Java's isSiteLocalAddress only covers the
        // deprecated fec0::/10 for v6, so ULA needs checking directly.
        var bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
