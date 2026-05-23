import org.junit.jupiter.api.*;
import play.test.*;
import agents.SkillVersionManager;

/**
 * Tests for SkillVersionManager: semver parsing, comparison, patch bumping,
 * frontmatter splitting, and content-diff-ignoring-version logic.
 */
class SkillVersionManagerTest extends UnitTest {

    // --- parseVersion ---

    @Test
    void parseVersionHandlesStandardSemver() {
        var result = SkillVersionManager.parseVersion("1.2.3");
        assertArrayEquals(new int[]{1, 2, 3}, result);
    }

    @Test
    void parseVersionHandlesAllZeros() {
        var result = SkillVersionManager.parseVersion("0.0.0");
        assertArrayEquals(new int[]{0, 0, 0}, result);
    }

    @Test
    void parseVersionHandlesInvalidString() {
        var result = SkillVersionManager.parseVersion("invalid");
        assertArrayEquals(new int[]{0, 0, 0}, result);
    }

    @Test
    void parseVersionHandlesNull() {
        var result = SkillVersionManager.parseVersion(null);
        assertArrayEquals(new int[]{0, 0, 0}, result);
    }

    @Test
    void parseVersionHandlesPartialVersion() {
        var result = SkillVersionManager.parseVersion("2.5");
        assertArrayEquals(new int[]{2, 5, 0}, result);
    }

    // --- compareVersions ---

    @Test
    void compareVersionsGreaterThan() {
        assertTrue(SkillVersionManager.compareVersions("1.0.0", "0.9.0") > 0);
    }

    @Test
    void compareVersionsEqual() {
        assertEquals(0, SkillVersionManager.compareVersions("1.0.0", "1.0.0"));
    }

    @Test
    void compareVersionsLessThan() {
        assertTrue(SkillVersionManager.compareVersions("1.0.0", "1.0.1") < 0);
    }

    @Test
    void compareVersionsHandlesMajorDifference() {
        assertTrue(SkillVersionManager.compareVersions("2.0.0", "1.99.99") > 0);
    }

    // --- bumpPatch ---

    @Test
    void bumpPatchSimple() {
        assertEquals("1.0.1", SkillVersionManager.bumpPatch("1.0.0"));
    }

    @Test
    void bumpPatchIncrementsThirdComponent() {
        assertEquals("1.2.4", SkillVersionManager.bumpPatch("1.2.3"));
    }

    @Test
    void bumpPatchFromZero() {
        assertEquals("0.0.1", SkillVersionManager.bumpPatch("0.0.0"));
    }

    // --- splitFrontmatter ---

    @Test
    void splitFrontmatterSeparatesCorrectly() {
        var content = "---\nversion: 1.0.0\ndescription: test\n---\n# Body content\nHello world";
        var result = SkillVersionManager.splitFrontmatter(content);
        assertNotNull(result.frontmatter());
        assertNotNull(result.body());
        assertTrue(result.frontmatter().contains("version: 1.0.0"));
        assertTrue(result.body().contains("# Body content"));
    }

    @Test
    void splitFrontmatterWithNoFrontmatter() {
        var content = "# Just a body\nNo frontmatter here";
        var result = SkillVersionManager.splitFrontmatter(content);
        assertNull(result.frontmatter());
        assertEquals(content, result.body());
    }

    @Test
    void splitFrontmatterWithNull() {
        var result = SkillVersionManager.splitFrontmatter(null);
        assertNull(result.frontmatter());
        assertNull(result.body());
    }

    // --- contentDiffersIgnoringVersion ---

    @Test
    void contentDiffersIgnoringVersionReturnsFalseWhenOnlyVersionDiffers() {
        var a = "---\nversion: 1.0.0\ndescription: test\n---\n# Body";
        var b = "---\nversion: 1.0.1\ndescription: test\n---\n# Body";
        assertFalse(SkillVersionManager.contentDiffersIgnoringVersion(a, b));
    }

    @Test
    void contentDiffersIgnoringVersionReturnsTrueWhenBodyDiffers() {
        var a = "---\nversion: 1.0.0\n---\n# Body A";
        var b = "---\nversion: 1.0.0\n---\n# Body B";
        assertTrue(SkillVersionManager.contentDiffersIgnoringVersion(a, b));
    }

    @Test
    void contentDiffersIgnoringVersionReturnsFalseForIdenticalContent() {
        var content = "---\nversion: 1.0.0\n---\n# Same body";
        assertFalse(SkillVersionManager.contentDiffersIgnoringVersion(content, content));
    }

