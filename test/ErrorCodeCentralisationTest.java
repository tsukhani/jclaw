import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import utils.ApiResponses;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JCLAW-1138: error codes are wire contract, so each must have exactly one declaration.
 *
 * <h2>Why this scans source and not bytecode</h2>
 * javac inlines a {@code static final String} constant into the <em>calling</em> class's
 * constant pool (JLS 13.1). After compilation {@code ApiResponses.NOT_FOUND} and the literal
 * {@code "not_found"} are byte-for-byte identical at the call site, so ArchUnit — or any other
 * bytecode rule — physically cannot tell a centralised code from a re-typed one. The source is
 * the only place the distinction still exists, which is why this test reads {@code .java} files.
 *
 * <h2>Why the scanner is paren-balanced and string-aware</h2>
 * A naive regex stops at the first {@code )} or {@code ,}, and error messages routinely contain
 * both ("Try again, or reset it (see Settings)"). The first version of this scan, written as a
 * regex, missed {@code search_failed} entirely for exactly that reason. It is also why
 * {@link #theScannerSurvivesPunctuationInsideMessages()} exists.
 *
 * <h2>Why the scanner is tested against known-positive input</h2>
 * A guard that silently matches nothing passes forever and protects nothing. The negative
 * assertion over the real tree is only meaningful alongside
 * {@link #theScannerActuallyCatchesAViolation()}, which proves the detector fires.
 */
class ErrorCodeCentralisationTest extends UnitTest {

    /** {@code ApiResponses.error(...)} / {@code .errorAndLog(...)}, the only code-bearing calls. */
    private static final Pattern CALL =
            Pattern.compile("ApiResponses\\.(?:error|errorAndLog)\\s*\\(");

    /**
     * An error code: lowercase, snake, no spaces. Human messages are prose — they carry spaces
     * and capitals — so this separates the two argument kinds without needing to count commas.
     */
    private static final Pattern CODE_SHAPED = Pattern.compile("[a-z][a-z0-9_]*");

    private record Violation(String file, int line, String code) {
        @Override public String toString() {
            return "%s:%d passes the literal \"%s\"".formatted(file, line, code);
        }
    }

    // ── the scanner ───────────────────────────────────────────────────────────

    /**
     * End index of the argument list whose {@code (} sits at {@code open}, skipping over string
     * literals so a bracket or comma inside a message cannot terminate it early.
     */
    private static int argsEnd(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"') {
                i++;
                while (i < src.length() && src.charAt(i) != '"') i += src.charAt(i) == '\\' ? 2 : 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<Violation> scan(String file, String src) {
        var found = new ArrayList<Violation>();
        var m = CALL.matcher(src);
        while (m.find()) {
            int open = m.end() - 1;
            int close = argsEnd(src, open);
            if (close < 0) continue;
            for (int i = open; i < close; i++) {
                if (src.charAt(i) != '"') continue;
                int start = ++i;
                while (i < close && src.charAt(i) != '"') i += src.charAt(i) == '\\' ? 2 : 1;
                var literal = src.substring(start, Math.min(i, close));
                if (CODE_SHAPED.matcher(literal).matches()) {
                    int line = (int) src.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                    found.add(new Violation(file, line, literal));
                }
            }
        }
        return found;
    }

    private static Path appDir() {
        return Play.applicationPath.toPath().resolve("app");
    }

    // ── the guard ─────────────────────────────────────────────────────────────

    @Test
    void noCallSitePassesALiteralErrorCode() throws IOException {
        var violations = new ArrayList<Violation>();
        try (Stream<Path> files = Files.walk(appDir())) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    violations.addAll(scan(appDir().relativize(p).toString(), Files.readString(p)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "Error codes are wire contract and must be declared once on ApiResponses. "
                        + "Add a constant there and reference it:\n  "
                        + String.join("\n  ", violations.stream().map(Object::toString).toList()));
    }

    @Test
    void scannedTheTreeAtAllRatherThanPassingVacuously() throws IOException {
        // The guard above asserts an ABSENCE. If the walk found no files — a moved app dir, a
        // changed working directory under the test runner — it would pass having checked
        // nothing. Pin that it really read the tree and really saw the calls it filters.
        long javaFiles;
        try (Stream<Path> files = Files.walk(appDir())) {
            javaFiles = files.filter(p -> p.toString().endsWith(".java")).count();
        }
        assertTrue(javaFiles > 300, "expected to walk the whole app tree, saw " + javaFiles);

        long callSites;
        try (Stream<Path> files = Files.walk(appDir())) {
            callSites = files.filter(p -> p.toString().endsWith(".java")).mapToLong(p -> {
                try {
                    var m = CALL.matcher(Files.readString(p));
                    long n = 0;
                    while (m.find()) n++;
                    return n;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).sum();
        }
        assertTrue(callSites > 150,
                "expected to find the ApiResponses error call sites, saw " + callSites);
    }

    // ── the scanner's own tests ───────────────────────────────────────────────

    @Test
    void theScannerActuallyCatchesAViolation() {
        var offending = """
                class X {
                    void go() {
                        ApiResponses.error(404, "not_found", "No such thing.");
                    }
                }""";
        var found = scan("X.java", offending);

        assertEquals(1, found.size(), "the detector must fire on a known-bad sample: " + found);
        assertEquals("not_found", found.getFirst().code());
        assertEquals(3, found.getFirst().line(), "the report must point at the call site");
    }

    @Test
    void theScannerAcceptsAConstantReference() {
        var clean = """
                class X {
                    void go() {
                        ApiResponses.error(404, ApiResponses.NOT_FOUND, "No such thing.");
                    }
                }""";
        assertTrue(scan("X.java", clean).isEmpty(), "a constant reference is the fixed form");
    }

    @Test
    void theScannerSurvivesPunctuationInsideMessages() {
        // A message with a comma and brackets. A regex that stops at the first ')' or ','
        // truncates the argument list here and silently misses the literal that follows —
        // the exact bug that hid search_failed from the first version of this scan.
        var tricky = """
                class X {
                    void go() {
                        ApiResponses.error(500, "io_error", "Failed (badly), then again.");
                    }
                }""";
        var found = scan("X.java", tricky);

        assertEquals(1, found.size(), "punctuation in the message must not end the arg scan");
        assertEquals("io_error", found.getFirst().code());
    }

    @Test
    void theScannerDoesNotFlagProseMessages() {
        // Messages carry spaces and capitals; codes do not. Nothing here should be reported.
        var prose = """
                class X {
                    void go() {
                        ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Check the fields.");
                        ApiResponses.error(409, ApiResponses.CONFLICT, "already");
                    }
                }""";
        var found = scan("X.java", prose);

        // "already" IS code-shaped, so it is reported — deliberately. A one-word lowercase
        // message is indistinguishable from a code, and erring toward a false positive on a
        // human message is the safe direction: the fix is to reword it or use a constant.
        assertEquals(1, found.size(), found.toString());
        assertEquals("already", found.getFirst().code());
    }

    @Test
    void theScannerIgnoresCallsToOtherMethods() {
        var other = """
                class X {
                    void go() {
                        EventLogger.warn("auth", "some_event");
                        ApiResponses.ok("deleted", 3);
                    }
                }""";
        assertTrue(scan("X.java", other).isEmpty(),
                "only ApiResponses.error/errorAndLog carry a code argument");
    }

    // ── one declaration per code ──────────────────────────────────────────────

    @Test
    void sharedCodesAreDeclaredExactlyOnce() throws IOException {
        // operator_only had five private copies across controllers and app_scope one, which is
        // the drift ApiResponses' constants exist to prevent (java:S1192).
        for (var code : new String[] {ApiResponses.OPERATOR_ONLY, ApiResponses.APP_SCOPE}) {
            var declarations = new ArrayList<String>();
            try (Stream<Path> files = Files.walk(appDir())) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        var src = Files.readString(p);
                        // A DECLARATION, not a mention: ErrorTemplates legitimately names every
                        // code, and the first version of this check flagged it for that alone.
                        var declared = Pattern.compile("String\\s+\\w+\\s*=\\s*\"" + code + "\"");
                        if (declared.matcher(src).find() && !p.endsWith("ApiResponses.java")) {
                            declarations.add(appDir().relativize(p).toString());
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            assertTrue(declarations.isEmpty(),
                    "\"" + code + "\" must be declared only on ApiResponses; also declared in "
                            + declarations);
        }
    }
}
