import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.database.DatabaseSize;
import services.database.DatabaseSize.FileStats;

import java.util.Map;

/**
 * What a compaction would free, and when the database card flags it (JF-495). The figures of the first test are
 * H2's own for a copy of a real 556 MB backup, which a compaction then shrank by 140.1 MB.
 */
class DatabaseSizeTest extends UnitTest {

    private static final long MB = 1024L * 1024;

    @Test
    void theMeasuredBackupCopyEstimatesWhatItsCompactionFreed() {
        var copy = new FileStats(556_109_824, 95, 78);
        assertEquals(412_077_379, copy.liveBytes());
        assertEquals(144_032_445, copy.reclaimableBytes());
    }

    @Test
    void aFileThatIsAllLiveDataFreesNothing() {
        assertEquals(0, new FileStats(416_055_296, 100, 100).reclaimableBytes());
    }

    @Test
    void theJustCompactedCopyEstimatesItsOneRemainingPercent() {
        assertEquals(4_160_553, new FileStats(416_055_296, 100, 99).reclaimableBytes());
    }

    @Test
    void aRateOutsideZeroToAHundredIsHeldToThatRange() {
        assertEquals(0, new FileStats(1_000_000, 140, 100).reclaimableBytes());
        assertEquals(1_000_000, new FileStats(1_000_000, -5, 100).reclaimableBytes());
    }

    @Test
    void aHealthyFileStaysUnflaggedWhateverItsSize() {
        // 17 Nov 2026's live figures: about 23% unused, which H2 leaves in normal running.
        assertFalse(DatabaseSize.flagged(new FileStats(564_826_112, 87, 88), 50, 100));
        assertFalse(DatabaseSize.flagged(new FileStats(5L * 1024 * MB, 87, 88), 50, 100),
                "a fixed amount would flag this 5 GB file for good; the share does not");
    }

    @Test
    void theBloatBeforeTheThirteenthOfNovemberFlags() {
        assertTrue(DatabaseSize.flagged(new FileStats(1_746_000_000, 100, 21), 50, 100));
    }

    @Test
    void theShareFlagsAtItsPercentAndNotOnePercentBelow() {
        var half = new FileStats(1000 * MB, 100, 50);
        assertTrue(DatabaseSize.flagged(half, 50, 100));
        assertFalse(DatabaseSize.flagged(half, 51, 100));
        assertFalse(DatabaseSize.flagged(new FileStats(1000 * MB, 100, 51), 50, 100));
    }

    @Test
    void theFloorFlagsAtItsAmountAndNotOneByteBelow() {
        var exactly = new FileStats(200 * MB, 100, 50);
        assertEquals(100 * MB, exactly.reclaimableBytes());
        assertTrue(DatabaseSize.flagged(exactly, 50, 100));
        var oneByteShort = new FileStats(200 * MB - 2, 100, 50);
        assertEquals(100 * MB - 1, oneByteShort.reclaimableBytes());
        assertFalse(DatabaseSize.flagged(oneByteShort, 50, 100));
    }

    @Test
    void aFloorOfZeroLetsASmallFileFlagOnItsShareAlone() {
        var small = new FileStats(10 * MB, 100, 40);
        assertFalse(DatabaseSize.flagged(small, 50, 100));
        assertTrue(DatabaseSize.flagged(small, 50, 0));
    }

    @Test
    void aShareOfZeroOrNoFiguresNeverFlags() {
        assertFalse(DatabaseSize.flagged(new FileStats(1_746_000_000, 100, 21), 0, 0));
        assertFalse(DatabaseSize.flagged(null, 50, 100));
    }

    @Test
    void theSettingsRowsParseIntoTheFileFigures() {
        assertEquals(new FileStats(564_826_112, 87, 88), DatabaseSize.parse(Map.of(
                "info.FILE_SIZE", "564826112", "info.FILL_RATE", " 87", "info.CHUNKS_FILL_RATE", "88 ")));
    }

    @Test
    void missingUnreadableOrEmptyFiguresGiveNoEstimate() {
        assertNull(DatabaseSize.parse(Map.of()), "an in-memory database reports none");
        assertNull(DatabaseSize.parse(Map.of("info.FILE_SIZE", "564826112", "info.FILL_RATE", "87")));
        assertNull(DatabaseSize.parse(Map.of(
                "info.FILE_SIZE", "lots", "info.FILL_RATE", "87", "info.CHUNKS_FILL_RATE", "88")));
        assertNull(DatabaseSize.parse(Map.of(
                "info.FILE_SIZE", "0", "info.FILL_RATE", "87", "info.CHUNKS_FILL_RATE", "88")));
    }

    @Test
    void theShareIsAWholeNumberFromZeroToAHundred() {
        var key = DatabaseSize.KEY_FLAG_AT_PERCENT;
        var refused = "db.compaction.flagAtPercent must be a whole number from 0 to 100; 0 never flags a compaction.";
        assertNull(DatabaseSize.rejectionFor(key, "0"));
        assertNull(DatabaseSize.rejectionFor(key, " 100 "));
        assertEquals(refused, DatabaseSize.rejectionFor(key, "101"));
        assertEquals(refused, DatabaseSize.rejectionFor(key, "-1"));
        assertEquals(refused, DatabaseSize.rejectionFor(key, "50.5"));
        assertEquals(refused, DatabaseSize.rejectionFor(key, "half"));
        assertEquals(refused, DatabaseSize.rejectionFor(key, ""));
        assertEquals(refused, DatabaseSize.rejectionFor(key, null));
    }

    @Test
    void theFloorIsAWholeNumberOfMbFromZeroToAMillion() {
        var key = DatabaseSize.KEY_FLAG_AT_MB;
        var refused = "db.compaction.flagAtMb must be a whole number of MB from 0 to 1000000; 0 sets no minimum.";
        assertNull(DatabaseSize.rejectionFor(key, "0"));
        assertNull(DatabaseSize.rejectionFor(key, "1000000"));
        assertEquals(refused, DatabaseSize.rejectionFor(key, "1000001"));
        assertEquals(refused, DatabaseSize.rejectionFor(key, "-1"));
        assertEquals(refused, DatabaseSize.rejectionFor(key, "1.5"));
        assertEquals(refused, DatabaseSize.rejectionFor(key, "lots"));
    }

    @Test
    void anotherKeyUnderTheSamePrefixIsNotThisRulesToRefuse() {
        assertNull(DatabaseSize.rejectionFor("db.compaction.other", "lots"));
    }
}
