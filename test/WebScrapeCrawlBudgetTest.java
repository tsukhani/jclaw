import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.WebScrapeTool;
import tools.scrape.ScrapeOutput;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;

/**
 * The crawl's own budgets — time, and the escalation allowance the ladder spends
 * (JCLAW-1099).
 *
 * <p>Reached by reflection rather than by driving a crawl. Every path that increments an
 * escalation counter runs behind {@code ScrapeLadder.available()}, so on a host with no
 * sidecar a crawl-level test asserts nothing at all, and on one with a sidecar it would
 * fetch the live web. The accounting and the sentence it produces are what regress, and
 * both are reachable without either.
 */
class WebScrapeCrawlBudgetTest extends UnitTest {

    private static final Class<?> CRAWL_STATE;
    private static final Constructor<?> NEW_STATE;
    private static final Method EXHAUSTED;
    private static final Method RENDER;

    static {
        try {
            CRAWL_STATE = Class.forName("tools.WebScrapeTool$CrawlState");
            NEW_STATE = CRAWL_STATE.getDeclaredConstructor(ScrapeOutput.Request.class, boolean.class);
            NEW_STATE.setAccessible(true);
            EXHAUSTED = WebScrapeTool.class.getDeclaredMethod("exhausted", CRAWL_STATE);
            EXHAUSTED.setAccessible(true);
            RENDER = WebScrapeTool.class.getDeclaredMethod(
                    "result", URI.class, CRAWL_STATE, int.class, boolean.class, Agent.class);
            RENDER.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object state() throws ReflectiveOperationException {
        return NEW_STATE.newInstance(ScrapeOutput.Request.MARKDOWN, false);
    }

    private static void set(Object state, String field, Object value)
            throws ReflectiveOperationException {
        var f = CRAWL_STATE.getDeclaredField(field);
        f.setAccessible(true);
        f.set(state, value);
    }

    private static Object get(Object state, String field) throws ReflectiveOperationException {
        var f = CRAWL_STATE.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(state);
    }

    private static boolean exhausted(Object state) throws ReflectiveOperationException {
        return (boolean) EXHAUSTED.invoke(new WebScrapeTool(), state);
    }

    private static String render(Object state) throws ReflectiveOperationException {
        return (String) RENDER.invoke(null, URI.create("https://site.test/"), state, 2, true, null);
    }

    @Test
    void theDeadlineIsTheCrawlsOwnAndNotRecomputedPerCheck() throws Exception {
        // The per-page escalation path and the between-levels check read one instant, so
        // a check that recomputed "now + timeout" would never expire.
        var expired = state();
        set(expired, "deadline", System.nanoTime() - Duration.ofSeconds(1).toNanos());
        assertTrue(exhausted(expired));
        assertTrue(((String) get(expired, "stoppedBecause")).startsWith("time budget"),
                "the caller must be told which budget stopped the crawl: "
                        + get(expired, "stoppedBecause"));

        var live = state();
        set(live, "deadline", System.nanoTime() + Duration.ofHours(1).toNanos());
        assertFalse(exhausted(live));
        assertNull(get(live, "stoppedBecause"));
    }

    @Test
    void theContentBudgetStopsTheCrawlToo() throws Exception {
        var full = state();
        set(full, "deadline", System.nanoTime() + Duration.ofHours(1).toNanos());
        set(full, "totalChars", utils.WebExtraction.MAX_TEXT_LENGTH);
        assertTrue(exhausted(full));
        assertTrue(((String) get(full, "stoppedBecause")).startsWith("content budget"),
                (String) get(full, "stoppedBecause"));
    }

    @Test
    void theEscalationBudgetIsSpentOnceAndRefusalsAreCounted() throws Exception {
        // Never refunded on failure: a rung that failed still spent the seconds, and
        // refunding would let one pathological host consume the whole crawl one retry at
        // a time. What the refusals must not do is vanish — a thin result the crawl
        // stopped trying on reads exactly like one the origin refused.
        var state = state();
        set(state, "escalationsLeft", 2);
        var claim = CRAWL_STATE.getDeclaredMethod("claimEscalation");
        claim.setAccessible(true);

        assertTrue((boolean) claim.invoke(state));
        assertTrue((boolean) claim.invoke(state));
        assertFalse((boolean) claim.invoke(state), "the budget is two, not three");
        assertFalse((boolean) claim.invoke(state));

        assertEquals(2, get(state, "escalationsUsed"));
        assertEquals(2, get(state, "escalationsSuppressed"),
                "both refusals must be reported, not just the first");
        assertEquals(0, get(state, "escalationsOutOfTime"),
                "a spent budget is not a spent deadline");
    }

    @Test
    void theEscalationLineSeparatesABudgetFromADeadline() throws Exception {
        // Raising max-escalations fixes one of these and does nothing for the other, so
        // a report that merged them would send an operator to the wrong dial.
        var state = state();
        set(state, "escalationsUsed", 2);
        set(state, "escalationsSuppressed", 3);
        set(state, "escalationsOutOfTime", 1);

        var out = render(state);
        assertTrue(out.contains("Escalated 2 pages beyond the plain fetch"), out);
        assertTrue(out.contains("3 more could have been but the escalation budget ("), out);
        assertTrue(out.contains("1 more were skipped because the time budget ("), out);
    }

    @Test
    void aCrawlThatNeverEscalatedSaysNothingAboutEscalation() throws Exception {
        // The line is evidence that something was retried; emitting "Escalated 0 pages"
        // on every ordinary crawl would make it noise nobody reads.
        var out = render(state());
        assertFalse(out.contains("Escalated"), out);
    }

    @Test
    void aDeadlineAloneStillProducesTheLine() throws Exception {
        // A guard reading only the other two counters would drop this report entirely.
        var state = state();
        set(state, "escalationsOutOfTime", 1);
        var out = render(state);
        assertTrue(out.contains("Escalated 0 pages beyond the plain fetch"), out);
        assertTrue(out.contains("1 more were skipped because the time budget ("), out);
    }
}
