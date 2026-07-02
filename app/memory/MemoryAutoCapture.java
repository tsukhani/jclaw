package memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;
import models.Memory;
import play.Play;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;
import utils.CircuitBreaker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Memory auto-capture (JCLAW-39). After a conversation turn completes, an
 * LLM extractor pulls durable, reusable memories from the turn and stores them
 * — no explicit "save" call from the agent. The pipeline mirrors
 * {@link SessionCompactor}: functional {@link Extractor}/{@link Consolidator}
 * seams keep the LLM calls testable, and the phases are ordered so no DB
 * transaction is held during a slow LLM call (gate → extract (no Tx) → parse →
 * plan (Tx) → consolidation judge (no Tx) → persist + supersede (Tx)).
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
 *   <li><b>Consolidation, not append.</b> Each candidate is deduplicated against
 *       the agent's recent memories by a deterministic token-Jaccard test — a
 *       NOOP when a near-duplicate already exists. Beyond the NOOP, a batched
 *       {@link Consolidator} judge (JCLAW-525) marks same-subject older
 *       memories superseded — never hard-deleted — when a new write updates or
 *       contradicts them. The judge only pairs subjects; recency is resolved
 *       by deterministic serial comparison, never by the LLM.</li>
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

    public record Candidate(String text, String category, double importance) {}

    /** Snapshot of a stored memory the consolidation judge may pair against. */
    public record Existing(Long id, String text) {}

    /**
     * Output of the plan phase: the candidates that will be stored (after the
     * deterministic near-dup NOOP and per-turn cap) plus the same-subject
     * shortlist — active memories whose token-Jaccard overlap with a survivor
     * sits in the moderate band below the dup threshold, i.e. plausibly the
     * same subject with changed content.
     */
    public record ConsolidationPlan(List<Candidate> survivors, List<Existing> shortlist) {}

    public record CaptureResult(int captured, int skipped, String skipReason) {
        static CaptureResult skipped(String reason) {
            return new CaptureResult(0, 0, reason);
        }
    }

    // Shared breaker for the production async path. Plain default tuning (no DB
    // read at class-load); tests inject their own instance so they never trip
    // this process-global one (play1 runs unit + functional tests concurrently).
    private static final CircuitBreaker SHARED_BREAKER = new CircuitBreaker(20, 0.5, 5, 30_000L);

    private record ExtractContext(LlmProvider provider, String modelId, String channelType) {}

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
                var ctx = Tx.run(() -> {
                    var conv = ConversationService.findById(conversationId);
                    if (conv == null) return null;
                    var provider = resolveProvider(agent);
                    if (provider == null) return null;
                    return new ExtractContext(provider, resolveModelId(agent), conv.channelType);
                });
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
     * Whether a just-completed turn on {@code agent} is eligible for auto-capture,
     * independent of the async / test-mode plumbing in {@link #captureAsync}.
     * Capture is for operator-facing (root) agents only — subagents process
     * delegated work, not the operator's own turns (JCLAW-539) — and only when the
     * agent has auto-capture enabled (JCLAW-534, on by default).
     */
    public static boolean captureEligible(Agent agent) {
        return agent != null && !agent.isSubagent() && agent.memoryAutocaptureEnabled;
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
                    ChatMessage.user(renderTurn(userMessage, assistantResponse)));
            raw = extractor.extract(messages);
            breaker.recordSuccess();
        } catch (Exception e) {
            breaker.recordFailure();
            EventLogger.warn(EVENT_CATEGORY, agentName, null,
                    "Auto-capture extraction failed: %s".formatted(e.getMessage()));
            return CaptureResult.skipped("extraction_error");
        }

        List<Candidate> deduped = dedupeWithinBatch(parseCandidates(raw));

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

        if (candidates.isEmpty()) {
            return logged(agentName, CaptureResult.skipped("no_candidates"));
        }

        int maxPerTurn = ConfigService.getInt("memory.autocapture.maxPerTurn", 5);
        double dupThreshold = ConfigService.getDouble("memory.autocapture.dedup.threshold", 0.85);
        int dedupScan = ConfigService.getInt("memory.autocapture.dedup.scanLimit", 100);

        // JCLAW-525 split the old single-Tx persist into plan (Tx) → judge
        // (LLM, no Tx) → apply (Tx), preserving the "no Tx held during an LLM
        // call" invariant the pipeline is built around.
        var plan = Tx.run(() -> plan(agentKey, candidates, maxPerTurn, dupThreshold, dedupScan));
        var supersessions = judgeSupersessions(agentName, plan, consolidator, breaker);
        int stored = Tx.run(() -> applyPlan(agentKey, agentName, plan, supersessions));
        return logged(agentName, new CaptureResult(stored, candidates.size() - stored, null));
    }

    /**
     * Plan phase (in Tx): scan the agent's recent <em>active</em> memories once,
     * drop candidates with a near-duplicate (deterministic token-Jaccard NOOP —
     * portable across H2/Postgres, no dialect-specific search backend), cap at
     * {@code maxPerTurn}, and collect the consolidation shortlist for the judge.
     * Superseded rows are excluded from the scan ({@code findByAgent} filters
     * them), so a superseded old fact can never NOOP a re-emerging new one.
     */
    private static ConsolidationPlan plan(String agentKey, List<Candidate> candidates,
                                          int maxPerTurn, double dupThreshold, int dedupScan) {
        double shortlistMin = ConfigService.getDouble("memory.consolidation.shortlist.minJaccard", 0.2);
        int shortlistCap = ConfigService.getInt("memory.consolidation.shortlist.maxPerCandidate", 5);

        var recent = Memory.findByAgent(agentKey, dedupScan);
        var recentTokens = new ArrayList<Set<String>>();
        for (var m : recent) {
            recentTokens.add(tokenize(m.text));
        }

        var survivors = new ArrayList<Candidate>();
        var survivorTokens = new ArrayList<Set<String>>();
        var shortlist = new ArrayList<Existing>();
        var shortlisted = new HashSet<Long>();
        for (var c : candidates) {
            if (survivors.size() >= maxPerTurn) break;
            var toks = tokenize(c.text());
            if (isDuplicate(toks, recentTokens, dupThreshold)
                    || isDuplicate(toks, survivorTokens, dupThreshold)) continue;
            survivors.add(c);
            survivorTokens.add(toks);
            // Same-subject shortlist: moderate overlap below the dup threshold
            // (at/above it the candidate would have been NOOPed as a duplicate).
            int added = 0;
            for (int i = 0; i < recent.size() && added < shortlistCap; i++) {
                double j = jaccard(toks, recentTokens.get(i));
                if (j >= shortlistMin && j < dupThreshold && shortlisted.add(recent.get(i).id)) {
                    shortlist.add(new Existing(recent.get(i).id, recent.get(i).text));
                    added++;
                }
            }
        }
        return new ConsolidationPlan(survivors, shortlist);
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
     * superseded. The judge only nominates same-subject pairs; the direction is
     * enforced here deterministically — a row is superseded only when its id
     * (serial) is strictly lower than the new row's, i.e. the newer write
     * always wins and the LLM can never flip recency (JCLAW-525 AC). Each
     * supersession is event-logged.
     */
    private static int applyPlan(String agentKey, String agentName, ConsolidationPlan plan,
                                 Map<Integer, List<Integer>> supersessions) {
        var store = MemoryStoreFactory.get();
        int stored = 0;
        for (int i = 0; i < plan.survivors().size(); i++) {
            var c = plan.survivors().get(i);
            var newIdStr = store.store(agentKey, c.text(), c.category(), c.importance());
            stored++;
            var olds = supersessions.get(i);
            if (olds == null || olds.isEmpty()) continue;
            long newId = Long.parseLong(newIdStr);
            for (int oldIdx : olds) {
                var ex = plan.shortlist().get(oldIdx);
                if (ex.id() >= newId) continue;   // serial guard: newer always wins
                Memory old = Memory.findById(ex.id());
                if (old == null || old.supersededAt != null) continue;
                old.supersede(newId);
                EventLogger.info(EVENT_CATEGORY, agentName, null,
                        "Memory %d superseded by %d: \"%s\" → \"%s\""
                                .formatted(ex.id(), newId, snippet(ex.text()), snippet(c.text())));
            }
        }
        return stored;
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
                var category = (o.has(KEY_CATEGORY) && !o.get(KEY_CATEGORY).isJsonNull())
                        ? MemoryCategory.normalize(o.get(KEY_CATEGORY).getAsString()) : null;
                if (category == null) category = MemoryCategory.FACT.label;
                double importance = (o.has(KEY_IMPORTANCE) && !o.get(KEY_IMPORTANCE).isJsonNull())
                        ? clamp01(safeDouble(o.get(KEY_IMPORTANCE)))
                        : MemoryCategory.defaultImportanceFor(category);
                out.add(new Candidate(text, category, importance));
            }
        } catch (Exception _) {
            return new ArrayList<>();
        }
        return out;
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
                if (!el.isJsonObject()) continue;
                var o = el.getAsJsonObject();
                if (!o.has(KEY_NEW) || !o.has(KEY_OLD) || !o.get(KEY_OLD).isJsonArray()) continue;
                int newIdx = o.get(KEY_NEW).getAsInt();
                if (newIdx < 0 || newIdx >= newCount) continue;
                var olds = new ArrayList<Integer>();
                for (var oldEl : o.getAsJsonArray(KEY_OLD)) {
                    int oldIdx = oldEl.getAsInt();
                    if (oldIdx >= 0 && oldIdx < existingCount && !olds.contains(oldIdx)) {
                        olds.add(oldIdx);
                    }
                }
                if (!olds.isEmpty()) out.merge(newIdx, olds, (a, b) -> a);
            }
        } catch (Exception _) {
            return Map.of();
        }
        return out;
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
        var set = new HashSet<String>();
        if (text == null) return set;
        for (var tok : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!tok.isBlank()) set.add(tok);
        }
        return set;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        for (var x : a) if (b.contains(x)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
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

    private static String renderTurn(String userMessage, String assistantResponse) {
        return "[USER]\n" + userMessage.strip() + "\n\n[ASSISTANT]\n" + assistantResponse.strip();
    }

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
            You extract durable, reusable memories from a single conversation turn (one user message and the assistant's reply) so a future session can recall them. Output ONLY a JSON object — no prose, no code fences.

            Extract a memory ONLY when the user has conveyed something that is:
            - durable (true beyond this turn — not a transient request like "summarize this"),
            - explicit (actually stated, not your inference or assumption), and
            - reusable (would help a future session serve this user better).

            Do NOT extract: the assistant's own suggestions or opinions, speculation, one-off task instructions, pleasantries, or sensitive secrets (passwords, full card numbers, API keys).

            Write each memory as one concise, self-contained sentence in the third person ("The user ...", "The project ..."), resolving pronouns so it stands alone out of context. Preserve exact identifiers (names, paths, IDs, URLs) verbatim.

            Classify each into exactly one category:
            - core: identity-defining, always-relevant facts about the user or their setup
            - fact: a stable factual statement
            - preference: how the user likes things done
            - decision: a choice made and (if given) its rationale
            - entity: attributes of a specific named person, place, project, system, or account
            - lesson: something learned, often from a correction or mistake

            Assign each an importance from 0.0 to 1.0 (higher = more broadly and lastingly useful).

            Respond with exactly this shape, and an empty array when nothing qualifies:
            {"memories":[{"text":"...","category":"fact","importance":0.6}]}
            """;

    // ─── Consolidation prompt (JCLAW-525, Mem0 UPDATE / Zep invalidation) ─────

    static final String CONSOLIDATION_INSTRUCTIONS = """
            You maintain an agent's long-term memory. You are given NEW memories (just captured) and EXISTING memories (already stored), each numbered from 0. Identify which EXISTING memories each NEW memory supersedes: same subject, where the NEW text updates, contradicts, or restates the EXISTING information. For example, "The user lives in Porto" supersedes "The user lives in Berlin", and a duplicate phrased differently supersedes the older phrasing.

            Pair only memories that are genuinely about the same subject; related-but-different facts must NOT be paired. Never judge which side is more recent — the NEW entries are always the newer ones.

            Output ONLY a JSON object — no prose, no code fences — with exactly this shape, and an empty array when nothing is superseded:
            {"supersessions":[{"new":0,"old":[2]}]}
            """;
}
