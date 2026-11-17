package services.database;

import jobs.RetentionDays;
import models.DatabaseFileSample;
import models.DatabaseTableSample;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import utils.AppClock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where the database grows (JF-496): one sample a day of each table's size and the data file's, and each table's
 * change over a day, a week and 30 days from them.
 */
public final class DatabaseGrowth {

    public static final String KEY_RETENTION = "db.sizeHistory.retentionDays";
    public static final int DEFAULT_RETENTION_DAYS = 365;
    static final int MAX_RETENTION_DAYS = 3650;
    /** The days the trend columns and the file's view cover, and the longest change column. */
    public static final int TREND_DAYS = 30;
    // The oldest sample a change is read from: a reference older than twice the longest column reads as none.
    private static final int LOOKBACK_DAYS = 2 * TREND_DAYS + 1;

    /** Test seam: the in-memory test database has no pages on disk, so every table there reads 0 bytes. */
    @SuppressWarnings("java:S3077") // a test seam swapped whole, never changed in place
    public static volatile @Nullable TablesForTest tablesForTest;

    @FunctionalInterface
    public interface TablesForTest {
        List<TableSize> read();
    }

    public record TableSize(String name, long bytes, long rows) {}

    /** One table's sample on one day, as {@link #compute} reads them. */
    public record TableRow(LocalDate day, String name, long bytes, long rows) {}

    /** The file's sample on one day; its figures are null when the database reported none. */
    public record FilePoint(LocalDate day, @Nullable Long fileBytes, @Nullable Long liveBytes,
                            @Nullable Long reclaimableBytes) {}

    /** A {@link FilePoint} as the API sends it, its day ISO-written: the shared Gson has no {@code LocalDate} adapter. */
    public record FileDay(String day, @Nullable Long fileBytes, @Nullable Long liveBytes, @Nullable Long reclaimableBytes) {
        static FileDay of(FilePoint p) {
            return new FileDay(p.day().toString(), p.fileBytes(), p.liveBytes(), p.reclaimableBytes());
        }
    }

    public record Change(long bytes, long rows) {}

    /**
     * One table now, and its change against its newest sample at least that many days older; a change is null when
     * there is none within the look-back.
     *
     * @param share the table's part of the data file, from 0 to 1, or null when the file reported no size
     * @param trend its bytes on each sampled day of the last {@value #TREND_DAYS}, oldest first
     */
    public record TableGrowth(String name, long bytes, long rows, @Nullable Double share,
                              @Nullable Change day, @Nullable Change week, @Nullable Change month, List<Long> trend) {}

    /**
     * The growth table, as of the newest sample.
     *
     * @param otherLiveBytes the live data no table's figure counts, such as its indexes; null without the file's figures
     * @param historySince   the oldest sample still kept
     */
    public record Growth(@Nullable String sampledOn, @Nullable String takenAt, @Nullable String historySince,
                         @Nullable FileDay file, @Nullable Long otherLiveBytes, List<TableGrowth> tables,
                         List<FileDay> fileTrend) {}

    private DatabaseGrowth() {}

    // ---- sampling ----

    /**
     * Writes {@code today}'s sample unless it has one, then drops the samples past the retention window. Called in a
     * transaction; returns whether it wrote.
     */
    public static boolean sampleIfDue(LocalDate today) {
        var wrote = false;
        if (DatabaseFileSample.count("sampledOn = ?1", today) == 0) {
            var stats = DatabaseSize.fileStats();
            for (var t : readTables()) {
                var row = new DatabaseTableSample();
                row.sampledOn = today;
                row.tableName = t.name();
                row.bytes = t.bytes();
                row.rowCount = t.rows();
                row.save();
            }
            var file = new DatabaseFileSample();
            file.sampledOn = today;
            file.takenAt = AppClock.now();
            if (stats != null) {
                file.fileBytes = stats.fileBytes();
                file.liveBytes = stats.liveBytes();
                file.reclaimableBytes = stats.reclaimableBytes();
            }
            file.save();
            wrote = true;
        }
        prune(today);
        return wrote;
    }

    /** About a second on the live 565 MB file, once a day: {@code DISK_SPACE_USED} reads every page of the table. */
    static List<TableSize> readTables() {
        var seam = tablesForTest;
        if (seam != null) {
            return seam.read();
        }
        List<?> rows = JPA.em().createNativeQuery("SELECT TABLE_NAME,"
                + " DISK_SPACE_USED('\"' || TABLE_SCHEMA || '\".\"' || TABLE_NAME || '\"'), ROW_COUNT_ESTIMATE"
                + " FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'")
                .getResultList();
        var out = new ArrayList<TableSize>();
        for (var row : rows) {
            var cells = (Object[]) row;
            out.add(new TableSize(String.valueOf(cells[0]), ((Number) cells[1]).longValue(),
                    ((Number) cells[2]).longValue()));
        }
        return out;
    }

