package controllers;

import com.google.gson.JsonObject;

import java.util.function.BiFunction;

/**
 * Shared JSON body keys and helpers for the per-agent channel-binding controllers
 * ({@link ApiSlackBindingsController}, {@link ApiTelegramBindingsController},
 * {@link ApiWhatsAppBindingsController}). These three CRUD controllers speak the same binding
 * vocabulary (JCLAW-708); the per-channel {@code BindingView} projections stay separate by design
 * (they surface channel-specific fields), but the request-body keys and the transport parse are common.
 */
public final class BindingKeys {

    private BindingKeys() { /* constants + static helpers */ }

    /** EventLogger category shared by every binding mutation. */
    public static final String EVENT_CATEGORY_CHANNEL = "channel";

    public static final String KEY_AGENT_ID = "agentId";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_TRANSPORT = "transport";
    public static final String KEY_WEBHOOK_BASE_URL = "webhookBaseUrl";
    public static final String KEY_REPLY_TO_MODE = "replyToMode";

    /**
     * Parse the {@code transport} field with a channel-specific enum parser. Reads the raw
     * {@code transport} string (null when absent or JSON-null) and delegates to {@code parser}, which
     * applies {@code fallback} on null/blank/unknown — e.g. {@code ChannelTransport::parse} or
     * {@code WhatsAppTransport::parse}.
     */
    public static <T> T parseTransport(JsonObject body, T fallback, BiFunction<String, T, T> parser) {
        String raw = body.has(KEY_TRANSPORT) && !body.get(KEY_TRANSPORT).isJsonNull()
                ? body.get(KEY_TRANSPORT).getAsString() : null;
        return parser.apply(raw, fallback);
    }
}
