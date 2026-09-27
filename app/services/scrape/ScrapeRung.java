package services.scrape;

/**
 * Rungs of the escalation ladder, in cost order (JCLAW-1086).
 *
 * <p>{@link #PLAIN}, {@link #IMPERSONATE} and {@link #BROWSER} ship. {@link #PROVIDER}
 * was descoped, so {@link BlockClassifier#nextRung} names it only as the rung past
 * {@link #BROWSER} — kept distinct from {@link #NONE} rather than a rung that will be
 * attempted.
 */
public enum ScrapeRung {
    /** OkHttp + SsrfGuard + Readability. What ships today. */
    PLAIN,
    /** TLS/HTTP2 fingerprint impersonation (JCLAW-1087). */
    IMPERSONATE,
    /** Real browser — renders JavaScript and defeats browser fingerprinting (JCLAW-1088). */
    BROWSER,
    /** Paid provider with a residential pool (JCLAW-1089/1090). */
    PROVIDER,
    /** Nothing further will help. */
    NONE
}
