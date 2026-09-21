import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import tools.scrape.WebScrapeSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Write-time rules for the web_scrape.* keys, and that every one of them has a Settings row. */
class WebScrapeSettingsTest extends UnitTest {

    private static String reject(String key, String value) {
        return WebScrapeSettings.rejectionFor(key, value);
    }

    @Test
    void pagesAndTimeoutMustBeAtLeastOne() {
        for (var key : List.of(WebScrapeSettings.MAX_PAGES, WebScrapeSettings.TIMEOUT_SECONDS)) {
            assertNotNull(reject(key, "0"), key);
            assertNotNull(reject(key, "ten"), key);
            assertNull(reject(key, "1"), key);
        }
    }

    @Test
    void zeroIsALegitimateValueForTheOtherLimits() {
        for (var key : List.of(WebScrapeSettings.MAX_DEPTH, WebScrapeSettings.MAX_ESCALATIONS,
                WebScrapeSettings.MAX_SITEMAP_URLS, WebScrapeSettings.MAX_SITEMAP_DOCUMENTS)) {
            assertNull(reject(key, "0"), key);
            assertNotNull(reject(key, "-1"), key);
            assertNotNull(reject(key, ""), key);
        }
    }

    @Test
    void concurrencyIsBoundedAtBothEnds() {
        assertNotNull(reject(WebScrapeSettings.CONCURRENCY, "0"));
        assertNull(reject(WebScrapeSettings.CONCURRENCY, "1"));
        assertNull(reject(WebScrapeSettings.CONCURRENCY, "16"));
        assertNotNull(reject(WebScrapeSettings.CONCURRENCY, "17"));
    }

    @Test
    void theBackgroundJobLimitsAreWholeNumbersOfAtLeastOne() {
        for (var key : List.of(WebScrapeSettings.JOB_MAX_PAGES, WebScrapeSettings.JOB_MAX_MINUTES)) {
            assertNotNull(reject(key, "0"), key);
            assertNotNull(reject(key, "an hour"), key);
            assertNull(reject(key, "1"), key);
            assertNull(reject(key, "5000"), key);
        }
        assertNotNull(reject(WebScrapeSettings.JOB_MAX_CONCURRENT, "0"));
        assertNull(reject(WebScrapeSettings.JOB_MAX_CONCURRENT, "1"));
        assertNull(reject(WebScrapeSettings.JOB_MAX_CONCURRENT, "8"));
        assertNotNull(reject(WebScrapeSettings.JOB_MAX_CONCURRENT, "9"), "each running job has its own worker pool");
    }

    @Test
    void togglesTakeOnlyTrueOrFalse() {
        for (var key : List.of(WebScrapeSettings.RESPECT_ROBOTS, WebScrapeSettings.SEED_FROM_SITEMAP)) {
            assertNull(reject(key, "true"), key);
            assertNull(reject(key, "FALSE"), key);
            // The reader treats anything but "false" as on, so a typo would silently stay on.
            assertNotNull(reject(key, "no"), key);
        }
    }

    @Test
    void languageMustBeAnHreflangCode() {
        for (var ok : List.of("en", "ja", "pt-BR", "zh-Hant")) {
            assertNull(reject(WebScrapeSettings.LANGUAGE, ok), ok);
        }
        for (var bad : List.of("", "english", "en_GB", "en\r\nX-Injected: 1")) {
            assertNotNull(reject(WebScrapeSettings.LANGUAGE, bad), bad);
        }
    }

    @Test
    void theProxyKeysAreCheckedWhenWritten() {
        assertNull(WebScrapeSettings.rejectionFor(WebScrapeSettings.PROXY_URL, ""), "empty turns the proxy off");
        assertNull(WebScrapeSettings.rejectionFor(WebScrapeSettings.PROXY_URL, "http://proxy.example:8080"));
        assertNotNull(WebScrapeSettings.rejectionFor(WebScrapeSettings.PROXY_URL, "ftp://proxy.example:21"));
        assertNotNull(WebScrapeSettings.rejectionFor(WebScrapeSettings.PROXY_URL, "http://u:p@proxy.example:8080"),
                "credentials in the URL would be returned unmasked on every config read");
        assertNotNull(WebScrapeSettings.rejectionFor(WebScrapeSettings.PROXY_ENABLED, "maybe"));
        assertNotNull(WebScrapeSettings.rejectionFor(WebScrapeSettings.PROXY_PASSWORD, "pass\nword"));
    }

    @Test
    void everyWebScrapeKeyInTheCodeHasASettingsRow() throws IOException {
        var root = Path.of(Play.applicationPath.getAbsolutePath());
        var panel = Files.readString(root.resolve("frontend/components/settings/SettingsWebScrapePanel.vue"));
        // Dotted segments included: web_scrape.proxy.url matched nothing under [a-z0-9-]+ alone.
        var literal = Pattern.compile("\"(web_scrape\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)*)\"");
        var keys = new TreeSet<String>();
        try (var files = Files.walk(root.resolve("app"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var m = literal.matcher(Files.readString(file));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }

        assertTrue(keys.size() >= 10, "the scan must find the keys it guards, found " + keys);
        var missing = keys.stream().filter(k -> !panel.contains("'" + k + "'")).toList();
        assertEquals(List.of(), missing, "web_scrape keys with no row in Settings > Web Scraping");
    }
}
