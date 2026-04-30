package services.scanners;

@FunctionalInterface
public interface ScannerLogger {
    void warn(String message);
}
