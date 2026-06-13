package channels;

import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.model.contact.ContactStatus;
import it.auties.whatsapp.model.info.ChatMessageInfo;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.message.standard.DocumentMessageSimpleBuilder;
import it.auties.whatsapp.model.message.standard.ImageMessageSimpleBuilder;
import models.WhatsAppBinding;
import services.EventLogger;
import utils.TikaHolder;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Outbound {@link Channel} for a WhatsApp-Web (Cobalt) binding (JCLAW-450). The
 * WhatsApp-Web peer of the Cloud-API {@link WhatsAppChannel}: same
 * {@link Channel} contract — {@link #trySend}, {@link #sendPhoto},
 * {@link #sendDocument} — plus WhatsApp-Web-only reactions and presence/typing.
 *
 * <p>It holds no socket of its own: the live {@link WhatsAppCobaltSession} is owned
 * by {@link WhatsAppCobaltRunner}, so this channel resolves the handle by binding
 * id on each call ({@link WhatsAppCobaltRunner#session}). A null/disconnected
 * session yields {@link SendResult#FAILED} (never throws) so a reply during a
 * reconnect degrades quietly, like the other channels.
 *
 * <p>{@code peerId} is the chat JID string — a 1:1 user JID for a DM, a
 * {@code @g.us} group JID for a group reply — already computed by
 * {@link WhatsAppInbound#conversationPeerId}. Cobalt's {@code sendMessage} takes a
 * {@link it.auties.whatsapp.model.jid.JidProvider}; we parse the peer string back
 * into a {@link Jid}.
 *
 * <p><b>Media outbound</b> (image/document) works via the PDFBox-backed
 * {@code com.aspose.words} shim (JCLAW-451): Cobalt's media builders link through
 * {@code it.auties.whatsapp.util.Medias}, which statically references
 * {@code com.aspose:aspose-words} (a commercial artifact JClaw excludes) only to
 * count document pages + render a thumbnail — the shim satisfies that link with the
 * PDFBox we already ship. {@link #buildImage}/{@link #buildDocument} still catch a
 * {@link LinkageError} defensively, so a hypothetical missing shim degrades to a
 * logged {@link SendResult#FAILED} rather than letting an {@link Error} escape the
 * send thread.
 */
public final class WhatsAppCobaltChannel implements Channel {

    private static final String LOG_CATEGORY = "channel";
    private static final String WHATSAPP = "whatsapp";

    /** Bound on a single outbound Cobalt call before we treat it as failed. */
    private static final long SEND_TIMEOUT_SECONDS = 30;

    private final Long bindingId;

    private WhatsAppCobaltChannel(Long bindingId) {
        this.bindingId = bindingId;
    }

    /** The channel for {@code binding}'s live WhatsApp-Web session. Resolution of
     *  the actual socket is deferred to call time via the runner. */
    public static WhatsAppCobaltChannel forBinding(WhatsAppBinding binding) {
        return binding == null ? null : new WhatsAppCobaltChannel(binding.id);
    }

    @Override
    public String channelName() {
        return WHATSAPP;
    }

    @Override
    public SendResult trySend(String peerId, String text) {
        var wa = liveSession();
        if (wa == null) return SendResult.FAILED;
        var jid = toJid(peerId);
        if (jid == null) return SendResult.FAILED;
        try {
            wa.sendMessage(jid, text).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            EventLogger.info(LOG_CATEGORY, null, WHATSAPP, "Message sent to %s".formatted(peerId));
            return SendResult.OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.FAILED;
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, WHATSAPP, "Send failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    @Override
    public SendResult sendPhoto(String peerId, File file, String caption) {
        var wa = liveSession();
        if (wa == null) return SendResult.FAILED;
        var jid = toJid(peerId);
        if (jid == null) return SendResult.FAILED;
        try {
            var bytes = Files.readAllBytes(file.toPath());
            var image = buildImage(bytes, detectMime(file), blankToNull(caption));
            if (image == null) return SendResult.FAILED;
            wa.sendMessage(jid, image).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return SendResult.OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.FAILED;
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, WHATSAPP, "sendPhoto failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    @Override
    public SendResult sendDocument(String peerId, File file, String caption) {
        var wa = liveSession();
        if (wa == null) return SendResult.FAILED;
        var jid = toJid(peerId);
        if (jid == null) return SendResult.FAILED;
        try {
            var bytes = Files.readAllBytes(file.toPath());
            var doc = buildDocument(bytes, file.getName(), detectMime(file), blankToNull(caption));
            if (doc == null) return SendResult.FAILED;
            wa.sendMessage(jid, doc).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return SendResult.OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.FAILED;
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, WHATSAPP, "sendDocument failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    /**
     * Build an outbound image message. Cobalt's media builders route through
     * {@code it.auties.whatsapp.util.Medias}, whose {@code com.aspose.words} link is
     * satisfied by the PDFBox-backed shim (JCLAW-451), so this normally succeeds.
     * The {@link LinkageError} catch is a defensive guard: were the shim ever absent
     * the class would fail to link, and we degrade to a logged null (→
     * {@link SendResult#FAILED}) rather than let an {@link Error} escape the send.
     */
    private static it.auties.whatsapp.model.message.standard.ImageMessage buildImage(
            byte[] bytes, String mime, String caption) {
        try {
            return new ImageMessageSimpleBuilder()
                    .media(bytes).mimeType(mime).caption(caption).build();
        } catch (LinkageError e) {
            warnMediaUnavailable("image", e);
            return null;
        }
    }

    /** Build an outbound document message; same shim-backed media path + defensive
     *  linkage guard as {@link #buildImage}. */
    private static it.auties.whatsapp.model.message.standard.DocumentMessage buildDocument(
            byte[] bytes, String fileName, String mime, String title) {
        try {
            return new DocumentMessageSimpleBuilder()
                    .media(bytes).fileName(fileName).mimeType(mime).title(title).build();
        } catch (LinkageError e) {
            warnMediaUnavailable("document", e);
            return null;
        }
    }

    private static void warnMediaUnavailable(String kind, LinkageError e) {
        EventLogger.warn(LOG_CATEGORY, null, WHATSAPP,
                "WhatsApp-Web %s send failed to link the com.aspose.words shim (JCLAW-451) — media unavailable: %s"
                        .formatted(kind, e.getMessage()));
    }

    /**
     * Send an emoji reaction on an earlier message (JCLAW-450 outbound reactions).
     * Resolves the target message from the session's recent-message cache by id;
     * a blank emoji removes the reaction (Cobalt's {@code sendReaction(info, "")}
     * convention). Returns false (never throws) when the session/target isn't
     * available.
     */
    public boolean sendReaction(String targetMessageId, String emoji) {
        var session = WhatsAppCobaltRunner.session(bindingId);
        if (session == null) return false;
        var wa = session.whatsapp();
        ChatMessageInfo target = session.recentMessage(targetMessageId);
        if (wa == null || target == null) return false;
        try {
            wa.sendReaction(target, emoji == null ? "" : emoji)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, WHATSAPP, "sendReaction failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

    /**
     * Show the WhatsApp typing indicator ({@link ContactStatus#COMPOSING}) in a
     * chat. Best-effort: a failure to set presence must never break a reply, so
     * this swallows everything and returns void. Overrides the {@link Channel}
     * no-op default so {@code WhatsAppStreamingSink} cues it polymorphically.
     */
    @Override
    public void startTyping(String peerId) {
        var wa = liveSession();
        if (wa == null) return;
        var jid = toJid(peerId);
        if (jid == null) return;
        try {
            wa.changePresence(jid, ContactStatus.COMPOSING);
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, WHATSAPP, "startTyping failed: %s".formatted(e.getMessage()));
        }
    }

    // --- internals ---

    /** The connected Cobalt handle for this binding, or null when no live,
     *  connected session exists. */
    private Whatsapp liveSession() {
        var session = WhatsAppCobaltRunner.session(bindingId);
        if (session == null || !session.isConnected()) return null;
        return session.whatsapp();
    }

    private static Jid toJid(String peerId) {
        if (peerId == null || peerId.isBlank()) return null;
        try {
            return Jid.of(peerId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String detectMime(File file) {
        try {
            return TikaHolder.TIKA.detect(file);
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
