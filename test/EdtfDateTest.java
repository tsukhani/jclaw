import memory.ontology.EdtfDate;
import memory.ontology.EdtfDate.Precision;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.LocalDate;
import java.util.List;

/** JCLAW-1361: the EDTF date subset schema v3 stores. */
class EdtfDateTest extends UnitTest {

    private static void assertSpan(String edtf, String lo, String hi) {
        var date = EdtfDate.parse(edtf);
        assertEquals(LocalDate.parse(lo), date.lo(), edtf + " lo");
        assertEquals(LocalDate.parse(hi), date.hi(), edtf + " hi");
        assertEquals(edtf, date.toString(), edtf + " prints back");
    }

    private static void assertRefused(String edtf) {
        assertThrows(IllegalArgumentException.class, () -> EdtfDate.parse(edtf), edtf);
    }

    @Test
    void aYearCoversItsCalendarYear() {
        assertSpan("2019", "2019-01-01", "2020-01-01");
        assertEquals(Precision.YEAR, EdtfDate.parse("2019").precision());
    }

    @Test
    void aMonthCoversItsCalendarMonth() {
        assertSpan("2026-02", "2026-02-01", "2026-03-01");
        assertSpan("2028-02", "2028-02-01", "2028-03-01");
    }

    @Test
    void aDayCoversOneDay() {
        assertSpan("2026-02-15", "2026-02-15", "2026-02-16");
        assertSpan("2028-02-29", "2028-02-29", "2028-03-01");
    }

    @Test
    void seasonsAreMeteorologicalAndWinterRunsIntoTheNextYear() {
        assertSpan("2026-21", "2026-03-01", "2026-06-01");
        assertSpan("2026-22", "2026-06-01", "2026-09-01");
        assertSpan("2026-23", "2026-09-01", "2026-12-01");
        assertSpan("2026-24", "2026-12-01", "2027-03-01");
    }

    @Test
    void quartersAreCalendarQuarters() {
        assertSpan("2027-33", "2027-01-01", "2027-04-01");
        assertSpan("2027-34", "2027-04-01", "2027-07-01");
        assertSpan("2027-35", "2027-07-01", "2027-10-01");
        assertSpan("2027-36", "2027-10-01", "2028-01-01");
    }

    @Test
    void approximationWidensByThePrecision() {
        assertSpan("2025~", "2024-01-01", "2027-01-01");
        assertSpan("2026-08~", "2026-07-01", "2026-10-01");
        assertSpan("2026-10-10~", "2026-10-09", "2026-10-12");
        assertSpan("2026-21~", "2025-12-01", "2026-09-01");
        assertSpan("2027-35~", "2027-04-01", "2028-01-01");
    }

    @Test
    void theCodeRangesAreExactlySeasonsAndQuarters() {
        for (var accepted : List.of("2026-21", "2026-24", "2026-33", "2026-36", "2026-01", "2026-12")) {
            assertEquals(accepted, EdtfDate.parse(accepted).toString());
        }
        for (var refused : List.of("2026-20", "2026-25", "2026-32", "2026-37", "2026-00", "2026-13", "2019-25")) {
            assertRefused(refused);
        }
    }

    @Test
    void unspecifiedDigitsUncertaintyAndQualifiersAreRefused() {
        for (var refused : List.of("2019-XX", "201X", "2019?", "2019%", "?2019", "2019-~06", "2019-06~-01",
                "2019-02-30", "2026-21-01", "19", "20190", "", "2019-6", "+2019")) {
            assertRefused(refused);
        }
    }

    @Test
    void factoriesBuildTheSameDatesAsParse() {
        assertEquals(EdtfDate.parse("2026-24"), EdtfDate.ofSeason(2026, 4));
        assertEquals(EdtfDate.parse("2027-35"), EdtfDate.ofQuarter(2027, 3));
        assertEquals(EdtfDate.parse("2025~"), EdtfDate.ofYear(2025, true));
        assertEquals(EdtfDate.parse("2026-02-15"), EdtfDate.ofDay(LocalDate.of(2026, 2, 15), false));
    }

    @Test
    void plusYearsMovesTheYearOnly() {
        assertEquals(EdtfDate.parse("2027-24"), EdtfDate.parse("2026-24").plusYears(1));
        assertEquals(EdtfDate.parse("2027-10-10~"), EdtfDate.parse("2026-10-10~").plusYears(1));
        var leapDay = EdtfDate.parse("2028-02-29");
        assertThrows(IllegalArgumentException.class, () -> leapDay.plusYears(1));
    }
}
