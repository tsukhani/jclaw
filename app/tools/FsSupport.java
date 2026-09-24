package tools;

import org.jspecify.annotations.Nullable;
import utils.ErrorTemplate;
import utils.ToolErrorTemplates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cross-family shared support for {@link FileSystemTools}: the shared read/edit size cap,
 * the small result records the edit and patch paths pass around, and
 * {@link #loadEditableFile} — the single reader the text-edit and line-edit families agree
 * on so their size-limit and not-found behavior stay identical.
 */
final class FsSupport {

    private FsSupport() {}

    static final long MAX_FILE_READ_BYTES = 1_048_576; // 1MB

    /**
     * What a filesystem action produced: the text the model reads, and — on failure — the
     * {@link ErrorTemplate} behind it (JCLAW-1132).
     *
     * <p>The template rather than the text is what the family passes around, so a caller
     * asking "did this fail?" reads a field instead of matching the prose.
     */
    record FsOutcome(String text, @Nullable ErrorTemplate error) {
        static FsOutcome ok(String text) { return new FsOutcome(text, null); }

        static FsOutcome fail(ErrorTemplate template) {
            return new FsOutcome(ToolErrorTemplates.render(template), template);
        }

        boolean failed() { return error != null; }

        /** Valid only once {@link #failed()} has answered true. */
        ErrorTemplate resolvedError() {
            if (error == null) throw new IllegalStateException("action succeeded: " + text);
            return error;
        }
    }

    record EditResult(@Nullable String result, @Nullable ErrorTemplate error, @Nullable String note) {
        static EditResult ok(String result) { return new EditResult(result, null, null); }
        static EditResult okWithNote(String result, String note) { return new EditResult(result, null, note); }
        static EditResult err(ErrorTemplate error) { return new EditResult(null, error, null); }

        /** Valid only once {@link #error()} has been checked null — {@code err()} carries no result. */
        String resolvedResult() {
            if (result == null) throw new IllegalStateException("edit failed: " + error);
            return result;
        }

        /** Valid only once {@link #error()} has been checked non-null. */
        ErrorTemplate resolvedError() {
            if (error == null) throw new IllegalStateException("edit succeeded");
            return error;
        }
    }

    record LoadedFile(@Nullable String content, @Nullable ErrorTemplate error) {
        static LoadedFile ok(String content) { return new LoadedFile(content, null); }
        static LoadedFile err(ErrorTemplate error) { return new LoadedFile(null, error); }

        /** Valid only once {@link #error()} has been checked null — {@code err()} carries no content. */
        String resolvedContent() {
            if (content == null) throw new IllegalStateException("load failed: " + error);
            return content;
        }

        /** Valid only once {@link #error()} has been checked non-null. */
        ErrorTemplate resolvedError() {
            if (error == null) throw new IllegalStateException("load succeeded");
            return error;
        }
    }

    /**
     * Validate that {@code target} exists and is within the edit-size limit, then read its
     * full content. Returns the content, or the template for the failure.
     */
    static LoadedFile loadEditableFile(Path target) {
        if (!Files.exists(target)) {
            return LoadedFile.err(ToolErrorTemplates.fsNotFound(String.valueOf(target.getFileName())));
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return LoadedFile.err(ToolErrorTemplates.fsIoFailure(
                    "Reading the file's size failed: %s".formatted(e.getMessage())));
        }
        if (size > MAX_FILE_READ_BYTES) {
            return LoadedFile.err(ToolErrorTemplates.fsTooLarge(
                    "File exceeds edit size limit (%d bytes). File size: %d bytes."
                            .formatted(MAX_FILE_READ_BYTES, size)));
        }
        try {
            return LoadedFile.ok(Files.readString(target));
        } catch (IOException e) {
            return LoadedFile.err(ToolErrorTemplates.fsIoFailure(
                    "Reading the file failed: %s".formatted(e.getMessage())));
        }
    }
}
