import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.printing.DiscoveredPrinter;
import services.printing.PrintProtocol;
import services.printing.PrintTargetGuard;
import services.printing.PrinterDefaults;

import java.util.List;
import java.util.Map;

/**
 * {@link PrintTargetGuard} decides whether a destination the model named may be
 * dialled (JCLAW-1229). Its three verdicts are pinned here rather than through
 * {@code PrinterTool.dangerous}, because {@code classify} takes the discovered list
 * as a parameter and the tool browses mDNS for it — only this level can assert what
 * a discovered printer does to the answer without a network.
 */
class PrintTargetGuardTest extends UnitTest {

    private static final PrinterDefaults.Defaults NO_DEFAULT = PrinterDefaults.NONE;

    private static DiscoveredPrinter printerAt(String host, int port) {
        return new DiscoveredPrinter("Office laser", host, port, PrintProtocol.IPP, Map.of());
    }

    private static PrinterDefaults.Defaults savedAt(String host, int port) {
        return new PrinterDefaults.Defaults("Saved", host, port, "IPP", Map.of());
    }

    @Test
    void theCloudMetadataAddressIsRefusedOutright() {
        assertEquals(PrintTargetGuard.Verdict.REFUSED,
                PrintTargetGuard.classify("169.254.169.254", 631, NO_DEFAULT, List.of()));
    }

    @Test
    void aHostNobodyChoseIsUnvetted() {
        // TEST-NET-1 on the IPP port: routable-looking and plausibly a printer, but
        // neither saved nor announced. That decision belongs to the operator.
        assertEquals(PrintTargetGuard.Verdict.UNVETTED,
                PrintTargetGuard.classify("192.0.2.10", 631, NO_DEFAULT, List.of()));
    }

    @Test
    void aDiscoveredPrinterOnANonStandardPortIsAllowed() {
        // The regression this test exists for: an mDNS announcement chose the port, so
        // screening it against the well-known print ports only cost an approval prompt
        // for a printer that had in fact been vetted.
        assertEquals(PrintTargetGuard.Verdict.ALLOWED,
                PrintTargetGuard.classify("192.0.2.10", 8631, NO_DEFAULT,
                        List.of(printerAt("192.0.2.10", 8631))));
    }

    @Test
    void aSavedDefaultOnANonStandardPortIsAllowed() {
        assertEquals(PrintTargetGuard.Verdict.ALLOWED,
                PrintTargetGuard.classify("192.0.2.10", 8631, savedAt("192.0.2.10", 8631), List.of()));
    }

    @Test
    void aPortNobodyChoseIsStillUnvettedOnAChosenHost() {
        // 6379 is Redis — the shape the finding named, a service reachable as a blind
        // byte sink. Dropping the print-port screen did not open this: the host matches
        // a discovered printer, the port matches nothing, so no ALLOWED path is reached.
        assertEquals(PrintTargetGuard.Verdict.UNVETTED,
                PrintTargetGuard.classify("192.0.2.10", 6379, savedAt("192.0.2.10", 631),
                        List.of(printerAt("192.0.2.10", 631))));
    }

    @Test
    void aMissingHostIsUnvettedRatherThanAllowed() {
        assertEquals(PrintTargetGuard.Verdict.UNVETTED,
                PrintTargetGuard.classify(null, 631, NO_DEFAULT, List.of()));
        assertEquals(PrintTargetGuard.Verdict.UNVETTED,
                PrintTargetGuard.classify("  ", 631, NO_DEFAULT, List.of()));
    }

    @Test
    void aForbiddenRangeBeatsAMatchingDiscovery() {
        // Order matters: REFUSED is checked before either ALLOWED path, so a link-local
        // address cannot be laundered into ALLOWED by appearing in the browse results.
        assertEquals(PrintTargetGuard.Verdict.REFUSED,
                PrintTargetGuard.classify("169.254.169.254", 631,
                        savedAt("169.254.169.254", 631),
                        List.of(printerAt("169.254.169.254", 631))));
    }
}
