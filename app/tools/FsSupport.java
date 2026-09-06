package tools;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cross-family shared support for {@link FileSystemTools}: the common error-message
 * templates, the shared read/edit size cap, the small result records the edit and patch
 * paths pass around, and {@link #loadEditableFile} — the single reader the text-edit and
 * line-edit families agree on so their size-limit and not-found behavior stay identical.
 */
final class FsSupport {

    private FsSupport() {}

    // === Common error message templates ===
    static final String ERROR_PREFIX = "Error";
    static final String ERROR_PREFIX_COLON = "Error: ";
    static final String ERROR_FILE_NOT_FOUND = "Error: File not found: %s";
    static final String ERROR_READING_FILE = "Error reading file: %s";

    static final long MAX_FILE_READ_BYTES = 1_048_576; // 1MB

    record EditResult(@Nullable String result, @Nullable String error, @Nullable String note) {
        static EditResult ok(String result) { return new EditResult(result, null, null); }
        static EditResult okWithNote(String result, String note) { return new EditResult(result, null, note); }
        static EditResult err(String error) { return new EditResult(null, error, null); }

        /** Valid only once {@link #error()} has been checked null — {@code err()} carries no result. */
        String resolvedResult() {
            if (result == null) throw new IllegalStateException("edit failed: " + error);
            return result;
        }
    }

    record LoadedFile(@Nullable String content, @Nullable String error) {
        static LoadedFile ok(String content) { return new LoadedFile(content, null); }
        static LoadedFile err(String error) { return new LoadedFile(null, error); }

        /** Valid only once {@link #error()} has been checked null — {@code err()} carries no content. */
        String resolvedContent() {
            if (content == null) throw new IllegalStateException("load failed: " + error);
            return content;
        }
    }

    /**
     * Validate that {@code target} exists and is within the edit-size limit, then read its
     * full content. Returns the content, or an error string already shaped for the caller's
     * return value.
     */
    static LoadedFile loadEditableFile(Path target) {
        if (!Files.exists(target)) {
            return LoadedFile.err(ERROR_FILE_NOT_FOUND.formatted(target.getFileName()));
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return LoadedFile.err("Error reading file size: %s".formatted(e.getMessage()));
        }
        if (size > MAX_FILE_READ_BYTES) {
            return LoadedFile.err("Error: File exceeds edit size limit (%d bytes). File size: %d bytes. Consider using writeFile for wholesale replacement instead."
                    .formatted(MAX_FILE_READ_BYTES, size));
        }
        try {
            return LoadedFile.ok(Files.readString(target));
        } catch (IOException e) {
            return LoadedFile.err(ERROR_READING_FILE.formatted(e.getMessage()));
        }
    }
}
