package services.graphspike;

import org.jspecify.annotations.Nullable;
import services.graphspike.ExtractionPipeline.CaseRun;
import services.graphspike.ExtractionPipeline.Decision;
import services.graphspike.GraphCases.Case;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Scores harness runs against the case labels. Pure: no I/O, no clock. */
public final class GraphSpikeScorer {

    /** Relations the labels may give in either direction. */
    public static final Set<String> SYMMETRIC = Set.of("same_as", "family_of");
    /** The thresholds the curve reports, in hundredths: 0.30 to 0.95 by 0.05. */
    private static final int CURVE_FROM = 30;
    private static final int CURVE_TO = 95;
    private static final int CURVE_STEP = 5;

    private GraphSpikeScorer() {}

    /** One proposer × decision model, with every case's run; {@code skipped} says why nothing ran. */
    public record PairingRun(String proposer, String model, @Nullable String skipped, List<CaseRun> cases) {
        public PairingRun {
            cases = List.copyOf(cases);
        }
    }

    /**
     * One pairing at one threshold. {@code wrongShare} is wrong / written and {@code abstentionRate} abstained /
     * decisions, each null when its denominator is zero.
     */
    public record PairingScore(String proposer, String model, @Nullable String skipped, int cases, int written,
                               int wrong, int wrongMatch, int wrongType, int wrongRelation, @Nullable Double wrongShare,
                               int decisions, int abstentions, @Nullable Double abstentionRate, int decisionFailures,
                               int proposerFailures, int discardedMentions, int missedLabels, int prunedPairs) {}

    public record CurvePoint(double threshold, String proposer, String model, int written, int wrong,
                             @Nullable Double wrongShare, @Nullable Double abstentionRate) {}

    /**
     * A decision model's verdict: allowed at the run's threshold, its worst wrong share there across proposers, and
     * every curve threshold it is allowed at.
     */
    public record ModelVerdict(String model, boolean allowed, @Nullable Double worstWrongShare,
                               List<Double> allowedThresholds, @Nullable Double lowestAllowedThreshold) {}

    public record Score(double threshold, List<PairingScore> pairings, List<CurvePoint> curve,
                        List<ModelVerdict> models, List<String> allowed) {}

    public static Score score(List<Case> cases, List<PairingRun> runs, double threshold) {
        var byId = cases.stream().collect(Collectors.toMap(Case::id, Function.identity()));
        var sorted = runs.stream()
                .sorted(Comparator.comparing(PairingRun::proposer).thenComparing(PairingRun::model))
                .toList();

        var pairings = sorted.stream().map(r -> pairing(byId, r, threshold)).toList();
        var curve = new ArrayList<CurvePoint>();
        var curveScores = new TreeMap<Integer, List<PairingScore>>();
        for (int k = CURVE_FROM; k <= CURVE_TO; k += CURVE_STEP) {
            double t = k / 100.0;
            var at = sorted.stream().map(r -> pairing(byId, r, t)).toList();
            curveScores.put(k, at);
            for (var s : at) {
                curve.add(new CurvePoint(t, s.proposer(), s.model(), s.written(), s.wrong(), s.wrongShare(),
                        s.abstentionRate()));
            }
        }

        var models = new ArrayList<ModelVerdict>();
        var allowed = new ArrayList<String>();
        for (var model : sorted.stream().map(PairingRun::model).distinct().sorted().toList()) {
            var allowedAt = new ArrayList<Double>();
            curveScores.forEach((k, at) -> {
                if (allowed(of(at, model))) allowedAt.add(k / 100.0);
            });
            var here = of(pairings, model);
            boolean ok = allowed(here);
            if (ok) allowed.add(model);
            models.add(new ModelVerdict(model, ok, worst(here), allowedAt, allowedAt.isEmpty() ? null : allowedAt.getFirst()));
        }
        return new Score(threshold, pairings, curve, models, allowed);
    }

    /**
     * Whether a pairing's record passes the gate: at least one record written, and at most one in twenty wrong.
     * Integer arithmetic, so exactly 0.05 passes.
     */
    public static boolean passes(int wrong, int written) {
        return written > 0 && (long) wrong * 20 <= written;
    }

    /** Every proposer's pairing with {@code model} must pass: the production proposer is whichever the agent uses. */
    private static boolean allowed(List<PairingScore> pairings) {
        return !pairings.isEmpty()
                && pairings.stream().allMatch(p -> p.skipped() == null && passes(p.wrong(), p.written()));
    }

