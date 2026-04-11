package services.scanners;

/**
 * Hash-based malware scanner contract. Every scanner takes a SHA-256 and returns
 * a {@link Verdict}. Scanners are independent — each one may be enabled or
 * disabled by its own config keys — and {@link services.SkillBinaryScanner}
 * composes their verdicts under OR semantics: a file is rejected if any enabled
 * scanner flags it.
 *
 * <p>Implementations MUST fail open: network errors, timeouts, quota exhaustion,
 * and unexpected responses MUST return {@link Verdict#clean()} and log a warning
 * rather than throwing. Scanner outages must never block skill install flows.
 *
 * <p>Implementations MUST treat "hash not found" as clean, not as unknown —
 * known-sample lookup services have no notion of "inconclusive."
 */
public interface Scanner {

    /** Short human-readable name used in {@code Violation.scanner} and audit logs. */
    String name();

    /**
     * True only when the scanner is both toggled on <em>and</em> has the
     * credentials it needs to actually make queries. A scanner whose API key is
     * blank MUST return false here so the orchestrator skips it silently (with
     * a one-shot warning on first check).
     */
    boolean isEnabled();

    /**
     * Look up a single SHA-256 hash. Never throws; on any failure returns
     * {@link Verdict#clean()} and logs a warning.
     */
    Verdict lookup(String sha256);

    /** Scanner verdict for a single file. */
    record Verdict(boolean malicious, String reason) {
        public static Verdict clean() { return new Verdict(false, null); }
        public static Verdict malicious(String reason) { return new Verdict(true, reason); }
    }
}
