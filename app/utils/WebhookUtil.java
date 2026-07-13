package utils;

import org.jspecify.annotations.NonNull;
import play.mvc.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared helpers for webhook controllers.
 */
public final class WebhookUtil {

    private WebhookUtil() {}

    /** Read the raw request body as a UTF-8 string using the snapshot (readAllBytes) approach. */
    public static @NonNull String readRawBody() throws IOException {
        return new String(Http.Request.current().body.readAllBytes(), StandardCharsets.UTF_8);
    }
}
