import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizationModelManager;
import services.transcription.DiarizationModelManager.DiarizationModel;
import services.transcription.FfmpegProbe;
import services.transcription.SherpaDiarizer;
import services.transcription.SpeakerNamer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
                    List.of(new SherpaDiarizer.SpeakerSegment(0, 1, 0)),
                    0.6f);
            assertTrue(names.isEmpty());
        } finally {
            FfmpegProbe.setForTest(null);
        }
    }

    @Test
    void nameSpeakers_matchesEnrolledVoices_endToEnd() {
        assumeTrue(FfmpegProbe.probe().available(), "ffmpeg not on PATH — skipping");
        assumeTrue(DiarizationModelManager.availableLocally(DiarizationModel.SEGMENTATION)
                        && DiarizationModelManager.availableLocally(DiarizationModel.EMBEDDING),
                "diarization models not downloaded — skipping");
        var voicesRoot = Path.of("data", "speaker-voices");
        var fixture = voicesRoot.resolve(".fixtures").resolve("en3.wav");
        assumeTrue(Files.isRegularFile(fixture) && SpeakerNamer.enrollmentPresent(),
                "local enrollment fixtures absent — skipping (dev-machine-only test)");

        var speakers = SherpaDiarizer.diarize(fixture, 0.3f, 3);
        var names = SpeakerNamer.nameSpeakers(fixture, speakers, 0.6f);

        Set<Integer> distinct = new HashSet<>();
        speakers.forEach(s -> distinct.add(s.speaker()));
        assertEquals(distinct.size(), names.size(),
                "every diarized speaker should match an enrolled voice: " + names);
        assertTrue(names.values().containsAll(Set.of("Daniel", "Samantha", "Albert")),
                "expected the three enrolled names, got: " + names);
    }
}
