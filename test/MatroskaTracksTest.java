import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.MatroskaTracks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCLAW-560: audio-only WebM/Matroska classification. Header windows are
 * synthesized around real Matroska CodecID strings (they appear as ASCII in
 * the track entries), which is exactly what the sniffer keys on — no real
 * media files or Tika needed.
 */
class MatroskaTracksTest extends UnitTest {

    private static byte[] header(String... codecIds) {
        var sb = new StringBuilder("Eߣ...webm...");   // arbitrary binary-ish prefix
        for (var id : codecIds) sb.append("....").append(id).append("....");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Path tempFile(byte[] bytes) throws Exception {
        var p = Files.createTempFile("matroska-tracks-test", ".webm");
        p.toFile().deleteOnExit();
        Files.write(p, bytes);
        return p;
    }

    // ─── the pure header classifier ──────────────────────────────────────────

    @Test
    void audioCodecWithoutVideoCodecIsAudioOnly() {
        assertTrue(MatroskaTracks.isAudioOnly(header("A_OPUS")));
        assertTrue(MatroskaTracks.isAudioOnly(header("A_VORBIS")));
        assertTrue(MatroskaTracks.isAudioOnly(header("A_AAC", "A_OPUS")));
    }

    @Test
    void anyVideoCodecMeansNotAudioOnly() {
        assertFalse(MatroskaTracks.isAudioOnly(header("V_VP9", "A_OPUS")),
                "a real video with an audio track must stay video");
        assertFalse(MatroskaTracks.isAudioOnly(header("V_VP8")));
        assertFalse(MatroskaTracks.isAudioOnly(header("V_MPEG4/ISO/AVC", "A_AAC")),
                "the V_MPEG prefix covers the AVC/HEVC family");
    }

    @Test
    void noRecognizableCodecIsInconclusiveNotAudio() {
        assertFalse(MatroskaTracks.isAudioOnly(header()),
                "an inconclusive scan must keep the conservative video classification");
        assertFalse(MatroskaTracks.isAudioOnly(new byte[0]));
    }

    // ─── disambiguate: the shared upload/finalize entry point ────────────────

    @Test
    void filePickerAudioOnlyWebmReclassifiesWithoutAnyBrowserHint() throws Exception {
        // The reported bug: browsers report video/webm for .webm picker uploads
        // no matter the content, so the hint can never rescue the file — the
        // container scan must.
        var f = tempFile(header("A_OPUS"));
        assertEquals("audio/webm", MatroskaTracks.disambiguate("video/webm", "video/webm", f));
        assertEquals("audio/webm", MatroskaTracks.disambiguate("video/webm", null, f));
    }

    @Test
    void webmWithVideoTrackKeepsVideoClassification() throws Exception {
        var f = tempFile(header("V_VP9", "A_OPUS"));
        assertEquals("video/webm", MatroskaTracks.disambiguate("video/webm", "video/webm", f));
    }

    @Test
    void audioBrowserHintStillWinsAsTheVoiceNoteFastPath() throws Exception {
        // MediaRecorder voice notes declare audio/webm — the pre-560 carve-out
        // must keep working, including when the scan would be inconclusive.
        var f = tempFile(header());
        assertEquals("audio/webm", MatroskaTracks.disambiguate("video/webm", "audio/webm", f));
    }

    @Test
    void matroskaGetsItsOwnAudioVariant() throws Exception {
        var f = tempFile(header("A_FLAC"));
        assertEquals("audio/x-matroska",
                MatroskaTracks.disambiguate("video/x-matroska", null, f));
    }

    @Test
    void nonMatroskaMimesPassThroughUntouched() throws Exception {
        var f = tempFile(header("A_OPUS"));
        assertEquals("audio/mpeg", MatroskaTracks.disambiguate("audio/mpeg", null, f));
        assertEquals("application/pdf", MatroskaTracks.disambiguate("application/pdf", null, f));
        assertEquals("video/mp4", MatroskaTracks.disambiguate("video/mp4", null, f),
                "only the ambiguous Matroska containers are second-guessed");
    }

    @Test
    void unreadableFileKeepsVideoClassification() {
        var missing = Path.of("does-not-exist-560.webm");
        assertEquals("video/webm", MatroskaTracks.disambiguate("video/webm", null, missing));
    }
}
