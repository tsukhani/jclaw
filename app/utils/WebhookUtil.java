package utils;

import play.mvc.Http;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

/**
 * Shared helpers for webhook controllers.
 */
public final class WebhookUtil {

    private WebhookUtil() {}

    /** Read the raw request body as a UTF-8 string using the snapshot (readAllBytes) approach. */
    public static String readRawBody() throws IOException {
        return new String(Http.Request.current().body.readAllBytes(), StandardCharsets.UTF_8);
    }
}
