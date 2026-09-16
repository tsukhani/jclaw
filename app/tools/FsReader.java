package tools;

import tools.FsSupport.FsOutcome;
import utils.ToolErrorTemplates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Read-only family for {@link FileSystemTools}: {@code readFile} (with the 1 MB cap) and
 * {@code listFiles}. Neither mutates, so neither takes a file lock.
 */
final class FsReader {

    private FsReader() {}

    static FsOutcome readFile(Path path) {
        try {
            if (!Files.exists(path)) {
                return FsOutcome.fail(ToolErrorTemplates.fsNotFound(String.valueOf(path.getFileName())));
            }
            if (Files.size(path) > FsSupport.MAX_FILE_READ_BYTES) {
                return FsOutcome.fail(ToolErrorTemplates.fsTooLarge(
                        "File exceeds read limit (%d bytes). File size: %d bytes."
                                .formatted(FsSupport.MAX_FILE_READ_BYTES, Files.size(path))));
            }
            return FsOutcome.ok(Files.readString(path));
        } catch (IOException e) {
            return FsOutcome.fail(ToolErrorTemplates.fsIoFailure(
                    "Reading the file failed: %s".formatted(e.getMessage())));
        }
    }

    static FsOutcome listFiles(Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                return FsOutcome.fail(ToolErrorTemplates.fsNotADirectory(String.valueOf(dir.getFileName())));
            }
            try (var stream = Files.list(dir)) {
                var entries = stream.map(p -> {
                    var name = p.getFileName().toString();
                    return Files.isDirectory(p) ? name + "/" : name;
                }).sorted().toList();
                return FsOutcome.ok(entries.isEmpty() ? "(empty directory)" : String.join("\n", entries));
            }
        } catch (IOException e) {
            return FsOutcome.fail(ToolErrorTemplates.fsIoFailure(
                    "Listing the directory failed: %s".formatted(e.getMessage())));
        }
    }
}
