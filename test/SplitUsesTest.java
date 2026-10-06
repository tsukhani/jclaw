import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.SplitUses;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** JCLAW-1368: the use-once ledger's admission rules. */
class SplitUsesTest extends UnitTest {

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("split-uses");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Test
    void aFreshSplitIsAdmittedAndAUsedOneIsRefusedQuotingItsLine() throws IOException {
        assertFalse(SplitUses.admit(root, "cert-1", "tev1", "sha256:a", 1).secondRun());
        SplitUses.append(root, "cert-1", "tev1", "sha256:a", SplitUses.USED);
        var e = assertThrows(IllegalArgumentException.class, () -> SplitUses.admit(root, "cert-1", "tev1", "sha256:a", 1));
        assertTrue(e.getMessage().contains(SplitUses.FILE), e.getMessage());
        assertTrue(e.getMessage().contains(
                "{\"split\":\"cert-1\",\"model\":\"tev1\",\"digest\":\"sha256:a\",\"state\":\"used\"}"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SplitUses.admit(root, "cert-1", "tev1", "sha256:a", 2));
        assertFalse(SplitUses.admit(root, "cert-1", "tev1", "sha256:b", 1).secondRun(), "a new digest is a new version");
        assertFalse(SplitUses.admit(root, "cert-1", "nimble", "sha256:a", 1).secondRun());
        assertFalse(SplitUses.admit(root, "cert-2", "tev1", "sha256:a", 1).secondRun());
    }

    @Test
    void aDifferingSpotCheckAdmitsExactlyOneRunOfTwoAsTheSameUse() throws IOException {
        SplitUses.append(root, "cert-1", "tev1", "sha256:a", SplitUses.NEEDS_SECOND_RUN);
        var e = assertThrows(IllegalArgumentException.class, () -> SplitUses.admit(root, "cert-1", "tev1", "sha256:a", 1));
        assertTrue(e.getMessage().contains("only runs 2"), e.getMessage());
        assertTrue(SplitUses.admit(root, "cert-1", "tev1", "sha256:a", 2).secondRun());
        SplitUses.append(root, "cert-1", "tev1", "sha256:a", SplitUses.USED);
        assertThrows(IllegalArgumentException.class, () -> SplitUses.admit(root, "cert-1", "tev1", "sha256:a", 2));
    }

    @Test
    void aVoidRunAdmitsAFreshOne() throws IOException {
        SplitUses.append(root, "cert-1", "tev1", "sha256:a", SplitUses.VOID);
        assertFalse(SplitUses.admit(root, "cert-1", "tev1", "sha256:a", 1).secondRun());
        assertEquals(SplitUses.VOID, SplitUses.latest(root, "cert-1", "tev1", "sha256:a").state());
        SplitUses.append(root, "cert-1", "tev1", "sha256:a", SplitUses.USED);
        assertEquals(SplitUses.USED, SplitUses.latest(root, "cert-1", "tev1", "sha256:a").state(), "the latest line wins");
        assertEquals(2, Files.readAllLines(root.resolve(SplitUses.FILE)).size(), "append-only");
    }
}
