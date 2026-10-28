package services;

import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ToolDef;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SessionCompaction;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session compaction: when a conversation's active context approaches the
 * model's input window, roll the oldest non-essential turns into a single
 * LLM-generated summary (JCLAW-38).
 *
 * <p>Storage layout — all metadata lives in the DB (no filesystem
 * coupling, matching JClaw's "framework state stays in the schema"
 * philosophy). Each compaction writes a {@link SessionCompaction} row
 * holding the summary text + bookkeeping, and bumps
 * {@link Conversation#compactionSince} so subsequent context loads skip
 * the now-summarized prefix. The original {@link Message} rows remain
 * intact for sidebar / scrollback; only the LLM-facing view is
 * truncated.
 *
 * <p>Trigger: whichever comes first of {@code estimatedTokens >= chat.compaction.triggerTokens}
 * (default 150,000; 0 or less turns it off), since quality falls well before today's windows fill
 * (JF-154), and {@code estimatedTokens > (contextWindow - reserveTokens)}. The reserve carves out
 * headroom for the completion itself plus a safety margin, following OpenClaw's approach — tuned via
 * {@code chat.compactionReserveTokens} (default 15000, floor 9000).
 *
 * <p>Boundary selection: we anchor cuts at user-message boundaries so
 * we never split an assistant's tool_calls from its tool_result
 * companions. Providers reject contexts with orphaned tool calls.
 *
 * <p>Re-injection: the summary is appended to the system prompt as a
 * "[Prior conversation summary]" section by the caller (not here). That
 * keeps SessionCompactor free of ChatMessage-list manipulation and
 * stays provider-agnostic.
 */
public final class SessionCompactor {

    private SessionCompactor() {}

    /**
     * Functional seam for the summarization call. Production passes a
     * lambda that calls {@code LlmProvider.chat}; tests inject a canned
     * response without needing to mock the sealed provider hierarchy.
     */
    @FunctionalInterface
    public interface Summarizer {
        // Production lambda calls LlmProvider.chat which surfaces provider-specific checked exceptions; broad signature avoids leaking provider exception types into the seam.
        @SuppressWarnings("java:S112")
        @Nullable String summarize(List<ChatMessage> messages) throws Exception;
    }

    /**
     * A turn's request as it goes to the provider: its messages, system prompt first, after pruning and
     * compression, and its tool definitions.
     */
    public record TurnRequest(List<ChatMessage> messages, List<ToolDef> tools) {}

    /** One model call on a message list and tool list; production wraps {@code LlmProvider.chat}. */
    @FunctionalInterface
    public interface ModelCall {
        @SuppressWarnings("java:S112") // as Summarizer.summarize: keeps provider exception types out of the seam
        ChatResponse call(List<ChatMessage> messages, List<ToolDef> tools) throws Exception;
    }

    @FunctionalInterface
    private interface SummaryStep {
        @SuppressWarnings("java:S112")
        @Nullable String summarize(CompactionPlan plan) throws Exception;
    }

    public record CompactionResult(
            boolean compacted,
            int turnsCompacted,
            int summaryChars,
            @Nullable String skipReason
    ) {
        public static CompactionResult skipped(String reason) {
            return new CompactionResult(false, 0, 0, reason);
        }
        public static CompactionResult success(int turns, int chars) {
            return new CompactionResult(true, turns, chars, null);
        }
    }

    // ─── Decision ───────────────────────────────────────────────────────

    /**
     * True when the current message list reaches {@code chat.compaction.triggerTokens} or exceeds the
     * model's window budget. Callers should compute {@code estimatedTokens} with
     * {@code ContextWindowManager.adjustedPromptTokens} so thresholds compare apples to apples.
     */
    public static boolean shouldCompact(int estimatedTokens, @Nullable ModelInfo modelInfo) {
        return shouldCompact(estimatedTokens, modelInfo, ConfigService.getInt("chat.compaction.triggerTokens", 150_000));
    }

    /** {@link #shouldCompact(int, ModelInfo)} with the token trigger given; 0 or less leaves only the window rule. */
    public static boolean shouldCompact(int estimatedTokens, @Nullable ModelInfo modelInfo, int triggerTokens) {
        if (modelInfo == null) return false;
        if (triggerTokens > 0 && estimatedTokens >= triggerTokens) return true;
        if (modelInfo.contextWindow() <= 0) return false;
        int budget = modelInfo.contextWindow() - effectiveReserveTokens();
        return budget > 0 && estimatedTokens > budget;
    }

    /** The reserve carved out of the context window for completion + safety margin. */
    public static int effectiveReserveTokens() {
        int configured = ConfigService.getInt("chat.compactionReserveTokens", 15_000);
        int floor = ConfigService.getInt("chat.compactionReserveTokensFloor", 9_000);
        return Math.max(configured, floor);
    }

    // ─── Execution ──────────────────────────────────────────────────────

    /**
     * Compact the oldest eligible prefix of {@code conversationId} using
     * {@code summarizer} to produce the narrative. Runs in three phases:
     *
     * <ol>
     *   <li>Tx: load eligible messages, pick safe boundary, snapshot
     *       content into a {@link CompactionPlan}.</li>
     *   <li>No Tx: call {@code summarizer} — this is the slow LLM-bound
     *       step and must not hold a DB connection.</li>
     *   <li>Tx: insert {@link SessionCompaction} row and bump
     *       {@link Conversation#compactionSince}.</li>
     * </ol>
     *
     * @param conversationId id of the conversation to compact
     * @param modelLabel {@code "provider/modelId"} identifying the
     *        summarization model, for audit in the SessionCompaction row
     * @param summarizer the LLM call that turns ChatMessages into a summary
     * @return outcome; {@code compacted=false} means skipped or failed —
     *         caller can still fall back to drop-oldest trimming
     */
    public static CompactionResult compact(Long conversationId, String modelLabel, Summarizer summarizer) {
        return compact(conversationId, modelLabel, summarizer, false, null);
    }

    /**
     * Full-knob variant. {@code force=true} bypasses the default too-few-turns
     * guard so a manual {@code /compact} invocation can summarize small
     * conversations the auto-trigger would skip — boundaries still anchor
     * on user messages to keep tool-call cycles intact.
     * {@code additionalInstructions} is appended to the summarization
     * system prompt so the user can steer the summary ("focus on the SQL
     * migration work").
     */
    public static CompactionResult compact(Long conversationId, String modelLabel, Summarizer summarizer,
                                            boolean force, @Nullable String additionalInstructions) {
        return compact(conversationId, modelLabel, force,
                plan -> summarizer.summarize(transcriptRequest(plan, additionalInstructions)));
    }

    /**
     * Automatic compaction on the turn's own prefix (JF-154): the request sends the turn's system prompt,
     * the summarized messages as the turn sent them and its tools, with the instruction last as a user
     * message, so the provider bills that prefix at its cached rate. A reply that calls a tool is retried
     * once on the transcript without tools; no tool is ever run. When the turn's list no longer lines up
     * with the stored rows, the transcript is sent instead.
     *
     * @param turn the request the turn is about to send
     * @param call the model call, made outside any transaction
     */
    public static CompactionResult compact(Long conversationId, String modelLabel, TurnRequest turn, ModelCall call) {
        return compact(conversationId, modelLabel, false, plan -> summarizeOnTurn(conversationId, plan, turn, call));
    }

    private static CompactionResult compact(Long conversationId, String modelLabel, boolean force, SummaryStep step) {
        var planOpt = Tx.run(() -> buildPlan(conversationId, force));
        if (planOpt.isEmpty()) return CompactionResult.skipped("no safe boundary or below min-turns");
        var plan = planOpt.get();

        String summary;
        try {
            summary = step.summarize(plan);
        } catch (Exception e) {
            EventLogger.warn("compaction", null, null,
                    "Compaction LLM call failed for conversation %d: %s"
                            .formatted(conversationId, e.getMessage()));
            return CompactionResult.skipped("llm error");
        }
        if (summary == null || summary.isBlank()) {
            EventLogger.warn("compaction", null, null,
                    "Compaction returned empty summary for conversation %d".formatted(conversationId));
            return CompactionResult.skipped("empty summary");
        }

        final var finalSummary = summary.strip();
        final var summaryTokens = Math.max(1, finalSummary.length() / 4);
        final var turnsCompacted = plan.toSummarize().size();

        Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(conversationId);
            if (conv == null) return; // conversation deleted between phases
            var sc = new SessionCompaction();
            sc.conversation = conv;
            sc.turnCount = turnsCompacted;
            sc.summaryTokens = summaryTokens;
            sc.model = modelLabel != null && !modelLabel.isBlank() ? modelLabel : "unknown";
            sc.summary = finalSummary;
            sc.save();
            conv.compactionSince = plan.firstKept().createdAt();
            conv.save();
        });

        return CompactionResult.success(turnsCompacted, finalSummary.length());
    }

    // ─── Summary → system-prompt injection helper ────────────────────────

    /**
     * Append the conversation's current compaction summary to {@code basePrompt}
     * as a labeled section. Returns {@code basePrompt} unchanged when there is
     * none: never compacted, or {@code /reset} since the last compaction. Call
     * this inside the prep Tx where {@code Conversation} is already managed.
     */
    public static String appendSummaryToPrompt(String basePrompt, Conversation conversation) {
        if (conversation == null) return basePrompt;
        var summary = currentSummary(conversation);
        if (summary == null) return basePrompt;
        return basePrompt + "\n\n" + PRIOR_SUMMARY_HEADER + "\n\n" + summary;
    }

    /** The latest summary, unless a {@code /reset} after it cleared the turns it covers. */
    private static @Nullable String currentSummary(Conversation conversation) {
        var latest = SessionCompaction.findLatest(conversation);
        if (latest == null || latest.summary == null || latest.summary.isBlank()) return null;
        if (conversation.contextSince != null && !latest.compactedAt.isAfter(conversation.contextSince)) return null;
        return latest.summary;
    }

    /**
     * Append the spawn-time inherited parent context (JCLAW-268) to
     * {@code basePrompt} as a labeled section. Returns {@code basePrompt}
     * unchanged when the conversation has no parent context (fresh-mode
     * spawn or a top-level conversation). Stamped once at spawn time and
     * re-injected on every turn so the child carries continuity with the
     * parent's recent turns without re-summarizing per turn.
     */
    public static String appendParentContextToPrompt(String basePrompt, Conversation conversation) {
        if (conversation == null) return basePrompt;
        if (conversation.parentContext == null || conversation.parentContext.isBlank()) return basePrompt;
        return basePrompt + "\n\n" + PARENT_CONTEXT_HEADER + "\n\n" + conversation.parentContext;
    }

    // ─── Internals ──────────────────────────────────────────────────────

    /**
     * Snapshot of a persisted {@link Message} taken under a Tx, usable after
     * the Tx closes.
     *
     * @param role        message role ({@code user}, {@code assistant},
     *                    {@code tool}, {@code system})
     * @param content     message body
     * @param toolCalls   assistant tool-calls JSON (null for non-assistant)
     * @param toolResults tool-call id this tool-row answers (null for non-tool)
     * @param createdAt   creation timestamp
     */
    public record MessageSnapshot(String role, String content, String toolCalls, String toolResults, Instant createdAt) {}
    record CompactionPlan(List<MessageSnapshot> toSummarize, MessageSnapshot firstKept, @Nullable String priorSummary) {}

    private static Optional<CompactionPlan> buildPlan(Long conversationId, boolean force) {
        var conv = Conversation.<Conversation>findById(conversationId);
        if (conv == null) return Optional.empty();
        var recent = ConversationService.loadRecentMessages(conv);
        int boundary = force ? findSafeBoundaryForced(recent) : findSafeBoundary(recent);
        if (boundary <= 0) return Optional.empty();
        var snaps = new ArrayList<MessageSnapshot>(boundary);
        for (int i = 0; i < boundary; i++) {
            snaps.add(snapshot(recent.get(i)));
        }
        return Optional.of(new CompactionPlan(snaps, snapshot(recent.get(boundary)), currentSummary(conv)));
    }

    private static MessageSnapshot snapshot(Message m) {
        return new MessageSnapshot(m.role, m.content, m.toolCalls, m.toolResults, m.createdAt);
    }

    /** Today's request shape: the instruction as the system prompt and the turns as one transcript. */
    private static List<ChatMessage> transcriptRequest(CompactionPlan plan, @Nullable String additionalInstructions) {
        var instructions = SUMMARIZATION_INSTRUCTIONS;
        if (additionalInstructions != null && !additionalInstructions.isBlank()) {
            instructions += "\n\nAdditional user guidance for this summary: " + additionalInstructions.strip();
        }
        var turns = renderTurns(plan.toSummarize());
        var prior = plan.priorSummary();
        var input = prior == null ? turns
                : PRIOR_SUMMARY_HEADER + "\n\n" + prior + "\n\n[Turns since that summary]\n\n" + turns;
        return List.of(ChatMessage.system(instructions), ChatMessage.user(input));
    }

    private static @Nullable String summarizeOnTurn(Long conversationId, CompactionPlan plan, TurnRequest turn,
                                                    ModelCall call) throws Exception {
        if (!alignsWith(plan, turn.messages())) {
            EventLogger.info("compaction", null, null, ("Compaction for conversation %d sent the transcript: "
                    + "the turn's messages no longer match the stored rows").formatted(conversationId));
            return firstChoiceText(call.call(transcriptRequest(plan, null), List.of()));
        }
        var reply = call.call(turnRequest(plan, turn.messages()), turn.tools());
        if (!callsATool(reply)) return firstChoiceText(reply);
        EventLogger.info("compaction", null, null,
                "Compaction reply for conversation %d called a tool; retrying without tools".formatted(conversationId));
        // The transcript, not this history minus tools: tool-call blocks sent with no tool definitions risk a refusal.
        return firstChoiceText(call.call(transcriptRequest(plan, null), List.of()));
    }

    /** The turn's list is its system prompt then one message per stored row, so row {@code i} sits at {@code i + 1}. */
    private static boolean alignsWith(CompactionPlan plan, List<ChatMessage> turn) {
        int firstKept = plan.toSummarize().size() + 1;
        if (turn.size() <= firstKept) return false;
        var kept = turn.get(firstKept);
        return MessageRole.USER.value.equals(kept.role())
                && (!(kept.content() instanceof String text) || text.equals(plan.firstKept().content()));
    }

    private static List<ChatMessage> turnRequest(CompactionPlan plan, List<ChatMessage> turn) {
        var prefix = turn.subList(0, plan.toSummarize().size() + 1);
        var request = new ArrayList<ChatMessage>(prefix.size() + 1);
        for (var m : prefix) request.add(withoutMedia(m));
        request.add(ChatMessage.user(SUMMARIZATION_INSTRUCTIONS));
        return request;
    }

    // Compaction runs before the media rewrites, so a part the model cannot take would fail the call.
    private static ChatMessage withoutMedia(ChatMessage m) {
        if (!(m.content() instanceof List<?> parts)) return m;
        var text = new StringBuilder();
        for (var part : parts) {
            if (part instanceof Map<?, ?> p && "text".equals(p.get("type")) && p.get("text") instanceof String t) {
                text.append(t);
            }
        }
        return new ChatMessage(m.role(), text.isEmpty() ? "[attachment]" : text.toString(),
                m.toolCalls(), m.toolCallId(), m.toolName());
    }

    private static boolean callsATool(ChatResponse reply) {
        if (reply.choices().isEmpty()) return false;
        var calls = reply.choices().getFirst().message().toolCalls();
        return calls != null && !calls.isEmpty();
    }

    /**
     * Pick an index in {@code messages} that splits a "to-summarize"
     * prefix from a "keep verbatim" suffix. Constraints:
     *
     * <ul>
     *   <li>Suffix has at least {@code chat.compactionKeepMessages}
     *       messages (default 10) — a minimum tail so the model keeps
     *       tight coupling with the most recent work.</li>
     *   <li>Prefix has at least {@code chat.compactionMinTurns}
     *       messages (default 10) — below that, compaction costs more
     *       than it saves.</li>
     *   <li>The boundary message is a USER role — guarantees we don't
     *       split a tool-call cycle across the boundary, since user
     *       messages start new turns.</li>
     * </ul>
     *
     * Returns {@code -1} if no valid boundary exists (conversation too
     * short, or no user message in the eligible range).
     */
    public static int findSafeBoundary(List<Message> messages) {
        return findSafeBoundary(messages,
                Math.max(1, ConfigService.getInt("chat.compactionKeepMessages", 10)),
                Math.max(1, ConfigService.getInt("chat.compactionMinTurns", 10)));
    }

    /**
     * Relaxed thresholds for manual {@code /compact} invocations —
     * {@code chat.compactionForcedKeepMessages} (default 4) and
     * {@code chat.compactionForcedMinTurns} (default 2). Still anchors at a
     * user message so tool-call cycles stay intact.
     */
    public static int findSafeBoundaryForced(List<Message> messages) {
        return findSafeBoundary(messages,
                Math.max(1, ConfigService.getInt("chat.compactionForcedKeepMessages", 4)),
                Math.max(1, ConfigService.getInt("chat.compactionForcedMinTurns", 2)));
    }

    public static int findSafeBoundary(List<Message> messages, int keepMin, int minCompactable) {
        int total = messages.size();
        int maxBoundary = total - keepMin;
        if (maxBoundary < minCompactable) return -1;
        for (int i = maxBoundary; i >= minCompactable; i--) {
            if (MessageRole.USER.value.equals(messages.get(i).role)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Flatten the to-summarize messages into a single
     * {@code [ROLE] content} block for the summarizer. Tool calls and
     * tool results are included as labeled sub-sections so the
     * summarizer knows what actions the agent took and what came back.
     */
    public static String renderTurns(List<MessageSnapshot> snaps) {
        var sb = new StringBuilder(snaps.size() * 200);
        for (var s : snaps) {
            var role = s.role() == null ? "unknown" : s.role().toUpperCase();
            sb.append("[").append(role).append("]");
            if (s.content() != null && !s.content().isBlank()) {
                sb.append(' ').append(s.content().strip());
            }
            if (s.toolCalls() != null && !s.toolCalls().isBlank()) {
                sb.append("\n[TOOL_CALLS] ").append(s.toolCalls().strip());
            }
            if (s.toolResults() != null && !s.toolResults().isBlank()) {
                sb.append("\n[TOOL_RESULT_ID] ").append(s.toolResults().strip());
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Extract the first-choice content string from an OpenAI-compat
     * response. Returns {@code null} when the response shape is
     * unexpected (non-string content, empty choices, etc.).
     */
    public static @Nullable String firstChoiceText(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) return null;
        var msg = response.choices().getFirst().message();
        if (msg == null) return null;
        var content = msg.content();
        if (content instanceof String s) return s;
        if (content instanceof List<?> parts) {
            var out = new StringBuilder();
            for (var part : parts) {
                if (part instanceof Map<?, ?> m && m.get("text") instanceof String t) out.append(t);
            }
            return out.isEmpty() ? null : out.toString();
        }
        return null;
    }

    // ─── Summarization prompt ────────────────────────────────────────────

    // Fixed sections (JF-154), so every summary records the same kinds of facts; level-3 headings nest
    // under the system prompt's own sections once the summary is appended to it.
    private static final String SUMMARIZATION_INSTRUCTIONS = """
            The conversation so far is being compacted: its older turns will be dropped, and the summary you write now is what the agent will read in their place when the work continues. Do not answer the user and do not call a tool; reply with the summary alone.

            An earlier compaction may have left a [Prior conversation summary …]. Your summary replaces it, so carry forward everything in it that still matters.

            Write these five sections, in this order, each under its heading as short bullet points, with "None." under a heading that has nothing to record:

            ### Goals and constraints
            What the user wants, and every constraint, preference and requirement they set.

            ### Decisions
            What the user or the agent decided, with the reason when one was given.

            ### Identifiers and facts
            The names, ids, paths, URLs, numbers and other values the work relies on, copied exactly as written: never shorten, round or reconstruct one.

            ### Open questions and errors
            Questions still unanswered, errors not yet resolved, and choices still waiting on the user.

            ### Next steps
            What remains to do, starting with the user's latest request and the step in progress.

            Where turns disagree, the latest update wins. Mark anything that no tool result or user message confirmed as (unverified). Leave out greetings, small talk and finished work with no follow-up. No preface and no sign-off.
            """;

    public static final String PRIOR_SUMMARY_HEADER =
            "[Prior conversation summary — earlier turns were compacted to fit the context window]";

    /**
     * Header used by {@link #appendParentContextToPrompt} when a subagent was
     * spawned with {@code context="inherit"}. Lets the child distinguish the
     * inherited parent narrative from its own compaction summary (which can
     * appear alongside on long-running children).
     */
    public static final String PARENT_CONTEXT_HEADER =
            "[Parent conversation context — summary of the spawning parent's recent turns at the time this subagent was spawned]";

    /**
     * Hard cap on the inherited-parent summary length (JCLAW-268). The
     * summarization prompt asks for a summary within this budget, then the
     * spawn path truncates anything longer with an ellipsis. Belt-and-
     * suspenders against an over-eager model.
     */
    public static final int PARENT_CONTEXT_MAX_CHARS = 8_000;

    /**
     * Bounded summary of a parent conversation for inherit-mode subagent
     * spawning (JCLAW-268). Returns {@code null} when the conversation has
     * no usable history (the caller treats that as "skip summary cleanly").
     * Calls the supplied {@code summarizer} synchronously — must run
     * OUTSIDE a Tx since the LLM call may take seconds. The result is
     * stripped and hard-truncated to {@link #PARENT_CONTEXT_MAX_CHARS}
     * with an ellipsis suffix when the model overshoots; the prompt asks
     * for a summary within the budget but the truncation is the load-
     * bearing guarantee.
     *
     * @param parentMessages an ASC-ordered snapshot of the parent's recent
     *                       message rows (read inside a Tx by the caller,
     *                       so this method has no DB dependency)
     * @param summarizer     same functional seam {@link #compact} uses;
     *                       production passes a lambda over LlmProvider.chat
     * @throws Exception if the summarizer raises — the caller degrades
     *                   to fresh and emits SUBAGENT_ERROR.
     */
    @SuppressWarnings("java:S112") // mirrors Summarizer.summarize: broad signature avoids leaking provider exception types
    public static @Nullable String summarizeParentForSubagent(List<MessageSnapshot> parentMessages, Summarizer summarizer)
            throws Exception {
        if (parentMessages == null || parentMessages.isEmpty()) return null;
        var sumMessages = List.<ChatMessage>of(
                ChatMessage.system(PARENT_CONTEXT_SUMMARIZATION_INSTRUCTIONS),
                ChatMessage.user(renderTurns(parentMessages))
        );
        var raw = summarizer.summarize(sumMessages);
        if (raw == null || raw.isBlank()) return null;
        var trimmed = raw.strip();
        if (trimmed.length() <= PARENT_CONTEXT_MAX_CHARS) return trimmed;
        return trimmed.substring(0, PARENT_CONTEXT_MAX_CHARS - 1).stripTrailing() + "…";
    }

    /**
     * Snapshot the parent's recent messages for {@link #summarizeParentForSubagent}.
     * Must be called inside a {@link Tx#run} block — uses
     * {@link ConversationService#loadRecentMessages} which is JPA-bound. The
     * returned {@link MessageSnapshot} list is detached from the
     * persistence context and safe to consume outside the Tx.
     */
    public static List<MessageSnapshot> snapshotParentMessages(Conversation parentConv) {
        if (parentConv == null) return List.of();
        var recent = ConversationService.loadRecentMessages(parentConv);
        var out = new ArrayList<MessageSnapshot>(recent.size());
        for (var m : recent) {
            out.add(new MessageSnapshot(m.role, m.content, m.toolCalls, m.toolResults, m.createdAt));
        }
        return out;
    }

    /**
     * Summarization instructions for the inherit-mode parent-context snapshot
     * (JCLAW-268). Scoped tighter than {@link #SUMMARIZATION_INSTRUCTIONS} —
     * the child only needs enough background to continue the parent's task,
     * not a faithful compression of the entire conversation. The 7500-char
     * target leaves slack inside the 8000 hard cap for the model's
     * occasional overshoot.
     */
    private static final String PARENT_CONTEXT_SUMMARIZATION_INSTRUCTIONS = """
            You are summarizing a parent agent's conversation so a child subagent can pick up its task with enough context to do useful work. Produce a single prose summary — no preamble, no meta-commentary — under 7500 characters. The child will see this as background context, not as the conversation itself.

            Focus on:
            1. The most recent task or request the parent was working on.
            2. Key decisions, constraints, and any user preferences already established.
            3. Exact values the child may need: names, file paths, IDs, URLs, configuration values. Preserve identifiers verbatim.
            4. Tool state — any in-flight operations, file edits, or external system interactions.
            5. What the child still needs to do, if explicit instructions were already given to the parent.

            De-prioritize greetings, resolved errors, and completed side-quests. Do not invent facts; if a detail is ambiguous, summarize it as ambiguous.

            Output only the summary itself — no "Here is the summary:" preface, no markdown headers, no sign-off.
            """;
}
