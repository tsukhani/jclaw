import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.printing.DiscoveredPrinter;
import services.printing.JobAttributes;
import services.printing.LpdClient;
import services.printing.PrintProtocol;
import services.printing.PrinterDiscovery;
import services.printing.RawSocketClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The two print backends that can be exercised without a printer (JCLAW-911).
 *
 * <p>Both run against a loopback {@link ServerSocket} on an ephemeral port, so
 * these are real protocol round-trips rather than mocks — the LPD test asserts
 * the exact RFC 1179 byte sequence a daemon would receive. What they cannot
 * cover is a real printer's interpretation of those bytes; see the IPP path,
 * which has no equivalent test for exactly that reason.
 */
class PrintBackendTest extends UnitTest {

    /** What a fake daemon received: the two command lines and the two payloads. */
    private record LpdExchange(String queueCommand, String controlHeader, String control,
                               String dataHeader, byte[] data) {}

    /**
     * A minimal but real RFC 1179 receiver.
     *
     * <p>Reads by the protocol's own framing — line, then exactly the byte count the
     * header declared — rather than draining whatever happens to be buffered. An
     * earlier version of this helper acked up front and read on {@code available()},
     * which passed or failed depending on how the client's writes coalesced; that is
     * a flaky harness, and a flaky harness is worse than no test. Reading by length
     * also makes the declared counts part of what is asserted.
     */
    private static CompletableFuture<LpdExchange> lpdDaemon(ServerSocket server) {
        return CompletableFuture.supplyAsync(() -> {
            try (var socket = server.accept()) {
                var in = socket.getInputStream();
                var out = socket.getOutputStream();

                var queueCommand = readLine(in);
                ack(out);

                var controlHeader = readLine(in);
                ack(out);
                var control = new String(readExactly(in, payloadLength(controlHeader)),
                        StandardCharsets.US_ASCII);
                readExactly(in, 1); // trailing NUL
                ack(out);

                var dataHeader = readLine(in);
                ack(out);
                var data = readExactly(in, payloadLength(dataHeader));
                readExactly(in, 1); // trailing NUL
                ack(out);

                return new LpdExchange(queueCommand, controlHeader, control, dataHeader, data);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void ack(java.io.OutputStream out) throws IOException {
        out.write(0);
        out.flush();
    }

    /** Byte count from a {@code \002<len> SP <name>} header. */
    private static int payloadLength(String header) {
        // Skip the leading subcommand byte, take up to the space.
        var body = header.substring(1);
        return Integer.parseInt(body.substring(0, body.indexOf(' ')));
    }

    private static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            sb.append((char) c);
        }
        return sb.toString();
    }

    /** Block until exactly {@code n} bytes have arrived — no partial reads. */
    private static byte[] readExactly(InputStream in, int n) throws IOException {
        var buf = new byte[n];
        int read = 0;
        while (read < n) {
            int got = in.read(buf, read, n - read);
            if (got == -1) {
                throw new IOException("stream ended after " + read + " of " + n + " bytes");
            }
            read += got;
        }
        return buf;
    }

    @Test
    void lpdSendsTheRfc1179Sequence() throws Exception {
        try (var server = new ServerSocket(0)) {
            var daemon = lpdDaemon(server);
            var document = "PDFBYTES".getBytes(StandardCharsets.UTF_8);
            LpdClient.print("127.0.0.1", server.getLocalPort(), "lp", "invoice.pdf",
                    "tester", document, 5000);

            var seen = daemon.get(10, TimeUnit.SECONDS);

            // \002 + queue is "receive a printer job".
            assertEquals("\002lp", seen.queueCommand());
            // Control file is subcommand \002, data file \003.
            assertTrue(seen.controlHeader().startsWith("\002"), seen.controlHeader());
            assertTrue(seen.dataHeader().startsWith("\003"), seen.dataHeader());
            assertTrue(seen.controlHeader().contains("cfA"),
                    "control file name should use the cfA<id> convention: " + seen.controlHeader());
            assertTrue(seen.dataHeader().contains("dfA"),
                    "data file name should use the dfA<id> convention: " + seen.dataHeader());

            // The daemon read both payloads using ONLY the declared byte counts, so
            // arriving intact proves the length headers were correct — a wrong count
            // desynchronises the stream and fails in readExactly.
            assertArrayEquals(document, seen.data());
            assertTrue(seen.control().contains("Ptester"), seen.control());
            assertTrue(seen.control().contains("Jinvoice.pdf"), seen.control());
        }
    }

    @Test
    void lpdControlFileUsesRawPrintNotFormattedText() {
        var control = new String(
                LpdClient.buildControlFile("host", "user", "report.pdf", "dfA001host"),
                StandardCharsets.US_ASCII);

        // 'l' prints the file verbatim; 'f' would make the daemon treat a PDF as
        // ASCII and paginate it into hundreds of pages of garbage.
        assertTrue(control.contains("\nldfA001host\n") || control.startsWith("ldfA001host\n")
                        || control.contains("ldfA001host"),
                "control file must request raw ('l') printing, got: " + control);
        assertFalse(control.contains("\nfdfA001host"), "must not request formatted ('f') printing");
        assertTrue(control.contains("Hhost"), "control file records the originating host");
        assertTrue(control.contains("Puser"), "control file records the submitting user");
    }

    @Test
    void lpdRejectsControlFileInjectionViaJobName() {
        // Control-file records are newline-delimited, so a newline in a job name
        // would inject an extra record the daemon reads as a command. Job names
        // come from model-supplied arguments, so this is reachable.
        var evil = LpdClient.safeToken("job\nUdfA000evil\nHattacker");
        assertFalse(evil.contains("\n"), "newlines must not survive into a control-file value");

        var control = new String(
                LpdClient.buildControlFile("h", "u", "job\nHattacker", "dfA001h"),
                StandardCharsets.US_ASCII);

        // The invariant is about RECORDS, not substrings. Sanitising turns
        // "job\nHattacker" into "job_Hattacker", so the text "Hattacker" still
        // appears — harmlessly, inside the J record. What must not happen is a
        // second line STARTING with H, which is what the daemon would parse as a
        // host record. Asserting on the substring instead would fail on correct code.
        var hostRecords = control.lines().filter(l -> l.startsWith("H")).toList();
        assertEquals(List.of("Hh"), hostRecords,
                "exactly one H record, and not the injected one: " + control);
        assertFalse(control.lines().anyMatch(l -> l.isEmpty()),
                "a blank record would mean a stray newline survived: " + control);
    }

    @Test
    void lpdSurfacesADaemonRefusal() throws Exception {
        try (var server = new ServerSocket(0)) {
            // Non-zero first ack = refusal. Reporting this as success is how a job
            // silently vanishes, so it must throw.
            CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    socket.getOutputStream().write(2);
                    socket.getOutputStream().flush();
                } catch (IOException _) {
                    // Test server; the assertion is on the client side.
                }
            });
            var port = server.getLocalPort();
            var boom = assertThrows(IOException.class, () ->
                    LpdClient.print("127.0.0.1", port, "lp", "j", "u",
                            "x".getBytes(StandardCharsets.UTF_8), 5000));
            assertTrue(boom.getMessage().contains("refused"), boom.getMessage());
        }
    }

    @Test
    void rawSocketStreamsTheDocumentVerbatim() throws Exception {
        try (var server = new ServerSocket(0)) {
            var received = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept()) {
                    return socket.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

            var payload = "%PDF-1.7 raw stream".getBytes(StandardCharsets.UTF_8);
            RawSocketClient.print("127.0.0.1", server.getLocalPort(), payload, 5000);

            // Raw socket adds no framing at all — byte-for-byte, or the printer
            // gets a corrupt document.
            assertArrayEquals(payload, received.get(10, TimeUnit.SECONDS));
        }
    }

    // ─── Addressing ───

    @Test
    void ippUriHonoursTheAdvertisedResourcePath() {
        var withRp = new DiscoveredPrinter("HP", "10.0.0.5", 631,
                PrintProtocol.IPP, Map.of("rp", "ipp/print"));
        // TXT records omit the leading slash by convention; the URI needs it.
        assertEquals("ipp://10.0.0.5:631/ipp/print", withRp.ippUri());

        var noRp = new DiscoveredPrinter("HP", "10.0.0.5", 631, PrintProtocol.IPP, Map.of());
        assertEquals("ipp://10.0.0.5:631/ipp/print", noRp.ippUri());

        var secure = new DiscoveredPrinter("HP", "10.0.0.5", 631,
                PrintProtocol.IPPS, Map.of("rp", "/secure/print"));
        assertEquals("ipps://10.0.0.5:631/secure/print", secure.ippUri());
    }

    @Test
    void protocolsMapToTheirDnsSdServiceTypes() {
        assertEquals(PrintProtocol.IPP, PrintProtocol.fromServiceType("_ipp._tcp.local."));
        assertEquals(PrintProtocol.IPPS, PrintProtocol.fromServiceType("_ipps._tcp.local."));
        assertEquals(PrintProtocol.RAW, PrintProtocol.fromServiceType("_pdl-datastream._tcp.local."));
        assertEquals(PrintProtocol.LPD, PrintProtocol.fromServiceType("_printer._tcp.local."));
        assertNull(PrintProtocol.fromServiceType("_http._tcp.local."));

        // All four AC-listed service types are covered — that list is the contract.
        assertEquals(4, PrintProtocol.values().length);
    }

    @Test
    void directAddressingFallsBackToTheProtocolDefaultPort() {
        assertEquals(631, PrinterDiscovery.direct("printer.local", null, PrintProtocol.IPP).port());
        assertEquals(9100, PrinterDiscovery.direct("printer.local", null, PrintProtocol.RAW).port());
        assertEquals(515, PrinterDiscovery.direct("printer.local", null, PrintProtocol.LPD).port());
        assertEquals(1234, PrinterDiscovery.direct("printer.local", 1234, PrintProtocol.IPP).port());
        // No protocol given → IPP, the only backend that can report back.
        assertEquals(PrintProtocol.IPP, PrinterDiscovery.direct("p", null, null).protocol());
    }

    @Test
    void reachabilityDistinguishesAListeningPortFromAClosedOne() throws Exception {
        int port;
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = server.getLocalPort();
            assertTrue(PrinterDiscovery.reachable("127.0.0.1", port),
                    "a listening socket must read as reachable");
        }
        // Same port, now closed — without this half the check could return true for
        // everything and still pass, which is what a stale-default badge must never do.
        assertFalse(PrinterDiscovery.reachable("127.0.0.1", port),
                "a closed port must read as unreachable");
    }

    @Test
    void reachabilityRejectsAnUnusableAddressWithoutWaiting() {
        // Blank host and non-port answer immediately rather than burning the timeout
        // on a settings page load.
        assertFalse(PrinterDiscovery.reachable(null, 631));
        assertFalse(PrinterDiscovery.reachable("", 631));
        assertFalse(PrinterDiscovery.reachable("127.0.0.1", 0));
    }

    // ─── Job attributes ───

    @Test
    void jobAttributesValidateAgainstTheRfcKeywordSets() {
        assertNull(new JobAttributes("two-sided-long-edge", "monochrome", "iso_a4_210x297mm")
                .validationError());
        assertNull(JobAttributes.DEFAULTS.validationError());

        assertNotNull(new JobAttributes("duplex", null, null).validationError());
        assertNotNull(new JobAttributes(null, "greyscale", null).validationError());

        // media is deliberately open: its vocabulary spans PWG size names and
        // vendor tray names, so a closed list would reject valid input.
        assertNull(new JobAttributes(null, null, "vendor-tray-7").validationError());
    }

    @Test
    void aFallbackBackendReportsThatItDroppedTheAttributes() throws Exception {
        // The point of the whole droppedAttributes field. A duplex request that
        // falls back to port 9100 prints single-sided, and the operator would
        // otherwise learn that from the paper.
        try (var server = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    socket.getInputStream().readAllBytes();
                } catch (IOException _) {
                    // Test sink; the assertion is on the dispatcher's outcome.
                }
            });

            var printer = PrinterDiscovery.direct("127.0.0.1", server.getLocalPort(),
                    PrintProtocol.RAW);
            var outcome = services.printing.PrintDispatcher.print(printer, "job", "tester",
                    "application/pdf", "x".getBytes(StandardCharsets.UTF_8),
                    new JobAttributes("two-sided-long-edge", "monochrome", null));

            assertEquals(PrintProtocol.RAW, outcome.protocol());
            assertFalse(outcome.verified(), "raw socket cannot confirm anything");
            assertNotNull(outcome.droppedAttributes(),
                    "a raw-socket job must report that the attributes were not applied");
            assertTrue(outcome.droppedAttributes().contains("two-sided-long-edge"),
                    outcome.droppedAttributes());
        }
    }

    @Test
    void aFallbackWithNoAttributesRequestedReportsNothingDropped() throws Exception {
        try (var server = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    socket.getInputStream().readAllBytes();
                } catch (IOException _) {
                    // Test sink.
                }
            });

            var printer = PrinterDiscovery.direct("127.0.0.1", server.getLocalPort(),
                    PrintProtocol.RAW);
            var outcome = services.printing.PrintDispatcher.print(printer, "job", "tester",
                    "application/pdf", "x".getBytes(StandardCharsets.UTF_8),
                    JobAttributes.DEFAULTS);

            // Nothing was asked for, so nothing was lost — warning here would be noise
            // that trains the operator to ignore the real one.
            assertNull(outcome.droppedAttributes());
        }
    }

    @Test
    void oneDeviceIsOneRowAtItsMostCapableProtocol() {
        // A printer advertises several service types on different ports — the
        // Canon answers on 631, 9100 and 515 — and each browse returns it
        // separately. Keying the merge by port showed the operator the same
        // device three times, each row a different protocol, with no indication
        // which to choose.
        var ipp = new DiscoveredPrinter("Canon", "192.168.68.60", 631,
                PrintProtocol.IPP, Map.of());
        var raw = new DiscoveredPrinter("Canon", "192.168.68.60", 9100,
                PrintProtocol.RAW, Map.of());
        var lpd = new DiscoveredPrinter("Canon", "192.168.68.60", 515,
                PrintProtocol.LPD, Map.of());

        // PrintProtocol is declared in capability order, so the ordinal is the
        // ranking — IPP is the only backend that can report a job id back.
        assertTrue(ipp.protocol().ordinal() < raw.protocol().ordinal());
        assertTrue(raw.protocol().ordinal() < lpd.protocol().ordinal());

        // Whichever browse returns first, the surviving row must be the IPP one:
        // the browses run in parallel, so arrival order is not deterministic and
        // a first-wins merge would pick differently between scans.
        for (var order : List.of(List.of(ipp, raw, lpd), List.of(lpd, raw, ipp),
                List.of(raw, lpd, ipp))) {
            var merged = new java.util.LinkedHashMap<String, DiscoveredPrinter>();
            for (var p : order) {
                merged.merge(p.host(), p,
                        (a, b) -> a.protocol().ordinal() <= b.protocol().ordinal() ? a : b);
            }
            assertEquals(1, merged.size(), "one device is one row");
            assertEquals(PrintProtocol.IPP, merged.values().iterator().next().protocol(),
                    "arrival order " + order.stream().map(p -> p.protocol().name()).toList());
        }
    }

    @Test
    void discoveryBindsToRealInterfacesNotLoopback() {
        var addresses = PrinterDiscovery.multicastAddresses();

        // The bug this pins, found only against real hardware: discovery used
        // InetAddress.getLocalHost(), which on macOS resolves the hostname to
        // 127.0.0.1. JmDNS bound to loopback and heard nothing, while the OS's own
        // dns-sd saw the printer on all four service types. Discovery returned an
        // empty list on a network with a printer sitting on it — indistinguishable
        // from "no printers", which is why no test caught it.
        for (var a : addresses) {
            assertFalse(a.isLoopbackAddress(),
                    "loopback can never carry mDNS to a printer: " + a);
            assertFalse(a.isAnyLocalAddress(), "wildcard is not a browsable interface: " + a);
        }
        // CI may legitimately have none; what must not happen is loopback being
        // offered as though it were usable.
        assertNotNull(addresses);
    }

    @Test
    void discoveryDegradesToEmptyRatherThanThrowing() {
        // CI and containers routinely have no multicast route. "No printers found"
        // is the honest answer there; a stack trace would suggest a bug.
        assertNotNull(PrinterDiscovery.discover(java.time.Duration.ofMillis(50)));
    }

    @Test
    void discoveryDoesNotWaitForJmdnsTeardown() {
        // JmDNS close() costs ~2s regardless of the browse window — measured 2008ms for a
        // bare create-then-close (JCLAW-1254) — so a blocking close would put this call at
        // 2s+ however short the timeout. Detached, it tracks the window instead. The bound
        // is deliberately loose: the gap being guarded is 50ms vs 2000ms.
        var start = System.nanoTime();
        PrinterDiscovery.discover(java.time.Duration.ofMillis(50));
        var elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 1_000,
                () -> "discover() took " + elapsedMs + "ms for a 50ms browse — close() is being "
                        + "waited on again");
    }
}