    public static int retentionDays() {
        return RetentionDays.fromConfig(KEY_RETENTION, DEFAULT_RETENTION_DAYS, MAX_RETENTION_DAYS,
                DatabaseService.CATEGORY);
    }

    /** The oldest day kept: a sample exactly {@code retentionDays} old stays, so a 30-day window still has its start. */
    public static @Nullable LocalDate keepFrom(LocalDate today, int retentionDays) {
        return retentionDays == RetentionDays.DISABLED ? null : today.minusDays(retentionDays);
    }

    static void prune(LocalDate today) {
        var from = keepFrom(today, retentionDays());
        if (from != null) {
            DatabaseTableSample.delete("sampledOn < ?1", from);
            DatabaseFileSample.delete("sampledOn < ?1", from);
        }
    }

    // ---- reading ----

    /** The growth table as of the newest sample; empty when none is kept yet. */
    public static Growth growth() {
        DatabaseFileSample newest = DatabaseFileSample.find("ORDER BY sampledOn DESC").first();
        if (newest == null) {
            return new Growth(null, null, null, null, null, List.of(), List.of());
        }
        var from = newest.sampledOn.minusDays(LOOKBACK_DAYS);
        var tables = new ArrayList<TableRow>();
        for (var o : DatabaseTableSample.find("sampledOn >= ?1", from).fetch()) {
            var s = (DatabaseTableSample) o;
            tables.add(new TableRow(s.sampledOn, s.tableName, s.bytes, s.rowCount));
        }
        var files = new ArrayList<FilePoint>();
        for (var o : DatabaseFileSample.find("sampledOn >= ?1", from).fetch()) {
            var s = (DatabaseFileSample) o;
            files.add(new FilePoint(s.sampledOn, s.fileBytes, s.liveBytes, s.reclaimableBytes));
        }
        DatabaseFileSample oldest = DatabaseFileSample.find("ORDER BY sampledOn ASC").first();
        return compute(tables, files, newest.takenAt.toString(), oldest == null ? newest.sampledOn : oldest.sampledOn);
    }

    /** The growth table from the samples read, as of the newest file sample among them. */
    public static Growth compute(List<TableRow> tables, List<FilePoint> files, @Nullable String takenAt,
                                 LocalDate historySince) {
        var latestFile = files.stream().max(Comparator.comparing(FilePoint::day)).orElse(null);
        if (latestFile == null) {
            return new Growth(null, takenAt, historySince.toString(), null, null, List.of(), List.of());
        }
        var today = latestFile.day();
        var byTable = new HashMap<String, TreeMap<LocalDate, TableRow>>();
        for (var row : tables) {
            if (!row.day().isAfter(today)) {
                byTable.computeIfAbsent(row.name(), _ -> new TreeMap<>()).put(row.day(), row);
            }
        }
        var live = latestFile.liveBytes();
        var size = latestFile.fileBytes();
        var trendFrom = today.minusDays(TREND_DAYS - 1L);
        var out = new ArrayList<TableGrowth>();
        long counted = 0;
        for (Map.Entry<String, TreeMap<LocalDate, TableRow>> e : byTable.entrySet()) {
            var now = e.getValue().get(today);
            if (now == null) {
                continue;
            }
            counted += now.bytes();
            var trend = e.getValue().tailMap(trendFrom, true).values().stream().map(TableRow::bytes).toList();
            out.add(new TableGrowth(now.name(), now.bytes(), now.rows(),
                    size == null || size <= 0 ? null : (double) now.bytes() / size,
                    change(e.getValue(), now, 1), change(e.getValue(), now, 7), change(e.getValue(), now, TREND_DAYS),
                    trend));
        }
        out.sort(Comparator.comparingLong(TableGrowth::bytes).reversed().thenComparing(TableGrowth::name));
        var fileTrend = files.stream().filter(f -> !f.day().isBefore(trendFrom) && !f.day().isAfter(today))
                .sorted(Comparator.comparing(FilePoint::day)).map(FileDay::of).toList();
        return new Growth(today.toString(), takenAt, historySince.toString(), FileDay.of(latestFile),
                live == null ? null : Math.max(0, live - counted), out, fileTrend);
    }

    private static @Nullable Change change(TreeMap<LocalDate, TableRow> samples, TableRow now, int days) {
        var then = samples.floorEntry(now.day().minusDays(days));
        if (then == null || then.getKey().isBefore(now.day().minusDays(LOOKBACK_DAYS))) {
            return null;
        }
        return new Change(now.bytes() - then.getValue().bytes(), now.rows() - then.getValue().rows());
    }

    // ---- config validation (called from ConfigService.setWithSideEffects) ----

    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        return !key.equals(KEY_RETENTION) || DatabaseSize.wholeNumberUpTo(value, MAX_RETENTION_DAYS) ? null
                : KEY_RETENTION + " must be a whole number of days from 0 to 3650; 0 keeps every sample.";
    }
}
