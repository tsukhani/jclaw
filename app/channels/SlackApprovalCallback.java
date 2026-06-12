package channels;

import java.util.Optional;

/**
 * Encode and parse the {@code action_id} of approve/deny Block Kit buttons for the
 * Slack exec-approval workflow (JCLAW-350) — the Slack analog of
 * {@link TelegramApprovalCallback}.
 *
 * <p>Slack returns the clicked button's {@code action_id} in the {@code block_actions}
 * interactivity payload, so the decision + pending-approval id ride there directly
 * (no separate {@code value} field needed, and a distinct {@code action_id} per
 * button avoids the duplicate-id constraint within an actions block). Every approval
 * action_id starts with {@code sa:} so {@code WebhookSlackController.interactive} can
 * route on the prefix.
 *
 * <h2>Schema</h2>
 * <ul>
 *   <li>{@code sa:o:ID} — APPROVE_ONCE</li>
 *   <li>{@code sa:s:ID} — APPROVE_SESSION (remember for the session)</li>
 *   <li>{@code sa:a:ID} — APPROVE_ALWAYS (remember permanently)</li>
 *   <li>{@code sa:d:ID} — DENY</li>
 * </ul>
 * The id resolves against {@link SlackApprovalService}'s pending registry; a stale
 * id is caught at resolve time.
 */
public final class SlackApprovalCallback {

    private SlackApprovalCallback() {}

    public enum Decision { APPROVE_ONCE, APPROVE_SESSION, APPROVE_ALWAYS, DENY }

    public record Payload(Decision decision, String approvalId) {}

    /** Prefix namespace: every JCLAW-350 approval action_id starts with {@code sa:}. */
    public static final String PREFIX = "sa:";

    public static String encodeApproveOnce(String approvalId) {
        return "sa:o:" + approvalId;
    }

    public static String encodeApproveSession(String approvalId) {
        return "sa:s:" + approvalId;
    }

    public static String encodeApproveAlways(String approvalId) {
        return "sa:a:" + approvalId;
    }

    public static String encodeDeny(String approvalId) {
        return "sa:d:" + approvalId;
    }

    /**
     * Parse an action_id. Returns empty when it doesn't start with {@code sa:} or
     * the shape doesn't match a known decision. Never throws — an unrecognized
     * action_id surfaces to the dispatcher as a no-op.
     */
    public static Optional<Payload> parse(String actionId) {
        if (actionId == null || !actionId.startsWith(PREFIX)) return Optional.empty();
        // "sa:" (3) + tag (1) + ":" (1) = 5-char header before the id.
        if (actionId.length() < 6 || actionId.charAt(4) != ':') return Optional.empty();
        var tag = actionId.charAt(3);
        var id = actionId.substring(5);
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
