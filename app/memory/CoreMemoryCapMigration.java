package memory;

import com.google.gson.JsonParser;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;
import models.Memory;
import services.ConfigService;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recategorize core memories beyond the cap (JCLAW-981).
 *
 * <p>Operator-triggered rather than automatic. The tool-side rule guarantees no core
 * memory is written past {@code memory.coreload.maxCount}, but it cannot guarantee the
 * agent asks before storing something elsewhere — that half is instruction to a model. A
 * button the operator presses is enforceable in a way a mid-conversation prompt is not,
 * so this is the deliberate path for bringing an over-cap corpus back in line.
 *
 * <p>The new category comes from the owning agent's own model rather than a blind demotion
 * to {@code fact}. These are memories the operator once thought core, so flattening them
 * all into one bucket destroys the distinction between a preference, a decision and an
 * entity — and that distinction is what recall's importance blend and the admin UI both
 * read. Classification is per-agent because memories are partitioned per-agent, so the
 * model that knows the corpus is the one already configured for it.
 *
 * <p>Fail-safe, not fail-open: a memory whose classification fails is left as core rather
 * than guessed at. A wrong bucket is a silent, permanent mislabel of something the operator
 * marked important; staying over the cap is visible and re-runnable.
 */
public final class CoreMemoryCapMigration {

    private CoreMemoryCapMigration() {}

    private static final String EVENT_CATEGORY = "memory";

    /** The buckets a demoted memory may land in — everything except core itself. */
    private static final List<String> TARGETS = MemoryCategory.labels().stream()
            .filter(l -> !l.equals(MemoryCategory.CORE.label))
            .toList();

    private static final String INSTRUCTIONS = """
            You are recategorising an agent's long-term memories. Each was previously filed as
            "core" — always loaded into context — but the core tier is full, so each must move to
            the bucket that fits it best.

            Categories:
            - preference: how the user likes things done
            - decision: a choice made and (if given) its rationale
            - entity: attributes of a specific named person, place, project, system, or account
            - lesson: something learned, often from a correction or mistake
            - fact: a stable factual statement, and the fallback when none of the others fit

            Reply with ONLY a JSON array of category strings, one per memory, in the same order
            you received them. No prose, no code fences.
            """;

    /** Canned classification for tests, mirroring {@link MemoryReranker#setRankCallForTest}. */
    @FunctionalInterface
    public interface Classifier {
        List<String> classify(Agent agent, List<String> texts);
    }

    private static volatile Classifier classifierOverride;

    /** Test-only: install (or clear with {@code null}) a canned classifier. */
    public static void setClassifierForTest(Classifier override) {
        classifierOverride = override;
    }

