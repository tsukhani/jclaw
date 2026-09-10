import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Guards the one way this suite can lie: silently running nothing (JCLAW-1176).
 *
 * <p>The play1 fork's runner discovers only classes extending {@link UnitTest} or
 * {@code FunctionalTest}. A plain JUnit class under {@code test/} compiles, is never
 * executed, and the suite still reports green — so the failure mode is an absence, which
 * no assertion inside that class can ever report. {@code MultiImageVideoAdapterTest} sat
 * that way with two live test methods until this check found it.
 */
class TestDiscoveryConformanceTest extends UnitTest {

    /** A top-level declaration: nested classes are indented, so anchoring at column 0 skips them. */
    private static final Pattern TOP_LEVEL_CLASS =
            Pattern.compile("^(?:public\\s+|final\\s+|abstract\\s+)*class\\s+\\w+([^{]*)\\{", Pattern.MULTILINE);

    /** What the runner looks for. An intermediate base class would need this widened — it fails
     *  loudly rather than silently, which is the whole point of the check. */
    private static final Pattern DISCOVERABLE =
            Pattern.compile("extends\\s+(play\\.test\\.)?(UnitTest|FunctionalTest)\\b");

    private static final Pattern CARRIES_TESTS =
            Pattern.compile("@(Test|ParameterizedTest|RepeatedTest|Property|TestFactory)\\b");

    /**
     * Whether {@code source} declares tests the runner would never reach. Pure, so the
     * offending shape can be exercised without committing a class that breaks the build.
     */
    static boolean declaresUnreachableTests(String source) {
        var stripped = ResourceLeakGateConformanceTest.withoutComments(source);
        if (!CARRIES_TESTS.matcher(stripped).find()) {
            return false;   // a helper such as LuceneTestSync or AuthFixture, not a test class
        }
        var declaration = TOP_LEVEL_CLASS.matcher(stripped);
        if (!declaration.find()) {
            return false;
        }
        return !DISCOVERABLE.matcher(declaration.group(1)).find();
    }

    @Test
    void everyTestClassIsReachableByTheRunner() throws IOException {
        var offenders = new ArrayList<String>();
        var examined = 0;
        try (Stream<Path> tree = Files.walk(Path.of(Play.applicationPath.getAbsolutePath(), "test"))) {
            for (var file : tree.filter(f -> f.toString().endsWith(".java")).toList()) {
                examined++;
                if (declaresUnreachableTests(Files.readString(file))) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        // A scan that silently matched nothing would report the same empty list as a clean tree.
        assertTrue(examined > 400, "the scan reached only " + examined + " files — it is not measuring the suite");
        assertTrue(offenders.isEmpty(),
                "these declare tests the runner never reaches; extend UnitTest or FunctionalTest: " + offenders);
    }

    @Test
    void thePredicateNamesTheShapeItIsLookingFor() {
        assertTrue(declaresUnreachableTests("""
                import org.junit.jupiter.api.Test;
                class Orphan {
                    @Test void doesNothing() {}
                }"""), "a plain JUnit class is exactly what never runs");
        assertFalse(declaresUnreachableTests("""
                class Fine extends UnitTest {
                    @Test void runs() {}
                }"""));
        assertFalse(declaresUnreachableTests("""
                class AlsoFine extends play.test.FunctionalTest {
                    @Test void runs() {}
                }"""));
        assertFalse(declaresUnreachableTests("""
                class Helper {
                    static void acquire() {}
                }"""), "a support class carries no tests and is not the runner's business");
        assertFalse(declaresUnreachableTests("""
                // class Commented { @Test void x() {} }
                class Real extends UnitTest {}"""), "a commented-out class is not a declaration");
    }
}
