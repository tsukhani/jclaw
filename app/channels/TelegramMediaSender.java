package channels;

import channels.Channel.SendResult;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import services.EventLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Native file-upload send path for a single bound Telegram bot token
 * (JCLAW-724, extracted from {@code TelegramSender}). Owns every multipart
 * upload — the {@code trySend{Photo,Document,Voice,Audio,Video}} family, the
 * {@link #sendMediaGroup} album path, and the {@link Channel}-contract
 * {@link #sendPhoto}/{@link #sendDocument} — plus the shared upload tail
 * ({@link #uploadVia}) that times, records, and logs each upload. All uploads
 * ride the tolerant {@link TelegramSendContext#uploadClient} (60 s r/w) rather
 * than the fast text client, since voice/audio/video/document bodies routinely
 * exceed the text-path timeouts.
 */
final class TelegramMediaSender {

    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "telegram";

    private final TelegramSendContext ctx;

    TelegramMediaSender(TelegramSendContext ctx) {
        this.ctx = ctx;
    }

    /**
     * JCLAW-141: generic cross-channel photo send (the {@link Channel} contract).
     * Delegates to {@link #trySendPhoto(String, java.io.File, String, ReplyParameters,
     * Integer, String)} (no reply/topic context) so a caller routing through the
     * uniform interface still uploads via the dedicated upload client.
     */
    SendResult sendPhoto(String peerId, File file, String caption) {
        if (file == null) return SendResult.FAILED;
        return trySendPhoto(peerId, file, file.getName(), null, null, caption)
                ? SendResult.OK : SendResult.FAILED;
    }

    /**
     * JCLAW-141: generic cross-channel document send (the {@link Channel} contract).
     * Delegates to {@link #trySendDocument(String, java.io.File, String,
     * ReplyParameters, Integer, String)} (no reply/topic context).
     */
    SendResult sendDocument(String peerId, File file, String caption) {
        if (file == null) return SendResult.FAILED;
        return trySendDocument(peerId, file, file.getName(), null, null, caption)
                ? SendResult.OK : SendResult.FAILED;
    }

    // ── Per-instance send path ──

    /**
     * Upload {@code file} as a Telegram photo. The {@code displayName} is shown
     * to the user as the filename hint; Telegram renders images inline, so
     * captions aren't used in this MVP — prose accompanying the photo arrives as
     * a separate text message above or below it.
     */
    boolean trySendPhoto(String peerId, File file, String displayName) {
        return trySendPhoto(peerId, file, displayName, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware photo upload. {@code replyParams}
     * (null to omit) attaches {@code reply_parameters}; {@code messageThreadId}
     * (already General-stripped by the caller; null to omit) scopes the upload
     * to a forum topic. The no-extra-args {@link #trySendPhoto(String, java.io.File, String)}
     * overload preserves the legacy call sites. Delegates to the caption-aware
     * overload with a null caption.
     */
    boolean trySendPhoto(String peerId, File file, String displayName,
                         ReplyParameters replyParams, Integer messageThreadId) {
        return trySendPhoto(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /**
     * JCLAW-364: caption-aware photo upload. {@code caption} (null/blank to
     * omit) rides as the photo's {@code caption} so prose adjacent to the file
     * reference arrives attached to the image instead of as a separate text
     * message. Other params as
     * {@link #trySendPhoto(String, java.io.File, String, ReplyParameters, Integer)}.
     */
    boolean trySendPhoto(String peerId, File file, String displayName,
                         ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendPhoto.builder()
                .chatId(peerId)
                .photo(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
        // JCLAW-122: upload via uploadClient (60 s r/w) rather than client (2 s w)
        // — a 1–2 MB screenshot reliably times out on the text-path timeouts.
        return uploadVia("Photo", peerId, file, displayName, () -> ctx.uploadClient().execute(request));
    }

    /**
     * Upload {@code file} as a Telegram document (download attachment). Covers
     * anything that isn't one of the image extensions Telegram renders inline.
     */
    boolean trySendDocument(String peerId, File file, String displayName) {
        return trySendDocument(peerId, file, displayName, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware document upload. Mirrors
     * {@link #trySendPhoto(String, java.io.File, String, ReplyParameters, Integer)} —
     * {@code replyParams} / {@code messageThreadId} (both null to omit) attach
     * {@code reply_parameters} / {@code message_thread_id}. The no-extra-args
     * overload preserves the legacy call sites. Delegates to the caption-aware
     * overload with a null caption.
     */
    boolean trySendDocument(String peerId, File file, String displayName,
                            ReplyParameters replyParams, Integer messageThreadId) {
        return trySendDocument(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /**
     * JCLAW-364: caption-aware document upload. {@code caption} (null/blank to
     * omit) rides as the document's {@code caption}. Other params as
     * {@link #trySendDocument(String, java.io.File, String, ReplyParameters, Integer)}.
     */
    boolean trySendDocument(String peerId, File file, String displayName,
                            ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendDocument.builder()
                .chatId(peerId)
                .document(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
        // JCLAW-122: upload via uploadClient (60 s r/w) — document bodies are
        // often larger than photos (PDFs, reports, zips).
        return uploadVia("Document", peerId, file, displayName, () -> ctx.uploadClient().execute(request));
    }

    // ── JCLAW-364: native media send paths ──
    //
    // Each mirrors trySendPhoto/trySendDocument: a legacy 3-arg overload, a
    // JCLAW-369 reply/topic 5-arg overload (caption null), and a caption-aware
    // 6-arg overload that builds + uploads the request. All upload via
    // uploadClient (60 s r/w) — voice/audio/video bodies routinely exceed the
    // text-path timeouts. The execute call is inlined per method because the
    // SDK's TelegramClient exposes a distinct, concretely-typed execute()
    // overload per send class (no shared PartialBotApiMethod entry point).

    /** Upload {@code file} as a Telegram voice note (.ogg/opus). */
    boolean trySendVoice(String peerId, File file, String displayName) {
        return trySendVoice(peerId, file, displayName, null, null);
    }

    /** Reply/topic-aware voice upload; delegates with a null caption. */
    boolean trySendVoice(String peerId, File file, String displayName,
                         ReplyParameters replyParams, Integer messageThreadId) {
        return trySendVoice(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /** Caption-aware voice upload. {@code caption} null/blank to omit. */
    boolean trySendVoice(String peerId, File file, String displayName,
                         ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendVoice.builder()
                .chatId(peerId)
                .voice(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
        return uploadVia("Voice", peerId, file, displayName, () -> ctx.uploadClient().execute(request));
    }

    /** Upload {@code file} as a Telegram audio track (.mp3 and other audio). */
    boolean trySendAudio(String peerId, File file, String displayName) {
        return trySendAudio(peerId, file, displayName, null, null);
    }

    /** Reply/topic-aware audio upload; delegates with a null caption. */
    boolean trySendAudio(String peerId, File file, String displayName,
                         ReplyParameters replyParams, Integer messageThreadId) {
        return trySendAudio(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /** Caption-aware audio upload. {@code caption} null/blank to omit. */
    boolean trySendAudio(String peerId, File file, String displayName,
                         ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendAudio.builder()
                .chatId(peerId)
                .audio(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
        return uploadVia("Audio", peerId, file, displayName, () -> ctx.uploadClient().execute(request));
    }

    /** Upload {@code file} as a Telegram video. */
    boolean trySendVideo(String peerId, File file, String displayName) {
        return trySendVideo(peerId, file, displayName, null, null);
    }

    /** Reply/topic-aware video upload; delegates with a null caption. */
    boolean trySendVideo(String peerId, File file, String displayName,
                         ReplyParameters replyParams, Integer messageThreadId) {
        return trySendVideo(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /** Caption-aware video upload. {@code caption} null/blank to omit. */
    boolean trySendVideo(String peerId, File file, String displayName,
                         ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendVideo.builder()
                .chatId(peerId)
                .video(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
        return uploadVia("Video", peerId, file, displayName, () -> ctx.uploadClient().execute(request));
    }

    /**
     * JCLAW-365: bundle 2–10 photos/videos into a single Telegram album via
     * {@code sendMediaGroup}. Each item in {@code items} becomes an
     * {@link InputMediaPhoto}
     * (for {@link TelegramOutboundPlanner.MediaKind#PHOTO}) or
     * {@link InputMediaVideo}
     * (everything else the caller passes — the planner only ever groups PHOTO
     * and VIDEO). The {@code caption} (null/blank to omit) rides on the FIRST
     * item only, matching Telegram's album-caption convention. {@code replyParams}
     * / {@code messageThreadId} (both null to omit) mirror the single-send
     * methods for JCLAW-369 reply/thread consistency.
     *
     * <p>Returns false (logged) on any API failure or an out-of-range item count
     * — never throws — so the caller can fall back to individual sends. Uploads
     * via {@link TelegramSendContext#uploadClient} (60 s r/w) like the other file paths.
     */
    boolean sendMediaGroup(String peerId,
                           List<TelegramOutboundPlanner.FileSegment> items,
                           String caption, ReplyParameters replyParams, Integer messageThreadId) {
        if (items == null || items.size() < 2 || items.size() > 10) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendMediaGroup requires 2-10 items; got %d"
                            .formatted(items == null ? 0 : items.size()));
            return false;
        }
        var medias = buildMediaGroupItems(items, caption);
        var builder = SendMediaGroup.builder()
                .chatId(peerId)
                .medias(medias);
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        var request = builder.build();
        long startNs = System.nanoTime();
        try {
            // sendMediaGroup returns a List<Message> — one per album item.
            var sent = ctx.uploadClient().execute(request);
            if (sent != null) {
                for (var m : sent) {
                    if (m != null) ctx.recordSent(peerId, m.getMessageId()); // JCLAW-383
                }
            }
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Media group sent to chat %s: %d items (elapsedMs=%d)"
                            .formatted(peerId, medias.size(), elapsedMs));
            return true;
        } catch (TelegramApiException e) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Media group send failed for %d items after %dms: %s"
                            .formatted(medias.size(), elapsedMs, e.getMessage()));
            return false;
        }
    }

    /** Build the album's per-item {@link InputMedia} list. The caption rides the
     *  first item only — Telegram surfaces it as the album caption; subsequent
     *  items carry none. */
    private List<InputMedia> buildMediaGroupItems(List<TelegramOutboundPlanner.FileSegment> items, String caption) {
        var medias = new ArrayList<InputMedia>(items.size());
        for (int i = 0; i < items.size(); i++) {
            var fs = items.get(i);
            var file = fs.file();
            var name = fs.displayName() != null ? fs.displayName() : file.getName();
            String itemCaption = i == 0 && caption != null && !caption.isBlank() ? caption : null;
            medias.add(buildInputMedia(fs.kind(), file, name, itemCaption));
        }
        return medias;
    }

    /**
     * Build the {@link InputMedia}
     * for one album item: a photo for {@link TelegramOutboundPlanner.MediaKind#PHOTO},
     * otherwise a video (the planner only groups photos + videos). The local file
     * is attached via {@code media(File, name)} so the SDK streams it in the
     * multipart body. {@code caption} null to omit.
     */
    private static InputMedia buildInputMedia(
            TelegramOutboundPlanner.MediaKind kind, File file, String name, String caption) {
        if (kind == TelegramOutboundPlanner.MediaKind.PHOTO) {
            var b = InputMediaPhoto.builder()
                    .media(file, name);
            if (caption != null) b.caption(caption);
            return b.build();
        }
        var b = InputMediaVideo.builder()
                .media(file, name);
        if (caption != null) b.caption(caption);
        return b.build();
    }

    /**
     * Upload call for the single-file trySend* paths. The SDK exposes a distinct
     * concretely-typed {@code execute()} per send class (no shared
     * {@code PartialBotApiMethod} entry point), so each caller supplies its own
     * {@code () -> uploadClient.execute(typedRequest)}; the checked
     * {@link TelegramApiException} is why this isn't a plain {@code Supplier}.
     */
    @FunctionalInterface
    private interface UploadCall {
        Message execute() throws TelegramApiException;
    }

    /**
     * JCLAW-408: shared upload tail for the five single-file trySend* methods —
     * times the upload, runs {@code exec}, records the sent message id for
     * reactions (JCLAW-383), and logs success/failure uniformly via
     * {@link #logMediaSent}/{@link #logMediaFailed}. Returns false (never throws)
     * on {@link TelegramApiException} so callers can fall back. Each trySend*
     * builds its typed request (chat/file/reply/thread/caption) and passes the
     * execute lambda here.
     */
    private boolean uploadVia(String kind, String peerId, File file,
                              String displayName, UploadCall exec) {
        long startNs = System.nanoTime();
        long fileSize = file.length();
        try {
            var sent = exec.execute();
            if (sent != null) ctx.recordSent(peerId, sent.getMessageId()); // JCLAW-383
            logMediaSent(kind, peerId, displayName, startNs, fileSize);
            return true;
        } catch (TelegramApiException e) {
            logMediaFailed(kind, displayName, startNs, e);
            return false;
        }
    }

    /** Shared success-log for the native media send methods. {@code kind} labels the line. */
    private static void logMediaSent(String kind, String peerId, String displayName,
                                     long startNs, long fileSize) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                "%s sent to chat %s: %s (elapsedMs=%d, bytes=%d)"
                        .formatted(kind, peerId, displayName, elapsedMs, fileSize));
    }

    /** Shared failure-log for the native media send methods. */
    private static void logMediaFailed(String kind, String displayName,
                                       long startNs, TelegramApiException e) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                "%s send failed for %s after %dms: %s"
                        .formatted(kind, displayName, elapsedMs, e.getMessage()));
    }
}
