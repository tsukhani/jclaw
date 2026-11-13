import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.KeptSkillFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** JF-405: what an update carries from a skill's old copy into the new one it stages. */
class KeptSkillFilesCarryOverTest extends UnitTest {

    private Path root;
    private Path oldCopy;
    private Path newVersion;
    private Path staging;

    @BeforeEach
    void folders() throws IOException {
        root = Files.createTempDirectory("jf405-");
        oldCopy = Files.createDirectories(root.resolve("old"));
        newVersion = Files.createDirectories(root.resolve("library"));
        staging = Files.createDirectories(root.resolve("staging"));
        Files.writeString(oldCopy.resolve("SKILL.md"), "v1");
        Files.writeString(newVersion.resolve("SKILL.md"), "v2");
        Files.writeString(staging.resolve("SKILL.md"), "v2");
    }

    @AfterEach
    void cleanUp() throws IOException {
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Test
    void theCredentialsFilesAndAFilledInExampleComeOverWithTheirPermissions() throws IOException {
        Files.createDirectories(oldCopy.resolve("credentials"));
        Files.writeString(oldCopy.resolve("credentials/nas.env"), "NAS_PASS=fake-nas-jf405\n");
        Files.setPosixFilePermissions(oldCopy.resolve("credentials/nas.env"), PosixFilePermissions.fromString("rw-------"));
        Files.writeString(oldCopy.resolve("config.env"), "NAS_URL=https://nas.example.org\n");
        Files.writeString(newVersion.resolve("config.env.example"), "NAS_URL=\n");

        assertEquals(List.of("config.env", "credentials/nas.env"),
                KeptSkillFiles.carryOver(oldCopy, newVersion, staging));
        assertEquals("NAS_PASS=fake-nas-jf405\n", Files.readString(staging.resolve("credentials/nas.env")));
        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(staging.resolve("credentials/nas.env"))));
        assertEquals("NAS_URL=https://nas.example.org\n", Files.readString(staging.resolve("config.env")));
        assertEquals("v2", Files.readString(staging.resolve("SKILL.md")), "nothing else in the old copy comes over");
    }

    @Test
    void aPathTheNewVersionShipsKeepsTheLibrarysContent() throws IOException {
        Files.createDirectories(oldCopy.resolve("credentials"));
        Files.writeString(oldCopy.resolve("credentials/token"), "old-token-jf405");
        Files.createDirectories(staging.resolve("credentials"));
        Files.writeString(staging.resolve("credentials/token"), "shipped");
        Files.createDirectories(newVersion.resolve("credentials"));
        Files.writeString(newVersion.resolve("credentials/token"), "shipped");

        assertEquals(List.of(), KeptSkillFiles.carryOver(oldCopy, newVersion, staging));
        assertEquals("shipped", Files.readString(staging.resolve("credentials/token")));
    }

    @Test
    void aLinkIsNeverCarriedOver() throws IOException {
        var outside = Files.writeString(root.resolve("outside.txt"), "outside-jf405");
        Files.createDirectories(oldCopy.resolve("credentials"));
        Files.createSymbolicLink(oldCopy.resolve("credentials/linked"), outside);
        Files.writeString(oldCopy.resolve("credentials/real.env"), "REAL_KEY=real-jf405\n");

        assertEquals(List.of("credentials/real.env"), KeptSkillFiles.carryOver(oldCopy, newVersion, staging));
        assertEquals(false, Files.exists(staging.resolve("credentials/linked"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void aStagedFolderThatBecameALinkIsNotWrittenThrough() throws IOException {
        var outside = Files.createDirectories(root.resolve("outside"));
        Files.createDirectories(oldCopy.resolve("credentials"));
        Files.writeString(oldCopy.resolve("credentials/nas.env"), "NAS_PASS=fake-nas-jf405\n");
        Files.createSymbolicLink(staging.resolve("credentials"), outside);

        assertEquals(List.of(), KeptSkillFiles.carryOver(oldCopy, newVersion, staging));
        try (var files = Files.list(outside)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void anOldCopyWithNothingToCarryAddsNothing() throws IOException {
        Files.writeString(oldCopy.resolve("notes.md"), "the agent's own notes");

        assertEquals(List.of(), KeptSkillFiles.carryOver(oldCopy, newVersion, staging));
        try (var files = Files.list(staging)) {
            assertEquals(List.of("SKILL.md"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aCopyThatCannotWriteThrowsRatherThanFinishingHalfway() throws IOException {
        Files.createDirectories(oldCopy.resolve("credentials"));
        Files.writeString(oldCopy.resolve("credentials/nas.env"), "NAS_PASS=fake-nas-jf405\n");
        Files.writeString(staging.resolve("credentials"), "a file where the folder must go");

        assertThrows(IOException.class, () -> KeptSkillFiles.carryOver(oldCopy, newVersion, staging));
    }
}
