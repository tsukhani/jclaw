package services.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders a discovered catalog by a provider's configured leaderboard (JCLAW-999, moved
 * out of {@code ModelDiscoveryService}). The leaderboard URL comes from
 * {@code provider.<name>.leaderboardUrl}; a missing, rejected or unreachable one degrades
 * to a name sort, never an error, because the ranking is a convenience for the picker.
 */
public final class LeaderboardRanker {

    private LeaderboardRanker() {}

    private static final Pattern HREF_PATTERN = Pattern.compile(
            "href=\"/([a-zA-Z0-9][-a-zA-Z0-9._]*/[a-zA-Z0-9][-a-zA-Z0-9._:]*?)\"");
    private static final Pattern USAGE_PATTERN = Pattern.compile(
            "\"([a-zA-Z0-9_-]+/[a-zA-Z0-9._:-]+)\"\\s*:\\s*\\d");

    private static final int LEADERBOARD_TIMEOUT_SECONDS = 10;

    /**
     * Fetch the configured leaderboard for {@code providerName} (if any),
     * apply rankings to {@code models}, then sort by {@code leaderboardRank}
     * with name fallback. Shared by the OpenAI-compat and Ollama-native
     * discovery strategies.
     */
    public static void applyLeaderboardAndSort(String providerName, List<Map<String, Object>> models) {
        var leaderboardUrl = ConfigService.get("provider." + providerName + ".leaderboardUrl");
        var rankings = fetchLeaderboard(leaderboardUrl);
        if (!rankings.isEmpty()) {
            applyRankings(models, rankings);
        }
        sortByRankThenName(models);
    }

    /** Sort without a leaderboard lookup — the LM Studio native path has no ranking source. */
    public static void sortByRankThenName(List<Map<String, Object>> models) {
        models.sort(LeaderboardRanker::compareByRankThenName);
    }

    /**
     * Comparator: rank-bearing entries first (ascending rank), then everything
     * else by name (id fallback), case-insensitive.
     */
    private static int compareByRankThenName(Map<String, Object> a, Map<String, Object> b) {
        var rankA = a.get(ModelCatalogParser.KEY_LEADERBOARD_RANK);
        var rankB = b.get(ModelCatalogParser.KEY_LEADERBOARD_RANK);
        if (rankA != null && rankB != null) return Integer.compare((int) rankA, (int) rankB);
        if (rankA != null) return -1;
        if (rankB != null) return 1;
        var nameA = Objects.toString(a.get(ModelCatalogParser.KEY_NAME), Objects.toString(a.get(ModelCatalogParser.KEY_ID), ""));
        var nameB = Objects.toString(b.get(ModelCatalogParser.KEY_NAME), Objects.toString(b.get(ModelCatalogParser.KEY_ID), ""));
        return nameA.compareToIgnoreCase(nameB);
    }

