package services.scanners;

import com.google.gson.JsonObject;
import okhttp3.Request;
import utils.HttpKeys;

import java.util.List;
import com.google.gson.JsonElement;

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
 * outages must not block skill workflows. All outcomes are audit-logged.
 *
 * <p>Free tier: 4,000 requests/day with no per-minute throttling. Get a key
 * at https://metadefender.opswat.com/ and save it under
 * {@code scanner.metadefender.apiKey}.
 */
public class MetaDefenderCloudScanner extends ConfiguredHashScanner {

    private static final String NAME = "MetaDefender";

    private static final String FIELD_SCAN_RESULTS = "scan_results";
    private static final String FIELD_SCAN_ALL_RESULT_I = "scan_all_result_i";
    private static final String FIELD_SCAN_DETAILS = "scan_details";
    private static final String FIELD_THREAT_FOUND = "threat_found";

    private static final OneShotWarning MISSING_KEY_WARNING = new OneShotWarning();

    public MetaDefenderCloudScanner() {
        this(ScannerDependencies.production());
    }

    public MetaDefenderCloudScanner(ScannerDependencies dependencies) {
        super(NAME,
                "scanner.metadefender.enabled",
                "scanner.metadefender.apiKey",
                MISSING_KEY_WARNING,
                "MetaDefender scanning is enabled but scanner.metadefender.apiKey is not set — "
                        + "this scanner is a no-op. Get a free 4,000 req/day key at "
                        + "https://metadefender.opswat.com/ and save it via the Config table.",
                dependencies);
    }

    /** Test-only hook to reset the one-shot warning between key-toggle tests. */
    static void resetMissingKeyWarning() {
        new MetaDefenderCloudScanner().resetWarningForTest();
    }

    /**
     * Look up a single SHA-256 hash against MetaDefender Cloud v4. Never throws;
     * on any failure returns a clean verdict and logs a warning (fail-open).
     */
    @Override
    public Verdict lookup(String sha256) {
        var baseUrl = config().get("scanner.metadefender.url", "https://api.metadefender.com/v4/");
        baseUrl = ensureTrailingSlash(baseUrl);
        var timeoutMs = config().getInt("scanner.metadefender.timeoutMs", "5000", 5000);
        var apiKey = config().get("scanner.metadefender.apiKey");

        var request = new Request.Builder()
                .url(baseUrl + "hash/" + sha256)
                .header("apikey", apiKey)
                .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                .get()
                .build();
        return sendJsonLookup(sha256, request, timeoutMs, true, this::parseVerdict);
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
    private Verdict parseVerdict(JsonObject json) {
        if (!json.has(FIELD_SCAN_RESULTS) || !json.get(FIELD_SCAN_RESULTS).isJsonObject()) {
            // MetaDefender sometimes returns a sparse record with no scan_results
            // when a hash has been indexed but never actually scanned. Treat as clean.
            return Verdict.clean();
        }
        var scanResults = json.getAsJsonObject(FIELD_SCAN_RESULTS);

        int resultCode = extractResultCode(scanResults);
        if (resultCode != 1) {
            // 0=clean, 2+=various unknown/unscanned states — all treated as clean
            return Verdict.clean();
        }

        var threats = collectThreats(scanResults);
        var reason = threats.isEmpty() ? "MetaDefender match" : String.join(", ", threats);
        return Verdict.malicious(reason);
    }

    private static int extractResultCode(JsonObject scanResults) {
        if (!scanResults.has(FIELD_SCAN_ALL_RESULT_I) || scanResults.get(FIELD_SCAN_ALL_RESULT_I).isJsonNull()) {
            return -1;
        }
        return scanResults.get(FIELD_SCAN_ALL_RESULT_I).getAsInt();
    }

    /** Walk scan_details, collecting up to 3 "engine: threat" labels for the reason string. */
    private static List<String> collectThreats(JsonObject scanResults) {
        if (!scanResults.has(FIELD_SCAN_DETAILS) || !scanResults.get(FIELD_SCAN_DETAILS).isJsonObject()) {
            return List.of();
        }
        return collectLabels(scanResults.getAsJsonObject(FIELD_SCAN_DETAILS),
                MetaDefenderCloudScanner::extractThreatLabel, 3);
    }

    /** Returns "engine: threat" when the engine reported a non-blank threat, null otherwise. */
    private static String extractThreatLabel(String engineName, JsonElement engineResult) {
        if (!engineResult.isJsonObject()) return null;
        var engineObj = engineResult.getAsJsonObject();
        var threat = optString(engineObj, FIELD_THREAT_FOUND, "");
        if (threat.isBlank()) return null;
        return engineName + ": " + threat;
    }

}
