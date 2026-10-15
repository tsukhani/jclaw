package services.database;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Masks the values H2 quotes into its trace file (JCLAW-1455). A refused write copies the value into the error message,
 * as {@code 'value'}, or {@code 'first 80 characters... (length)'} when it is long, and a rejected credential sat there in
 * plaintext. The file is kept, because {@link DatabaseHealth#corruptionEvents} reads it and a repair keeps it as
 * evidence, and it is rewritten in place byte for byte: H2 appends to it while the app runs, and a same-length overwrite
 * of what was read loses nothing H2 writes meanwhile. Standalone on the JDK, like {@link H2Maintenance}.
 */
public final class H2TraceMask {

    private static final byte QUOTE = '\'';
    private static final byte MASK = '*';
    // H2's trace form of a value it cut short: the first 80 characters, then this, the full length and ')'.
    private static final byte[] TRUNCATED = "... (".getBytes(StandardCharsets.US_ASCII);

    private H2TraceMask() {}

    /** Mask {@code dataDir}'s trace file and the copy H2 rolls it over to. Returns how many values were masked. */
    public static int maskTraces(Path dataDir) throws IOException {
        return mask(dataDir.resolve(H2Maintenance.TRACE_FILE)) + mask(dataDir.resolve(H2Maintenance.TRACE_FILE + ".old"));
    }

    /** Replace every character inside a single-quoted value in {@code trace} with {@code *}. Returns how many values changed. */
    public static int mask(Path trace) throws IOException {
        if (!Files.isRegularFile(trace)) return 0;
        try (var channel = FileChannel.open(trace, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var bytes = new byte[(int) Math.min(channel.size(), Integer.MAX_VALUE - 8)];
            var read = 0;
            while (read < bytes.length) {
                var n = channel.read(ByteBuffer.wrap(bytes, read, bytes.length - read), read);
                if (n <= 0) break;
                read += n;
            }
            var masked = mask(bytes, read);
            if (masked > 0) {
                channel.write(ByteBuffer.wrap(bytes, 0, read), 0);
            }
            return masked;
        }
    }

    /** Mask {@code bytes[0, length)} in place. A value ends at its closing quote, H2's truncation marker or the line's end. */
    static int mask(byte[] bytes, int length) {
        var values = 0;
        var i = 0;
        while (i < length) {
            if (bytes[i] != QUOTE) {
                i++;
                continue;
            }
            i++;
            var changed = false;
            while (i < length && bytes[i] != '\n' && bytes[i] != '\r' && !truncatedAt(bytes, i, length)) {
                if (bytes[i] == QUOTE) {
                    if (i + 1 < length && bytes[i + 1] == QUOTE) {
                        changed |= maskAt(bytes, i) | maskAt(bytes, i + 1);
                        i += 2;
                        continue;
                    }
                    i++;
                    break;
                }
                changed |= maskAt(bytes, i);
                i++;
            }
            if (changed) values++;
        }
        return values;
    }

    private static boolean maskAt(byte[] bytes, int i) {
        if (bytes[i] == MASK) return false;
        bytes[i] = MASK;
        return true;
    }

    private static boolean truncatedAt(byte[] bytes, int i, int length) {
        if (i + TRUNCATED.length >= length) return false;
        for (var k = 0; k < TRUNCATED.length; k++) {
            if (bytes[i + k] != TRUNCATED[k]) return false;
        }
        var j = i + TRUNCATED.length;
        var digits = 0;
        while (j < length && bytes[j] >= '0' && bytes[j] <= '9') {
            j++;
            digits++;
        }
        return digits > 0 && j < length && bytes[j] == ')';
    }
}
