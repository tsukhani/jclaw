package services.openaicompat;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import services.ConfigService;
import utils.HttpKeys;
import utils.Strings;

import java.io.IOException;

/**
 * Shared plumbing for the OpenAI-wire-compatible provider clients — image captioning
 * ({@code services.caption}), audio transcription ({@code services.transcription}), and image
 * generation ({@code services.imagegen}). Holds the two concerns every one of them duplicated:
 *
 * <ul>
 *   <li><b>Credential resolution</b> — read {@code provider.{name}.baseUrl} / {@code .apiKey},
 *       validate both are present, and trim the base URL's trailing slash so an endpoint path
 *       appends cleanly. Missing config fails fast, before any HTTP call fires.</li>
 *   <li><b>Error mapping</b> — turn a non-2xx {@link Response} or a transport {@link IOException}
 *       into the subsystem's typed {@code RuntimeException} with uniform text
 *       ({@code "{provider} {operation} failed: HTTP {code} {message} — {body snippet}"}).</li>
 * </ul>
 *
 * <p>Subclasses stay OpenAI-shape-specific: they own the endpoint path and the request/response
 * body shaping. The exception type and the operation label are supplied via the abstract hooks so
 * each subsystem keeps throwing its own {@code *Exception} while the shared error text stays uniform.
 */
public abstract class OpenAiCompatibleClientBase {

    protected final String providerName;
    protected final OkHttpClient client;

    protected OpenAiCompatibleClientBase(String providerName, OkHttpClient client) {
        this.providerName = providerName;
        this.client = client;
    }

    /** Resolved base URL (trailing slash trimmed) plus API key for this provider. */
    protected record Credentials(String baseUrl, String apiKey) {}

    /**
     * Resolve {@code provider.{name}.baseUrl} / {@code .apiKey}, trimming the base URL's trailing
     * slash so a {@code /endpoint} path appends cleanly. Throws the subsystem exception (via
     * {@link #newException(String)}) when either key is blank — before any HTTP call fires, so a
     * misconfiguration never leaks a request payload.
     */
    protected Credentials resolveCredentials() {
        var baseUrlKey = "provider." + providerName + ".baseUrl";
        var apiKeyKey = "provider." + providerName + ".apiKey";
        var baseUrl = ConfigService.get(baseUrlKey);
        var apiKey = ConfigService.get(apiKeyKey);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw newException(baseUrlKey + " is not configured");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw newException(apiKeyKey + " is not configured");
        }
        return new Credentials(Strings.trimTrailingSlash(baseUrl), apiKey);
    }

    /** Add {@code Authorization: Bearer {apiKey}} to a request builder and return it for chaining. */
    protected Request.Builder bearer(Request.Builder builder, String apiKey) {
        return builder.header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey);
    }

    /**
     * Build the typed HTTP-error exception for a non-2xx response:
     * {@code "{provider} {operation} failed: HTTP {code} {message} — {body snippet}"}. The caller
     * has already confirmed {@code !response.isSuccessful()} and read {@code responseBody}.
     */
    protected RuntimeException httpError(Response response, String responseBody) {
        var snippet = Strings.truncate(responseBody, Strings.ERROR_SNIPPET_MAX_CHARS);
        return newException("%s %s failed: HTTP %d %s%s".formatted(
                providerName, operationLabel(), response.code(), response.message(),
                snippet.isEmpty() ? "" : (" — " + snippet)));
    }

    /** Build the typed transport-failure exception for an {@link IOException} during the call. */
    protected RuntimeException transportError(IOException e) {
        return newException(
                providerName + " " + operationLabel() + " transport failed: " + e.getMessage(), e);
    }

    /** Operation label used in error text, e.g. {@code "captioning"} / {@code "transcription"}. */
    protected abstract String operationLabel();

    /** Construct the subsystem's typed exception (CaptionException / TranscriptionException / …). */
    protected abstract RuntimeException newException(String message);

    /** Construct the subsystem's typed exception with an originating cause. */
    protected abstract RuntimeException newException(String message, Throwable cause);
}
