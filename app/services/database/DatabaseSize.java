package services.database;

import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.ConfigService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much of the open data file a compaction would free, by H2's own figures, and when the database card flags it
 * (JF-495). Nothing here compacts: the operator does, from the card or {@code jclaw.sh compact}.
 */
public final class DatabaseSize {

    public static final String KEY_FLAG_AT_PERCENT = "db.compaction.flagAtPercent";
    public static final String KEY_FLAG_AT_MB = "db.compaction.flagAtMb";
    // A share, not a fixed amount, decides: H2 leaves about 20% of a healthy file unused whatever its size, so a fixed
    // amount would flag every large file for good, while the bloat a compaction is for left 79% unused (13 Nov 2026).
    public static final int DEFAULT_FLAG_AT_PERCENT = 50;
    public static final int DEFAULT_FLAG_AT_MB = 100;
    static final int MAX_FLAG_AT_MB = 1_000_000;
    // formatSize's unit, so the amount the operator types reads the way the card prints sizes.
    static final long MB = 1024L * 1024;

    private static final String FILE_SIZE = "info.FILE_SIZE";
    private static final String FILL_RATE = "info.FILL_RATE";
    private static final String CHUNKS_FILL_RATE = "info.CHUNKS_FILL_RATE";

    /** Test seam: the in-memory test database reports no file figures. A null from it reads as a failed query. */
    @SuppressWarnings("java:S3077") // a test seam swapped whole, never changed in place
    public static volatile @Nullable FileStatsForTest fileStatsForTest;

    @FunctionalInterface
    public interface FileStatsForTest {
        @Nullable FileStats read();
    }

    /**
     * H2's figures for the data file, as {@code INFORMATION_SCHEMA.SETTINGS} reports them.
     *
     * @param fillRate       the share of the file's blocks that chunks occupy, a whole percentage
     * @param chunksFillRate the share of those chunks that is still live, a whole percentage
     */
    public record FileStats(long fileBytes, int fillRate, int chunksFillRate) {

        public long liveBytes() {
            return fileBytes * percent(fillRate) * percent(chunksFillRate) / 10_000;
        }

        /**
         * What a compaction would free. On a copy of a 556 MB backup this said 144.0 MB, and the compaction freed
         * 140.1 MB; counting live pages instead said 185 MB, because pages differ in size.
         */
        public long reclaimableBytes() {
            return fileBytes - liveBytes();
        }

        private static long percent(int rate) {
            return Math.clamp(rate, 0, 100);
        }
    }

    private DatabaseSize() {}

    /** The open data file's figures, or null when the database reports none, as an in-memory one does. */
    public static @Nullable FileStats fileStats() {
        var seam = fileStatsForTest;
        if (seam != null) {
            return seam.read();
        }
        try {
            List<?> rows = JPA.em().createNativeQuery("SELECT SETTING_NAME, SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS"
                    + " WHERE SETTING_NAME IN ('" + FILE_SIZE + "', '" + FILL_RATE + "', '" + CHUNKS_FILL_RATE + "')")
                    .getResultList();
            var values = new HashMap<String, String>();
            for (var row : rows) {
                var cells = (Object[]) row;
                values.put(String.valueOf(cells[0]), String.valueOf(cells[1]));
            }
            return parse(values);
        } catch (RuntimeException _) {
            // The card leaves the figure out; the status it rides on must still answer.
            return null;
        }
    }

    public static @Nullable FileStats parse(Map<String, String> values) {
        var size = values.get(FILE_SIZE);
        var fill = values.get(FILL_RATE);
        var chunks = values.get(CHUNKS_FILL_RATE);
        if (size == null || fill == null || chunks == null) {
            return null;
        }
        try {
            var bytes = Long.parseLong(size.trim());
            return bytes > 0 ? new FileStats(bytes, Integer.parseInt(fill.trim()), Integer.parseInt(chunks.trim())) : null;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** The share of the file a compaction must free before the card flags it; 0 never flags. */
    public static int flagAtPercent() {
        return Math.clamp(ConfigService.getInt(KEY_FLAG_AT_PERCENT, DEFAULT_FLAG_AT_PERCENT), 0, 100);
    }

    /** The least a compaction must free, in MB, before the card flags it, whatever the share; 0 sets no minimum. */
    public static int flagAtMb() {
        return Math.clamp(ConfigService.getInt(KEY_FLAG_AT_MB, DEFAULT_FLAG_AT_MB), 0, MAX_FLAG_AT_MB);
    }

    public static boolean flagged(@Nullable FileStats stats, int flagAtPercent, int flagAtMb) {
        if (stats == null || flagAtPercent <= 0) {
            return false;
        }
        var reclaimable = stats.reclaimableBytes();
        return reclaimable * 100 >= flagAtPercent * stats.fileBytes() && reclaimable >= flagAtMb * MB;
    }

    // ---- config validation (called from ConfigService.setWithSideEffects) ----

    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        return switch (key) {
            case KEY_FLAG_AT_PERCENT -> wholeNumberUpTo(value, 100) ? null
                    : KEY_FLAG_AT_PERCENT + " must be a whole number from 0 to 100; 0 never flags a compaction.";
            case KEY_FLAG_AT_MB -> wholeNumberUpTo(value, MAX_FLAG_AT_MB) ? null
                    : KEY_FLAG_AT_MB + " must be a whole number of MB from 0 to 1000000; 0 sets no minimum.";
            default -> null;
        };
    }

    static boolean wholeNumberUpTo(@Nullable String value, int max) {
        try {
            var n = Integer.parseInt(value == null ? "" : value.trim());
            return n >= 0 && n <= max;
        } catch (NumberFormatException _) {
            return false;
        }
    }
}
