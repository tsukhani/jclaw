package services.compression;

/**
 * Content classification for a single message body (JCLAW-460), used to route
 * it to the matching {@link ContentCompressor}. Mirrors the content types in
 * Headroom's detector, minus the variants JClaw's MVP doesn't compress
 * separately — MARKDOWN and DIFF fold into {@link #TEXT} for now.
 */
public enum ContentType {
    /** Valid JSON object or array — compressed by the JSON SmartCrusher. */
    JSON,
    /** Source code in a recognized language — preserve signatures, compress bodies. */
    CODE,
    /** Log output with level indicators — statistical text compression. */
    LOG,
    /** Anything else — plain prose, the catch-all. */
    TEXT
}
