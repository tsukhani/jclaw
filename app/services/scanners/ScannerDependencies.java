package services.scanners;

import services.ConfigService;
import services.EventLogger;
import utils.HttpFactories;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public record ScannerDependencies(
        ScannerConfig config,
        ScannerHttpClient httpClient,
        ScannerLogger logger
) {
    private static final ScannerDependencies PRODUCTION = new ScannerDependencies(
            new ScannerConfig() {
                @Override
                public String get(String key) {
                    return ConfigService.get(key);
                }

                @Override
                public String get(String key, String fallback) {
                    return ConfigService.get(key, fallback);
                }
            },
            (request, timeoutMs) -> {
                var call = HttpFactories.general().newCall(request);
                call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS);
                return call.execute();
            },
            message -> EventLogger.warn("scanner", message)
    );

    public ScannerDependencies {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(logger, "logger");
    }

    public static ScannerDependencies production() {
        return PRODUCTION;
    }
}
