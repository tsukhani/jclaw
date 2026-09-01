package services.tts;

import play.Logger;
import services.transcription.FfmpegProbe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns the WAV {@link TtsRouter} produces into something the destination will
 * actually play (JCLAW-876).
 *
 * <p>WAV is the wrong wire format for a spoken reply — at 48&nbsp;kHz mono PCM a
 * one-minute answer is ~5.8&nbsp;MB — but there is no single right replacement,
 * because the container decides how the reply <em>renders</em> and the clients
 * disagree:
 *
 * <ul>
 *   <li><b>Telegram and WhatsApp</b> want OGG/Opus. Telegram's delivery path
 *       routes {@code .ogg} to {@code sendVoice}, which is the voice-note bubble —
 *       the natural mirror when the operator sent one.</li>
 *   <li><b>Slack</b> does not inline Ogg. It renders it as a "Download Ogg Vorbis"
 *       file card, which is worse than useless for a spoken reply. It does inline
 *       MP3 with a player.</li>
 *   <li><b>Browsers</b> play both, so the web chat is indifferent.</li>
 * </ul>
 *
 * <p>So the format follows the channel rather than being chosen once. This was
 * originally Opus everywhere, on the assumption that Slack would play it; it does
 * not, and the reply arrived as a file to download.
 *
 * <p>Falls back to the original WAV when ffmpeg is missing or the transcode fails.
 * A larger reply that plays beats no reply, and the web player handles WAV. The
 * returned {@link Encoded} always describes the bytes actually produced — a caller
 * must never assume it asked for one container and got it.
 */
public final class VoiceNoteEncoder {

    private VoiceNoteEncoder() {}

    /** Channels whose clients render OGG/Opus as a voice note. Everything else gets
     *  MP3, which is the broadest "plays inline without being downloaded" format. */
    private static final Set<String> VOICE_NOTE_CHANNELS = Set.of("telegram", "whatsapp");

    /** Opus bitrate. 32&nbsp;kbps mono is the voice-note range and transparent for
     *  speech. */
    private static final String OPUS_BITRATE = "32k";

    /** MP3 bitrate. 64&nbsp;kbps mono is comfortably above speech transparency and
     *  still an order of magnitude below the source WAV. */
    private static final String MP3_BITRATE = "64k";

    /** Tail of ffmpeg's output kept when it fails — enough to name the cause without
     *  dumping its banner into the log. */
    private static final int FFMPEG_ERROR_TAIL_CHARS = 400;

    /** Audio bytes plus what they actually are. */
    public record Encoded(byte[] bytes, String mimeType, String extension) {}

    private static Encoded wav(byte[] bytes) {
        return new Encoded(bytes, "audio/wav", "wav");
    }

    /**
     * Encode {@code wavBytes} for {@code channel}, or return it unchanged when that
     * is not possible. Never throws for a transcode problem: the caller has working
     * audio either way, and losing a reply over its container is the worse outcome.
     *
     * @param channel the delivering channel ({@code Conversation.channelType}), or
     *                null when unknown — which takes the broadly-playable MP3 path
     *                rather than gambling on a voice-note client being at the far end.
     */
    public static Encoded forChannel(byte[] wavBytes, String channel) {
        if (wavBytes == null || wavBytes.length == 0) return wav(wavBytes);
        if (!FfmpegProbe.isAvailable()) {
            Logger.debug("VoiceNoteEncoder: ffmpeg unavailable (%s) — sending WAV",
                    FfmpegProbe.lastResult().reason());
            return wav(wavBytes);
        }
        boolean voiceNote = channel != null && VOICE_NOTE_CHANNELS.contains(channel);
        return voiceNote
                ? transcode(wavBytes, "ogg", List.of("-c:a", "libopus", "-b:a", OPUS_BITRATE), "audio/ogg")
                : transcode(wavBytes, "mp3", List.of("-c:a", "libmp3lame", "-b:a", MP3_BITRATE), "audio/mpeg");
    }

    private static Encoded transcode(byte[] wavBytes, String extension, List<String> codecArgs, String mimeType) {
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("jclaw-tts-", ".wav");
            out = Files.createTempFile("jclaw-tts-", "." + extension);
            Files.write(in, wavBytes);
            // -y so the pre-created temp output is overwritten rather than prompting.
            var cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-i", in.toString()));
            cmd.addAll(codecArgs);
            cmd.addAll(List.of("-ac", "1", out.toString()));
            var proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            var output = new String(proc.getInputStream().readAllBytes());
            if (proc.waitFor() != 0) {
                Logger.warn("VoiceNoteEncoder: ffmpeg failed, sending WAV instead: %s",
                        output.substring(Math.max(0, output.length() - FFMPEG_ERROR_TAIL_CHARS)));
                return wav(wavBytes);
            }
            var encoded = Files.readAllBytes(out);
            // A zero-length success is still a failure from the caller's point of
            // view; an encoder can exit 0 having written nothing on odd inputs.
            if (encoded.length == 0) return wav(wavBytes);
            return new Encoded(encoded, mimeType, extension);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return wav(wavBytes);
        } catch (IOException | RuntimeException e) {
            Logger.warn("VoiceNoteEncoder: transcode failed, sending WAV instead: %s", e.getMessage());
            return wav(wavBytes);
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            Logger.debug("VoiceNoteEncoder: could not remove temp file %s: %s", p, e.getMessage());
        }
    }
}
