package services.scanners;

import services.ConfigService;
import services.EventLogger;
import utils.HttpClients;

import java.net.http.HttpResponse;
import java.util.Objects;

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
            request -> HttpClients.GENERAL.send(request, HttpResponse.BodyHandlers.ofString()),
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
