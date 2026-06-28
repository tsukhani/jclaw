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
        // Browse omits the owner, and clawhub slugs are owner-scoped, so the row
        // links to the slug-filtered search rather than a (non-resolvable) page.
        assertEquals("https://clawhub.ai/skills?q=docker-helper", s.url());
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
        // Owner-scoped canonical page (the form clawhub declares canonical).
        assertEquals("https://clawhub.ai/ivangdavila/skills/git", s.url());
        assertEquals("clawhub.ai/ivangdavila/git", s.source());
    }

    @Test
    void searchResultWithoutOwnerFallsBackToSearchLink() {
        // ownerHandle can be absent; without it we can't resolve the exact page,
        // so fall back to the slug-filtered search (same as browse rows).
        var res = obj("""
                {"slug":"mystery-skill","displayName":"Mystery","summary":"does things","downloads":3}
                """);

        var s = ClawhubCatalog.mapSearchResult(res, "https://clawhub.ai");

        assertEquals("", s.owner());
        assertEquals("https://clawhub.ai/skills?q=mystery-skill", s.url());
        assertEquals("clawhub.ai/mystery-skill", s.source());
    }
}
