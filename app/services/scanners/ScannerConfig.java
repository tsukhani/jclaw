package services.scanners;

public interface ScannerConfig {
    String get(String key);
    String get(String key, String fallback);

    default int getInt(String key, String fallback, int defaultValue) {
        return Scanner.parseInt(get(key, fallback), defaultValue);
    }
}
