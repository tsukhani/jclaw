package memory;

import llm.LlmTypes.ChatMessage;
import models.Agent;
import models.Memory;
import services.EventLogger;
import services.Tx;
import services.evals.MemoryEvalGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Brings an existing corpus up to the JCLAW-529 storage contract: identity facts in the
 * always-loaded core tier, and every memory carrying the questions it answers.
 *
 * <p>Both tiers only change what capture writes <em>next</em>. A corpus already stored
 * keeps the shape that produced the defect — on the corpus this was measured against, all
 * 89 memories predated the change and none were in the core tier.
 *
 * <p>Two passes, deliberately separated:
 *
 * <ol>
 *   <li><b>Promote.</b> Deterministic and instant — {@link MemoryIdentityClass} over the
 *       stored text, lifting importance to core's default so {@code findCore}'s
 *       {@code minImportance} filter does not hide what was just promoted.</li>
 *   <li><b>Key.</b> One model call per un-keyed memory, then a re-embed. Slow and
 *       network-bound, so it runs after the free pass rather than gating it.</li>
 * </ol>
 *
 * <p><b>Neighbor context is what makes the keys worth generating.</b> Asked to write
 * questions for "Mateo's nickname is Ziggy" alone, a model can only produce questions
 * naming Mateo — which is the vocabulary that already worked. Shown the sibling memory
 * that says Mateo is the user's son, it can write "what do my kids go by?", and that is
 * the question the corpus could not previously answer. Neighbours are found by shared
 * entity name.
 *
 * <p>Rewrites no stored text. A key is additive and reversible; editing the operator's
 * own memories to a shape a model preferred is neither.
 */
public final class MemoryKeyBackfillService {

    private MemoryKeyBackfillService() {}

    private static final String EVENT_CATEGORY = "memory";

    /** Beyond a handful the context stops being "related" and starts being the corpus. */
    private static final int MAX_NEIGHBOURS = 4;

    private static final String INSTRUCTIONS = """
            You write retrieval keys for a personal memory store. Given one stored MEMORY and \
            some RELATED memories for context, write two or three short questions, phrased the \
            way this user would later ask them, that the MEMORY answers.

            Every question must be answered by the MEMORY itself. The RELATED memories are \
            background only: use them to learn how the user refers to the MEMORY's subject, \
            never as subject matter. If the MEMORY says the user's name and a RELATED memory \
            says they own a company, ask about the name — a question about the company is wrong \
            here however sensible it looks.

            Given that, prefer asking through a relationship over asking by name: if a RELATED \
            memory shows the MEMORY's subject is the user's son, ask "what do my kids ...", not \
            "what does <name> ...". The name already matches the memory's own words; the \
            relationship is what it cannot be found by today.

            Output only the questions, one per line, no numbering and no quotation marks.""";

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger processed = new AtomicInteger();
    private static final AtomicInteger total = new AtomicInteger();
    private static final AtomicInteger promoted = new AtomicInteger();
    private static final AtomicInteger keyed = new AtomicInteger();
    private static volatile String lastError;

    public record Status(boolean running, int processed, int total, int promoted, int keyed,
                         String error) {}

    public static Status status() {
        return new Status(running.get(), processed.get(), total.get(), promoted.get(), keyed.get(),
                lastError);
    }

    /** A memory lifted out of its transaction, so the model calls hold no connection. */
    private record Row(Long id, String text, String retrievalKey) {}

