import memory.AbsoluteDates;
import memory.TemporalExpressions;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.LocalDate;
import java.util.Set;

/** JCLAW-1383: capture writes a relative date as the absolute form the finder reads back to the same value. */
class AbsoluteDatesTest extends UnitTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 3);

    private static void rewrites(String input, String expected) {
        assertEquals(expected, AbsoluteDates.rewrite(input, ANCHOR), () -> "rewrite of: " + input);
    }

    private static void unchanged(String input) {
        rewrites(input, input);
    }

    // ─── I/O matrix ──────────────────────────────────────────────────────────

    @Test
    void aDayGainsOn() {
        rewrites("The user moved to Porto yesterday.", "The user moved to Porto on 2 October 2026.");
    }

    @Test
    void aSentenceStartIsCapitalized() {
        rewrites("Last year the user joined Vela.", "In 2025 the user joined Vela.");
        rewrites("Moved to Porto. Yesterday the user unpacked.", "Moved to Porto. On 2 October 2026 the user unpacked.");
    }

    @Test
    void aCapitalizedSpanAfterALineStartColonOrQuoteGetsACapitalizedPreposition() {
        rewrites("Moved to Porto\nYesterday the user unpacked.", "Moved to Porto\nOn 2 October 2026 the user unpacked.");
        rewrites("Plan: Next month the user starts.", "Plan: In November 2026 the user starts.");
        rewrites("The user said \"Last year was hard.\"", "The user said \"In 2025 was hard.\"");
        rewrites("Plan: the user starts next month.", "Plan: the user starts in November 2026.");
    }

    @Test
    void aPrecedingPrepositionIsKept() {
        rewrites("Has lived in Porto since last year.", "Has lived in Porto since 2025.");
    }

    @Test
    void aRewriteThatReadsBackAsARangeIsDropped() {
        unchanged("The user moved last year – 2027.");
    }

    @Test
    void aDroppedRewriteLeavesTheOthersInPlace() {
        rewrites("Moved yesterday and lived there last year – 2027.",
                "Moved on 2 October 2026 and lived there last year – 2027.");
    }

    @Test
    void aMonth() {
        rewrites("Started the course last month.", "Started the course in September 2026.");
    }

    @Test
    void aNamedMonth() {
        rewrites("The retreat is this June.", "The retreat is in June 2026.");
        rewrites("The retreat is next June.", "The retreat is in June 2027.");
    }

    @Test
    void aQuarter() {
        rewrites("Closed the deal last quarter.", "Closed the deal in Q3 2026.");
    }

    @Test
    void aFiscalClauseIsLeftAlone() {
        unchanged("Closed this quarter, ahead of the fiscal plan.");
    }

    @Test
    void aStretchAfterTheIsLeftAloneButTheCalendarPeriodIsNot() {
        unchanged("Over the last year the user ran daily.");
        rewrites("Has run daily since last year.", "Has run daily since 2025.");
    }

    @Test
    void aPossessiveIsLeftAloneButTheBarePhraseIsNot() {
        unchanged("Her next month is booked.");
        unchanged("Whose next month is free?");
        unchanged("The calendar shows the user's next month as full.");
        unchanged("The calendar shows the user’s next month as full.");
        rewrites("The user is booked next month.", "The user is booked in November 2026.");
    }

    @Test
    void aGenitiveHyphenOrDigitJoinIsLeftAlone() {
        unchanged("Last year's budget doubled.");
        unchanged("Last year’s budget doubled.");
        unchanged("The board met before this year-end.");
        unchanged("The retreat is this June 5.");
        unchanged("Planned the mid-next-year review.");
        rewrites("The budget doubled last year.", "The budget doubled in 2025.");
        rewrites("The retreat is this June, then July.", "The retreat is in June 2026, then July.");
    }

    @Test
    void phrasesOutsideTheSetAreLeftAlone() {
        for (var text : new String[] {"The user moved in March.", "The party is on 12 December.",
                "The user has climbed for three years.", "The user moved two years ago.",
                "The gym runs a retreat every June.", "The race is this spring.", "The user moved recently.",
                "The standup is on Monday.", "The standup is at 9:30 am.", "The file is report-2019.pdf.",
                "See https://x.example/last-year for notes.", "Tracked as JCLAW-2022.", "The user arrives tonight.",
                "The user slept badly last night.", "The user ran this morning.", "The user travels next week.",
                "The launch is coming June.", "The launch is in Q3.", "The user left the day before yesterday.",
                "The launch is next Jan.", "The user moved this june."}) {
            unchanged(text);
        }
    }

    @Test
    void severalPhrases() {
        rewrites("Moved yesterday and starts next month.", "Moved on 2 October 2026 and starts in November 2026.");
    }

    @Test
    void textWithNothingToRewriteIsReturnedIdentical() {
        var text = "The user prefers dark mode.";
        assertSame(text, AbsoluteDates.rewrite(text, ANCHOR));
        assertEquals("", AbsoluteDates.rewrite("", ANCHOR));
    }

    // ─── Rewrite table ───────────────────────────────────────────────────────

    @Test
    void everyDayWord() {
        rewrites("Moved today.", "Moved on 3 October 2026.");
        rewrites("Moved yesterday.", "Moved on 2 October 2026.");
        rewrites("Moves tomorrow.", "Moves on 4 October 2026.");
        rewrites("Today the user moved.", "On 3 October 2026 the user moved.");
    }

    @Test
    void everyYear() {
        rewrites("Joined this year.", "Joined in 2026.");
        rewrites("Joined last year.", "Joined in 2025.");
        rewrites("Joins next year.", "Joins in 2027.");
    }

    @Test
    void everyMonth() {
        rewrites("Started this month.", "Started in October 2026.");
        rewrites("Started last month.", "Started in September 2026.");
        rewrites("Starts next month.", "Starts in November 2026.");
    }

    @Test
    void everyNamedMonth() {
        rewrites("The retreat was last June.", "The retreat was in June 2026.");
        rewrites("The retreat was last November.", "The retreat was in November 2025.");
        rewrites("The retreat is this December.", "The retreat is in December 2026.");
        rewrites("The retreat is next October.", "The retreat is in October 2027.");
    }

    @Test
    void everyQuarter() {
        rewrites("Closed this quarter.", "Closed in Q4 2026.");
        rewrites("Closed last quarter.", "Closed in Q3 2026.");
        rewrites("Closes next quarter.", "Closes in Q1 2027.");
        rewrites("Closes next quarter.", LocalDate.of(2026, 12, 31), "Closes in Q1 2027.");
    }

    private static void rewrites(String input, LocalDate anchor, String expected) {
        assertEquals(expected, AbsoluteDates.rewrite(input, anchor), () -> "rewrite of: " + input + " @" + anchor);
    }

    @Test
    void everyKeptPreposition() {
        for (var p : new String[] {"in", "on", "at", "by", "since", "until", "from", "before", "after", "during",
                "early", "late", "mid"}) {
            rewrites("Moved " + p + " last year.", "Moved " + p + " 2025.");
        }
        rewrites("Started early next month.", "Started early November 2026.");
        rewrites("Starts mid next quarter.", "Starts mid Q1 2027.");
        rewrites("Since last year the user ran daily.", "Since 2025 the user ran daily.");
    }

    @Test
    void theAnchorDecides() {
        rewrites("Joined last year.", LocalDate.of(2028, 2, 29), "Joined in 2027.");
        rewrites("Moved yesterday.", LocalDate.of(2028, 3, 1), "Moved on 29 February 2028.");
        rewrites("Moved yesterday.", LocalDate.of(2027, 1, 1), "Moved on 31 December 2026.");
    }

    // ─── Guard effects ───────────────────────────────────────────────────────

    @Test
    void valueKindsBeforeAndAfter() {
        kinds("The user moved yesterday.", Set.of(), Set.of("date", "year"));
        kinds("The user started last month.", Set.of(), Set.of("date"));
        kinds("The retreat is this June.", Set.of("date"), Set.of("date"));
        kinds("The user joined last year.", Set.of(), Set.of("year"));
        kinds("The deal closed last quarter.", Set.of(), Set.of("year"));
    }

    private static void kinds(String before, Set<String> kindsBefore, Set<String> kindsAfter) {
        var after = AbsoluteDates.rewrite(before, ANCHOR);
        assertNotEquals(before, after);
        assertEquals(kindsBefore, TemporalExpressions.valueKinds(before), () -> "before: " + before);
        assertEquals(kindsAfter, TemporalExpressions.valueKinds(after), () -> "after: " + after);
    }
}
