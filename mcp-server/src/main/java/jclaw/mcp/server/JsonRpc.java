package jclaw.mcp.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Minimal JSON-RPC 2.0 message types for the MCP wire protocol.
 *
 * <p>Mirrors the inverse side of {@code app/mcp/jsonrpc/JsonRpc.java}
 * in the main Play app — that file is the client; this is the server.
 * Kept as a small standalone copy here rather than shared via a common
 * jar so this module stays self-contained (operators build one fat
 * jar, no transitive dependency on Play 1.x or any internal artifact).
 *
 * <p>Standard error codes used by {@link McpServer}:
 * <ul>
 *   <li>{@code -32600} invalid request</li>
 *   <li>{@code -32601} method not found</li>
 *   <li>{@code -32602} invalid params</li>
 *   <li>{@code -32603} internal error</li>
 * </ul>
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    public static final int ERROR_INVALID_REQUEST = -32600;
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    public static final int ERROR_INVALID_PARAMS = -32602;
    public static final int ERROR_INTERNAL = -32603;

    private JsonRpc() {}

    public sealed interface Message permits Request, Response, Notification {}

    public record Request(Object id, String method, JsonElement params) implements Message {}
    public record Response(Object id, JsonElement result, Error error) implements Message {}
    public record Notification(String method, JsonElement params) implements Message {}
    public record Error(int code, String message, JsonElement data) {
        public Error(int code, String message) { this(code, message, null); }
    }

    public static String encode(Message msg) {
        return switch (msg) {
            case Request r -> encodeRequest(r);
            case Response r -> encodeResponse(r);
            case Notification n -> encodeNotification(n);
        };
    }

    public static Message decode(String wire) {
        var root = JsonParser.parseString(wire).getAsJsonObject();
        var version = root.has("jsonrpc") ? root.get("jsonrpc").getAsString() : null;
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("Not a JSON-RPC 2.0 message: jsonrpc=" + version);
        }
        boolean hasId = root.has("id");
        boolean hasMethod = root.has("method");
        boolean hasResult = root.has("result");
        boolean hasError = root.has("error");

        if (hasMethod && hasId) {
            return new Request(decodeId(root.get("id")), root.get("method").getAsString(),
                    root.has("params") ? root.get("params") : null);
        }
        if (hasMethod) {
            return new Notification(root.get("method").getAsString(),
                    root.has("params") ? root.get("params") : null);
        }
        if (hasResult || hasError) {
            Object id = hasId ? decodeId(root.get("id")) : null;
            JsonElement result = hasResult ? root.get("result") : null;
            Error err = null;
            if (hasError) {
                var e = root.getAsJsonObject("error");
                err = new Error(e.get("code").getAsInt(), e.get("message").getAsString(),
                        e.has("data") ? e.get("data") : null);
            }
            return new Response(id, result, err);
        }
        throw new IllegalArgumentException("Unrecognized JSON-RPC message: " + wire);
    }

    private static String encodeRequest(Request req) {
        var obj = new JsonObject();
        obj.addProperty("jsonrpc", VERSION);
        obj.add("id", idJson(req.id()));
        obj.addProperty("method", req.method());
        if (req.params() != null) obj.add("params", req.params());
        return obj.toString();
    }

    private static String encodeResponse(Response resp) {
        var obj = new JsonObject();
        obj.addProperty("jsonrpc", VERSION);
        obj.add("id", idJson(resp.id()));
        if (resp.error() != null) {
            var err = new JsonObject();
            err.addProperty("code", resp.error().code());
            err.addProperty("message", resp.error().message());
            if (resp.error().data() != null) err.add("data", resp.error().data());
            obj.add("error", err);
        } else {
            obj.add("result", resp.result() != null ? resp.result() : JsonNull.INSTANCE);
        }
        return obj.toString();
    }

    private static String encodeNotification(Notification note) {
        var obj = new JsonObject();
        obj.addProperty("jsonrpc", VERSION);
        obj.addProperty("method", note.method());
        if (note.params() != null) obj.add("params", note.params());
        return obj.toString();
    }

    private static JsonElement idJson(Object id) {
        if (id == null) return JsonNull.INSTANCE;
        if (id instanceof Number n) return new JsonPrimitive(n);
        return new JsonPrimitive(id.toString());
    }

    private static Object decodeId(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        var p = el.getAsJsonPrimitive();
        if (p.isNumber()) return p.getAsLong();
        return p.getAsString();
    }
}
