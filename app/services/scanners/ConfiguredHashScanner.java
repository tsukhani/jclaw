package services.scanners;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Request;

abstract class ConfiguredHashScanner implements Scanner {

    @FunctionalInterface
    protected interface JsonVerdictParser {
        Verdict parse(JsonObject json);
    }

    private final String scannerName;
    private final String enabledKey;
    private final String apiKeyKey;
    private final OneShotWarning missingKeyWarning;
    private final String missingKeyMessage;
    private final ScannerDependencies dependencies;

    protected ConfiguredHashScanner(String scannerName, String enabledKey, String apiKeyKey,
                                    OneShotWarning missingKeyWarning, String missingKeyMessage,
                                    ScannerDependencies dependencies) {
        this.scannerName = scannerName;
        this.enabledKey = enabledKey;
        this.apiKeyKey = apiKeyKey;
        this.missingKeyWarning = missingKeyWarning;
        this.missingKeyMessage = missingKeyMessage;
        this.dependencies = java.util.Objects.requireNonNull(dependencies, "dependencies");
    }

    @Override
    public String name() {
        return scannerName;
    }

    @Override
    public boolean isEnabled() {
        if (!"true".equalsIgnoreCase(config().get(enabledKey, "true"))) return false;
        var key = config().get(apiKeyKey);
        if (key == null || key.isBlank()) {
            if (missingKeyWarning.shouldWarn()) warn(missingKeyMessage);
            return false;
        }
        return true;
    }

    protected void resetWarningForTest() {
        missingKeyWarning.reset();
    }

    protected ScannerConfig config() {
        return dependencies.config();
    }

    protected Verdict sendJsonLookup(String sha256, Request request, int timeoutMs,
                                     boolean cleanOnNotFound, JsonVerdictParser parser) {
        try (var response = dependencies.httpClient().send(request, timeoutMs)) {
            var status = response.code();
            if (cleanOnNotFound && status == 404) return Verdict.clean();
            if (status < 200 || status >= 300) {
                warn("%s returned HTTP %d for hash %s — failing open"
                        .formatted(scannerName, status, hashPrefix(sha256)));
                return Verdict.clean();
            }
            var body = response.body() != null ? response.body().string() : "";
            return parser.parse(JsonParser.parseString(body).getAsJsonObject());
        } catch (java.io.InterruptedIOException e) {
            // OkHttp throws InterruptedIOException when a Call is interrupted
            // (Call.timeout firing, Thread.interrupt mid-call, etc.). Re-set
            // the interrupt flag so callers up the stack can observe it.
            Thread.currentThread().interrupt();
            warn("%s lookup interrupted for hash %s — failing open"
                    .formatted(scannerName, hashPrefix(sha256)));
            return Verdict.clean();
        } catch (Exception e) {
            warn("%s lookup failed for hash %s: %s — failing open"
                    .formatted(scannerName, hashPrefix(sha256), e.getMessage()));
            return Verdict.clean();
        }
    }

    protected void warn(String message) {
        dependencies.logger().warn(message);
    }

    protected static String ensureTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    protected static String hashPrefix(String sha256) {
        return sha256.substring(0, Math.min(sha256.length(), 12));
    }
}
