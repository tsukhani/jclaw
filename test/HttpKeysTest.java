import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import utils.HttpKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-774: guards the cross-file literals {@link HttpKeys} centralises. SonarQube's
 * java:S1192 counts repeats per file, so it never saw these — the same media type was
 * hand-written in a dozen files without a single flag.
 */
class HttpKeysTest extends UnitTest {

    /** Quoted forms that must come from {@link HttpKeys} instead of being retyped. */
    private static final List<String> CENTRALIZED = List.of(
            "\"application/json\"",
            "\"application/octet-stream\"",
            "\"/chat/completions\"");

    @Test
    void constantsCarryTheExactWireStrings() {
        assertEquals("application/json", HttpKeys.APPLICATION_JSON);
        assertEquals("application/octet-stream", HttpKeys.APPLICATION_OCTET_STREAM);
        assertEquals("/chat/completions", HttpKeys.CHAT_COMPLETIONS_PATH);
    }

    @Test
    void noProductionSourceHandWritesACentralizedLiteral() throws IOException {
        var appRoot = Path.of(Play.applicationPath.getAbsolutePath(), "app");
        var owner = appRoot.resolve("utils").resolve("HttpKeys.java");
        var offenders = new ArrayList<String>();
        try (var tree = Files.walk(appRoot)) {
            var sources = tree.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.equals(owner))
                    .sorted()
                    .toList();
            for (var source : sources) {
                var lines = Files.readAllLines(source);
                for (int i = 0; i < lines.size(); i++) {
                    var line = lines.get(i);
                    var stripped = line.stripLeading();
                    // A mention in prose is not a call site; only executable lines count.
                    if (stripped.startsWith("*") || stripped.startsWith("//") || stripped.startsWith("/*")) continue;
                    for (var literal : CENTRALIZED) {
                        if (line.contains(literal)) {
                            offenders.add(appRoot.relativize(source) + ":" + (i + 1) + " " + literal);
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Reference the HttpKeys constant instead of retyping: " + offenders);
    }
}
