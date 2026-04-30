package services.scanners;

import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * Functional interface for scanner HTTP calls. Wraps an OkHttp
 * {@link okhttp3.OkHttpClient} call so test fixtures can stub the
 * response without a real network round-trip. JCLAW-188 reshaped this
 * around OkHttp; the prior version returned a JDK
 * {@code java.net.http.HttpResponse}.
 *
 * <p>{@code timeoutMs} bounds the entire call (connect plus write plus read);
 * the production implementation sets {@code Call.timeout(...)} so each
 * scanner gets its own configured budget without forcing a separate
 * {@link okhttp3.OkHttpClient} per scanner.
 *
 * <p>Implementations must return a fresh {@link Response}; callers close
 * it via try-with-resources after consuming the body.
 */
@FunctionalInterface
public interface ScannerHttpClient {
    Response send(Request request, int timeoutMs) throws IOException;
}
