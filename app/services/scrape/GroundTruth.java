package services.scrape;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * What a corpus entry must yield for a fetch to count as content (JCLAW-1081).
 *
 * <p>{@code rejectMarkers} is the load-bearing half. A Cloudflare interstitial is
 * valid HTML that extracts to a few hundred characters of clean markdown, so
 * without it the harness scores "checking your browser" as a success and reports
 * a healthy number while agents receive nothing.
 *
 * <p>{@code expectTitle} is deliberately <em>not</em> part of the pass/fail gate:
 * it is captured from the raw {@code <title>} at corpus-build time, and Readability
 * plus the markdown conversion do not reliably preserve it. Gating on it would
 * manufacture failures that say nothing about access. Reported as a secondary
 * signal instead.
 */
public record GroundTruth(int minChars, List<String> rejectMarkers, @Nullable String expectTitle) {

    public boolean rejected(String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        return rejectMarkers.stream().anyMatch(lower::contains);
    }

    public boolean titleSeen(String text) {
        return expectTitle == null
                || text.toLowerCase(Locale.ROOT).contains(expectTitle.toLowerCase(Locale.ROOT));
    }
}
