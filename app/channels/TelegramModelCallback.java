package channels;

import java.util.Optional;

/**
 * Encode and parse {@code callback_data} payloads for the {@code /model}
 * inline keyboard selector (JCLAW-109).
 *
 * <p>Telegram caps {@code callback_data} at 64 UTF-8 bytes. An indexed
 * schema keeps every variant well under that budget even at the extremes
 * (eight-digit conversation id plus two-digit provider and model indices
 * plus a three-digit page ≈ 20 bytes total).
 *
 * <h2>Schema</h2>
 * <ul>
 *   <li>{@code m:b:C} — BROWSE: open the providers list for conversation C</li>
 *   <li>{@code m:p:C:P:PAGE} — PROVIDER_PAGE: show models for provider index P on PAGE</li>
 *   <li>{@code m:s:C:P:M} — SELECT: write model at (P, M) as the override</li>
 *   <li>{@code m:k:C} — BACK: return to the summary message</li>
 *   <li>{@code m:d:C} — DETAILS: render full model metadata (JCLAW-107 text)</li>
 * </ul>
 *
 * <p>Indices reference the server-side {@code ProviderRegistry} snapshot at
 * render time. The handler re-resolves indices to provider / model names
 * before writing; stale indices (registry refreshed since render) are
 * caught via re-validation and surfaced to the user via
 * {@code answerCallbackQuery} with {@code show_alert}.
 */
public final class TelegramModelCallback {

    private TelegramModelCallback() {}

    /** Kind of callback encoded in the payload. */
    public enum Kind { BROWSE, PROVIDER_PAGE, SELECT, BACK, DETAILS }

    /** Parsed callback payload. Fields not relevant to the kind are -1 / 0. */
    public record Payload(Kind kind, long conversationId, int providerIdx, int modelIdx, int page) {}

    /** Prefix namespace: every JCLAW-109 callback starts with {@code m:}. */
    public static final String PREFIX = "m:";

    public static String encodeBrowse(long conversationId) {
        return "m:b:" + conversationId;
    }

    public static String encodeProviderPage(long conversationId, int providerIdx, int page) {
        return "m:p:" + conversationId + ":" + providerIdx + ":" + page;
    }

    public static String encodeSelect(long conversationId, int providerIdx, int modelIdx) {
        return "m:s:" + conversationId + ":" + providerIdx + ":" + modelIdx;
    }

    public static String encodeBack(long conversationId) {
        return "m:k:" + conversationId;
    }

    public static String encodeDetails(long conversationId) {
        return "m:d:" + conversationId;
    }

    /**
     * Parse a callback_data string. Returns empty if the payload doesn't
     * start with the {@code m:} prefix or the field shape doesn't match
     * any known kind. Malformed payloads never throw — they surface to the
     * dispatcher as "unrecognized callback," which answers with a generic
     * alert.
     */
    public static Optional<Payload> parse(String data) {
        if (data == null || !data.startsWith(PREFIX)) return Optional.empty();
        var parts = data.split(":");
        if (parts.length < 3) return Optional.empty();
        try {
            var kindTag = parts[1];
            long convId = Long.parseLong(parts[2]);
            return switch (kindTag) {
                case "b" -> parts.length == 3
                        ? Optional.of(new Payload(Kind.BROWSE, convId, -1, -1, 0))
                        : Optional.empty();
                case "k" -> parts.length == 3
                        ? Optional.of(new Payload(Kind.BACK, convId, -1, -1, 0))
                        : Optional.empty();
                case "d" -> parts.length == 3
                        ? Optional.of(new Payload(Kind.DETAILS, convId, -1, -1, 0))
                        : Optional.empty();
                case "p" -> parts.length == 5
                        ? Optional.of(new Payload(Kind.PROVIDER_PAGE, convId,
                                Integer.parseInt(parts[3]), -1, Integer.parseInt(parts[4])))
                        : Optional.empty();
                case "s" -> parts.length == 5
                        ? Optional.of(new Payload(Kind.SELECT, convId,
                                Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), 0))
                        : Optional.empty();
                default -> Optional.empty();
            };
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
    }
}
