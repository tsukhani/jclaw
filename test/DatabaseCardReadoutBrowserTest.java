import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import models.DatabaseFileSample;
import models.DatabaseTableSample;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.Fixtures;
import play.test.FunctionalTest;
import tools.PlaywrightBrowserTool;
import utils.AppClock;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The database card's parts readout keeps one height in a narrow window, so the bar and the table stay under a
 * pointer resting on them (JF-526). Layout, so jsdom cannot show it: Chromium drives the built SPA against this
 * autotest server (JF-527).
 *
 * <p>Live-browser, so it runs only under {@code JCLAW_PLAYWRIGHT_TEST}, which {@code ./jclaw.sh test} sets where
 * Chromium is installed. It needs {@code public/spa} built from the card as it is now: {@code /release-check} builds it
 * from the release commit before its Mac run, and anywhere else a build older than the card's sources would test old
 * code, so the class skips, naming the newer file.
 */
class DatabaseCardReadoutBrowserTest extends FunctionalTest {

    private static final String PASSWORD = "readout-check";
    private static final long MB = 1024L * 1024L;
    private static final List<String> CARD_SOURCES = List.of(
            "frontend/components/settings/SettingsDatabaseGrowth.vue",
            "frontend/components/settings/DatabaseFileHistory.vue",
            "frontend/utils/database-growth.ts");

    private static @Nullable Playwright playwright;
    private static @Nullable Browser browser;
    private static @Nullable String noBrowser = "JCLAW_PLAYWRIGHT_TEST is not set";

    private static boolean isPlaywrightTestEnabled() {
        var v = System.getenv("JCLAW_PLAYWRIGHT_TEST");
        return v != null && !v.isBlank() && !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    @BeforeAll
    static void launch() {
        if (!isPlaywrightTestEnabled()) return;
        playwright = PlaywrightBrowserTool.startDriver().playwright();
        var launched = JevRunTest.launch(playwright, List.of());
        browser = launched.browser();
        noBrowser = launched.missing();
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void seed() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword(PASSWORD);
        var today = LocalDate.now(AppClock.clock());
        commitInFreshTx(() -> {
            // An operator past the first login: on a fresh install the tour's intro dialog covers the page.
            services.ConfigService.set(controllers.ApiOnboardingController.CONFIG_KEY, "1");
            seedSample(today.minusDays(1), 550 * MB, 420 * MB, 250 * MB);
            seedSample(today, 560 * MB, 425 * MB, 253 * MB);
            return null;
        });
    }

    @Test
    void aHoveredPartOrTableRowStaysUnderThePointerInANarrowWindow() {
        Assumptions.assumeTrue(browser != null, noBrowser);
        var stale = staleCardSource();
        Assumptions.assumeTrue(stale == null, stale);

        try (var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(400, 900))) {
            var base = "http://127.0.0.1:" + Play.configuration.getProperty("http.port",
                    System.getenv().getOrDefault("PLAY_TEST_PORT", "9000"));
            var login = context.request().post(base + "/api/auth/login",
                    RequestOptions.create().setData(Map.of("username", "admin", "password", PASSWORD)));
            assertEquals(200, login.status(), login.text());

            var page = context.newPage();
            page.navigate(base + "/settings?section=database");
            page.getByTestId("db-growth-parts").waitFor();
            var readout = page.getByTestId("db-growth-parts-readout");
            assertEquals("The file today, by part. Each row below wears its part's color.", readout.innerText().strip());
            var idle = height(readout);

            for (var target : List.of(page.locator("[data-testid^=\"db-growth-part-\"]").first(),
                    page.locator("[data-testid^=\"db-growth-row-\"]").first())) {
                // The mouse itself rather than hover(): on a flickering card hover() waits for the target to hold still,
                // which it never does, and times out instead of saying what moved.
                target.scrollIntoViewIfNeeded();
                var box = Objects.requireNonNull(target.boundingBox(), "the target is not laid out");
                page.mouse().move(box.x + box.width / 2, box.y + box.height / 2);
                var heights = new LinkedHashSet<Integer>();
                var texts = new LinkedHashSet<String>();
                for (var k = 0; k < 12; k++) {
                    heights.add(height(readout));
                    texts.add(readout.innerText().strip());
                    page.waitForTimeout(80);
                }
                assertEquals(List.of(idle), List.copyOf(heights), "the readout changed height under a resting pointer");
                assertEquals(List.of("MESSAGE: 253.0 MB, 45.2% of the file"), List.copyOf(texts),
                        "the readout flickered under a resting pointer");
            }
        }
    }

    /** The first of the card's sources newer than the built SPA, as the skip reason, or null when the build is current. */
    private static @Nullable String staleCardSource() {
        var index = Play.getFile("public/spa/index.html");
        if (!index.isFile()) return "public/spa is not built; ./gradlew playFrontendSpa builds it";
        for (var source : CARD_SOURCES) {
            File file = Play.getFile(source);
            if (file.lastModified() > index.lastModified()) {
                return "public/spa predates " + source + "; ./gradlew playFrontendSpa rebuilds it";
            }
        }
        return null;
    }

    private static int height(Locator locator) {
        return (int) Math.round(Objects.requireNonNull(locator.boundingBox(), "the readout is not laid out").height);
    }

    private static void seedSample(LocalDate day, long fileBytes, long liveBytes, long messageBytes) {
        var file = new DatabaseFileSample();
        file.sampledOn = day;
        file.takenAt = day.atTime(1, 0).toInstant(ZoneOffset.UTC);
        file.fileBytes = fileBytes;
        file.liveBytes = liveBytes;
        file.reclaimableBytes = fileBytes - liveBytes;
        file.save();
        var table = new DatabaseTableSample();
        table.sampledOn = day;
        table.tableName = "MESSAGE";
        table.bytes = messageBytes;
        table.rowCount = 75_000L;
        table.save();
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
