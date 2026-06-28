import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.catalog.CatalogSkill;
import services.catalog.ClawhubCatalog;

/**
 * Pure mapping coverage for the dynamic ClawHub catalog — the clawhub API item
 * shapes → normalized {@link CatalogSkill}. No network: feeds verbatim API JSON
 * to the public mapping methods. (The live HTTP path is exercised by running.)
 */
class ClawhubCatalogTest extends UnitTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void mapsBrowseItemWithInstallsAndCategory() {
        // Verbatim /api/v1/skills item shape.
        var item = obj("""
                {"slug":"docker-helper","displayName":"Docker Helper","summary":"Manage containers",
                 "description":"Build and run docker images and compose stacks","topics":["devops"],
                 "tags":{"latest":"1.2.3"},"stats":{"downloads":5000,"installsAllTime":1200,"stars":40}}
                """);

        var s = ClawhubCatalog.mapBrowseItem(item, "https://clawhub.ai");

        assertEquals("docker-helper", s.skillId());
        assertEquals("Docker Helper", s.displayName());
        assertEquals("clawhub", s.provider());
        assertEquals(1200L, s.installs(), "prefers installsAllTime over downloads");
        assertEquals("https://clawhub.ai/skills/docker-helper", s.url());
        assertEquals("clawhub.ai/docker-helper", s.source());
        assertEquals("DevOps & Cloud", s.category());
    }

    @Test
    void browseItemFallsBackToDownloadsAndSlug() {
        var item = obj("""
                {"slug":"zxqwop-thing","stats":{"downloads":77}}
                """);

        var s = ClawhubCatalog.mapBrowseItem(item, "https://clawhub.ai");

        assertEquals("zxqwop-thing", s.displayName(), "displayName falls back to slug");
        assertEquals(77L, s.installs(), "installs falls back to downloads");
        assertEquals("Other", s.category(), "no keyword match → Other");
    }

    @Test
    void mapsSearchResultWithOwnerHandle() {
        var res = obj("""
                {"score":4.1,"slug":"git","displayName":"Git","summary":"version control workflows",
                 "downloads":15817,"ownerHandle":"ivangdavila"}
                """);

        var s = ClawhubCatalog.mapSearchResult(res, "https://clawhub.ai");

        assertEquals("git", s.skillId());
        assertEquals("ivangdavila", s.owner());
        assertEquals(15817L, s.installs());
        assertEquals("Git & VCS", s.category());
        assertEquals("clawhub", s.provider());
    }
}
