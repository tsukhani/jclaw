package services.scanners;

import com.google.gson.JsonObject;
import okhttp3.Request;
import utils.HttpKeys;

import java.util.ArrayList;

/**
 * VirusTotal SHA-256 hash lookup. Queries
 * {@code https://www.virustotal.com/api/v3/files/{sha256}} for a verdict that
 * aggregates ~70 commercial and open-source AV engines.
 *
 * <p>Like the other scanners, this is hash-only: no file content leaves the host.
 * VirusTotal sits alongside MalwareBazaar (research feed) and MetaDefender Cloud
 * (commercial aggregator) — the three sources overlap but each catches things
 * the others miss, so JClaw composes all of them under OR semantics.
 *
 * <p>Fails <b>open</b> on any error: network outage, timeout, HTTP 4xx/5xx,
 * quota exhaustion, or malformed response all return {@link Verdict#clean()}
 * and log a warning. Scanner outages must never block skill workflows. All
 * outcomes are audit-logged.
 *
 * <p>Free public API: 500 requests/day at 4 req/minute. Get a key at
 * https://www.virustotal.com/gui/join-us and save it under
 * {@code scanner.virustotal.apiKey}.
 */
public class VirusTotalScanner extends ConfiguredHashScanner {

    private static final String NAME = "VirusTotal";

    private static final OneShotWarning MISSING_KEY_WARNING = new OneShotWarning();

    public VirusTotalScanner() {
        this(ScannerDependencies.production());
    }

    public VirusTotalScanner(ScannerDependencies dependencies) {
        super(NAME,
                "scanner.virustotal.enabled",
                "scanner.virustotal.apiKey",
                MISSING_KEY_WARNING,
                "VirusTotal scanning is enabled but scanner.virustotal.apiKey is not set — "
                        + "this scanner is a no-op. Get a free 500 req/day key at "
                        + "https://www.virustotal.com/gui/join-us and save it via the Config table.",
                dependencies);
    }

    /** Test-only hook to reset the one-shot warning between key-toggle tests. */
    static void resetMissingKeyWarning() {
        new VirusTotalScanner().resetWarningForTest();
    }

    @Override
    public Verdict lookup(String sha256) {
        var baseUrl = config().get("scanner.virustotal.url", "https://www.virustotal.com/api/v3/");
        baseUrl = ensureTrailingSlash(baseUrl);
        var timeoutMs = config().getInt("scanner.virustotal.timeoutMs", "5000", 5000);
        var apiKey = config().get("scanner.virustotal.apiKey");

        var request = new Request.Builder()
                .url(baseUrl + "files/" + sha256)
                .header("x-apikey", apiKey)
                .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                .get()
                .build();
        return sendJsonLookup(sha256, request, timeoutMs, true, this::parseVerdict);
    }

    /**
     * Parse a VirusTotal v3 file-info response. The relevant shape:
     * <pre>
     * {
     *   "data": {
     *     "attributes": {
     *       "last_analysis_stats": {
     *         "malicious": 42, "suspicious": 0, "undetected": 30, ...
     *       },
     *       "last_analysis_results": {
     *         "Kaspersky": { "category": "malicious", "result": "Trojan.Win32.Foo" },
     *         "ESET-NOD32": { "category": "malicious", "result": "a variant of Win32/Bar" },
     *         ...
     *       }
     *     }
     *   }
     * }
     * </pre>
     * Verdict is malicious when {@code last_analysis_stats.malicious > 0}. The
     * reason names up to three flagging engines plus the malicious/total count
     * so operators can see how widely the sample is detected.
     */
    private Verdict parseVerdict(JsonObject json) {
        if (!json.has("data") || !json.get("data").isJsonObject()) {
            return Verdict.clean();
        }
        var data = json.getAsJsonObject("data");
        if (!data.has("attributes") || !data.get("attributes").isJsonObject()) {
            return Verdict.clean();
        }
        var attributes = data.getAsJsonObject("attributes");

        int malicious = 0;
        int total = 0;
        if (attributes.has("last_analysis_stats") && attributes.get("last_analysis_stats").isJsonObject()) {
            var stats = attributes.getAsJsonObject("last_analysis_stats");
            for (var statEntry : stats.entrySet()) {
                if (statEntry.getValue().isJsonPrimitive() && statEntry.getValue().getAsJsonPrimitive().isNumber()) {
                    int count = statEntry.getValue().getAsInt();
                    total += count;
                    if ("malicious".equals(statEntry.getKey())) {
                        malicious = count;
                    }
                }
            }
        }

        if (malicious <= 0) {
            return Verdict.clean();
        }

        var threats = new ArrayList<String>();
        if (attributes.has("last_analysis_results") && attributes.get("last_analysis_results").isJsonObject()) {
            var results = attributes.getAsJsonObject("last_analysis_results");
            for (var engineEntry : results.entrySet()) {
                if (!engineEntry.getValue().isJsonObject()) continue;
                var engineObj = engineEntry.getValue().getAsJsonObject();
                var category = engineObj.has("category") && !engineObj.get("category").isJsonNull()
                        ? engineObj.get("category").getAsString() : "";
                if (!"malicious".equals(category)) continue;
                var threat = engineObj.has("result") && !engineObj.get("result").isJsonNull()
                        ? engineObj.get("result").getAsString() : "";
                if (threat.isBlank()) continue;
                threats.add(engineEntry.getKey() + ": " + threat);
                if (threats.size() >= 3) break;
            }
        }

        var prefix = total > 0
                ? "VirusTotal %d/%d engines flagged".formatted(malicious, total)
                : "VirusTotal %d engines flagged".formatted(malicious);
        return Verdict.malicious(
                threats.isEmpty() ? prefix : prefix + " — " + String.join(", ", threats));
    }

}
