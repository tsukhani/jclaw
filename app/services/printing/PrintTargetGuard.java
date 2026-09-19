package services.printing;

import org.jspecify.annotations.Nullable;
import utils.SsrfGuard;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * Whether a print destination the <em>model</em> named may be dialled (JCLAW-1229).
 *
 * <p>The printer tool accepts an explicit {@code host} and {@code port}, and the
 * backends behind it write raw bytes to whatever answers. That makes a destination
 * argument an outbound-connection primitive, so it is screened the way every other
 * model-chosen destination in this codebase is — except that a printer legitimately
 * lives on the LAN, which the strict {@link SsrfGuard#isUnsafe} guard forbids. The
 * relaxed {@link SsrfGuard#isBlockedForProvider} test is the right one for exactly
 * the reason it exists: it blocks the cloud-metadata range and nothing an operator's
 * own hardware occupies.
 *
 * <p>Three answers rather than two, because a bad destination fails in two different
 * ways. {@link Verdict#REFUSED} is a range no approval can make correct;
 * {@link Verdict#UNVETTED} is a plausible address nobody chose, which is a decision
 * for the operator rather than a refusal.
 *
 * <p>A destination nobody chose is the interesting case: a discovered printer or the
 * saved default was picked by a human or announced itself over mDNS, whereas an
 * address the model composed was not.
 */
public final class PrintTargetGuard {

    /** What the tool may do with a destination the model named. */
    public enum Verdict {
        /** A human or an mDNS announcement already chose this — dial it. */
        ALLOWED,
        /** Never a printer. Refuse before opening a connection. */
        REFUSED,
        /** Plausible, but nobody chose it — route through {@code DangerousActionGate}. */
        UNVETTED
    }

    /**
     * The ports a print backend speaks on. A destination on any other port is not a
     * printer, whatever else it is — which is the half of the port-scan surface that
     * no approval prompt should have to adjudicate.
     */
    private static final Set<Integer> PRINT_PORTS = Set.of(
            PrintProtocol.IPP.defaultPort(),
            PrintProtocol.RAW.defaultPort(),
            PrintProtocol.LPD.defaultPort());

    private PrintTargetGuard() {}

    /** Is {@code port} one a print backend speaks on? */
    public static boolean isPrintPort(int port) {
        return PRINT_PORTS.contains(port);
    }

    /**
     * Classify {@code host:port}.
     *
     * @param port       the port the tool would actually dial, not the raw argument —
     *                   see {@link PrinterDiscovery#directPort}, which is how the two
     *                   are kept identical. Vetting one port and dialling another
     *                   would make this guard decorative
     * @param discovered printers from an mDNS browse, or empty to answer from the
     *                   saved default alone. Passed in rather than browsed here so
     *                   the caller decides when a two-second browse is worth paying
     *                   for; an empty list can only widen the answer to UNVETTED
     */
    public static Verdict classify(@Nullable String host, int port,
                                   PrinterDefaults.Defaults saved,
                                   List<DiscoveredPrinter> discovered) {
        if (host == null || host.isBlank()) {
            return Verdict.UNVETTED;
        }
        if (isForbiddenDestination(host)) {
            return Verdict.REFUSED;
        }
        if (!isPrintPort(port)) {
            return Verdict.UNVETTED;
        }
        if (saved.matches(host, port)) {
            return Verdict.ALLOWED;
        }
        for (var printer : discovered) {
            if (host.equalsIgnoreCase(printer.host()) && port == printer.port()) {
                return Verdict.ALLOWED;
            }
        }
        return Verdict.UNVETTED;
    }

    /**
     * Does {@code host} land in a range that is never a printer — the cloud-metadata
     * address and the rest of link-local, multicast, or the unspecified address?
     *
     * <p>Resolved rather than pattern-matched, because {@code metadata.google.internal}
     * is a name for 169.254.169.254 and a literal-only check would miss it. A name that
     * does not resolve is not refused: the address may only resolve on the network the
     * printer is on, and {@link Verdict#UNVETTED} already puts that in front of the
     * operator.
     */
    public static boolean isForbiddenDestination(@Nullable String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        var bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        try {
            for (var addr : InetAddress.getAllByName(bare)) {
                if (SsrfGuard.isBlockedForProvider(addr)) {
                    return true;
                }
            }
        } catch (UnknownHostException _) {
            // Not a refusal — see the Javadoc; UNVETTED already puts it in front of
            // the operator.
        }
        return false;
    }
}
