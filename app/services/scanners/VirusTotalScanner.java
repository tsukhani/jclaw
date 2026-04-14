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
 * outcomes are audit-logged via {@link EventLogger}.
 *
 * <p>Free public API: 500 requests/day at 4 req/minute. Get a key at
 * https://www.virustotal.com/gui/join-us and save it under
 * {@code scanner.virustotal.apiKey}.
 */
public class VirusTotalScanner implements Scanner {

    public static final String NAME = "VirusTotal";

    @Override
    public String name() { return NAME; }

    private static final OneShotWarning MISSING_KEY_WARNING = new OneShotWarning();

    @Override
    public boolean isEnabled() {
        var enabled = "true".equalsIgnoreCase(
                ConfigService.get("scanner.virustotal.enabled", "true"));
        if (!enabled) return false;
        var key = ConfigService.get("scanner.virustotal.apiKey");
        if (key == null || key.isBlank()) {
            MISSING_KEY_WARNING.warnOnce(
                    "VirusTotal scanning is enabled but scanner.virustotal.apiKey is not set — "
                            + "this scanner is a no-op. Get a free 500 req/day key at "
                            + "https://www.virustotal.com/gui/join-us and save it via the Config table.");
            return false;
        }
        return true;
    }

    /** Test-only hook to reset the one-shot warning between key-toggle tests. */
    static void resetMissingKeyWarning() {
        MISSING_KEY_WARNING.reset();
    }

    @Override
    public Verdict lookup(String sha256) {
        var baseUrl = ConfigService.get(
                "scanner.virustotal.url", "https://www.virustotal.com/api/v3/");
        if (!baseUrl.endsWith("/")) baseUrl = baseUrl + "/";
        var timeoutMs = Scanner.parseInt(ConfigService.get(
                "scanner.virustotal.timeoutMs", "5000"), 5000);
        var apiKey = ConfigService.get("scanner.virustotal.apiKey");

        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "files/" + sha256))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("x-apikey", apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            var response = HttpClients.GENERAL.send(request, HttpResponse.BodyHandlers.ofString());
            var status = response.statusCode();

            // 404 = VirusTotal has never seen this hash (unknown → clean, matching
            // MetaDefender's 404 and MalwareBazaar's hash_not_found).
            if (status == 404) {
                return Verdict.clean();
            }
            if (status < 200 || status >= 300) {
                EventLogger.warn("scanner",
                        "VirusTotal returned HTTP %d for hash %s — failing open"
                                .formatted(status, sha256.substring(0, 12)));
                return Verdict.clean();
            }

            var json = JsonParser.parseString(response.body()).getAsJsonObject();
            return parseVerdict(json);

        } catch (Exception e) {
            EventLogger.warn("scanner",
                    "VirusTotal lookup failed for hash %s: %s — failing open"
                            .formatted(sha256.substring(0, 12), e.getMessage()));
            return Verdict.clean();
        }
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
