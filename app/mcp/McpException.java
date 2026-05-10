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
}
