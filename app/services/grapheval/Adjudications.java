package services.grapheval;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import services.grapheval.GraphEvalScorer.GateRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Two-sided adjudication (JCLAW-1368): verdicts on the unmatched written records and on a hash-drawn sample of the
 * agreed ones, each stamped with the guide version it was judged under. Pure: parsing, drawing and judging only.
 */
public final class Adjudications {

    public static final String UNMATCHED = "unmatched";
    public static final String AGREED = "agreed";
    public static final String WRONG = Certifier.WRONG;
    public static final String LABEL_ERROR = Certifier.LABEL_ERROR;
    public static final String RIGHT = "right";
    public static final String OPERATOR = "operator";
    public static final String MODEL_PREFIX = "model:";
    public static final String AGREE = "agree";
    public static final String DISAGREE = "disagree";
    public static final double DEFAULT_SHARE = 0.2;
    public static final double DEFAULT_CHECK_SHARE = 0.2;
    /** The agreed sample is drawn over the agreed records written at this threshold, the widest set. */
    public static final double DRAWN_AT = ExtractionPipeline.KEPT;
    /** The held-out set's verdicts, under {@code data/graph-eval/}; the committed set's are {@link GraphCases#ADJUDICATIONS_PATH}. */
    public static final String HELDOUT_FILE = "adjudications.json";

    private static final Set<String> KEYS = Set.of("caseId", "record", "side", "verdict", "guide", "adjudicator",
            "inclusion", "check", "note");
    private static final double TWO_TO_64 = 0x1p64;
    private static final String CHECK_PREFIX = "check:";

    private Adjudications() {}

    /**
     * One verdict. {@code side} is {@link #UNMATCHED} (verdict {@code wrong} or {@code label-error}),
     * {@link #AGREED} ({@code right} or {@code wrong}), or null for a blind verdict ({@code right} or {@code wrong}),
     * which takes the side of the record it judges; {@code adjudicator} is {@link #OPERATOR} or {@code model:<name>},
     * and {@code check} the operator's {@code agree} or {@code disagree} on a model's verdict.
     */
    public record Verdict(String caseId, String record, @Nullable String side, String verdict, String guide,
                          String adjudicator, @Nullable Double inclusion, @Nullable String check, String note) {
        public boolean byModel() {
            return adjudicator.startsWith(MODEL_PREFIX);
        }

        /** This verdict on a record of the given side: a blind {@code right} on an unmatched record is a label error. */
        public String on(boolean agreed) {
            return side == null && !agreed && verdict.equals(RIGHT) ? LABEL_ERROR : verdict;
        }
    }

