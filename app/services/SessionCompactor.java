package services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ToolDef;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SessionCompaction;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Session compaction: when a conversation's active context approaches the
 * model's input window, roll the oldest non-essential turns into
 * LLM-generated summary segments (JCLAW-38, JF-161).
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
 * <p>Trigger: whichever comes first of the token trigger, the smaller of {@code chat.compaction.triggerPercent} of
 * the model's window left after the system prompt and tools and {@code chat.compaction.triggerTokens} (defaults 50
 * and 150,000), since quality falls well before today's windows fill (JF-154, JF-159), and
 * {@code estimatedTokens > (contextWindow - reserveTokens)}. The
 * reserve carves out headroom for the completion itself plus a safety margin, following OpenClaw's approach — tuned
 * via {@code chat.compactionReserveTokens} (default 15000, floor 9000). Separately, the gate compacts a chat whose
 * rows since its floor pass the history limit ({@link #pastHistoryLimit}), which would otherwise drop them unread.
 *
 * <p>A compaction summarizes the oldest rows since the floor, in chunks when they exceed what one summary call
 * takes (JF-159). Each call stores its own segment and no earlier summary is folded in; once the live segments pass
 * {@code chat.compaction.summaryBudgetTokens}, the oldest are merged into one (JF-161).
 *
 * <p>Boundary selection: we anchor cuts at user-message boundaries so
 * we never split an assistant's tool_calls from its tool_result
 * companions. Providers reject contexts with orphaned tool calls.
 *
 * <p>Re-injection: the segments are appended to the system prompt as a
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
     * A turn's request as the compaction gate sees it: its messages, system prompt first, after pruning and
     * compression but before the media rewrites and the trailing clock and recall message, and its tool definitions.
     */
    public record TurnRequest(List<ChatMessage> messages, List<ToolDef> tools) {}

    /** One model call on a message list and tool list; production wraps {@code LlmProvider.chat}. */
    @FunctionalInterface
    public interface ModelCall {
        @SuppressWarnings("java:S112") // as Summarizer.summarize: keeps provider exception types out of the seam
        ChatResponse call(List<ChatMessage> messages, List<ToolDef> tools) throws Exception;

        /** Whether the summary model's window takes this request; a turn-shaped one it does not goes as the transcript. */
        default boolean fits(List<ChatMessage> messages, List<ToolDef> tools) {
            return true;
        }

        /** The {@code provider/model} that answered the last call when it may differ from the label given (JF-160). */
        default @Nullable String answeredBy() {
            return null;
        }
    }

    @FunctionalInterface
    private interface SummaryStep {
        @SuppressWarnings("java:S112") // as Summarizer.summarize: keeps provider exception types out of the seam
        @Nullable String summarize(CompactionPlan plan) throws Exception;
    }

    /**
     * @param summaryChars the characters of every segment the prompt carries after it
     * @param calls        the summary calls it made, merges included
     * @param rowsLeft     rows still before the kept tail when it stopped short: a call failed or the call cap was hit
     * @param rowsSkipped  rows the newest-rows fallback left behind the floor unsummarized
     */
    public record CompactionResult(
            boolean compacted,
            int turnsCompacted,
            int summaryChars,
            @Nullable String skipReason,
            int calls,
            int rowsLeft,
            int rowsSkipped
    ) {
        public static CompactionResult skipped(String reason) {
            return new CompactionResult(false, 0, 0, reason, 0, 0, 0);
        }
        public static CompactionResult success(int turns, int chars, int calls, int rowsLeft, int rowsSkipped) {
            return new CompactionResult(true, turns, chars, null, calls, rowsLeft, rowsSkipped);
        }
    }

    // ─── Decision ───────────────────────────────────────────────────────

    /** JF-154: the token trigger's cap; 0 or less turns this half off. */
    public static final String TRIGGER_TOKENS_KEY = "chat.compaction.triggerTokens";
    public static final int DEFAULT_TRIGGER_TOKENS = 150_000;
    /** JF-159: the token trigger's share, in percent, of the window left after the system prompt and tools; 0 or less turns this half off. */
    public static final String TRIGGER_PERCENT_KEY = "chat.compaction.triggerPercent";
    public static final int DEFAULT_TRIGGER_PERCENT = 50;
    /** JF-159: false leaves a chat past its history limit to the newest-rows load, dropping the oldest unread. */
    public static final String ON_ROW_LIMIT_KEY = "chat.compaction.onRowLimit";
    /** The most summary calls one automatic compaction makes; a catch-up that needs more continues on the next turn. */
    public static final int MAX_SUMMARY_CALLS = 8;
    /** JF-161: the live segments' total, in tokens, past which the oldest are merged. */
    public static final String SUMMARY_BUDGET_KEY = "chat.compaction.summaryBudgetTokens";
    public static final int DEFAULT_SUMMARY_BUDGET = 20_000;
    public static final int MIN_SUMMARY_BUDGET = 1_000;
    /** JF-161: how many characters of each tool result, and of long tool-call arguments, a transcript carries. */
    public static final String TOOL_RESULT_CHARS_KEY = "chat.compaction.toolResultChars";
    public static final int DEFAULT_TOOL_RESULT_CHARS = 600;
    /** The output limit of every summary call, a merge's included. */
    public static final String MAX_TOKENS_KEY = "chat.compactionMaxTokens";
    public static final int DEFAULT_MAX_TOKENS = 8192;

    /**
     * True when the current message list reaches the token trigger ({@link #triggerThreshold}) or exceeds the
     * model's window budget. Callers should compute {@code estimatedTokens} with
     * {@code ContextWindowManager.adjustedPromptTokens} so thresholds compare apples to apples.
     */
    public static boolean shouldCompact(int estimatedTokens, @Nullable ModelInfo modelInfo) {
        return shouldCompact(estimatedTokens, 0, modelInfo);
    }

    /**
     * {@link #shouldCompact(int, ModelInfo)} with the share taken of the window left after {@code fixedTokens}, the
     * system prompt and tools a summary cannot shrink, so a prompt that small never re-fires the trigger.
     */
    public static boolean shouldCompact(int estimatedTokens, int fixedTokens, @Nullable ModelInfo modelInfo) {
        return estimatedTokens >= triggerThreshold(modelInfo, triggerPercent(), triggerTokens(), fixedTokens)
                || estimatedTokens > windowBudget(modelInfo);
    }

    /** {@link #shouldCompact(int, ModelInfo)} with the token trigger's two halves given; both off leaves the window rule. */
    public static boolean shouldCompact(int estimatedTokens, @Nullable ModelInfo modelInfo, int triggerPercent,
                                        int triggerTokens) {
        return estimatedTokens >= triggerThreshold(modelInfo, triggerPercent, triggerTokens)
                || estimatedTokens > windowBudget(modelInfo);
    }

    /** {@link #triggerThreshold(ModelInfo, int, int, int)} with nothing fixed. */
    public static int triggerThreshold(@Nullable ModelInfo modelInfo, int triggerPercent, int triggerTokens) {
        return triggerThreshold(modelInfo, triggerPercent, triggerTokens, 0);
    }

    /**
     * The prompt size at which the token trigger fires: {@code fixedTokens} plus {@code triggerPercent} of the window
     * left after them, or {@code triggerTokens} if smaller. A half at 0 or less is off, as is the share when the window
     * is unknown or the fixed part fills it, leaving the cap alone; {@link Integer#MAX_VALUE}, never reached, when both
     * are off.
     */
    public static int triggerThreshold(@Nullable ModelInfo modelInfo, int triggerPercent, int triggerTokens,
                                       int fixedTokens) {
        long threshold = Integer.MAX_VALUE;
        int window = modelInfo != null ? modelInfo.contextWindow() : 0;
        long fixed = Math.max(0, fixedTokens);
        long room = window - fixed;
        if (triggerPercent > 0 && window > 0 && room > 0) {
            threshold = Math.max(1, fixed + room * Math.min(triggerPercent, 100) / 100);
        }
        if (triggerTokens > 0) threshold = Math.min(threshold, triggerTokens);
        return (int) threshold;
    }

    /**
     * The most tokens one summary call's rows may carry for {@code modelInfo}: the token trigger's threshold, the size
     * at which a turn's own prompt is summarized, else the window budget; unbounded when neither is known.
     */
    public static int summaryCallTokens(@Nullable ModelInfo modelInfo) {
        int threshold = triggerThreshold(modelInfo, triggerPercent(), triggerTokens());
        return threshold != Integer.MAX_VALUE ? threshold : windowBudget(modelInfo);
    }

    /**
     * JF-159: whether {@code conversation}'s rows since its floor pass its history limit, so the newest-rows load would
     * leave the oldest out unsummarized. False when {@code chat.compaction.onRowLimit} is false. Call inside a Tx.
     */
    public static boolean pastHistoryLimit(Conversation conversation) {
        return ConfigService.getBoolean(ON_ROW_LIMIT_KEY, true)
                && ConversationService.countSinceFloor(conversation) > ConversationService.effectiveHistoryLimit(conversation);
    }

    private static int triggerPercent() {
        return ConfigService.getInt(TRIGGER_PERCENT_KEY, DEFAULT_TRIGGER_PERCENT);
    }

    private static int triggerTokens() {
        return ConfigService.getInt(TRIGGER_TOKENS_KEY, DEFAULT_TRIGGER_TOKENS);
    }

    /** {@value #MAX_TOKENS_KEY}, the most tokens a summary call may write. */
    public static int summaryMaxTokens() {
        return ConfigService.getInt(MAX_TOKENS_KEY, DEFAULT_MAX_TOKENS);
    }

    // A value written around the validation reads as its floor.
    private static int summaryBudgetTokens() {
        return Math.max(MIN_SUMMARY_BUDGET, ConfigService.getInt(SUMMARY_BUDGET_KEY, DEFAULT_SUMMARY_BUDGET));
    }

    private static int toolResultChars() {
        return Math.max(0, ConfigService.getInt(TOOL_RESULT_CHARS_KEY, DEFAULT_TOOL_RESULT_CHARS));
    }

    /** The model's window less the reserve; {@link Integer#MAX_VALUE}, never passed, when that leaves nothing. */
    public static int windowBudget(@Nullable ModelInfo modelInfo) {
        if (modelInfo == null || modelInfo.contextWindow() <= 0) return Integer.MAX_VALUE;
        int budget = modelInfo.contextWindow() - effectiveReserveTokens();
        return budget > 0 ? budget : Integer.MAX_VALUE;
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
     * Then the oldest segments are merged while the live ones pass the budget.
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
        return compact(conversationId, () -> modelLabel, force, Integer.MAX_VALUE, MAX_SUMMARY_CALLS,
                plan -> summarizer.summarize(transcriptRequest(plan, additionalInstructions)), summarizer);
    }

    /**
     * Automatic compaction on the turn's own prefix (JF-154): the request sends the turn's system prompt,
     * the summarized messages as the turn sent them and its tools, with the instruction last as a user
     * message. Its message and tool bytes match the turn's up to the first summarized message that carries
     * an image or audio, which goes as its text; whether a provider reads that prefix from its cache is the
     * provider's rule, and the request sets no reasoning effort ({@code thinkingMode} null) where the turn
     * may set one. A reply that calls a tool is retried once on the transcript without tools, and so is a
     * call that fails, since the turn's prompt, tools and {@code max_tokens} can overflow a small window the
     * transcript fits; no tool is ever run. When the turn's list no longer lines up with the stored rows,
     * the transcript is sent instead, as it is for rows the turn did not load and for a request the summary
     * model's window does not take ({@link ModelCall#fits}).
     *
     * @param turn the request the turn is about to send
     * @param call the model call, made outside any transaction
     */
    public static CompactionResult compact(Long conversationId, String modelLabel, TurnRequest turn, ModelCall call) {
        return compact(conversationId, modelLabel, turn, call, false, null, Integer.MAX_VALUE, MAX_SUMMARY_CALLS);
    }

    /**
     * {@link #compact(Long, String, TurnRequest, ModelCall)} with {@code /compact}'s knobs: {@code force} relaxes the
     * too-few-turns guard as the transcript form's does, and {@code additionalInstructions} follows the instruction,
     * in the transcript retries too. {@code callTokens} bounds the rows of each summary call the turn's own request
     * cannot carry ({@link #summaryCallTokens}), and {@code maxCalls} the summary calls, merges included: automatic
     * compaction passes {@link #MAX_SUMMARY_CALLS}, {@code /compact} no bound, so it reaches the kept tail. Each
     * stored summary names {@link ModelCall#answeredBy}, else {@code modelLabel}.
     */
    public static CompactionResult compact(Long conversationId, String modelLabel, TurnRequest turn, ModelCall call,
                                           boolean force, @Nullable String additionalInstructions, int callTokens,
                                           int maxCalls) {
        return compact(conversationId, () -> Objects.requireNonNullElse(call.answeredBy(), modelLabel), force,
                callTokens, maxCalls, plan -> summarizeOnTurn(conversationId, plan, turn, call, additionalInstructions),
                messages -> firstChoiceText(call.call(messages, List.of())));
    }

    /**
     * One summary call after another, oldest rows first, each storing its own segment, until a plan reaches the kept
     * tail or {@code maxCalls} are made (JF-159); then the oldest segments are merged while the live ones pass the
     * budget (JF-161).
     */
    private static CompactionResult compact(Long conversationId, Supplier<String> modelLabel, boolean force,
                                            int callTokens, int maxCalls, SummaryStep step, Summarizer merger) {
        int turnsCompacted = 0;
        int rowsSkipped = 0;
        int calls = 0;
        boolean reachedTail = false;
        @Nullable Instant floor = null;
        while (calls < maxCalls) {
            final boolean first = calls == 0;
            var planOpt = Tx.run(() -> buildPlan(conversationId, force, callTokens, first));
            if (planOpt.isEmpty()) {
                if (first) return CompactionResult.skipped("no safe boundary or below min-turns");
                reachedTail = true;
                break;
            }
            var plan = planOpt.get();
            if (floor != null && !plan.firstKept().createdAt().isAfter(floor)) {
                // Rows sharing the floor's timestamp would be planned again on every call without the floor moving.
                EventLogger.info("compaction", null, null, ("Compaction for conversation %d stopped: its next rows "
                        + "share the timestamp the last summary ended on").formatted(conversationId));
                break;
            }
            calls++;
            var stored = summarizeAndStore(conversationId, modelLabel, plan, step);
            // A later call that fails keeps the segments already stored, and the next compaction starts after them.
            if (!stored.compacted()) {
                if (first) return stored;
                break;
            }
            turnsCompacted += stored.turnsCompacted();
            rowsSkipped += plan.skipped();
            floor = plan.firstKept().createdAt();
            if (plan.last()) {
                reachedTail = true;
                break;
            }
        }
        if (!reachedTail && calls >= maxCalls) {
            EventLogger.info("compaction", null, null,
                    "Compaction for conversation %d stopped after %d summary calls; the next turn continues it"
                            .formatted(conversationId, maxCalls));
        }
        calls += mergeOverBudget(conversationId, modelLabel, merger, maxCalls - calls);
        int rowsLeft = reachedTail ? 0 : Tx.run(() -> rowsBeforeTail(conversationId, force));
        int summaryChars = Tx.run(() -> liveChars(conversationId));
        return CompactionResult.success(turnsCompacted, summaryChars, calls, rowsLeft, rowsSkipped);
    }

    private static CompactionResult summarizeAndStore(Long conversationId, Supplier<String> modelLabel,
                                                      CompactionPlan plan, SummaryStep step) {
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
        final var turnsCompacted = plan.toSummarize().size();
        final var label = modelLabel.get();

        Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(conversationId);
            if (conv == null) return; // conversation deleted between phases
            segment(conv, finalSummary, label, turnsCompacted, plan.toSummarize().getFirst().createdAt(),
                    plan.toSummarize().getLast().createdAt()).save();
            conv.compactionSince = plan.firstKept().createdAt();
            conv.save();
        });

        return CompactionResult.success(turnsCompacted, finalSummary.length(), 1, 0, 0);
    }

    private static SessionCompaction segment(Conversation conv, String summary, @Nullable String label, int turnCount,
                                             @Nullable Instant coversFrom, @Nullable Instant coversTo) {
        var sc = new SessionCompaction();
        sc.conversation = conv;
        sc.turnCount = turnCount;
        sc.summaryTokens = Math.max(1, summary.length() / 4);
        sc.model = label != null && !label.isBlank() ? label : "unknown";
        sc.summary = summary;
        sc.coversFrom = coversFrom;
        sc.coversTo = coversTo;
        return sc;
    }

    // ─── Merging the oldest segments (JF-161) ───────────────────────────

    /** The oldest live segments one merge call takes: their ids, their text as the call reads it, and what they cover. */
    private record MergePlan(List<Long> ids, String segments, @Nullable Instant coversFrom,
                             @Nullable Instant coversTo, int turnCount) {}

    /** Merges the oldest live segments, one call at a time, while they pass the budget; returns the calls it made. */
    private static int mergeOverBudget(Long conversationId, Supplier<String> modelLabel, Summarizer merger,
                                       int callsLeft) {
        int budget = summaryBudgetTokens();
        int maxOut = summaryMaxTokens();
        int calls = 0;
        while (calls < callsLeft) {
            var plan = Tx.run(() -> oldestToMerge(conversationId, budget, maxOut));
            if (plan.isEmpty()) break;
            calls++;
            if (!mergeAndStore(conversationId, modelLabel, plan.get(), merger)) break;
        }
        return calls;
    }

    /**
     * The fewest oldest live segments, two or more, whose merge brings the stack within {@code budget}, counting the
     * merge at its input's size up to {@code maxOut}, which it cannot write past; every one when none fewer would.
     * Empty when the stack fits or holds one segment.
     */
    private static Optional<MergePlan> oldestToMerge(Long conversationId, int budget, int maxOut) {
        var conv = Conversation.<Conversation>findById(conversationId);
        if (conv == null) return Optional.empty();
        var live = SessionCompaction.findLive(conv);
        long total = 0;
        for (var s : live) total += tokens(s);
        if (total <= budget || live.size() < 2) return Optional.empty();
        int take = live.size();
        long head = tokens(live.getFirst());
        for (int k = 2; k < live.size(); k++) {
            head += tokens(live.get(k - 1));
            if (total - head + Math.min(head, maxOut) <= budget) {
                take = k;
                break;
            }
        }
        var oldest = live.subList(0, take);
        var ids = new ArrayList<Long>(take);
        @Nullable Instant coversTo = null;
        int turnCount = 0;
        for (var s : oldest) {
            ids.add(s.id);
            if (s.coversTo != null && (coversTo == null || s.coversTo.isAfter(coversTo))) coversTo = s.coversTo;
            turnCount += s.turnCount;
        }
        return Optional.of(new MergePlan(ids, renderSegments(oldest), oldest.getFirst().coversFrom, coversTo,
                turnCount));
    }

    private static int tokens(SessionCompaction segment) {
        return segment.summary == null ? 0 : segment.summary.strip().length() / 4;
    }

    private static boolean mergeAndStore(Long conversationId, Supplier<String> modelLabel, MergePlan plan,
                                         Summarizer merger) {
        String merged;
        try {
            merged = merger.summarize(List.of(ChatMessage.system(MERGE_INSTRUCTIONS), ChatMessage.user(plan.segments())));
        } catch (Exception e) {
            EventLogger.warn("compaction", null, null, "Merging %d summaries of conversation %d failed: %s"
                    .formatted(plan.ids().size(), conversationId, e.getMessage()));
            return false;
        }
        if (merged == null || merged.isBlank()) {
            EventLogger.warn("compaction", null, null, "Merging %d summaries of conversation %d returned an empty summary"
                    .formatted(plan.ids().size(), conversationId));
            return false;
        }
        final var text = merged.strip();
        final var label = modelLabel.get();
        boolean stored = Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(conversationId);
            if (conv == null) return false;
            var sources = new ArrayList<SessionCompaction>(plan.ids().size());
            for (var id : plan.ids()) {
                SessionCompaction source = SessionCompaction.findById(id);
                // Another compaction of this conversation merged it first.
                if (source == null || source.mergedIntoId != null) return false;
                sources.add(source);
            }
            var result = segment(conv, text, label, plan.turnCount(), plan.coversFrom(), plan.coversTo());
            result.save();
            for (var source : sources) {
                source.mergedIntoId = result.id;
                source.save();
            }
            return true;
        });
        if (stored) {
            EventLogger.info("compaction", null, null, "Merged the oldest %d summaries of conversation %d into one"
                    .formatted(plan.ids().size(), conversationId));
        }
        return stored;
    }

    /** The characters of every segment the prompt carries for the conversation. */
    private static int liveChars(Long conversationId) {
        var conv = Conversation.<Conversation>findById(conversationId);
        if (conv == null) return 0;
        int chars = 0;
        for (var s : SessionCompaction.findLive(conv)) chars += s.summary == null ? 0 : s.summary.strip().length();
        return chars;
    }

    /** Rows since the floor ahead of where a compaction would keep the tail: what one that stopped short leaves. */
    private static int rowsBeforeTail(Long conversationId, boolean force) {
        var conv = Conversation.<Conversation>findById(conversationId);
        if (conv == null) return 0;
        long total = ConversationService.countSinceFloor(conv);
        var recent = ConversationService.loadRecentMessages(conv);
        int boundary = findSafeBoundary(recent, keepMessages(force), minTurns(force));
        return (int) Math.max(0, total - recent.size() + Math.max(0, boundary));
    }

    // ─── Summaries written before JF-161 ────────────────────────────────

    /**
     * Before JF-161 each summary rewrote the one before it, so a conversation's latest summary already holds its
     * earlier ones. Each earlier row is marked merged into the latest, which becomes the conversation's first segment,
     * its text unchanged and dated from the rows it covered. Only undated rows are read. Call inside a Tx.
     *
     * @return how many conversations had summaries to adopt
     */
    public static int adoptRollingSummaries() {
        var conversationIds = JPA.em().createQuery("SELECT DISTINCT s.conversation.id FROM SessionCompaction s"
                + " WHERE s.mergedIntoId IS NULL AND s.coversFrom IS NULL AND s.coversTo IS NULL", Long.class)
                .getResultList();
        int adopted = 0;
        for (var conversationId : conversationIds) {
            var conv = Conversation.<Conversation>findById(conversationId);
            if (conv == null) continue;
            List<SessionCompaction> chain = new ArrayList<>();
            for (Object row : SessionCompaction.find("conversation = ?1 AND mergedIntoId IS NULL AND coversFrom IS NULL"
                    + " AND coversTo IS NULL ORDER BY compactedAt DESC, id DESC", conv).fetch()) {
                chain.add((SessionCompaction) row);
            }
            if (chain.isEmpty()) continue;
            var latest = chain.getFirst();
            for (var earlier : chain.subList(1, chain.size())) {
                earlier.mergedIntoId = latest.id;
                earlier.save();
            }
            dateRollingSummary(conv, latest);
            latest.save();
            adopted++;
        }
        return adopted;
    }

    // Its rows end before the conversation's compactionSince and start at a /reset that came before it, else at the first row.
    private static void dateRollingSummary(Conversation conv, SessionCompaction latest) {
        var end = conv.compactionSince != null ? conv.compactionSince : latest.compactedAt;
        var start = conv.contextSince != null && conv.contextSince.isBefore(latest.compactedAt) ? conv.contextSince : null;
        latest.coversFrom = rowTime("MIN", conv, start, end);
        latest.coversTo = rowTime("MAX", conv, start, end);
    }

    private static @Nullable Instant rowTime(String aggregate, Conversation conv, @Nullable Instant start, Instant end) {
        var query = JPA.em().createQuery("SELECT " + aggregate + "(m.createdAt) FROM Message m WHERE m.conversation = :c"
                + " AND m.createdAt < :end" + (start != null ? " AND m.createdAt >= :start" : ""), Instant.class);
        query.setParameter("c", conv).setParameter("end", end);
        if (start != null) query.setParameter("start", start);
        return query.getSingleResult();
    }

    // ─── Summary → system-prompt injection helper ────────────────────────

    /**
     * Append the conversation's live summary segments to {@code basePrompt} as a labeled section, oldest first, each
     * under the dates it covers (JF-161). Returns {@code basePrompt} unchanged when there is none: never compacted, or
     * {@code /reset} since the last compaction. Call this inside the prep Tx where {@code Conversation} is already
     * managed.
     */
    public static String appendSummaryToPrompt(String basePrompt, Conversation conversation) {
        if (conversation == null) return basePrompt;
        var segments = renderSegments(SessionCompaction.findLive(conversation));
        if (segments.isEmpty()) return basePrompt;
        return basePrompt + "\n\n" + PRIOR_SUMMARY_HEADER + "\n\n" + segments;
    }

    /** Each segment under its {@link #segmentLabel}, in the order given: the prompt's section and a merge's input. */
    private static String renderSegments(List<SessionCompaction> segments) {
        var parts = new ArrayList<String>(segments.size());
        for (var s : segments) {
            if (s.summary == null || s.summary.isBlank()) continue;
            parts.add(segmentLabel(s) + "\n\n" + s.summary.strip());
        }
        return String.join("\n\n", parts);
    }

    private static final DateTimeFormatter LABEL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * The line above a segment: the dates of the first and last rows it covers, in the operator's zone. A summary from
     * before JF-161 whose rows could not be dated is labelled with the time it was written.
     */
    public static String segmentLabel(SessionCompaction segment) {
        var zone = TimezoneResolver.appZone();
        var to = LABEL_TIME.format(Objects.requireNonNullElse(segment.coversTo, segment.compactedAt).atZone(zone));
        if (segment.coversFrom == null) return "[Turns up to " + to + ", " + zone.getId() + "]";
        return "[Turns from " + LABEL_TIME.format(segment.coversFrom.atZone(zone)) + " to " + to + ", "
                + zone.getId() + "]";
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
    /**
     * One summary call's rows. {@code turnsOwnHistory} when they are the head of the history the turn loaded, so the
     * turn's own request can carry them; {@code last} when {@code firstKept} starts the conversation's kept tail
     * rather than the next chunk; {@code skipped}, the rows the newest-rows fallback leaves behind it unsummarized.
     */
    record CompactionPlan(List<MessageSnapshot> toSummarize, MessageSnapshot firstKept, boolean turnsOwnHistory,
                          boolean last, int skipped) {}

    /**
     * The next summary call's rows (JF-159): the oldest since the floor, ending on a user row that leaves at least the
     * kept tail, at most the history limit and, past the turn's own history, at most {@code callTokens}. When no user
     * row ends such a chunk in a chat past its limit, today's plan on the newest rows, with one line saying what it
     * leaves out.
     */
    private static Optional<CompactionPlan> buildPlan(Long conversationId, boolean force, int callTokens,
                                                      boolean firstCall) {
        var conv = Conversation.<Conversation>findById(conversationId);
        if (conv == null) return Optional.empty();
        int keep = keepMessages(force);
        int min = minTurns(force);
        int limit = ConversationService.effectiveHistoryLimit(conv);
        long total = ConversationService.countSinceFloor(conv);
        if (firstCall && total <= limit) {
            var recent = ConversationService.loadRecentMessages(conv);
            return planAt(recent, findSafeBoundary(recent, keep, min), true, true, 0);
        }
        var oldest = ConversationService.loadOldestMessages(conv, (int) Math.min(total, limit + 1L));
        int reach = (int) Math.min(limit, total - keep);
        int fit = rowsWithin(oldest, callTokens);
        int boundary = latestUserRow(oldest, min, Math.min(reach, fit));
        if (boundary > 0) return planAt(oldest, boundary, false, reach == total - keep && fit >= reach, 0);
        // One chunk over the budget beats none: the call may still fit, and one that fails only stops the catch-up.
        boundary = earliestUserRow(oldest, Math.max(min, fit + 1), reach);
        if (boundary > 0) return planAt(oldest, boundary, false, false, 0);
        if (total <= limit) return Optional.empty();
        EventLogger.info("compaction", null, null, ("Compaction for conversation %d found no user message to end a "
                + "summary on among its oldest rows; the %d rows before its newest %d are not summarized")
                .formatted(conversationId, total - limit, limit));
        var recent = ConversationService.loadRecentMessages(conv);
        return planAt(recent, findSafeBoundary(recent, keep, min), firstCall, true, (int) (total - limit));
    }

    private static Optional<CompactionPlan> planAt(List<Message> rows, int boundary, boolean turnsOwnHistory,
                                                   boolean last, int skipped) {
        if (boundary <= 0) return Optional.empty();
        var snaps = new ArrayList<MessageSnapshot>(boundary);
        for (int i = 0; i < boundary; i++) {
            snaps.add(snapshot(rows.get(i)));
        }
        return Optional.of(new CompactionPlan(snaps, snapshot(rows.get(boundary)), turnsOwnHistory, last, skipped));
    }

    /** How many of {@code rows}, from the first, fit in {@code tokens} at four characters a token as a transcript renders them. */
    private static int rowsWithin(List<Message> rows, int tokens) {
        if (tokens == Integer.MAX_VALUE) return rows.size();
        int toolChars = toolResultChars();
        long used = 0;
        for (int i = 0; i < rows.size(); i++) {
            used += renderTurns(List.of(snapshot(rows.get(i))), toolChars).length() / 4;
            if (used > tokens) return i;
        }
        return rows.size();
    }

    private static MessageSnapshot snapshot(Message m) {
        return new MessageSnapshot(m.role, m.content, m.toolCalls, m.toolResults, m.createdAt);
    }

    /** The instruction as the system prompt and the turns as one transcript, tool output shortened (JF-161). */
    private static List<ChatMessage> transcriptRequest(CompactionPlan plan, @Nullable String additionalInstructions) {
        return List.of(ChatMessage.system(instructions(additionalInstructions)),
                ChatMessage.user(renderTurns(plan.toSummarize(), toolResultChars())));
    }

    private static String instructions(@Nullable String additionalInstructions) {
        if (additionalInstructions == null || additionalInstructions.isBlank()) return SUMMARIZATION_INSTRUCTIONS;
        return SUMMARIZATION_INSTRUCTIONS + "\n\nAdditional user guidance for this summary: " + additionalInstructions.strip();
    }

    private static @Nullable String summarizeOnTurn(Long conversationId, CompactionPlan plan, TurnRequest turn,
                                                    ModelCall call, @Nullable String additionalInstructions)
            throws Exception {
        if (!plan.turnsOwnHistory()) {
            return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions), List.of()));
        }
        if (!alignsWith(plan, turn.messages())) {
            EventLogger.info("compaction", null, null, ("Compaction for conversation %d sent the transcript: "
                    + "the turn's messages no longer match the stored rows").formatted(conversationId));
            return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions), List.of()));
        }
        var request = turnRequest(plan, turn.messages(), additionalInstructions);
        if (!call.fits(request, turn.tools())) {
            EventLogger.info("compaction", null, null, ("Compaction for conversation %d sent the transcript: "
                    + "the turn's request does not fit the summary model's window").formatted(conversationId));
            return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions), List.of()));
        }
        ChatResponse reply;
        try {
            reply = call.call(request, turn.tools());
        } catch (Exception e) {
            EventLogger.warn("compaction", null, null, ("Compaction on the turn's request failed for conversation %d "
                    + "(%s); retrying on the transcript").formatted(conversationId, e.getMessage()));
            return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions), List.of()));
        }
        if (!callsATool(reply)) return firstChoiceText(reply);
        EventLogger.info("compaction", null, null,
                "Compaction reply for conversation %d called a tool; retrying without tools".formatted(conversationId));
        // The transcript, not this history minus tools: tool-call blocks sent with no tool definitions risk a refusal.
        return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions), List.of()));
    }

    /** The turn's list is its system prompt then the loaded rows, so the slot after the summarized rows holds the first kept row. */
    private static boolean alignsWith(CompactionPlan plan, List<ChatMessage> turn) {
        int firstKept = plan.toSummarize().size() + 1;
        if (turn.size() <= firstKept) return false;
        var kept = turn.get(firstKept);
        return MessageRole.USER.value.equals(kept.role())
                && (!(kept.content() instanceof String text) || text.equals(plan.firstKept().content()));
    }

    private static List<ChatMessage> turnRequest(CompactionPlan plan, List<ChatMessage> turn,
                                                 @Nullable String additionalInstructions) {
        var prefix = turn.subList(0, plan.toSummarize().size() + 1);
        var request = new ArrayList<ChatMessage>(prefix.size() + 1);
        for (var m : prefix) request.add(withoutMedia(m));
        request.add(ChatMessage.user(instructions(additionalInstructions)));
        return request;
    }

    // Compaction runs before the media rewrites, so a part the model cannot take would fail the call. Text parts
    // alone (a document attachment's notes) are left as the turn sends them.
    private static ChatMessage withoutMedia(ChatMessage m) {
        if (!(m.content() instanceof List<?> parts) || parts.stream().allMatch(SessionCompactor::isTextPart)) return m;
        var text = new StringBuilder();
        for (var part : parts) {
            if (isTextPart(part) && ((Map<?, ?>) part).get("text") instanceof String t) text.append(t);
        }
        return new ChatMessage(m.role(), text.isEmpty() ? "[attachment]" : text.toString(),
                m.toolCalls(), m.toolCallId(), m.toolName());
    }

    private static boolean isTextPart(Object part) {
        return part instanceof Map<?, ?> p && "text".equals(p.get("type"));
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
        return findSafeBoundary(messages, keepMessages(false), minTurns(false));
    }

    /**
     * Relaxed thresholds for manual {@code /compact} invocations —
     * {@code chat.compactionForcedKeepMessages} (default 4) and
     * {@code chat.compactionForcedMinTurns} (default 2). Still anchors at a
     * user message so tool-call cycles stay intact.
     */
    public static int findSafeBoundaryForced(List<Message> messages) {
        return findSafeBoundary(messages, keepMessages(true), minTurns(true));
    }

    public static int findSafeBoundary(List<Message> messages, int keepMin, int minCompactable) {
        return latestUserRow(messages, minCompactable, messages.size() - keepMin);
    }

    private static int keepMessages(boolean force) {
        return force ? Math.max(1, ConfigService.getInt("chat.compactionForcedKeepMessages", 4))
                : Math.max(1, ConfigService.getInt("chat.compactionKeepMessages", 10));
    }

    private static int minTurns(boolean force) {
        return force ? Math.max(1, ConfigService.getInt("chat.compactionForcedMinTurns", 2))
                : Math.max(1, ConfigService.getInt("chat.compactionMinTurns", 10));
    }

    /** The last user row at an index from {@code from} to {@code to}, or -1. */
    private static int latestUserRow(List<Message> rows, int from, int to) {
        for (int i = Math.min(to, rows.size() - 1); i >= from; i--) {
            if (MessageRole.USER.value.equals(rows.get(i).role)) return i;
        }
        return -1;
    }

    /** The first user row at an index from {@code from} to {@code to}, or -1. */
    private static int earliestUserRow(List<Message> rows, int from, int to) {
        for (int i = from; i <= Math.min(to, rows.size() - 1); i++) {
            if (MessageRole.USER.value.equals(rows.get(i).role)) return i;
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
        return renderTurns(snaps, Integer.MAX_VALUE);
    }

    /**
     * {@link #renderTurns(List)} with each tool result, and each tool call's arguments, longer than {@code toolChars}
     * cut to that many characters with the full length noted; user and assistant text stays whole.
     */
    public static String renderTurns(List<MessageSnapshot> snaps, int toolChars) {
        var sb = new StringBuilder(snaps.size() * 200);
        for (var s : snaps) {
            var role = s.role() == null ? "unknown" : s.role().toUpperCase();
            sb.append("[").append(role).append("]");
            if (s.content() != null && !s.content().isBlank()) {
                var text = s.content().strip();
                sb.append(' ').append(MessageRole.TOOL.value.equals(s.role()) ? shortened(text, toolChars) : text);
            }
            if (s.toolCalls() != null && !s.toolCalls().isBlank()) {
                sb.append("\n[TOOL_CALLS] ").append(withShortenedArguments(s.toolCalls().strip(), toolChars));
            }
            if (s.toolResults() != null && !s.toolResults().isBlank()) {
                sb.append("\n[TOOL_RESULT_ID] ").append(s.toolResults().strip());
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /** {@code text} cut to its first {@code limit} characters with its length noted, or whole when it fits. */
    private static String shortened(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = limit > 0 && Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end) + "… [first " + end + " of " + text.length() + " characters]";
    }

    // Message.toolCalls holds one call object as MessageHydrator.parseToolCalls reads it; an array is shortened alike.
    private static String withShortenedArguments(String toolCalls, int limit) {
        if (toolCalls.length() <= limit) return toolCalls;
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(toolCalls);
        } catch (RuntimeException _) {
            return shortened(toolCalls, limit);
        }
        if (!parsed.isJsonObject() && !parsed.isJsonArray()) return shortened(toolCalls, limit);
        boolean changed = false;
        for (var call : parsed.isJsonArray() ? parsed.getAsJsonArray() : List.of(parsed)) {
            if (call instanceof JsonObject c && c.get("function") instanceof JsonObject function
                    && function.get("arguments") instanceof JsonPrimitive arguments && arguments.isString()
                    && arguments.getAsString().length() > limit) {
                function.addProperty("arguments", shortened(arguments.getAsString(), limit));
                changed = true;
            }
        }
        return changed ? parsed.toString() : toolCalls;
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
    private static final String SECTIONS = """
            Write these five sections, in this order, each under its heading as bullet points, with "None." under a heading that has nothing to record:

            ### Goals and constraints
            What the user wants, and every constraint, preference and requirement they set.

            ### Decisions
            What the user or the agent decided, with the reason when one was given.

            ### Identifiers and facts
            The names, ids, file paths, URLs, commit hashes, numbers and other values the work relies on, copied exactly as written: never shorten, round or reconstruct one.

            ### Open questions and errors
            Questions still unanswered, errors not yet resolved, and choices still waiting on the user.

            ### Next steps
            What remains to do, starting with the user's latest request and the step in progress.
            """;

    // JF-161: a segment covers its own turns; finished work is kept short rather than left out, which a long chat needs later.
    private static final String SUMMARIZATION_INSTRUCTIONS = """
            The conversation so far is being compacted: its older turns will be dropped, and the summary you write now is what the agent will read in their place when the work continues. Do not answer the user and do not call a tool; reply with the summary alone.

            Summarize only the turns of this conversation. Summaries of earlier turns, under [Prior conversation summary …], stay as they are and the agent reads yours after them, so do not repeat what they say.

            """ + SECTIONS + """

            Keep every file path, URL, commit hash, name, number and open item the turns name, unless a later turn supersedes it. Finished work may shrink to one line, but keep its identifiers. Where turns disagree, the latest update wins. Mark anything that no tool result or user message confirmed as (unverified). Leave out greetings and small talk. Write as much as the turns need. No preface and no sign-off.
            """;

    private static final String MERGE_INSTRUCTIONS = """
            Several summaries of one conversation are being merged into one. Each covers the turns between the dates above it, oldest first, and the summary you write now is what the agent will read in their place. Do not answer the user and do not call a tool; reply with the summary alone.

            Keep every file path, URL, commit hash, name, number and open item they record, copied exactly as written, unless a later summary supersedes it. Finished work may shrink to one line, but keep its identifiers.

            """ + SECTIONS + """

            Where the summaries disagree, the later one wins. Keep each (unverified) mark. Write as much as the summaries need. No preface and no sign-off.
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
