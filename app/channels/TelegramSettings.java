package channels;

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The Telegram behaviour keys, edited under Channel defaults on the Channels &gt; Telegram page,
 * and the rules {@code ConfigService.setWithSideEffects} applies when one is written. The webhook
 * hardening keys ({@code telegram.webhook.*}) are read from application.conf and are not here.
 */
public final class TelegramSettings {

    public static final String PREFIX = "telegram.";

    public static final String REPLY_TO_MODE = "telegram.replyTo.mode";
    public static final String LINK_PREVIEW = "telegram.linkPreview";
    public static final String REACTIONS_NOTIFY = "telegram.reactions.notify";
    public static final String ACK_REACTION = "telegram.ackReaction";
    public static final String NOTIFIER_POLICY = "telegram.notifier.policy";
    public static final String NOTIFIER_COOLDOWN_MS = "telegram.notifier.cooldownMs";
    public static final String COALESCE_THRESHOLD = "telegram.inbound.coalesce-threshold";
    public static final String COALESCE_WINDOW_MS = "telegram.inbound.coalesce-window-ms";
    public static final String FORWARD_COALESCE_WINDOW_MS = "telegram.inbound.forward-coalesce-window-ms";
    public static final String MENTION_PATTERNS = "telegram.mentionPatterns";
    public static final String KEYBOARD_SCOPE = "telegram.keyboardScope";
    /** One true/false toggle per message-tool action: {@code telegram.actions.reply}, {@code .pin}, … */
    public static final String ACTIONS_PREFIX = "telegram.actions.";

    /** Wake-word patterns are separated by newlines or commas. */
    static final String MENTION_PATTERN_SEPARATOR = "[\\n,]";

    private TelegramSettings() {}

    /** A message naming what {@code value} must be, or null when {@code key} accepts it. */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        var v = value == null ? "" : value.trim();
        if (key.startsWith(ACTIONS_PREFIX)) {
            return oneOf(key, v, "true", "false");
        }
        return switch (key) {
            case REPLY_TO_MODE -> oneOf(key, v, "off", "first", "all");
            case LINK_PREVIEW, ACK_REACTION -> oneOf(key, v, "on", "off");
            case REACTIONS_NOTIFY -> oneOf(key, v, "off", "own", "all");
            case NOTIFIER_POLICY -> oneOf(key, v, "reply", "silent");
            case KEYBOARD_SCOPE -> oneOf(key, v, "off", "dm", "group", "all");
            case NOTIFIER_COOLDOWN_MS, COALESCE_THRESHOLD -> wholeNumber(key, v, 1);
            case COALESCE_WINDOW_MS, FORWARD_COALESCE_WINDOW_MS -> wholeNumber(key, v, 0);
            // The parser skips a bad pattern with a warning, which would otherwise be the only trace.
            case MENTION_PATTERNS -> invalidWakeWord(key, v);
            default -> null;
        };
    }

    private static @Nullable String oneOf(String key, String v, String... allowed) {
        for (var a : allowed) {
            if (a.equalsIgnoreCase(v)) {
                return null;
            }
        }
        return "%s must be one of %s.".formatted(key, String.join(", ", allowed));
    }

    private static @Nullable String wholeNumber(String key, String v, long min) {
        try {
            if (Long.parseLong(v) >= min) {
                return null;
            }
        } catch (NumberFormatException _) {
            // rejected below
        }
        return "%s must be a whole number of at least %d.".formatted(key, min);
    }

    private static @Nullable String invalidWakeWord(String key, String v) {
        for (var token : v.split(MENTION_PATTERN_SEPARATOR)) {
            var pattern = token.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                return "%s: '%s' is not a valid regular expression (%s).".formatted(key, pattern, e.getDescription());
            }
        }
        return null;
    }
}
