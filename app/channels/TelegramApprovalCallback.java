package channels;

import java.util.Optional;

/**
 * Encode and parse {@code callback_data} payloads for the generic
 * approve/deny inline-keyboard workflow (JCLAW-373).
 *
 * <p>Separate namespace from the {@code /model} selector
 * ({@link TelegramModelCallback}, prefix {@code m:}): every approval
 * callback starts with {@code a:}. Keeping the two schemas disjoint lets
 * {@link TelegramCallbackDispatcher} route on the prefix without ambiguity.
 *
 * <p>Telegram caps {@code callback_data} at 64 UTF-8 bytes. The approval
 * id is an opaque short token (see {@link TelegramApprovalService}); a
 * realistic id of ~24 chars plus the {@code "a:o:"} tag stays well under
 * the budget.
 *
 * <h2>Schema</h2>
 * <ul>
 *   <li>{@code a:o:ID} — APPROVE_ONCE: approve this single request</li>
 *   <li>{@code a:s:ID} — APPROVE_SESSION: approve and remember for the session</li>
 *   <li>{@code a:a:ID} — APPROVE_ALWAYS: approve and remember permanently</li>
 *   <li>{@code a:d:ID} — DENY: reject this request</li>
 * </ul>
 *
 * <p>The id resolves against the server-side pending-approval registry in
 * {@link TelegramApprovalService}. A stale id (request already resolved,
 * timed out, or from a previous deploy) is caught at resolve time and
 * surfaced to the user via {@code answerCallbackQuery} with an alert.
 */
public final class TelegramApprovalCallback {

    private TelegramApprovalCallback() {}

    /**
     * Decision encoded in the payload. The three approve variants differ
     * only in the scope the caller records ({@code once} grants nothing
     * beyond this request; {@code session} / {@code always} let the
     * requesting tool widen its own policy).
     */
    public enum Decision { APPROVE_ONCE, APPROVE_SESSION, APPROVE_ALWAYS, DENY }

    /**
     * Parsed approval callback payload.
     *
     * @param decision   approve (once / session / always) or deny
     * @param approvalId opaque id of the pending approval this tap resolves
     */
    public record Payload(Decision decision, String approvalId) {}

    /** Prefix namespace: every JCLAW-373 approval callback starts with {@code a:}. */
    public static final String PREFIX = "a:";

    public static String encodeApproveOnce(String approvalId) {
        return "a:o:" + approvalId;
    }

    public static String encodeApproveSession(String approvalId) {
        return "a:s:" + approvalId;
    }

    public static String encodeApproveAlways(String approvalId) {
        return "a:a:" + approvalId;
    }

    public static String encodeDeny(String approvalId) {
        return "a:d:" + approvalId;
    }

    /**
     * Parse a callback_data string. Returns empty if the payload doesn't
     * start with the {@code a:} prefix or the field shape doesn't match
     * any known decision. Malformed payloads never throw — they surface to
     * the dispatcher as "unrecognized callback," which answers with a
     * generic alert.
     *
     * <p>The approval id is taken verbatim from everything after the tag,
     * so an id that happens to contain a {@code :} still round-trips.
     */
    public static Optional<Payload> parse(String data) {
        if (data == null || !data.startsWith(PREFIX)) return Optional.empty();
        // "a:" (2) + tag (1) + ":" (1) = 4-char header before the id.
        if (data.length() < 5) return Optional.empty();
        if (data.charAt(3) != ':') return Optional.empty();
        var tag = data.charAt(2);
        var id = data.substring(4);
        if (id.isEmpty()) return Optional.empty();
        return switch (tag) {
            case 'o' -> Optional.of(new Payload(Decision.APPROVE_ONCE, id));
            case 's' -> Optional.of(new Payload(Decision.APPROVE_SESSION, id));
            case 'a' -> Optional.of(new Payload(Decision.APPROVE_ALWAYS, id));
            case 'd' -> Optional.of(new Payload(Decision.DENY, id));
            default -> Optional.empty();
        };
    }
}
