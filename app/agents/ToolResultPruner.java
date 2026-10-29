package agents;

import llm.LlmTypes.ChatMessage;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.compression.ContentHash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replaces large tool results from earlier turns with a one-line stub carrying their
 * {@code ccr_retrieve} handle (JCLAW-1202). Independent of window fill: the context-window
 * trim only truncates when the prompt overflows, which a 1M-window model never does, so an
 * early round's 70k-char file read otherwise rides along in every later call of the
 * conversation. The current turn and the newest messages are never touched, and stored
 * history is untouched — the stub points at the durable row.
 */
public final class ToolResultPruner {
    private ToolResultPruner() {}

    static final String ENABLED_KEY = "chat.pruneToolResults";
    static final String MIN_CHARS_KEY = "chat.pruneToolResultsMinChars";
    static final String PROTECT_RECENT_KEY = "chat.pruneToolResultsProtectRecent";
    public static final String EVERY_TURNS_KEY = "chat.pruneToolResultsEveryTurns";
    static final int DEFAULT_MIN_CHARS = 4_000;
    static final int DEFAULT_PROTECT_RECENT = 12;
    /** Per turn, as before JF-155; 5 is the recommended step once a provider's cache holds across turns. */
    static final int DEFAULT_EVERY_TURNS = 1;
    /** The most of one skill's text a conversation keeps, as Claude Code keeps its first 5,000 tokens. */
    public static final int SKILL_KEEP_CHARS = 20_000;
    /** The most skill text a conversation keeps in all, newest skill first. */
    public static final int SKILL_BUDGET_CHARS = 100_000;
    /** Prefix of every stub; also what stops a stub from being stubbed again. */
    public static final String STUB_MARKER = "[tool result elided";

    /** Config-driven entry point; returns the same list instance when nothing changed. */
    public static List<ChatMessage> prune(List<ChatMessage> messages, Agent agent, @Nullable Conversation conversation) {
        if (messages == null || messages.isEmpty() || !ConfigService.getBoolean(ENABLED_KEY, true)) return messages;
        return prune(messages, ConfigService.getInt(MIN_CHARS_KEY, DEFAULT_MIN_CHARS),
                ConfigService.getInt(PROTECT_RECENT_KEY, DEFAULT_PROTECT_RECENT),
                ConfigService.getInt(EVERY_TURNS_KEY, DEFAULT_EVERY_TURNS),
                conversation != null ? ConversationService.effectiveHistoryLimit(conversation) : Integer.MAX_VALUE,
                agent, conversation);
    }

    /**
     * Pure core (test seam). Eligible: TOOL-role messages before the last user message and
     * outside the last {@code protectRecent} messages, with a String body of at least
     * {@code minChars} that is not already a stub. That includes {@code ccr_retrieve}'s own
     * output: exempt, a fetched copy stayed in every later call of the conversation. A result whose tool
     * declares it a standing instruction ({@link ToolRegistry.Tool#keepsResultInContext}, a skill's text)
     * follows {@link #skillDecisions} instead. An {@code everyTurns} above 1 holds the boundary still
     * between steps ({@link #stepCutoff}) while the history is shorter than {@code historyLimit}, the rows
     * {@link ConversationService#loadRecentMessages} loads at most; 1 or less, or a history at that cap, moves
     * it on every call.
     */
    public static List<ChatMessage> prune(List<ChatMessage> messages, int minChars, int protectRecent, int everyTurns,
                                          int historyLimit, @Nullable Agent agent, @Nullable Conversation conversation) {
        int turn = turnUserIndex(messages);
        // Position 0 is the system prompt, so the turn's ask at index n is the nth row loaded. A load that hit the
        // cap slides its head every turn, so the cached history changes anyway and holding stubs back saves nothing.
        int cutoff = everyTurns > 1 && turn < historyLimit
                ? stepCutoff(messages, turn, protectRecent, everyTurns)
                : Math.clamp((long) messages.size() - protectRecent, 0, lastUserIndex(messages));
        var skills = skillDecisions(messages, minChars);
        List<ChatMessage> out = null;
        int stubbed = 0;
        long elided = 0;
        for (int i = 0; i < cutoff; i++) {
            var m = messages.get(i);
            if (!MessageRole.TOOL.value.equals(m.role())) continue;
            if (!(m.content() instanceof String body) || body.length() < minChars || body.startsWith(STUB_MARKER)) continue;
            var skill = skills.get(i);
            if (skill == SkillDecision.KEEP) continue;
            var shortened = skill == SkillDecision.CUT ? cut(body) : stub(m.toolName(), body);
            if (out == null) out = new ArrayList<>(messages);
            out.set(i, new ChatMessage(m.role(), shortened, m.toolCalls(), m.toolCallId(), m.toolName()));
            stubbed++;
            elided += body.length() - shortened.length();
        }
        if (out == null) return messages;
        if (agent != null) {
            EventLogger.info("llm", agent.name, conversation != null ? conversation.channelType : null,
                    "Stubbed %d earlier tool result(s), %d chars elided; each carries its ccr_retrieve handle"
                            .formatted(stubbed, elided));
        }
        return out;
    }

