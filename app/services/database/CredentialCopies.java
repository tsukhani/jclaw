package services.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Where the stored credentials' plaintext may still sit outside its ciphertext (JCLAW-1455), kept beside the data file as
 * {@value #FILE}. Encrypting a row leaves the plaintext it replaced in the file's free pages, and a backup copies those
 * pages too (both measured on H2 2.5.252), so the file holds plaintext from before the first boot pass until the first
 * compaction after it, and again from a restore that brings plaintext back. Each stretch is a {@link Window}; a backup
 * taken inside one holds plaintext. Standalone on the JDK and Gson, like {@link H2Maintenance}, because {@code jclaw.sh}
 * compacts and restores with the app stopped.
 *
 * @param encryptedAt when the first boot pass finished: the migration
 * @param plaintext   the windows, oldest first; a null {@code from} reaches back to the first backup ever taken
 * @param due         what a compaction would remove from the data file now, or null when there is nothing
 * @param compactedAt when the last compaction finished
 */
public record CredentialCopies(@Nullable String encryptedAt, List<Window> plaintext, @Nullable OldCopies due,
                               @Nullable String compactedAt) {

    public static final String FILE = "credential-encryption.json";

    public static final CredentialCopies NONE = new CredentialCopies(null, List.of(), null, null);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum OldCopies {
        /** Plaintext from before the values were encrypted. */
        PLAINTEXT,
        /** Ciphertext under the secret a rotation replaced. */
        PREVIOUS_SECRET
    }

    /** A stretch when the data file held credential plaintext; a null end is open. */
    public record Window(@Nullable String from, @Nullable String until) {

        boolean open() {
            return until == null;
        }

        boolean covers(Instant at) {
            return (from == null || !at.isBefore(Instant.parse(from))) && (until == null || at.isBefore(Instant.parse(until)));
        }
    }

    public CredentialCopies {
        // Gson builds records through this constructor and passes null for a missing member.
        plaintext = plaintext == null ? List.of() : List.copyOf(plaintext);
    }

    /** True when a backup taken at {@code takenAt} copied a data file holding credential plaintext. */
    public boolean holdsPlaintext(Instant takenAt) {
        return plaintext.stream().anyMatch(w -> w.covers(takenAt));
    }

    /**
     * After a boot pass that sealed {@code sealed} plaintext values and re-sealed {@code rekeyed} values from the
     * previous secret. The first pass closes the window every earlier version's writes opened, at once when it found no
     * plaintext to seal; a later pass that seals plaintext opens a new one.
     */
    public CredentialCopies afterPass(int sealed, int rekeyed, Instant at) {
        var windows = new ArrayList<>(plaintext);
        if (encryptedAt == null) {
            windows.add(new Window(null, sealed > 0 ? null : at.toString()));
        } else if (sealed > 0 && windows.stream().noneMatch(Window::open)) {
            windows.add(new Window(at.toString(), null));
        }
        return new CredentialCopies(encryptedAt == null ? at.toString() : encryptedAt, windows,
                dueAfter(windows, rekeyed > 0), compactedAt);
    }

    /** After a restore of a backup taken at {@code takenAt}: one taken inside a window brings its plaintext back. */
    public CredentialCopies afterRestore(Instant takenAt, Instant at) {
        if (encryptedAt == null || !holdsPlaintext(takenAt) || plaintext.stream().anyMatch(Window::open)) {
            return this;
        }
        var windows = new ArrayList<>(plaintext);
        windows.add(new Window(at.toString(), null));
        return new CredentialCopies(encryptedAt, windows, OldCopies.PLAINTEXT, compactedAt);
    }

    /** After a compaction that rewrote the data file with its live data alone. */
    public CredentialCopies afterCompaction(Instant at) {
        var windows = plaintext.stream().map(w -> w.open() ? new Window(w.from(), at.toString()) : w).toList();
        return new CredentialCopies(encryptedAt, windows, null, at.toString());
    }

    private @Nullable OldCopies dueAfter(List<Window> windows, boolean rekeyed) {
        if (windows.stream().anyMatch(Window::open)) return OldCopies.PLAINTEXT;
        return rekeyed && due == null ? OldCopies.PREVIOUS_SECRET : due;
    }

    /** The record in {@code dataDir}, or {@link #NONE} when there is none or it does not parse. */
    public static CredentialCopies read(Path dataDir) {
        var file = dataDir.resolve(FILE);
        if (!Files.isRegularFile(file)) return NONE;
        try {
            var parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), CredentialCopies.class);
            return parsed == null ? NONE : parsed;
        } catch (IOException | JsonParseException e) {
            return NONE;
        }
    }

    /** Write the record through a sibling and a move, so a reader never sees half of it. */
    public void write(Path dataDir) throws IOException {
        var file = dataDir.resolve(FILE);
        var tmp = dataDir.resolve(FILE + ".tmp");
        Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * When {@code backup} copied the data file: the time on its {@value H2Maintenance#DATA_FILE} entry, which H2 stamps
     * as it writes the backup and an upload keeps, else the file's own modified time.
     */
    public static Instant takenAt(Path backup) throws IOException {
        if (backup.getFileName().toString().endsWith(".zip")) {
            try (var zip = new ZipFile(backup.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    var name = entry.getName();
                    var time = entry.getLastModifiedTime();
                    if (time != null && (name.equals(H2Maintenance.DATA_FILE) || name.endsWith("/" + H2Maintenance.DATA_FILE))) {
                        return time.toInstant();
                    }
                }
            } catch (IOException _) {
                // Not a readable zip: the file's own time is all there is.
            }
        }
        return Files.getLastModifiedTime(backup).toInstant();
    }
}
