package mcp;

/**
 * Thrown when an MCP server returns a JSON-RPC error response, when the
 * protocol contract is violated, or when a request times out (JCLAW-31).
 *
 * <p>Transport-level I/O failures are surfaced as {@link java.io.IOException}
 * — this class is reserved for protocol-level problems where the wire was
 * fine but the conversation went wrong.
 */
public class McpException extends RuntimeException {

    private final Integer code;

    public McpException(String message) {
        super(message);
        this.code = null;
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    public McpException(int code, String message) {
        super("[code=" + code + "] " + message);
        this.code = code;
    }

    public Integer code() { return code; }

    /**
     * The operator opened this server's circuit breaker by hand (JCLAW-1187). Still an
     * {@code McpException}, so every caller's fail-fast path is unchanged; a distinct class so
     * an operator's decision is never read back as the server having failed.
     */
    public static final class ManuallyIsolated extends McpException {
        public ManuallyIsolated(String message) {
            super(message);
        }
    }
}
