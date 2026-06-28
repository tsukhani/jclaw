import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ClawhubSkillFetcher;

import java.util.List;

/**
 * Pure coverage for the ClawHub import manifest parsing — the version-detail
 * response shape -> file paths and the latest-version resolution. No network:
 * the live download path is exercised by running.
 */
class ClawhubSkillFetcherTest extends UnitTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void resolvesLatestVersion() {
        var detail = obj("""
                {"skill":{"slug":"git"},"latestVersion":{"version":"1.0.8","changelog":"x"},"owner":{}}
                """);
        assertEquals("1.0.8", ClawhubSkillFetcher.latestVersion(detail));
    }

    @Test
    void latestVersionAbsentIsNull() {
        assertNull(ClawhubSkillFetcher.latestVersion(obj("{\"skill\":{}}")));
    }

    @Test
    void parsesFilePathsFromVersionManifest() {
        // Verbatim /api/v1/skills/{slug}/versions/{v} shape (version.files[].path).
        var ver = obj("""
                {"skill":{"slug":"git"},"version":{"version":"1.0.8","files":[
                  {"path":"SKILL.md","size":5408,"sha256":"a","contentType":"text/markdown"},
                  {"path":"advanced.md","size":3643,"sha256":"b","contentType":"text/markdown"},
                  {"path":"tools/run.sh","size":12,"sha256":"c","contentType":"text/plain"}
                ]}}
                """);
        assertEquals(List.of("SKILL.md", "advanced.md", "tools/run.sh"),
                ClawhubSkillFetcher.parseFilePaths(ver));
    }

    @Test
    void parseFilePathsEmptyWhenNoFiles() {
        assertTrue(ClawhubSkillFetcher.parseFilePaths(obj("{\"version\":{\"version\":\"1.0\"}}")).isEmpty());
        assertTrue(ClawhubSkillFetcher.parseFilePaths(obj("{}")).isEmpty());
    }
}
