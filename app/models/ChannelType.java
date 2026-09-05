package models;

import java.util.Map;

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
    WHATSAPP("whatsapp"),
    /**
     * Real-time voice mode. Unlike the others this is not a bindable inbound
     * channel — there is no connector or credential for it — but since JCLAW-862
     * a voice session owns a conversation of its own, so the value genuinely
     * appears in {@code channel_type} and belongs here. Before that it existed
     * only as a per-turn tag and {@code fromValue("voice")} returned null for a
     * row that was really in the table.
     */
    VOICE("voice");

    public final String value;

    ChannelType(String value) {
        this.value = value;
    }

    @Override
    public String wireValue() {
        return value;
    }

    private static final Map<String, ChannelType> BY_VALUE =
            ValueEnum.indexOf(values());

    /**
     * Resolve a raw database/wire string to the corresponding enum constant.
     * Returns {@code null} for unrecognized values so callers can fall through
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
