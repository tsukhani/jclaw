package services.scrape;

import utils.WebExtraction;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What one fetch attempt produced, as the classifier needs to see it (JCLAW-1086).
 *
 * <p>Carries the <em>raw</em> body alongside the extracted text, which is the whole
 * point. Readability strips scripts, so by the time a page reaches extracted text a
 * Cloudflare gate and a client-rendered app are indistinguishable — both are zero
 * characters. The markers that separate them live in the markup the extractor threw
 * away.
 *
 * @param error non-null when the fetch itself failed; the other fields are then empty
 */
public record ScrapeObservation(String url, String contentType, String rawBody,
                                String extractedText, String error) {

    /** Cap on the raw markup scanned for markers. Gate pages are small and put their
     *  markers near the top; scanning megabytes of a large article buys nothing. */
    private static final int SCAN_LIMIT = 64 * 1024;

    public static ScrapeObservation of(WebExtraction.FetchResult fetched, String text) {
        var body = new String(fetched.body(), 0,
                Math.min(fetched.body().length, SCAN_LIMIT), StandardCharsets.UTF_8);
        return new ScrapeObservation(fetched.finalUrl(), fetched.contentType(),
                body.toLowerCase(Locale.ROOT), text == null ? "" : text, null);
    }

    public static ScrapeObservation failed(String url, String error) {
        return new ScrapeObservation(url, "", "", "", error);
    }

    public boolean failed() {
        return error != null;
    }

    public int textLength() {
        return extractedText == null ? 0 : extractedText.length();
    }
}
