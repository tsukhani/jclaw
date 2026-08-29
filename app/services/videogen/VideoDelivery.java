package services.videogen;

import channels.ChannelRegistry;
import models.ChannelType;
import models.Conversation;
import play.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Push a finished clip out to the channel that asked for it (JCLAW-1057).
 *
 * <p>{@code generate_image} and {@code generate_audio} return their bytes during the
 * turn, so they ride out on the assistant's reply through the normal channel sender.
 * {@code generate_video} returns a job reference and the bytes arrive minutes later on
 * the poller, by which time the reply has already gone. Nothing sent them on.
 *
 * <p>Web chat hid this: the UI polls and re-renders the message once its attachment
 * fills, so the clip appears on its own. Telegram, Slack and WhatsApp have no such
 * mechanism — they received only the "started generating" text, and two verified 5 MB
 * clips sat in the database undelivered.
 */
public final class VideoDelivery {

    private VideoDelivery() {}

    /**
     * Best-effort send of {@code bytes} to {@code conversation}'s channel.
     *
     * <p>Never throws. A delivery failure must not undo a generation that succeeded — the
     * clip is already stored on the message either way, so the worst case degrades to the
     * behavior this method exists to fix rather than losing the job.
     *
     * <p>Public because Play compiles {@code test/} into the default package, which
     * cannot see package-private members of {@code services.videogen}.
     *
     * @return true when a channel accepted the clip
     */
    public static boolean send(Conversation conversation, byte[] bytes, String prompt) {
        // A job submitted from a scheduled task has no conversation, and a web
        // conversation needs nothing: its UI polls the attachment into place.
        if (conversation == null || conversation.channelType == null) return false;
        if (ChannelType.WEB.value.equals(conversation.channelType)) return false;
        if (conversation.peerId == null || conversation.peerId.isBlank()) return false;

        File tmp = null;
        try {
            var channel = ChannelRegistry.forConversation(conversation);
            if (channel == null) return false;

            // The send contract takes a File, and the bytes are only in memory here.
            // Files.createTempFile, not File.createTempFile: the NIO form creates with
            // owner-only permissions on POSIX, where the legacy one leaves a readable
            // file in a world-writable directory for the length of the upload.
            tmp = Files.createTempFile("jclaw-video-", ".mp4").toFile();
            Files.write(tmp.toPath(), bytes);

            // sendDocument rather than a video-specific call: it is the cross-channel
            // contract every channel either implements or refuses uniformly, and a
            // channel with no upload path returns FAILED instead of throwing.
            var result = channel.sendDocument(conversation.peerId, tmp, caption(prompt));
            var ok = result != null && result.ok();
            if (!ok) {
                Logger.warn("videogen: %s channel refused the finished clip for conversation %s",
                        conversation.channelType, conversation.id);
            }
            return ok;
        } catch (IOException | RuntimeException e) {
            Logger.error(e, "videogen: failed to deliver the finished clip to conversation %s",
                    conversation.id);
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp.toPath());
                } catch (IOException e) {
                    // Say why rather than dropping a silent false; the JVM still clears it.
                    Logger.warn("videogen: could not remove the temp clip %s (%s)",
                            tmp, e.getMessage());
                    tmp.deleteOnExit();
                }
            }
        }
    }

    /** Echo the prompt so a clip arriving minutes later is identifiable. */
    private static String caption(String prompt) {
        if (prompt == null || prompt.isBlank()) return "Your video is ready.";
        var trimmed = prompt.strip();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 177) + "…";
    }
}
