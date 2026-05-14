package utils;

/**
 * Shared HTTP header name and value constants for outbound HTTP calls.
 * Centralizes the canonical strings used by OkHttp request builders across
 * provider drivers, scanners, the MCP HTTP transport, and channel webhooks —
 * eliminating the java:S1192 duplicated-literal flags those files were
 * collecting. BEARER_PREFIX retains its trailing space because callers
 * concatenate the token directly after it.
 */
public final class HttpKeys {

    private HttpKeys() {}

    // Header names
    public static final String AUTHORIZATION = "Authorization";
    public static final String ACCEPT = "Accept";
    public static final String CONTENT_TYPE = "Content-Type";

    // Common values
    public static final String APPLICATION_JSON = "application/json";

    // Bearer-auth prefix (trailing space is intentional — callers append the token)
    public static final String BEARER_PREFIX = "Bearer ";
}
