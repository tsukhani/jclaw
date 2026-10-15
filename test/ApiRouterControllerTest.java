import controllers.ApiRouterController.CloudUsageResponse;
import controllers.ApiRouterController.UsageBucket;
import llm.routing.SubscriptionUsage.Bucket;
import llm.routing.SubscriptionUsage.Range;
import llm.routing.SubscriptionUsage.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-1449: {@code GET /api/router/usage/ollama-cloud}. Only the refusals go over HTTP: a valid
 * range reads ollama.com with whatever {@code ollama-cloud} key another class has set, and a Play
 * request thread is out of reach of a canned transport. {@code SubscriptionUsageTest} covers the read.
 */
class ApiRouterControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-123";

    @BeforeEach
    void login() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"%s\"}".formatted(TEST_PASSWORD)));
    }

    @AfterEach
    void logout() {
        AuthFixture.clearAdminPassword();
    }

    @Test
    void aRangeOllamaWouldRefuseIsA400() {
        for (var url : List.of("/api/router/usage/ollama-cloud", "/api/router/usage/ollama-cloud?range=1h",
                "/api/router/usage/ollama-cloud?range=7D")) {
            var response = GET(url);
            assertStatus(400, response);
            assertTrue(getContent(response).contains("range must be 24h, 7d, or 30d"), () -> url + ": " + getContent(response));
        }
    }

    @Test
    void noReadingIsAnEmptySnapshotForTheRequestedRange() {
        var view = CloudUsageResponse.of("ollama-cloud", Range.MONTH, null);
        assertEquals(new CloudUsageResponse("ollama-cloud", "30d", 0, List.of(), null), view);
    }

    @Test
    void aReadingCarriesItsRangeTotalBucketsAndFetchTime() {
        var fetchedAt = Instant.parse("2026-10-15T13:17:00Z");
        var snapshot = new Snapshot(Map.of(), "24h", 9, List.of(
                new Bucket(Instant.parse("2026-10-15T11:00:00Z"), Instant.parse("2026-10-15T12:00:00Z"), 7, false),
                new Bucket(Instant.parse("2026-10-15T12:00:00Z"), Instant.parse("2026-10-15T12:30:00Z"), 2, true)),
                fetchedAt);
        var view = CloudUsageResponse.of("ollama-cloud", Range.DAY, snapshot);
        assertEquals("24h", view.range());
        assertEquals(9, view.total());
        assertEquals(List.of(
                new UsageBucket("2026-10-15T11:00:00Z", "2026-10-15T12:00:00Z", 7, false),
                new UsageBucket("2026-10-15T12:00:00Z", "2026-10-15T12:30:00Z", 2, true)), view.buckets());
        assertEquals("2026-10-15T13:17:00Z", view.fetchedAt());
    }
}
