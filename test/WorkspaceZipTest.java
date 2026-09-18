import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.WorkspaceFiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

/**
 * JCLAW-1248 / JCLAW-1251: {@link WorkspaceFiles#zipDirectory}. Runs against its own uniquely
 * named agent directory under {@code workspace-test} so concurrent test classes cannot collide.
 */
class WorkspaceZipTest extends UnitTest {

    private String agentName;
    private Path root;

    @BeforeEach
    void materialize() throws IOException {
        agentName = "jclaw1248-zip-" + System.nanoTime();
        root = WorkspaceFiles.workspacePath(agentName);
        Files.createDirectories(root);
    }

    @AfterEach
    void cleanUp() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) { /* best-effort */ }
            });
        }
    }

    /** Entry name to entry body, in the order the archive lists them. */
    private Map<String, String> zipOf(String relative) throws IOException {
        var buffer = new ByteArrayOutputStream();
        WorkspaceFiles.zipDirectory(agentName, relative, buffer);
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    void entryNamesAreRelativeToTheFolderBeingDownloaded() throws IOException {
        Files.createDirectories(root.resolve("downloads/nested"));
        Files.writeString(root.resolve("downloads/report.pdf"), "pdf-bytes");
        Files.writeString(root.resolve("downloads/nested/a.bin"), "abc");
        Files.writeString(root.resolve("outside.txt"), "not in this archive");

        var entries = zipOf("downloads");

        assertEquals(List.of("nested/", "nested/a.bin", "report.pdf"), List.copyOf(entries.keySet()),
                "names are relative to the downloaded folder, not to the workspace root");
        assertEquals("pdf-bytes", entries.get("report.pdf"));
        assertEquals("abc", entries.get("nested/a.bin"));
    }

    @Test
    void neverFollowsSymlinks() throws IOException {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/b.txt"), "hello");
        // Out of the workspace entirely, and a second link whose target is already an entry here.
        Files.createSymbolicLink(root.resolve("data/escape"), root.toAbsolutePath().getParent());
        Files.createSymbolicLink(root.resolve("data/alias.txt"), root.resolve("data/b.txt"));

        var entries = zipOf("data");

        assertEquals(List.of("b.txt"), List.copyOf(entries.keySet()),
                "a link out of the root is dropped by the guard, a link inside it is not followed");
    }

    @Test
    void anEmptyFolderZipsToAnEmptyArchive() throws IOException {
        Files.createDirectories(root.resolve("empty"));

        assertTrue(zipOf("empty").isEmpty(), "an empty folder still produces a readable archive");
    }
}
