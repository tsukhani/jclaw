package mcp;

import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Thrown when an MCP server returns a JSON-RPC error response, when the
 * protocol contract is violated, or when a request times out (JCLAW-31).
 *
 * <p>Transport-level I/O failures are surfaced as {@link java.io.IOException}
 * — this class is reserved for protocol-level problems where the wire was
 * fine but the conversation went wrong.
 */
public class McpException extends RuntimeException {

    /** A capability name from a server's error data is attacker-influenced; only a plain identifier is repeated. */
    private static final Pattern CAPABILITY_NAME = Pattern.compile("[A-Za-z0-9_./-]{1,64}");

    private final @Nullable Integer code;
    private final List<String> supportedVersions;
    private final List<String> requiredCapabilities;
    private final @Nullable Integer httpStatus;

    public McpException(String message) {
        this(message, (Throwable) null);
    }

    public McpException(String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = null;
        this.supportedVersions = List.of();
        this.requiredCapabilities = List.of();
        this.httpStatus = null;
    }

    public McpException(int code, String message) {
        this(code, message, List.of());
    }

    public McpException(int code, String message, List<String> supportedVersions) {
        this(code, message, supportedVersions, List.of(), null);
    }

    private McpException(@Nullable Integer code, String message, List<String> supportedVersions,
                         List<String> requiredCapabilities, @Nullable Integer httpStatus) {
        super(code != null ? "[code=" + code + "] " + message : message);
        this.code = code;
        this.supportedVersions = List.copyOf(supportedVersions);
        this.requiredCapabilities = List.copyOf(requiredCapabilities);
        this.httpStatus = httpStatus;
    }

    /** A JSON-RPC error, with what its {@code data} says about versions and capabilities. */
    public static McpException fromRpcError(int code, String message, @Nullable JsonElement data) {
        return new McpException(code, message, supportedVersions(data), requiredCapabilities(data), null);
    }

    /** A non-2xx HTTP answer: its status, plus the JSON-RPC error its body held, if any. */
    public static McpException fromHttp(int status, @Nullable Integer code, @Nullable JsonElement data) {
        return new McpException(code, "MCP HTTP " + status, supportedVersions(data), requiredCapabilities(data),
                status);
    }

    public @Nullable Integer code() { return code; }

    /** This failure under {@code message}, with its code and what its data said kept. */
    McpException withMessage(String message) {
        return new McpException(code, message, supportedVersions, requiredCapabilities, httpStatus);
    }

    /**
     * The protocol versions an unsupported-version error offered in {@code data.supported},
     * limited to those in {@link McpClient#KNOWN_PROTOCOL_VERSIONS}; empty otherwise.
     */
    public List<String> supportedVersions() { return supportedVersions; }

    /** The client capabilities a -32021 error named in {@code data.requiredCapabilities}, plain identifiers only. */
    public List<String> requiredCapabilities() { return requiredCapabilities; }

    /** The HTTP status when a non-2xx answer carried this failure; null otherwise. */
    public @Nullable Integer httpStatus() { return httpStatus; }

    private static List<String> supportedVersions(@Nullable JsonElement data) {
        if (data == null || !data.isJsonObject()) return List.of();
        var supported = data.getAsJsonObject().get("supported");
        if (supported == null || !supported.isJsonArray()) return List.of();
        var out = new ArrayList<String>();
        for (var el : supported.getAsJsonArray()) {
            if (el.isJsonPrimitive() && McpClient.KNOWN_PROTOCOL_VERSIONS.contains(el.getAsString())) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    private static List<String> requiredCapabilities(@Nullable JsonElement data) {
        if (data == null || !data.isJsonObject()) return List.of();
        var required = data.getAsJsonObject().get("requiredCapabilities");
        if (required == null) return List.of();
        var names = new ArrayList<String>();
        if (required.isJsonObject()) {
            names.addAll(required.getAsJsonObject().keySet());
        } else if (required.isJsonArray()) {
            for (var el : required.getAsJsonArray()) if (el.isJsonPrimitive()) names.add(el.getAsString());
        }
        return names.stream().filter(n -> CAPABILITY_NAME.matcher(n).matches()).toList();
    }

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

    /**
     * {@code resources/read} was answered with -32602 or the legacy -32002: the URI names nothing.
     * The server answered, so this is no evidence against it, and its breaker records a success.
     */
    public static final class ResourceNotFound extends McpException {
        private final String uri;

        public ResourceNotFound(int code, String uri) {
            super(code, "no resource '" + uri + "'");
            this.uri = uri;
        }

        public String uri() { return uri; }
    }

    /**
     * The server must be reached again through the {@code initialize} handshake on a fresh
     * connection (JCLAW-1443). {@link #switchOnSuccess()} is true when the server answered
     * discovery and then failed on 2026-07-28, so a handshake that works moves it to Handshake;
     * false when it simply shares no 2026-07-28 with JClaw.
     */
    public static final class NeedsHandshake extends McpException {
        private final boolean switchOnSuccess;
        private final String reason;

        public NeedsHandshake(String reason, boolean switchOnSuccess, @Nullable Integer code,
                              List<String> supportedVersions) {
            super(code, reason, supportedVersions, List.of(), null);
            this.switchOnSuccess = switchOnSuccess;
            this.reason = reason;
        }

        public boolean switchOnSuccess() { return switchOnSuccess; }

        /** Why, in words fit for the event log and the MCP Servers page. */
        public String reason() { return reason; }
    }
}