    /** Test-only: run the pass inline for one agent, so a test need not poll the virtual thread. */
    public static void runForTest(String agentId) {
        migrate(agentId);
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger processed = new AtomicInteger();
    private static final AtomicInteger total = new AtomicInteger();
    private static volatile String lastError;
    /** Which agent the in-flight pass belongs to; null when idle. */
    private static volatile String runningFor;
    /** Which agent {@link #lastError} came from, so it surfaces on that card only. */
    private static volatile String lastErrorFor;

    /**
     * What one agent's Memory card polls. {@code overCap} is what turns its button on, and
     * is not derivable from {@code running} — an agent can be over the cap with nothing in
     * flight.
     *
     * @param running true only while THIS agent is the one being migrated
     */
    public record Status(boolean running, int processed, int total,
                         long liveCore, int cap, boolean overCap, String error) {}

    public static Status status(String agentId) {
        int cap = cap();
        long live = Tx.run(() -> Memory.countLiveCore(agentId));
        boolean mine = agentId.equals(runningFor);
        return new Status(mine, mine ? processed.get() : 0, mine ? total.get() : 0,
                live, cap, live > cap, agentId.equals(lastErrorFor) ? lastError : null);
    }

    private static int cap() {
        return ConfigService.getInt("memory.coreload.maxCount", 20);
    }

    public static String start(String agentId) {
        var s = status(agentId);
        if (!s.overCap()) {
            return "This agent is not over the core-memory cap of %d — there is nothing to migrate."
                    .formatted(s.cap());
        }
        // Single-flight across the instance, not per agent: each pass is a chain of model
        // calls, and letting several run at once would multiply that load for no gain.
        // The refusal names the agent holding the slot so the wait is explainable.
        if (!running.compareAndSet(false, true)) {
            return "A core-memory migration is already running for %s.".formatted(runningFor);
        }
        runningFor = agentId;
        lastError = null;
        lastErrorFor = null;
        processed.set(0);
        total.set(0);
        Thread.ofVirtual().name("core-memory-migration").start(CoreMemoryCapMigration::run);
        return null;
    }

    private static void run() {
        var agentId = runningFor;
        try {
            migrate(agentId);
        } catch (Exception e) {
            lastError = e.getMessage();
            lastErrorFor = agentId;
            EventLogger.warn(EVENT_CATEGORY,
                    "Core-memory migration failed: %s".formatted(e.getMessage()));
        } finally {
            runningFor = null;
            running.set(false);
        }
    }

    private static void migrate(String agentId) {
        int cap = cap();
        Agent agent = Tx.run(() -> Agent.<Agent>findById(Long.valueOf(agentId)));
        if (agent == null) return;
        // Importance 0 so this sees every core row, matching countLiveCore — one below
        // the load threshold still occupies a slot.
        List<Memory> core = Tx.run(() -> Memory.findCore(agentId, 0.0, Integer.MAX_VALUE));
        if (core.size() <= cap) return;

        // findCore's own order — importance, then recency. The survivors are therefore
        // exactly the memories the operator has been seeing in the prompt; any other
        // order silently swaps the always-loaded set.
        var overflow = core.subList(cap, core.size());
        total.addAndGet(overflow.size());
        var assigned = classify(agent, overflow.stream().map(m -> m.text).toList());

        for (int i = 0; i < overflow.size(); i++) {
            var category = assigned.get(i);
            if (category == null) continue;   // left as core, to be retried
            var id = overflow.get(i).id;
            Tx.run(() -> {
                Memory row = Memory.findById(id);
                if (row != null) {
                    row.category = category;
                    row.save();
                }
            });
            processed.incrementAndGet();
        }
        EventLogger.info(EVENT_CATEGORY, agent.name, null,
                "Core-memory migration: %d kept, %d recategorised of %d over the cap"
                        .formatted(cap, processed.get(), overflow.size()));
    }

    private static List<String> classify(Agent agent, List<String> texts) {
        var answers = classifierOverride != null
                ? classifierOverride.classify(agent, texts)
                : askModel(agent, texts);
        return sanitise(answers, texts.size());
    }

    /** Raw, unvalidated category strings from the agent's model — empty when it cannot run. */
    private static List<String> askModel(Agent agent, List<String> texts) {
        var provider = ProviderRegistry.get(agent.modelProvider);
        if (provider == null || agent.modelId == null || agent.modelId.isBlank()) {
            EventLogger.warn(EVENT_CATEGORY,
                    "Agent %s has no usable model, so its core overflow stays core".formatted(agent.name));
            return List.of();
        }
        var numbered = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            numbered.append(i).append(": ").append(texts.get(i)).append('\n');
        }
        try {
            var reply = SessionCompactor.firstChoiceText(provider.chat(agent.modelId,
                    List.of(ChatMessage.system(INSTRUCTIONS), ChatMessage.user(numbered.toString())),
                    List.of(), 1024, null, null));
            var arr = JsonParser.parseString(strip(reply)).getAsJsonArray();
            var out = new ArrayList<String>(arr.size());
            for (var el : arr) out.add(el.getAsString());
            return out;
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY,
                    "Core-memory classification failed for %s, leaving its overflow as core: %s"
                            .formatted(agent.name, e.getMessage()));
            return List.of();
        }
    }

    /**
     * Map raw answers onto valid targets, padded to {@code size} with nulls.
     *
     * <p>A model that answers {@code core} would defeat the migration entirely, and models
     * do return values outside the set they are given (JCLAW-927) — so {@code core} is
     * coerced away here rather than trusted not to appear.
     */
    private static List<String> sanitise(List<String> answers, int size) {
        var out = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) out.add(null);
        for (int i = 0; i < Math.min(answers.size(), size); i++) {
            var raw = MemoryCategory.normalize(answers.get(i));
            if (raw == null) continue;
            out.set(i, TARGETS.contains(raw) ? raw : MemoryCategory.coerceForCapture(raw));
        }
        return out;
    }

    /** Models fence JSON despite being told not to. */
    private static String strip(String s) {
        if (s == null) return "[]";
        var t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip();
    }
}
