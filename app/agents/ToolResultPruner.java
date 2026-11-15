package agents;

import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.compression.ContentHash;
import utils.Strings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replaces large tool results from earlier turns with a one-line stub carrying their
 * {@code ccr_retrieve} handle (JCLAW-1202), and a large user message from an earlier turn with
 * its head and tail and the same kind of handle (JF-417). Independent of window fill: the
 * context-window trim only truncates when the prompt overflows, which a 1M-window model never
 * does, so an early round's 70k-char file read or a pasted document otherwise rides along in
 * every later call of the conversation. The current turn is never touched, nor the newest
 * messages' tool results, and stored history is untouched — each handle points at the durable row.
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
    /** The share of the model's window a user message may take before later turns read its preview; 0 or less is off. */
    public static final String USER_PREVIEW_PERCENT_KEY = "chat.userMessagePreviewPercent";
    public static final int DEFAULT_USER_PREVIEW_PERCENT = 10;
    public static final int USER_PREVIEW_MIN_TOKENS = 2_000;
    public static final int USER_PREVIEW_UNKNOWN_WINDOW_TOKENS = 8_000;
    static final int CHARS_PER_TOKEN = 4;
    public static final int USER_PREVIEW_HEAD_CHARS = 2_000;
    public static final int USER_PREVIEW_TAIL_CHARS = 1_000;

    /** Config-driven entry point; returns the same list instance when nothing changed. */
    public static List<ChatMessage> prune(List<ChatMessage> messages, Agent agent, @Nullable Conversation conversation) {
        if (messages == null || messages.isEmpty()) return messages;
        var pruned = messages;
        if (ConfigService.getBoolean(ENABLED_KEY, true)) {
            int historyLimit = conversation != null ? ConversationService.effectiveHistoryLimit(conversation) : Integer.MAX_VALUE;
            pruned = prune(messages, ConfigService.getInt(MIN_CHARS_KEY, DEFAULT_MIN_CHARS),
                    ConfigService.getInt(PROTECT_RECENT_KEY, DEFAULT_PROTECT_RECENT),
                    ConfigService.getInt(EVERY_TURNS_KEY, DEFAULT_EVERY_TURNS), historyLimit, agent, conversation);
        }
        return previewEarlierUserMessages(pruned, userPreviewChars(agent, conversation), agent, conversation);
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

    /** Where the current turn's own user messages begin: a channel batch persists one row per message it carries. */
    private static int turnRunStart(List<ChatMessage> messages) {
        int start = turnUserIndex(messages);
        if (start >= messages.size()) return start;
        while (start > 0 && isUser(messages.get(start - 1))) start--;
        return start;
    }

    /**
     * The longest stored message, in characters, a later turn still reads whole: {@link #USER_PREVIEW_PERCENT_KEY}
     * of the window resolved for {@code agent} on {@code conversation}, read at most 100, or 0 when off.
     */
    static int userPreviewChars(Agent agent, @Nullable Conversation conversation) {
        if (ConfigService.getInt(USER_PREVIEW_PERCENT_KEY, DEFAULT_USER_PREVIEW_PERCENT) <= 0) return 0;
        return userPreviewChars(conversation != null ? contextWindow(agent, conversation) : 0);
    }

    /** {@link #USER_PREVIEW_PERCENT_KEY} as {@link #previewThresholdChars} reads it, for a known window. */
    public static int userPreviewChars(int contextWindow) {
        return previewThresholdChars(ConfigService.getInt(USER_PREVIEW_PERCENT_KEY, DEFAULT_USER_PREVIEW_PERCENT),
                contextWindow);
    }

    private static int contextWindow(Agent agent, Conversation conversation) {
        var provider = ProviderRegistry.get(ModelResolver.effectiveModelProvider(agent, conversation));
        if (provider == null) return 0;
        return ModelResolver.resolveModelInfo(agent, conversation, provider).map(ModelInfo::contextWindow).orElse(0);
    }

    /**
     * {@code percent} (at most 100) of {@code contextWindow} tokens, at least {@link #USER_PREVIEW_MIN_TOKENS}, or
     * {@link #USER_PREVIEW_UNKNOWN_WINDOW_TOKENS} for a window of 0 or less, in characters at
     * {@link #CHARS_PER_TOKEN} a token; 0, off, for a {@code percent} of 0 or less.
     */
    public static int previewThresholdChars(int percent, int contextWindow) {
        if (percent <= 0) return 0;
        long tokens = contextWindow > 0
                ? Math.max(USER_PREVIEW_MIN_TOKENS, (long) contextWindow * Math.min(percent, 100) / 100)
                : USER_PREVIEW_UNKNOWN_WINDOW_TOKENS;
        return (int) Math.min(Integer.MAX_VALUE, tokens * CHARS_PER_TOKEN);
    }

    /**
     * Pure core (test seam): every user message before the current turn's own ({@link #turnRunStart}) whose stored
     * text is longer than {@code thresholdChars} becomes its {@link #preview}. A String body is the stored row
     * itself; a text part is the stored row followed by attachment notes, which {@link VisionAudioAssembler} records,
     * so an unrecorded part is left whole rather than given a handle no row hashes to. 0 or less is off; returns the
     * same list instance when nothing changed.
     */
    public static List<ChatMessage> previewEarlierUserMessages(List<ChatMessage> messages, int thresholdChars,
                                                               @Nullable Agent agent,
                                                               @Nullable Conversation conversation) {
        if (thresholdChars <= 0 || messages.isEmpty()) return messages;
        int runStart = turnRunStart(messages);
        List<ChatMessage> out = null;
        int shortened = 0;
        long elided = 0;
        for (int i = 0; i < runStart; i++) {
            var m = messages.get(i);
            if (!isUser(m)) continue;
            var preview = previewOf(m.content(), thresholdChars);
            if (preview == null) continue;
            if (out == null) out = new ArrayList<>(messages);
            out.set(i, new ChatMessage(m.role(), preview.content(), m.toolCalls(), m.toolCallId(), m.toolName()));
            shortened++;
            elided += preview.elided();
        }
        if (out == null) return messages;
        if (agent != null) {
            EventLogger.info("llm", agent.name, conversation != null ? conversation.channelType : null,
                    "Shortened %d earlier user message(s), %d chars elided; each carries its ccr_retrieve handle"
                            .formatted(shortened, elided));
        }
        return out;
    }

    private record Preview(Object content, long elided) {}

    private static @Nullable Preview previewOf(@Nullable Object content, int thresholdChars) {
        if (content instanceof String body) {
            int stored = VisionAudioAssembler.storedTextLength(body);
            var text = preview(body, stored < 0 ? body.length() : stored, thresholdChars);
            return text == null ? null : new Preview(text, (long) body.length() - text.length());
        }
        if (!(content instanceof List<?> parts)) return null;
        List<Object> out = null;
        long elided = 0;
        for (int k = 0; k < parts.size(); k++) {
            if (!(parts.get(k) instanceof Map<?, ?> part) || !"text".equals(part.get("type"))
                    || !(part.get("text") instanceof String body)) continue;
            int stored = VisionAudioAssembler.storedTextLength(body);
            var text = stored < 0 ? null : preview(body, stored, thresholdChars);
            if (text == null) continue;
            if (out == null) out = new ArrayList<>(parts);
            var copy = new LinkedHashMap<Object, Object>(part);
            copy.put("text", text);
            out.set(k, copy);
            elided += body.length() - text.length();
        }
        return out == null ? null : new Preview(out, elided);
    }

    /**
     * {@code body} with its first {@code stored} characters, a stored message's text, cut to the first
     * {@link #USER_PREVIEW_HEAD_CHARS} and last {@link #USER_PREVIEW_TAIL_CHARS} around a line naming the message's
     * {@code ccr_retrieve} handle, never between a surrogate pair; what follows the stored text stays. Null when the
     * stored text is at most {@code thresholdChars}, or too short for a preview to be shorter.
     */
    public static @Nullable String preview(String body, int stored, int thresholdChars) {
        if (stored <= Math.max(thresholdChars, USER_PREVIEW_HEAD_CHARS + USER_PREVIEW_TAIL_CHARS)) return null;
        var handle = ContentHash.handle(stored == body.length() ? body : body.substring(0, stored));
        return Strings.cutMiddle(body, USER_PREVIEW_HEAD_CHARS, stored - USER_PREVIEW_TAIL_CHARS, left ->
                String.format(Locale.ROOT, "\n[message shortened: %,d characters, the middle left out; "
                        + "call ccr_retrieve(\"%s\") for the full text]\n", stored, handle));
    }

    private static boolean isUser(ChatMessage m) {
        return MessageRole.USER.value.equals(m.role());
    }

    static String stub(@Nullable String toolName, String body) {
        return STUB_MARKER + ": " + (toolName != null ? toolName : "a tool") + " returned " + body.length()
                + " chars earlier in this conversation; call ccr_retrieve(\"" + CompressionPipeline.retrievalHandle(body)
                + "\") if you need the full text again]";
    }
}
