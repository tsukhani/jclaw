package services.scrape;

import org.jspecify.annotations.Nullable;
import utils.WebExtraction;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What one fetch attempt produced, as the classifier needs to see it (JCLAW-1086).
 *
 * <p>Carries the <em>raw</em> body alongside the extracted text, which is the whole
 * point. Readability strips scripts, so by the time a page reaches extracted text a
 * Cloudflare gate and a client-rendered app are indistinguishable — both are zero
 * characters. The markers that separate them live in the markup the extractor threw
 * away.
 *
 * @param error non-null when the fetch itself failed; the other fields are then empty,
 *              except on a refused response
 * @param status the HTTP status of a refused response (400 and above), whose body prefix
 *               and classifier headers are then kept too; 0 for anything else
 * @param headers the response headers {@link BlockClassifier} reads, and a render's
 *                {@link #CHALLENGE_HEADER}, keyed in lower case
 */
public record ScrapeObservation(@Nullable String url, String contentType, String rawBody,
                                String extractedText, @Nullable String error,
                                int status, Map<String, String> headers) {

    /** Cap on the raw markup scanned for markers. Gate pages are small and put their
     *  markers near the top; scanning megabytes of a large article buys nothing. */
    public static final int SCAN_LIMIT = 64 * 1024;

    /** The stealth sidecar's report of the Cloudflare challenge a render met, such as
     *  {@code managed; cleared} (JCLAW-1306). */
    public static final String CHALLENGE_HEADER = "x-challenge";

    public static ScrapeObservation of(WebExtraction.FetchResult fetched, @Nullable String text) {
        return new ScrapeObservation(fetched.finalUrl(), fetched.contentType(),
                scanned(fetched.body()), text == null ? "" : text, null, 0, Map.of());
    }

    public static ScrapeObservation failed(@Nullable String url, @Nullable String error) {
        return new ScrapeObservation(url, "", "", "", error, 0, Map.of());
    }

    /** A failed attempt; a refused response keeps what {@link #refused} keeps. */
    public static ScrapeObservation failed(@Nullable String url, Exception e) {
        if (e instanceof WebExtraction.HttpStatusException refusal) return refused(url, refusal);
        var m = e.getMessage();
        return failed(url, m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
    }

    public static ScrapeObservation refused(@Nullable String url, WebExtraction.HttpStatusException e) {
        return new ScrapeObservation(url, e.contentType(), scanned(e.body()), "", e.getMessage(),
                e.status(), e.headers());
    }

    public boolean failed() {
        return error != null;
    }

    /** Valid only when {@link #failed()} — a successful observation carries no error. */
    public String resolvedError() {
        if (error == null) throw new IllegalStateException("observation did not fail");
        return error;
    }

    public int textLength() {
        return extractedText == null ? 0 : extractedText.length();
    }

    public @Nullable String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public @Nullable String challenge() {
        return header(CHALLENGE_HEADER);
    }

    /** This observation carrying {@code challenge}; itself when there is none to add. */
    public ScrapeObservation withChallenge(@Nullable String challenge) {
        if (challenge == null || challenge.equals(challenge())) return this;
        var merged = new LinkedHashMap<>(headers);
        merged.put(CHALLENGE_HEADER, challenge);
        return new ScrapeObservation(url, contentType, rawBody, extractedText, error, status,
                Map.copyOf(merged));
    }

    private static String scanned(byte[] body) {
        return new String(body, 0, Math.min(body.length, SCAN_LIMIT), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
    }
}
