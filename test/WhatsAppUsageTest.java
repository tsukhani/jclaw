import channels.WhatsAppUsage;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.AppClock;
import utils.HttpFactories;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP-level coverage for {@link WhatsAppUsage} (JCLAW-1412) against the Graph bodies the
 * ticket measured, under a bound {@link AppClock}. Uses the {@code apiBase} overload, which
 * never consults the static override {@code WhatsAppUsageControllerTest} installs.
 */
class WhatsAppUsageTest extends UnitTest {

    private static final String BASE = "http://graph.invalid/v21.0/";
    private static final String TOKEN = "tok-SECRET-1412";
    private static final String DISPLAY = "+1 555-000-1111";
    private static final String DIGITS = "15550001111";

    /** 2026-10-07T02:28:06Z, the instant the ticket's query ended. */
    private static final Instant MEASURED_NOW = Instant.ofEpochSecond(1791340086L);
    /** 2026-09-30T00:00:00Z, a day before the first of October. */
    private static final long OCTOBER_WINDOW_START = 1790726400L;

    private static final String HEALTH = """
            {"health_status":{"entities":[
              {"entity_type":"PHONE_NUMBER","id":"111"},
              {"entity_type":"WABA","id":"222"},
              {"entity_type":"APP","id":"444"}]},"id":"111"}""";

    private static final String MEASURED = """
            {"id":"222","pricing_analytics":{"data":[{"data_points":[
              {"start":1791270000,"end":1791356400,"phone_number":"15550001111","pricing_type":"FREE_CUSTOMER_SERVICE","pricing_category":"SERVICE","volume":2,"cost":0},
              {"start":1791270000,"end":1791356400,"phone_number":"15550001111","pricing_type":"REGULAR","pricing_category":"UTILITY","volume":2,"cost":0}]}]}}""";

    private static final String NO_DIMENSIONS = """
            {"id":"222","pricing_analytics":{"data":[{"data_points":[{"start":1791270000,"end":1791356400,"volume":4,"cost":0}]}]}}""";

    private static final String MONTHLY_400 = """
            {"error":{"message":"Invalid parameter","code":100,"type":"OAuthException","error_subcode":2388087,"is_transient":false,
              "error_user_msg":"Too small time window to get monthly granularity data.","error_user_title":"Insight time window for monthly data invalid","fbtrace_id":"..."}}""";

    private static final String HEALTH_PATH = "GET /v21.0/111";
    private static final String WABA_PATH = "GET /v21.0/222";

    private record Canned(int code, String body) {}

    private final Map<String, Canned> answers = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private boolean failTransport;

