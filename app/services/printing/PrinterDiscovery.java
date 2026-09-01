package services.printing;

import services.EventLogger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds printers on the local link via mDNS/DNS-SD (JCLAW-911).
 *
 * <p>Browses the four service types printers advertise under and returns what
 * answered inside a bounded window. Nothing is cached: a discovery result goes
 * stale the moment a printer sleeps or a laptop changes networks, and a stale
 * cache here would send a job to an address that stopped answering — a failure
 * mode that looks like "the printer ate it" rather than "the address was wrong".
 */
public final class PrinterDiscovery {

    private static final String CATEGORY = "printer";

    /**
     * How long to let printers answer the browse. mDNS is a broadcast-and-wait
     * protocol with no completion signal, so the only termination condition is a
     * timer. Two seconds is above the ~1s that sleeping printers typically take to
     * wake their network interface and answer, and short enough that an agent
     * calling {@code discover} does not appear hung.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private PrinterDiscovery() {}

    /** Browse every supported service type with the default window. */
    public static List<DiscoveredPrinter> discover() {
        return discover(DEFAULT_TIMEOUT);
    }

    /**
     * Browse every supported service type and return what answered within
     * {@code timeout}.
     *
     * <p>Returns an empty list rather than throwing when the host has no usable
     * multicast interface — a container with host networking disabled, a locked-down
     * corporate VLAN, or CI. "No printers found" is the honest answer there, and it
     * is what the tool surfaces; a stack trace would suggest a bug that isn't one.
     */
    public static List<DiscoveredPrinter> discover(Duration timeout) {
        var addresses = multicastAddresses();
        if (addresses.isEmpty()) {
            EventLogger.warn(CATEGORY, "No multicast-capable network interface — mDNS discovery skipped");
            return List.of();
        }

        // One browse per (interface, service type). JmDNS has no multi-type list(),
        // and calling list() concurrently on a shared instance is not a contract it
        // documents — so each browse gets its own short-lived instance and they run
        // in parallel. That keeps the wall clock at roughly one timeout instead of
        // interfaces × types × timeout.
        var tasks = new ArrayList<Callable<List<DiscoveredPrinter>>>();
        for (var address : addresses) {
            for (var protocol : PrintProtocol.values()) {
                tasks.add(() -> browse(address, protocol, timeout));
            }
        }

        var found = new LinkedHashMap<String, DiscoveredPrinter>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = pool.invokeAll(tasks, timeout.toSeconds() + 10, TimeUnit.SECONDS);
            for (var future : results) {
                if (future.state() != Future.State.SUCCESS) {
                    continue;
                }
                for (var printer : future.resultNow()) {
                    // Key on HOST, not host:port. One physical printer advertises
                    // several service types on different ports — the Canon E3300
                    // answers on 631 (IPP), 9100 (RAW) and 515 (LPD) — and keying
                    // by port made each a separate row, so the operator saw the
                    // same device three times and had to know which to pick.
                    //
                    // Keep the most capable: PrintProtocol is declared in
                    // capability order, so the lower ordinal wins. Nothing is lost
                    // by discarding the others, because PrintDispatcher falls back
                    // through RAW and LPD on its own using their standard ports.
                    found.merge(printer.host(), printer,
                            (a, b) -> a.protocol().ordinal() <= b.protocol().ordinal() ? a : b);
                }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        return List.copyOf(found.values());
    }

    /** Browse one service type on one interface. Never throws; a dead interface is empty. */
    private static List<DiscoveredPrinter> browse(InetAddress address, PrintProtocol protocol,
                                                  Duration timeout) {
        var hits = new ArrayList<DiscoveredPrinter>();
        try (var jmdns = JmDNS.create(address)) {
            for (var info : jmdns.list(protocol.serviceType(), timeout.toMillis())) {
                var printer = toPrinter(info, protocol);
                if (printer != null) {
                    hits.add(printer);
                }
            }
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "mDNS browse failed on %s for %s: %s"
                    .formatted(address.getHostAddress(), protocol.serviceType(), e.getMessage()));
        }
        return hits;
    }

