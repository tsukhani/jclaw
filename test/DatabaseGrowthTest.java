import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.database.DatabaseGrowth;
import services.database.DatabaseGrowth.Change;
import services.database.DatabaseGrowth.FilePoint;
import services.database.DatabaseGrowth.TableRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The growth table's arithmetic over the daily samples, and the retention window (JF-496, JF-525). */
class DatabaseGrowthTest extends UnitTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-17");
    private static final String TAKEN = "2026-11-17T01:00:00Z";

    private static TableRow row(int daysAgo, String table, long bytes, long rows) {
        return new TableRow(TODAY.minusDays(daysAgo), table, bytes, rows);
    }

    private static FilePoint file(int daysAgo, long fileBytes, long liveBytes) {
        return new FilePoint(TODAY.minusDays(daysAgo), fileBytes, liveBytes, fileBytes - liveBytes);
    }

    /** The oldest day among the samples is the history's start, as it is when a day's table and file rows land together. */
    private static DatabaseGrowth.Growth compute(List<TableRow> tables, List<FilePoint> files) {
        var since = Stream.concat(tables.stream().map(TableRow::day), files.stream().map(FilePoint::day))
                .min(LocalDate::compareTo).orElse(TODAY);
        return DatabaseGrowth.compute(tables, files, TAKEN, since);
    }

    @Test
    void noSampleYetGivesAnEmptyTable() {
        var g = compute(List.of(), List.of());
        assertNull(g.sampledOn());
        assertNull(g.file());
        assertEquals(List.of(), g.tables());
        assertEquals(List.of(), g.fileTrend());
    }

    @Test
    void oneDayListsTheTablesBySizeWithTheirShareAndNoChangeYet() {
        var g = compute(List.of(row(0, "EVENT_LOG", 46, 232), row(0, "MESSAGE", 259, 75), row(0, "AGENT", 1, 74)),
                List.of(file(0, 565, 432)));
        assertEquals("2026-11-17", g.sampledOn());
        assertEquals("2026-11-17", g.historySince());
        assertEquals(List.of("MESSAGE", "EVENT_LOG", "AGENT"), g.tables().stream().map(t -> t.name()).toList());
        var message = g.tables().getFirst();
        assertEquals(259, message.bytes());
        assertEquals(75, message.rows());
        assertEquals(259.0 / 565, message.share(), "its part of the whole file, so every row adds up to it");
        assertNull(message.day());
        assertNull(message.week());
        assertEquals(0, g.growthDays());
        assertNull(message.growth(), "one day of history has no window to grow over");
        assertEquals(432 - 259 - 46 - 1, g.otherLiveBytes());
    }

    @Test
    void eachChangeComparesWithTheSampleThatManyDaysOlder() {
        var g = compute(List.of(row(30, "MESSAGE", 100, 10), row(7, "MESSAGE", 200, 20), row(1, "MESSAGE", 250, 25),
                row(0, "MESSAGE", 259, 26)), List.of(file(0, 565, 432)));
        var message = g.tables().getFirst();
        assertEquals(new Change(9, 1), message.day());
        assertEquals(new Change(59, 6), message.week());
        assertEquals(30, g.growthDays());
        assertEquals(new Change(159, 16), message.growth(), "thirty days kept: the growth is the 30-day change");
    }

    @Test
    void beforeThirtyDaysTheGrowthReachesBackToTheOldestSample() {
        var two = compute(List.of(row(2, "MESSAGE", 240, 24), row(1, "MESSAGE", 250, 25), row(0, "MESSAGE", 259, 26)),
                List.of(file(0, 565, 432)));
        assertEquals(2, two.growthDays());
        assertEquals(new Change(19, 2), two.tables().getFirst().growth());

        var week = compute(List.of(row(7, "MESSAGE", 200, 20), row(0, "MESSAGE", 259, 26)), List.of(file(0, 565, 432)));
        assertEquals(7, week.growthDays());
        assertEquals(week.tables().getFirst().week(), week.tables().getFirst().growth());
    }

    @Test
    void theGrowthWindowIsTheHistoryUpToThirtyDays() {
        for (int[] c : new int[][] {{1, 1}, {29, 29}, {30, 30}, {31, 30}, {45, 30}}) {
            var g = compute(List.of(row(c[0], "MESSAGE", 100, 10), row(0, "MESSAGE", 259, 26)),
                    List.of(file(0, 565, 432)));
            assertEquals(c[1], g.growthDays(), c[0] + " days of history");
            assertEquals(new Change(159, 16), g.tables().getFirst().growth(), c[0] + " days of history");
        }
    }

    @Test
    void aTableFirstSeenAfterTheOldestSampleHasNoGrowth() {
        var g = compute(List.of(row(2, "MESSAGE", 240, 24), row(0, "MESSAGE", 259, 26),
                row(1, "DATABASE_FILE_SAMPLE", 0, 0), row(0, "DATABASE_FILE_SAMPLE", 16, 1)), List.of(file(0, 565, 432)));
        var t = g.tables().stream().filter(r -> r.name().equals("DATABASE_FILE_SAMPLE")).findFirst().orElseThrow();
        assertEquals(new Change(16, 1), t.day());
        assertNull(t.growth(), "it has no sample at the window's start, the same as the other columns");
    }

    @Test
    void aMissingDayIsBridgedByTheNewestOlderSample() {
        var g = compute(List.of(row(3, "MESSAGE", 240, 24), row(0, "MESSAGE", 259, 26)), List.of(file(0, 565, 432)));
        var message = g.tables().getFirst();
        assertEquals(new Change(19, 2), message.day(), "the instance was down for two days");
        assertNull(message.week());
    }

    @Test
    void aTableFirstSeenYesterdayHasADayChangeAndNoWeek() {
        var g = compute(List.of(row(1, "DATABASE_FILE_SAMPLE", 0, 0), row(0, "DATABASE_FILE_SAMPLE", 16, 1)),
                List.of(file(0, 565, 432)));
        var t = g.tables().getFirst();
        assertEquals(new Change(16, 1), t.day());
        assertNull(t.week());
    }

    @Test
    void aSampleOlderThanTheLookBackIsNoReference() {
        var g = compute(List.of(row(62, "MESSAGE", 100, 10), row(0, "MESSAGE", 259, 26)), List.of(file(0, 565, 432)));
        assertNull(g.tables().getFirst().growth(), "62 days is past twice the 30-day window");
        var within = compute(List.of(row(61, "MESSAGE", 100, 10), row(0, "MESSAGE", 259, 26)),
                List.of(file(0, 565, 432)));
        assertEquals(new Change(159, 16), within.tables().getFirst().growth());
    }

    @Test
    void aTableGoneTodayIsNotListed() {
        var g = compute(List.of(row(1, "DROPPED", 5, 5), row(0, "MESSAGE", 259, 26)), List.of(file(0, 565, 432)));
        assertEquals(List.of("MESSAGE"), g.tables().stream().map(t -> t.name()).toList());
    }

    @Test
    void theFileTrendCoversTheLastThirtyDaysOldestFirst() {
        var files = new ArrayList<FilePoint>();
        for (int d = 31; d >= 0; d--) {
            files.add(file(d, 1000 + d, 800));
        }
        var g = compute(List.of(row(0, "MESSAGE", 259, 26)), files);
        assertEquals(30, g.fileTrend().size());
        assertEquals("2026-10-19", g.fileTrend().getFirst().day());
        assertEquals("2026-11-17", g.fileTrend().getLast().day());
        assertEquals(1000L, g.file().fileBytes());
    }

    @Test
    void theOtherLiveDataIsNeverNegativeAndUnknownWithoutTheFilesFigures() {
        var over = compute(List.of(row(0, "MESSAGE", 500, 26)), List.of(file(0, 565, 432)));
        assertEquals(0L, over.otherLiveBytes());

        var noFigures = compute(List.of(row(0, "MESSAGE", 259, 26)),
                List.of(new FilePoint(TODAY, null, null, null)));
        assertNull(noFigures.otherLiveBytes());
        assertNull(noFigures.tables().getFirst().share());
    }

    @Test
    void aSampleExactlyTheRetentionOldIsKeptAndOneDayOlderIsNot() {
        var from = DatabaseGrowth.keepFrom(TODAY, 30);
        assertEquals(TODAY.minusDays(30), from);
        assertFalse(TODAY.minusDays(30).isBefore(from), "kept");
        assertTrue(TODAY.minusDays(31).isBefore(from), "deleted");
    }

    @Test
    void aRetentionOfZeroKeepsEverySample() {
        assertNull(DatabaseGrowth.keepFrom(TODAY, 0));
    }

    @Test
    void theRetentionIsAWholeNumberOfDaysFromZeroTo3650() {
        var key = DatabaseGrowth.KEY_RETENTION;
        var refused = "db.sizeHistory.retentionDays must be a whole number of days from 0 to 3650; 0 keeps every sample.";
        assertNull(DatabaseGrowth.rejectionFor(key, "0"));
        assertNull(DatabaseGrowth.rejectionFor(key, "3650"));
        assertEquals(refused, DatabaseGrowth.rejectionFor(key, "3651"));
        assertEquals(refused, DatabaseGrowth.rejectionFor(key, "-1"));
        assertEquals(refused, DatabaseGrowth.rejectionFor(key, "a year"));
        assertNull(DatabaseGrowth.rejectionFor("db.sizeHistory.other", "a year"));
    }
}
