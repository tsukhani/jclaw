package channels;

import org.jspecify.annotations.Nullable;
import slash.Commands;

/**
 * The message an operator replies to, carried into the user turn the agent sees (JCLAW-1295).
 * A task result or reminder is sent to the channel without entering the conversation, so a reply
 * to one would otherwise reach the agent missing the text it answers.
 */
public final class QuotedReply {

    /** About 4,000 tokens: one quoted task result must not force a compaction by itself. */
    static final int MAX_CHARS = 16_000;

    private QuotedReply() {}

    /**
     * The quoted block: a bracketed line naming the source, then {@code quoted} as a Markdown
     * blockquote, keeping its first {@value #MAX_CHARS} characters and saying so when cut.
     *
     * @param source what was replied to, completing "Replying to …", e.g. "a reminder this bot sent"
     */
    public static String block(String source, String quoted, boolean partial) {
        var body = quoted.strip();
        if (body.length() > MAX_CHARS) {
            int cut = Character.isHighSurrogate(body.charAt(MAX_CHARS - 1)) ? MAX_CHARS - 1 : MAX_CHARS;
            body = body.substring(0, cut) + "\n[truncated: the original is %d characters]".formatted(body.length());
        }
        var sb = new StringBuilder("[").append(partial ? "Quoting part of " : "Replying to ").append(source).append(']');
        body.lines().forEach(line -> sb.append("\n>").append(line.isEmpty() ? "" : " " + line));
        return sb.toString();
    }

    /**
     * {@code text} with {@code block} ahead of it, so the operator's words come last. A slash
     * command is returned unchanged: the dispatcher matches commands on the turn's first token.
     */
    public static String fold(String text, @Nullable String block) {
        if (block == null || Commands.parse(text).isPresent() || Commands.isStart(text)) return text;
        return text.isEmpty() ? block : block + "\n\n" + text;
    }
}
