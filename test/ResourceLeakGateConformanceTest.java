import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The build-time resource-leak gate's own guard rail (JCLAW-1156).
 *
 * <p>Error Prone's {@code MustBeClosedChecker} runs on the Gradle {@code compileJava} and
 * {@code compileTestJava}, neither of which {@code play autotest} invokes — so nothing in
 * the suite would notice the gate being switched off, downgraded to a warning, or left
 * behind by a newly added {@code AutoCloseable}. These assertions read
 * {@code build.gradle.kts} and the sources directly, which is the only way to make those
 * regressions fail a test run rather than a later push.
 */
class ResourceLeakGateConformanceTest extends UnitTest {

    /** A class (not an interface — its implementations carry the annotation) that owns a resource. */
    private static final Pattern AUTOCLOSEABLE_CLASS = Pattern.compile(
            "\\bclass\\s+\\w+[^{;]*\\bimplements\\b[^{;]*\\bAutoCloseable\\b");

    private static Path repo(String... parts) {
        var path = Path.of(Play.applicationPath.getAbsolutePath());
        for (var part : parts) {
            path = path.resolve(part);
        }
        return path;
    }

    private static String buildScript() throws IOException {
        return Files.readString(repo("build.gradle.kts"));
    }

    @Test
    void theCheckerIsWiredAtErrorSeverityOnBothCompiles() throws Exception {
        var script = buildScript();
        assertTrue(script.contains("com.google.errorprone:error_prone_annotations:"),
                "@MustBeClosed itself must be a compile dependency, not only a tool one");
        assertTrue(script.contains("check(\"MustBeClosed\", CheckSeverity.ERROR)"),
                "MustBeClosed must fail the build, not warn — a warning nobody reads is not a gate");

        var block = script.indexOf("tasks.named<JavaCompile>(\"compileTestJava\")");
        assertTrue(block >= 0, "compileTestJava carries its own Error Prone block");
        assertTrue(script.indexOf("check(\"MustBeClosed\", CheckSeverity.ERROR)", block) >= 0,
                "test/ enforcement is what reaches LuceneTestSync's lock; app/-only cannot");
    }

    @Test
    void everyAutoCloseableClassInAppDeclaresMustBeClosed() throws Exception {
        var offenders = new ArrayList<String>();
        var matched = 0;
        try (Stream<Path> tree = Files.walk(repo("app"))) {
            for (var file : tree.filter(f -> f.toString().endsWith(".java")).toList()) {
                var source = Files.readString(file);
                if (!AUTOCLOSEABLE_CLASS.matcher(source).find()) continue;
                matched++;
                // A constructor a caller can leak is the whole failure mode; the annotation
                // is what turns leaking it into a compile error rather than a stray handle.
                if (!source.contains("@MustBeClosed")) {
                    offenders.add(repo().relativize(file).toString());
                }
            }
        }
        assertEquals(List.of(), offenders,
                "AutoCloseable types in app/ whose factory carries no @MustBeClosed");
        // A regex that silently stops matching would pass this test on an empty set.
        assertTrue(matched >= 4, "the scan must still find the known AutoCloseable types, saw " + matched);
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
                if (file.getFileName().toString().equals("LuceneTestSync.java")) continue;
                var source = Files.readString(file);
                if (!source.contains("LuceneTestSync.openForTest(")
                        && !source.contains("LuceneTestSync.closedForTest(")) continue;
                acquirers++;
                if (!source.contains("LuceneTestSync.release(")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), offenders,
                "test classes that take the Lucene lock without ever releasing it");
        assertTrue(acquirers >= 20, "the scan must still see the hook-pair callers, saw " + acquirers);
    }
}
