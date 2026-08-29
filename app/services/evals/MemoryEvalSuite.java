package services.evals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A memory-recall eval suite (JCLAW-529): questions paired with the memories that answer
 * them, for measuring retrieval rather than agent behavior.
 *
 * <p>Deliberately a sibling of {@link EvalSuite} rather than a variant of it. An
 * {@link EvalCase} carries a user turn and checks scored against a model's response; a
 * recall case carries a query and gold memory ids scored against a retrieval, with no
 * model call in the loop at scoring time. Folding one into the other would give both a
 * shape that fits neither.
 *
 * <p>Keeps {@link EvalSuite}'s fingerprint idea, for the same reason: two runs are only
 * comparable if the stick did not move between them.
 */
public record MemoryEvalSuite(String id, String description, String corpusFingerprint,
                              List<MemoryEvalCase> cases) {

    private static final char FIELD_SEP = (char) 0x1F;

    public MemoryEvalSuite {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /**
     * Content hash over what decides scoring: each case's id, query, shape and gold ids, in
     * order. Excludes {@link #description}, which is prose and changes no verdict.
     *
     * <p>Shape is folded in only when it is not {@link MemoryEvalCase#SHAPE_SINGLE}
     * (JCLAW-943). A suite carrying temporal or multi-hop cases therefore fingerprints
     * differently from one built before those shapes existed, and refuses comparison against
     * it — which is the point, because the two are no longer measuring the same thing. A
     * suite of nothing but single-fact cases hashes exactly as it did before: nothing about
     * it moved, so an existing baseline stays valid rather than being invalidated by a
     * field that carries no information for it.
     */
    public String fingerprint() {
        var canonical = new StringBuilder(id);
        for (var c : cases) {
            canonical.append(FIELD_SEP).append(c.id())
                    .append(FIELD_SEP).append(c.query());
            if (!MemoryEvalCase.SHAPE_SINGLE.equals(c.shape())) {
                canonical.append(FIELD_SEP).append(c.shape());
            }
            for (var group : c.goldGroups()) {
                canonical.append(FIELD_SEP);
                for (var g : group) canonical.append(g).append(',');
            }
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable — JDK install broken?", e);
        }
    }
}
