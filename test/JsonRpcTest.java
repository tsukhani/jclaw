import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import mcp.jsonrpc.JsonRpc;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.Map;

class JsonRpcTest extends UnitTest {

    // ==================== encode ====================

    @Test
    void encodeRequestEmitsSpecCompliantJson() {
        var req = new JsonRpc.Request(1L, "tools/list", null);
        var wire = JsonRpc.encode(req);
        var obj = JsonParser.parseString(wire).getAsJsonObject();
        assertEquals("2.0", obj.get("jsonrpc").getAsString());
        assertEquals(1L, obj.get("id").getAsLong());
        assertEquals("tools/list", obj.get("method").getAsString());
        assertFalse(obj.has("params"), "absent params field must not be emitted");
    }

    @Test
    void encodeRequestWithObjectParams() {
        var req = new JsonRpc.Request(7L, "tools/call",
                Map.of("name", "echo", "arguments", Map.of("text", "hi")));
        var obj = JsonParser.parseString(JsonRpc.encode(req)).getAsJsonObject();
        assertEquals("echo", obj.getAsJsonObject("params").get("name").getAsString());
        assertEquals("hi", obj.getAsJsonObject("params")
                .getAsJsonObject("arguments").get("text").getAsString());
    }

    @Test
    void encodeRequestPreservesStringId() {
        var req = new JsonRpc.Request("req-abc", "ping", null);
        var obj = JsonParser.parseString(JsonRpc.encode(req)).getAsJsonObject();
        assertTrue(obj.get("id").getAsJsonPrimitive().isString(), "string id must remain a JSON string");
        assertEquals("req-abc", obj.get("id").getAsString());
    }

    @Test
    void encodeNotificationOmitsId() {
        var note = new JsonRpc.Notification("notifications/initialized", null);
        var obj = JsonParser.parseString(JsonRpc.encode(note)).getAsJsonObject();
        assertEquals("notifications/initialized", obj.get("method").getAsString());
        assertFalse(obj.has("id"), "notifications must not carry an id");
    }

    @Test
    void encodeResponseSuccessSetsResultNotError() {
        var result = new JsonObject();
        result.add("tools", new JsonObject());
        var resp = new JsonRpc.Response(1L, result, null);
        var obj = JsonParser.parseString(JsonRpc.encode(resp)).getAsJsonObject();
        assertEquals(1L, obj.get("id").getAsLong());
        assertTrue(obj.has("result"));
        assertFalse(obj.has("error"));
    }

    @Test
    void encodeResponseErrorSetsErrorNotResult() {
        var resp = new JsonRpc.Response(1L, null, new JsonRpc.Error(-32601, "Method not found"));
        var obj = JsonParser.parseString(JsonRpc.encode(resp)).getAsJsonObject();
        assertFalse(obj.has("result"));
        assertEquals(-32601, obj.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("Method not found", obj.getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    void encodeResponseSuccessWithNullResultEmitsJsonNull() {
        // ping responses carry result: null per spec.
        var resp = new JsonRpc.Response(1L, null, null);
        var obj = JsonParser.parseString(JsonRpc.encode(resp)).getAsJsonObject();
        assertTrue(obj.has("result"), "result field must be present, even if null");
        assertTrue(obj.get("result").isJsonNull());
    }

    @Test
    void encodeRequestRejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new JsonRpc.Request(null, "tools/list", null));
    }

    @Test
    void encodeRequestRejectsBlankMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> new JsonRpc.Request(1L, "", null));
    }

    @Test
    void encodeNotificationRejectsBlankMethod() {
        assertThrows(IllegalArgumentException.class,
                () -> new JsonRpc.Notification("", null));
    }

    // ==================== decode ====================

    @Test
    void decodeRequest() {
        var msg = JsonRpc.decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        assertTrue(msg instanceof JsonRpc.Request);
        var req = (JsonRpc.Request) msg;
        assertEquals(1L, req.id());
        assertEquals("tools/list", req.method());
        assertNotNull(req.params());
    }

    @Test
    void decodeNotificationWhenIdAbsent() {
        var msg = JsonRpc.decode("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertTrue(msg instanceof JsonRpc.Notification);
        var note = (JsonRpc.Notification) msg;
        assertEquals("notifications/initialized", note.method());
    }

    @Test
    void decodeResponseSuccess() {
        var msg = JsonRpc.decode("{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":{\"tools\":[]}}");
        assertTrue(msg instanceof JsonRpc.Response);
        var resp = (JsonRpc.Response) msg;
        assertEquals(42L, resp.id());
        assertFalse(resp.isError());
        assertNotNull(resp.result());
    }

    @Test
    void decodeResponseError() {
        var wire = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"nope\"}}";
        var msg = JsonRpc.decode(wire);
        assertTrue(msg instanceof JsonRpc.Response);
        var resp = (JsonRpc.Response) msg;
        assertTrue(resp.isError());
        assertEquals(-32601, resp.error().code());
        assertEquals("nope", resp.error().message());
        assertNull(resp.error().data());
    }

    @Test
    void decodeResponseErrorWithDataPreserved() {
        var wire = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"x\",\"data\":{\"detail\":\"trace\"}}}";
        var resp = (JsonRpc.Response) JsonRpc.decode(wire);
        assertEquals("trace", resp.error().data().getAsJsonObject().get("detail").getAsString());
    }

    @Test
    void decodeRequestWithStringId() {
        var msg = JsonRpc.decode("{\"jsonrpc\":\"2.0\",\"id\":\"call-7\",\"method\":\"ping\"}");
        var req = (JsonRpc.Request) msg;
        assertEquals("call-7", req.id());
        assertTrue(req.id() instanceof String, "string id must round-trip as String, not Long");
    }

    @Test
    void decodeRejectsWrongJsonRpcVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonRpc.decode("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"x\"}"));
    }

    @Test
    void decodeRejectsMissingJsonRpcVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonRpc.decode("{\"id\":1,\"method\":\"x\"}"));
    }

    @Test
    void decodeRejectsUnrecognizableShape() {
        // No method, no result, no error.
        assertThrows(IllegalArgumentException.class,
                () -> JsonRpc.decode("{\"jsonrpc\":\"2.0\",\"id\":1}"));
    }

    // ==================== round-trip ====================

    @Test
    void roundTripRequest() {
        var original = new JsonRpc.Request(1L, "tools/call",
                Map.of("name", "x", "arguments", Map.of("a", 1)));
        var wire = JsonRpc.encode(original);
        var decoded = (JsonRpc.Request) JsonRpc.decode(wire);
        assertEquals(original.id(), decoded.id());
        assertEquals(original.method(), decoded.method());
        // params come back as JsonElement, not the original Map — check structurally.
        var params = (com.google.gson.JsonElement) decoded.params();
        assertEquals("x", params.getAsJsonObject().get("name").getAsString());
    }

    @Test
    void roundTripErrorResponse() {
        var original = new JsonRpc.Response(99L, null,
                new JsonRpc.Error(-32602, "Invalid params", new JsonPrimitive("missing field foo")));
        var decoded = (JsonRpc.Response) JsonRpc.decode(JsonRpc.encode(original));
        assertEquals(99L, decoded.id());
        assertEquals(-32602, decoded.error().code());
        assertEquals("Invalid params", decoded.error().message());
        assertEquals("missing field foo", decoded.error().data().getAsString());
    }
}
