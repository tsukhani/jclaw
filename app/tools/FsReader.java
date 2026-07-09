package tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Read-only family for {@link FileSystemTools}: {@code readFile} (with the 1 MB cap) and
 * {@code listFiles}. Neither mutates, so neither takes a file lock.
 */
final class FsReader {

    private FsReader() {}

    static String readFile(Path path) {
        try {
            if (!Files.exists(path)) return FsSupport.ERROR_FILE_NOT_FOUND.formatted(path.getFileName());
            if (Files.size(path) > FsSupport.MAX_FILE_READ_BYTES) {
                return "Error: File exceeds read limit (%d bytes). File size: %d bytes. "
                        .formatted(FsSupport.MAX_FILE_READ_BYTES, Files.size(path))
                        + "For rich document formats (PDF, DOCX, XLSX, etc.), use the 'documents' tool's readDocument action.";
            }
            return Files.readString(path);
        } catch (IOException e) {
            return FsSupport.ERROR_READING_FILE.formatted(e.getMessage());
        }
    }

    static String listFiles(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return "Error: Not a directory: %s".formatted(dir.getFileName());
            try (var stream = Files.list(dir)) {
                var entries = stream.map(p -> {
                    var name = p.getFileName().toString();
                    return Files.isDirectory(p) ? name + "/" : name;
                }).sorted().toList();
                return entries.isEmpty() ? "(empty directory)" : String.join("\n", entries);
            }
        } catch (IOException e) {
            return "Error listing directory: %s".formatted(e.getMessage());
        }
    }
}
