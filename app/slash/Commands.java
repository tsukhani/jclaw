package slash;

import models.Agent;
import models.Conversation;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

import java.time.Instant;
import java.util.Optional;

/**
 * Chat slash-command registry and dispatcher (JCLAW-26).
 *
 * <p>Commands are parsed at the channel entry points (web SSE, web sync,
 * Telegram streaming, generic channel) BEFORE the LLM round fires. A
 * recognized command short-circuits the turn: the caller persists a canned
 * assistant response, optionally mutates conversation state, and returns
 * without invoking the model. Unknown slash-prefixed input falls through
 * as normal user text — no hard rejection.
 *
 * <p>The "do nothing if not mine" contract is important: slash literals
 * like {@code /start} (Telegram bot-open auto-message) MUST pass through
 * to the LLM unless a future ticket handles them here explicitly.
 *
 * <h2>Persistence model</h2>
 * The user's slash text is <b>not</b> persisted as a message — it's a
 * control signal, not conversation content. The bot's canned response IS
 * persisted as an assistant message in the target conversation so history
 * reload shows the acknowledgement.
 *
 * <h2>/reset ordering</h2>
 * {@link Conversation#contextSince} is written <i>after</i> the assistant
 * response is appended, so the acknowledgement stays out of the next LLM
 * context window (its {@code createdAt} will be less than the watermark).
 */
public final class Commands {

    private Commands() {}

    /** Recognized commands. Unknown slash-prefixed input returns empty Optional from {@link #parse}. */
    public enum Command {
        NEW("/new"),
        RESET("/reset"),
        HELP("/help");

        public final String literal;
        Command(String literal) { this.literal = literal; }
    }

    /** Canned response text for {@link Command#HELP}. */
    public static final String HELP_TEXT = """
            Available commands:
            • /new — start a fresh conversation (creates a new thread)
            • /reset — clear the LLM's memory for this conversation (keeps the thread)
            • /help — show this message""";

    /**
     * Canned response for {@link Command#NEW}. The leading {@code >} line
     * becomes an HTML {@code <blockquote>} via {@code TelegramMarkdownFormatter},
     * which Telegram renders with a colored vertical bar on the left edge —
     * a native, unmistakable session-boundary marker. Web chat renders the
     * same markdown with its own blockquote styling, so the visual cue
     * works across both channels without channel-specific content.
     */
    public static final String NEW_TEXT = """
            > New Conversation

            What would you like to discuss?""";

    /** Canned response for {@link Command#RESET}. See {@link #NEW_TEXT} for the blockquote rationale. */
    public static final String RESET_TEXT = """
            > Context Cleared

            I no longer remember our earlier exchange in this conversation.""";

    /** Outcome of handling a slash command. */
    public record Result(Conversation conversation, String responseText, Command command) {}

    /**
     * Parse the first token of {@code text} as a recognized command.
     * Case-insensitive. Leading/trailing whitespace ignored. Anything
     * after the command (e.g. {@code "/help foo bar"}) is discarded —
     * commands don't currently take arguments.
     */
    public static Optional<Command> parse(String text) {
        if (text == null) return Optional.empty();
        var trimmed = text.strip();
        if (!trimmed.startsWith("/")) return Optional.empty();
        var firstSpace = indexOfWhitespace(trimmed);
        var head = firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
        for (var cmd : Command.values()) {
            if (cmd.literal.equalsIgnoreCase(head)) return Optional.of(cmd);
        }
        return Optional.empty();
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /**
     * Convenience: {@link #parse} + {@link #execute}. Returns empty when
     * the text is not a recognized slash command (including unknown
     * slash-prefixed input) — caller should proceed with the normal LLM
     * flow in that case.
     *
     * @param current may be null only for channels that haven't resolved a
     *                conversation yet; {@code /reset} and {@code /help}
     *                will be treated as no-ops in that case.
     */
    public static Optional<Result> handle(String text, Agent agent, String channelType,
                                           String peerId, Conversation current) {
        return parse(text).map(cmd -> execute(cmd, agent, channelType, peerId, current));
    }

    /** Execute a previously-parsed command. See class javadoc for side effects. */
    public static Result execute(Command cmd, Agent agent, String channelType,
                                  String peerId, Conversation current) {
        return switch (cmd) {
            case NEW -> executeNew(agent, channelType, peerId);
            case RESET -> executeReset(agent, channelType, current);
            case HELP -> executeHelp(agent, channelType, current);
        };
    }

    private static Result executeNew(Agent agent, String channelType, String peerId) {
        var newConv = Tx.run(() -> {
            var conv = ConversationService.create(agent, channelType, peerId);
            ConversationService.appendAssistantMessage(conv, NEW_TEXT, null);
            return conv;
        });
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/new → new conversation %d for peer=%s".formatted(newConv.id, peerId));
        return new Result(newConv, NEW_TEXT, Command.NEW);
    }

    private static Result executeReset(Agent agent, String channelType, Conversation current) {
        if (current == null) {
            // Nothing to reset. Treat as help-like: tell the user we can't
            // reset because there's no current conversation.
            var fallback = "No active conversation to reset.";
            EventLogger.warn("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                    "/reset with no current conversation");
            return new Result(null, fallback, Command.RESET);
        }
        final Long convId = current.id;
        var updated = Tx.run(() -> {
            var conv = (Conversation) Conversation.findById(convId);
            if (conv == null) return null;
            // Persist the acknowledgement first and use its createdAt as the
            // contextSince watermark. Combined with the strict ">" filter in
            // Message.findRecent, this leaves the ack visible in the UI but
            // excludes it (and everything older) from the next LLM context,
            // without relying on Instant.now() having sub-ms precision
            // between two consecutive calls.
            var ack = ConversationService.appendAssistantMessage(conv, RESET_TEXT, null);
            conv.contextSince = ack.createdAt;
            conv.save();
            return conv;
        });
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/reset for conversation %d".formatted(convId));
        return new Result(updated != null ? updated : current, RESET_TEXT, Command.RESET);
    }

    private static Result executeHelp(Agent agent, String channelType, Conversation current) {
        if (current != null) {
            final Long convId = current.id;
            Tx.run(() -> {
                var conv = (Conversation) Conversation.findById(convId);
                if (conv != null) {
                    ConversationService.appendAssistantMessage(conv, HELP_TEXT, null);
                }
            });
        }
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/help" + (current != null ? " for conversation " + current.id : ""));
        return new Result(current, HELP_TEXT, Command.HELP);
    }
}
