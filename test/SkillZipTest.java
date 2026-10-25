import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.SkillPromotionService;
import services.SkillZip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Unpacking an uploaded skill zip: the two layouts a skill arrives in, and every zip it refuses. */
class SkillZipTest extends UnitTest {

    private Path work;

    @BeforeEach
    void setup() throws IOException {
        work = Files.createTempDirectory("skill-zip-test-");
    }

    @AfterEach
    void teardown() throws IOException {
        SkillPromotionService.deleteRecursive(work);
    }

    private Path zip(Map<String, byte[]> entries) throws IOException {
        var file = work.resolve("upload-" + System.nanoTime() + ".zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private static Map<String, byte[]> entries(String... namesAndTexts) {
        var map = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < namesAndTexts.length; i += 2) {
            map.put(namesAndTexts[i], namesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    private Path dest() throws IOException {
        return Files.createDirectories(work.resolve("dest-" + System.nanoTime()));
    }

    private static List<String> files(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList();
        }
    }

    private String refusal(Map<String, byte[]> entries, SkillZip.Limits limits) throws IOException {
        var zip = zip(entries);
        var dest = dest();
        return assertThrows(IllegalArgumentException.class, () -> SkillZip.extract(zip, dest, limits)).getMessage();
    }

    @Test
    void theExportLayoutUnpacksToItsOneTopFolderWithoutMacosClutter() throws IOException {
        var dest = dest();
        var root = SkillZip.extract(zip(entries(
                "pdf/SKILL.md", "---\nname: pdf\n---\n",
                "pdf/LICENSE.txt", "Apache",
                "pdf/.DS_Store", "x",
                "__MACOSX/pdf/._SKILL.md", "x")), dest);

        assertEquals(dest.toAbsolutePath().normalize().resolve("pdf"), root);
        assertEquals(List.of("LICENSE.txt", "SKILL.md"), files(root));
        assertEquals(List.of("pdf/LICENSE.txt", "pdf/SKILL.md"), files(dest));
    }

    @Test
    void aSkillMdAtTheTopUnpacksInPlace() throws IOException {
        var dest = dest();
        var root = SkillZip.extract(zip(entries("SKILL.md", "---\nname: x\n---\n", "scripts/run.py", "print(1)")), dest);

        assertEquals(dest.toAbsolutePath().normalize(), root);
        assertEquals(List.of("SKILL.md", "scripts/run.py"), files(root));
    }

    @Test
    void aPathOutOfTheFolderIsRefused() throws IOException {
        for (var name : List.of("../evil.txt", "a/../../evil.txt", "/etc/evil.txt", "a\\evil.txt", "C:/evil.txt")) {
            assertEquals("the zip holds a path outside its folder: " + name,
                    refusal(entries("SKILL.md", "x", name, "x"), SkillZip.DEFAULT_LIMITS), name);
        }
        assertFalse(Files.exists(work.resolve("evil.txt")), "nothing landed outside the destination");
    }

    @Test
    void aFileListedTwiceIsRefused() throws IOException {
        assertEquals("the zip lists a file twice: x//SKILL.md",
                refusal(entries("x/SKILL.md", "a", "x//SKILL.md", "b"), SkillZip.DEFAULT_LIMITS));
    }

    @Test
    void bothLimitsHoldAtTheBoundary() throws IOException {
        var two = new SkillZip.Limits(2, SkillZip.DEFAULT_LIMITS.maxInflatedBytes());
        var dest = dest();
        assertEquals(dest.toAbsolutePath().normalize(),
                SkillZip.extract(zip(entries("SKILL.md", "x", "b.md", "x")), dest, two), "two files under a two-file limit");
        assertEquals("the zip holds more than 2 files", refusal(entries("SKILL.md", "x", "b.md", "x", "c.md", "x"), two));

        var twoMb = new SkillZip.Limits(10, 2L * 1024 * 1024);
        var exactly = new LinkedHashMap<String, byte[]>();
        exactly.put("SKILL.md", new byte[0]);
        exactly.put("big.bin", new byte[2 * 1024 * 1024]);
        var dest2 = dest();
        assertEquals(dest2.toAbsolutePath().normalize(), SkillZip.extract(zip(exactly), dest2, twoMb), "exactly the limit");
        exactly.put("big.bin", new byte[2 * 1024 * 1024 + 1]);
        assertEquals("the zip unpacks to more than 2 MB", refusal(exactly, twoMb));
    }

    @Test
    void aZipWithNoSkillMdWhereOneIsExpectedIsRefused() throws IOException {
        assertEquals("the zip has no SKILL.md at its top or in a single top folder",
                refusal(entries("a/README.md", "x", "b/SKILL.md", "x"), SkillZip.DEFAULT_LIMITS));
        assertEquals("the zip has no SKILL.md at its top or in a single top folder",
                refusal(entries("a/README.md", "x"), SkillZip.DEFAULT_LIMITS));
    }

    @Test
    void aFileThatIsNotAZipIsRefused() throws IOException {
        var notZip = work.resolve("notes.zip");
        Files.writeString(notZip, "just text");
        var dest = dest();

        var message = assertThrows(IllegalArgumentException.class, () -> SkillZip.extract(notZip, dest)).getMessage();
        assertEquals("not a readable zip", message.substring(0, message.indexOf(':')));
    }
}
