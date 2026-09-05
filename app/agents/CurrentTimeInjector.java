package agents;

import llm.LlmTypes.ChatMessage;
import services.TimezoneResolver;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the "current date and time" block and splices it into the outgoing
 * message list at send time.
 *
 * <p>The block used to live in the system prompt, below
 * {@link SystemPromptAssembler}'s cache boundary. That boundary is real for the
 * system message itself, but the system message is element 0 of the chat array —
 * so a value that changes every minute still sat ahead of the entire
 * conversation history in the token stream, and an LLM prefix cache had to
 * re-process all of that history whenever the minute ticked over.
 *
 * <p>Verified on prompts captured off the shipped path. With the clock in the
 * system message the cacheable prefix is pinned at a constant length — it can
 * never extend past the clock's position — so the re-processed region grows
 * every turn (247, 300, 351, 405 chars over four turns). Flat versus linear is
 * the verified property; the absolute numbers are small only because those
 * captured turns carried very short messages.
 *
 * <h2>JCLAW-900: why it is now its own message</h2>
 * <p>Merging the block into the last user message fixed that for engines which
 * reuse the longest common token prefix INCREMENTALLY — llama.cpp and MLX, the
 * two this was measured against. It does not transfer to a provider that caches
 * at explicit breakpoints. Anthropic via OpenRouter re-reads only up to a
 * breakpoint, and merging mutated a message that ships as history WITHOUT the
 * block one turn later, so the prefix broke at the FIRST history message every
 * turn. Measured on the shipped path: {@code cached} froze at the system-message
 * size while the re-written tail grew 500, 595, 689 tokens across three turns,
 * each re-write paying the 1.25x cache-write premium. Cost per turn climbed
 * $0.001657, $0.002291, $0.002404, $0.002542 against $0.001657 for the one turn
 * that hit cache fully — a 10x saving forfeited from turn three onward.
 *
 * <p>As its own trailing message the block is merged into nothing, so every
 * history message is byte-stable. Both engine families win: incremental engines
 * still diverge only at the tail (constant re-processing, the property measured
 * above), and breakpoint engines get a prefix that grows with the conversation.
 * Confirmed live against anthropic/claude-haiku-4.5 — turn two read 5,110 cached
 * tokens and wrote 13, where the merged form read 0 beyond the system block.
 *
 * <p>A synthetic harness against a local 7B put the same effect in milliseconds
 * (+334 ms/turn with the clock in the system message versus +12 ms/turn on the
 * last user message, 5 reps, per-turn medians). Treat that as illustrative
 * rather than a promise: it used simulated prompts on a contended machine, and
 * the realized win depends on message sizes and how often turns cross a minute
 * boundary. Both llama.cpp and MLX were separately confirmed to reuse a stable
 * prefix incrementally, which is the engine behavior this relies on.
 *
 * <h2>Call-site contract</h2>
 * <p>Every path that finalizes a message list for an LLM call MUST end with
 * {@link #inject}, or that path's model sees no clock at all. There are three,
 * and it has to be the <em>last</em> step in each — compaction, context trim,
 * and the media rewrites all rebuild the list and would drop an earlier splice:
 * <ul>
 *   <li>{@code AgentPromptPreparer.applyMediaRewrite} — streaming chat</li>
 *   <li>{@code AgentPromptPreparer.rewriteSyncMedia} — synchronous chat</li>
 *   <li>{@code AgentRunner} task-fire, which builds its own two-message list</li>
 * </ul>
 *
 * <p>The block is deliberately <b>not</b> persisted. The stored user message
 * keeps its bare text, so the prefix diverges only at the previous user turn —
 * roughly three messages of re-processing, constant regardless of depth.
 * Persisting it instead would pin the prefix one message later (cheaper still:
 * ~582 ms by turn 6) but leave one stale clock per turn in the transcript,
 * costing ~80 tokens/turn of permanent context and leaving the model to read a
 * pile of contradictory "Now:" statements that all claim to be current.
 */
public final class CurrentTimeInjector {

    private CurrentTimeInjector() {}

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm (xxx)");

    /** Section heading, shared with the tests that assert placement. */
    public static final String HEADING = "## Current Date and Time";

    /**
     * The live clock block in the operator's configured zone
     * ({@link TimezoneResolver#appZone()}), captured fresh on every call so the
     * model always sees real wall-clock time instead of guessing from its
     * training cutoff.
     */
    public static String block() {
        var zone = TimezoneResolver.appZone();
        var now = ZonedDateTime.now(zone);
        return "\n" + HEADING + "\n"
                + "- Now: %s\n".formatted(now.format(FORMAT))
                + "- Timezone: %s\n".formatted(zone.getId())
                + "- This is the live wall-clock time captured when this prompt was built. "
                + "Treat it as the current date and time; do not guess or rely on "
                + "training-cutoff assumptions.\n";
    }

    /**
     * Return a copy of {@code messages} with the clock appended as its own
     * trailing message. Returns the input unchanged for an empty list.
     *
     * <p>JCLAW-900: this used to merge the block into the last user message, which
     * broke a breakpoint-caching provider's prefix every turn (measurements in the
     * class Javadoc).
     *
     * <p>As a separate message the clock is never merged into anything, so every
     * history message is byte-stable and the cacheable prefix grows with the
     * conversation. The provider anchors its cache breakpoint to the last message
     * that is NOT this one (see {@code OpenRouterProvider}), leaving the volatile
     * block outside the cached region — fresh every turn, at a cost of ~70
     * uncached tokens.
     *
     * <p>Appended unconditionally at the end rather than searched-for, because
     * {@code inject} runs once per turn before the tool loop. On a tool round the
     * loop appends the assistant and tool messages after this block, which keeps
     * the clock present for the whole turn without re-injecting it per round.
     */
    public static List<ChatMessage> inject(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        var out = new ArrayList<>(messages);
        out.add(ChatMessage.user(block()));
        return out;
    }

    /**
     * True when {@code content} is the clock block this class appends — the test
     * a provider uses to avoid anchoring its cache breakpoint to a value that
     * changes every turn. Matches on {@link #HEADING} because that is the one
     * part of the block guaranteed constant; everything else is the timestamp.
     */
    public static boolean isClockBlock(Object content) {
        return content instanceof String s && s.contains(HEADING);
    }

}
