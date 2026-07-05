import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.FfmpegProbe;
import services.transcription.SpeakerSegment;
import services.transcription.SpeakerNamer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


/**
 * JCLAW-558: enrollment scanning edge cases run everywhere; the real
 * embedding-match path is exercised by an assumption-gated integration
 * test that needs the diarization models plus local fixtures (enrolled
 * voices in {@code data/speaker-voices/} and a multi-speaker recording at
 * {@code data/speaker-voices/.fixtures/en3.wav} — dot-prefixed, so the
 * enrollment scanner ignores it). CI has neither and skips.
 */
class SpeakerNamerTest extends UnitTest {

    private Path tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("speaker-voices-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        SpeakerNamer.resetForTest();
        try (var stream = Files.walk(tempRoot)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (java.io.IOException _) {} });
        }
    }

    @Test
    void enrollmentPresent_falseForMissingRoot() {
        SpeakerNamer.setRootForTest(tempRoot.resolve("does-not-exist"));
        assertFalse(SpeakerNamer.enrollmentPresent());
    }

    @Test
    void enrollmentPresent_falseForEmptyRoot_andEmptyPersonFolder() throws Exception {
        SpeakerNamer.setRootForTest(tempRoot);
        assertFalse(SpeakerNamer.enrollmentPresent(), "no subfolders → no enrollment");

        Files.createDirectory(tempRoot.resolve("Alice"));
        assertFalse(SpeakerNamer.enrollmentPresent(), "a person folder with no clips is not enrollment");
    }

    @Test
    void enrollmentPresent_ignoresHiddenEntries() throws Exception {
        SpeakerNamer.setRootForTest(tempRoot);
        // Dot-directory (e.g. .fixtures) and dotfiles (e.g. .DS_Store) must
        // not count as enrollment.
        Files.createDirectories(tempRoot.resolve(".fixtures"));
        Files.write(tempRoot.resolve(".fixtures").resolve("clip.wav"), new byte[10]);
        Files.createDirectory(tempRoot.resolve("Alice"));
        Files.write(tempRoot.resolve("Alice").resolve(".DS_Store"), new byte[10]);
        assertFalse(SpeakerNamer.enrollmentPresent());

        Files.write(tempRoot.resolve("Alice").resolve("clip.wav"), new byte[10]);
        assertTrue(SpeakerNamer.enrollmentPresent(), "a real clip flips it on");
    }

    @Test
    void nameSpeakers_emptyWithoutEnrollment_beforeAnyModelWork() {
        SpeakerNamer.setRootForTest(tempRoot);
        // Models may be absent and ffmpeg forced off — neither may matter,
        // because the enrollment check must short-circuit first.
        FfmpegProbe.setForTest(new FfmpegProbe.ProbeResult(false, "forced-missing-for-test"));
        try {
            var names = SpeakerNamer.nameSpeakers(
                    tempRoot.resolve("whatever.wav"),
                    List.of(new SpeakerSegment(0, 1, 0)),
                    0.6f);
            assertTrue(names.isEmpty());
        } finally {
            FfmpegProbe.setForTest(null);
        }
    }

    @Test
    void l2normalize_removesMagnitudeDominance() {
        // JCLAW-623: one loud chunk (10x magnitude) must not own the
        // centroid. Unit-scaled, direction [1,0] and [0,1] average to 45°;
        // unnormalized, the loud [10,0] chunk would pin it near [1,0].
        var loud = services.transcription.OverlapReattributor.l2normalize(new float[]{10, 0});
        var quiet = services.transcription.OverlapReattributor.l2normalize(new float[]{0, 1});
        assertEquals(1.0, loud[0], 1e-6);
        assertEquals(1.0, quiet[1], 1e-6);
        var centroid = new float[]{(loud[0] + quiet[0]) / 2, (loud[1] + quiet[1]) / 2};
        assertEquals(centroid[0], centroid[1], 1e-6,
                "equal per-chunk weight regardless of raw magnitude");
    }

    @Test
    void assignExclusive_preventsTheCollapse() {
        // The original failure: both clusters exceed threshold against ONE
        // enrolled person — only the better-scoring cluster may take the
        // name (JCLAW-606 regression test).
        var scores = java.util.Map.of(
                0, java.util.Map.of("Podcaster", 0.82),
                1, java.util.Map.of("Podcaster", 0.71));
        var names = SpeakerNamer.assignExclusive(scores, 0.6f, 0.03);
        assertEquals(java.util.Map.of(0, "Podcaster"), names,
                "one enrolled voice must name at most one cluster");
    }

    @Test
    void assignExclusive_greedyBestFirst_acrossTwoPeople() {
        var scores = java.util.Map.of(
                0, java.util.Map.of("Podcaster", 0.85, "Firdaus", 0.62),
                1, java.util.Map.of("Podcaster", 0.70, "Firdaus", 0.78));
        var names = SpeakerNamer.assignExclusive(scores, 0.6f, 0.03);
        assertEquals("Podcaster", names.get(0));
        assertEquals("Firdaus", names.get(1),
                "cluster 1 takes its remaining best after Podcaster is claimed");
    }

    @Test
    void assignExclusive_leavesAmbiguousClustersAnonymous() {
        // Top two candidates within the ambiguity gap: no guess.
        var scores = java.util.Map.of(
                0, java.util.Map.of("Podcaster", 0.71, "Firdaus", 0.695));
        assertTrue(SpeakerNamer.assignExclusive(scores, 0.6f, 0.03).isEmpty(),
                "a coin-flip cluster stays SPEAKER_NN");
    }

    @Test
    void assignExclusive_respectsThreshold() {
        var scores = java.util.Map.of(
                0, java.util.Map.of("Podcaster", 0.55, "Firdaus", 0.31));
        assertTrue(SpeakerNamer.assignExclusive(scores, 0.6f, 0.03).isEmpty());
    }

}
