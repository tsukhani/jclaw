package channels;

import models.WhatsAppBinding;
import models.WhatsAppTransport;
import services.EventLogger;
import services.Tx;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * WhatsApp-Web (Cobalt) connection runner (JCLAW-449). The direct analog of
 * {@link SlackSocketModeRunner}: an idempotent {@link #reconcile()} (called at boot
 * by {@code ChannelRunnerJob} and after every binding CRUD) opens a
 * {@link WhatsAppCobaltSession} for each enabled {@code WHATSAPP_WEB} binding and
 * closes the sessions whose binding vanished or was disabled; {@link #stop()} closes
 * them all at shutdown.
 *
 * <p>Unlike Slack's app-token model there's no per-binding secret to rotate on —
 * a WhatsApp-Web binding is identified solely by its id and pairs lazily via QR —
 * so the ACTIVE set is keyed on binding id alone. A binding the runner has never
 * seen is opened with {@link WhatsAppCobaltSession#connect} (QR pairing if needed,
 * resume if a session is already on disk); Cobalt itself resumes a stored session
 * transparently when {@code registered()} succeeds, so the runner doesn't track
 * paired-vs-unpaired — it always calls {@code connect} for a fresh id and lets the
 * session pick the on-disk path.
 *
 * <p>The latest pending QR string per binding is exposed via
 * {@link #pendingQr(Long)} so {@link controllers.WhatsAppQrController} can return it
 * to the pairing UI without reaching into a session.
 */
public final class WhatsAppCobaltRunner {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "whatsapp";

    /** bindingId → live session. The presence of a key means "active". */
    private static final ConcurrentHashMap<Long, WhatsAppCobaltSession> HANDLES = new ConcurrentHashMap<>();
    /** bindingId → latest QR string awaiting a scan (cleared on successful pair via reconcile). */
    private static final ConcurrentHashMap<Long, String> PENDING_QR = new ConcurrentHashMap<>();

    private WhatsAppCobaltRunner() {}

    /**
     * Reconcile live sessions against the desired set
     * ({@link WhatsAppBinding#findAllEnabledByTransport} for {@code WHATSAPP_WEB}):
     * close sessions whose binding vanished or was disabled, open new ones.
     * Synchronized so concurrent admin saves don't race, exactly like
     * {@link SlackSocketModeRunner#reconcile()}.
     */
    public static synchronized void reconcile() {
        var desired = Tx.run(() -> WhatsAppBinding.findAllEnabledByTransport(WhatsAppTransport.WHATSAPP_WEB));
        var desiredIds = new HashSet<Long>();
        for (var b : desired) desiredIds.add(b.id);

        // Close active sessions that vanished or were disabled.
        for (var bindingId : new HashSet<>(HANDLES.keySet())) {
            if (!desiredIds.contains(bindingId)) {
                unregister(bindingId);
            }
        }
        // Open any desired binding not currently active.
        for (var target : desired) {
            if (!HANDLES.containsKey(target.id)) {
                register(target);
            }
        }
    }

    /** Close all live sessions. Safe to call at app shutdown. */
    public static synchronized void stop() {
        for (var bindingId : new HashSet<>(HANDLES.keySet())) {
            unregister(bindingId);
        }
        PENDING_QR.clear();
    }

    /** Test/admin introspection: binding ids with a live session. */
    public static Set<Long> activeBindingIds() {
        return new HashSet<>(HANDLES.keySet());
    }

    /** The latest pending QR string for a binding (awaiting a scan), or null when
     *  none is pending (not yet generated, or already paired). */
    public static String pendingQr(Long bindingId) {
        return PENDING_QR.get(bindingId);
    }

    /** True when a live session exists for the binding and reports a connected
     *  socket — i.e. paired and online. */
    public static boolean isPaired(Long bindingId) {
        var session = HANDLES.get(bindingId);
        return session != null && session.isConnected() && session.ownerJid() != null;
    }

    /**
     * The live session for a binding, or null if none is active. Used by
     * {@link WhatsAppCobaltChannel} to resolve the Cobalt handle for outbound send
     * and by {@link WhatsAppMediaDownloader} to pull inbound media bytes.
     */
    public static WhatsAppCobaltSession session(Long bindingId) {
        return bindingId == null ? null : HANDLES.get(bindingId);
    }

    /**
     * Open (and connect) a session for {@code binding}. A QR consumer feeds the
     * latest pairing QR into {@link #PENDING_QR}; the session clears it on a
     * successful login via {@link WhatsAppCobaltSession}'s logged-in path (the QR
     * is moot once paired, and the next reconcile sees {@code isPaired}). Mirrors
     * {@link SlackSocketModeRunner#register}.
     */
    public static void register(WhatsAppBinding binding) {
        var bindingId = binding.id;
        try {
            var session = new WhatsAppCobaltSession(bindingId);
            HANDLES.put(bindingId, session);
            // An already-paired binding (ownerJid persisted) resumes silently; a
            // fresh one shows a QR. Resume is attempted first; if there's no stored
            // session, the session logs "awaiting re-pair" and we fall to a QR open.
            Consumer<String> qrConsumer = qr -> PENDING_QR.put(bindingId, qr);
            if (binding.ownerJid != null && !binding.ownerJid.isBlank()) {
                session.resume(binding, qrConsumer);
            } else {
                session.connect(binding, qrConsumer);
            }
            EventLogger.info(LOG_CATEGORY, agentName(binding), LOG_SOURCE,
                    "WhatsApp-Web session registered for binding %s".formatted(bindingId));
        } catch (Exception e) {
            HANDLES.remove(bindingId);
            EventLogger.error(LOG_CATEGORY, agentName(binding), LOG_SOURCE,
                    "WhatsApp-Web register failed for binding %s: %s".formatted(bindingId, e.getMessage()));
        }
    }

    private static void unregister(Long bindingId) {
        PENDING_QR.remove(bindingId);
        var session = HANDLES.remove(bindingId);
        if (session == null) return;
        try {
            session.disconnect();
            EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                    "WhatsApp-Web session closed for binding %s".formatted(bindingId));
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "WhatsApp-Web close error for binding %s: %s".formatted(bindingId, e.getMessage()));
        }
    }

    private static String agentName(WhatsAppBinding binding) {
        return binding != null && binding.agent != null ? binding.agent.name : null;
    }
}