    private enum SkillDecision { KEEP, CUT, STUB }

    private record SkillResult(int index, String body) {}

    /**
     * Claude Code's shape for skill text: newest first, each skill keeps its first {@link #SKILL_KEEP_CHARS}
     * within {@link #SKILL_BUDGET_CHARS} for the conversation; an older copy of a skill read again, and a skill
     * past the budget, is stubbed. Every skill result counts toward the budget, the protected recent ones too.
     */
    private static Map<Integer, SkillDecision> skillDecisions(List<ChatMessage> messages, int minChars) {
        var skillResults = skillResults(messages);
        var decisions = new HashMap<Integer, SkillDecision>();
        var seen = new HashSet<String>();
        long budget = SKILL_BUDGET_CHARS;
        for (int k = skillResults.size() - 1; k >= 0; k--) {
            int i = skillResults.get(k).index();
            var body = skillResults.get(k).body();
            if (!seen.add(body)) {
                decisions.put(i, SkillDecision.STUB);
                continue;
            }
            int kept = Math.min(body.length(), SKILL_KEEP_CHARS);
            if (kept > budget && body.length() >= minChars) {
                decisions.put(i, SkillDecision.STUB);
                continue;
            }
            budget -= kept;
            decisions.put(i, body.length() > SKILL_KEEP_CHARS ? SkillDecision.CUT : SkillDecision.KEEP);
        }
        return decisions;
    }

    /** The unstubbed results of calls whose tool keeps its result in context, oldest first. */
    private static List<SkillResult> skillResults(List<ChatMessage> messages) {
        var argsByCallId = new HashMap<String, String>();
        var skillResults = new ArrayList<SkillResult>();
        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            if (m.toolCalls() != null) {
                for (var call : m.toolCalls()) {
                    if (call.id() != null) argsByCallId.put(call.id(), call.function().arguments());
                }
            }
            if (!MessageRole.TOOL.value.equals(m.role()) || !(m.content() instanceof String body)
                    || body.startsWith(STUB_MARKER)) continue;
            var callArgs = m.toolCallId() == null ? null : argsByCallId.get(m.toolCallId());
            if (callArgs != null && keepsInContext(m.toolName(), callArgs)) skillResults.add(new SkillResult(i, body));
        }
        return skillResults;
    }

    /** A skill's first {@link #SKILL_KEEP_CHARS}, never ending between a surrogate pair, and where the rest is. */
    static String cut(String body) {
        int end = Character.isHighSurrogate(body.charAt(SKILL_KEEP_CHARS - 1)) ? SKILL_KEEP_CHARS - 1 : SKILL_KEEP_CHARS;
        return body.substring(0, end) + String.format(Locale.ROOT,
                "\n[skill text cut at %,d of %,d characters; call ccr_retrieve(\"%s\") for the full text]",
                end, body.length(), ContentHash.handle(body));
    }

    private static boolean keepsInContext(@Nullable String toolName, String argsJson) {
        var tool = ToolRegistry.lookupTool(toolName);
        return tool != null && tool.keepsResultInContext(argsJson);
    }

    private static int lastUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isUser(messages.get(i))) return i;
        }
        return messages.size();
    }

    /**
     * Stubbing in steps, so the history a provider cached stays the same between them (JF-155): the newest user
     * message with a multiple of {@code everyTurns} user messages before it, at or before the per-call boundary as
     * it stood when the turn began. The history starts at the conversation's floor ({@code /reset} or compaction),
     * so the count is the conversation's. Counted from the turn's own user message ({@code turn}), never from the
     * rounds or the clock message after it, so the boundary holds still for the whole turn. 0, stubbing nothing,
     * before the first step.
     */
    private static int stepCutoff(List<ChatMessage> messages, int turn, int protectRecent, int everyTurns) {
        int limit = Math.clamp((long) turn + 1 - protectRecent, 0, turn);
        int cutoff = 0;
        int usersBefore = 0;
        for (int i = 0; i <= limit && i < messages.size(); i++) {
            if (!isUser(messages.get(i))) continue;
            if (usersBefore % everyTurns == 0) cutoff = i;
            usersBefore++;
        }
        return cutoff;
    }

    /** The current turn's user message: the last one that is not the clock {@link CurrentTimeInjector} appends. */
    private static int turnUserIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            var m = messages.get(i);
            if (isUser(m) && !(m.content() instanceof String body && CurrentTimeInjector.isClockBlock(body))) return i;
        }
        return messages.size();
    }

    private static boolean isUser(ChatMessage m) {
        return MessageRole.USER.value.equals(m.role());
    }

    static String stub(@Nullable String toolName, String body) {
        return STUB_MARKER + ": " + (toolName != null ? toolName : "a tool") + " returned " + body.length()
                + " chars earlier in this conversation; call ccr_retrieve(\"" + ContentHash.handle(body)
                + "\") if you need the full text again]";
    }
}
