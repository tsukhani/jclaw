import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.scrape.ScrapeJobRequest;
import tools.scrape.ScrapeOutput;
import tools.scrape.WebScrapeSettings;

/**
 * A background scrape job's request (JCLAW-1272): an agent's limits are clamped into the operator's
 * ceilings, the operator's own are not, and what is stored reads back unchanged.
 *
 * <p>Ceilings are read from Settings at the start of each test rather than assumed, since the keys are
 * process-wide and another class may be changing one.
 */
class ScrapeJobRequestTest extends UnitTest {

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void anAgentsLimitsAreClampedIntoTheCeilings() {
        int pages = WebScrapeSettings.jobMaxPages();
        int depth = WebScrapeSettings.maxDepth();
        int minutes = WebScrapeSettings.jobMaxMinutes();

        var over = ScrapeJobRequest.forAgent(args("""
                {"url": "https://site.test/", "maxPages": %d, "maxDepth": %d, "maxMinutes": %d}"""
                .formatted(pages + 100, depth + 3, minutes + 30)));
        assertEquals(pages, over.maxPages());
        assertEquals(depth, over.maxDepth());
        assertEquals(minutes, over.maxMinutes());

        var under = ScrapeJobRequest.forAgent(args("""
                {"url": "https://site.test/", "maxPages": 0, "maxDepth": -1, "maxMinutes": 0}"""));
        assertEquals(1, under.maxPages());
        assertEquals(0, under.maxDepth());
        assertEquals(1, under.maxMinutes());
    }

    @Test
    void theCeilingsAreTheDefaults() {
        var request = ScrapeJobRequest.forAgent(args("{\"url\": \"https://site.test/\"}"));

        assertEquals(WebScrapeSettings.jobMaxPages(), request.maxPages());
        assertEquals(WebScrapeSettings.maxDepth(), request.maxDepth());
        assertEquals(WebScrapeSettings.jobMaxMinutes(), request.maxMinutes());
        assertTrue(request.sameHostOnly());
        assertEquals(ScrapeOutput.Format.MARKDOWN, request.output().format());
    }

    @Test
    void theOperatorMayGoPastTheCeilingsButNotBelowTheFloor() {
        int pages = WebScrapeSettings.jobMaxPages() + 250;
        var request = ScrapeJobRequest.forOperator(args("""
                {"url": "https://site.test/", "maxPages": %d, "maxMinutes": 600}""".formatted(pages)));
        assertEquals(pages, request.maxPages());
        assertEquals(600, request.maxMinutes());

        var error = assertThrows(IllegalArgumentException.class, () -> ScrapeJobRequest.forOperator(
                args("{\"url\": \"https://site.test/\", \"maxPages\": 0}")));
        assertEquals("maxPages must be at least 1", error.getMessage());
    }

    @Test
    void whatIsStoredReadsBackUnchanged() {
        var original = ScrapeJobRequest.forOperator(args("""
                {"url": "https://site.test/docs/", "maxPages": 40, "maxDepth": 3, "maxMinutes": 15,
                 "sameHostOnly": false, "respectRobots": false, "seedFromSitemap": false, "language": "pt-BR",
                 "format": "json", "extract": {"title": "h1", "next": "a.next@href"}, "metadata": true}"""));

        var stored = original.toJson().toString();
        assertEquals(original, ScrapeJobRequest.fromJson(stored));
        assertEquals("a.next@href", original.toJson().getAsJsonObject("extract").get("next").getAsString());
    }

    @Test
    void eachBadValueIsRefusedByName() {
        assertMessage("url is required", "{}");
        assertMessageStarts("url cannot be scraped", "{\"url\": \"ftp://site.test/\"}");
        assertMessage("maxPages must be a whole number", "{\"url\": \"https://site.test/\", \"maxPages\": 2.5}");
        assertMessage("maxMinutes must be a whole number", "{\"url\": \"https://site.test/\", \"maxMinutes\": \"soon\"}");
        assertMessage("sameHostOnly must be true or false", "{\"url\": \"https://site.test/\", \"sameHostOnly\": \"maybe\"}");
        assertMessageStarts("language must be", "{\"url\": \"https://site.test/\", \"language\": \"en\\nX-Evil: 1\"}");
        assertMessageStarts("format must be one of", "{\"url\": \"https://site.test/\", \"format\": \"html\"}");
        assertMessageStarts("extract field 'x'", "{\"url\": \"https://site.test/\", \"extract\": {\"x\": \"div[[\"}}");
    }

    @Test
    void aWholeNumberWrittenAsTextIsStillANumber() {
        assertEquals(12, ScrapeJobRequest.forOperator(
                args("{\"url\": \"https://site.test/\", \"maxPages\": \"12\"}")).maxPages());
    }

    private static void assertMessage(String expected, String json) {
        var error = assertThrows(IllegalArgumentException.class, () -> ScrapeJobRequest.forOperator(args(json)));
        assertEquals(expected, error.getMessage());
    }

    private static void assertMessageStarts(String prefix, String json) {
        var error = assertThrows(IllegalArgumentException.class, () -> ScrapeJobRequest.forOperator(args(json)));
        assertTrue(error.getMessage().startsWith(prefix), error.getMessage());
    }
}