    /**
     * Promote identity facts, then key every un-keyed memory of {@code agent}.
     *
     * @return false when a run is already in flight
     */
    public static boolean start(Agent agent) {
        if (!running.compareAndSet(false, true)) return false;
        processed.set(0);
        promoted.set(0);
        keyed.set(0);
        lastError = null;
        Thread.ofVirtual().name("memory-key-backfill").start(() -> {
            try {
                run(agent);
            } catch (Exception e) {
                lastError = e.getMessage();
                EventLogger.warn(EVENT_CATEGORY, "Key backfill failed: %s".formatted(e.getMessage()));
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    private static void run(Agent agent) {
        var rows = load(agent);
        total.set(rows.size());
        promoteIdentityFacts(rows);

        var writer = MemoryEvalGenerator.writerFor(agent);
        var store = MemoryStoreFactory.get();
        for (var row : rows) {
            processed.incrementAndGet();
            if (row.retrievalKey() != null && !row.retrievalKey().isBlank()) continue;
            if (writer == null) continue;
            var key = keyFor(row, rows, writer);
            if (key == null) continue;
            Tx.run(() -> {
                Memory m = Memory.findById(row.id());
                if (m != null) {
                    m.retrievalKey = key;
                    m.save();
                }
            });
            // The key is only in the index once the row is re-embedded; without this the
            // column is set and retrieval behaves exactly as it did before.
            store.embedStored(String.valueOf(row.id()));
            keyed.incrementAndGet();
        }
    }

    private static List<Row> load(Agent agent) {
        return Tx.run(() -> Memory.<Memory>find(
                        "agent.id = ?1 AND supersededAt IS NULL ORDER BY id", agent.id).<Memory>fetch()
                .stream().map(m -> new Row(m.id, m.text, m.retrievalKey)).toList());
    }

    /** Deterministic pass: no model call, so it lands even if the keying pass never runs. */
    private static void promoteIdentityFacts(List<Row> rows) {
        for (var row : rows) {
            if (!MemoryIdentityClass.isIdentity(row.text())) continue;
            Tx.run(() -> {
                Memory m = Memory.findById(row.id());
                if (m == null || MemoryCategory.CORE.label.equals(m.category)) return;
                m.category = MemoryCategory.CORE.label;
                m.importance = Math.max(m.importance,
                        MemoryCategory.defaultImportanceFor(MemoryCategory.CORE.label));
                m.save();
                promoted.incrementAndGet();
            });
        }
    }

    /** Memories sharing an entity name with {@code row}. */
    private static List<String> neighbours(Row row, List<Row> all) {
        var names = JpaMemoryStore.entityNames(row.text());
        var related = new ArrayList<String>();
        for (var other : all) {
            if (related.size() >= MAX_NEIGHBOURS) break;
            if (other.id().equals(row.id())) continue;
            if (names.stream().anyMatch(n -> other.text().contains(n))) related.add(other.text());
        }
        return related;
    }

    /*
     * No deterministic guard rejects a key for describing a neighbor instead of its own
     * memory, though the failure is real: "The user's name is Sam." came back keyed "what
     * does my business do / what is abundent", which a neighbouring memory answers.
     *
     * A token-overlap guard was written for it and then measured against the corpus, where it
     * flagged 5 of 89 — including the single most valuable key in the store, the one keying
     * the children's-nicknames memory as "what are my sons' nicknames / what do my kids
     * have". That key is the entire point of the feature, and the guard rejected it for the
     * same property that makes it work: a bridge key borrows the neighbor's relation
     * vocabulary by construction, so it necessarily overlaps the neighbor more than its own
     * memory. Stemming makes it worse — "nickname" and "nicknames" do not match.
     *
     * The measured miskey rate is roughly 2 in 89 and its cost is bounded: a bad key adds a
     * wrong candidate that ranking still has to beat the rest of the corpus to surface. A
     * guard that removes the best key to remove the worst one is the more expensive trade.
     */

    private static String keyFor(Row row, List<Row> all, MemoryEvalGenerator.QuestionWriter writer) {
        var related = neighbours(row, all);
        var prompt = "MEMORY: %s\nRELATED:\n%s".formatted(row.text(),
                related.isEmpty() ? "(none)" : String.join("\n", related));
        try {
            var out = writer.write(List.of(ChatMessage.system(INSTRUCTIONS), ChatMessage.user(prompt)));
            if (out == null || out.isBlank()) return null;
            return out.lines().map(String::strip).filter(s -> !s.isEmpty()).limit(4)
                    .reduce((a, b) -> a + "\n" + b).orElse(null);
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY,
                    "Key generation failed for memory %d: %s".formatted(row.id(), e.getMessage()));
            return null;
        }
    }

    static void resetForTest() {
        running.set(false);
        processed.set(0);
        total.set(0);
        promoted.set(0);
        keyed.set(0);
        lastError = null;
    }
}
