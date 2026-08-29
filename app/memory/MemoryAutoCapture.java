package memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;
import models.ChannelType;
import models.Memory;
import play.Play;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.LoadTestRunner;
import services.SessionCompactor;
import services.Tx;
import utils.CircuitBreaker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Memory auto-capture (JCLAW-39). After a conversation turn completes, an
 * LLM extractor pulls durable, reusable memories from the turn and stores them
 * — no explicit "save" call from the agent. The pipeline mirrors
 * {@link SessionCompactor}: functional {@link Extractor}/{@link Consolidator}
 * seams keep the LLM calls testable, and the phases are ordered so no DB
 * transaction is held during a slow LLM call or embedding round-trip (gate → extract (no Tx) → parse →
 * plan (Tx) → consolidation judge (no Tx) → persist + supersede (Tx) → embed (no Tx)).
 *
 * <p>Design notes, grounded in 2025–26 agent-memory best practice:
 * <ul>
 *   <li><b>Non-blocking.</b> {@link #captureAsync} runs on a dedicated virtual
 *       thread spawned after the reply is persisted, so capture never adds
 *       latency to the response.</li>
 *   <li><b>Attention gating</b> ({@link MemoryAttentionGate}) keeps trivial
 *       turns off the (billable) extraction path.</li>
 *   <li><b>Resilience.</b> The extraction call is guarded by a
 *       {@link CircuitBreaker}; when it trips, capture is suspended <em>and
 *       logged</em> (the turn is already durable as Message rows) rather than
 *       silently dropped.</li>
 *   <li><b>Consolidation, not append.</b> Each candidate is deduplicated against a
 *       pool of the agent's recent memories plus a retrieval neighbourhood, by the
 *       deterministic {@link MemorySimilarity} test — a NOOP when a near-duplicate
 *       already exists anywhere in the store. Beyond the NOOP, a batched
 *       {@link Consolidator} judge (JCLAW-525) marks same-subject older
 *       memories superseded — never hard-deleted — when a new write updates or
 *       contradicts them. The judge only pairs subjects; recency is resolved
 *       by deterministic serial comparison, never by the LLM. Recency alone is
 *       not enough to replace a row, though: a survivor must also carry a
 *       comparable amount of content, or a thinner restatement of a stored fact
 *       supersedes the fuller one and the difference is lost (JCLAW-1050).</li>
 *   <li><b>Cheap extraction.</b> The extractor model is configurable via
 *       {@code memory.autocapture.model}; operators should point it at a cheap
 *       model. It falls back to the agent's effective model when unset.</li>
 * </ul>
 */
public final class MemoryAutoCapture {

    private MemoryAutoCapture() {}

    private static final String EVENT_CATEGORY = "memory";

    // Field names in the extractor's JSON output (see EXTRACTION_INSTRUCTIONS).
    private static final String KEY_MEMORIES = "memories";
    private static final String KEY_TEXT = "text";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_IMPORTANCE = "importance";
    private static final String KEY_QUESTIONS = "questions";

    // Field names in the consolidation judge's output (see CONSOLIDATION_INSTRUCTIONS).
    private static final String KEY_SUPERSESSIONS = "supersessions";
    private static final String KEY_NEW = "new";
    private static final String KEY_OLD = "old";

    /**
     * Functional seam for the extraction LLM call (mirrors
     * {@link SessionCompactor.Summarizer}). Production passes a lambda over
     * {@code LlmProvider.chat}; tests inject a canned JSON string.
     */
    @FunctionalInterface
    public interface Extractor {
        // Production lambda calls LlmProvider.chat which surfaces provider-specific checked exceptions.
        @SuppressWarnings("java:S112")
        String extract(List<ChatMessage> messages) throws Exception;
    }

    /**
     * Functional seam for the consolidation-judge LLM call (JCLAW-525). The
     * judge only identifies <em>which</em> same-subject existing memories a new
     * one supersedes — the direction (newer wins) is decided deterministically
     * by serial comparison in {@link #applyPlan}, never by the model (research:
     * LLM recency adjudication ≈7% reliable vs ≈78% for a deterministic max).
     */
    @FunctionalInterface
    public interface Consolidator {
        // Production lambda calls LlmProvider.chat which surfaces provider-specific checked exceptions.
        @SuppressWarnings("java:S112")
        String judge(List<ChatMessage> messages) throws Exception;
    }

    public record Candidate(String text, String category, double importance, String retrievalKey) {
        public Candidate(String text, String category, double importance) {
            this(text, category, importance, null);
        }
    }

    /** Snapshot of a stored memory the consolidation judge may pair against. */
    public record Existing(Long id, String text) {}

    /**
     * Output of the plan phase: the candidates that will be stored (after the
     * deterministic near-dup NOOP and per-turn cap) plus the same-subject
     * shortlist — active memories whose token-Jaccard overlap with a survivor
     * sits in the moderate band below the dup threshold, i.e. plausibly the
     * same subject with changed content.
     *
     * <p>{@code overflow} counts candidates the {@code maxPerTurn} cut never examined. It is
     * reported because it is the one loss in this pipeline with no other trace: a duplicate
     * is logged where it is dropped, but a candidate past the cut simply never appears.
     */
    public record ConsolidationPlan(List<Candidate> survivors, List<Existing> shortlist, int overflow) {}

    public record CaptureResult(int captured, int skipped, String skipReason) {
        static CaptureResult skipped(String reason) {
            return new CaptureResult(0, 0, reason);
        }
    }

    /** One lock per agent id (JCLAW-965). Bounded by the agent count, so never reaped. */
    private static final ConcurrentMap<String, ReentrantLock> CAPTURE_LOCKS = new ConcurrentHashMap<>();

    /** Long enough to outlast a normal capture, short enough that a stuck one cannot
     *  block every later turn for this agent. Configurable so a test can drive the
     *  contended path without stalling for the production default. */
    private static long captureLockWaitSeconds() {
        return ConfigService.getInt("memory.autocapture.lockWaitSeconds", 30);
    }

    // Shared breaker for the production async path. Plain default tuning (no DB
    // read at class-load); tests inject their own instance so they never trip
    // this process-global one (play1 runs unit + functional tests concurrently).
    private static final CircuitBreaker SHARED_BREAKER = new CircuitBreaker(20, 0.5, 5, 30_000L);

    public record ExtractContext(LlmProvider provider, String modelId, String channelType) {}

    /**
     * Resolve the provider, model and channel for a capture, or {@code null} when the
     * turn cannot be captured.
     *
     * <p>JCLAW-928: logs the two anomalous reasons. Every other exit from this pipeline
     * already logs — the gate, an open breaker, an extraction failure, and the terminal
     * {@link #logged} call — so a bare {@code return} here was the one path that made
     * "capture found only duplicates" and "capture never ran" produce identical
     * evidence, which is none. An ineligible channel stays silent because that is by
     * design (JCLAW-866) and would otherwise log on every voice turn.
     *
     * <p>Split out of the async plumbing for the same reason {@link #captureEligible}
     * is: {@link #captureAsync} returns early in test mode, so anything left inside its
     * virtual thread is unreachable from a unit test. Public because the test tree
     * compiles into the default package. Must run inside a transaction — it reads the
     * Conversation.
     */
    public static ExtractContext resolveExtractContext(Agent agent, Long conversationId, String agentName) {
        var conv = ConversationService.findById(conversationId);
        if (conv == null) {
            EventLogger.warn(EVENT_CATEGORY, agentName, null,
                    "Auto-capture skipped: conversation %d not found".formatted(conversationId));
            return null;
        }
        if (!channelEligible(conv.channelType)) return null;
        var provider = resolveProvider(agent);
        if (provider == null) {
            EventLogger.warn(EVENT_CATEGORY, agentName, conv.channelType,
                    "Auto-capture skipped: no LLM provider available");
            return null;
        }
        return new ExtractContext(provider, resolveModelId(agent), conv.channelType);
    }

    // ─── Async entry point (hooked from AgentRunner) ─────────────────────────

    /**
     * Fire-and-forget capture for a just-completed turn. Spawns a virtual thread
     * so the reply is never blocked, resolves the provider/model, and runs the
     * pipeline. No-op in test mode, when disabled, or when the turn has no
     * usable content.
     */
    public static void captureAsync(Agent agent, Long conversationId, String userMessage, String assistantResponse) {
        if (agent == null || conversationId == null) return;
        if (userMessage == null || userMessage.isBlank()
                || assistantResponse == null || assistantResponse.isBlank()) return;
        if (Play.runningInTestMode()) return;
        // JCLAW-539 (skip subagents) + JCLAW-534 (per-agent enable, on by
        // default): the agent-level eligibility gate, factored out so it's
        // unit-testable without the async / test-mode plumbing above.
        if (!captureEligible(agent)) return;

        // Memory is partitioned on the immutable agent id, not the mutable name
        // (JCLAW-531): a rename must not strand prior memories, and a name later
        // reused by a different agent must not inherit them. The human-readable
        // name still rides along purely for the event-log agent column.
        final var agentKey = String.valueOf(agent.id);
        final var agentName = agent.name;
        Thread.ofVirtual().name("memory-capture").start(() -> {
            try {
                // Snapshot provider/model/channel under a short Tx — no Tx is held
                // during the LLM call below.
                var ctx = Tx.run(() -> resolveExtractContext(agent, conversationId, agentName));
                if (ctx == null) return;

                int maxOutput = ConfigService.getInt("memory.autocapture.maxTokens", 1024);
                Extractor extractor = msgs -> SessionCompactor.firstChoiceText(
                        ctx.provider().chat(ctx.modelId(), msgs, List.of(), maxOutput, null, ctx.channelType()));
                // JCLAW-525: the consolidation judge rides the same (cheap)
                // capture model — it sees only short memory texts.
                int judgeOutput = ConfigService.getInt("memory.consolidation.maxTokens", 512);
                Consolidator consolidator = msgs -> SessionCompactor.firstChoiceText(
                        ctx.provider().chat(ctx.modelId(), msgs, List.of(), judgeOutput, null, ctx.channelType()));

                capture(agentKey, agentName, userMessage, assistantResponse, extractor, consolidator, SHARED_BREAKER);
            } catch (Exception e) {
                EventLogger.warn(EVENT_CATEGORY, agentName, null,
                        "Auto-capture failed: %s".formatted(e.getMessage()));
            }
        });
    }

    /**
     * Run one turn through the real capture pipeline, synchronously, with no conversation
     * behind it (JCLAW-942 eval corpus ingestion).
     *
     * <p>Exists so a benchmark corpus can be loaded through the pipeline that is actually
     * under test — the gate, the extractor, all six content guards, dedup and the
     * consolidation judge — rather than by writing rows straight to the store, which would
     * measure retrieval against memories capture would never have produced.
     *
     * <p><b>Synchronous on purpose, and callers must stay sequential.</b>
     * {@link #captureAsync} spawns a virtual thread per turn and {@link #capture} serializes
     * per agent on {@link #CAPTURE_LOCKS}, skipping rather than queueing when the lock is
     * held. Firing a corpus at the async form would therefore drop most of it as
     * {@code capture_in_flight} and quietly ingest a fraction of what was asked for.
     *
     * <p>Unlike {@code captureAsync} this takes no conversation id, so it resolves the
     * provider from the agent directly and passes a null channel — there is no channel to
     * be ineligible (JCLAW-866 gates voice turns, which a corpus has none of).
     */
    public static CaptureResult captureSync(Agent agent, String userMessage, String assistantResponse) {
        if (agent == null || userMessage == null || userMessage.isBlank()
                || assistantResponse == null || assistantResponse.isBlank()) {
            return CaptureResult.skipped("empty_turn");
        }
        if (!captureEligible(agent)) {
            return CaptureResult.skipped("ineligible_agent");
        }
        var provider = resolveProvider(agent);
        if (provider == null) {
            return CaptureResult.skipped("no_provider");
        }
        var modelId = resolveModelId(agent);
        int maxOutput = ConfigService.getInt("memory.autocapture.maxTokens", 1024);
        int judgeOutput = ConfigService.getInt("memory.consolidation.maxTokens", 512);
        Extractor extractor = msgs -> SessionCompactor.firstChoiceText(
                provider.chat(modelId, msgs, List.of(), maxOutput, null, null));
        Consolidator consolidator = msgs -> SessionCompactor.firstChoiceText(
                provider.chat(modelId, msgs, List.of(), judgeOutput, null, null));
        return capture(String.valueOf(agent.id), agent.name, userMessage, assistantResponse,
                extractor, consolidator, SHARED_BREAKER);
    }

    /**
     * Whether a just-completed turn on {@code agent} is eligible for auto-capture,
     * independent of the async / test-mode plumbing in {@link #captureAsync}.
     * Capture is for operator-facing (root) agents only — subagents process
     * delegated work, not the operator's own turns (JCLAW-539) — and only when the
     * agent has auto-capture enabled (JCLAW-534, on by default).
     *
     * <p>The benchmark agents are excluded too (JCLAW-942). Their turns are generated
     * traffic, so anything distilled from them is a memory of a load test: a run left
     * five rows in the store, which then rank against real recalls. It also cost the
     * run — capture fires per turn against whatever the agent is pointed at, and in
     * mock mode that is the mock, which answers with chat text rather than extraction
     * JSON. One run logged 31 parse failures and 370 turns suspended behind the
     * circuit breaker those failures tripped, all of it DB writes on the path being
     * measured. {@code ToolRegistry.getToolDefsForAgent} already withholds the memory
     * tool from them by the same reasoning; this closes the passive half.
     */
    private static boolean isBenchmarkAgent(Agent agent) {
        return LoadTestRunner.LOADTEST_AGENT_NAME.equals(agent.name)
                || LoadTestRunner.LOADTEST_TOOLS_AGENT_NAME.equals(agent.name);
    }

    public static boolean captureEligible(Agent agent) {
        return agent != null
                && !agent.isSubagent()
                && !isBenchmarkAgent(agent)
                && agent.memoryAutocaptureEnabled;
    }

    /**
     * Whether a turn on {@code channelType} may be auto-captured (JCLAW-866).
     *
     * <p>Voice is excluded. Those sessions are ephemeral by design — JCLAW-862
     * gives each its own conversation and JCLAW-864 deletes it when the dialog
     * closes — while memories are partitioned by agent, not conversation. Capturing
     * would leave a permanent derivative of a transcript the operator was told
     * would vanish, shaping later answers from a source they can no longer inspect.
     *
     * <p>Scoped to <em>auto</em>-capture only. The {@code memory} tool (JCLAW-919) still
     * works during a voice conversation: an explicit "remember this" is an instruction,
     * not the passive distillation being withheld here.
     *
     * <p>Split out rather than inlined at the call site because {@code captureAsync}
     * returns early in test mode, so anything buried in its virtual thread is
     * unreachable from a unit test — the same reason {@link #captureEligible} was
     * factored out. An unknown or null channel stays eligible: capture has always
     * been the default and a channel we don't recognize is not a reason to drop it.
     */
    public static boolean channelEligible(String channelType) {
        return !ChannelType.VOICE.value.equalsIgnoreCase(channelType);
    }

    // JCLAW-534: the extractor runs on the agent's per-agent autocapture model —
    // the agent's default model unless an operator set an explicit override in the
    // agent's Memory section. No global model knob.
    private static LlmProvider resolveProvider(Agent agent) {
        var p = ProviderRegistry.get(agent.autocaptureProviderEffective());
        return p != null ? p : ProviderRegistry.getPrimary();
    }

    private static String resolveModelId(Agent agent) {
        return agent.autocaptureModelEffective();
    }

    // ─── Testable core pipeline ──────────────────────────────────────────────

    /**
     * Core capture pipeline, testable via the injected {@code extractor} and
     * {@code breaker}: heuristic gate → breaker-guarded extraction → JSON parse →
     * dedup → persist. Never throws; returns a {@link CaptureResult} describing
     * the outcome.
     *
     * <p>{@code agentKey} is the immutable memory partition key (the agent id);
     * {@code agentName} is the human-readable label used only for the event-log
     * agent column (JCLAW-531). In tests with no Agent entity the two may be the
     * same string.
     */
    public static CaptureResult capture(String agentKey, String agentName, String userMessage,
                                        String assistantResponse, Extractor extractor, CircuitBreaker breaker) {
        return capture(agentKey, agentName, userMessage, assistantResponse, extractor, null, breaker);
    }

    /**
     * As the six-arg overload, with a {@link Consolidator} for the supersession
     * judge (JCLAW-525). {@code null} skips consolidation entirely — candidates
     * are stored append-only, the pre-525 behavior.
     */
    public static CaptureResult capture(String agentKey, String agentName, String userMessage,
                                        String assistantResponse, Extractor extractor,
                                        Consolidator consolidator, CircuitBreaker breaker) {
        var gate = MemoryAttentionGate.evaluate(userMessage);
        if (!gate.proceed()) {
            return logged(agentName, CaptureResult.skipped(gate.reason()));
        }

        if (!breaker.allowRequest()) {
            // Suspend but LOG — the turn is already durable as Message rows, so a
            // future reprocessing pass could revisit it. Never a silent drop.
            EventLogger.warn(EVENT_CATEGORY, agentName, null,
                    "Auto-capture suspended (circuit breaker %s)".formatted(breaker.state()));
            return CaptureResult.skipped("breaker_open");
        }

        String raw;
        try {
            var messages = List.<ChatMessage>of(
                    ChatMessage.system(EXTRACTION_INSTRUCTIONS),
                    ChatMessage.user(userMessage.strip()),
                    ChatMessage.assistant(assistantResponse.strip()),
                    ChatMessage.user(EXTRACTION_REQUEST));
            raw = extractor.extract(messages);
            breaker.recordSuccess();
        } catch (Exception e) {
            breaker.recordFailure();
            EventLogger.warn(EVENT_CATEGORY, agentName, null,
                    "Auto-capture extraction failed: %s".formatted(e.getMessage()));
            return CaptureResult.skipped("extraction_error");
        }

        // JCLAW-964: bound the extractor's output before anything expensive reads it.
        // semanticDuplicateIndices embeds EVERY candidate over HTTP and plan runs one FTS
        // search plus a hydration per candidate, both ahead of the maxPerTurn cut — so a
        // degenerate extractor returning 200 one-sentence candidates (a known failure mode
        // for cheap models on long turns) became 200 embedding round-trips and 200 searches
        // for a turn that can store at most maxPerTurn of them. A ceiling, NOT maxPerTurn
        // itself: that caps SURVIVORS after dedup, so applying it here would silently store
        // fewer memories whenever a batch contained duplicates.
        int maxCandidates = ConfigService.getInt("memory.autocapture.maxCandidates", 25);
        List<Candidate> parsed = dedupeWithinBatch(parseCandidates(raw));
        if (parsed.size() > maxCandidates) {
            EventLogger.warn(EVENT_CATEGORY, agentName, null,
                    "Extractor returned %d candidates; capping at %d".formatted(parsed.size(), maxCandidates));
            parsed = parsed.subList(0, maxCandidates);
        }
        List<Candidate> deduped = parsed;

        // JCLAW-535: deterministic secret scrub — never persist credentials to
        // long-term memory, even if the extractor ignores the prompt's guidance.
        final List<Candidate> noSecrets =
                deduped.stream().filter(c -> !MemorySafety.looksLikeSecret(c.text())).toList();
        int scrubbed = deduped.size() - noSecrets.size();
        if (scrubbed > 0) {
            EventLogger.warn(EVENT_CATEGORY, agentName, null,
                    "Dropped %d candidate memory(ies) containing apparent secrets".formatted(scrubbed));
        }

        // JCLAW-553: stored memories are re-injected into every future system
        // prompt, so injection/exfiltration payloads are refused at write time.
        final List<Candidate> candidates =
                noSecrets.stream().filter(c -> !MemorySafety.looksLikeInjection(c.text())).toList();
        int blocked = noSecrets.size() - candidates.size();
        if (blocked > 0) {
            EventLogger.warn(EVENT_CATEGORY, agentName, null,
                    "Dropped %d candidate memory(ies) containing apparent injection payloads".formatted(blocked));
        }

        // JCLAW-919: the turn that asks to forget a fact states it, so without this the
        // capture running on that same turn re-learns what forget just deleted.
        final List<Candidate> notReforgotten =
                candidates.stream().filter(c -> !MemoryForgetLog.recentlyForgotten(agentKey, c.text())).toList();
        int reforgotten = candidates.size() - notReforgotten.size();
        if (reforgotten > 0) {
            EventLogger.info(EVENT_CATEGORY, agentName, null,
                    "Dropped %d candidate memory(ies) the operator just asked to forget".formatted(reforgotten));
        }

        // JCLAW-1048: and the request itself is extractable — "the user wants X forgotten"
        // stores as a durable, well-keyed memory the model then reads as standing policy,
        // refusing the whole subject. The filter above cannot catch it: it tests for a
        // restatement of the deleted fact, and this restates the request instead.
        final List<Candidate> noForgetNotes =
                notReforgotten.stream().filter(c -> !MemorySafety.looksLikeForgetRequest(c.text())).toList();
        int forgetNotes = notReforgotten.size() - noForgetNotes.size();
        if (forgetNotes > 0) {
            EventLogger.info(EVENT_CATEGORY, agentName, null,
                    "Dropped %d candidate memory(ies) recording a request to forget".formatted(forgetNotes));
        }

        // JCLAW-1051: and the same for any other instruction to drive the tool. UAT stored
        // "the user wants the assistant to use the recall action of its memory tool", which
        // then ranked ABOVE the real fact when recalling that topic. The filter above misses
        // it — it carries no removal verb — just as this one misses a forget note naming no
        // tool. Two shapes, neither subsuming the other.
        final List<Candidate> noToolNotes =
                noForgetNotes.stream().filter(c -> !MemorySafety.looksLikeToolInstruction(c.text())).toList();
        int toolNotes = noForgetNotes.size() - noToolNotes.size();
        if (toolNotes > 0) {
            EventLogger.info(EVENT_CATEGORY, agentName, null,
                    "Dropped %d candidate memory(ies) recording a memory-tool instruction".formatted(toolNotes));
        }

        // JCLAW-1055: the third shape, and the one the two filters above are structurally
        // unable to see. "Forget my dentist's name" stored "The user has a dentist." — text
        // that carries neither a removal verb nor a tool name because it is not a note about
        // the request at all. It is the request's presupposition asserted as fact, so the
        // guard has to read the candidate against the turn rather than on its own.
        final List<Candidate> notPresupposed = noToolNotes.stream()
                .filter(c -> !MemorySafety.assertsOnlyPresupposition(userMessage, c.text())).toList();
        int presupposed = noToolNotes.size() - notPresupposed.size();
        if (presupposed > 0) {
            EventLogger.info(EVENT_CATEGORY, agentName, null,
                    "Dropped %d candidate memory(ies) the turn only presupposed".formatted(presupposed));
        }

        // JCLAW-1056: and the fourth shape — substance taken from the assistant turn, which
        // the prompt bars outright and a live model did anyway. The filter above cannot see
        // it: that one needs the candidate to touch the user turn somewhere, and this fires
        // precisely when it touches nothing in it. Worth a guard beyond the phrasing churn it
        // was found causing, because the assistant turn carries tool output.
        final List<Candidate> kept = notPresupposed.stream()
                .filter(c -> !MemorySafety.assertsOnlyAssistantContent(userMessage, assistantResponse, c.text()))
                .toList();
        int assistantSourced = notPresupposed.size() - kept.size();
        if (assistantSourced > 0) {
            EventLogger.info(EVENT_CATEGORY, agentName, null,
                    "Dropped %d candidate memory(ies) sourced from the assistant turn".formatted(assistantSourced));
        }

        // JCLAW-942: the maxPerTurn cut in plan() keeps the first survivors in list order, so
        // that order has to mean something. The extractor scores every candidate for
        // importance and nothing read it: a turn stating six durable facts stored whichever
        // five the model happened to emit first. Measured on a live 751-turn agent, the cap
        // bound on 79 turns and discarded leftovers on 56. Sorted here rather than in plan()
        // because semanticDuplicateIndices returns indices into this list. Stable, so equal
        // importance keeps emission order.
        final List<Candidate> ranked = kept.stream()
                .sorted(Comparator.comparingDouble(Candidate::importance).reversed())
                .toList();

        if (ranked.isEmpty()) {
            // Two different failures, and conflating them hid which one was happening: the
            // extractor finding nothing durable is the ordinary case, every candidate being
            // refused by the filters above is the one worth investigating.
            return logged(agentName,
                    CaptureResult.skipped(deduped.isEmpty() ? "no_candidates" : "all_filtered"));
        }

        int maxPerTurn = ConfigService.getInt("memory.autocapture.maxPerTurn", 5);
        double dupThreshold = ConfigService.getDouble("memory.autocapture.dedup.threshold", 0.85);
        int dedupScan = ConfigService.getInt("memory.autocapture.dedup.scanLimit", 100);

        // JCLAW-525 split the old single-Tx persist into plan (Tx) → judge
        // (LLM, no Tx) → apply (Tx), preserving the "no Tx held during an LLM
        // call" invariant the pipeline is built around. JCLAW-922 adds the
        // semantic pass ahead of plan for the same reason: it embeds each
        // candidate, and that round-trip must not run inside the plan Tx.
        // JCLAW-965: everything below is check-then-act across three transactions with an LLM
        // judge call in the middle, and captureAsync spawns an unsynchronized virtual thread
        // per turn. Two turns for one agent — web chat and Telegram, say — could both find
        // nothing matching and both store the same fact; Memory's @Table declares only
        // non-unique indexes, so the DB cannot reject the second write either. The
        // consolidation judge does not rescue it: identical rows are not "same subject,
        // changed content". Serialize per agent across the whole window.
        var lock = CAPTURE_LOCKS.computeIfAbsent(agentKey, _ -> new ReentrantLock());
        boolean held;
        try {
            held = lock.tryLock(captureLockWaitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return logged(agentName, CaptureResult.skipped("interrupted"));
        }
        if (!held) {
            // Skip rather than queue: the wait is behind a judge call of unbounded duration,
            // and the turn stays durable as Message rows for a future reprocessing pass.
            return logged(agentName, CaptureResult.skipped("capture_in_flight"));
        }
        try {
            var semanticDupes = semanticDuplicateIndices(agentKey, agentName, ranked);
            var plan = Tx.run(() -> plan(agentKey, ranked, maxPerTurn, dupThreshold, dedupScan, semanticDupes));
            if (plan.overflow() > 0) {
                EventLogger.info(EVENT_CATEGORY, agentName, null,
                        ("Turn yielded more than the %d-memory per-turn cap — %d lower-importance "
                                + "candidate(s) not stored").formatted(maxPerTurn, plan.overflow()));
            }
            var supersessions = judgeSupersessions(agentName, plan, consolidator, breaker);
            // Persist the survivor rows inside the apply Tx (capturing their ids), then
            // generate + write their embeddings AFTER it commits — the embedding HTTP
            // round-trip must never run inside the write tx (same "slow call OUTSIDE the
            // Tx" ordering as extract/judge above; the vector leg otherwise pinned one
            // pooled connection across up to maxPerTurn sequential embedding calls).
            var storedIds = Tx.run(() -> applyPlan(agentKey, agentName, plan, supersessions));
            int embedded = embedStored(storedIds);
            if (embedded < storedIds.size()) {
                // Distinct from "Auto-capture failed", which reads as "nothing was captured".
                // Every row here IS stored and keyword-searchable; some lack a vector.
                EventLogger.warn(EVENT_CATEGORY, agentName, null,
                        "Auto-capture stored %d memory(ies) but embedded only %d"
                                .formatted(storedIds.size(), embedded));
            }
            return logged(agentName, new CaptureResult(storedIds.size(),
                    ranked.size() - storedIds.size(), null));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Semantic dedup phase (no Tx): which candidate indices already have a
     * near-identical memory stored, judged by embedding cosine rather than shared
     * wording (JCLAW-922). This is the tier the lexical rule cannot reach — a
     * restatement like "scheduled for the last Friday" against "recurring ... on the
     * last Friday" shares too few tokens for any safe lexical threshold.
     *
     * <p>Runs before {@link #plan} and outside its transaction because
     * {@link MemoryStore#semanticNeighbours} embeds each candidate. Fail-open at
     * every level: disabled by config, vector memory off, no embedding provider, or
     * a lookup error all yield no semantic drops and the lexical rule stands alone.
     */
    private static Set<Integer> semanticDuplicateIndices(String agentKey, String agentName,
                                                         List<Candidate> candidates) {
        if (!ConfigService.getBoolean("memory.autocapture.dedup.semantic.enabled", true)) return Set.of();
        double minCosine = ConfigService.getDouble("memory.autocapture.dedup.cosineThreshold", 0.90);
        int limit = ConfigService.getInt("memory.autocapture.dedup.semanticLimit", 5);
        var store = MemoryStoreFactory.get();
        var out = new HashSet<Integer>();
        for (int i = 0; i < candidates.size(); i++) {
            var matches = store.semanticNeighbours(agentKey, candidates.get(i).text(),
                    candidates.get(i).retrievalKey(), limit, minCosine);
            if (matches.isEmpty()) continue;
            out.add(i);
            EventLogger.info(EVENT_CATEGORY, agentName, null,
                    "Memory candidate dropped as a semantic duplicate of %s: \"%s\""
                            .formatted(matches, snippet(candidates.get(i).text())));
        }
        return out;
    }

    /**
     * Plan phase (in Tx): build the comparison pool, drop candidates that already
     * have a near-duplicate stored ({@link MemorySimilarity} NOOP), cap at
     * {@code maxPerTurn}, and collect the consolidation shortlist for the judge.
     * Superseded rows are excluded from both legs of the pool, so a superseded old
     * fact can never NOOP a re-emerging new one.
     *
     * <p>The pool is the agent's recency slice <em>plus</em> a per-candidate
     * retrieval neighbourhood (JCLAW-920). The slice alone bounded dedup to the
     * newest {@code dedupScan} rows, so a fact restated after the window had moved
     * on was compared against nothing and stored again — two byte-identical pairs
     * survived that way in a 1248-row store, 1312 and 5214 ids apart. Retrieval
     * reaches the whole index, making the pool relevance-selected rather than
     * recency-selected at a cost that does not grow with the store.
     *
     * <p>Retrieval here is the keyword leg only ({@link Memory#searchByTextScored}
     * — Lucene FTS or the DB LIKE fallback). Deliberate: {@code MemoryStore.search}
     * would embed the query, and this method runs inside the plan transaction where
     * a blocking HTTP call must never happen.
     */
    private static ConsolidationPlan plan(String agentKey, List<Candidate> candidates,
                                          int maxPerTurn, double dupThreshold, int dedupScan,
                                          Set<Integer> semanticDupes) {
        double shortlistMin = ConfigService.getDouble("memory.consolidation.shortlist.minJaccard", 0.2);
        int shortlistCap = ConfigService.getInt("memory.consolidation.shortlist.maxPerCandidate", 5);
        // 0.82, not 0.85: swept against a 1248-row store, 0.82 catches 10 restatement
        // pairs with no false positive, 0.85 catches 8. Below 0.80 unrelated facts
        // sharing a sentence template start matching (two different hosted apps, two
        // different providers), so this is the floor, not a tuning knob to lower.
        //
        // Re-swept for JCLAW-1054 when MemorySimilarity moved onto the search analyzer's
        // normalization, because changing tokenization redefines what these numbers measure.
        // They hold: over 792 real memory texts the stemmed rule at the SAME 0.85/0.82 finds
        // 29 restatement pairs the old one missed and drops 8, about half of which were false
        // positives (it had been collapsing two printers at different IP addresses into one).
        // Raising them to compensate would only give the misses back.
        double containment = ConfigService.getDouble("memory.autocapture.dedup.containmentThreshold", 0.82);
        double minLengthRatio = ConfigService.getDouble("memory.autocapture.dedup.minLengthRatio", 0.5);
        int retrievalLimit = ConfigService.getInt("memory.autocapture.dedup.retrievalLimit", 25);

        var pool = new LinkedHashMap<Long, Memory>();
        for (var m : Memory.findByAgent(agentKey, dedupScan)) {
            pool.put(m.id, m);
        }
        for (var c : candidates) {
            for (var hit : Memory.searchByTextScored(agentKey, c.text(), retrievalLimit)) {
                pool.putIfAbsent(hit.memory().id, hit.memory());
            }
        }
        var poolRows = List.copyOf(pool.values());
        var poolTokens = poolRows.stream().map(m -> MemorySimilarity.Tokens.of(m.text)).toList();

        var survivors = new ArrayList<Candidate>();
        var survivorTokens = new ArrayList<MemorySimilarity.Tokens>();
        var shortlist = new ArrayList<Existing>();
        var shortlisted = new HashSet<Long>();
        int overflow = 0;
        for (int idx = 0; idx < candidates.size(); idx++) {
            if (survivors.size() >= maxPerTurn) {
                overflow = candidates.size() - idx;
                break;
            }
            if (semanticDupes.contains(idx)) continue;
            var c = candidates.get(idx);
            var toks = MemorySimilarity.Tokens.of(c.text());
            if (hasDuplicate(toks, poolTokens, dupThreshold, containment, minLengthRatio)
                    || hasDuplicate(toks, survivorTokens, dupThreshold, containment, minLengthRatio)) continue;
            survivors.add(c);
            survivorTokens.add(toks);
            // Same-subject shortlist: moderate overlap below the dup threshold
            // (at/above it the candidate would have been NOOPed as a duplicate).
            int added = 0;
            for (int i = 0; i < poolRows.size() && added < shortlistCap; i++) {
                double j = jaccard(toks.raw(), poolTokens.get(i).raw());
                if (j >= shortlistMin && j < dupThreshold && shortlisted.add(poolRows.get(i).id)) {
                    shortlist.add(new Existing(poolRows.get(i).id, poolRows.get(i).text));
                    added++;
                }
            }
        }
        return new ConsolidationPlan(survivors, shortlist, overflow);
    }

    private static boolean hasDuplicate(MemorySimilarity.Tokens toks,
            List<MemorySimilarity.Tokens> against, double jaccardThreshold,
            double containmentThreshold, double minLengthRatio) {
        for (var other : against) {
            if (MemorySimilarity.isDuplicate(toks, other, jaccardThreshold,
                    containmentThreshold, minLengthRatio)) return true;
        }
        return false;
    }

    /**
     * Judge phase (no Tx): one batched LLM call pairing the survivors against
     * the shortlist. Fail-open — a missing consolidator, disabled config, open
     * breaker, call failure, or malformed output all yield no supersessions and
     * the capture stores append-only, exactly the pre-525 behavior.
     */
    private static Map<Integer, List<Integer>> judgeSupersessions(String agentName, ConsolidationPlan plan,
                                                                  Consolidator consolidator, CircuitBreaker breaker) {
        if (consolidator == null || plan.survivors().isEmpty() || plan.shortlist().isEmpty()) return Map.of();
        if (!ConfigService.getBoolean("memory.consolidation.enabled", true)) return Map.of();
        if (!breaker.allowRequest()) {
            EventLogger.warn(EVENT_CATEGORY, agentName, null,
                    "Memory consolidation suspended (circuit breaker %s)".formatted(breaker.state()));
            return Map.of();
        }
        try {
            var messages = List.<ChatMessage>of(
                    ChatMessage.system(CONSOLIDATION_INSTRUCTIONS),
                    ChatMessage.user(renderConsolidation(plan)));
            var raw = consolidator.judge(messages);
            breaker.recordSuccess();
            return parseSupersessions(raw, plan.survivors().size(), plan.shortlist().size());
        } catch (Exception e) {
            breaker.recordFailure();
            EventLogger.warn(EVENT_CATEGORY, agentName, null,
                    "Memory consolidation judge failed (storing without supersession): %s"
                            .formatted(e.getMessage()));
            return Map.of();
        }
    }

    /**
     * Apply phase (in Tx): store the survivors, then mark judged rows
     * superseded. Survivor rows are persisted via {@link MemoryStore#storeDeferred}
     * — their vector embeddings are written by a separate post-commit pass
     * ({@link #embedStored}), never inside this write tx — and the method returns
     * the new row ids in survivor order for that pass. The judge only nominates
     * same-subject pairs; the direction is enforced here deterministically — a row
     * is superseded only when its id (serial) is strictly lower than the new row's,
     * i.e. the newer write always wins and the LLM can never flip recency
     * (JCLAW-525 AC). Each supersession is event-logged.
     */
    private static List<String> applyPlan(String agentKey, String agentName, ConsolidationPlan plan,
                                          Map<Integer, List<Integer>> supersessions) {
        var store = MemoryStoreFactory.get();
        var storedIds = new ArrayList<String>(plan.survivors().size());
        for (int i = 0; i < plan.survivors().size(); i++) {
            var c = plan.survivors().get(i);
            var newIdStr = store.storeDeferred(agentKey, c.text(), c.category(), c.importance(),
                    c.retrievalKey());
            storedIds.add(newIdStr);
            var olds = supersessions.get(i);
            if (olds == null || olds.isEmpty()) continue;
            long newId = Long.parseLong(newIdStr);
            for (int oldIdx : olds) {
                var ex = plan.shortlist().get(oldIdx);
                if (ex.id() >= newId) continue;   // serial guard: of two facts, the newer wins
                if (!atLeastAsInformative(ex.text(), c.text())) {
                    EventLogger.info(EVENT_CATEGORY, agentName, null,
                            ("Kept memory %d instead of superseding it with %d — the newer text drops "
                                    + "content: \"%s\" vs \"%s\"")
                                    .formatted(ex.id(), newId, snippet(c.text()), snippet(ex.text())));
                    continue;
                }
                if (!sharesSubject(ex.text(), c.text())) {
                    EventLogger.info(EVENT_CATEGORY, agentName, null,
                            ("Kept memory %d instead of superseding it with %d — no shared subject: "
                                    + "\"%s\" vs \"%s\"")
                                    .formatted(ex.id(), newId, snippet(c.text()), snippet(ex.text())));
                    continue;
                }
                if (!preservesValues(ex.text(), c.text())) {
                    EventLogger.info(EVENT_CATEGORY, agentName, null,
                            ("Kept memory %d instead of superseding it with %d — the newer text pins "
                                    + "no %s: \"%s\" vs \"%s\"")
                                    .formatted(ex.id(), newId, lostValueKinds(ex.text(), c.text()),
                                            snippet(c.text()), snippet(ex.text())));
                    continue;
                }
                Memory old = Memory.findById(ex.id());
                if (old == null || old.supersededAt != null) continue;
                old.supersede(newId);
                EventLogger.info(EVENT_CATEGORY, agentName, null,
                        "Memory %d superseded by %d: \"%s\" → \"%s\""
                                .formatted(ex.id(), newId, snippet(ex.text()), snippet(c.text())));
            }
        }
        return storedIds;
    }

    /**
     * Post-commit embedding pass (JCLAW-807-follow-up): runs after {@link #applyPlan}'s
     * write tx has committed, so the (up to {@code maxPerTurn}) blocking embedding
     * calls never pin that tx's pooled connection. Each row's embedding is generated
     * and written by the store outside the write tx (a fresh short tx for the pgvector
     * UPDATE, or a no-DB Lucene upsert); a no-op when vector memory is disabled.
     */
    private static int embedStored(List<String> storedIds) {
        if (storedIds.isEmpty()) return 0;
        var store = MemoryStoreFactory.get();
        int embedded = 0;
        for (var id : storedIds) {
            try {
                store.embedStored(id);
                embedded++;
            } catch (Exception e) {
                // JCLAW-1015: JpaMemoryStore.embedStored swallows provider and Lucene failures
                // internally, but not a throw from its own snapshot transaction — a pool timeout
                // on one id abandoned every id after it. Those rows are already committed and
                // FTS-searchable; what they lose is the vector leg, and nothing re-embeds them
                // because MemoryReembedService reads a marker this path never touches.
                EventLogger.warn(EVENT_CATEGORY, null, null,
                        "Memory %s stored but not embedded: %s".formatted(id, e.getMessage()));
            }
        }
        return embedded;
    }

    /**
     * How much of the superseded row's content a survivor must carry to replace it
     * (JCLAW-1050). Measured on the shapes consolidation actually pairs: narrower
     * restatements land at 0.375-0.500 ("osteopath is called Ines and her clinic is on Rua
     * do Almada" → "osteopath is named Ines"), genuine corrections at 1.0 or above ("lives
     * in Porto" → "lives in Lisbon"; "flight is at 08:00" → "at 11:30 from Gate 4"). The
     * sweep is clean anywhere in 0.55-1.00 and breaks at 0.50; 0.75 centers that gap.
     */
    private static final double SUPERSEDE_MIN_CONTENT_RATIO = 0.75;

    /**
     * Whether {@code replacement} may supersede {@code existing}, by content volume.
     *
     * <p>Recency decides between two facts that disagree — that is the JCLAW-525 rule and it
     * stays. It is the wrong rule for a candidate that merely restates an existing memory
     * with less in it: UAT watched a clinic address vanish because the extractor re-emitted
     * a thinner version of a fact already stored, and the serial guard let it win.
     *
     * <p>Refusing costs a redundant row that both remain visible and recallable; allowing
     * costs content with nothing to recover it from. A stale row an operator can see beats
     * a detail they cannot.
     */
    private static boolean atLeastAsInformative(String existing, String replacement) {
        int older = MemorySimilarity.contentTokens(existing).size();
        if (older == 0) return true;
        int newer = MemorySimilarity.contentTokens(replacement).size();
        return newer >= SUPERSEDE_MIN_CONTENT_RATIO * older;
    }

    /** Tokens that only ever qualify a number, so sharing one is not sharing a subject. */
    private static final Set<String> VALUE_MARKERS = Set.of("am", "pm");

    /**
     * Whether the two texts are about the same thing at all, by shared non-numeric content
     * (JCLAW-942).
     *
     * <p>{@link #atLeastAsInformative} asks only how <em>much</em> content the replacement
     * has, never whether it concerns the same subject, so any sufficiently long memory could
     * retire any other the judge happened to pair. Measured over a 302-turn LongMemEval
     * ingest: of 104 supersessions, 11% shared no non-numeric token with what they retired —
     * "Emma taught the user how to make a Negroni" replaced by "the user is interested in
     * infusing spirits with lavender", and a memory about gym sessions at 7:00 pm retired by
     * one about stopping work email at 7 pm. The prompt already forbids pairing
     * related-but-different facts; the judge does it anyway.
     *
     * <p>Numbers and clock markers are excluded from the evidence precisely because they are
     * what those false pairs shared. A shared time is not a shared subject, and "pm" carries
     * no more subject information than the "7" it qualifies — excluding the digits alone
     * left the gym/work-email pair still sharing {@code pm} and still passing.
     *
     * <p>Deliberately weak — one shared noun is enough. A correction keeps its subject while
     * changing its value ("lives in Porto" → "lives in Lisbon"), so anything stricter starts
     * refusing the updates supersession exists to apply. This blocks only pairs with no
     * lexical claim to being about the same thing, and the cost of a false refusal is a
     * redundant row that both remain visible and recallable.
     */
    private static boolean sharesSubject(String existing, String replacement) {
        var shared = MemorySimilarity.contentTokens(existing);
        shared.retainAll(MemorySimilarity.contentTokens(replacement));
        return shared.stream().anyMatch(t ->
                !VALUE_MARKERS.contains(t) && !t.chars().allMatch(Character::isDigit));
    }

    private static final String MONTHS =
            "january|february|march|april|may|june|july|august|september|october|november|december"
                    + "|jan|feb|mar|apr|jun|jul|aug|sept?|oct|nov|dec";

    /** A value-bearing shape and the kind of value it pins. */
    private record ValueShape(Pattern pattern, String kind) {}

    /**
     * Ordered most specific first. {@link #valueKinds} strips each shape's matches before
     * trying the next, so "7:00 pm" is read as a clock and not additionally as the bare
     * numbers 7 and 0 — without that, any text holding a time would also count as carrying
     * a quantity and the guard would wave through a replacement that dropped one.
     */
    private static final List<ValueShape> VALUE_SHAPES = List.of(
            new ValueShape(Pattern.compile(
                    "\\b\\d{1,2}:\\d{2}\\s*(?:am|pm)?|\\b\\d{1,2}\\s*(?:am|pm)\\b"), "clock"),
            // Possessive throughout: the two \s around the optional hyphen are an overlapping
            // pair, so trailing spaces backtrack polynomially on conversation text (JCLAW-1048).
            new ValueShape(Pattern.compile(
                    "\\b\\d++\\s*+-?+\\s*+(?:second|minute|hour|day|week|month|year|decade)s?+\\b"), "duration"),
            new ValueShape(Pattern.compile(
                    "\\b(?:" + MONTHS + ")\\b\\s*\\d{0,4}(?:st|nd|rd|th)?"
                            + "|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?:" + MONTHS + ")\\b"), "date"),
            new ValueShape(Pattern.compile(
                    "\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b|\\b\\d{1,2}[-/]\\d{1,2}(?:[-/]\\d{2,4})?\\b"), "date"),
            new ValueShape(Pattern.compile("\\b(?:19|20)\\d{2}\\b"), "year"),
            new ValueShape(Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b"), "quantity"));

    /** Which kinds of value a text pins down. */
    private static Set<String> valueKinds(String text) {
        var remaining = text.toLowerCase(Locale.ROOT);
        var kinds = new HashSet<String>();
        for (var shape : VALUE_SHAPES) {
            var m = shape.pattern().matcher(remaining);
            if (!m.find()) continue;
            kinds.add(shape.kind());
            remaining = m.replaceAll(" ");
        }
        return kinds;
    }

    /**
     * Whether {@code replacement} still pins every kind of value {@code existing} did
     * (JCLAW-942).
     *
     * <p>Neither guard above can see this. {@link #atLeastAsInformative} counts tokens
     * without asking which, and {@link #sharesSubject} deliberately ignores numbers when
     * looking for a shared subject — so a replacement can be long enough and about the same
     * thing while dropping the one detail that made the old row worth keeping. Measured over
     * a 302-turn LongMemEval ingest: of 104 supersessions, 16 retired a memory carrying a
     * date, time, duration or quantity and 15 of those dropped it. "on page 250 of 'The
     * Nightingale'" was retired by "currently reading 'Sapiens'"; "a daily tidying routine
     * for 3 weeks" by "has made a significant positive difference".
     *
     * <p>Compares kinds rather than values, because a correction refills the slot it
     * empties — "flight is at 08:00" → "at 11:30 from Gate 4" keeps a clock and must still
     * pass, and that pair is the worked example in {@link #SUPERSEDE_MIN_CONTENT_RATIO}'s
     * note. Only a kind vanishing outright is destruction. It follows that two distinct
     * events of the same shape are still not separated — "caught 7 bass on 7/10" may retire
     * "caught 9 bass on 7/22" — which stays a judge problem, as 08454ef4 recorded.
     */
    private static boolean preservesValues(String existing, String replacement) {
        return valueKinds(replacement).containsAll(valueKinds(existing));
    }

    /** The kinds in the event log line, so an operator sees what the refusal protected. */
    private static String lostValueKinds(String existing, String replacement) {
        var lost = valueKinds(existing);
        lost.removeAll(valueKinds(replacement));
        return String.join("/", lost);
    }

    private static String snippet(String text) {
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }

    // ─── Parsing & helpers ───────────────────────────────────────────────────

    /**
     * Parse the extractor's raw output into candidates. Tolerant of code-fenced
     * JSON and of either {@code {"memories":[...]}} or a bare array; returns an
     * empty list on any malformed/non-JSON output (capture nothing this turn).
     */
    public static List<Candidate> parseCandidates(String raw) {
        var out = new ArrayList<Candidate>();
        if (raw == null || raw.isBlank()) return out;
        try {
            var root = JsonParser.parseString(stripFences(raw.strip()));
            JsonArray arr;
            if (root.isJsonObject() && root.getAsJsonObject().has(KEY_MEMORIES)
                    && root.getAsJsonObject().get(KEY_MEMORIES).isJsonArray()) {
                arr = root.getAsJsonObject().getAsJsonArray(KEY_MEMORIES);
            } else if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else {
                return out;
            }
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                var o = el.getAsJsonObject();
                if (!o.has(KEY_TEXT) || o.get(KEY_TEXT).isJsonNull()) continue;
                var text = o.get(KEY_TEXT).getAsString().strip();
                if (text.isEmpty()) continue;
                // JCLAW-927: the prompt names six categories and says to pick exactly one;
                // the model still returns others (opinion, belief, instruction, project).
                // Storing those verbatim leaves rows the taxonomy does not describe, and
                // silently drops them to BASELINE_IMPORTANCE whenever the extractor also
                // omits importance.
                var rawCategory = (o.has(KEY_CATEGORY) && !o.get(KEY_CATEGORY).isJsonNull())
                        ? MemoryCategory.normalize(o.get(KEY_CATEGORY).getAsString()) : null;
                // JCLAW-981: coerceForCapture, not coerceForStorage — capture may not assign
                // core. That tier is granted by an explicit operator instruction only.
                var category = MemoryCategory.coerceForCapture(rawCategory);
                if (rawCategory != null && !rawCategory.equals(category)) {
                    EventLogger.warn(EVENT_CATEGORY,
                            "Extractor returned category '%s', which capture may not assign; stored as '%s'"
                                    .formatted(rawCategory, category));
                }
                double importance = (o.has(KEY_IMPORTANCE) && !o.get(KEY_IMPORTANCE).isJsonNull())
                        ? clamp01(safeDouble(o.get(KEY_IMPORTANCE)))
                        : MemoryCategory.defaultImportanceFor(category);
                // JCLAW-529: identity facts go to the always-loaded tier instead of competing
                // for vector-search slots. The importance lift is not cosmetic — findCore
                // filters on memory.coreload.minImportance (0.8), and the extractor scores
                // facts like "the user's son X was born on Y" at 0.7, so a promotion without
                // it would admit the memory to a tier that then never renders it.
                if (MemoryIdentityClass.isIdentity(text)) {
                    category = MemoryCategory.CORE.label;
                    importance = Math.max(importance, MemoryCategory.defaultImportanceFor(category));
                }
                out.add(new Candidate(text, category, importance, parseQuestions(o)));
            }
        } catch (Exception _) {
            return new ArrayList<>();
        }
        return out;
    }

    /** Cap on stored questions: the prompt asks for two or three, this bounds a model that ignores that. */
    private static final int MAX_QUESTIONS = 5;

    /**
     * The {@code questions} array as one newline-joined block, or null when the model
     * omitted it (JCLAW-529). Absent is the pre-529 behavior, not an error — the row
     * simply embeds its statement alone.
     */
    private static String parseQuestions(com.google.gson.JsonObject o) {
        if (!o.has(KEY_QUESTIONS) || !o.get(KEY_QUESTIONS).isJsonArray()) return null;
        var joined = new StringBuilder();
        for (var q : o.getAsJsonArray(KEY_QUESTIONS)) {
            if (q == null || !q.isJsonPrimitive()) continue;
            var s = q.getAsString().strip();
            if (s.isEmpty()) continue;
            if (!joined.isEmpty()) joined.append('\n');
            joined.append(s);
            if (joined.chars().filter(ch -> ch == '\n').count() + 1 >= MAX_QUESTIONS) break;
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    /**
     * Parse the consolidation judge's output into {@code survivor index →
     * superseded shortlist indices} (JCLAW-525). Tolerant of code fences;
     * out-of-range and duplicate indices are dropped; any malformed output
     * yields no supersessions (fail-open, the capture stores append-only).
     * Public because the test tree compiles into the default package.
     */
    public static Map<Integer, List<Integer>> parseSupersessions(String raw, int newCount, int existingCount) {
        if (raw == null || raw.isBlank()) return Map.of();
        var out = new LinkedHashMap<Integer, List<Integer>>();
        try {
            var root = JsonParser.parseString(stripFences(raw.strip()));
            if (!root.isJsonObject() || !root.getAsJsonObject().has(KEY_SUPERSESSIONS)
                    || !root.getAsJsonObject().get(KEY_SUPERSESSIONS).isJsonArray()) {
                return Map.of();
            }
            for (var el : root.getAsJsonObject().getAsJsonArray(KEY_SUPERSESSIONS)) {
                addSupersessionIfValid(el, newCount, existingCount, out);
            }
        } catch (Exception _) {
            return Map.of();
        }
        return out;
    }

    /**
     * Validate one judge entry and record it. Guards mirror the JCLAW-525
     * contract: non-object entries, missing/mis-typed fields, and
     * out-of-range indices are dropped; duplicate old-indices collapse. A
     * parse throw propagates to {@code parseSupersessions}' catch, which
     * discards the whole batch — same fail-open behavior as before the
     * extraction.
     */
    private static void addSupersessionIfValid(JsonElement el, int newCount, int existingCount,
            Map<Integer, List<Integer>> out) {
        if (!el.isJsonObject()) return;
        var o = el.getAsJsonObject();
        if (!o.has(KEY_NEW) || !o.has(KEY_OLD) || !o.get(KEY_OLD).isJsonArray()) return;
        int newIdx = o.get(KEY_NEW).getAsInt();
        if (newIdx < 0 || newIdx >= newCount) return;
        var olds = new ArrayList<Integer>();
        for (var oldEl : o.getAsJsonArray(KEY_OLD)) {
            int oldIdx = oldEl.getAsInt();
            if (oldIdx >= 0 && oldIdx < existingCount && !olds.contains(oldIdx)) {
                olds.add(oldIdx);
            }
        }
        if (!olds.isEmpty()) out.merge(newIdx, olds, (a, b) -> a);
    }

    private static String renderConsolidation(ConsolidationPlan plan) {
        var sb = new StringBuilder("NEW:\n");
        for (int i = 0; i < plan.survivors().size(); i++) {
            sb.append(i).append(": ").append(plan.survivors().get(i).text()).append('\n');
        }
        sb.append("\nEXISTING:\n");
        for (int i = 0; i < plan.shortlist().size(); i++) {
            sb.append(i).append(": ").append(plan.shortlist().get(i).text()).append('\n');
        }
        return sb.toString();
    }

    private static List<Candidate> dedupeWithinBatch(List<Candidate> in) {
        var kept = new ArrayList<Candidate>();
        var keptTokens = new ArrayList<Set<String>>();
        for (var c : in) {
            var toks = tokenize(c.text());
            // Tighter threshold within a single extraction — only drop near-identical.
            if (!isDuplicate(toks, keptTokens, 0.95)) {
                kept.add(c);
                keptTokens.add(toks);
            }
        }
        return kept;
    }

    private static boolean isDuplicate(Set<String> toks, List<Set<String>> against, double threshold) {
        for (var other : against) {
            if (jaccard(toks, other) >= threshold) return true;
        }
        return false;
    }

    static Set<String> tokenize(String text) {
        return MemorySimilarity.tokenize(text);
    }

    static double jaccard(Set<String> a, Set<String> b) {
        return MemorySimilarity.jaccard(a, b);
    }

    /** Package-visible: {@link MemoryReranker} parses LLM JSON the same way. */
    static String stripFences(String s) {
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.strip();
    }

    private static double safeDouble(JsonElement el) {
        try {
            return el.getAsDouble();
        } catch (Exception _) {
            try {
                return Double.parseDouble(el.getAsString().trim());
            } catch (Exception _) {
                return MemoryCategory.BASELINE_IMPORTANCE;
            }
        }
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

    /**
     * Closing turn, so the request ends on a user message.
     *
     * <p>Mem0 hands its two role-tagged messages to an ingestion endpoint that
     * reformats them server-side; this extractor is a plain chat completion,
     * where a trailing assistant message is prefill on several providers — the
     * model continues that text instead of answering. Ending here keeps the
     * role separation without inviting that.
     */
    static final String EXTRACTION_REQUEST =
            "Extract now, from the user turn above only. Output the JSON object and nothing else.";

    private static CaptureResult logged(String agentName, CaptureResult r) {
        String skipSuffix = r.skipped() > 0 ? " (%d skipped)".formatted(r.skipped()) : "";
        String reason = r.skipReason() != null ? r.skipReason() : "all_duplicates";
        String msg = r.captured() > 0
                ? "Auto-captured %d memory(ies)%s".formatted(r.captured(), skipSuffix)
                : "Auto-capture stored nothing (%s)".formatted(reason);
        EventLogger.info(EVENT_CATEGORY, agentName, null, msg);
        return r;
    }

    // ─── Extraction prompt (adapted from OpenClaw / Mem0 patterns) ────────────

    static final String EXTRACTION_INSTRUCTIONS = """
            You extract durable, reusable memories from a single conversation turn so a future session can recall them. Output ONLY a JSON object — no prose, no code fences.

            You are given the turn as real messages: a user turn, then the assistant turn that answered it. Only the USER turn is a source. The assistant turn is supplied so you can resolve pronouns and references in the user's words — never extract from it, however factual, confident or authoritative it sounds. If the substance of a memory you are about to write appears only in the assistant turn, discard that memory.

            Extract a memory ONLY when the user has conveyed something that is:
            - durable (true beyond this turn — not a transient request like "summarize this"),
            - explicit (actually stated by the USER — not your inference, and not the assistant's claim), and
            - reusable (would help a future session serve this user better).

            Do NOT extract: anything the assistant said — including its confident factual claims about products, systems or capabilities, which are frequently wrong — nor speculation, one-off task instructions, pleasantries, or sensitive secrets (passwords, full card numbers, API keys).

            A request or a question takes its subject for granted rather than stating it. "Forget my dentist's name" does not tell you the user has a dentist, and "what did I say about my accountant" does not tell you they have one. Extract what the user claims, never what their wording assumes.

            Write each memory as one concise, self-contained sentence in the third person ("The user ...", "The project ..."), resolving pronouns so it stands alone out of context. Preserve exact identifiers (names, paths, IDs, URLs) verbatim.

            Preserve every date, time, duration and quantity the turn gives, in the memory that carries the fact they belong to. Write "The user has been keeping a daily tidying routine since 2 September" rather than "The user has a daily tidying routine", and "The user attended the Seattle International Film Festival in June 2021 and watched 8 films" rather than "The user attended the Seattle International Film Festival and watched 8 films". "Concise" never means dropping the when or the how-many: a fact stripped of them cannot answer the question it was stored to answer, and the capture timestamp records when you wrote the memory, not when the thing happened.

            One fact per memory. If the turn states two things, emit two memories — "Mateo's nickname is Ziggy and Rafa's nickname is Bolt" is two facts, not one. State the fact directly: write "The user's daughter Nadia is allergic to peanuts", not "The user said that Nadia is allergic to peanuts".

            Splitting must not lose anything. A durable fact often arrives inside a passing remark: "my son Theo turns 12 next week" tells you the user has a son named Theo, which is worth keeping even though the birthday itself is transient. Extract the durable part rather than discarding the sentence.

            When the user's turn says how they are related to a person or thing, keep that relationship in the sentence: "The user's son Theo goes by Bo", not "Theo goes by Bo". Only when the turn actually says it — never guess a relationship it did not state.

            Also give each memory "questions": two or three short questions, phrased the way this user would later ask them, that this memory answers. Ask through the relationship where there is one ("what do my kids go by?") rather than by name, because that is how the question will arrive. These are used to find the memory later and are never shown to anyone.

            Classify each into exactly one category:
            - fact: a stable factual statement
            - preference: how the user likes things done
            - decision: a choice made and (if given) its rationale
            - entity: attributes of a specific named person, place, project, system, or account
            - lesson: something learned, often from a correction or mistake

            Assign each an importance from 0.0 to 1.0 (higher = more broadly and lastingly useful).

            Respond with exactly this shape, and an empty array when nothing qualifies:
            {"memories":[{"text":"...","category":"fact","importance":0.6,"questions":["...","..."]}]}
            """;

    // ─── Consolidation prompt (JCLAW-525, Mem0 UPDATE / Zep invalidation) ─────

    static final String CONSOLIDATION_INSTRUCTIONS = """
            You maintain an agent's long-term memory. You are given NEW memories (just captured) and EXISTING memories (already stored), each numbered from 0. Identify which EXISTING memories each NEW memory supersedes.

            A supersession replaces one fact with a newer version of THAT SAME FACT. The test is whether both texts answer the same question about the same thing, so that keeping both would leave the store contradictory or redundant. "The user lives in Porto" supersedes "The user lives in Berlin" — both answer "where does the user live". A duplicate phrased differently supersedes the older phrasing, and a correction supersedes the value it corrects.

            Two facts that merely mention the same person, place, project, tool or number are NOT a supersession, however strong the wording overlap. "The user has two sons, Mateo (18) and Rafa (12)" is not superseded by "The user is shopping for a telescope for 12-year-old Rafa": one answers who the user's children are and the other what they are shopping for, and retiring the first would lose Mateo entirely. Two printers, two hosts, two trips, two books and two events of the same kind are each separate facts.

            Most pairs you are shown overlap in wording without being the same fact. Pairing nothing is a correct and common answer. If you cannot name the single question both texts answer, do not pair them.

            For each supersession give "question": the one question both texts answer, in five words or fewer.

            Never judge which side is more recent — the NEW entries are always the newer ones.

            Output ONLY a JSON object — no prose, no code fences — with exactly this shape, and an empty array when nothing is superseded:
            {"supersessions":[{"new":0,"old":[2],"question":"where does the user live"}]}
            """;
}
