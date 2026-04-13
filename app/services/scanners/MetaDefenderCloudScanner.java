package services.scanners;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import services.ConfigService;
import services.EventLogger;
import utils.HttpClients;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;

/**
 * MetaDefender Cloud (OPSWAT) SHA-256 hash lookup. Queries
 * {@code https://api.metadefender.com/v4/hash/{sha256}} for a verdict that
 * aggregates dozens of commercial AV engines (ESET, Kaspersky, Sophos,
 * Bitdefender, etc.).
 *
 * <p>This scanner is intentionally hash-only: no file content leaves the host.
 * It is the commercial-aggregator complement to MalwareBazaar's research-feed
 * catalog — the two sources overlap but each catches things the other misses,
 * so JClaw composes both under OR semantics (any scanner flags → violation).
 *
 * <p>Like {@link MalwareBazaarScanner}, this scanner fails <b>open</b> on any
 * error: network outage, timeout, HTTP 5xx, quota exhaustion, or malformed
 * response all return {@link Verdict#clean()} and log a warning. Scanner
 * outages must not block skill workflows. All outcomes are audit-logged via
 * {@link EventLogger}.
 *
 * <p>Free tier: 4,000 requests/day with no per-minute throttling. Get a key
 * at https://metadefender.opswat.com/ and save it under
 * {@code scanner.metadefender.apiKey}.
 */
public class MetaDefenderCloudScanner implements Scanner {

    public static final String NAME = "MetaDefender";

    @Override
    public String name() { return NAME; }

    /**
     * True if MetaDefender lookups are enabled AND an API key is configured.
     * Matches the MalwareBazaar pattern: "enabled but no key" is treated as
     * disabled, with a one-shot warning on first check.
     */
    private static final OneShotWarning MISSING_KEY_WARNING = new OneShotWarning();

    @Override
    public boolean isEnabled() {
        var enabled = "true".equalsIgnoreCase(
                ConfigService.get("scanner.metadefender.enabled", "true"));
        if (!enabled) return false;
        var key = ConfigService.get("scanner.metadefender.apiKey");
        if (key == null || key.isBlank()) {
            MISSING_KEY_WARNING.warnOnce(
                    "MetaDefender scanning is enabled but scanner.metadefender.apiKey is not set — "
                            + "this scanner is a no-op. Get a free 4,000 req/day key at "
                            + "https://metadefender.opswat.com/ and save it via the Config table.");
            return false;
        }
        return true;
    }

    /** Test-only hook to reset the one-shot warning between key-toggle tests. */
    static void resetMissingKeyWarning() {
        MISSING_KEY_WARNING.reset();
    }

    /**
     * Look up a single SHA-256 hash against MetaDefender Cloud v4. Never throws;
     * on any failure returns a clean verdict and logs a warning (fail-open).
     */
    @Override
    public Verdict lookup(String sha256) {
        var baseUrl = ConfigService.get(
                "scanner.metadefender.url", "https://api.metadefender.com/v4/");
        if (!baseUrl.endsWith("/")) baseUrl = baseUrl + "/";
        var timeoutMs = Scanner.parseInt(ConfigService.get(
                "scanner.metadefender.timeoutMs", "5000"), 5000);
        var apiKey = ConfigService.get("scanner.metadefender.apiKey");

        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "hash/" + sha256))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("apikey", apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            var response = HttpClients.GENERAL.send(request, HttpResponse.BodyHandlers.ofString());
            var status = response.statusCode();

            // 404 = hash not seen by any engine (unknown → clean, matching MalwareBazaar's hash_not_found)
            if (status == 404) {
                return Verdict.clean();
            }
            if (status < 200 || status >= 300) {
                EventLogger.warn("scanner",
                        "MetaDefender returned HTTP %d for hash %s — failing open"
                                .formatted(status, sha256.substring(0, 12)));
                return Verdict.clean();
            }

            var json = JsonParser.parseString(response.body()).getAsJsonObject();
            return parseVerdict(json, sha256);

        } catch (Exception e) {
            EventLogger.warn("scanner",
                    "MetaDefender lookup failed for hash %s: %s — failing open"
                            .formatted(sha256.substring(0, 12), e.getMessage()));
            return Verdict.clean();
        }
    }

    /**
     * Parse a MetaDefender v4 hash-info response. The relevant shape:
     * <pre>
     * {
     *   "scan_results": {
     *     "scan_all_result_i": 1,           // 0=clean, 1=infected, others=unknown
     *     "scan_details": {
     *       "ESET": { "threat_found": "Win32/EICAR" },
     *       "Kaspersky": { "threat_found": "EICAR-Test-File" },
     *       ...
     *     }
     *   }
     * }
     * </pre>
     * Verdict is malicious when {@code scan_all_result_i == 1}. The reason
     * string is built by joining the first few per-engine threat names so
     * operators can see which engines flagged the sample.
     */
    private Verdict parseVerdict(JsonObject json, String sha256) {
        if (!json.has("scan_results") || !json.get("scan_results").isJsonObject()) {
            // MetaDefender sometimes returns a sparse record with no scan_results
            // when a hash has been indexed but never actually scanned. Treat as clean.
            return Verdict.clean();
        }
        var scanResults = json.getAsJsonObject("scan_results");

        int resultCode = scanResults.has("scan_all_result_i") && !scanResults.get("scan_all_result_i").isJsonNull()
                ? scanResults.get("scan_all_result_i").getAsInt()
                : -1;

        if (resultCode != 1) {
            // 0=clean, 2+=various unknown/unscanned states — all treated as clean
            return Verdict.clean();
        }

        var threats = new ArrayList<String>();
        if (scanResults.has("scan_details") && scanResults.get("scan_details").isJsonObject()) {
            var scanDetails = scanResults.getAsJsonObject("scan_details");
            for (var engineEntry : scanDetails.entrySet()) {
                var engineResult = engineEntry.getValue();
                if (!engineResult.isJsonObject()) continue;
                var engineObj = engineResult.getAsJsonObject();
                if (engineObj.has("threat_found") && !engineObj.get("threat_found").isJsonNull()) {
                    var threat = engineObj.get("threat_found").getAsString();
                    if (!threat.isBlank()) {
                        threats.add(engineEntry.getKey() + ": " + threat);
                        if (threats.size() >= 3) break; // cap the reason string
                    }
                }
            }
        }

        var reason = threats.isEmpty() ? "MetaDefender match" : String.join(", ", threats);
        return Verdict.malicious(reason);
    }

}
