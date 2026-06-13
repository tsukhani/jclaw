package channels;

import it.auties.whatsapp.api.DisconnectReason;
import it.auties.whatsapp.api.QrHandler;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.controller.ControllerSerializer;
import it.auties.whatsapp.model.info.ChatMessageInfo;
import it.auties.whatsapp.model.jid.Jid;
import models.WhatsAppBinding;
import play.Play;
import services.EventLogger;
import services.Tx;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Wraps ONE Cobalt {@link Whatsapp} (WhatsApp-Web / {@code it.auties.whatsapp})
 * instance for ONE {@link WhatsAppBinding} (JCLAW-448). One session per binding;
 * the {@link WhatsAppCobaltRunner} owns the live set and reconciles them, exactly
 * as {@link SlackSocketModeRunner} owns its {@code SocketModeClient}s.
 *
 * <p><b>Pairing / resume.</b> Cobalt persists a paired session under a protobuf
 * serializer ({@link ControllerSerializer#toProtobuf(Path)}). Each binding gets
 * its own directory ({@code data/whatsapp-cobalt/<bindingId>/}) and a stable
 * {@link UUID} derived from the binding id, so the same binding always resumes the
 * same on-disk session:
 * <ul>
 *   <li>{@link #connect(WhatsAppBinding, Consumer)} — an UNPAIRED binding: builds
 *       {@code unregistered(QrHandler)} and feeds each emitted QR string to the
 *       supplied consumer (the runner stores it for {@code WhatsAppQrController}).</li>
 *   <li>{@link #resume(WhatsAppBinding)} — an already-paired binding: builds
 *       {@code registered()} and reconnects without a QR.</li>
 * </ul>
 *
 * <p><b>Disconnect handling.</b> Cobalt owns transient reconnection internally; on
 * {@link DisconnectReason#LOGGED_OUT} we surface the binding as needing re-pair
 * (clear {@code ownerJid}); on {@link DisconnectReason#BANNED} we disable the
 * binding in a Tx so the runner stops trying to reconnect a banned number.
 *
 * <p><b>Inbound.</b> A new-chat-message listener parses via
 * {@link WhatsAppCobaltParser} and hands the result to the single shared seam
 * {@link WhatsAppInbound#dispatchMessage}. No business logic lives here. The live
 * {@link ChatMessageInfo} objects are cached (bounded) by message id so the
 * outbound channel ({@link WhatsAppCobaltChannel}) and media downloader
 * ({@link WhatsAppMediaDownloader}) can resolve a message back to its Cobalt object
 * for {@code downloadMedia} / {@code sendReaction}.
 */
public final class WhatsAppCobaltSession {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "whatsapp";

    /** Root for per-binding on-disk Cobalt sessions; resolved against the app
     *  root like {@code data/jclaw-lucene} so cwd never matters. */
    static final String SESSION_ROOT = "data/whatsapp-cobalt";

    /** Stable namespace so a binding id maps to the same session UUID every run. */
    private static final UUID NAMESPACE =
            UUID.fromString("7f1d3c0a-0000-4000-8000-000000000001");

    /** Bound on the recent-message cache used to resolve a message id back to its
     *  live {@link ChatMessageInfo} (for media download / reactions). Inbound
     *  media is downloaded immediately after receipt, so a small window suffices. */
    static final int RECENT_MESSAGE_CACHE_SIZE = 256;

    private final Long bindingId;
    private volatile Whatsapp whatsapp;
    private volatile Jid ownerJid;

    /** message id → live Cobalt object, LRU-bounded, access-synchronized. */
    private final Map<String, ChatMessageInfo> recentMessages =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ChatMessageInfo> eldest) {
                    return size() > RECENT_MESSAGE_CACHE_SIZE;
                }
            });

    public WhatsAppCobaltSession(Long bindingId) {
        this.bindingId = bindingId;
    }

    public Long bindingId() {
        return bindingId;
    }

    /** True once Cobalt reports a live socket. */
    public boolean isConnected() {
        var wa = whatsapp;
        return wa != null && wa.isConnected();
    }

    /** The paired user's JID (the binding owner), or null before pairing. */
    public Jid ownerJid() {
        return ownerJid;
    }

    /** This session's own JID for group mention-gating: the cached owner JID, or
     *  — on a resumed session whose logged-in event hasn't (re)fired — the JID the
     *  Cobalt store already holds. Null when neither is available (pre-pairing);
     *  the parser then treats group mentions as non-matching, the safe default. */
    private Jid botJid() {
        if (ownerJid != null) return ownerJid;
        var wa = whatsapp;
        return wa != null ? wa.store().jid().orElse(null) : null;
    }

    /** The underlying Cobalt handle (for outbound send / media download). Null
     *  until connect/resume has built it. */
    public Whatsapp whatsapp() {
        return whatsapp;
    }

    /** Resolve a previously-seen inbound message id back to its live Cobalt
     *  object, or null if it has aged out of the cache. */
    public ChatMessageInfo recentMessage(String messageId) {
        if (messageId == null) return null;
        return recentMessages.get(messageId);
    }

    /**
     * Connect an UNPAIRED binding: show a QR. Each QR string Cobalt emits is
     * passed to {@code qrConsumer} (the runner stores the latest for the QR
     * endpoint). On successful login the paired JID is captured and persisted to
     * {@code binding.ownerJid}. Idempotent-ish: a second call rebuilds.
     */
    public void connect(WhatsAppBinding binding, Consumer<String> qrConsumer) {
        try {
            var wa = builder(binding).unregistered(qrHandler(qrConsumer));
            wireListeners(wa);
            this.whatsapp = wa;
            wa.connect();
            EventLogger.info(LOG_CATEGORY, agentName(binding), LOG_SOURCE,
                    "WhatsApp-Web pairing started for binding %s".formatted(bindingId));
        } catch (Exception e) {
            EventLogger.error(LOG_CATEGORY, agentName(binding), LOG_SOURCE,
                    "WhatsApp-Web connect failed for binding %s: %s".formatted(bindingId, e.getMessage()));
        }
    }

    /**
     * Resume an already-paired binding without a QR. If Cobalt has no serialized
     * session on disk for this binding (e.g. the {@code ownerJid} is persisted but
     * the data dir was wiped), {@code registered()} is empty — we fall back to
     * {@link #connect} so a fresh QR surfaces and the operator can re-pair, rather
     * than silently doing nothing. {@code qrConsumer} receives that fallback QR.
     */
    public void resume(WhatsAppBinding binding, Consumer<String> qrConsumer) {
        try {
            var registered = builder(binding).registered();
            if (registered.isEmpty()) {
                EventLogger.warn(LOG_CATEGORY, agentName(binding), LOG_SOURCE,
                        "No stored WhatsApp-Web session for binding %s; falling back to QR re-pair".formatted(bindingId));
                connect(binding, qrConsumer);
                return;
            }
            var wa = registered.get();
            wireListeners(wa);
            this.whatsapp = wa;
            wa.connect();
            EventLogger.info(LOG_CATEGORY, agentName(binding), LOG_SOURCE,
                    "WhatsApp-Web session resumed for binding %s".formatted(bindingId));
        } catch (Exception e) {
            EventLogger.error(LOG_CATEGORY, agentName(binding), LOG_SOURCE,
                    "WhatsApp-Web resume failed for binding %s: %s".formatted(bindingId, e.getMessage()));
        }
    }

    /** Close the socket without invalidating the stored session (so a later
     *  {@link #resume} reconnects). Best-effort; never throws. */
    public void disconnect() {
        var wa = whatsapp;
        if (wa == null) return;
        try {
            wa.disconnect();
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "WhatsApp-Web disconnect error for binding %s: %s".formatted(bindingId, e.getMessage()));
        } finally {
            whatsapp = null;
            recentMessages.clear();
        }
    }

    // --- internals ---

    /** Build the connection seed shared by connect/resume: a per-binding protobuf
     *  serializer + a stable session UUID. {@code webBuilder().newConnection(uuid)}
     *  yields a {@link it.auties.whatsapp.api.WebOptionsBuilder} the caller turns
     *  into a paired/unpaired {@link Whatsapp}. */
    private it.auties.whatsapp.api.WebOptionsBuilder builder(WhatsAppBinding binding) {
        var serializer = ControllerSerializer.toProtobuf(sessionDir());
        return Whatsapp.webBuilder()
                .serializer(serializer)
                .newConnection(sessionUuid())
                .name(sessionName(binding));
    }

    private QrHandler qrHandler(Consumer<String> qrConsumer) {
        return QrHandler.toString(qr -> {
            if (qrConsumer != null && qr != null) {
                qrConsumer.accept(qr);
            }
        });
    }

    private void wireListeners(Whatsapp wa) {
        wa.addLoggedInListener(api -> onLoggedIn());
        wa.addNewChatMessageListener(this::onNewChatMessage);
        wa.addDisconnectedListener(this::onDisconnected);
    }

    private void onLoggedIn() {
        var wa = whatsapp;
        if (wa == null) return;
        var jid = wa.store().jid().orElse(null);
        if (jid == null) return;
        this.ownerJid = jid;
        persistOwnerJid(jid.toString());
        EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                "WhatsApp-Web paired for binding %s as %s".formatted(bindingId, jid));
    }

    /** Inbound seam: cache the live object, parse, hand to the shared dispatcher.
     *  Re-reads the binding in a Tx so the dispatcher works against a fresh row
     *  (owner/enabled may have changed since connect). */
    void onNewChatMessage(ChatMessageInfo info) {
        try {
            if (info == null) return;
            if (info.id() != null) {
                recentMessages.put(info.id(), info);
            }
            var msg = WhatsAppCobaltParser.parse(info, botJid());
            if (msg == null) return;
            var binding = Tx.run(() -> {
                WhatsAppBinding b = WhatsAppBinding.findById(bindingId);
                return (b == null || !b.enabled) ? null : b;
            });
            if (binding == null) return;
            WhatsAppInbound.dispatchMessage(binding, msg);
        } catch (Exception e) {
            EventLogger.error(LOG_CATEGORY, null, LOG_SOURCE,
                    "WhatsApp-Web inbound parse/dispatch error for binding %s: %s"
                            .formatted(bindingId, e.getMessage()));
        }
    }

    /**
     * React to a disconnect. Cobalt reconnects transient drops itself; we only
     * act on the terminal reasons:
     * <ul>
     *   <li>{@link DisconnectReason#LOGGED_OUT} — the session is invalid (the user
     *       unlinked the device). Clear {@code ownerJid} so the card shows
     *       "needs re-pair"; the runner's next reconcile re-opens with a QR.</li>
     *   <li>{@link DisconnectReason#BANNED} — the number was banned. Disable the
     *       binding so we stop hammering a dead account.</li>
     *   <li>{@link DisconnectReason#DISCONNECTED} / {@link DisconnectReason#RECONNECTING}
     *       — transient; log only, Cobalt handles it.</li>
     * </ul>
     */
    void onDisconnected(DisconnectReason reason) {
        switch (reason) {
            case LOGGED_OUT -> {
                this.ownerJid = null;
                persistOwnerJid(null);
                EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                        "WhatsApp-Web binding %s logged out; awaiting re-pair".formatted(bindingId));
            }
            case BANNED -> {
                disableBinding();
                EventLogger.error(LOG_CATEGORY, null, LOG_SOURCE,
                        "WhatsApp-Web binding %s BANNED; disabled".formatted(bindingId));
            }
            case RECONNECTING, DISCONNECTED -> EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                    "WhatsApp-Web binding %s %s".formatted(bindingId, reason));
        }
    }

    private void persistOwnerJid(String jid) {
        try {
            Tx.run(() -> {
                WhatsAppBinding b = WhatsAppBinding.findById(bindingId);
                if (b != null) {
                    b.ownerJid = jid;
                    b.save();
                }
            });
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Failed to persist ownerJid for binding %s: %s".formatted(bindingId, e.getMessage()));
        }
    }

    private void disableBinding() {
        try {
            Tx.run(() -> {
                WhatsAppBinding b = WhatsAppBinding.findById(bindingId);
                if (b != null) {
                    b.enabled = false;
                    b.save();
                }
            });
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Failed to disable banned binding %s: %s".formatted(bindingId, e.getMessage()));
        }
    }

    /** Per-binding session directory under the app root. Public for the
     *  default-package test seam. */
    public Path sessionDir() {
        var root = Path.of(SESSION_ROOT);
        var base = root.isAbsolute() ? root
                : Play.applicationPath.toPath().resolve(root);
        return base.resolve(String.valueOf(bindingId));
    }

    /** Deterministic per-binding session UUID so resume always finds the same
     *  serialized store. Public for the default-package test seam. */
    public UUID sessionUuid() {
        return UUID.nameUUIDFromBytes(
                (NAMESPACE + ":" + bindingId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String sessionName(WhatsAppBinding binding) {
        var agent = agentName(binding);
        return agent != null ? "JClaw-" + agent : "JClaw-" + bindingId;
    }

    private static String agentName(WhatsAppBinding binding) {
        return binding != null && binding.agent != null ? binding.agent.name : null;
    }
}
