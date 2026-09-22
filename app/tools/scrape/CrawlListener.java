package tools.scrape;

import org.jspecify.annotations.Nullable;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;

import java.time.Duration;
import java.util.Map;

/**
 * Where a background job's crawl reports as it goes, and how it is told to stop (JCLAW-1272).
 *
 * <p>Called on the crawl's own thread, never a fetch worker, so an implementation may write to the
 * database without its own locking.
 */
public interface CrawlListener {

    /** One page, in the order the crawl records them. A page replayed on a resume is not reported again. */
    void page(Page page);

    /** Distinct URLs queued so far, including those a budget or a refusal later drops. */
    void discovered(int urls);

    /** Checked before each fetch starts; a fetch already in flight finishes. */
    boolean stopRequested();

    /**
     * @param requested the URL the crawl queued, which is how a resumed crawl recognises the page;
     *                  {@code url} is where it ended up after redirects
     * @param content   the page as the job's format renders it — Markdown, plain text, or its JSON
     *                  record — or null when the page was not retrieved
     * @param harvest   what the frontier needs from the page; null when it was not retrieved
     */
    record Page(String requested, String url, int depth, ScrapeRung servedBy, ScrapeReason reason,
                @Nullable String content, @Nullable PageHarvest harvest) {}

    /** A page an earlier run of the job already read, which a resumed crawl replays instead of fetching. */
    record Recorded(ScrapeRung servedBy, @Nullable PageHarvest harvest) {}

    /**
     * Where a crawl picks up.
     *
     * @param recorded the pages already read, by the URL the crawl requested
     * @param timeLeft what remains of the job's time limit, which counts only time spent running
     */
    record Resume(Map<String, Recorded> recorded, Duration timeLeft) {

        public static Resume fresh(Duration timeLimit) {
            return new Resume(Map.of(), timeLimit);
        }
    }
}
