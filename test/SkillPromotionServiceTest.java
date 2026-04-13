import org.junit.jupiter.api.*;
import play.test.*;
import services.SkillPromotionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;

public class SkillPromotionServiceTest extends UnitTest {

    private Path tmpDir;

    @BeforeEach
    void setup() throws Exception {
        tmpDir = Files.createTempDirectory("skill-promotion-test-");
    }

    @AfterEach
    void teardown() throws Exception {
        if (tmpDir != null && Files.exists(tmpDir)) {
            SkillPromotionService.deleteRecursive(tmpDir);
        }
    }

    // ==================== deleteRecursive ====================

    @Test
    public void deleteRecursiveRemovesNestedStructure() throws Exception {
        var sub = tmpDir.resolve("a/b/c");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("file.txt"), "hello");
        Files.writeString(tmpDir.resolve("root.txt"), "root");

        SkillPromotionService.deleteRecursive(tmpDir);

        assertFalse(Files.exists(tmpDir));
    }

    @Test
    public void deleteRecursiveHandlesEmptyDirectory() throws Exception {
        var empty = tmpDir.resolve("empty");
        Files.createDirectories(empty);

        SkillPromotionService.deleteRecursive(empty);

        assertFalse(Files.exists(empty));
        assertTrue(Files.exists(tmpDir), "Parent should still exist");
    }

    @Test
    public void deleteRecursiveNoopOnNonExistentPath() throws Exception {
        var ghost = tmpDir.resolve("does-not-exist");
        // Should not throw
        SkillPromotionService.deleteRecursive(ghost);
    }

    @Test
    public void deleteRecursiveRemovesMultipleFiles() throws Exception {
        Files.writeString(tmpDir.resolve("a.txt"), "a");
        Files.writeString(tmpDir.resolve("b.txt"), "b");
        Files.writeString(tmpDir.resolve("c.txt"), "c");

        SkillPromotionService.deleteRecursive(tmpDir);

        assertFalse(Files.exists(tmpDir));
    }

    // ==================== enforceTextFilePath ====================

    @Test
    public void skillMdStaysAtRoot() {
        assertEquals("SKILL.md", SkillPromotionService.enforceTextFilePath("SKILL.md"));
    }

    @Test
    public void toolsSubdirStaysUnchanged() {
        assertEquals("tools/helper.sh", SkillPromotionService.enforceTextFilePath("tools/helper.sh"));
    }

    @Test
    public void credentialsSubdirStaysUnchanged() {
        assertEquals("credentials/config.json", SkillPromotionService.enforceTextFilePath("credentials/config.json"));
    }

    @Test
    public void jsonFileRelocatedToCredentials() {
        assertEquals("credentials/config.json", SkillPromotionService.enforceTextFilePath("config.json"));
    }

    @Test
    public void yamlFileRelocatedToCredentials() {
        assertEquals("credentials/settings.yaml", SkillPromotionService.enforceTextFilePath("settings.yaml"));
    }

    @Test
    public void ymlFileRelocatedToCredentials() {
        assertEquals("credentials/secrets.yml", SkillPromotionService.enforceTextFilePath("secrets.yml"));
    }

    @Test
    public void envFileRelocatedToCredentials() {
        assertEquals("credentials/.env", SkillPromotionService.enforceTextFilePath(".env"));
    }

    @Test
    public void propertiesFileRelocatedToCredentials() {
        assertEquals("credentials/app.properties", SkillPromotionService.enforceTextFilePath("app.properties"));
    }

    @Test
    public void txtFileRelocatedToCredentials() {
        assertEquals("credentials/notes.txt", SkillPromotionService.enforceTextFilePath("notes.txt"));
    }

    @Test
    public void nonCredentialFileStaysAtRoot() {
        assertEquals("README.md", SkillPromotionService.enforceTextFilePath("README.md"));
    }

    @Test
    public void nonCredentialPyFileStaysAtRoot() {
        assertEquals("script.py", SkillPromotionService.enforceTextFilePath("script.py"));
    }

    // ==================== stripCredentialsJson ====================

    @Test
    public void stripCredentialsReplacesValues() {
        var input = """
                {"apiKey": "sk-secret-123", "baseUrl": "https://api.example.com"}""";
        var result = SkillPromotionService.stripCredentialsJson(input);
        assertTrue(result.contains("[CREDENTIAL]"));
        assertFalse(result.contains("sk-secret-123"));
        assertFalse(result.contains("https://api.example.com"));
        // Keys should still be present
        assertTrue(result.contains("apiKey"));
        assertTrue(result.contains("baseUrl"));
    }

    @Test
    public void stripCredentialsEmptyObjectReturnsEmpty() {
        var result = SkillPromotionService.stripCredentialsJson("{}");
        assertEquals("{}", result);
    }

    @Test
    public void stripCredentialsInvalidJsonReturnsFallback() {
        var result = SkillPromotionService.stripCredentialsJson("not json at all");
        assertEquals("{}", result);
    }

    @Test
    public void stripCredentialsArrayFallsBackToEmpty() {
        // Arrays are not JsonObjects, so getAsJsonObject() throws
        var result = SkillPromotionService.stripCredentialsJson("[1, 2, 3]");
        assertEquals("{}", result);
    }

    // ==================== atomicSwap ====================

    @Test
    public void atomicSwapMovesStaging() throws Exception {
        var staging = tmpDir.resolve("staging");
        var target = tmpDir.resolve("target");
        var backup = tmpDir.resolve("backup");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("file.txt"), "new content");

        SkillPromotionService.atomicSwap(target, staging, backup, false);

        assertTrue(Files.exists(target.resolve("file.txt")));
        assertEquals("new content", Files.readString(target.resolve("file.txt")));
        assertFalse(Files.exists(staging));
    }

    @Test
    public void atomicSwapReplacesExisting() throws Exception {
        var staging = tmpDir.resolve("staging");
        var target = tmpDir.resolve("target");
        var backup = tmpDir.resolve("backup");
        Files.createDirectories(staging);
        Files.createDirectories(target);
        Files.writeString(staging.resolve("new.txt"), "new");
        Files.writeString(target.resolve("old.txt"), "old");

        SkillPromotionService.atomicSwap(target, staging, backup, true);

        assertTrue(Files.exists(target.resolve("new.txt")));
        assertFalse(Files.exists(target.resolve("old.txt")));
        // Backup should have been cleaned up
        assertFalse(Files.exists(backup));
    }

    // ==================== formatViolations ====================

    @Test
    public void formatViolationsJoinsWithSemicolon() {
        var violations = java.util.List.of(
                new services.SkillBinaryScanner.Violation("tools/bad.bin", "abc123", "MalwareBazaar", "Mirai"),
                new services.SkillBinaryScanner.Violation("tools/evil.exe", "def456", "MetaDefender", "Trojan")
        );
        var formatted = SkillPromotionService.formatViolations(violations);
        assertTrue(formatted.contains("tools/bad.bin"));
        assertTrue(formatted.contains("tools/evil.exe"));
        assertTrue(formatted.contains("; "));
    }

    @Test
    public void formatViolationsEmptyList() {
        var formatted = SkillPromotionService.formatViolations(java.util.List.of());
        assertEquals("", formatted);
    }
}