    private static @Nullable Double worst(List<PairingScore> pairings) {
        return pairings.stream().map(PairingScore::wrongShare).filter(Objects::nonNull)
                .max(Double::compare).orElse(null);
    }

    private static List<PairingScore> of(List<PairingScore> scores, String model) {
        return scores.stream().filter(s -> s.model().equals(model)).toList();
    }

    static PairingScore pairing(Map<String, Case> cases, PairingRun run, double t) {
        var tally = new Tally();
        if (run.skipped() == null) {
            for (var caseRun : run.cases().stream().sorted(Comparator.comparing(CaseRun::caseId)).toList()) {
                var c = cases.get(caseRun.caseId());
                if (c == null) throw new IllegalArgumentException("no labelled case " + caseRun.caseId());
                tally.add(c, caseRun, t);
            }
        }
        int wrong = tally.wrongMatch + tally.wrongType + tally.wrongRelation;
        return new PairingScore(run.proposer(), run.model(), run.skipped(), run.cases().size(), tally.written, wrong,
                tally.wrongMatch, tally.wrongType, tally.wrongRelation, ratio(wrong, tally.written), tally.decisions,
                tally.abstentions, ratio(tally.abstentions, tally.decisions), tally.decisionFailures,
                tally.proposerFailures, tally.discarded, tally.missed, tally.pruned);
    }

    private static @Nullable Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static final class Tally {
        int written;
        int wrongMatch;
        int wrongType;
        int wrongRelation;
        int decisions;
        int abstentions;
        int decisionFailures;
        int proposerFailures;
        int discarded;
        int missed;
        int pruned;

        void add(Case c, CaseRun run, double t) {
            discarded += run.discardedMentions();
            pruned += run.prunedPairs();
            if (run.proposerFailure() != null) proposerFailures++;

            var termWritten = new HashSet<String>();
            var termCorrect = new HashMap<String, Boolean>();
            var relationsRight = new HashSet<GraphCases.Relation>();
            var symmetricWritten = new HashSet<List<String>>();
            for (var d : run.decisions()) {
                if (!d.stage().equals(ExtractionPipeline.TERM)) continue;
                var choice = counted(d, t);
                if (choice == null || choice.equals(ExtractionPipeline.NOT_AN_ENTITY)) continue;
                written++;
                termWritten.add(d.subject());
                var labelled = c.typeOf(d.subject());
                if (labelled.isEmpty()) {
                    wrongMatch++;
                } else if (!labelled.get().equals(choice)) {
                    wrongType++;
                }
                termCorrect.put(d.subject(), labelled.isPresent() && labelled.get().equals(choice));
            }
            for (var d : run.decisions()) {
                if (!d.stage().equals(ExtractionPipeline.RELATION)) continue;
                var choice = counted(d, t);
                var from = d.from();
                var to = d.to();
                if (choice == null || choice.equals(ExtractionPipeline.NONE) || from == null || to == null
                        || !termWritten.contains(from) || !termWritten.contains(to)) {
                    continue;
                }
                // Both directions of a symmetric pair are asked, but they are one record, so the second is no write.
                if (SYMMETRIC.contains(choice) && !symmetricWritten.add(from.compareTo(to) < 0
                        ? List.of(choice, from, to) : List.of(choice, to, from))) {
                    continue;
                }
                written++;
                var labelled = labelled(c, from, choice, to);
                if (labelled != null && Boolean.TRUE.equals(termCorrect.get(from))
                        && Boolean.TRUE.equals(termCorrect.get(to))) {
                    relationsRight.add(labelled);
                } else {
                    wrongRelation++;
                }
            }

            for (var e : c.entities()) {
                if (!Boolean.TRUE.equals(termCorrect.get(e.mention()))) missed++;
            }
            for (var r : c.relations()) {
                if (!relationsRight.contains(r)) missed++;
            }
        }

        /** Tallies the question; its choice when it is sure at {@code t}, else null. */
        private @Nullable String counted(Decision d, double t) {
            decisions++;
            var choice = d.choice();
            if (d.failed() || choice == null) {
                decisionFailures++;
                return null;
            }
            if (d.confidence() < t) {
                abstentions++;
                return null;
            }
            return choice;
        }
    }

    /** The labelled relation a written {@code from type to} matches, either way round for a symmetric type. */
    private static GraphCases.@Nullable Relation labelled(Case c, String from, String type, String to) {
        for (var r : c.relations()) {
            if (!r.type().equals(type)) continue;
            if (r.from().equals(from) && r.to().equals(to)) return r;
            if (SYMMETRIC.contains(type) && r.from().equals(to) && r.to().equals(from)) return r;
        }
        return null;
    }
}
