package models;

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
