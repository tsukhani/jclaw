import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.UpgradeService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers what an upgrade WOULD run and when it refuses. The spawn itself is
 * never exercised — this test JVM runs from a developer clone, which is exactly
 * the case the service refuses — so {@code spawnerForTest} intercepts the
 * handoff and the assertions land on the composed command.
 */
class UpgradeServiceTest extends UnitTest {

    @AfterEach
    void clearSeams() {
        UpgradeService.spawnerForTest = null;
        UpgradeService.latestVersionForTest = null;
    }

    @Test
    void versionComparisonIsNumericNotLexical() {
        // The trap this guards: "0.17.9" > "0.17.10" as strings, so a lexical
        // compare would hide every tenth release.
        assertTrue(UpgradeService.isNewer("0.17.10", "0.17.9"));
        assertFalse(UpgradeService.isNewer("0.17.9", "0.17.10"));

        assertTrue(UpgradeService.isNewer("0.17.50", "0.17.49"));
        assertTrue(UpgradeService.isNewer("0.18.0", "0.17.99"));
        assertTrue(UpgradeService.isNewer("1.0.0", "0.99.99"));

        assertFalse(UpgradeService.isNewer("0.17.49", "0.17.49"));
        assertFalse(UpgradeService.isNewer("0.17.48", "0.17.49"));
    }

    @Test
    void versionComparisonToleratesTagsAndShortVersions() {
        // Release tags carry a leading v; application.version never does.
        assertTrue(UpgradeService.isNewer("v0.17.50", "0.17.49"));
        assertFalse(UpgradeService.isNewer("v0.17.49", "v0.17.49"));

        // A missing component reads as 0, so 0.18 outranks 0.17.49.
        assertTrue(UpgradeService.isNewer("0.18", "0.17.49"));
        assertFalse(UpgradeService.isNewer("0.17", "0.17.1"));
    }

    @Test
    void unresolvedVersionsNeverReportAnUpgrade() {
        // latestVersion() returns null when GitHub is unreachable. The preflight
        // feeds that straight into isNewer, so a null must read as "no upgrade"
        // rather than throwing or, worse, offering a button with no target.
        assertFalse(UpgradeService.isNewer(null, "0.17.49"));
        assertFalse(UpgradeService.isNewer("0.17.50", null));
    }

    @Test
    void planPinsTheTargetVersionIntoTheArgv() {
        var plan = UpgradeService.plan("0.17.50");

        var command = plan.command();
        assertTrue(command.get(0).endsWith("jclaw.sh"), "helper is jclaw.sh: " + command);
        assertEquals(List.of("upgrade", "--yes", "--version", "v0.17.50"),
                command.subList(1, command.size()));
        assertEquals("0.17.50", plan.targetVersion());
    }

    @Test
    void planNormalizesAVPrefixedTarget() {
        // The UI shows the tag as GitHub reports it ("v0.17.50"), so the POST can
        // carry either form; the argv must not end up with "--version vv0.17.50".
        assertTrue(UpgradeService.plan("v0.17.50").command().contains("v0.17.50"));
        assertFalse(UpgradeService.plan("v0.17.50").command().contains("vv0.17.50"));
    }

    @Test
    void planWithoutATargetLetsTheHelperResolveTheLatest() {
        var command = UpgradeService.plan(null).command();

        assertEquals(List.of("upgrade", "--yes"), command.subList(1, command.size()));
        assertFalse(command.contains("--version"));
    }

    @Test
    void alwaysPassesYesSoTheDetachedHelperNeverBlocksOnAPrompt() {
        // The helper runs with no controlling terminal. Without --yes it would
        // refuse rather than prompt, and the upgrade would die silently in
        // logs/upgrade.log after the operator had already confirmed in the UI.
        assertTrue(UpgradeService.plan("0.17.50").command().contains("--yes"));
        assertTrue(UpgradeService.plan(null).command().contains("--yes"));
    }

    @Test
    void aSourceCheckoutIsRefused() {
        // This suite runs from the repo, so app/ is present — the same signal
        // do_start_prod and jclaw.sh's upgrade guard use.
        var reason = UpgradeService.unavailableReason();
        assertNotNull(reason, "a source checkout must report why it cannot upgrade");
        assertTrue(reason.contains("git pull"), "reason should point at git pull: " + reason);
    }

    @Test
    void requestUpgradeRefusesBeforeSpawningAnything() {
        var spawned = new ArrayList<UpgradeService.Plan>();
        UpgradeService.spawnerForTest = spawned::add;
        UpgradeService.latestVersionForTest = "99.0.0";

        // Refusal has to happen ahead of the spawn: a helper launched against an
        // install it cannot upgrade would have already stopped the app by the
        // time it worked that out.
        assertThrows(IllegalStateException.class, () -> UpgradeService.requestUpgrade(null));
        assertTrue(spawned.isEmpty(), "nothing may be spawned when the install is unsupported");
    }

    @Test
    void installKindSelectsTheReleaseAssetForThisTree() {
        // A developer clone has no framework/ + play launcher pair, so it reads
        // as "dist". The distinction is load-bearing: swapping a bundle install
        // for a dist tree would leave it with no launcher to start.
        assertEquals("dist", UpgradeService.installKind());
    }

    @Test
    void currentVersionComesFromApplicationConf() {
        var version = UpgradeService.currentVersion();
        assertNotNull(version);
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), "unexpected version shape: " + version);
    }

    @Test
    void aReleasePayloadYieldsItsVersionAndNotes() throws IOException {
        var release = UpgradeService.parseRelease("""
                {"tag_name": "v0.18.83", "body": "### Fixes\\n\\n- **A fix.**\\n\\n"}
                """);
        assertEquals("0.18.83", release.version());
        assertEquals("### Fixes\n\n- **A fix.**", release.notes());
    }

    @Test
    void aReleaseWithoutNotesReportsNone() throws IOException {
        // GitHub sends "body": null for a release published with no description.
        assertNull(UpgradeService.parseRelease("""
                {"tag_name": "v0.18.83", "body": null}
                """).notes());
        assertNull(UpgradeService.parseRelease("""
                {"tag_name": "v0.18.83", "body": "  \\n "}
                """).notes());
        assertNull(UpgradeService.parseRelease("""
                {"tag_name": "v0.18.83"}
                """).notes());
    }

    @Test
    void aReleasePayloadWithoutATagIsRefused() {
        assertThrows(IOException.class, () -> UpgradeService.parseRelease("""
                {"tag_name": null, "body": "notes"}
                """));
    }
}
