package agents;

import llm.LlmTypes.ChatMessage;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
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
    static final int DEFAULT_MIN_CHARS = 4_000;
    static final int DEFAULT_PROTECT_RECENT = 12;
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
                ConfigService.getInt(PROTECT_RECENT_KEY, DEFAULT_PROTECT_RECENT), agent, conversation);
    }

    /**
     * Pure core (test seam). Eligible: TOOL-role messages before the last user message and
     * outside the last {@code protectRecent} messages, with a String body of at least
     * {@code minChars} that is not already a stub. That includes {@code ccr_retrieve}'s own
     * output: exempt, a fetched copy stayed in every later call of the conversation. A result whose tool
     * declares it a standing instruction ({@link ToolRegistry.Tool#keepsResultInContext}, a skill's text)
     * follows {@link #skillDecisions} instead.
     */
    public static List<ChatMessage> prune(List<ChatMessage> messages, int minChars, int protectRecent,
                                          @Nullable Agent agent, @Nullable Conversation conversation) {
        int cutoff = Math.clamp((long) messages.size() - protectRecent, 0, lastUserIndex(messages));
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
            if (MessageRole.USER.value.equals(messages.get(i).role())) return i;
        }
        return messages.size();
    }

    static String stub(@Nullable String toolName, String body) {
        return STUB_MARKER + ": " + (toolName != null ? toolName : "a tool") + " returned " + body.length()
                + " chars earlier in this conversation; call ccr_retrieve(\"" + ContentHash.handle(body)
                + "\") if you need the full text again]";
    }
}
