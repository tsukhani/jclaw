package services;

import java.util.Set;

/**
 * JCLAW-417: the typed grammar for a {@link models.Task#delivery} string — the
 * single source of truth for <em>where a task's output goes</em>. Parsing is
 * purely syntactic and never throws; whether a channel is configured or a tool
 * is registered is a separate, caller-scoped concern (channels are
 * dispatcher-scoped, tool resolution is agent-scoped).
 *
 * <h2>Grammar</h2>
 * <table border="1">
 *   <tr><th>{@code delivery} value</th><th>kind</th><th>who delivers</th></tr>
 *   <tr><td>{@code null} / blank / {@code "none"}</td><td>{@link Kind#NONE}</td>
 *       <td>nobody — output stays in the run row</td></tr>
 *   <tr><td>{@code "tool:<name>"} (e.g. {@code tool:send_gmail_message})</td>
 *       <td>{@link Kind#TOOL}</td><td>the agent, inline via that tool</td></tr>
 *   <tr><td>{@code "<channel>:<target>"} (e.g. {@code telegram:878224171})</td>
 *       <td>{@link Kind#CHANNEL}</td><td>{@link DeliveryDispatcher} routes the run summary</td></tr>
 *   <tr><td>{@code "<channel>"} (bare, no colon)</td><td>{@link Kind#CHANNEL}</td>
 *       <td>dispatcher, target auto-fills from the calling chat</td></tr>
 * </table>
 *
 * <p>The {@link Kind#TOOL} kind is the JCLAW-416 addition: it gives the
 * previously-undeclarable "agent self-delivers via a tool" case a typed slot,
 * so a {@code null} delivery is no longer ambiguous between "no output" and
 * "delivered in-run".
 */
public record DeliverySpec(Kind kind, String channel, String target, String tool, String raw) {

    public enum Kind { CHANNEL, TOOL, NONE }

    private static final String TOOL_PREFIX = "tool:";
    private static final String NONE_LITERAL = "none";

    /**
     * Channels {@link DeliveryDispatcher} can push to. {@code web} is included
     * because it is a valid auto-fill target (delivers back to the calling
     * chat), even though it is not a push destination. {@code email} is
     * deliberately absent: there is no email dispatcher — email delivery goes
     * through a {@link Kind#TOOL} (e.g. {@code tool:send_gmail_message}).
     */
    public static final Set<String> DISPATCH_CHANNELS = Set.of("telegram", "slack", "whatsapp", "web");

    public static DeliverySpec none(String raw) {
        return new DeliverySpec(Kind.NONE, null, null, null, raw);
    }

    /**
     * Parse a {@code delivery} string into its kind and parts. Syntactic only —
     * never throws, never validates support/resolvability. {@code null}, blank,
     * and the literal {@code "none"} all collapse to {@link Kind#NONE}.
     */
    public static DeliverySpec parse(String raw) {
        if (raw == null) return none(null);
        var s = raw.trim();
        if (s.isEmpty() || s.equalsIgnoreCase(NONE_LITERAL)) return none(raw);

        if (s.regionMatches(true, 0, TOOL_PREFIX, 0, TOOL_PREFIX.length())) {
            var tool = s.substring(TOOL_PREFIX.length()).trim();
            return new DeliverySpec(Kind.TOOL, null, null, tool, raw);
        }

        int colon = s.indexOf(':');
        if (colon < 0) {
            // Bare channel name — target auto-fills from the calling chat.
            return new DeliverySpec(Kind.CHANNEL, s.toLowerCase(), "", null, raw);
        }
        var channel = s.substring(0, colon).trim().toLowerCase();
        var target = s.substring(colon + 1).trim();
        return new DeliverySpec(Kind.CHANNEL, channel, target, null, raw);
    }

    public boolean isNone() {
        return kind == Kind.NONE;
    }

    /** True when this is a dispatcher-routed channel JClaw can actually push to. */
    public boolean isDispatchChannel() {
        return kind == Kind.CHANNEL && channel != null && DISPATCH_CHANNELS.contains(channel);
    }

    /**
     * Short, human-facing label for the Tasks UI's "Channel" column:
     * the channel name, the tool name, or {@code "none"}.
     */
    public String label() {
        return switch (kind) {
            case CHANNEL -> channel;
            case TOOL -> tool;
            case NONE -> NONE_LITERAL;
        };
    }

    /**
     * Structural validation for the write boundary (REST controller, TaskTool).
     * Returns an error message, or {@code null} when the string is well-formed.
     *
     * <p>Intentionally <em>syntactic</em>: it does not check whether a channel
     * is configured or a tool is registered — those are caller-scoped (channels
     * via {@link DeliveryDispatcher}, tools via the agent's registry). A
     * {@link Kind#CHANNEL} naming an unknown channel is reported here because
     * the dispatcher set is fixed and small; an unknown channel is almost
     * always a typo for {@code tool:}.
     */
    public static String validate(String raw) {
        var spec = parse(raw);
        return switch (spec.kind()) {
            case NONE -> null;
            case TOOL -> spec.tool().isBlank()
                    ? "Delivery 'tool:' requires a tool name, e.g. 'tool:send_gmail_message'."
                    : null;
            case CHANNEL -> {
                if (spec.channel().isBlank()) {
                    yield "Delivery is empty; use '<channel>:<target>', 'tool:<name>', or 'none'.";
                }
                if (!DISPATCH_CHANNELS.contains(spec.channel())) {
                    yield "Unknown delivery channel '" + spec.channel() + "'. Use one of "
                            + DISPATCH_CHANNELS + ", or 'tool:<name>' for in-run tool delivery"
                            + " (e.g. email via 'tool:send_gmail_message').";
                }
                yield null;
            }
        };
    }
}