    private static String callEnsureVersion(String content, String targetVersion) {
        try {
            var m = SkillVersionManager.class.getDeclaredMethod(
                    "ensureVersionInFrontmatter", String.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, content, targetVersion);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void ensureVersionWrapsContentWhenNoFrontmatter() {
        var result = callEnsureVersion("# body only", "2.1.0");
        assertTrue(result.startsWith("---\nversion: 2.1.0\n---"));
        assertTrue(result.contains("# body only"));
    }

    @Test
    void ensureVersionDefaultsTo1_0_0WhenNullAndNoFrontmatter() {
        var result = callEnsureVersion("body only", null);
        assertTrue(result.startsWith("---\nversion: 1.0.0\n---"));
    }

    @Test
    void ensureVersionAcceptsNullContent() {
        var result = callEnsureVersion(null, "3.0.0");
        assertTrue(result.startsWith("---\nversion: 3.0.0\n---"));
    }

    @Test
    void ensureVersionReplacesExistingWhenTargetSet() {
        var input = "---\nname: x\nversion: 0.1.0\n---\n# body";
        var result = callEnsureVersion(input, "1.5.0");
        assertTrue(result.contains("version: 1.5.0"), "got: " + result);
        assertFalse(result.contains("version: 0.1.0"));
    }

    @Test
    void ensureVersionKeepsExistingWhenTargetNull() {
        var input = "---\nname: x\nversion: 0.7.7\n---\n# body";
        var result = callEnsureVersion(input, null);
        assertTrue(result.contains("version: 0.7.7"),
                "existing version preserved when target null: " + result);
    }

    @Test
    void ensureVersionInsertsAfterDescriptionWhenVersionMissing() {
        var input = "---\nname: x\ndescription: hello\n---\n# body";
        var result = callEnsureVersion(input, "1.2.3");
        int descIdx = result.indexOf("description:");
        int verIdx = result.indexOf("version: 1.2.3");
        assertTrue(descIdx >= 0 && verIdx > descIdx,
                "version must follow description: " + result);
    }

    @Test
    void ensureVersionAppendsAtEndOfFrontmatterWhenNoDescription() {
        var input = "---\nname: x\n---\n# body";
        var result = callEnsureVersion(input, "4.0.0");
        assertTrue(result.contains("version: 4.0.0"));
    }

    // --- extractExplicitVersion + parseFrontmatterStringForVersion + resolveVersion ---

    @SuppressWarnings("unchecked")
    private static <T> T invokePkgPriv(String name, Class<?>[] paramTypes, Object[] args) {
        try {
            var m = SkillVersionManager.class.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return (T) m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void extractExplicitVersionReturnsNullForNullContent() {
        String v = invokePkgPriv("extractExplicitVersion",
                new Class<?>[]{String.class}, new Object[]{null});
        assertNull(v);
    }

    @Test
    void extractExplicitVersionReturnsNullWhenFrontmatterAbsent() {
        String v = invokePkgPriv("extractExplicitVersion",
                new Class<?>[]{String.class}, new Object[]{"# just body"});
        assertNull(v);
    }

    @Test
    void extractExplicitVersionReturnsVersionFromFrontmatter() {
        String v = invokePkgPriv("extractExplicitVersion",
                new Class<?>[]{String.class},
                new Object[]{"---\nname: x\nversion: 7.7.7\n---\n# body"});
        assertEquals("7.7.7", v);
    }

    @Test
    void parseFrontmatterStringForVersionDefaultsForNullContent() {
        String[] v = invokePkgPriv("parseFrontmatterStringForVersion",
                new Class<?>[]{String.class}, new Object[]{null});
        assertEquals("0.0.0", v[0]);
    }

    @Test
    void parseFrontmatterStringForVersionDefaultsForNoFrontmatter() {
        String[] v = invokePkgPriv("parseFrontmatterStringForVersion",
                new Class<?>[]{String.class}, new Object[]{"# body"});
        assertEquals("0.0.0", v[0]);
    }

    @Test
    void parseFrontmatterStringForVersionReturnsExplicitVersion() {
        String[] v = invokePkgPriv("parseFrontmatterStringForVersion",
                new Class<?>[]{String.class},
                new Object[]{"---\nname: x\nversion: 2.4.6\n---\n# body"});
        assertEquals("2.4.6", v[0]);
    }

    @Test
    void resolveVersionPrefersAutoWhenLlmNullOrBlank() {
        String v1 = invokePkgPriv("resolveVersion",
                new Class<?>[]{String.class, String.class},
                new Object[]{null, "1.2.3"});
        assertEquals("1.2.3", v1);
        String v2 = invokePkgPriv("resolveVersion",
                new Class<?>[]{String.class, String.class},
                new Object[]{"   ", "1.2.3"});
        assertEquals("1.2.3", v2);
    }

    // --- finalizeSkillMdWrite branches ---

    @Test
    void finalizeSkillMdWriteWrapsNewContentForMissingTarget() {
        // !Files.exists(targetPath) branch: pass a path that doesn't exist.
        // The result should have version frontmatter injected (defaulting to
        // "1.0.0" when content doesn't carry one).
        var target = java.nio.file.Path.of("/tmp/never-existed-skill-" + System.nanoTime() + ".md");
        var result = SkillVersionManager.finalizeSkillMdWrite(target, "# Body only");
        assertTrue(result.startsWith("---\nversion: 1.0.0"),
                "missing-target path injects 1.0.0 default: " + result);
        assertTrue(result.contains("# Body only"));
    }

    @Test
    void finalizeSkillMdWriteHonorsLlmVersionForMissingTarget() {
        // Same branch but LLM put an explicit version in the new content.
        var target = java.nio.file.Path.of("/tmp/never-existed-skill-" + System.nanoTime() + ".md");
        var content = "---\nname: x\nversion: 2.5.0\n---\n# body";
        var result = SkillVersionManager.finalizeSkillMdWrite(target, content);
        assertTrue(result.contains("version: 2.5.0"), "LLM version honored: " + result);
    }

    @Test
    void finalizeSkillMdWriteCoercesNullContentToEmpty() {
        // Null content → coerced to "" then wrapped with default version.
        var target = java.nio.file.Path.of("/tmp/null-content-skill-" + System.nanoTime() + ".md");
        var result = SkillVersionManager.finalizeSkillMdWrite(target, null);
        assertNotNull(result);
        assertTrue(result.contains("version: 1.0.0"));
    }

    @Test
    void resolveVersionPrefersLlmOnlyWhenStrictlyHigher() {
        String win = invokePkgPriv("resolveVersion",
                new Class<?>[]{String.class, String.class},
                new Object[]{"2.0.0", "1.5.0"});
        assertEquals("2.0.0", win);

        String fallback = invokePkgPriv("resolveVersion",
                new Class<?>[]{String.class, String.class},
                new Object[]{"1.0.0", "1.5.0"});
        assertEquals("1.5.0", fallback);
    }
}