    @SuppressWarnings("java:S1141") // Inner try isolates JSON-parse fallback to HTML; refactor would require returning a sentinel from a helper
    static List<String> fetchLeaderboard(@Nullable String leaderboardUrl) {
        if (leaderboardUrl == null || leaderboardUrl.isBlank()) return List.of();

        // JCLAW-778: the leaderboard URL is config-set (provider.<name>.leaderboardUrl).
        // Reject a metadata / link-local target before connecting; the leaderboard
        // is optional, so a rejected URL degrades to "no rankings".
        try {
            SsrfGuard.assertProviderUrlSafe(leaderboardUrl);
        } catch (SecurityException e) {
            EventLogger.warn("provider", "Leaderboard URL rejected by SSRF guard: %s".formatted(e.getMessage()));
            return List.of();
        }

        try {
            var req = new Request.Builder()
                    .url(leaderboardUrl)
                    .header(HttpKeys.ACCEPT, "text/html,application/json")
                    .get()
                    .build();
            var call = HttpFactories.generalGuarded().newCall(req);
            call.timeout().timeout(LEADERBOARD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            String body;
            try (var response = call.execute()) {
                if (response.code() != 200) return List.of();
                body = response.body().string();
            }

            try {
                var json = JsonParser.parseString(body);
                if (json.isJsonArray()) {
                    return parseJsonLeaderboard(json.getAsJsonArray());
                }
                if (json.isJsonObject() && json.getAsJsonObject().has(ModelCatalogParser.FIELD_DATA)) {
                    return parseJsonLeaderboard(json.getAsJsonObject().getAsJsonArray(ModelCatalogParser.FIELD_DATA));
                }
            } catch (JsonSyntaxException _) { /* not JSON → fall through to HTML parse */ }

            return parseHtmlLeaderboard(body);

        } catch (Exception e) {
            EventLogger.warn("provider", "Leaderboard fetch failed: %s".formatted(e.getMessage()));
            return List.of();
        }
    }

    private static List<String> parseJsonLeaderboard(JsonArray array) {
        var result = new ArrayList<String>();
        for (var el : array) {
            if (!el.isJsonObject()) continue;
            var obj = el.getAsJsonObject();
            var id = ModelCatalogParser.getString(obj, ModelCatalogParser.KEY_ID, ModelCatalogParser.getString(obj, "slug", ModelCatalogParser.getString(obj, ModelCatalogParser.KEY_NAME, "")));
            if (!id.isBlank()) result.add(id.toLowerCase());
        }
        return result;
    }

    private static final String[] HREF_PREFIX_BLOCKLIST =
            {"docs/", "api/", "_next/", "images/", "settings/"};
    private static final String[] USAGE_PREFIX_BLOCKLIST = {"api/", "docs/", "_next/"};

    static List<String> parseHtmlLeaderboard(String html) {
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();

        scanLeaderboardMatches(HREF_PATTERN.matcher(html), HREF_PREFIX_BLOCKLIST, seen, result);
        if (result.isEmpty()) {
            scanLeaderboardMatches(USAGE_PATTERN.matcher(html), USAGE_PREFIX_BLOCKLIST, seen, result);
        }

        return result;
    }

    /**
     * Walk a leaderboard regex matcher, lower-case + strip-variant each slug,
     * filter out blocklisted prefixes, and append deduped survivors to
     * {@code out}. Shared by the {@code href} and {@code usage} passes — the
     * only difference is the blocklist set.
     */
    private static void scanLeaderboardMatches(
            Matcher matcher,
            String[] prefixBlocklist,
            HashSet<String> seen,
            ArrayList<String> out) {
        while (matcher.find()) {
            var slug = matcher.group(1).toLowerCase();
            var baseSlug = ModelCatalogParser.stripVariant(slug);
            if (hasAnyPrefix(baseSlug, prefixBlocklist)) continue;
            if (seen.add(baseSlug)) out.add(baseSlug);
        }
    }

    private static boolean hasAnyPrefix(String s, String[] prefixes) {
        for (var p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    static void applyRankings(List<Map<String, Object>> models, List<String> rankings) {
        // stripVersionSuffix(ranked) is invariant across the inner loop's
        // model iterations, so precompute it once rather than per model×ranking.
        var strippedRankings = rankings.stream().map(ModelCatalogParser::stripVersionSuffix).toList();
        for (var model : models) {
            var modelId = Objects.toString(model.get(ModelCatalogParser.KEY_ID), "").toLowerCase();
            var modelBase = ModelCatalogParser.stripVariant(modelId);
            var modelBaseStripped = ModelCatalogParser.stripVersionSuffix(modelBase);
            int bestRank = Integer.MAX_VALUE;

            for (int i = 0; i < rankings.size(); i++) {
                var ranked = rankings.get(i);
                if (modelBase.equals(ranked) || modelId.equals(ranked)
                        || modelBaseStripped.equals(strippedRankings.get(i))) {
                    if (i + 1 < bestRank) bestRank = i + 1;
                    break;
                }
            }

            if (bestRank < Integer.MAX_VALUE) {
                model.put(ModelCatalogParser.KEY_LEADERBOARD_RANK, bestRank);
            }
        }
    }
}
