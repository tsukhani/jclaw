import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The build-time nullness gate's own guard rail (JCLAW-1149).
 *
 * <p>NullAway runs on the Gradle {@code compileJava}, which {@code play autotest} never
 * invokes — so nothing in the suite would notice the gate being switched off, downgraded
 * to a warning, or left behind by a new subpackage. These assertions read
 * {@code build.gradle.kts} and the {@code package-info.java} files directly, which is the
 * only way to make those three regressions fail a test run rather than a later push.
 */
class NullnessGateConformanceTest extends UnitTest {

    private static final Pattern ANNOTATED_PACKAGES = Pattern.compile(
            "option\\(\"NullAway:AnnotatedPackages\",\\s*\"([^\"]*)\"\\)");

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

    private static List<String> annotatedPackages() throws IOException {
        var matcher = ANNOTATED_PACKAGES.matcher(buildScript());
        assertTrue(matcher.find(), "build.gradle.kts declares NullAway:AnnotatedPackages");
        return Arrays.stream(matcher.group(1).split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Test
    void theCheckerIsWiredAtErrorSeverity() throws Exception {
        var script = buildScript();
        assertTrue(script.contains("id(\"net.ltgt.errorprone\")"), "Error Prone plugin is applied");
        assertTrue(script.contains("com.uber.nullaway:nullaway:"), "NullAway is on the errorprone configuration");
        assertTrue(script.contains("check(\"NullAway\", CheckSeverity.ERROR)"),
                "NullAway must fail the build, not warn — a warning nobody reads is not a gate");
        assertTrue(script.contains("enabled.set(name == \"compileJava\")"),
                "the checker stays scoped to compileJava");
    }

    @Test
    void modelsStaysOutOfScope() throws Exception {
        // JPA populates entity fields reflectively after construction, so every non-null
        // column would report as uninitialised. Re-adding it needs a plan, not a typo.
        assertFalse(annotatedPackages().contains("models"),
                "models is excluded on purpose — see the comment in build.gradle.kts");
    }

    @Test
    void everyPackageInScopeDeclaresNullMarked() throws Exception {
        var missing = new ArrayList<String>();
        for (var root : annotatedPackages()) {
            var dir = repo("app", root.replace('.', '/'));
            assertTrue(Files.isDirectory(dir), "annotated package " + root + " has a source directory");
            try (Stream<Path> tree = Files.walk(dir)) {
                for (var pkgDir : tree.filter(Files::isDirectory).toList()) {
                    if (!hasJavaSource(pkgDir)) {
                        continue;
                    }
                    var info = pkgDir.resolve("package-info.java");
                    // @NullMarked is per-package and does not reach subpackages, so a new
                    // one silently loses the source-level contract without this check.
                    if (!Files.exists(info) || !Files.readString(info).contains("@NullMarked")) {
                        missing.add(repo().relativize(pkgDir).toString());
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "packages under the NullAway scope with no @NullMarked package-info");
    }

    private static boolean hasJavaSource(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".java")
                    && !f.getFileName().toString().equals("package-info.java"));
        }
    }
}
