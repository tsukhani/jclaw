package channels;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slack.api.Slack;
import com.slack.api.socket_mode.SocketModeClient;
import models.SlackBinding;
import services.EventLogger;
import services.Tx;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * JCLAW-351: Slack Socket Mode transport. Each enabled {@code SOCKET}-transport binding
 * opens its own WebSocket to Slack with its own app-level token ({@code xapp-}), so a
 * bot can run with NO public Request URL (laptop / dev). Mirrors
 * {@link TelegramPollingRunner}: an idempotent {@link #reconcile()} (called at boot by
 * {@code ChannelRunnerJob} and on every binding CRUD) starts/stops/restarts per-binding
 * connections; {@link #stop()} closes them at shutdown.
 *
 * <p>Events arrive over the socket in the same shape as the Events API webhook, so both
 * dispatch through {@link SlackInbound}. The SDK owns reconnection
 * ({@code setAutoReconnectEnabled(true)}); we only track the live handle per binding.
 */
public final class SlackSocketModeRunner {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "slack";
    private static final String KEY_ENVELOPE_ID = "envelope_id";
    private static final String KEY_PAYLOAD = "payload";

    /** bindingId → active app token. Used to detect app-token rotation on reconcile. */
    private static final ConcurrentHashMap<Long, String> ACTIVE = new ConcurrentHashMap<>();
    /** bindingId → the live connection handle (the {@link SocketModeClient}, a Closeable). */
    private static final ConcurrentHashMap<Long, AutoCloseable> HANDLES = new ConcurrentHashMap<>();

    private SlackSocketModeRunner() {}

    /**
     * Reconcile live connections against the desired set
     * ({@link SlackBinding#findAllEnabledByTransport} for {@code SOCKET}): close vanished /
     * disabled / app-token-rotated ones, open new ones. Synchronized so concurrent admin
     * saves don't race.
     */
    public static synchronized void reconcile() {
        var desired = Tx.run(() -> SlackBinding.findAllEnabledByTransport(ChannelTransport.SOCKET));
        var desiredById = new HashMap<Long, SlackBinding>();
        for (var b : desired) desiredById.put(b.id, b);

        // Close active connections that vanished or had their app token rotated.
        for (var entry : new HashMap<>(ACTIVE).entrySet()) {
            Long bindingId = entry.getKey();
            String activeToken = entry.getValue();
            var target = desiredById.get(bindingId);
            if (target == null || !Objects.equals(target.appToken, activeToken)) {
                unregister(bindingId);
            }
        }
        // Open any desired binding not currently active.
        for (var target : desired) {
            if (!ACTIVE.containsKey(target.id)) {
                register(target);
            }
        }
    }

    /** Close all live connections. Safe to call at app shutdown. */
    public static synchronized void stop() {
        for (var bindingId : new HashSet<>(HANDLES.keySet())) {
            unregister(bindingId);
        }
    }

    /** Test/admin introspection: binding ids with a live socket connection. */
    public static Set<Long> activeBindingIds() {
        return new HashSet<>(ACTIVE.keySet());
    }

    /**
     * Open a {@link SocketModeClient} for the binding's app token over the lightweight
     * Java-WebSocket backend, wire the message listener, and connect. The
     * {@link SocketModeClient} is a {@link java.io.Closeable}, so it serves as the teardown
     * handle directly. The SDK owns reconnection ({@code setAutoReconnectEnabled}).
     */
    private static void register(SlackBinding binding) {
        if (binding.appToken == null || binding.appToken.isBlank()) {
            EventLogger.warn(LOG_CATEGORY, binding.agent != null ? binding.agent.name : null, LOG_SOURCE,
                    "Slack binding %s uses SOCKET transport but has no app token; skipping".formatted(binding.id));
            return;
        }
        var bindingId = binding.id;
        try {
            var client = Slack.getInstance().socketMode(binding.appToken, SocketModeClient.Backend.JavaWebSocket);
            client.setAutoReconnectEnabled(true);
            client.addWebSocketMessageListener(message ->
                    routeMessage(bindingId, client::sendSocketModeResponse, message));
            client.connect();
            HANDLES.put(binding.id, client);
            ACTIVE.put(binding.id, binding.appToken);
            EventLogger.info(LOG_CATEGORY, binding.agent != null ? binding.agent.name : null, LOG_SOURCE,
                    "Socket Mode connected for binding %s".formatted(binding.id));
        } catch (Exception e) {
            EventLogger.error(LOG_CATEGORY, null, LOG_SOURCE,
                    "Socket Mode connect failed for binding %s: %s".formatted(binding.id, e.getMessage()));
        }
    }

    private static void unregister(Long bindingId) {
        ACTIVE.remove(bindingId);
        var handle = HANDLES.remove(bindingId);
        if (handle == null) return;
        try {
            handle.close();
            EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                    "Socket Mode closed for binding %s".formatted(bindingId));
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Socket Mode close error for binding %s: %s".formatted(bindingId, e.getMessage()));
        }
    }

    /**
     * Parse one raw Socket Mode frame, ack it (within Slack's 3 s window), and route it to
     * {@link SlackInbound}. {@code events_api} → the message pipeline (re-fetching the
     * binding for current owner/bot-id), {@code interactive} → approval resolution.
     * {@code hello} / {@code disconnect} carry no payload and are ignored (the SDK
     * reconnects on disconnect). Package-visible + ack-sender-injected so it's testable
     * without a live {@link SocketModeClient}.
     */
    public static void routeMessage(Long bindingId, Consumer<String> ackSender, String message) {
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception _) {
            return; // non-JSON / unparseable frame
        }
        var type = envelope.has("type") ? envelope.get("type").getAsString() : "";
        boolean dispatchable = "events_api".equals(type) || "interactive".equals(type);
        if (dispatchable && envelope.has(KEY_ENVELOPE_ID)) {
            var ack = new JsonObject();
            ack.addProperty(KEY_ENVELOPE_ID, envelope.get(KEY_ENVELOPE_ID).getAsString());
            ackSender.accept(ack.toString());
        }
        if (!dispatchable || !envelope.has(KEY_PAYLOAD) || !envelope.get(KEY_PAYLOAD).isJsonObject()) {
            return; // hello / disconnect / payload-less frame
        }
        var payload = envelope.getAsJsonObject(KEY_PAYLOAD);
        if ("events_api".equals(type)) {
            var binding = Tx.run(() -> {
                SlackBinding b = SlackBinding.findById(bindingId);
                return (b == null || !b.enabled) ? null : b;
            });
            if (binding != null) {
                SlackInbound.dispatchEvent(binding, payload);
            }
        } else {
            SlackInbound.dispatchInteractive(payload);
        }
    }
}
