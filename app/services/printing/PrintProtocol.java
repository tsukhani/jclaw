package services.printing;

/**
 * The wire protocols the printer tool speaks, in the order
 * {@link PrintDispatcher} tries them.
 *
 * <p>The order is a capability ordering, not a preference: IPP is the only one
 * that can report back what happened (job id, state, errors), raw socket is a
 * blind write that succeeds as long as the TCP connection does, and LPD is the
 * 1990 fallback for hardware that predates both. A job that lands via RAW is
 * unverifiable by design — that is the cost of the fallback, not a defect.
 */
public enum PrintProtocol {

    /** RFC 8010/8011 over HTTP, conventionally port 631. Bidirectional. */
    IPP("_ipp._tcp.local.", 631),

    /** IPP over TLS, conventionally port 631. Bidirectional. */
    IPPS("_ipps._tcp.local.", 631),

    /** JetDirect / AppSocket raw stream, conventionally port 9100. Write-only. */
    RAW("_pdl-datastream._tcp.local.", 9100),

    /** RFC 1179 Line Printer Daemon, conventionally port 515. Minimal ack only. */
    LPD("_printer._tcp.local.", 515);

    private final String serviceType;
    private final int defaultPort;

    PrintProtocol(String serviceType, int defaultPort) {
        this.serviceType = serviceType;
        this.defaultPort = defaultPort;
    }

    /** The mDNS/DNS-SD service type printers advertise this protocol under. */
    public String serviceType() {
        return serviceType;
    }

    /** Conventional port, used when a printer is addressed directly rather than discovered. */
    public int defaultPort() {
        return defaultPort;
    }

    /** The protocol advertised under {@code serviceType}, or null if it isn't one of ours. */
    public static PrintProtocol fromServiceType(String serviceType) {
        for (var p : values()) {
            if (p.serviceType.equals(serviceType)) {
                return p;
            }
        }
        return null;
    }

    /** Parse a caller-supplied protocol name, case-insensitively. Null when unrecognized. */
    public static PrintProtocol parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (var p : values()) {
            if (p.name().equalsIgnoreCase(name.trim())) {
                return p;
            }
        }
        return null;
    }
}
