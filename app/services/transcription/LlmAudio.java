package services.transcription;

import models.MessageAttachment;
import play.Logger;
import services.MimeExtensions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Prepares an audio attachment for an OpenAI-compatible {@code input_audio}
 * content part (JCLAW-654 follow-up). Base64 inflates payloads 4/3× and a
 * PCM WAV of a few minutes blows straight past provider request limits —
 * the live failure mode: a 33 MB recording became ~44 MB of base64 and
 * OpenRouter rejected every attempt, surfacing only as retries. Anything
 * large or losslessly-encoded is transcoded ONCE to mono 128k MP3 and
 * cached beside the attachment ({@code <file>.llm.mp3}), because chat
 * re-sends the audio part on every subsequent turn of the conversation.
 *
 * <p>Shared by the chat passthrough ({@code VisionAudioAssembler}) and the
 * {@code diarize_audio} tool.
 */
public final class LlmAudio {

    /** One {@code input_audio} payload: bare base64 plus the format hint. */
    public record Prepared(String base64, String format) {}

    /** Formats the OpenAI {@code input_audio.format} hint understands. */
    public static final String[] FORMAT_CANDIDATES = {
            "mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "opus", "weba"
    };

    /** Above this size the audio is transcoded regardless of container. */
    static final long TRANSCODE_THRESHOLD_BYTES = 6L * 1024 * 1024;

    private LlmAudio() {}

    /** Prepare an attachment, using/refreshing the sibling MP3 cache. */
    public static Prepared prepare(MessageAttachment att) throws IOException {
        var path = services.AgentService.workspaceRoot().resolve(att.storagePath);
        return prepare(path, att.mimeType);
    }

    /** Path-level core (the diarize tool resolves its own path). */
    public static Prepared prepare(Path path, String mimeType) throws IOException {
        var format = MimeExtensions.forMime(mimeType, FORMAT_CANDIDATES);
        long size = Files.size(path);
        // Size is the only thing providers reject on — a short voice-note
        // WAV rides fine raw; only multi-minute/lossless recordings balloon
        // past request limits under base64.
        if (size <= TRANSCODE_THRESHOLD_BYTES) {
            return new Prepared(Base64.getEncoder().encodeToString(Files.readAllBytes(path)), format);
        }
        var cached = path.resolveSibling(path.getFileName() + ".llm.mp3");
        if (!Files.isRegularFile(cached)
                || Files.getLastModifiedTime(cached).compareTo(Files.getLastModifiedTime(path)) < 0) {
            transcode(path, cached);
        }
        return new Prepared(Base64.getEncoder().encodeToString(Files.readAllBytes(cached)), "mp3");
    }

    private static void transcode(Path src, Path dest) throws IOException {
        var tmp = Files.createTempFile("jclaw-llm-audio-", ".mp3");
        try {
            var proc = new ProcessBuilder("ffmpeg", "-y", "-i", src.toString(),
                    "-ac", "1", "-b:a", "128k", tmp.toString())
                    .redirectErrorStream(true).start();
            String output = new String(proc.getInputStream().readAllBytes());
            int exit;
            try {
                exit = proc.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while transcoding audio for the LLM");
            }
            if (exit != 0) {
                throw new IOException("ffmpeg exited %d: %s".formatted(exit,
                        output.substring(Math.max(0, output.length() - 300))));
            }
            Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Logger.info("LlmAudio: transcoded %s -> %s (%d KB)", src.getFileName(),
                    dest.getFileName(), Files.size(dest) / 1024);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
