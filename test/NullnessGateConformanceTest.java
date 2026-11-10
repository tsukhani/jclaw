import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The build-time nullness gate's own guard rail (JCLAW-1149).
 *
 * <p>NullAway runs on the Gradle {@code compileJava}. The play CLI's autotest task depends on
 * it, so a violation does fail {@code play autotest} — but nothing in the suite would notice
 * the gate being switched off, downgraded to a warning, or left behind by a new subpackage.
 * These assertions read
 * {@code build.gradle.kts} and the {@code package-info.java} files directly, which is the
 * only way to make those three regressions fail a test run rather than a later push.
 */
class NullnessGateConformanceTest extends UnitTest {

    private static final Pattern ANNOTATED_PACKAGES = Pattern.compile(
            "option\\(\"NullAway:AnnotatedPackages\",\\s*\"([^\"]*)\"\\)");

    /** The annotation on the package declaration itself, not a mention of it in prose. */
    private static final Pattern NULL_MARKED_PACKAGE = Pattern.compile("@NullMarked\\s+package\\s");

    private static final String TEST_COMPILE_BLOCK = "tasks.named<JavaCompile>(\"compileTestJava\")";

    /** JPA entities, populated reflectively after construction, and the vendored Aspose shim. */
    private static final Set<String> EXCLUDED_PACKAGES = Set.of("models", "com");

    /** Everything a commented-out line would leave behind: the token without the configuration. */
    private static final List<String> SILENT_SWITCH_OFFS = List.of(
            "enabled.set(false)", "disable(\"NullAway\")", "excludedPaths", "errorproneArgs",
            "-XepDisableAllChecks", "NullAway:UnannotatedSubPackages", "NullAway:ExcludedClasses");

    private static Path repo(String... parts) {
        var path = Path.of(Play.applicationPath.getAbsolutePath());
        for (var part : parts) {
            path = path.resolve(part);
        }
        return path;
    }

    private static String buildScript() throws IOException {
        return ResourceLeakGateConformanceTest.withoutComments(Files.readString(repo("build.gradle.kts")));
    }

    private static List<String> annotatedPackages() throws IOException {
        var matcher = ANNOTATED_PACKAGES.matcher(buildScript());
        assertTrue(matcher.find(), "build.gradle.kts no longer declares NullAway:AnnotatedPackages: restore "
                + "option(\"NullAway:AnnotatedPackages\", \"<packages>\") in compileJava's Error Prone block");
        return Arrays.stream(matcher.group(1).split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Test
    void theCheckerIsWiredAtErrorSeverity() throws Exception {
        var script = buildScript();
        assertTrue(script.contains("id(\"net.ltgt.errorprone\")"),
                "the Error Prone plugin is no longer applied: restore id(\"net.ltgt.errorprone\") in build.gradle.kts");
        assertTrue(script.contains("com.uber.nullaway:nullaway:"),
                "NullAway is off the errorprone configuration: restore errorprone(\"com.uber.nullaway:nullaway:<version>\")");
        assertTrue(script.contains("enabled.set(name == \"compileJava\")"),
                "the checker is no longer scoped to compileJava: restore enabled.set(name == \"compileJava\") in the "
                        + "shared Error Prone block");
        int testBlock = script.indexOf(TEST_COMPILE_BLOCK);
        assertTrue(testBlock > 0, "compileTestJava lost its own Error Prone block: restore "
                + TEST_COMPILE_BLOCK + " after the shared one");
        var mainBlock = script.substring(0, testBlock);
        assertTrue(mainBlock.contains("check(\"NullAway\", CheckSeverity.ERROR)"),
                "NullAway must fail the app/ compile, not warn — a warning nobody reads is not a gate. Restore "
                        + "check(\"NullAway\", CheckSeverity.ERROR) in the shared Error Prone block");
        assertFalse(mainBlock.contains("check(\"NullAway\", CheckSeverity.OFF)"),
                "NullAway is OFF for compileTestJava only: move check(\"NullAway\", CheckSeverity.OFF) into that block");
        for (var off : SILENT_SWITCH_OFFS) {
            assertFalse(script.contains(off), off + " would leave every token above intact while the checker checks "
                    + "nothing: remove it from build.gradle.kts");
        }
    }

    @Test
    void modelsStaysOutOfScope() throws Exception {
        // JPA populates entity fields reflectively after construction, so every non-null
        // column would report as uninitialised. Re-adding it needs a plan, not a typo.
        assertFalse(annotatedPackages().contains("models"),
                "models is excluded on purpose — see the comment in build.gradle.kts. Remove it from "
                        + "NullAway:AnnotatedPackages");
    }

    @Test
    void everyPackageInScopeDeclaresNullMarked() throws Exception {
        var missing = new ArrayList<String>();
        for (var root : annotatedPackages()) {
            var dir = repo("app", root.replace('.', '/'));
            assertTrue(Files.isDirectory(dir), "annotated package " + root + " has no source directory: remove it "
                    + "from NullAway:AnnotatedPackages, or correct its name");
            try (Stream<Path> tree = Files.walk(dir)) {
                for (var pkgDir : tree.filter(Files::isDirectory).toList()) {
                    if (!hasJavaSource(pkgDir)) {
                        continue;
                    }
                    var info = pkgDir.resolve("package-info.java");
                    // @NullMarked is per-package and does not reach subpackages, so a new
                    // one silently loses the source-level contract without this check.
                    if (!Files.exists(info) || !NULL_MARKED_PACKAGE.matcher(
                            ResourceLeakGateConformanceTest.withoutComments(Files.readString(info))).find()) {
                        missing.add(repo().relativize(pkgDir).toString());
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "packages under the NullAway scope with no @NullMarked package-info: give "
                + "each a package-info.java declaring @NullMarked on its package (docs/agents/nullness.md)");
    }

    @Test
    void everyTopLevelPackageIsInScopeOrDeliberatelyExcluded() throws Exception {
        // AnnotatedPackages is an allowlist, so a package nobody adds is silently unchecked.
        var listed = annotatedPackages();
        var unaccounted = new ArrayList<String>();
        try (Stream<Path> roots = Files.list(repo("app"))) {
            for (var dir : roots.filter(Files::isDirectory).sorted().toList()) {
                var name = dir.getFileName().toString();
                if (!listed.contains(name) && !EXCLUDED_PACKAGES.contains(name) && treeHoldsJavaSource(dir)) {
                    unaccounted.add(name);
                }
            }
        }
        assertEquals(List.of(), unaccounted,
                "top-level app/ packages neither in NullAway:AnnotatedPackages nor excluded on purpose: add each to "
                        + "NullAway:AnnotatedPackages with a @NullMarked package-info, or to EXCLUDED_PACKAGES here "
                        + "with its reason");
    }

    private static boolean treeHoldsJavaSource(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.anyMatch(f -> f.getFileName().toString().endsWith(".java"));
        }
    }

    private static boolean hasJavaSource(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".java")
                    && !f.getFileName().toString().equals("package-info.java"));
        }
    }
}