    /**
     * The verdicts in {@code json}.
     *
     * @throws IllegalArgumentException on a malformed file, an unknown key or any value outside the format
     */
    public static List<Verdict> parse(String json) {
        var out = new ArrayList<Verdict>();
        try {
            var root = JsonParser.parseString(json);
            if (!root.isJsonArray()) throw new IllegalArgumentException("adjudications: the document must be an array");
            int i = 0;
            for (var e : root.getAsJsonArray()) {
                var where = "adjudication #" + i++;
                if (!e.isJsonObject()) throw new IllegalArgumentException(where + ": must be an object");
                var o = e.getAsJsonObject();
                GraphCases.onlyKeys(o, KEYS, where);
                var side = o.has("side") ? GraphCases.text(o, "side", where) : null;
                var verdict = GraphCases.text(o, "verdict", where);
                if (side == null) {
                    if (!verdict.equals(RIGHT) && !verdict.equals(WRONG)) {
                        throw new IllegalArgumentException(where + ": a verdict without a side is 'right' or 'wrong'");
                    }
                } else if (side.equals(UNMATCHED)) {
                    if (!verdict.equals(WRONG) && !verdict.equals(LABEL_ERROR)) {
                        throw new IllegalArgumentException(where + ": an unmatched verdict is 'wrong' or 'label-error'");
                    }
                } else if (side.equals(AGREED)) {
                    if (!verdict.equals(RIGHT) && !verdict.equals(WRONG)) {
                        throw new IllegalArgumentException(where + ": an agreed verdict is 'right' or 'wrong'");
                    }
                } else {
                    throw new IllegalArgumentException(where + ": side must be 'unmatched' or 'agreed'");
                }
                var guide = GraphCases.text(o, "guide", where);
                if (!guide.startsWith("guide@")) throw new IllegalArgumentException(where + ": guide must be guide@<hex>");
                var adjudicator = GraphCases.text(o, "adjudicator", where);
                if (!adjudicator.equals(OPERATOR)
                        && !(adjudicator.startsWith(MODEL_PREFIX) && adjudicator.length() > MODEL_PREFIX.length())) {
                    throw new IllegalArgumentException(where + ": adjudicator must be 'operator' or 'model:<name>'");
                }
                Double inclusion = null;
                if (o.has("inclusion")) {
                    var raw = o.get("inclusion");
                    if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()
                            || !(raw.getAsDouble() > 0 && raw.getAsDouble() <= 1)) {
                        throw new IllegalArgumentException(where + ": inclusion must be a number in (0, 1]");
                    }
                    inclusion = raw.getAsDouble();
                }
                String check = null;
                if (o.has("check")) {
                    check = GraphCases.text(o, "check", where);
                    if (!check.equals(AGREE) && !check.equals(DISAGREE)) {
                        throw new IllegalArgumentException(where + ": check must be 'agree' or 'disagree'");
                    }
                    if (adjudicator.equals(OPERATOR)) {
                        throw new IllegalArgumentException(where + ": only a model's verdict takes a check");
                    }
                }
                var note = o.has("note") ? GraphCases.text(o, "note", where) : "";
                out.add(new Verdict(GraphCases.text(o, "caseId", where), GraphCases.text(o, "record", where), side,
                        verdict, guide, adjudicator, inclusion, check, note));
            }
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("adjudications are not valid JSON: " + e.getMessage(), e);
        }
        return List.copyOf(out);
    }

    /**
     * The first 8 bytes of SHA-256({@code seed:id:key}) as a fraction of 2^64: a record is drawn when this falls below
     * the share, so a smaller share draws a subset of a larger one.
     */
    public static double fraction(long seed, String id, String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((seed + ":" + id + ":" + key).getBytes(StandardCharsets.UTF_8));
            long bits = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
            double unsigned = bits >= 0 ? bits : bits + TWO_TO_64;
            return unsigned / TWO_TO_64;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static boolean sampled(long seed, String id, String key, double share) {
        return fraction(seed, id, key) < share;
    }

    /** What the verdicts under one guide version say about the records written at one configuration. */
    public record Judgement(Map<String, Integer> unjudgedByGate, List<GateRecord> unjudged, List<GateRecord> sampled,
                            boolean labelError, int marked, int checked, int disagreed, int uncheckedMarked,
                            @Nullable Double disagreementRate) {
        public Judgement {
            unjudgedByGate = Collections.unmodifiableMap(new TreeMap<>(unjudgedByGate));
            unjudged = List.copyOf(unjudged);
            sampled = List.copyOf(sampled);
        }

        public int unjudgedCount() {
            return unjudged.size();
        }
    }

    /** The verdicts under {@code guide}, looked up by case and record; later lines win. */
    public static final class Book {
        private final Map<List<String>, Verdict> current = new HashMap<>();
        private final long seed;
        private final double share;
        private final double checkShare;

        public Book(List<Verdict> verdicts, String guide, long seed, double share, double checkShare) {
            this.seed = seed;
            this.share = share;
            this.checkShare = checkShare;
            for (var v : verdicts) {
                if (v.guide().equals(guide)) current.put(List.of(v.caseId(), v.record()), v);
            }
        }

        public static Book empty(String guide) {
            return new Book(List.of(), guide, 0, DEFAULT_SHARE, DEFAULT_CHECK_SHARE);
        }

        public long seed() {
            return seed;
        }

        public double share() {
            return share;
        }

        public double checkShare() {
            return checkShare;
        }

        public boolean sampled(GateRecord r) {
            return r.agreed() && Adjudications.sampled(seed, r.caseId(), r.record(), share);
        }

        private @Nullable Verdict verdict(GateRecord r) {
            var v = current.get(List.of(r.caseId(), r.record()));
            if (v == null) return null;
            var side = v.side();
            if (side != null && !side.equals(r.agreed() ? AGREED : UNMATCHED)) return null;
            return v;
        }

        /** The model-verdict check's own draw: the same hash rule over a key the agreed draw never uses. */
        public boolean checkMarked(GateRecord r) {
            return Adjudications.sampled(seed, r.caseId(), CHECK_PREFIX + r.record(), checkShare);
        }

        /** 1 / inclusion when {@code r} is a sampled agreed record judged wrong, else 0. */
        public double agreedWrongWeight(GateRecord r) {
            if (!sampled(r)) return 0;
            var v = verdict(r);
            return v != null && v.verdict().equals(WRONG) ? 1 / share : 0;
        }

        /**
         * The records in scope judged: an unmatched record, or a sampled agreed one, with no verdict is unjudged;
         * an unsampled agreed record never is. Model verdicts are marked for an operator check by the same hash rule
         * at the check share.
         */
        public Judgement judge(List<GateRecord> records) {
            var byGate = new TreeMap<String, Integer>();
            var unjudged = new ArrayList<GateRecord>();
            var sample = new ArrayList<GateRecord>();
            boolean labelError = false;
            int marked = 0;
            int checked = 0;
            int disagreed = 0;
            for (var r : records) {
                if (r.agreed() && !sampled(r)) continue;
                if (r.agreed()) sample.add(r);
                var v = verdict(r);
                if (v == null) {
                    unjudged.add(r);
                    byGate.merge(r.gate(), 1, Integer::sum);
                    continue;
                }
                if (v.on(r.agreed()).equals(LABEL_ERROR)) labelError = true;
                if (!v.byModel() || !checkMarked(r)) continue;
                marked++;
                var check = v.check();
                if (check == null) continue;
                checked++;
                if (check.equals(DISAGREE)) disagreed++;
            }
            return new Judgement(byGate, unjudged, sample, labelError, marked, checked, disagreed, marked - checked,
                    checked == 0 ? null : (double) disagreed / checked);
        }
    }
}
