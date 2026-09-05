package services.printing;

import java.util.Map;

/**
 * One printer found on the local network, or addressed directly by host.
 *
 * @param name         the human-facing name the printer advertises (mDNS instance
 *                     name, e.g. {@code "HP LaserJet M404"}), or the host when the
 *                     printer was addressed directly rather than discovered
 * @param host         resolved IPv4/IPv6 address or hostname
 * @param port         the advertised port for {@code protocol}
 * @param protocol     which wire protocol this record was advertised under
 * @param capabilities TXT-record key/values the printer published — {@code pdl}
 *                     (supported document formats), {@code rp} (IPP resource path),
 *                     {@code Color}, {@code Duplex} and friends. Empty when the
 *                     printer was addressed directly. Deliberately raw: the TXT keys
 *                     are vendor-flavored and normalizing them here would throw away
 *                     the detail an operator needs when a job lands wrong.
 */
public record DiscoveredPrinter(String name, String host, int port,
                                PrintProtocol protocol, Map<String, String> capabilities) {

    public DiscoveredPrinter {
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }

    /**
     * The IPP resource path this printer advertises, defaulting to {@code /ipp/print}
     * (the RFC 8011 convention) when no {@code rp} TXT record is present.
     *
     * <p>Leading slash is normalized because the TXT record convention is to omit it
     * ({@code rp=ipp/print}) while the URI needs it.
     */
    public String ippResourcePath() {
        var rp = capabilities.get("rp");
        if (rp == null || rp.isBlank()) {
            return "/ipp/print";
        }
        return rp.startsWith("/") ? rp : "/" + rp;
    }

    /** {@code ipp://host:port/path} — the URI the IPP backend prints to. */
    public String ippUri() {
        var scheme = protocol == PrintProtocol.IPPS ? "ipps" : "ipp";
        return scheme + "://" + host + ":" + port + ippResourcePath();
    }
}
