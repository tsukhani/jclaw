package services.grapheval;

import memory.TemporalExpressions.DateSpan;
import memory.TemporalExpressions.Kind;
import memory.TemporalExpressions.NegationCue;
import memory.ontology.EdtfDate;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import org.jspecify.annotations.Nullable;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.ExtractionPipeline.Records;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a run would write at base threshold {@code t} (JCLAW-1365): its terms with when each Event occurs, its positive
 * relations with status, valid time and valence, its denials, and how many answers contradicted each other. Every value
 * is an option id or a code-computed literal; the model never supplies one. A run any of whose decisions failed
 * derives nothing. Pure: no I/O, no clock.
 */
public final class Statements {

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s|$)");
    private static final String HOLDS_VIEW_ON = "holds_view_on";

    private Statements() {}

    /**
     * Each qualifier class's own threshold, or null when the class is disabled. A class value writes at the higher of
     * the base threshold and its own.
     */
    public record Classes(@Nullable Double status, @Nullable Double time, @Nullable Double negation,
                          @Nullable Double lineage) {
        public static Classes all(double threshold) {
            return new Classes(threshold, threshold, threshold, threshold);
        }

        public static Classes none() {
            return new Classes(null, null, null, null);
        }
    }

    /** A written term; {@code occurs} only on a dated term whose occurs answer wrote. */
    public record Term(String span, String type, double confidence, @Nullable EdtfInterval occurs) {}

    /** A written relation: a positive one ({@code holds}, {@code ended} or null status) or a denial. */
    public record Claim(String from, String type, String to, OntologyRecord.@Nullable Status status,
                        @Nullable EdtfInterval valid, OntologyRecord.@Nullable Valence valence, double confidence) {}

    public record Outcome(boolean failed, List<Term> terms, List<Claim> relations, List<Claim> denials, int conflict) {
        public Outcome {
            terms = List.copyOf(terms);
            relations = List.copyOf(relations);
            denials = List.copyOf(denials);
        }

        static Outcome failure() {
            return new Outcome(true, List.of(), List.of(), List.of(), 0);
        }
    }

    /** Whether any decision of {@code run}, in any stage, failed: then nothing partial is derived. */
    static boolean anyFailed(CaseRun run) {
        return run.decisions().stream().anyMatch(Decision::failed);
    }

    /** {@code classThreshold} raised to {@code t}, or null when the class is disabled. */
    static @Nullable Double at(double t, @Nullable Double classThreshold) {
        return classThreshold == null ? null : Math.max(t, classThreshold);
    }

    public static Outcome at(CaseRun run, double t, Classes classes) {
        var schema = run.schema();
        if (schema == null) throw new IllegalArgumentException("a run without its schema derives no statements");
        if (anyFailed(run)) return Outcome.failure();
        var conflict = new int[1];
        var records = Records.at(run, t);
        var types = new HashMap<String, String>();
        for (var d : run.stage(ExtractionPipeline.TERM)) {
            var choice = d.choice();
            if (choice != null && !d.declined()) types.putIfAbsent(d.subject(), choice);
        }
        var statusAt = at(t, classes.status());
        var timeAt = at(t, classes.time());
        var negationAt = at(t, classes.negation());

        var terms = new ArrayList<Term>();
        for (var d : records.terms()) {
            var type = Objects.requireNonNull(d.choice());
            var occurs = timeAt == null ? null : occurs(run, d.subject(), timeAt, conflict);
            terms.add(new Term(d.subject(), type, d.confidence(), occurs));
        }

        var vetoed = new HashSet<>(Records.vetoed(run));
        var positives = new ArrayList<Claim>();
        for (var d : records.relations()) {
            if (vetoed.contains(d)) continue;
            var from = Objects.requireNonNull(d.from());
            var to = Objects.requireNonNull(d.to());
            var type = Objects.requireNonNull(d.choice());
            var fromType = types.get(from);
            if (fromType == null) continue;
            OntologyRecord.Status status;
            if (schema.effectiveStatuses(type, fromType).contains(ExtractionPipeline.ENDED)) {
                var s = find(run.stage(ExtractionPipeline.STATUS), from, to);
                status = statusAt != null && s != null && s.writes(statusAt) ? status(s.choice()) : null;
            } else {
                status = OntologyRecord.Status.HOLDS;
            }
            EdtfInterval valid = null;
            if (timeAt != null && status != null && schema.validAllowed(type, fromType)) {
                var timed = valid(run, from, type, to, status, timeAt, conflict);
                status = timed.status();
                valid = timed.valid();
            }
            positives.add(new Claim(from, type, to, status, valid, valence(run, type, from, to), d.confidence()));
        }

        var denials = new ArrayList<Claim>();
        if (negationAt != null) {
            for (var d : run.stage(ExtractionPipeline.NEGATION)) {
                if (!d.writes(negationAt)) continue;
                var from = Objects.requireNonNull(d.from());
                var to = Objects.requireNonNull(d.to());
                var type = Objects.requireNonNull(d.choice());
                var positive = positives.stream().filter(p -> p.type().equals(type) && (p.from().equals(from)
                        && p.to().equals(to) || schema.symmetric(type) && p.from().equals(to) && p.to().equals(from)))
                        .findFirst();
                if (positive.isPresent()) {
                    conflict[0]++;
                    // A denial beside an ended positive reads as "not any more", which the positive already says.
                    if (positive.get().status() != OntologyRecord.Status.ENDED) positives.remove(positive.get());
                    continue;
                }
                var fromType = types.get(from);
                EdtfInterval valid = timeAt != null && fromType != null && schema.validAllowed(type, fromType)
                        && perfectNever(run, from, to)
                        ? EdtfInterval.between(EdtfInterval.OPEN,
                                new EdtfInterval.Point(EdtfDate.ofDay(run.anchor(), false)))
                        : null;
                denials.add(new Claim(from, type, to, OntologyRecord.Status.DENIED, valid, null,
                        Math.min(d.confidence(), d.floor())));
            }
        }
        return new Outcome(false, terms, positives, denials, conflict[0]);
    }

    private static OntologyRecord.@Nullable Status status(@Nullable String choice) {
        if (ExtractionPipeline.HOLDS.equals(choice)) return OntologyRecord.Status.HOLDS;
        if (ExtractionPipeline.ENDED.equals(choice)) return OntologyRecord.Status.ENDED;
        return null;
    }

    private static @Nullable Decision find(List<Decision> decisions, String from, String to) {
        return decisions.stream().filter(d -> from.equals(d.from()) && to.equals(d.to())).findFirst().orElse(null);
    }

    /** A date's reading and the confidence it was chosen at: 1 for a single reading, else the tense answer's. */
    private record Reading(EdtfInterval interval, double confidence) {}

    private static @Nullable Reading reading(CaseRun run, String span) {
        DateSpan date = run.dates().stream().filter(d -> d.span().equals(span)).findFirst().orElse(null);
        if (date == null || date.readings().isEmpty()) return null;
        if (date.readings().size() == 1) return new Reading(date.readings().getFirst(), 1.0);
        var tense = run.stage(ExtractionPipeline.TENSE).stream().filter(d -> d.subject().equals(span)).findFirst()
                .orElse(null);
        if (tense == null || tense.choice() == null) return null;
        var index = tense.choice().equals(ExtractionPipeline.PAST) ? 0 : 1;
        return new Reading(date.readings().get(index), tense.confidence());
    }

    /** The interval an Event's highest-confidence occurs answer names. */
    private static @Nullable EdtfInterval occurs(CaseRun run, String event, double threshold, int[] conflict) {
        var named = new ArrayList<Bound<EdtfInterval>>();
        for (var d : run.stage(ExtractionPipeline.OCCURS)) {
            if (!event.equals(d.from()) || d.to() == null || !d.writes(threshold)) continue;
            var reading = reading(run, d.to());
            if (reading == null) continue;
            var confidence = Math.min(Math.min(d.confidence(), d.floor()), reading.confidence());
            if (confidence >= threshold) named.add(new Bound<>(reading.interval(), confidence));
        }
        return pick(named, conflict);
    }

    private record Bound<T>(T value, double confidence) {}

    private record Timed(OntologyRecord.@Nullable Status status, @Nullable EdtfInterval valid) {}

    /** The valid time the slot answers give a relation of {@code status}, after the consistency checks. */
    private static Timed valid(CaseRun run, String from, String type, String to, OntologyRecord.Status status,
                               double threshold, int[] conflict) {
        var prefix = from + " -" + type + "-> " + to + " @ ";
        var starts = new ArrayList<Bound<EdtfDate>>();
        var ends = new ArrayList<Bound<EdtfDate>>();
        for (var d : run.stage(ExtractionPipeline.SLOT)) {
            if (!d.subject().startsWith(prefix) || !d.writes(threshold)) continue;
            var span = d.subject().substring(prefix.length());
            var reading = reading(run, span);
            if (reading == null) continue;
            var confidence = Math.min(Math.min(d.confidence(), d.floor()), reading.confidence());
            if (confidence < threshold) continue;
            var interval = reading.interval();
            var kind = run.dates().stream().filter(x -> x.span().equals(span)).findFirst().orElseThrow().kind();
            switch (Objects.requireNonNull(d.choice())) {
                case ExtractionPipeline.FROM -> {
                    if (kind == Kind.DURATION && status == OntologyRecord.Status.ENDED) {
                        // "for N years" counts back from the anchor, which an ended relation no longer reaches.
                        conflict[0]++;
                        continue;
                    }
                    add(starts, interval.start(), confidence);
                }
                case ExtractionPipeline.TO -> add(ends, interval.end(), confidence);
                case ExtractionPipeline.DURING -> {
                    add(starts, interval.start(), confidence);
                    add(ends, interval.end(), confidence);
                }
                default -> { }
            }
        }
        var start = pick(starts, conflict);
        var end = pick(ends, conflict);
        var anchor = run.anchor();
        if (status == OntologyRecord.Status.HOLDS && end != null && !end.hi().isAfter(anchor)) {
            conflict[0]++;
            return new Timed(null, null);
        }
        if (status == OntologyRecord.Status.ENDED
                && (start != null && start.lo().isAfter(anchor) || end != null && end.lo().isAfter(anchor))) {
            conflict[0]++;
            return new Timed(null, null);
        }
        if (start != null && end != null && !start.lo().isBefore(end.hi())) {
            conflict[0]++;
            return new Timed(status, null);
        }
        if (start == null && end == null) return new Timed(status, null);
        EdtfInterval.Endpoint lo = start != null ? new EdtfInterval.Point(start) : EdtfInterval.UNKNOWN;
        EdtfInterval.Endpoint hi = end != null ? new EdtfInterval.Point(end) : unboundedEnd(status);
        return new Timed(status, EdtfInterval.between(lo, hi));
    }

    private static EdtfInterval.Endpoint unboundedEnd(OntologyRecord.Status status) {
        return status == OntologyRecord.Status.HOLDS ? EdtfInterval.OPEN : EdtfInterval.UNKNOWN;
    }

    private static void add(List<Bound<EdtfDate>> bounds, EdtfInterval.Endpoint endpoint, double confidence) {
        if (endpoint instanceof EdtfInterval.Point(var date)) bounds.add(new Bound<>(date, confidence));
    }

    /** The highest-confidence value; two values chosen count a conflict, and a tie between them gives none. */
    private static <T> @Nullable T pick(List<Bound<T>> bounds, int[] conflict) {
        if (bounds.isEmpty()) return null;
        if (bounds.stream().map(Bound::value).distinct().count() > 1) conflict[0]++;
        double top = bounds.stream().mapToDouble(Bound::confidence).max().orElseThrow();
        var best = bounds.stream().filter(b -> b.confidence() == top).map(Bound::value).distinct().toList();
        return best.size() == 1 ? best.getFirst() : null;
    }

    /** The view's valence when {@code from} is the subject of the frame on {@code to}; else none. */
    private static OntologyRecord.@Nullable Valence valence(CaseRun run, String type, String from, String to) {
        if (!type.equals(HOLDS_VIEW_ON)) return null;
        for (var c : run.candidates()) {
            var frame = c.frame();
            if (c.span().equals(to) && frame != null && from.equals(frame.subject())) return frame.valence();
        }
        return null;
    }

    /** Whether the negation cue nearest the pair, in the sentence holding it, is a perfect "has never". */
    private static boolean perfectNever(CaseRun run, String from, String to) {
        int lo = Integer.MAX_VALUE;
        int hi = -1;
        for (var c : run.candidates()) {
            if (c.start() < 0 || !(c.span().equals(from) || c.span().equals(to))) continue;
            lo = Math.min(lo, c.start());
            hi = Math.max(hi, c.end());
        }
        if (hi < 0) return false;
        var text = run.text();
        int sentenceStart = 0;
        int sentenceEnd = text.length();
        var m = SENTENCE_END.matcher(text);
        while (m.find()) {
            if (m.end() <= lo) sentenceStart = m.end();
            else {
                sentenceEnd = Math.max(m.end(), hi);
                break;
            }
        }
        NegationCue nearest = null;
        int best = Integer.MAX_VALUE;
        for (var cue : run.cues()) {
            if (cue.start() < sentenceStart || cue.end() > sentenceEnd) continue;
            int distance = distance(cue, lo, hi);
            if (distance < best) {
                best = distance;
                nearest = cue;
            }
        }
        return nearest != null && nearest.perfectNever();
    }

    private static int distance(NegationCue cue, int lo, int hi) {
        if (cue.end() <= lo) return lo - cue.end();
        return cue.start() >= hi ? cue.start() - hi : 0;
    }

}
