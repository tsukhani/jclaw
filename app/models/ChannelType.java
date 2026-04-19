package models;

import channels.Channel;
import channels.SlackChannel;
import channels.TelegramChannel;
import channels.WhatsAppChannel;

/**
 * Enum for the {@code channel_type} column on {@link Conversation}. Values are
 * stored as lowercase strings in the database so JPA {@code @Enumerated} is
 * <em>not</em> used — Play 1.x keeps the column as a plain VARCHAR and
 * conversion goes through the accessors on this enum.
 */
public enum ChannelType {

    WEB("web"),
    SLACK("slack"),
    TELEGRAM("telegram"),
    WHATSAPP("whatsapp");

    public final String value;

    ChannelType(String value) {
        this.value = value;
    }

    /**
     * Return a {@link Channel} implementation for this type, or {@code null}
     * for types that don't support context-free outbound push. WEB responses
     * are DB-persisted and fetched on refresh; TELEGRAM needs a per-binding
     * bot token that the generic Channel contract can't carry, so callers
     * must route Telegram outbound through {@link TelegramChannel#forToken}
     * with the binding resolved from (agent, peerId).
     */
    public Channel resolve() {
        return switch (this) {
            case TELEGRAM -> null;
            case SLACK -> new SlackChannel();
            case WHATSAPP -> new WhatsAppChannel();
            case WEB -> null;
        };
    }

    /**
     * Resolve a raw database/wire string to the corresponding enum constant.
     * Returns {@code null} for unrecognised values so callers can fall through
     * to a default branch without throwing.
     */
    public static ChannelType fromValue(String value) {
        if (value == null) return null;
        return switch (value) {
            case "web" -> WEB;
            case "slack" -> SLACK;
            case "telegram" -> TELEGRAM;
            case "whatsapp" -> WHATSAPP;
            default -> null;
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
