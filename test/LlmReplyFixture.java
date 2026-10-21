import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** A canned chat completion in the shape the request asked for: a streamed call reads SSE, a sync one JSON. */
public final class LlmReplyFixture {

    private LlmReplyFixture() {}

    /** Answer {@code exchange} with {@code completionJson}, streamed when the client asked for an event stream. */
    public static void send(HttpExchange exchange, String completionJson) throws IOException {
        var accept = exchange.getRequestHeaders().getFirst("Accept");
        boolean streamed = accept != null && accept.contains("text/event-stream");
        var body = (streamed ? sse(completionJson) : completionJson).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", streamed ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** One completion as a single-chunk stream: each choice's message becomes its delta, and each tool call gains an index. */
    public static String sse(String completionJson) {
        var completion = JsonParser.parseString(completionJson).getAsJsonObject();
        var chunk = new JsonObject();
        for (var key : new String[] {"id", "model"}) {
            if (completion.has(key)) chunk.add(key, completion.get(key));
        }
        var choices = new JsonArray();
        if (completion.has("choices")) {
            for (var element : completion.getAsJsonArray("choices")) {
                var choice = element.getAsJsonObject().deepCopy();
                var message = choice.remove("message");
                if (message != null && message.isJsonObject()) {
                    var delta = message.getAsJsonObject();
                    if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                        var calls = delta.getAsJsonArray("tool_calls");
                        for (int i = 0; i < calls.size(); i++) {
                            var call = calls.get(i).getAsJsonObject();
                            if (!call.has("index")) call.addProperty("index", i);
                        }
                    }
                    choice.add("delta", delta);
                }
                choices.add(choice);
            }
        }
        chunk.add("choices", choices);
        if (completion.has("usage")) chunk.add("usage", completion.get("usage"));
        return "data: " + chunk + "\n\ndata: [DONE]\n\n";
    }
}