    @Test
    void measuredBodyCountsTheServiceReplyAndNotTheUtilityMessage() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, MEASURED));

        var usage = read(MEASURED_NOW);

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 10), 2, 0), usage);
        assertEquals(2, requests.size());
        var query = requests.get(1);
        assertEquals("/v21.0/222", query.url().encodedPath());
        assertEquals("pricing_analytics.start(" + OCTOBER_WINDOW_START + ").end(1791340086)"
                        + ".granularity(DAILY).dimensions(PHONE,PRICING_CATEGORY,PRICING_TYPE)",
                query.url().queryParameter("fields"));
        assertEquals("Bearer " + TOKEN, query.header("Authorization"));
    }

    @Test
    void sumsFreeAndBilledServiceRepliesForThisNumberOnly() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, body(
                point(1791270000L, DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 5),
                point(1791183600L, DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 400),
                point(1791270000L, DIGITS, "SERVICE", "REGULAR", 3),
                point(1791270000L, DIGITS, "SERVICE", "FREE_ENTRY_POINT", 7),
                point(1791270000L, DIGITS, "MARKETING", "REGULAR", 11),
                point(1791270000L, DIGITS, "UTILITY", "FREE_CUSTOMER_SERVICE", 17),
                point(1791270000L, "15550002222", "SERVICE", "FREE_CUSTOMER_SERVICE", 13),
                point(1791270000L, "15550002222", "SERVICE", "REGULAR", 19))));

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 10), 408, 3), read(MEASURED_NOW));
    }

    @Test
    void sumsAcrossSeveralDataSeries() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, """
                {"pricing_analytics":{"data":[{"data_points":[%s]},{"data_points":[%s]}]}}"""
                .formatted(point(1791270000L, DIGITS, "SERVICE", "REGULAR", 4),
                        point(1791183600L, DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 6))));

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 10), 10, 4), read(MEASURED_NOW));
    }

    // ===== month assignment around a boundary =====

    @Test
    void accountAtUtcPlus8CountsItsOwnOctoberDays() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, body(
                point(utc("2026-09-29T16:00:00Z"), DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 100),
                point(utc("2026-09-30T16:00:00Z"), DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 1),
                point(utc("2026-10-30T16:00:00Z"), DIGITS, "SERVICE", "REGULAR", 2))));

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 10), 3, 2),
                read(Instant.parse("2026-10-31T15:00:00Z")));
    }

    @Test
    void accountAtUtcPlus8StartsNovemberAtItsLocalMidnight() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, body(
                point(utc("2026-10-30T16:00:00Z"), DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 100),
                point(utc("2026-10-31T16:00:00Z"), DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 4))));

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 11), 4, 0),
                read(Instant.parse("2026-11-01T02:00:00Z")));
        assertEquals(String.valueOf(utc("2026-10-31T00:00:00Z")),
                requests.get(1).url().queryParameter("fields").replaceAll("^pricing_analytics\\.start\\((\\d+)\\).*", "$1"));
    }

    @Test
    void accountAtUtcMinus7CountsItsOwnOctoberDays() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, body(
                point(utc("2026-09-30T07:00:00Z"), DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 100),
                point(utc("2026-10-01T07:00:00Z"), DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 1),
                point(utc("2026-10-31T07:00:00Z"), DIGITS, "SERVICE", "REGULAR", 2))));

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 10), 3, 2),
                read(Instant.parse("2026-10-31T23:00:00Z")));
    }

    @Test
    void accountAtUtcMinus7StartsNovemberAtItsLocalMidnight() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, body(
                point(utc("2026-10-31T07:00:00Z"), DIGITS, "SERVICE", "FREE_CUSTOMER_SERVICE", 100),
                point(utc("2026-11-01T07:00:00Z"), DIGITS, "SERVICE", "REGULAR", 5))));

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 11), 5, 5),
                read(Instant.parse("2026-11-01T10:00:00Z")));
    }

    // ===== zero, not unknown =====

    @Test
    void noPricingAnalyticsKeyIsZero() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, "{\"id\":\"222\"}"));

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 10), 0, 0), read(MEASURED_NOW));
    }

    @Test
    void noMatchingDataPointsIsZero() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, NO_DIMENSIONS));

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 10), 0, 0), read(MEASURED_NOW));
    }

    @Test
    void anAccountNamingNoAppStillReadsUsage() {
        answers.put(HEALTH_PATH, new Canned(200,
                "{\"health_status\":{\"entities\":[{\"entity_type\":\"WABA\",\"id\":\"222\"}]},\"id\":\"111\"}"));
        answers.put(WABA_PATH, new Canned(200, MEASURED));

        assertEquals(new WhatsAppUsage.Counted(YearMonth.of(2026, 10), 2, 0), read(MEASURED_NOW));
    }

    // ===== unknown =====

    @Test
    void non200IsUnknownWithGraphsMessage() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(400, MONTHLY_400));

        assertUnknownContaining(read(MEASURED_NOW), "Invalid parameter");
    }

    @Test
    void unparseableBodyIsUnknown() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, "not json"));

        assertUnknownContaining(read(MEASURED_NOW), "unexpected Graph response");
    }

    @Test
    void malformedPricingAnalyticsIsUnknown() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(WABA_PATH, new Canned(200, "{\"pricing_analytics\":{}}"));
        assertUnknownContaining(read(MEASURED_NOW), "unexpected Graph response");

        answers.put(WABA_PATH, new Canned(200, body(
                "{\"start\":1791270000,\"phone_number\":\"15550001111\",\"pricing_type\":\"REGULAR\","
                        + "\"pricing_category\":\"SERVICE\",\"volume\":\"many\"}")));
        assertUnknownContaining(read(MEASURED_NOW), "unexpected Graph response");
    }

    @Test
    void noWabaIdIsUnknownWithNoAnalyticsCall() {
        answers.put(HEALTH_PATH, new Canned(200,
                "{\"health_status\":{\"entities\":[{\"entity_type\":\"APP\",\"id\":\"444\"}]},\"id\":\"111\"}"));

        assertUnknownContaining(read(MEASURED_NOW), "Business Account");
        assertEquals(1, requests.size());
    }

    @Test
    void healthStatusErrorIsUnknownWithNoAnalyticsCall() {
        answers.put(HEALTH_PATH, new Canned(403,
                "{\"error\":{\"message\":\"(#200) Permissions error\",\"code\":200}}"));

        assertUnknownContaining(read(MEASURED_NOW), "(#200) Permissions error");
        assertEquals(1, requests.size());
    }

    @Test
    void transportErrorIsUnknownAndDoesNotThrow() {
        failTransport = true;

        assertUnknownContaining(read(MEASURED_NOW), "Graph request failed");
    }

    @Test
    void missingInputsAreUnknownWithoutHttp() {
        var month = YearMonth.of(2026, 10);
        run(MEASURED_NOW, () -> {
            assertTrue(WhatsAppUsage.read("", TOKEN, DISPLAY, BASE) instanceof WhatsAppUsage.Unknown);
            assertTrue(WhatsAppUsage.read("111", " ", DISPLAY, BASE) instanceof WhatsAppUsage.Unknown);
            assertTrue(WhatsAppUsage.read("111", TOKEN, "+ -", BASE) instanceof WhatsAppUsage.Unknown);
            assertEquals(month, ((WhatsAppUsage.Unknown) WhatsAppUsage.read(null, null, null, BASE)).month());
            return null;
        });
        assertEquals(0, requests.size());
    }

    private WhatsAppUsage.Usage read(Instant now) {
        return run(now, () -> WhatsAppUsage.read("111", TOKEN, DISPLAY, BASE));
    }

    private void assertUnknownContaining(WhatsAppUsage.Usage usage, String fragment) {
        assertTrue(usage instanceof WhatsAppUsage.Unknown, "expected Unknown, got: " + usage);
        var reason = ((WhatsAppUsage.Unknown) usage).reason();
        assertTrue(reason.contains(fragment), () -> "reason '" + reason + "' should contain '" + fragment + "'");
        assertFalse(reason.contains(TOKEN), "the reason must never carry the access token");
    }

    private static long utc(String instant) {
        return Instant.parse(instant).getEpochSecond();
    }

    private static String point(long start, String phone, String category, String type, long volume) {
        return """
                {"start":%d,"end":%d,"phone_number":"%s","pricing_type":"%s","pricing_category":"%s","volume":%d,"cost":0}"""
                .formatted(start, start + 86_400, phone, type, category, volume);
    }

    private static String body(String... points) {
        return "{\"id\":\"222\",\"pricing_analytics\":{\"data\":[{\"data_points\":[" + String.join(",", points) + "]}]}}";
    }

    /** Answers each request by {@code METHOD path} from {@link #answers}; an unregistered one is a 599. */
    private <T> T run(Instant now, Supplier<T> body) {
        Interceptor canned = chain -> {
            var request = chain.request();
            requests.add(request);
            if (failTransport) {
                throw new IOException("connection reset by graph.invalid");
            }
            var answer = answers.getOrDefault(request.method() + " " + request.url().encodedPath(),
                    new Canned(599, "unregistered " + request.url().encodedPath()));
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(answer.code())
                    .message("canned")
                    .body(ResponseBody.create(answer.body(), null))
                    .build();
        };
        var client = new OkHttpClient.Builder().addInterceptor(canned).build();
        return AppClock.callWith(Clock.fixed(now, ZoneOffset.UTC), () -> HttpFactories.callWith(client, body));
    }
}
