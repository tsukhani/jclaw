package services.scanners;

public interface ScannerConfig {
    /**
     * Look up a config value with no default.
     *
     * @return the value for {@code key}, or {@code null} when unset. Callers
     *         doing credential lookups branch on {@code == null || isBlank()}.
     */
    String get(String key);

    /** Look up a config value, returning {@code fallback} when {@code key} is unset. */
    String get(String key, String fallback);

    default int getInt(String key, String fallback, int defaultValue) {
        return Scanner.parseInt(get(key, fallback), defaultValue);
    }
}
