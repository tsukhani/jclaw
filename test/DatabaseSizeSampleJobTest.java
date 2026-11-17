import jobs.DatabaseSizeSampleJob;
import models.DatabaseFileSample;
import models.DatabaseTableSample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConfigService;
import services.database.DatabaseGrowth;
import services.database.DatabaseSize;
import utils.AppClock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The daily size sample (JF-496): one a day in the app's zone, written whole, and pruned past its window. The file
 * figures come through {@code DatabaseSize.fileStatsForTest}, because the in-memory test database reports none.
 */
class DatabaseSizeSampleJobTest extends FunctionalTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-17");

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        DatabaseSize.fileStatsForTest = () -> new DatabaseSize.FileStats(564_826_112, 87, 88);
    }

    @AfterEach
    void clearSeams() {
        DatabaseSize.fileStatsForTest = null;
        DatabaseGrowth.tablesForTest = null;
        // The config cache outlives the rows Fixtures.deleteDatabase drops; delete() caches the absence.
        ConfigService.delete("app.timezone");
        ConfigService.delete(DatabaseGrowth.KEY_RETENTION);
        ConfigService.delete("jf496.sample.marker");
    }

    private static List<DatabaseTableSample> tablesOn(LocalDate day) {
        return DatabaseTableSample.find("sampledOn = ?1 ORDER BY tableName", day).fetch();
    }

    @Test
    void theFirstRunOfADayWritesTheFileAndEveryTable() {
        DatabaseGrowth.tablesForTest = () -> List.of(new DatabaseGrowth.TableSize("MESSAGE", 259_326_923, 75_226),
                new DatabaseGrowth.TableSize("EVENT_LOG", 45_963_441, 232_603));
        assertTrue(DatabaseGrowth.sampleIfDue(DAY));

        DatabaseFileSample file = DatabaseFileSample.find("sampledOn = ?1", DAY).first();
        assertEquals(564_826_112L, file.fileBytes);
        assertEquals(432_430_871L, file.liveBytes);
        assertEquals(132_395_241L, file.reclaimableBytes);
        var tables = tablesOn(DAY);
        assertEquals(List.of("EVENT_LOG", "MESSAGE"), tables.stream().map(t -> t.tableName).toList());
        assertEquals(259_326_923L, tables.get(1).bytes);
        assertEquals(75_226L, tables.get(1).rowCount);
    }

    @Test
    void aSecondRunTheSameDayWritesNothingAndTheNextDayWritesAgain() {
        DatabaseGrowth.tablesForTest = () -> List.of(new DatabaseGrowth.TableSize("MESSAGE", 1, 1));
        assertTrue(DatabaseGrowth.sampleIfDue(DAY));
        assertFalse(DatabaseGrowth.sampleIfDue(DAY));
        assertEquals(1, DatabaseFileSample.count());
        assertEquals(1, DatabaseTableSample.count());

        assertTrue(DatabaseGrowth.sampleIfDue(DAY.plusDays(1)));
        assertEquals(2, DatabaseFileSample.count());
    }

    @Test
    void theRealQueryReadsEveryTableInTheSchemaWithItsRowCount() {
        commitInFreshTx(() -> {
            ConfigService.set("jf496.sample.marker", "x");
            return null;
        });
        assertTrue(DatabaseGrowth.sampleIfDue(DAY));
        var names = tablesOn(DAY).stream().map(t -> t.tableName).toList();
        assertTrue(names.containsAll(List.of("CONFIG", "MESSAGE", "DATABASE_TABLE_SAMPLE", "DATABASE_FILE_SAMPLE")),
                () -> "tables read: " + names);
        DatabaseTableSample config = DatabaseTableSample.find("sampledOn = ?1 AND tableName = 'CONFIG'", DAY).first();
        assertEquals(models.Config.count(), config.rowCount);
    }

    @Test
    void aDatabaseThatReportsNoFileFiguresStillMarksTheDaySampled() {
        DatabaseSize.fileStatsForTest = () -> null;
        DatabaseGrowth.tablesForTest = List::of;
        assertTrue(DatabaseGrowth.sampleIfDue(DAY));
        DatabaseFileSample file = DatabaseFileSample.find("sampledOn = ?1", DAY).first();
        assertNull(file.fileBytes);
        assertFalse(DatabaseGrowth.sampleIfDue(DAY));
    }

    @Test
    void samplesPastTheRetentionWindowAreDropped() {
        DatabaseGrowth.tablesForTest = () -> List.of(new DatabaseGrowth.TableSize("MESSAGE", 1, 1));
        ConfigService.set(DatabaseGrowth.KEY_RETENTION, "30");
        DatabaseGrowth.sampleIfDue(DAY.minusDays(31));
        DatabaseGrowth.sampleIfDue(DAY.minusDays(30));
        DatabaseGrowth.sampleIfDue(DAY);

        List<DatabaseFileSample> kept = DatabaseFileSample.find("ORDER BY sampledOn").fetch();
        assertEquals(List.of(DAY.minusDays(30), DAY), kept.stream().map(f -> f.sampledOn).toList());
        assertEquals(List.of(), tablesOn(DAY.minusDays(31)));
        assertEquals(1, tablesOn(DAY.minusDays(30)).size());
    }

    @Test
    void aRetentionOfZeroKeepsEverySample() {
        DatabaseGrowth.tablesForTest = List::of;
        ConfigService.set(DatabaseGrowth.KEY_RETENTION, "0");
        DatabaseGrowth.sampleIfDue(DAY.minusDays(4000));
        DatabaseGrowth.sampleIfDue(DAY);
        assertEquals(2, DatabaseFileSample.count());
    }

    @Test
    void theJobSamplesTheDayInTheAppsTimeZoneNotUtcs() {
        DatabaseGrowth.tablesForTest = List::of;
        ConfigService.set("app.timezone", "Asia/Singapore");
        AppClock.runWith(Clock.fixed(Instant.parse("2026-11-16T20:30:00Z"), ZoneOffset.UTC),
                () -> new DatabaseSizeSampleJob().doJob());
        assertEquals(List.of(DAY), DatabaseFileSample.<DatabaseFileSample>findAll().stream().map(f -> f.sampledOn).toList(),
                "04:30 on the 17th in Singapore, still the 16th in UTC");
    }

    private static <T> T commitInFreshTx(java.util.function.Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) {
            throw new IllegalStateException(err.get());
        }
        return ref.get();
    }
}
