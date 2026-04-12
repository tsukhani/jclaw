package controllers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.mvc.Http;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Shared JSON body parsing for API controllers. Previously duplicated as a
 * private static {@code readJsonBody()} in every {@code Api*Controller}.
 */
public class JsonBodyReader {

    /**
     * Parse the current request body as a {@link JsonObject}. Returns
     * {@code null} on any parse failure — callers should follow with
     * {@code badRequest()} when null.
     */
    public static JsonObject readJsonBody() {
        try {
            var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8);
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception _) {
            return null;
        }
    }
}
