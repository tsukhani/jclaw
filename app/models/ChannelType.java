package models;

/**
 * Enum for the {@code channel_type} column on {@link Conversation}. Values are
 * stored as lowercase strings in the database so JPA {@code @Enumerated} is
 * <em>not</em> used — Play 1.x keeps the column as a plain VARCHAR and
 * conversion goes through the accessors on this enum.
 *
 * <p>JCLAW-141: the old {@code resolve()} method that mapped a type to a
 * {@link channels.Channel} (and returned {@code null} for Telegram + Web) is
 * gone — channel resolution now lives in
 * {@link channels.ChannelRegistry#forConversation}, which carries the per-binding
 * Telegram token and returns a real {@link channels.WebChannel}, so dispatch
 * never branches on the type.
 */
public enum ChannelType implements ValueEnum {

    WEB("web"),
    SLACK("slack"),
    TELEGRAM("telegram"),
    WHATSAPP("whatsapp");

    public final String value;

    ChannelType(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    private static final java.util.Map<String, ChannelType> BY_VALUE =
            ValueEnum.indexOf(values());

    /**
     * Resolve a raw database/wire string to the corresponding enum constant.
     * Returns {@code null} for unrecognised values so callers can fall through
     * to a default branch without throwing.
     */
    public static ChannelType fromValue(String value) {
        return ValueEnum.fromValue(BY_VALUE, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
