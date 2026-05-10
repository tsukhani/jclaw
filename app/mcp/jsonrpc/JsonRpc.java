package mcp.jsonrpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import utils.GsonHolder;

/**
 * JSON-RPC 2.0 message types for the MCP wire protocol (JCLAW-31).
 *
 * <p>MCP transports exchange JSON-RPC 2.0 messages of three shapes:
 * {@link Request} (id + method, expects a response), {@link Response}
 * (id + either {@code result} or {@code error}), and {@link Notification}
 * (method only, no id, no response). The sealed {@link Message} interface
 * lets the inbound dispatcher pattern-match on the discriminated shape.
 *
 * <p>IDs are typed as {@code Object} because JSON-RPC permits string or
 * numeric IDs; we mint outbound IDs as {@code Long} via an atomic counter
 * and accept either form on inbound messages.
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    private JsonRpc() {}

    public sealed interface Message permits Request, Response, Notification {}

    public record Request(Object id, String method, Object params) implements Message {
        public Request {
            if (id == null) throw new IllegalArgumentException("Request requires an id");
            if (method == null || method.isEmpty()) throw new IllegalArgumentException("method required");
        }
    }

    public record Response(Object id, JsonElement result, Error error) implements Message {
        public boolean isError() { return error != null; }
    }

    public record Notification(String method, Object params) implements Message {
        public Notification {
            if (method == null || method.isEmpty()) throw new IllegalArgumentException("method required");
        }
    }

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

    private static String encodeRequest(Request req) {
        var obj = new JsonObject();
        obj.addProperty("jsonrpc", VERSION);
        obj.add("id", idJson(req.id()));
        obj.addProperty("method", req.method());
        if (req.params() != null) obj.add("params", paramsJson(req.params()));
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
        if (note.params() != null) obj.add("params", paramsJson(note.params()));
        return obj.toString();
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
        throw new IllegalArgumentException("Not a recognizable JSON-RPC message: " + wire);
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

    private static JsonElement paramsJson(Object params) {
        if (params instanceof JsonElement el) return el;
        return GsonHolder.INSTANCE.toJsonTree(params);
    }
}
