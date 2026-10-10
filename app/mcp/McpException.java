package mcp;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Thrown when an MCP server returns a JSON-RPC error response, when the
 * protocol contract is violated, or when a request times out (JCLAW-31).
 *
 * <p>Transport-level I/O failures are surfaced as {@link java.io.IOException}
 * — this class is reserved for protocol-level problems where the wire was
 * fine but the conversation went wrong.
 */
public class McpException extends RuntimeException {

    private final @Nullable Integer code;
    private final List<String> supportedVersions;

    public McpException(String message) {
        super(message);
        this.code = null;
        this.supportedVersions = List.of();
    }

    public McpException(String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = null;
        this.supportedVersions = List.of();
    }

    public McpException(int code, String message) {
        this(code, message, List.of());
    }

    public McpException(int code, String message, List<String> supportedVersions) {
        super("[code=" + code + "] " + message);
        this.code = code;
        this.supportedVersions = List.copyOf(supportedVersions);
    }

    public @Nullable Integer code() { return code; }

    /**
     * The protocol versions an unsupported-version error offered in {@code data.supported},
     * limited to those in {@link McpClient#KNOWN_PROTOCOL_VERSIONS}; empty otherwise.
     */
    public List<String> supportedVersions() { return supportedVersions; }

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
