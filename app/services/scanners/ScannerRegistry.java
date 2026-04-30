package services.scanners;

import java.util.List;
import java.util.function.Function;

public final class ScannerRegistry {

    public record ConfigDefault(String key, String value) {}

    public record Registration(
            String id,
            Function<ScannerDependencies, Scanner> factory,
            List<ConfigDefault> configDefaults
    ) {
        public Scanner create() {
            return create(ScannerDependencies.production());
        }

        public Scanner create(ScannerDependencies dependencies) {
            return factory.apply(dependencies);
        }
    }

    private static final List<Registration> REGISTRATIONS = List.of(
            new Registration("malwarebazaar", MalwareBazaarScanner::new, List.of(
                    new ConfigDefault("scanner.malwarebazaar.enabled", "true"),
                    new ConfigDefault("scanner.malwarebazaar.authKey", ""),
                    new ConfigDefault("scanner.malwarebazaar.url", "https://mb-api.abuse.ch/api/v1/"),
                    new ConfigDefault("scanner.malwarebazaar.timeoutMs", "5000")
            )),
            new Registration("metadefender", MetaDefenderCloudScanner::new, List.of(
                    new ConfigDefault("scanner.metadefender.enabled", "true"),
                    new ConfigDefault("scanner.metadefender.apiKey", ""),
                    new ConfigDefault("scanner.metadefender.url", "https://api.metadefender.com/v4/"),
                    new ConfigDefault("scanner.metadefender.timeoutMs", "5000")
            )),
            new Registration("virustotal", VirusTotalScanner::new, List.of(
                    new ConfigDefault("scanner.virustotal.enabled", "true"),
                    new ConfigDefault("scanner.virustotal.apiKey", ""),
                    new ConfigDefault("scanner.virustotal.url", "https://www.virustotal.com/api/v3/"),
                    new ConfigDefault("scanner.virustotal.timeoutMs", "5000")
            ))
    );

    private ScannerRegistry() {}

    public static List<Registration> registrations() {
        return REGISTRATIONS;
    }

    public static List<Scanner> createDefaultScanners() {
        return createScanners(ScannerDependencies.production());
    }

    public static List<Scanner> createScanners(ScannerDependencies dependencies) {
        return REGISTRATIONS.stream()
                .map(registration -> registration.create(dependencies))
                .toList();
    }

    public static List<ConfigDefault> defaultConfig() {
        return REGISTRATIONS.stream()
                .flatMap(registration -> registration.configDefaults().stream())
                .toList();
    }
}
