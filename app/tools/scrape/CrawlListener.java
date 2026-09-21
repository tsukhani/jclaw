package tools.scrape;

import org.jspecify.annotations.Nullable;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;

/**
 * Where a background job's crawl reports as it goes, and how it is told to stop (JCLAW-1272).
 *
 * <p>Called on the crawl's own thread, never a fetch worker, so an implementation may write to the
 * database without its own locking.
 */
public interface CrawlListener {

    /** One page, in the order the crawl records them. */
    void page(Page page);

    /** Distinct URLs queued so far, including those a budget or a refusal later drops. */
    void discovered(int urls);

    /** Checked before each fetch starts; a fetch already in flight finishes. */
    boolean stopRequested();

    /**
     * @param content the page as the job's format renders it — Markdown, plain text, or its JSON
     *                record — or null when the page was not retrieved
     */
    record Page(String url, int depth, ScrapeRung servedBy, ScrapeReason reason, @Nullable String content) {}
}
