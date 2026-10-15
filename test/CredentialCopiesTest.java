import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.database.CredentialCopies;
import services.database.CredentialCopies.OldCopies;
import services.database.H2Maintenance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * JCLAW-1455: when the data file, and so a backup of it, held credential plaintext. A backup is marked by when it was
 * taken against the recorded windows, never by guessing from file dates alone.
 */
class CredentialCopiesTest extends UnitTest {

    private static final Instant BEFORE = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant MIGRATED = Instant.parse("2026-10-16T01:00:00Z");
    private static final Instant BETWEEN = Instant.parse("2026-10-16T02:00:00Z");
    private static final Instant COMPACTED = Instant.parse("2026-10-16T03:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-10-17T00:00:00Z");

    @TempDir
    Path tmp;

    @Test
    void theFirstPassOpensTheWindowEveryEarlierVersionLeftAndACompactionClosesIt() {
        var migrated = CredentialCopies.NONE.afterPass(3, 0, MIGRATED);
        assertEquals(MIGRATED.toString(), migrated.encryptedAt(), "the migration's completion is recorded");
        assertEquals(OldCopies.PLAINTEXT, migrated.due());
        assertTrue(migrated.holdsPlaintext(BEFORE), "a backup from before the migration holds plaintext");
        assertTrue(migrated.holdsPlaintext(BETWEEN), "and so does one taken before the compaction, from the free pages");

        var compacted = migrated.afterCompaction(COMPACTED);
        assertNull(compacted.due());
        assertEquals(COMPACTED.toString(), compacted.compactedAt());
        assertTrue(compacted.holdsPlaintext(BETWEEN));
        assertFalse(compacted.holdsPlaintext(AFTER), "one taken after the compaction holds none");

        var again = compacted.afterPass(0, 0, AFTER);
        assertEquals(compacted, again, "a later pass with nothing to seal changes nothing");
    }

    @Test
    void aFirstPassWithNoPlaintextClosesTheWindowAtOnce() {
        var copies = CredentialCopies.NONE.afterPass(0, 0, MIGRATED);
        assertNull(copies.due(), "nothing was sealed, so nothing waits for a compaction");
        assertTrue(copies.holdsPlaintext(BEFORE), "a backup an earlier version took is still marked");
        assertFalse(copies.holdsPlaintext(BETWEEN));
    }

    @Test
    void aRotationLeavesTheOldSecretsCopiesForACompactionButMarksNoBackup() {
        var copies = CredentialCopies.NONE.afterPass(0, 0, MIGRATED).afterPass(0, 4, BETWEEN);
        assertEquals(OldCopies.PREVIOUS_SECRET, copies.due());
        assertFalse(copies.holdsPlaintext(BETWEEN), "a ciphertext under the old secret is no plaintext");
        assertNull(copies.afterCompaction(COMPACTED).due());
    }

    @Test
    void plaintextBroughtBackOpensANewWindowWhetherAPassOrARestoreFindsIt() {
        var clean = CredentialCopies.NONE.afterPass(1, 0, MIGRATED).afterCompaction(COMPACTED);

        var resealed = clean.afterPass(2, 0, AFTER);
        assertEquals(OldCopies.PLAINTEXT, resealed.due(), "a pass that seals plaintext again: a restore of an old backup");
        assertFalse(resealed.holdsPlaintext(AFTER.minusSeconds(1)), "a backup between the two windows stays clean");
        assertTrue(resealed.holdsPlaintext(AFTER.plusSeconds(1)));

        var restored = clean.afterRestore(BETWEEN, AFTER);
        assertEquals(OldCopies.PLAINTEXT, restored.due(),
                "a backup taken before the compaction brings back plaintext the pass cannot see, in its free pages");
        assertTrue(restored.holdsPlaintext(AFTER.plusSeconds(1)));
        assertEquals(clean, clean.afterRestore(AFTER, AFTER.plusSeconds(60)), "restoring a clean backup changes nothing");
        assertEquals(CredentialCopies.NONE, CredentialCopies.NONE.afterRestore(BEFORE, AFTER),
                "before the first pass there is no record to keep");
    }

    @Test
    void theRecordRoundTripsThroughItsFileAndAnUnreadableOneIsNone() throws Exception {
        assertEquals(CredentialCopies.NONE, CredentialCopies.read(tmp), "no file is no record");
        var copies = CredentialCopies.NONE.afterPass(1, 0, MIGRATED).afterCompaction(COMPACTED).afterPass(0, 1, AFTER);
        copies.write(tmp);
        assertEquals(copies, CredentialCopies.read(tmp));
        assertFalse(Files.exists(tmp.resolve(CredentialCopies.FILE + ".tmp")));

        Files.writeString(tmp.resolve(CredentialCopies.FILE), "{not json");
        assertEquals(CredentialCopies.NONE, CredentialCopies.read(tmp));
        Files.writeString(tmp.resolve(CredentialCopies.FILE), "{}");
        assertEquals(CredentialCopies.NONE, CredentialCopies.read(tmp), "an empty record reads as none");
    }

    @Test
    void aBackupIsDatedByItsDatabaseEntryNotByWhenItWasCopiedOrUploaded() throws Exception {
        var zip = tmp.resolve("upload-20261020T000000Z.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            var entry = new ZipEntry(H2Maintenance.DATA_FILE);
            entry.setLastModifiedTime(FileTime.from(BEFORE));
            out.putNextEntry(entry);
            out.write("H:2,".getBytes());
            out.closeEntry();
        }
        Files.setLastModifiedTime(zip, FileTime.from(AFTER));
        assertEquals(BEFORE, CredentialCopies.takenAt(zip), "an upload keeps the time its backup was taken");

        var raw = tmp.resolve("jclaw-0.19.40-20261001T000000Z.mv.db");
        Files.writeString(raw, "H:2,");
        Files.setLastModifiedTime(raw, FileTime.from(BETWEEN));
        assertEquals(BETWEEN, CredentialCopies.takenAt(raw), "a raw copy has only its file time");
    }
}