    /**
     * Every IPv4 address that could actually carry mDNS.
     *
     * <p>Emphatically not {@link InetAddress#getLocalHost()}, which is what this
     * used to do. That resolves the machine's hostname, and on macOS the hostname
     * commonly maps to 127.0.0.1 — so JmDNS bound to loopback and heard nothing,
     * while the OS's own {@code dns-sd} found the printer on every service type.
     * Discovery silently returned empty on a network with a printer sitting on it.
     *
     * <p>Enumerating also handles the multi-homed case honestly: a host can have
     * several usable interfaces (this one has Wi-Fi plus a virtual bridge) and the
     * printer is only reachable from one of them. Browsing all of them costs a
     * socket each and removes the guess.
     */
    public static List<InetAddress> multicastAddresses() {
        var addresses = new ArrayList<InetAddress>();
        try {
            for (var ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) {
                    continue;
                }
                for (var ia : ni.getInterfaceAddresses()) {
                    // IPv4 only: JmDNS binds one family per instance, and printers
                    // that publish AAAA also publish A.
                    if (ia.getAddress() instanceof Inet4Address v4) {
                        addresses.add(v4);
                    }
                }
            }
        } catch (SocketException e) {
            EventLogger.warn(CATEGORY, "Could not enumerate network interfaces: " + e.getMessage());
        }
        return addresses;
    }

    /** Map one JmDNS record to a printer, or null when it carries no usable address. */
    private static DiscoveredPrinter toPrinter(ServiceInfo info, PrintProtocol protocol) {
        var addresses = info.getHostAddresses();
        if (addresses == null || addresses.length == 0) {
            // Advertised but unresolvable — nothing to print to.
            return null;
        }
        var capabilities = new LinkedHashMap<String, String>();
        var keys = info.getPropertyNames();
        while (keys.hasMoreElements()) {
            var key = keys.nextElement();
            var value = info.getPropertyString(key);
            if (value != null) {
                capabilities.put(key, value);
            }
        }
        var port = info.getPort() > 0 ? info.getPort() : protocol.defaultPort();
        return new DiscoveredPrinter(info.getName(), addresses[0], port, protocol, capabilities);
    }

    /**
     * A printer addressed directly by host rather than discovered, for the case
     * where mDNS is blocked but the address is known. Capabilities are empty
     * because nothing advertised them.
     */
    public static DiscoveredPrinter direct(String host, Integer port, PrintProtocol protocol) {
        var resolved = protocol == null ? PrintProtocol.IPP : protocol;
        return new DiscoveredPrinter(host, host,
                port == null || port <= 0 ? resolved.defaultPort() : port,
                resolved, Map.of());
    }

    /**
     * How long to wait for a saved printer to answer. Short because this runs on a
     * settings page load, where a slow probe reads as a hung page.
     */
    static final int REACHABILITY_TIMEOUT_MS = 2_500;

    /**
     * Does anything answer at {@code host:port}? A saved default outlives the DHCP
     * lease it was saved under — this one answered on .60 for a week and then moved
     * to .51, which surfaced as a print that timed out rather than as a stale
     * address. Deliberately a bare TCP connect: it is protocol-agnostic, so RAW and
     * LPD defaults get the same answer as IPP, and it cannot itself queue a job.
     */
    public static boolean reachable(String host, int port) {
        if (host == null || host.isBlank() || port <= 0) {
            return false;
        }
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), REACHABILITY_TIMEOUT_MS);
            return true;
        } catch (IOException | IllegalArgumentException | SecurityException _) {
            return false;
        }
    }

    /** Discovered printers whose name or host matches {@code query}, case-insensitively. */
    public static List<DiscoveredPrinter> matching(List<DiscoveredPrinter> printers, String query) {
        if (query == null || query.isBlank()) {
            return printers;
        }
        var needle = query.trim().toLowerCase();
        var hits = new ArrayList<DiscoveredPrinter>();
        for (var p : printers) {
            if (p.name().toLowerCase().contains(needle) || p.host().toLowerCase().contains(needle)) {
                hits.add(p);
            }
        }
        return hits;
    }
}
