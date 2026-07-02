package services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JCLAW-560: disambiguate audio-only WebM/Matroska uploads. Tika sniffs every
 * WebM container as {@code video/webm} (and every Matroska as
 * {@code video/x-matroska}) regardless of track content, so an uploaded
 * audio-only {@code .webm} classified {@code KIND_VIDEO} never entered the
 * transcription pipeline. The pre-existing carve-out (JCLAW-165 follow-up)
 * trusted a browser-declared {@code audio/*} hint — which MediaRecorder voice
 * notes carry but file-picker uploads don't: browsers report {@code video/webm}
 * for {@code .webm} files from the OS type registry no matter what's inside.
 *
 * <p>{@link #disambiguate} keeps the hint fast path and adds a content-level
 * fallback: Matroska track entries carry their codec as an ASCII CodecID
 * string ({@code V_VP9}, {@code A_OPUS}, …) in the header region near the file
 * start, so scanning the first {@value #SCAN_LIMIT_BYTES} bytes for the known
 * video codec IDs tells video from audio-only without spawning a process or
 * adding a parser dependency. Inconclusive scans (no recognizable codec ID in
 * the window) conservatively keep the video classification.
 */
public final class MatroskaTracks {

    private MatroskaTracks() {}

    /** Track entries live in the header; 256 KB covers them with a wide margin. */
    static final int SCAN_LIMIT_BYTES = 256 * 1024;

    /**
     * Matroska video CodecID prefixes (matroska.org codec registry).
     * {@code V_MPEG} covers the MPEG1/2/4 family including AVC and HEVC.
     */
    private static final String[] VIDEO_CODEC_IDS = {
            "V_VP8", "V_VP9", "V_AV1", "V_MPEG", "V_THEORA", "V_PRORES",
            "V_QUICKTIME", "V_FFV1", "V_MS/VFW", "V_UNCOMPRESSED", "V_DIRAC",
    };

    private static final String[] AUDIO_CODEC_IDS = {
            "A_OPUS", "A_VORBIS", "A_AAC", "A_FLAC", "A_MPEG", "A_PCM",
            "A_AC3", "A_EAC3", "A_DTS", "A_TRUEHD", "A_ALAC", "A_MS/ACM",
    };

    /**
     * Resolve the effective MIME for an upload Tika sniffed as
     * {@code sniffedMime}. Non-Matroska types pass through untouched. For
     * {@code video/webm} / {@code video/x-matroska}: an {@code audio/*} browser
     * hint wins (the original voice-note fast path); otherwise the container's
     * track codecs decide — no video codec but at least one audio codec means
     * audio-only, anything else keeps the sniff.
     */
    public static String disambiguate(String sniffedMime, String hintMime, Path file) {
        String audioMime = audioVariantOf(sniffedMime);
        if (audioMime == null) return sniffedMime;
        if (hintMime != null && hintMime.startsWith("audio/")) return audioMime;
        return isAudioOnly(file) ? audioMime : sniffedMime;
    }

    private static String audioVariantOf(String mime) {
        if ("video/webm".equals(mime)) return "audio/webm";
        if ("video/x-matroska".equals(mime)) return "audio/x-matroska";
        return null;
    }

    /**
     * True only when the header window shows at least one audio CodecID and no
     * video CodecID. An unreadable file or a window with neither marker is not
     * "audio-only" — the caller keeps the (conservative) video classification.
     */
    static boolean isAudioOnly(Path file) {
        byte[] header;
        try (InputStream in = Files.newInputStream(file)) {
            header = in.readNBytes(SCAN_LIMIT_BYTES);
        } catch (IOException _) {
            return false;
        }
        return isAudioOnly(header);
    }

    /**
     * Pure form: classify a header window. Public because the test tree
     * compiles into the default package.
     */
    public static boolean isAudioOnly(byte[] header) {
        for (String id : VIDEO_CODEC_IDS) {
            if (contains(header, id)) return false;
        }
        for (String id : AUDIO_CODEC_IDS) {
            if (contains(header, id)) return true;
        }
        return false;
    }

    private static boolean contains(byte[] haystack, String ascii) {
        byte[] needle = ascii.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
