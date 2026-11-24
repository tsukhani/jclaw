import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * install.ps1 is published as a file, and Windows PowerShell 5.1 reads a .ps1 without a byte-order mark as
 * Windows-1252. There UTF-8's em-dash ends in a byte PowerShell takes for a closing double quote, so a saved copy
 * failed to parse (JF-585). Plain ASCII reads the same under every decoding.
 */
class InstallerPowerShellEncodingTest extends UnitTest {

    @Test
    void installPs1IsPlainAsciiSoWindowsPowerShellReadsASavedCopyAsWritten() throws IOException {
        var script = Path.of(Play.applicationPath.getAbsolutePath(), "install.ps1");
        var lines = Files.readAllLines(script, StandardCharsets.UTF_8);
        var nonAscii = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(lines.get(i))) {
                nonAscii.add((i + 1) + ": " + lines.get(i).strip());
            }
        }
        assertEquals(List.of(), nonAscii);
    }
}
