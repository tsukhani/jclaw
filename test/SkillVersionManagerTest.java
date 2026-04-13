import org.junit.jupiter.api.*;
import play.test.*;
import agents.SkillVersionManager;

/**
 * Tests for SkillVersionManager: semver parsing, comparison, patch bumping,
 * frontmatter splitting, and content-diff-ignoring-version logic.
 */
public class SkillVersionManagerTest extends UnitTest {

    // --- parseVersion ---

    @Test
    public void parseVersionHandlesStandardSemver() {
        var result = SkillVersionManager.parseVersion("1.2.3");
        assertArrayEquals(new int[]{1, 2, 3}, result);
    }

    @Test
    public void parseVersionHandlesAllZeros() {
        var result = SkillVersionManager.parseVersion("0.0.0");
        assertArrayEquals(new int[]{0, 0, 0}, result);
    }

    @Test
    public void parseVersionHandlesInvalidString() {
        var result = SkillVersionManager.parseVersion("invalid");
        assertArrayEquals(new int[]{0, 0, 0}, result);
    }

    @Test
    public void parseVersionHandlesNull() {
        var result = SkillVersionManager.parseVersion(null);
        assertArrayEquals(new int[]{0, 0, 0}, result);
    }

    @Test
    public void parseVersionHandlesPartialVersion() {
        var result = SkillVersionManager.parseVersion("2.5");
        assertArrayEquals(new int[]{2, 5, 0}, result);
    }

    // --- compareVersions ---

    @Test
    public void compareVersionsGreaterThan() {
        assertTrue(SkillVersionManager.compareVersions("1.0.0", "0.9.0") > 0);
    }

    @Test
    public void compareVersionsEqual() {
        assertEquals(0, SkillVersionManager.compareVersions("1.0.0", "1.0.0"));
    }

    @Test
    public void compareVersionsLessThan() {
        assertTrue(SkillVersionManager.compareVersions("1.0.0", "1.0.1") < 0);
    }

    @Test
    public void compareVersionsHandlesMajorDifference() {
        assertTrue(SkillVersionManager.compareVersions("2.0.0", "1.99.99") > 0);
    }

    // --- bumpPatch ---

    @Test
    public void bumpPatchSimple() {
        assertEquals("1.0.1", SkillVersionManager.bumpPatch("1.0.0"));
    }

    @Test
    public void bumpPatchIncrementsThirdComponent() {
        assertEquals("1.2.4", SkillVersionManager.bumpPatch("1.2.3"));
    }

    @Test
    public void bumpPatchFromZero() {
        assertEquals("0.0.1", SkillVersionManager.bumpPatch("0.0.0"));
    }

    // --- splitFrontmatter ---

    @Test
    public void splitFrontmatterSeparatesCorrectly() {
        var content = "---\nversion: 1.0.0\ndescription: test\n---\n# Body content\nHello world";
        var result = SkillVersionManager.splitFrontmatter(content);
        assertNotNull(result.frontmatter());
        assertNotNull(result.body());
        assertTrue(result.frontmatter().contains("version: 1.0.0"));
        assertTrue(result.body().contains("# Body content"));
    }

    @Test
    public void splitFrontmatterWithNoFrontmatter() {
        var content = "# Just a body\nNo frontmatter here";
        var result = SkillVersionManager.splitFrontmatter(content);
        assertNull(result.frontmatter());
        assertEquals(content, result.body());
    }

    @Test
    public void splitFrontmatterWithNull() {
        var result = SkillVersionManager.splitFrontmatter(null);
        assertNull(result.frontmatter());
        assertNull(result.body());
    }

    // --- contentDiffersIgnoringVersion ---

    @Test
    public void contentDiffersIgnoringVersionReturnsFalseWhenOnlyVersionDiffers() {
        var a = "---\nversion: 1.0.0\ndescription: test\n---\n# Body";
        var b = "---\nversion: 1.0.1\ndescription: test\n---\n# Body";
        assertFalse(SkillVersionManager.contentDiffersIgnoringVersion(a, b));
    }

    @Test
    public void contentDiffersIgnoringVersionReturnsTrueWhenBodyDiffers() {
        var a = "---\nversion: 1.0.0\n---\n# Body A";
        var b = "---\nversion: 1.0.0\n---\n# Body B";
        assertTrue(SkillVersionManager.contentDiffersIgnoringVersion(a, b));
    }

    @Test
    public void contentDiffersIgnoringVersionReturnsFalseForIdenticalContent() {
        var content = "---\nversion: 1.0.0\n---\n# Same body";
        assertFalse(SkillVersionManager.contentDiffersIgnoringVersion(content, content));
    }
}
