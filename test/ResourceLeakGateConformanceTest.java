import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.Source;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The build-time resource-leak gate's own guard rail (JCLAW-1156).
 *
 * <p>Error Prone's {@code MustBeClosedChecker} runs on the Gradle {@code compileJava} and
 * {@code compileTestJava}. {@code play autotest} depends on the first, so a leak in {@code app/}
 * does fail the suite — but nothing in it would notice the gate being switched off, downgraded
 * to a warning, or left behind by a newly added {@code AutoCloseable}. These assertions read
 * {@code build.gradle.kts} and the sources directly, which is the only way to make those
 * regressions fail a test run rather than a later push.
 */
class ResourceLeakGateConformanceTest extends UnitTest {

    private static final String MUST_BE_CLOSED_AT_ERROR = "check(\"MustBeClosed\", CheckSeverity.ERROR)";
    private static final String TEST_COMPILE_BLOCK = "tasks.named<JavaCompile>(\"compileTestJava\")";

    /** A release inside a teardown hook or a finally block — one that runs whether or not the body finished. */
    private static final Pattern RELEASE_IN_TEARDOWN = Pattern.compile(
            "@After(?:Each|All)\\b(?:(?!@Test)[\\s\\S])*?LuceneTestSync\\.release\\("
                    + "|finally\\s*\\{[^}]*LuceneTestSync\\.release\\(");

    private static Path repo(String... parts) {
        var path = Path.of(Play.applicationPath.getAbsolutePath());
        for (var part : parts) {
            path = path.resolve(part);
        }
        return path;
    }

    private static String buildScript() throws IOException {
        return withoutComments(Files.readString(repo("build.gradle.kts")));
    }

    /** Comments are where a required token survives after the code that needed it is gone. */
    static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test
    void theCheckerIsWiredAtErrorSeverityOnBothCompiles() throws Exception {
        var script = buildScript();
        assertTrue(script.contains("com.google.errorprone:error_prone_annotations:"),
                "@MustBeClosed itself must be a compile dependency, not only a tool one");

        int testBlock = script.indexOf(TEST_COMPILE_BLOCK);
        assertTrue(testBlock > 0, "compileTestJava carries its own Error Prone block");
        assertTrue(script.substring(0, testBlock).contains(MUST_BE_CLOSED_AT_ERROR),
                "MustBeClosed must fail the app/ compile, not warn — a warning nobody reads is not a gate");
        assertTrue(script.indexOf(MUST_BE_CLOSED_AT_ERROR, testBlock) >= 0,
                "test/ enforcement is what reaches LuceneTestSync's lock; app/-only cannot");
        // Any of these leaves every token above intact while the checker checks nothing.
        for (var off : List.of("enabled.set(false)", "excludedPaths", "disable(\"MustBeClosed\")")) {
            assertFalse(script.contains(off), off + " would switch the leak gate off silently");
        }
    }

    @Test
    void everyAutoCloseableClassInAppDeclaresMustBeClosed() throws Exception {
        var offenders = new TreeSet<String>();
        var scanned = new TreeSet<String>();
        for (JavaClass javaClass : ArchitectureTest.APP_CLASSES) {
            // Interfaces and abstract types hand out nothing, and an anonymous class has no
            // constructor to annotate — its owner's factory carries the contract instead.
            if (javaClass.isInterface() || javaClass.isAnonymousClass()
                    || javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
                    || !javaClass.isAssignableTo(AutoCloseable.class)) {
                continue;
            }
            var file = sourceFile(javaClass);
            scanned.add(file);
            // A constructor a caller can leak is the whole failure mode; the annotation is what
            // turns leaking it into a compile error rather than a stray handle.
            if (!withoutComments(Files.readString(repo(file))).contains("@MustBeClosed")) {
                offenders.add(file);
            }
        }
        assertEquals(Set.of(), offenders, "AutoCloseable types in app/ whose factory carries no @MustBeClosed");
        // A hierarchy walk that silently stopped resolving would pass this test on an empty set.
        assertTrue(scanned.size() >= 7, "the scan must still find the known AutoCloseable types, saw " + scanned);
    }

    private static String sourceFile(JavaClass javaClass) {
        var name = javaClass.getSource().flatMap(Source::getFileName)
                .orElseThrow(() -> new AssertionError("no source file recorded for " + javaClass.getName()));
        return "app/" + javaClass.getPackageName().replace('.', '/') + "/" + name;
    }

    @Test
    void everyLegacyLuceneLockAcquisitionIsPairedWithARelease() throws Exception {
        // The lease API is compiler-enforced; the ~40 classes that hold the index across
        // JUnit lifecycle hooks cannot be, because @MustBeClosed accepts only a resource
        // variable or a return, never a field. An unpaired acquire hangs every later
        // Lucene test on the global lock, which reads as a suite-wide timeout.
        var offenders = new ArrayList<String>();
        var acquirers = 0;
        try (Stream<Path> tree = Files.list(repo("test"))) {
            for (var file : tree.filter(f -> f.toString().endsWith(".java")).toList()) {
                var name = file.getFileName().toString();
                if (name.equals("LuceneTestSync.java") || name.equals("ResourceLeakGateConformanceTest.java")) {
                    continue;
                }
                var source = withoutComments(Files.readString(file));
                if (!source.contains("LuceneTestSync.openForTest(")
                        && !source.contains("LuceneTestSync.closedForTest(")) continue;
                acquirers++;
                // A release at the end of a test body is skipped by the failing assertion above
                // it — the exact starvation mode — so only a teardown hook or a finally counts.
                if (!RELEASE_IN_TEARDOWN.matcher(source).find()) {
                    offenders.add(name);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "test classes that take the Lucene lock without releasing it in a teardown hook or finally");
        assertTrue(acquirers >= 20, "the scan must still see the hook-pair callers, saw " + acquirers);
    }
}
