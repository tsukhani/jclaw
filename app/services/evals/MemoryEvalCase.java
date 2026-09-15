package services.evals;

import java.util.List;

/**
 * One recall case: a question, and the memories that answer it (JCLAW-529).
 *
 * @param shape which generator built the case, so a report can break a score down by case
 *        kind (JCLAW-943). An aggregate alone hides a shape-specific regression: a change
 *        that helps single-fact recall and breaks multi-hop nets out to noise at the sizes
 *        these suites run at. Absent in suites written before shapes existed, and
 *        normalized to {@link #SHAPE_SINGLE} on read so those still load.
 * @param goldGroups one group per <em>distinct fact</em> the question should surface,
 *        each listing every memory that states that fact.
 *
 *        <p>Two levels, because two different things are being measured. Within a group,
 *        any member counts: a corpus holding one fact three times has three correct
 *        retrievals, and scoring only the source would mark a paraphrase a miss and
 *        penalize exactly what dedup produces. Across groups, each one is a separate
 *        thing the answer needs — which is what makes coverage measurable at all.
 *
 *        <p>A single-fact case is simply a case with one group, so the coverage metric
 *        degrades to plain recall rather than being a second, parallel scheme.
 */
public record MemoryEvalCase(String id, String query, String shape, List<List<Long>> goldGroups) {

    /** One fact, one question — the original shape, and what an unstamped case is read as. */
    public static final String SHAPE_SINGLE = "single";
    public static final String SHAPE_COVERAGE = "coverage";
    public static final String SHAPE_BRIDGE = "bridge";
    public static final String SHAPE_TEMPORAL = "temporal";
    public static final String SHAPE_MULTIHOP = "multihop";

    public MemoryEvalCase {
        shape = shape == null || shape.isBlank() ? SHAPE_SINGLE : shape;
        goldGroups = goldGroups == null ? List.of() : goldGroups.stream().map(List::copyOf).toList();
    }

    /** Pre-JCLAW-943 shape: a case whose kind was implied by the suite that held it. */
    public MemoryEvalCase(String id, String query, List<List<Long>> goldGroups) {
        this(id, query, SHAPE_SINGLE, goldGroups);
    }

    /** Every acceptable memory id, flattened — for "did anything correct come back". */
    public List<Long> allGoldIds() {
        return goldGroups.stream().flatMap(List::stream).toList();
    }
}
