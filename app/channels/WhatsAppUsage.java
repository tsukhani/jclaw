package channels;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import utils.AppClock;
import utils.JsonArgs;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * This month's replies for one Cloud API number, as Meta's pricing analytics count them
 * (JCLAW-1412). Meta bills service messages past {@link #FREE_SERVICE_ALLOWANCE} a month per
 * number; reading Meta's own figure keeps the bar on what Meta bills rather than a local tally.
 *
 * <p>The query is DAILY: MONTHLY answers no data or a 400 for a month still in progress.
 * Daily buckets start at midnight in the account's own timezone, which the analytics do not
 * name, so a bucket counts toward the UTC month of its start plus twelve hours — right for any
 * zone from UTC-12 to UTC+12. The window opens a day before the first of the UTC month so a
 * zone ahead of UTC has its first local day in it.
 */
public final class WhatsAppUsage {

    private WhatsAppUsage() {}

    /** Free service replies per number per month; Meta's pricing page, read 2026-10-07. */
    public static final int FREE_SERVICE_ALLOWANCE = 1000;

    private static final Duration BUCKET_MIDPOINT = Duration.ofHours(12);

    /** Outcome of a usage read. */
    public sealed interface Usage permits Counted, Unknown {}

    /** {@code replies} is every SERVICE reply delivered this month; {@code billed} the REGULAR part of it. */
    public record Counted(YearMonth month, long replies, long billed) implements Usage {}

    /** Meta's figure could not be read; {@code reason} never carries the token. */
    public record Unknown(YearMonth month, String reason) implements Usage {}

    /** Usage read seam: phone number id, access token, display number. */
    @FunctionalInterface
    public interface Reader {
        Usage read(String phoneNumberId, String accessToken, String displayNumber);
    }

    /**
     * Test override honoured by {@link #read(String, String, String)}. Not replaceable by
     * {@code HttpFactories.runWith}: the callers are {@code FunctionalTest}s driving a
     * controller, and a {@code ScopedValue} binding does not reach Play's request thread.
     */
    private static volatile @Nullable Reader readOverride;

    /** Install a canned usage read for tests. */
    public static void installForTest(Reader reader) {
        readOverride = reader;
    }

    /** Remove the test override, restoring live Graph reads. */
    public static void clearForTest() {
        readOverride = null;
    }

    /** Read from the live Graph API, honouring an {@link #installForTest} override. Never throws. */
    public static Usage read(String phoneNumberId, String accessToken, String displayNumber) {
        var override = readOverride;
        if (override != null) {
            return override.read(phoneNumberId, accessToken, displayNumber);
        }
        return read(phoneNumberId, accessToken, displayNumber, WhatsAppCloudApiProbe.API_BASE);
    }

    /** Read from {@code apiBase}; never consults the test override. Never throws. */
    public static Usage read(@Nullable String phoneNumberId, @Nullable String accessToken,
                             @Nullable String displayNumber, String apiBase) {
        var now = AppClock.now();
        var month = YearMonth.from(now.atOffset(ZoneOffset.UTC));
        var digits = displayNumber == null ? "" : displayNumber.replaceAll("\\D", "");
        if (phoneNumberId == null || phoneNumberId.isBlank()
                || accessToken == null || accessToken.isBlank() || digits.isEmpty()) {
            return new Unknown(month, "phoneNumberId, accessToken and a display number are required");
        }
        var lookup = WhatsAppSubscription.resolveIds(phoneNumberId, accessToken, apiBase);
        if (lookup instanceof WhatsAppSubscription.NotFound(String reason)) {
            return new Unknown(month, reason);
        }
        var wabaId = ((WhatsAppSubscription.Found) lookup).ids().wabaId();
        long start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).minusDays(1).toEpochSecond();
        var url = apiBase + wabaId + "?fields=pricing_analytics.start(" + start + ").end(" + now.getEpochSecond()
                + ").granularity(DAILY).dimensions(PHONE,PRICING_CATEGORY,PRICING_TYPE)";
        WhatsAppSubscription.GraphResponse response;
        try {
            response = WhatsAppSubscription.send(WhatsAppSubscription.get(url, accessToken));
        } catch (Exception e) {
            return new Unknown(month, WhatsAppSubscription.transportError(e));
        }
        if (response.code() != 200) {
            return new Unknown(month, WhatsAppCloudApiProbe.graphError(response.body(), response.code()));
        }
        try {
            return count(JsonParser.parseString(response.body()).getAsJsonObject(), month, digits);
        } catch (RuntimeException _) {
            return new Unknown(month, WhatsAppSubscription.unexpected(response.body()));
        }
    }

    /** Throws on a body that is not the measured shape; a missing {@code pricing_analytics} is no traffic. */
    private static Usage count(JsonObject body, YearMonth month, String digits) {
        var analytics = body.get("pricing_analytics");
        if (analytics == null) {
            return new Counted(month, 0, 0);
        }
        long replies = 0;
        long billed = 0;
        for (JsonElement series : analytics.getAsJsonObject().getAsJsonArray("data")) {
            for (JsonElement element : series.getAsJsonObject().getAsJsonArray("data_points")) {
                var point = element.getAsJsonObject();
                var type = JsonArgs.optNonBlankString(point, "pricing_type");
                boolean isBilled = "REGULAR".equals(type);
                if (!digits.equals(JsonArgs.optNonBlankString(point, "phone_number"))
                        || !"SERVICE".equals(JsonArgs.optNonBlankString(point, "pricing_category"))
                        || !(isBilled || "FREE_CUSTOMER_SERVICE".equals(type))) {
                    continue;
                }
                var bucketStart = Instant.ofEpochSecond(point.get("start").getAsLong());
                if (!month.equals(YearMonth.from(bucketStart.plus(BUCKET_MIDPOINT).atOffset(ZoneOffset.UTC)))) {
                    continue;
                }
                long volume = point.get("volume").getAsLong();
                replies += volume;
                if (isBilled) billed += volume;
            }
        }
        return new Counted(month, replies, billed);
    }
}
