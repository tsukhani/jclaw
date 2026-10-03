import memory.LiteralSpans;
import memory.TemporalExpressions;
import memory.TemporalExpressions.DateSpan;
import memory.TemporalExpressions.Kind;
import memory.TemporalExpressions.Polarity;
import memory.TemporalExpressions.Reason;
import memory.TemporalExpressions.Refused;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** JCLAW-1361: the date finder and normalizer, the capture guard's value kinds, and the lexicons. */
class TemporalExpressionsTest extends UnitTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 3);

    private static final String C025 = "Avery Lin has a standing call with Mateo Castillo every Sunday evening to plan"
            + " the Thornbury Marathon in April.";
    private static final String C080 = "Is renewing the Larkspur Inn booking for the Marrow Bay Retreat, which"
            + " Harborlight Analytics is holding there this June.";
    private static final String C133 = "Jonah Pell coaches juniors at the Ashgrove Rowing Club every Thursday evening"
            + " and is training for the Thornbury Marathon this spring.";

    /** The single found span's readings, with its duration, as text. */
    private static String read(String text, LocalDate anchor) {
        var found = TemporalExpressions.find(text, anchor).found();
        assertEquals(1, found.size(), () -> text + " found " + found);
        var span = found.getFirst();
        var readings = span.readings().stream().map(Object::toString).collect(Collectors.joining(","));
        return span.duration() == null ? readings : readings + " " + span.duration();
    }

    private static String read(String text) {
        return read(text, ANCHOR);
    }

    private static List<String> found(String text) {
        return TemporalExpressions.find(text, ANCHOR).found().stream().map(DateSpan::span).toList();
    }

    private static List<String> refused(String text, Reason reason) {
        return TemporalExpressions.find(text, ANCHOR).refused().stream().filter(r -> r.reason() == reason)
                .map(Refused::span).toList();
    }

    // ─── Normalizer rows at 2026-10-03 ───

    @Test
    void dayWords() {
        assertEquals("2026-10-03", read("today"));
        assertEquals("2026-10-04", read("tomorrow"));
        assertEquals("2026-10-02", read("yesterday"));
        assertEquals("2026-10-03", read("tonight"));
        assertEquals("2026-10-02", read("last night"));
        assertEquals("2026-10-03", read("this morning"));
        assertEquals("2026-10-01", read("the day before yesterday"));
        assertEquals("2026-10-05", read("the day after tomorrow"));
    }

    @Test
    void weeksReadAsApproximateDays() {
        assertEquals("2026-10-03~", read("this week"));
        assertEquals("2026-09-26~", read("last week"));
        assertEquals("2026-10-10~", read("next week"));
    }

    @Test
    void deicticMonthsYearsAndQuarters() {
        assertEquals("2026-10", read("this month"));
        assertEquals("2026-09", read("last month"));
        assertEquals("2026-11", read("next month"));
        assertEquals("2026", read("this year"));
        assertEquals("2025", read("last year"));
        assertEquals("2027", read("next year"));
        assertEquals("2026-36", read("this quarter"));
        assertEquals("2026-35", read("last quarter"));
        assertEquals("2027-33", read("next quarter"));
        assertEquals("2027-33", read("next quarter", LocalDate.of(2026, 12, 31)));
    }

    @Test
    void calendarQuarters() {
        assertEquals("2027-35", read("Q3 2027"));
        assertEquals("2027-35", read("Q3 of 2027"));
        assertEquals("2027-35", read("the third quarter of 2027"));
        assertEquals("2026-35", read("Q3"));
    }

    @Test
    void namedMonths() {
        assertEquals("2026-06", read("this June"));
        assertEquals("2026-06", read("this June", LocalDate.of(2026, 2, 15)));
        assertEquals("2026-06", read("last June"));
        assertEquals("2025-06", read("last June", LocalDate.of(2026, 6, 15)));
        assertEquals("2027-06", read("next June"));
        assertEquals("2026-06", read("in June"));
        assertEquals("2026-06", read("June 2026"));
        assertEquals("2019-05", read("May of 2019"));
    }

    @Test
    void days() {
        assertEquals("2026-06-05", read("June 5"));
        assertEquals("2026-06-05", read("5 June"));
        assertEquals("2026-06-05", read("the 5th of June"));
        assertEquals("2026-06-05", read("June 5, 2026"));
        assertEquals("2026-06-05", read("5 June 2026"));
        assertEquals("2026-02-15", read("2026-02-15"));
    }

    @Test
    void seasons() {
        assertEquals("2026-21", read("this spring"));
        assertEquals("2025-24", read("this winter", LocalDate.of(2026, 2, 15)));
        assertEquals("2026-24", read("this winter"));
        assertEquals("2026-22", read("last summer"));
        assertEquals("2025-22", read("last summer", LocalDate.of(2026, 7, 15)));
        assertEquals("2026-24", read("next winter"));
        assertEquals("2026-21", read("in the spring"));
        assertEquals("2025-23", read("autumn 2025"));
        assertEquals("2024-23", read("fall of 2024"));
    }

    @Test
    void agoAndAhead() {
        assertEquals("2023~", read("3 years ago"));
        assertEquals("2025~", read("a year ago"));
        assertEquals("2026-08~", read("two months ago"));
        assertEquals("2026-09-12~", read("3 weeks ago"));
        assertEquals("2026-09-23", read("10 days ago"));
        assertEquals("2016~", read("a decade ago"));
        assertEquals("2028~", read("in 2 years"));
        assertEquals("2026-10-06", read("in three days"));
    }

    @Test
    void durationsReadAsTheirStart() {
        assertEquals("2023~ P3Y", read("for three years"));
        assertEquals("2023~ P3Y", read("for the past three years"));
        assertEquals("2026-04~ P6M", read("for 6 months"));
        assertEquals("2026-09-19~ P2W", read("for two weeks"));
        assertEquals("2026-09-23 P10D", read("for 10 days"));
        var span = TemporalExpressions.find("for three years", ANCHOR).found().getFirst();
        assertEquals(Kind.DURATION, span.kind());
        assertEquals("three years", span.span());
        assertEquals("for three years", span.phrase());
    }

    @Test
    void absoluteYearsAndRanges() {
        assertEquals("2019", read("in 2019"));
        assertEquals("2019", read("since 2019"));
        assertEquals("2019/2021", read("from 2019 to 2021"));
        assertEquals("2020-03/2021-06", read("between March 2020 and June 2021"));
        assertEquals("2019/2021", read("2019-2021"));
        assertEquals("2016/2018", read("2016–2018"));
    }

    @Test
    void relativeMeansTheReadingDependsOnTheAnchor() {
        var found = TemporalExpressions.find("in 2019, last year and on June 5", ANCHOR).found();
        assertEquals(List.of(false, true, true), found.stream().map(DateSpan::relative).toList());
    }

    // ─── Graph cases ───

    @Test
    void c080sThisJuneIsTheAnchorYearsJune() {
        var found = TemporalExpressions.find(C080, LocalDate.of(2026, 2, 15)).found();
        assertEquals(List.of("this June"), found.stream().map(DateSpan::span).toList());
        assertEquals("2026-06", found.getFirst().readings().getFirst().toString());
    }

    @Test
    void aRecurrenceElsewhereInTheClauseLeavesADateFound() {
        assertEquals(List.of("April"), found(C025));
        assertEquals(List.of("Sunday"), refused(C025, Reason.RECURRING));
        assertEquals(List.of("this spring"), found(C133));
        assertEquals(List.of("Thursday"), refused(C133, Reason.RECURRING));
    }

    // ─── Never a date ───

    @Test
    void recurringSpansAreRefused() {
        assertEquals(List.of("June"), refused("The gym runs a retreat every June.", Reason.RECURRING));
        assertEquals(List.of("Sundays", "summers"), refused("They hike on Sundays and spend summers away.",
                Reason.RECURRING));
        assertEquals(List.of("every quarter"), refused("during the last week of every quarter", Reason.RECURRING));
        assertEquals(List.of("Thursday"), refused("every other Thursday", Reason.RECURRING));
        assertEquals(List.of(), found("during the last week of every quarter"));
        assertEquals(List.of("June"), refused("every\nJune", Reason.RECURRING));
    }

    @Test
    void asPerCitesRatherThanRecurs() {
        assertEquals(List.of("March 3"), found("as per our March 3 call"));
    }

    @Test
    void anOrdinalAfterTheIsNoDate() {
        assertEquals(List.of(), found("the last June"));
        assertEquals(List.of(), found("the next winter"));
    }

    @Test
    void anImpossibleDateNeverThrows() {
        assertEquals(List.of("June"), found("Met on June 31."));
        assertEquals(List.of("2026"), found("2026-13-45"));
        var leap = TemporalExpressions.find("February 29", ANCHOR);
        assertEquals(List.of("February"), leap.found().stream().map(DateSpan::span).toList());
        assertEquals(List.of(), leap.refused());
    }

    @Test
    void weekdaysClocksAndVagueWordsAreRefused() {
        assertEquals(List.of("Monday"), refused("The standup is on Monday.", Reason.WEEKDAY));
        assertEquals(List.of("9:30 am", "noon"), refused("at 9:30 am and before noon", Reason.CLOCK));
        assertEquals(List.of("recently", "soon", "the 1990s"),
                refused("She moved recently, leaves soon and grew up in the 1990s.", Reason.VAGUE));
        assertEquals(List.of("Two weeks before"), refused("Two weeks before the offsite.", Reason.EVENT_RELATIVE));
    }

    @Test
    void aModalIsNeverAMonth() {
        assertEquals(List.of(), found("You may march on if you like."));
        assertEquals(List.of("May 2019", "March 3"), found("Moved in May 2019 and met Dana on March 3."));
    }

    @Test
    void literalDigitsAreNeverAYear() {
        var text = "report-2019.pdf at https://x/2020 and /srv/2021 for JCLAW-2022, cost $2023 on ticket #2024";
        assertEquals(List.of(), found(text));
        assertEquals(List.of("2019", "2020", "2021", "2022", "2023", "2024"), refused(text, Reason.LITERAL));
        assertEquals(List.of(), found("cost $2000-2050 for #2019-2021"));
        assertEquals(List.of("2000-2050", "2019-2021"), refused("cost $2000-2050 for #2019-2021", Reason.LITERAL));
    }

    @Test
    void aYearBesidePunctuationIsStillAYear() {
        assertEquals(List.of("2019", "2019"), found("Founded in 2019. Its logo (2019) hangs there."));
    }

    @Test
    void fiscalPeriodsAndNumericDatesAreRefused() {
        assertEquals(List.of("this quarter"), refused("Closed this quarter, ahead of the fiscal plan.", Reason.FISCAL));
        assertEquals(List.of("Q2", "FY27"), refused("Q2 FY27 looks flat.", Reason.FISCAL));
        assertEquals(List.of("this quarter"), found("Closed this quarter. The fiscal plan is separate."));
        assertEquals(List.of("2021"), found("FYI, we launched in 2021."));
        assertEquals(List.of("2019"), found("Fyodor joined in 2019."));
        assertEquals(List.of("7/10", "12-03-2026"), refused("due 7/10 and 12-03-2026", Reason.NUMERIC_DM));
    }

    @Test
    void claimedSpansAreTheFoundOffsets() {
        assertEquals(List.of(new TemporalExpressions.Range(22, 29)),
                TemporalExpressions.claimedSpans("The launch slipped to Q3 2027 on Monday."));
    }

    @Test
    void literalSpansKeepTheGeneratorsOrder() {
        var spans = LiteralSpans.spans("See https://x.example/a.md), /srv/b.md and c.md for JCLAW-7.");
        assertEquals(List.of(LiteralSpans.Kind.URL, LiteralSpans.Kind.PATH, LiteralSpans.Kind.FILE,
                LiteralSpans.Kind.TICKET), spans.stream().map(LiteralSpans.Span::kind).toList());
        assertEquals(26, spans.getFirst().end(), "trailing punctuation trimmed from the URL");
    }

    // ─── Capture guard ───

    @Test
    void valueKindsKeepsTheCaptureGuardsReading() {
        assertEquals(Set.of("clock"), TemporalExpressions.valueKinds("The flight is at 7:00 pm"));
        assertEquals(Set.of("duration"), TemporalExpressions.valueKinds("a daily routine for 3 weeks"));
        assertEquals(Set.of("date"), TemporalExpressions.valueKinds("Lunch in may"));
        assertEquals(Set.of("date"), TemporalExpressions.valueKinds("Caught bass on 7/22"));
        assertEquals(Set.of("date"), TemporalExpressions.valueKinds("Booked for Sept 4th"));
        assertEquals(Set.of("year", "quantity"), TemporalExpressions.valueKinds("Since 2019, 3 cats"));
        assertEquals(Set.of("quantity"), TemporalExpressions.valueKinds("on page 250"));
        assertEquals(Set.of(), TemporalExpressions.valueKinds("no values here"));
    }

    @Test
    void timeWordsAreTheGeneratorsSet() {
        assertEquals(68, TemporalExpressions.TIME_WORDS.size());
        assertTrue(TemporalExpressions.TIME_WORDS.containsAll(List.of("may", "sept", "fridays", "tonight", "years")));
        assertEquals("monday", TemporalExpressions.TIME_WORDS.iterator().next());
    }

    // ─── Lexicons ───

    @Test
    void anEndingIsReadBeforeNegation() {
        assertEquals(List.of(), TemporalExpressions.negationCues("The user hasn't used Osprey Dashboard since 2024."));
        assertEquals(List.of(), TemporalExpressions.negationCues("Avery Lin no longer drives."));
        assertEquals(List.of("doesn't"), TemporalExpressions.negationCues("Avery Lin doesn't drive."));
        assertEquals(List.of("never"), TemporalExpressions.negationCues("Jonah has never been to Lisbon."));
        assertEquals(List.of("not", "without"), TemporalExpressions.negationCues("Not a fan, without doubt."));
    }

    @Test
    void perfectNeverIsTheTenseNotTheWord() {
        assertTrue(TemporalExpressions.perfectNever("Jonah has never been to Lisbon."));
        assertTrue(TemporalExpressions.perfectNever("They’ve never met."));
        assertFalse(TemporalExpressions.perfectNever("Jonah never drives."));
    }

    @Test
    void aStoppedLikingIsAFavorableEndingNeverAnUnfavorableStance() {
        var kale = TemporalExpressions.valence("Avery Lin doesn't like Kale anymore.").orElseThrow();
        assertEquals(Polarity.FAVORABLE, kale.polarity());
        assertTrue(kale.ending());
        var plain = TemporalExpressions.valence("Avery Lin doesn't like Kale.").orElseThrow();
        assertEquals(Polarity.UNFAVORABLE, plain.polarity());
        assertFalse(plain.ending());
        var hate = TemporalExpressions.valence("The user no longer hates jazz.").orElseThrow();
        assertEquals(Polarity.UNFAVORABLE, hate.polarity());
        assertTrue(hate.ending());
        assertEquals(Polarity.FAVORABLE, TemporalExpressions.valence("Loves bouldering.").orElseThrow().polarity());
        assertTrue(TemporalExpressions.valence("Lives in Ashgrove.").isEmpty());
    }

    // ─── Probes ───

    @Test
    void theProbeAnchorsAreAMonthEndAYearEndAndALeapDay() {
        assertEquals(List.of(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 12, 31), LocalDate.of(2028, 2, 29)),
                TemporalExpressions.NORMALIZER_PROBE_ANCHORS);
        assertTrue(TemporalExpressions.NORMALIZER_PROBES.contains("this June"));
    }

    @Test
    void theProbeOutputIsPinned() {
        assertEquals(PINNED_PROBES, TemporalExpressions.renderProbes());
    }

    private static final String PINNED_PROBES = """
            FINDER @2026-10-31
            Avery Lin has a standing call with Mateo Castillo every Sunday evening to plan the Thornbury Marathon in April.
              + [105,110) "April" phrase="in April" MONTH relative [2026-04]
              - [56,62) "Sunday" RECURRING
            Is renewing the Larkspur Inn booking for the Marrow Bay Retreat, which Harborlight Analytics is holding there this June.
              + [110,119) "this June" phrase="this June" MONTH relative [2026-06]
            Jonah Pell coaches juniors at the Ashgrove Rowing Club every Thursday evening and is training for the Thornbury Marathon this spring.
              + [121,132) "this spring" phrase="this spring" SEASON relative [2026-21]
              - [61,69) "Thursday" RECURRING
            Avery Lin says Ashgrove includes Larchmere House, their home since 2019, and the Juniper Clinic branch nearby.
              + [67,71) "2019" phrase="since 2019" YEAR absolute [2019]
            Avery Lin mentioned that Vela Design hired Felix Amari last year as its lead illustrator.
              + [55,64) "last year" phrase="last year" YEAR relative [2025]
            Loves bouldering and has climbed there with Tomas Varga for three years.
              + [60,71) "three years" phrase="for three years" DURATION relative [2023~] P3Y
            Avery Lin checks rota changes there on Fridays.
              - [39,46) "Fridays" RECURRING
            Avery Lin joins the reading circle every other Thursday.
              - [47,55) "Thursday" RECURRING
            Avery Lin is the on-call lead during the last week of every quarter.
              - [41,50) "last week" VAGUE
              - [54,67) "every quarter" RECURRING
            Jonah Pell tracks every late shipment before noon.
              - [45,49) "noon" CLOCK
            The launch slipped to Q3 2027.
              + [22,29) "Q3 2027" phrase="Q3 2027" QUARTER absolute [2027-35]
            The team closed the deal this quarter, ahead of the fiscal plan.
              - [25,37) "this quarter" FISCAL
            Revenue for FY2026 doubled and Q2 FY27 looks flat.
              - [12,18) "FY2026" FISCAL
              - [31,33) "Q2" FISCAL
              - [34,38) "FY27" FISCAL
            The invoice is due on 7/10 and the renewal on 12-03-2026.
              - [22,26) "7/10" NUMERIC_DM
              - [46,56) "12-03-2026" NUMERIC_DM
            The gym runs a retreat every June.
              - [29,33) "June" RECURRING
            They go hiking on Sundays and spend summers in Marrow Bay.
              - [18,25) "Sundays" RECURRING
              - [36,43) "summers" RECURRING
            The standup is at 9:30 am on Monday.
              - [18,25) "9:30 am" CLOCK
              - [29,35) "Monday" WEEKDAY
            She moved here recently and plans to visit soon.
              - [15,23) "recently" VAGUE
              - [43,47) "soon" VAGUE
            Two weeks before the offsite, Avery booked the inn.
              - [0,16) "Two weeks before" EVENT_RELATIVE
            The file report-2019.pdf sits at https://x.example/2020 and /srv/2021, tracked as JCLAW-2022.
              - [16,20) "2019" LITERAL
              - [51,55) "2020" LITERAL
              - [65,69) "2021" LITERAL
              - [88,92) "2022" LITERAL
            The firm was founded in 2019. Its logo (2019) still hangs there.
              + [24,28) "2019" phrase="in 2019" YEAR absolute [2019]
              + [40,44) "2019" phrase="2019" YEAR absolute [2019]
            The budget was $2020 and ticket #2021 closed.
              - [16,20) "2020" LITERAL
              - [33,37) "2021" LITERAL
            You may march on if you like.
            Avery Lin moved to Ashgrove in May 2019.
              + [31,39) "May 2019" phrase="in May 2019" MONTH absolute [2019-05]
            Avery Lin visited Lisbon on June 5, 2026 and again on 12th of March 2027.
              + [28,40) "June 5, 2026" phrase="on June 5, 2026" DAY absolute [2026-06-05]
              + [54,72) "12th of March 2027" phrase="on 12th of March 2027" DAY absolute [2027-03-12]
            Avery Lin met Dana on March 3.
              + [22,29) "March 3" phrase="on March 3" DAY relative [2026-03-03]
            The user lived in Porto from 2015 to 2019 and in Lisbon between March 2020 and June 2021.
              + [29,41) "2015 to 2019" phrase="from 2015 to 2019" RANGE absolute [2015/2019]
              + [64,88) "March 2020 and June 2021" phrase="between March 2020 and June 2021" RANGE absolute [2020-03/2021-06]
            The lease ran 2016–2018.
              + [14,23) "2016–2018" phrase="2016–2018" RANGE absolute [2016/2018]
            The clinic opened 3 years ago and moved two months ago.
              + [18,29) "3 years ago" phrase="3 years ago" YEAR relative [2023~]
              + [40,54) "two months ago" phrase="two months ago" MONTH relative [2026-08~]
            The roof was fixed 10 days ago and inspected yesterday.
              + [19,30) "10 days ago" phrase="10 days ago" DAY relative [2026-10-21]
              + [45,54) "yesterday" phrase="yesterday" DAY relative [2026-10-30]
            The user will retire in 2 years.
              + [21,31) "in 2 years" phrase="in 2 years" YEAR relative [2028~]
            Avery Lin has lived in Ashgrove for 6 months.
              + [36,44) "6 months" phrase="for 6 months" DURATION relative [2026-04~] P6M
            The house was renovated in the spring and painted last summer.
              + [31,37) "spring" phrase="in the spring" SEASON relative [2026-21]
              + [50,61) "last summer" phrase="last summer" SEASON relative [2026-22]
            The user grew up in the 1990s.
              - [20,29) "the 1990s" VAGUE
            The user hasn't used Osprey Dashboard since 2024.
              + [44,48) "2024" phrase="since 2024" YEAR absolute [2024]
            Avery Lin doesn't like Kale anymore.
            The user's anniversary is on 2026-02-15.
              + [29,39) "2026-02-15" phrase="on 2026-02-15" DAY absolute [2026-02-15]
            The board meets each spring and every year in April.
              + [46,51) "April" phrase="in April" MONTH relative [2026-04]
              - [16,27) "each spring" RECURRING
              - [32,42) "every year" RECURRING
            The festival is this winter, next to the Larkspur Inn.
              + [16,27) "this winter" phrase="this winter" SEASON relative [2026-24]
            The reunion is the day after the wedding, in autumn 2025.
              + [45,56) "autumn 2025" phrase="in autumn 2025" SEASON absolute [2025-23]
              - [15,28) "the day after" EVENT_RELATIVE
            NORMALIZER | @2026-10-31 | @2026-12-31 | @2028-02-29
            today | 2026-10-31 | 2026-12-31 | 2028-02-29
            tomorrow | 2026-11-01 | 2027-01-01 | 2028-03-01
            yesterday | 2026-10-30 | 2026-12-30 | 2028-02-28
            tonight | 2026-10-31 | 2026-12-31 | 2028-02-29
            last night | 2026-10-30 | 2026-12-30 | 2028-02-28
            this morning | 2026-10-31 | 2026-12-31 | 2028-02-29
            this week | 2026-10-31~ | 2026-12-31~ | 2028-02-29~
            last week | 2026-10-24~ | 2026-12-24~ | 2028-02-22~
            next week | 2026-11-07~ | 2027-01-07~ | 2028-03-07~
            this month | 2026-10 | 2026-12 | 2028-02
            last month | 2026-09 | 2026-11 | 2028-01
            next month | 2026-11 | 2027-01 | 2028-03
            this year | 2026 | 2026 | 2028
            last year | 2025 | 2025 | 2027
            next year | 2027 | 2027 | 2029
            this quarter | 2026-36 | 2026-36 | 2028-33
            last quarter | 2026-35 | 2026-35 | 2027-36
            next quarter | 2027-33 | 2027-33 | 2028-34
            Q3 | 2026-35 | 2026-35 | 2028-35
            Q3 2027 | 2027-35 | 2027-35 | 2027-35
            the third quarter of 2027 | 2027-35 | 2027-35 | 2027-35
            this June | 2026-06 | 2026-06 | 2028-06
            last June | 2026-06 | 2026-06 | 2027-06
            next June | 2027-06 | 2027-06 | 2028-06
            in June | 2026-06 | 2026-06 | 2028-06
            June 5 | 2026-06-05 | 2026-06-05 | 2028-06-05
            5 June | 2026-06-05 | 2026-06-05 | 2028-06-05
            June 2026 | 2026-06 | 2026-06 | 2026-06
            June 5, 2026 | 2026-06-05 | 2026-06-05 | 2026-06-05
            5 June 2026 | 2026-06-05 | 2026-06-05 | 2026-06-05
            2026-02-15 | 2026-02-15 | 2026-02-15 | 2026-02-15
            this spring | 2026-21 | 2026-21 | 2028-21
            this winter | 2026-24 | 2026-24 | 2027-24
            last summer | 2026-22 | 2026-22 | 2027-22
            next winter | 2026-24 | 2027-24 | 2028-24
            in the spring | 2026-21 | 2026-21 | 2028-21
            autumn 2025 | 2025-23 | 2025-23 | 2025-23
            fall of 2024 | 2024-23 | 2024-23 | 2024-23
            3 years ago | 2023~ | 2023~ | 2025~
            a year ago | 2025~ | 2025~ | 2027~
            two months ago | 2026-08~ | 2026-10~ | 2027-12~
            3 weeks ago | 2026-10-10~ | 2026-12-10~ | 2028-02-08~
            10 days ago | 2026-10-21 | 2026-12-21 | 2028-02-19
            a decade ago | 2016~ | 2016~ | 2018~
            in 2 years | 2028~ | 2028~ | 2030~
            in three days | 2026-11-03 | 2027-01-03 | 2028-03-03
            for three years | 2023~ P3Y | 2023~ P3Y | 2025~ P3Y
            for 6 months | 2026-04~ P6M | 2026-06~ P6M | 2027-08~ P6M
            for two weeks | 2026-10-17~ P2W | 2026-12-17~ P2W | 2028-02-15~ P2W
            for 10 days | 2026-10-21 P10D | 2026-12-21 P10D | 2028-02-19 P10D
            in 2019 | 2019 | 2019 | 2019
            since 2019 | 2019 | 2019 | 2019
            from 2019 to 2021 | 2019/2021 | 2019/2021 | 2019/2021
            between March 2020 and June 2021 | 2020-03/2021-06 | 2020-03/2021-06 | 2020-03/2021-06
            2019-2021 | 2019/2021 | 2019/2021 | 2019/2021
            """;
}
