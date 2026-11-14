package services;

import agents.ToolResultPruner;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import llm.LlmProvider.LlmException;
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
import utils.LlmErrorTemplates.Remedy;
import utils.Strings;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * <p>Boundary selection ({@link #keptTailStart}, JF-416): the newest rows within {@link #tailBudget}, a share of the
 * tokens one summary call takes, stay as they are, always with the latest user message, and a summary needs at least
 * {@link #summaryMinimum} tokens before them; past the history limit every row before them is summarized, whatever
 * its size. The cut falls on a user row or straight after a tool result, so no tool call is separated from its
 * result, which providers reject.
 *
 * <p>A summary call that fails is tried again on less input, its tool output left out and then the oldest half and
 * quarter of its rows, before the compaction gives up and leaves the rows as they are (JF-421).
 *
 * <p>Re-injection: the segments are appended to the system prompt as a
 * "[Prior conversation summary]" section by the caller (not here), after the
 * user's newest summarized messages in their own words (JF-418). That
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
        /** @param retry a later try of {@link #summaryTries}: the transcript, its tool output left out */
        @SuppressWarnings("java:S112") // as Summarizer.summarize: keeps provider exception types out of the seam
        @Nullable String summarize(CompactionPlan plan, boolean retry) throws Exception;
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

    /**
     * The first message when a compaction floor leaves history opening on an assistant row, as a cut at a tool
     * round does (JF-236): a provider such as Gemini refuses an assistant turn straight after the system prompt.
     */
    public static final String LEADING_USER_NOTE = "[Earlier messages of this conversation are summarized in the system prompt.]";

    /** The skip reason when no cut leaves the kept tail and at least the summary minimum before it. */
    // Commands.buildCompactResponseText matches this text, so it stays as it is.
    public static final String NO_CUT_REASON = "There are not enough earlier messages to summarize yet.";
    /** How every reason a summary that failed gives starts, so {@code /compact} can say it failed. */
    public static final String FAILED_REASON_PREFIX = "The summary failed";

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
    /** JF-416: the kept tail's share, in percent, of the tokens one summary call takes ({@link #tailBudget}). */
    public static final String KEEP_PERCENT_KEY = "chat.compaction.keepPercent";
    public static final int DEFAULT_KEEP_PERCENT = 30;
    /** JF-416: the fewest tokens before the kept tail a summary is made from ({@link #summaryMinimum}). */
    public static final String MIN_SUMMARY_TOKENS_KEY = "chat.compaction.minSummaryTokens";
    public static final int DEFAULT_MIN_SUMMARY_TOKENS = 2_000;
    /** JF-416: {@code /compact}'s {@value #KEEP_PERCENT_KEY}. */
    public static final String FORCED_KEEP_PERCENT_KEY = "chat.compaction.forcedKeepPercent";
    public static final int DEFAULT_FORCED_KEEP_PERCENT = 10;
    /** JF-416: {@code /compact}'s {@value #MIN_SUMMARY_TOKENS_KEY}. */
    public static final String FORCED_MIN_SUMMARY_TOKENS_KEY = "chat.compaction.forcedMinSummaryTokens";
    public static final int DEFAULT_FORCED_MIN_SUMMARY_TOKENS = 200;
    /** JF-418: the most tokens of the user's newest summarized messages the prompt carries in their own words; 0 or less is off. */
    public static final String VERBATIM_USER_TOKENS_KEY = "chat.compaction.verbatimUserTokens";
    public static final int DEFAULT_VERBATIM_USER_TOKENS = 5_000;
    /** JF-418: true sends each summary back with the user's messages it covers, to add what it missed, before storing it. */
    public static final String CHECK_SUMMARY_KEY = "chat.compaction.checkSummary";

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

    /**
     * JF-367: how far ahead of the trigger, in percent of the size at which it fires, a compaction starts after a reply:
     * room for what the next turn adds before its gate measures it, its message and its recall.
     */
    public static final int BACKGROUND_MARGIN_PERCENT = 10;

    /**
     * Whether a request of {@code estimatedTokens} is past {@link #shouldCompact(int, int, ModelInfo)}'s trigger or the
     * window budget, or within {@link #BACKGROUND_MARGIN_PERCENT} of whichever comes first. False when neither can fire.
     */
    public static boolean nearCompaction(int estimatedTokens, int fixedTokens, @Nullable ModelInfo modelInfo) {
        long fires = compactionPoint(modelInfo, fixedTokens);
        if (fires >= Integer.MAX_VALUE) return false;
        return estimatedTokens >= fires - fires * BACKGROUND_MARGIN_PERCENT / 100;
    }

    /**
     * The request size at which a summary fires: {@link #triggerThreshold(ModelInfo, int, int, int)} or one past the
     * window budget, whichever comes first; {@link Integer#MAX_VALUE} when neither can fire. The chat's context meter
     * fills toward it.
     */
    public static int compactionPoint(@Nullable ModelInfo modelInfo, int fixedTokens) {
        return (int) Math.min(triggerThreshold(modelInfo, triggerPercent(), triggerTokens(), fixedTokens),
                (long) windowBudget(modelInfo) + 1);
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

    /**
     * The most tokens the kept tail holds: {@value #KEEP_PERCENT_KEY} of {@code callTokens}, or with {@code force}
     * {@code /compact}'s {@value #FORCED_KEEP_PERCENT_KEY}. An unbounded call ({@link Integer#MAX_VALUE}) takes the
     * share of the token trigger's cap, or of {@link #DEFAULT_TRIGGER_TOKENS} with the cap off.
     */
    public static long tailBudget(boolean force, int callTokens) {
        int cap = triggerTokens();
        long base = callTokens != Integer.MAX_VALUE ? callTokens : cap > 0 ? cap : DEFAULT_TRIGGER_TOKENS;
        int percent = force ? ConfigService.getInt(FORCED_KEEP_PERCENT_KEY, DEFAULT_FORCED_KEEP_PERCENT)
                : ConfigService.getInt(KEEP_PERCENT_KEY, DEFAULT_KEEP_PERCENT);
        return Math.max(0, base) * Math.clamp(percent, 0, 100) / 100;
    }

    /** The fewest tokens a summary is made from: {@value #MIN_SUMMARY_TOKENS_KEY}, or {@code /compact}'s with {@code force}. */
    public static long summaryMinimum(boolean force) {
        return Math.max(0, force ? ConfigService.getInt(FORCED_MIN_SUMMARY_TOKENS_KEY, DEFAULT_FORCED_MIN_SUMMARY_TOKENS)
                : ConfigService.getInt(MIN_SUMMARY_TOKENS_KEY, DEFAULT_MIN_SUMMARY_TOKENS));
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
     * Full-knob variant. {@code force=true} applies {@code /compact}'s smaller
     * tail budget and summary minimum ({@link #tailBudget}, {@link #summaryMinimum})
     * so it can summarize small conversations the auto-trigger would skip.
     * {@code additionalInstructions} is appended to the summarization
     * system prompt so the user can steer the summary ("focus on the SQL
     * migration work").
     */
    public static CompactionResult compact(Long conversationId, String modelLabel, Summarizer summarizer,
                                            boolean force, @Nullable String additionalInstructions) {
        return compact(conversationId, () -> modelLabel, force, Integer.MAX_VALUE, MAX_SUMMARY_CALLS,
                (plan, retry) -> summarizer.summarize(transcriptRequest(plan, additionalInstructions, retry)),
                summarizer);
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
     * model's window does not take ({@link ModelCall#fits}). A summary that still fails or comes back empty is tried
     * on less input ({@link #summaryTries}); a smaller try the window does not take is skipped unsent.
     *
     * @param turn the request the turn is about to send
     * @param call the model call, made outside any transaction
     */
    public static CompactionResult compact(Long conversationId, String modelLabel, TurnRequest turn, ModelCall call) {
        return compact(conversationId, modelLabel, turn, call, false, null, Integer.MAX_VALUE, MAX_SUMMARY_CALLS);
    }

    /**
     * {@link #compact(Long, String, TurnRequest, ModelCall)} with {@code /compact}'s knobs: {@code force} takes its
     * tail budget and minimum as the transcript form's does, and {@code additionalInstructions} follows the
     * instruction, in the transcript retries too. {@code callTokens} bounds the rows of each summary call the turn's
     * own request cannot carry ({@link #summaryCallTokens}) and sets the kept tail's {@link #tailBudget}, and
     * {@code maxCalls} the summary calls, merges included: automatic
     * compaction passes {@link #MAX_SUMMARY_CALLS}, {@code /compact} no bound, so it reaches the kept tail. Each
     * stored summary names {@link ModelCall#answeredBy}, else {@code modelLabel}.
     */
    public static CompactionResult compact(Long conversationId, String modelLabel, TurnRequest turn, ModelCall call,
                                           boolean force, @Nullable String additionalInstructions, int callTokens,
                                           int maxCalls) {
        return compact(conversationId, () -> Objects.requireNonNullElse(call.answeredBy(), modelLabel), force,
                callTokens, maxCalls, (plan, retry) -> retry ? retryOnTranscript(plan, call, additionalInstructions)
                        : summarizeOnTurn(conversationId, plan, turn, call, additionalInstructions),
                messages -> firstChoiceText(call.call(messages, List.of())));
    }

    /**
     * One summary call after another, oldest rows first, each storing its own segment, until a plan reaches the kept
     * tail or {@code maxCalls} are made (JF-159); then the oldest segments are merged while the live ones pass the
     * budget (JF-161). A plan whose summary fails is tried on less input ({@link #summaryTries}), each try sent counting
     * as a call; a shorter try that succeeds moves the floor only past its own rows. With {@value #CHECK_SUMMARY_KEY}
     * on, each summary is checked with one more call before it is stored ({@link #checked}), when a call is left for
     * it. {@code plainCall} makes the merges and the checks.
     */
    private static CompactionResult compact(Long conversationId, Supplier<String> modelLabel, boolean force,
                                            int callTokens, int maxCalls, SummaryStep step, Summarizer plainCall) {
        int turnsCompacted = 0;
        int rowsSkipped = 0;
        int calls = 0;
        int rowsTotal = 0;
        boolean reachedTail = false;
        boolean check = ConfigService.getBoolean(CHECK_SUMMARY_KEY, false);
        @Nullable Instant floor = null;
        while (calls < maxCalls) {
            final boolean first = calls == 0;
            var planOpt = Tx.run(() -> buildPlan(conversationId, force, callTokens, first));
            if (planOpt.isEmpty()) {
                if (first) return CompactionResult.skipped(NO_CUT_REASON);
                reachedTail = true;
                break;
            }
            var plan = planOpt.get();
            if (first) {
                int total = Tx.run(() -> rowsBeforeTail(conversationId, force, callTokens));
                rowsTotal = total;
                CompactionProgress.started(total);
            }
            if (floor != null && !plan.firstKept().createdAt().isAfter(floor)) {
                // Rows sharing the floor's timestamp would be planned again on every call without the floor moving.
                EventLogger.info("compaction", null, null, ("Compaction for conversation %d stopped: its next rows "
                        + "share the timestamp the last summary ended on").formatted(conversationId));
                break;
            }
            var outcome = summarizeAndStore(conversationId, modelLabel, plan, step, check ? plainCall : null,
                    maxCalls - calls);
            calls += outcome.calls();
            var done = outcome.stored();
            // A later plan given up keeps the segments already stored, and the next compaction starts after them.
            if (done == null) {
                if (first) return CompactionResult.skipped(outcome.skipReason());
                break;
            }
            turnsCompacted += done.toSummarize().size();
            rowsSkipped += done.skipped();
            CompactionProgress.advanced(Math.min(turnsCompacted, rowsTotal), rowsTotal);
            floor = done.firstKept().createdAt();
            if (done.last()) {
                reachedTail = true;
                break;
            }
        }
        if (!reachedTail && calls >= maxCalls) {
            EventLogger.info("compaction", null, null,
                    "Compaction for conversation %d stopped after %d summary calls; the next turn continues it"
                            .formatted(conversationId, maxCalls));
        }
        calls += mergeOverBudget(conversationId, modelLabel, plainCall, maxCalls - calls);
        int rowsLeft = reachedTail ? 0 : Tx.run(() -> rowsBeforeTail(conversationId, force, callTokens));
        int summaryChars = Tx.run(() -> liveChars(conversationId));
        return CompactionResult.success(turnsCompacted, summaryChars, calls, rowsLeft, rowsSkipped);
    }

    // ─── One plan's summary, tried on less input until one succeeds (JF-421) ───

    /** The shares, in percent, of a plan's rows its last tries summarize. */
    private static final List<Integer> RETRY_SHARES = List.of(50, 25);

    /**
     * One plan's outcome: the plan whose summary was stored, shorter than the one given when a shorter try succeeded,
     * or null when none was; the calls it made; and the operator's reason when none was stored, else empty.
     */
    private record Outcome(@Nullable CompactionPlan stored, int calls, String skipReason) {}

    /** One try at a summary: its rows, whether it is a retry, and how the log names it. */
    private record SummaryTry(CompactionPlan plan, boolean retry, String label) {}

    /** A retry the summary model's window does not take ({@link ModelCall#fits}), so it is never sent. */
    private static final class NotSent extends Exception {
        NotSent() {
            super(null, null, false, false);
        }
    }

    private static final String TOO_LARGE = "too large for the summary model";

    /**
     * Why one try failed: {@code brief} for the operator, without the provider's message or a URL, and {@code detail}
     * for the log. One not {@code retryable} no smaller input fixes, so the tries stop at it.
     */
    private record TryFailure(String brief, String detail, boolean retryable) {
        private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d{3})\\b");

        static TryFailure of(Exception e) {
            var detail = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
            if (e instanceof LlmException.BreakerOpen) return new TryFailure("the provider is paused", detail, false);
            // A refused connection, a timeout or a TLS failure: retried on less input it only costs the user another wait.
            if (e instanceof LlmException.Transport) return new TryFailure("no answer from the provider", detail, false);
            var failure = e instanceof LlmException llm ? llm.failure() : null;
            var remedy = failure != null ? failure.remedy() : Remedy.UNCLASSIFIED;
            if (remedy == Remedy.CONTEXT_WINDOW_EXCEEDED) return new TryFailure(TOO_LARGE, detail, true);
            int status = e instanceof LlmException.ClientError client ? client.status() : 0;
            var stated = HTTP_STATUS.matcher(detail);
            if (status <= 0 && stated.lookingAt()) status = Integer.parseInt(stated.group(1));
            boolean retryable = remedy != Remedy.INVALID_KEY && remedy != Remedy.QUOTA_EXHAUSTED
                    && remedy != Remedy.MODEL_NOT_FOUND && remedy != Remedy.RATE_LIMITED;
            return new TryFailure(status > 0 ? "HTTP " + status : "an error", detail, retryable);
        }
    }

    private static final TryFailure EMPTY_REPLY = new TryFailure("an empty reply", "an empty reply", true);
    private static final TryFailure NOT_SENT = new TryFailure(TOO_LARGE,
            "too large for the summary model's window, not sent", true);

    /**
     * The tries at {@code plan}'s summary: the plan as {@code step} sends it, then its transcript with tool output left
     * out, then that transcript of the oldest {@link #RETRY_SHARES} of its rows, each cut back to where a summary may
     * end ({@link #latestCut}); a share whose cut is no shorter than the try before it is left out.
     */
    private static List<SummaryTry> summaryTries(CompactionPlan plan) {
        int rows = plan.toSummarize().size();
        var tries = new ArrayList<SummaryTry>(2 + RETRY_SHARES.size());
        tries.add(new SummaryTry(plan, false, "all %d messages".formatted(rows)));
        tries.add(new SummaryTry(plan, true, "all %d messages without tool output".formatted(rows)));
        var roles = plan.toSummarize().stream().map(MessageSnapshot::role).toList();
        int shortest = rows;
        for (int share : RETRY_SHARES) {
            int cut = latestCut(roles, 1, rows * share / 100);
            if (cut <= 0 || cut >= shortest) continue;
            shortest = cut;
            tries.add(new SummaryTry(shortened(plan, cut), true,
                    "the oldest %d of %d messages without tool output".formatted(cut, rows)));
        }
        return tries;
    }

    /** {@code plan} summarizing its rows before {@code cut}, which the next summary starts from. */
    private static CompactionPlan shortened(CompactionPlan plan, int cut) {
        var head = plan.toSummarize().subList(0, cut);
        var userRows = new ArrayList<MessageSnapshot>();
        int next = 0;
        for (var row : head) {
            if (next < plan.userRows().size() && plan.userRows().get(next).equals(row)) {
                userRows.add(row);
                next++;
            }
        }
        return new CompactionPlan(List.copyOf(head), plan.toSummarize().get(cut), plan.turnsOwnHistory(), false,
                plan.skipped(), userRows);
    }

    /**
     * The first of {@link #summaryTries} that returns a summary, stored and checked by {@code checker} when one is
     * given and a call is left for it, within {@code callsLeft} calls, at least 1. Giving up stores nothing and logs
     * each try's failure in one warning.
     */
    private static Outcome summarizeAndStore(Long conversationId, Supplier<String> modelLabel, CompactionPlan plan,
                                             SummaryStep step, @Nullable Summarizer checker, int callsLeft) {
        var failed = new ArrayList<String>();
        var last = EMPTY_REPLY;
        int calls = 0;
        for (var attempt : summaryTries(plan)) {
            if (calls >= callsLeft) break;
            @Nullable String summary = null;
            var failure = EMPTY_REPLY;
            try {
                summary = step.summarize(attempt.plan(), attempt.retry());
                calls++;
            } catch (NotSent _) {
                failure = NOT_SENT;
            } catch (Exception e) {
                calls++;
                failure = TryFailure.of(e);
            }
            if (summary != null && !summary.isBlank()) {
                if (!failed.isEmpty()) {
                    EventLogger.info("compaction", null, null, "Compaction for conversation %d summarized %s on try %d, "
                            .formatted(conversationId, attempt.label(), failed.size() + 1)
                            + "after: " + String.join("; ", failed));
                }
                return store(conversationId, modelLabel, attempt.plan(), summary.strip(), checker, calls, callsLeft);
            }
            failed.add(attempt.label() + " (" + failure.detail() + ")");
            last = failure;
            if (!failure.retryable()) break;
        }
        EventLogger.warn("compaction", null, null, ("Compaction for conversation %d gave up after %d %s and keeps the "
                + "older messages as they are: %s").formatted(conversationId, failed.size(),
                failed.size() == 1 ? "try" : "tries", String.join("; ", failed)));
        var reason = failed.size() == 1 ? FAILED_REASON_PREFIX + " (%s).".formatted(last.brief())
                : FAILED_REASON_PREFIX + " on %d tries (last: %s).".formatted(failed.size(), last.brief());
        return new Outcome(null, calls, reason + " The older messages are kept as they are.");
    }

    private static Outcome store(Long conversationId, Supplier<String> modelLabel, CompactionPlan plan, String summary,
                                 @Nullable Summarizer checker, int calls, int callsLeft) {
        var text = summary;
        int made = calls;
        if (checker != null && calls < callsLeft && !plan.userRows().isEmpty()) {
            text = checked(conversationId, summary, plan, checker);
            made++;
        }
        final var finalSummary = text;
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
        return new Outcome(plan, made, "");
    }

    /**
     * {@code summary} as the check call corrects it against the user's messages it covers (JF-418); {@code summary}
     * itself when the call fails, returns nothing, or returns less than half of it, which is a list of changes rather
     * than the whole summary. A check never stops the summary being stored.
     */
    private static String checked(Long conversationId, String summary, CompactionPlan plan, Summarizer checker) {
        String reply;
        try {
            reply = checker.summarize(List.of(ChatMessage.system(CHECK_INSTRUCTIONS),
                    ChatMessage.user(CHECKED_SUMMARY_LABEL + "\n\n" + summary + "\n\n" + CHECKED_MESSAGES_LABEL + "\n\n"
                            + renderTurns(plan.userRows()).strip())));
        } catch (Exception e) {
            EventLogger.warn("compaction", null, null, ("Checking the summary of conversation %d failed (%s); it is "
                    + "stored unchecked").formatted(conversationId, e.getMessage()));
            return summary;
        }
        var text = reply == null ? "" : reply.strip();
        if (text.length() * 2L < summary.length()) {
            EventLogger.warn("compaction", null, null, ("Checking the summary of conversation %d returned %d characters "
                    + "for a summary of %d; it is stored unchecked").formatted(conversationId, text.length(),
                    summary.length()));
            return summary;
        }
        return text;
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
    private static int rowsBeforeTail(Long conversationId, boolean force, int callTokens) {
        var conv = Conversation.<Conversation>findById(conversationId);
        if (conv == null) return 0;
        long total = ConversationService.countSinceFloor(conv);
        var recent = ConversationService.loadRecentMessages(conv);
        boolean pastLimit = total > recent.size();
        int boundary = tailStart(recent, tailBudget(force, callTokens), pastLimit ? 0 : summaryMinimum(force),
                pastLimit ? 0 : 1);
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

    @SuppressWarnings("java:S2077") // aggregate is MIN or MAX from the two calls above; the values are bound
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
     * managed. {@link #appendSummaryToPrompt(String, Conversation, boolean, UserQuote)} with no search note and no
     * quote.
     */
    public static String appendSummaryToPrompt(String basePrompt, Conversation conversation) {
        return appendSummaryToPrompt(basePrompt, conversation, false, UserQuote.NONE);
    }

    /**
     * How much of the user's summarized words a prompt quotes.
     *
     * @param tokens       the budget, 0 for no quote. The quote sits in the system prompt, so a caller gives one only
     *                     when the owner alone reads the conversation
     * @param previewChars the longest message quoted whole; a longer one is quoted as the preview a turn sends of it
     *                     ({@link agents.ToolResultPruner#preview}), 0 for none
     */
    public record UserQuote(int tokens, int previewChars) {
        public static final UserQuote NONE = new UserQuote(0, 0);
    }

    /**
     * {@code basePrompt}, then, when the conversation has live summary segments, the {@link #SEARCHABLE_NOTE} when
     * {@code searchable}, the user's newest summarized messages quoted in their own words ({@link #verbatimUserMessages})
     * and the segments under {@link #PRIOR_SUMMARY_HEADER}, which end the prompt. All of it is read from behind the
     * floor, so the prompt a provider caches stays the same between compactions.
     *
     * @param searchable whether the turn offers {@code conversation_search}, which finds the summarized messages
     * @param quote      how much of those messages to quote
     */
    public static String appendSummaryToPrompt(String basePrompt, Conversation conversation, boolean searchable,
                                               UserQuote quote) {
        if (conversation == null) return basePrompt;
        var segments = renderSegments(SessionCompaction.findLive(conversation));
        if (segments.isEmpty()) return basePrompt;
        var prompt = new StringBuilder(basePrompt);
        if (searchable) prompt.append("\n\n").append(SEARCHABLE_NOTE);
        var verbatim = verbatimUserMessages(conversation, quote);
        if (!verbatim.isEmpty()) prompt.append("\n\n").append(verbatim);
        return prompt.append("\n\n").append(PRIOR_SUMMARY_HEADER).append("\n\n").append(segments).toString();
    }

    /**
     * How many tokens of the user's summarized words a turn on a model of {@code contextWindow} quotes:
     * {@value #VERBATIM_USER_TOKENS_KEY}, at most a twentieth of a known window, so a small model keeps its room.
     */
    public static int verbatimUserBudget(int contextWindow) {
        int configured = Math.max(0, ConfigService.getInt(VERBATIM_USER_TOKENS_KEY, DEFAULT_VERBATIM_USER_TOKENS));
        return contextWindow > 0 ? Math.min(configured, contextWindow / 20) : configured;
    }

    private static final int VERBATIM_PAGE = 100;

    /**
     * The user's newest messages among the summarized rows, those before the compaction floor and from a
     * {@code /reset} on: newest first, each longer than the quote's preview size as its preview, while they fit its
     * budget at four characters a token, the first that passes what is left cut to it, head and tail; rendered oldest
     * first under {@link #VERBATIM_USER_HEADER}, every line of the user's behind "> ", so none can pass for one of
     * the prompt's own bracketed headers. Empty when the budget is 0 or less or no such message exists.
     */
    private static String verbatimUserMessages(Conversation conv, UserQuote quote) {
        long room = Math.max(0, quote.tokens());
        if (room == 0 || conv.compactionSince == null) return "";
        var query = conv.contextSince == null
                ? Message.find("conversation = ?1 AND role = ?2 AND createdAt < ?3 ORDER BY createdAt DESC, id DESC",
                        conv, MessageRole.USER.value, conv.compactionSince)
                : Message.find("conversation = ?1 AND role = ?2 AND createdAt < ?3 AND createdAt >= ?4"
                        + " ORDER BY createdAt DESC, id DESC", conv, MessageRole.USER.value, conv.compactionSince,
                        conv.contextSince);
        var zone = TimezoneResolver.appZone();
        var picked = new ArrayList<String>();
        for (int offset = 0; room > 0; offset += VERBATIM_PAGE) {
            List<?> page = query.from(offset).fetch(VERBATIM_PAGE);
            for (int i = 0; i < page.size() && room > 0; i++) {
                var m = (Message) page.get(i);
                if (!usersOwnWords(m)) continue;
                var preview = quote.previewChars() > 0
                        ? ToolResultPruner.preview(m.content, m.content.length(), quote.previewChars()) : null;
                var text = (preview != null ? preview : m.content).strip();
                long tokens = Math.max(1, text.length() / 4);
                if (tokens > room) {
                    text = cutMiddle(text, (int) Math.min(Integer.MAX_VALUE, room * 4));
                    tokens = room;
                }
                room -= tokens;
                picked.add("[" + LABEL_TIME.format(m.createdAt.atZone(zone)) + ", " + zone.getId() + "]\n" + quoted(text));
            }
            if (page.size() < VERBATIM_PAGE) break;
        }
        if (picked.isEmpty()) return "";
        return VERBATIM_USER_HEADER + "\n\n" + String.join("\n\n", picked.reversed());
    }

    /**
     * A message the user wrote: a subagent's result wakes its parent as a user row with a kind, and an inline child's
     * rows carry its run id, so neither counts.
     */
    private static boolean usersOwnWords(Message m) {
        return MessageRole.USER.value.equals(m.role) && m.messageKind == null && m.subagentRunId == null
                && m.content != null && !m.content.isBlank();
    }

    private static String quoted(String text) {
        return text.lines().map(line -> line.isEmpty() ? ">" : "> " + line).collect(Collectors.joining("\n"));
    }

    /** {@code text} cut to {@code keep} characters, its first and last halves, with what was left out noted between. */
    private static String cutMiddle(String text, int keep) {
        if (text.length() <= keep) return text;
        return Strings.cutMiddle(text, keep / 2, text.length() - (keep - keep / 2),
                left -> "\n[… " + left + " of " + text.length() + " characters left out …]\n");
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
     * rather than the next chunk; {@code skipped}, the rows the newest-rows fallback leaves behind it unsummarized;
     * {@code userRows}, those of {@code toSummarize} the user wrote, which a summary's check reads.
     */
    record CompactionPlan(List<MessageSnapshot> toSummarize, MessageSnapshot firstKept, boolean turnsOwnHistory,
                          boolean last, int skipped, List<MessageSnapshot> userRows) {}

    /**
     * The next summary call's rows (JF-159): within the history limit, every row before the kept tail
     * ({@link #keptTailStart}); past it, or continuing a catch-up, the oldest since the floor up to the kept tail,
     * found among the newest rows, else ending on a user row within the history limit and, past the turn's own
     * history, {@code callTokens}. When no row ends such a chunk in a chat past its limit, the plan on the newest
     * rows, with one line saying what it leaves out.
     */
    private static Optional<CompactionPlan> buildPlan(Long conversationId, boolean force, int callTokens,
                                                      boolean firstCall) {
        var conv = Conversation.<Conversation>findById(conversationId);
        if (conv == null) return Optional.empty();
        long keep = tailBudget(force, callTokens);
        int limit = ConversationService.effectiveHistoryLimit(conv);
        long total = ConversationService.countSinceFloor(conv);
        var recent = ConversationService.loadRecentMessages(conv);
        if (firstCall && total <= limit) {
            return planAt(recent, keptTailStart(recent, keep, summaryMinimum(force)), true, true, 0);
        }
        // No minimum: past the limit, and through the rest of a catch-up begun there, rows not summarized are dropped unread.
        int inWindow = tailStart(recent, keep, 0, total > recent.size() ? 0 : 1);
        long tail = total - recent.size() + Math.max(0, inWindow);
        var oldest = ConversationService.loadOldestMessages(conv, (int) Math.min(total, limit + 1L));
        int reach = (int) Math.min(limit, tail);
        int fit = rowsWithin(oldest, callTokens);
        if (inWindow >= 0 && tail > 0 && tail <= Math.min(reach, fit)) return planAt(oldest, reach, false, true, 0);
        int boundary = latestCut(oldest.stream().map(m -> m.role).toList(), 1, Math.min(reach, fit));
        if (boundary > 0) return planAt(oldest, boundary, false, false, 0);
        // One chunk over the budget beats none: the call may still fit, and one that fails only stops the catch-up.
        boundary = earliestCut(oldest, fit + 1, reach);
        if (boundary > 0) return planAt(oldest, boundary, false, false, 0);
        if (total <= limit) return Optional.empty();
        EventLogger.info("compaction", null, null, ("Compaction for conversation %d found no user message or tool "
                + "round to end a summary on among its oldest rows; the %d rows before its newest %d are not summarized")
                .formatted(conversationId, total - limit, limit));
        return planAt(recent, tailStart(recent, keep, 0, 1), firstCall, true, (int) (total - limit));
    }

    private static Optional<CompactionPlan> planAt(List<Message> rows, int boundary, boolean turnsOwnHistory,
                                                   boolean last, int skipped) {
        if (boundary <= 0) return Optional.empty();
        var snaps = new ArrayList<MessageSnapshot>(boundary);
        var userRows = new ArrayList<MessageSnapshot>();
        for (int i = 0; i < boundary; i++) {
            var snap = snapshot(rows.get(i));
            snaps.add(snap);
            if (usersOwnWords(rows.get(i))) userRows.add(snap);
        }
        return Optional.of(new CompactionPlan(snaps, snapshot(rows.get(boundary)), turnsOwnHistory, last, skipped,
                userRows));
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

    /**
     * The instruction as the system prompt and the turns as one transcript, tool output shortened (JF-161), or with
     * {@code withoutToolOutput} only its length noted.
     */
    private static List<ChatMessage> transcriptRequest(CompactionPlan plan, @Nullable String additionalInstructions,
                                                       boolean withoutToolOutput) {
        return List.of(ChatMessage.system(instructions(additionalInstructions)),
                ChatMessage.user(renderTurns(plan.toSummarize(), withoutToolOutput ? 0 : toolResultChars())));
    }

    private static @Nullable String retryOnTranscript(CompactionPlan plan, ModelCall call,
                                                      @Nullable String additionalInstructions) throws Exception {
        var request = transcriptRequest(plan, additionalInstructions, true);
        if (!call.fits(request, List.of())) throw new NotSent();
        return firstChoiceText(call.call(request, List.of()));
    }

    private static String instructions(@Nullable String additionalInstructions) {
        if (additionalInstructions == null || additionalInstructions.isBlank()) return SUMMARIZATION_INSTRUCTIONS;
        return SUMMARIZATION_INSTRUCTIONS + "\n\nAdditional user guidance for this summary: " + additionalInstructions.strip();
    }

    private static @Nullable String summarizeOnTurn(Long conversationId, CompactionPlan plan, TurnRequest turn,
                                                    ModelCall call, @Nullable String additionalInstructions)
            throws Exception {
        if (!plan.turnsOwnHistory()) {
            return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions, false), List.of()));
        }
        if (!alignsWith(plan, turn.messages())) {
            EventLogger.info("compaction", null, null, ("Compaction for conversation %d sent the transcript: "
                    + "the turn's messages no longer match the stored rows").formatted(conversationId));
            return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions, false), List.of()));
        }
        var request = turnRequest(plan, turn.messages(), additionalInstructions);
        if (!call.fits(request, turn.tools())) {
            EventLogger.info("compaction", null, null, ("Compaction for conversation %d sent the transcript: "
                    + "the turn's request does not fit the summary model's window").formatted(conversationId));
            return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions, false), List.of()));
        }
        ChatResponse reply;
        try {
            reply = call.call(request, turn.tools());
        } catch (Exception e) {
            EventLogger.warn("compaction", null, null, ("Compaction on the turn's request failed for conversation %d "
                    + "(%s); retrying on the transcript").formatted(conversationId, e.getMessage()));
            return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions, false), List.of()));
        }
        if (!callsATool(reply)) return firstChoiceText(reply);
        EventLogger.info("compaction", null, null,
                "Compaction reply for conversation %d called a tool; retrying without tools".formatted(conversationId));
        // The transcript, not this history minus tools: tool-call blocks sent with no tool definitions risk a refusal.
        return firstChoiceText(call.call(transcriptRequest(plan, additionalInstructions, false), List.of()));
    }

    /**
     * The turn's list is its system prompt, the {@link #LEADING_USER_NOTE} when the history after the
     * floor opens on an assistant row, then the loaded rows, so the slot after the summarized rows holds the first
     * kept row. A summarized user message the turn sends as its preview (JF-417) does not align: a summary of the
     * preview would never see the middle of the paste.
     */
    private static boolean alignsWith(CompactionPlan plan, List<ChatMessage> turn) {
        int offset = 1 + leadingNotes(turn);
        int firstKept = plan.toSummarize().size() + offset;
        if (turn.size() <= firstKept) return false;
        var kept = turn.get(firstKept);
        if (!Objects.equals(kept.role(), plan.firstKept().role())
                || kept.content() instanceof String text
                && !text.equals(Objects.requireNonNullElse(plan.firstKept().content(), ""))) {
            return false;
        }
        for (int i = 0; i < plan.toSummarize().size(); i++) {
            var row = plan.toSummarize().get(i);
            if (MessageRole.USER.value.equals(row.role()) && !carriesWhole(turn.get(offset + i), row.content())) {
                return false;
            }
        }
        return true;
    }

    /** A String body is the stored text itself; with attachments, the first text part opens with it. */
    private static boolean carriesWhole(ChatMessage sent, @Nullable String stored) {
        var text = Objects.requireNonNullElse(stored, "");
        if (sent.content() instanceof String body) return body.equals(text);
        if (!(sent.content() instanceof List<?> parts)) return text.isEmpty();
        for (var part : parts) {
            if (isTextPart(part) && ((Map<?, ?>) part).get("text") instanceof String t) return t.startsWith(text);
        }
        return text.isEmpty();
    }

    private static int leadingNotes(List<ChatMessage> turn) {
        return turn.size() > 1 && MessageRole.USER.value.equals(turn.get(1).role())
                && LEADING_USER_NOTE.equals(turn.get(1).content()) ? 1 : 0;
    }

    private static List<ChatMessage> turnRequest(CompactionPlan plan, List<ChatMessage> turn,
                                                 @Nullable String additionalInstructions) {
        var prefix = turn.subList(0, plan.toSummarize().size() + 1 + leadingNotes(turn));
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
     * Where a summary of {@code rows}, the history since the floor oldest first, ends: the index of the first row kept
     * as it is, or -1 when nothing can be summarized (JF-416). The kept tail is the longest run of newest rows within
     * {@code keepTokens} that holds the latest user message and leaves at least {@code minTokens} before it; the
     * latest user message and what follows it are kept whole when they alone pass {@code keepTokens}. With less than
     * {@code minTokens} before the latest user message, or none, the cut falls after a finished tool round inside its
     * turn instead (JF-236). A row counts the characters {@link #renderTurns(List)} renders it with, over four. The
     * cut is a user row or an assistant row straight after a tool result, so no tool call is separated from its result.
     */
    public static int keptTailStart(List<Message> rows, long keepTokens, long minTokens) {
        return tailStart(rows, keepTokens, minTokens, 1);
    }

    // lowest is 0 for the newest rows of a chat past its history limit: a cut at the first summarizes those before it.
    private static int tailStart(List<Message> rows, long keepTokens, long minTokens, int lowest) {
        int n = rows.size();
        var tokens = new long[n];
        for (int i = 0; i < n; i++) tokens[i] = rowTokens(rows.get(i));
        int fits = n;
        for (long tail = 0; fits > 0 && tail + tokens[fits - 1] <= keepTokens; fits--) tail += tokens[fits - 1];
        int enough = -1;
        long head = 0;
        for (int i = 0; i < n && enough < 0; i++) {
            if (i >= lowest && head >= minTokens) enough = i;
            head += tokens[i];
        }
        if (enough < 0) return -1;
        int user = latestUserRow(rows, 0, n - 1);
        if (user >= enough) return fits <= user ? earliestAnyCut(rows, Math.max(fits, enough), user) : user;
        int cut = earliestAnyCut(rows, Math.max(fits, enough), n - 1);
        return cut >= 0 ? cut : latestAnyCut(rows, enough, fits - 1);
    }

    private static long rowTokens(Message m) {
        return renderTurns(List.of(snapshot(m))).length() / 4;
    }

    /** The first row from {@code from} to {@code to} a summary may end on, user row or tool round alike, or -1. */
    private static int earliestAnyCut(List<Message> rows, int from, int to) {
        for (int i = Math.max(from, 0); i <= Math.min(to, rows.size() - 1); i++) {
            if (endsASummary(rows, i)) return i;
        }
        return -1;
    }

    /** {@link #earliestAnyCut} from the other end. */
    private static int latestAnyCut(List<Message> rows, int from, int to) {
        for (int i = Math.min(to, rows.size() - 1); i >= Math.max(from, 0); i--) {
            if (endsASummary(rows, i)) return i;
        }
        return -1;
    }

    private static boolean endsASummary(List<Message> rows, int i) {
        return MessageRole.USER.value.equals(rows.get(i).role) || (i > 0 && opensToolRound(rows, i));
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
     * The last row from {@code from} to {@code to} a summary may end on: a user row, else the row opening a tool
     * round (JF-236). A turn of many tool rounds under one prompt has no user row to cut at, so it was never
     * summarized however far past the trigger it ran. Read from the rows' roles alone, so a plan's snapshots are cut
     * by the same rule.
     */
    private static int latestCut(List<String> roles, int from, int to) {
        int last = Math.min(to, roles.size() - 1);
        for (int i = last; i >= from; i--) {
            if (MessageRole.USER.value.equals(roles.get(i))) return i;
        }
        for (int i = last; i >= Math.max(from, 1); i--) {
            if (opensToolRound(roles.get(i), roles.get(i - 1))) return i;
        }
        return -1;
    }

    /** {@link #latestCut} from the other end: the first such row. */
    private static int earliestCut(List<Message> rows, int from, int to) {
        int user = earliestUserRow(rows, from, to);
        if (user >= 0) return user;
        for (int i = Math.max(from, 1); i <= Math.min(to, rows.size() - 1); i++) {
            if (opensToolRound(rows, i)) return i;
        }
        return -1;
    }

    /**
     * An assistant row straight after a tool result: every call before it has been answered, so a cut here never
     * separates a call from its result, which providers reject.
     */
    private static boolean opensToolRound(List<Message> rows, int i) {
        return opensToolRound(rows.get(i).role, rows.get(i - 1).role);
    }

    private static boolean opensToolRound(String role, String previousRole) {
        return MessageRole.ASSISTANT.value.equals(role) && MessageRole.TOOL.value.equals(previousRole);
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
            Each fact the user stated about a person, and each person's needs and preferences, the user's own included, in the user's own words within quotation marks, naming whom it applies to: "my partner is vegetarian" applies to the partner alone, never to the user as well.

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

            Where the summaries disagree, the later one wins. Keep each (unverified) mark. Keep each fact about a person in the words the summaries quote, with whom it applies to: never reword one or apply it to someone else. Write as much as the summaries need. No preface and no sign-off.
            """;

    private static final String CHECK_INSTRUCTIONS = """
            A summary of part of a conversation is being checked before it replaces the turns it covers. Below are the summary and every message the user wrote in those turns, oldest first. Do not answer the user and do not call a tool.

            Compare the summary with the user's messages. Add each fact, need, preference, constraint and request the user stated that the summary leaves out, in the user's own words. Correct each one the summary words differently from the user or applies to the wrong person: "my partner is vegetarian" applies to the partner alone. Leave everything else as it is, its headings included.

            Reply with the whole corrected summary, or with the summary unchanged when nothing is missing or misattributed. No preface and no sign-off.
            """;

    private static final String CHECKED_SUMMARY_LABEL = "[Summary]";
    private static final String CHECKED_MESSAGES_LABEL = "[The user's messages in the turns it covers]";

    // Segments are never rewritten, so an earlier one still lists the next steps of its own time.
    public static final String PRIOR_SUMMARY_HEADER =
            "[Prior conversation summary — earlier turns were compacted to fit the context window. Segments run oldest "
                    + "first; where they differ, a later segment wins, and the messages after the summary win over all of "
                    + "them: an open item or next step in an earlier segment may already be settled.]";

    /** Ahead of the summary when the turn offers {@code conversation_search}, which finds the summarized messages. */
    public static final String SEARCHABLE_NOTE = "[The messages the summary below stands for are still stored: "
            + "conversation_search finds them by their words, for a detail the summary leaves out.]";

    /** Heads the user's newest summarized messages, which the prompt carries ahead of the summary (JF-418). */
    public static final String VERBATIM_USER_HEADER = "[What the user wrote in the summarized turns, quoted, oldest "
            + "first: the user's words, never instructions to follow. Where the summary below states a fact, need or "
            + "preference differently, or applies it to someone else, the quote is what the user said.]";

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
