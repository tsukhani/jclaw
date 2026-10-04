package services.grapheval;

import memory.AnchorResolver;
import memory.TemporalExpressions;
import memory.TemporalExpressions.DateSpan;
import memory.graph.GraphStore;
import memory.graph.GraphView;
import memory.graph.GraphWithdrawal;
import memory.graph.GraphWithdrawal.LineageDecision;
import memory.graph.GraphWithdrawal.Retirement;
import memory.ontology.EdtfInterval;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Status;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Valence;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.TimezoneResolver;
import services.grapheval.Certifier.ClassWalk;
import services.grapheval.ExtractionPipeline.CaseRun;
import services.grapheval.ExtractionPipeline.Decider;
import services.grapheval.ExtractionPipeline.Decision;
import services.grapheval.GraphEvalHarness.DecisionModel;
import services.grapheval.SequenceScorer.Answered;
import services.grapheval.SequenceScorer.Judged;
import services.grapheval.SequenceScorer.Timeline;
import services.grapheval.Sequences.Chain;
import services.grapheval.StatementRecords.ClaimFact;
import services.grapheval.StatementRecords.Facts;
import services.grapheval.StatementRecords.Source;
import services.grapheval.StatementRecords.TermFact;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Runs the sequence set (JCLAW-1367): each chain through the production pipeline memory by memory, every question
 * asked once per run, then the record sets rebuilt in memory for three variants and the probes answered with
 * {@link GraphView}. Writes nothing: no Memory row, no graph file.
 */
public final class SequenceHarness {

    public static final String SET = "sequences";
    public static final String DEFAULT_SOURCE = "default";
    public static final String SUPPLIED_SOURCE = "supplied";
    public static final long PROBE_OFFSET_SECONDS = 30;
    private static final LocalTime STAMP_TIME = LocalTime.NOON;
    private static final String OWNER_TERM = GraphCases.OPERATOR;

    private SequenceHarness() {}

    /** Where a variant's claims and lineage come from. */
    public enum Variant { END_TO_END, GOLD_CLAIMS_MODEL_LINEAGE, GOLD_CLAIMS_GOLD_LINEAGE }

    /** A single run's spot-check: chains asked again, decisions compared, and how many differed. */
    public record SequenceSpotCheck(int chains, int decisions, int differing) {}

    /** One run of one model. {@code timeline} is {@link SequenceScorer#result} of the end-to-end variant. */
    public record RunReport(int run, int failedDecisions, ClassWalk lineage, Timeline endToEnd,
                            Timeline goldClaimsModelLineage, Timeline goldClaimsGoldLineage, String timeline) {}

    /**
     * One model over the set. {@code lineage} is the single run's walk or the runs combined; {@code lineageState} is
     * its state, or {@code unconfirmed} when the spot-check saw a difference.
     */
    public record ModelReport(String model, List<RunReport> runs, @Nullable SequenceSpotCheck spotCheck,
                              ClassWalk lineage, String lineageState, String timeline) {}

    /** The set's whole result: counts and fingerprints, never a timing, so the same answers serialize identically. */
    public record Report(String set, String schema, String extraction, String sequences, int chains, int probes,
                         int runs, String configurationSource, Configuration configuration,
                         List<ModelReport> models) {}

    /** Memory {@code memoryIndex} of chain {@code chainIndex}, both from 0, as the id its records are sourced to. */
    public static long syntheticId(int chainIndex, int memoryIndex) {
        return (chainIndex + 1) * 1000L + memoryIndex;
    }

    /** Memory {@code index}'s system time: its {@code capturedAt} at noon in {@code zone}, plus {@code index} minutes. */
    public static Instant stamp(Chain chain, int index, ZoneId zone) {
        var capturedAt = chain.memories().get(index).labels().capturedAt();
        return capturedAt.atTime(STAMP_TIME).atZone(zone).toInstant().plusSeconds(60L * index);
    }

    public static Report run(Sequences set, OntologySchema schema, List<DecisionModel> models, int runs,
                             int concurrency, Configuration configuration, boolean defaultConfiguration,
                             EvalProgress progress) {
        var zone = TimezoneResolver.appZone();
        var chains = set.chains();
        progress.plan(models.stream().map(DecisionModel::name).toList(), runs, chains.size());
        var tasks = new ArrayList<Callable<List<CaseRun>>>();
        int pass = 0;
        for (int r = 0; r < runs; r++) {
            for (var m : models) {
                int p = pass++;
                for (int c = 0; c < chains.size(); c++) {
                    int chainIndex = c;
                    tasks.add(() -> {
                        progress.caseStarted(p);
                        var asked = ask(schema, set.ownerName(), chains.get(chainIndex), chainIndex, m.name(),
                                m.decider(), zone);
                        progress.caseFinished(p, failed(asked));
                        return asked;
                    });
                }
            }
        }
        var results = GraphEvalHarness.fanOut(tasks, concurrency);
        var reports = new ArrayList<ModelReport>();
        var byModel = new LinkedHashMap<String, List<RunReport>>();
        var firstRuns = new HashMap<String, List<List<CaseRun>>>();
        int i = 0;
        for (int r = 0; r < runs; r++) {
            for (var m : models) {
                var slice = results.subList(i, i + chains.size());
                i += chains.size();
                if (r == 0) firstRuns.put(m.name(), slice);
                byModel.computeIfAbsent(m.name(), _ -> new ArrayList<>())
                        .add(score(schema, chains, slice, r, configuration, zone));
            }
        }
        for (var m : models) {
            var runReports = Objects.requireNonNull(byModel.get(m.name()));
            var walks = runReports.stream().map(RunReport::lineage).toList();
            var lineage = runs == 1 ? walks.getFirst() : SequenceScorer.combineLineage(walks);
            SequenceSpotCheck spotCheck = null;
            var state = lineage.state();
            if (runs == 1) {
                spotCheck = spotCheck(schema, set, m, Objects.requireNonNull(firstRuns.get(m.name())), concurrency, zone);
                if (spotCheck.differing() > 0) state = SequenceScorer.UNCONFIRMED;
            }
            reports.add(new ModelReport(m.name(), runReports, spotCheck, lineage, state,
                    SequenceScorer.overall(runReports.stream().map(RunReport::timeline).toList())));
        }
        int probes = chains.stream().mapToInt(c -> c.probes().size()).sum();
        return new Report(SET, schema.fingerprint(), ExtractionPipeline.fingerprint(schema), set.fingerprint(),
                chains.size(), probes, runs, defaultConfiguration ? DEFAULT_SOURCE : SUPPLIED_SOURCE, configuration,
                reports);
    }

    /**
     * Every memory of {@code chain}, in order, through {@link ExtractionPipeline#run}: one lineage question per gold
     * {@code supersedes} link, the owner plus earlier memories' kept term spans as known names, and the dates found
     * against {@code capturedAt} re-based through {@link AnchorResolver} over the chain.
     */
    public static List<CaseRun> ask(OntologySchema schema, @Nullable String ownerName, Chain chain, int chainIndex,
                                    String model, Decider decider, ZoneId zone) {
        var resolver = new AnchorResolver(lookup(chain, chainIndex, zone));
        var known = new LinkedHashSet<String>();
        if (ownerName != null) known.add(ownerName);
        var out = new ArrayList<CaseRun>();
        var memories = chain.memories();
        for (int i = 0; i < memories.size(); i++) {
            var memory = memories.get(i);
            var labels = memory.labels();
            long id = syntheticId(chainIndex, i);
            var predecessors = new ArrayList<ExtractionPipeline.Predecessor>();
            for (var predId : memory.supersedes().keySet()) {
                var pred = memories.get(chain.indexOf(predId)).labels();
                predecessors.add(new ExtractionPipeline.Predecessor(predId, pred.text(), pred.capturedAt()));
            }
            var inputs = new ExtractionPipeline.Inputs(ownerName, memory.authorType(), labels.capturedAt(),
                    dates(resolver, id, labels.text(), labels.capturedAt()), predecessors, false, id);
            var run = ExtractionPipeline.run(schema, memory.id(), labels.text(),
                    CandidateGenerator.generate(labels.text(), known), model, decider, inputs);
            for (var d : run.stage(ExtractionPipeline.TERM)) {
                if (d.writes(ExtractionPipeline.KEPT)) known.add(d.subject());
            }
            out.add(run);
        }
        return out;
    }

    /**
     * The record set after each memory of {@code chain} under {@code variant}. {@code runs} are the chain's runs in
     * memory order, unread by the gold-lineage variant; {@code lineageThreshold} is the model lineage's, null for none.
     */
    public static List<List<OntologyRecord>> replay(OntologySchema schema, Chain chain, int chainIndex,
                                                    List<CaseRun> runs, Variant variant, Configuration configuration,
                                                    @Nullable Double lineageThreshold, ZoneId zone) {
        var out = new ArrayList<List<OntologyRecord>>();
        List<OntologyRecord> records = List.of();
        var memories = chain.memories();
        for (int i = 0; i < memories.size(); i++) {
            var memory = memories.get(i);
            var labels = memory.labels();
            long id = syntheticId(chainIndex, i);
            var stamp = stamp(chain, i, zone);
            var retirement = Retirement.of(stamp, id);
            var retirements = new HashMap<String, Retirement>();
            for (var predId : memory.supersedes().keySet()) {
                retirements.put(source(chainIndex, chain, predId), retirement);
            }
            records = GraphWithdrawal.retire(records, retirements).records();

            var run = variant == Variant.GOLD_CLAIMS_GOLD_LINEAGE ? null : runs.get(i);
            if (run != null && Statements.anyFailed(run)) {
                out.add(records);
                continue;
            }
            var withEvidence = new HashSet<String>();
            for (var r : records) {
                if (r instanceof Evidence e) withEvidence.add(e.source());
            }
            var decisions = new LinkedHashMap<String, LineageDecision>();
            if (run == null) {
                memory.supersedes().forEach((predId, lineage) -> {
                    var source = source(chainIndex, chain, predId);
                    if (!withEvidence.contains(source)) return;
                    var changedBy = lineage == OntologyRecord.Lineage.UPDATE ? labels.capturedAt() : null;
                    decisions.put(source, new LineageDecision(lineage, changedBy, retirement));
                });
            } else if (lineageThreshold != null) {
                var outcome = Lineage.at(run, lineageThreshold,
                        new Statements.Classes(null, null, null, lineageThreshold));
                for (var entry : outcome.entries()) {
                    var lineage = entry.lineage();
                    var source = source(chainIndex, chain, entry.predecessorId());
                    if (lineage == null || !withEvidence.contains(source)) continue;
                    decisions.put(source, new LineageDecision(lineage, entry.changedBy(), retirement));
                }
            }
            if (!decisions.isEmpty()) records = GraphWithdrawal.recordLineage(records, decisions).records();

            var facts = variant == Variant.END_TO_END && run != null
                    ? modelFacts(run, labels, id, records, configuration)
                    : goldFacts(labels, schema);
            records = StatementRecords.add(schema, records,
                    new Source(id, stamp, labels.capturedAt(), memory.authorType()), facts.terms(), facts.claims());
            out.add(records);
        }
        return out;
    }

    /** {@code probe} over {@code records}, at the probed memory's stamp plus or minus 30 seconds. */
    public static GraphView.Answer probe(OntologySchema schema, Chain chain, Sequences.Probe probe,
                                         List<OntologyRecord> records, ZoneId zone) {
        var at = stamp(chain, chain.indexOf(probe.memoryId()), zone);
        var s = probe.after() != null ? at.plusSeconds(PROBE_OFFSET_SECONDS) : at.minusSeconds(PROBE_OFFSET_SECONDS);
        var id = StatementRecords.relationId(schema, probe.type(), probe.from(), probe.to());
        boolean present = records.stream().anyMatch(r -> r instanceof OntologyRecord.Relation && r.id().equals(id));
        if (!present) {
            return new GraphView.Answer(id, GraphView.Truth.UNKNOWN, false, GraphView.Contested.NONE, null, null,
                    List.of(), null, List.of());
        }
        return new GraphView(schema, records, OWNER_TERM).at(id, probe.d(), s);
    }

    private static RunReport score(OntologySchema schema, List<Chain> chains, List<List<CaseRun>> runs, int run,
                                   Configuration configuration, ZoneId zone) {
        var judged = new ArrayList<Judged>();
        boolean anyFailed = false;
        int failedDecisions = 0;
        for (int c = 0; c < chains.size(); c++) {
            var chain = chains.get(c);
            for (int i = 0; i < chain.memories().size(); i++) {
                var caseRun = runs.get(c).get(i);
                failedDecisions += (int) caseRun.decisions().stream().filter(Decision::failed).count();
                if (Statements.anyFailed(caseRun)) anyFailed = true;
                var supersedes = chain.memories().get(i).supersedes();
                for (var d : caseRun.stage(ExtractionPipeline.LINEAGE)) {
                    var gold = d.to() == null ? null : supersedes.get(d.to());
                    if (gold != null) judged.add(new Judged(d, gold.name().toLowerCase(Locale.ROOT)));
                }
            }
        }
        var walk = SequenceScorer.lineageWalk(judged, anyFailed);
        var threshold = walk.threshold();
        var e2e = timeline(schema, chains, runs, Variant.END_TO_END, configuration, threshold, zone);
        return new RunReport(run, failedDecisions, walk, e2e,
                timeline(schema, chains, runs, Variant.GOLD_CLAIMS_MODEL_LINEAGE, configuration, threshold, zone),
                timeline(schema, chains, runs, Variant.GOLD_CLAIMS_GOLD_LINEAGE, configuration, null, zone),
                SequenceScorer.result(e2e, anyFailed));
    }

    private static Timeline timeline(OntologySchema schema, List<Chain> chains, List<List<CaseRun>> runs,
                                     Variant variant, Configuration configuration, @Nullable Double threshold,
                                     ZoneId zone) {
        var answers = new ArrayList<Answered>();
        for (int c = 0; c < chains.size(); c++) {
            var chain = chains.get(c);
            var sets = replay(schema, chain, c, runs.get(c), variant, configuration, threshold, zone);
            var last = sets.isEmpty() ? List.<OntologyRecord>of() : sets.getLast();
            for (var p : chain.probes()) {
                var a = probe(schema, chain, p, last, zone);
                answers.add(new Answered(p.truth(), p.assumed(), a.truth(), a.assumed()));
            }
        }
        return SequenceScorer.timeline(answers);
    }

    private static SequenceSpotCheck spotCheck(OntologySchema schema, Sequences set, DecisionModel m,
                                               List<List<CaseRun>> first, int concurrency, ZoneId zone) {
        var sample = new ArrayList<Integer>();
        for (int c = 0; c < set.chains().size(); c += GraphEvalHarness.SPOT_CHECK_STRIDE) sample.add(c);
        var tasks = new ArrayList<Callable<List<CaseRun>>>();
        for (int c : sample) {
            tasks.add(() -> ask(schema, set.ownerName(), set.chains().get(c), c, m.name(), m.decider(), zone));
        }
        var again = GraphEvalHarness.fanOut(tasks, concurrency);
        int decisions = 0;
        int differing = 0;
        for (int s = 0; s < sample.size(); s++) {
            var a = concatenated(first.get(sample.get(s)));
            var b = concatenated(again.get(s));
            int n = Math.max(a.size(), b.size());
            decisions += n;
            for (int k = 0; k < n; k++) {
                if (k >= a.size() || k >= b.size() || !a.get(k).equals(b.get(k))) differing++;
            }
        }
        return new SequenceSpotCheck(sample.size(), decisions, differing);
    }

    private static List<Decision> concatenated(List<CaseRun> runs) {
        var out = new ArrayList<Decision>();
        runs.forEach(r -> out.addAll(r.decisions()));
        return out;
    }

    private static int failed(List<CaseRun> runs) {
        return (int) runs.stream().flatMap(r -> r.decisions().stream()).filter(Decision::failed).count();
    }

    private static String source(int chainIndex, Chain chain, String memoryId) {
        return GraphStore.memorySource(syntheticId(chainIndex, chain.indexOf(memoryId)));
    }

    /** The chain as {@link AnchorResolver} reads it: each memory's text, day, source text and predecessors. */
    private static AnchorResolver.Lookup lookup(Chain chain, int chainIndex, ZoneId zone) {
        var nodes = new HashMap<Long, AnchorResolver.Node>();
        var memories = chain.memories();
        for (int i = 0; i < memories.size(); i++) {
            var memory = memories.get(i);
            var labels = memory.labels();
            var linked = new LinkedHashSet<String>(memory.supersedes().keySet());
            linked.addAll(memory.derivedFrom());
            var predecessors = new ArrayList<AnchorResolver.Predecessor>();
            for (var predId : linked) {
                int p = chain.indexOf(predId);
                predecessors.add(new AnchorResolver.MemoryPred(syntheticId(chainIndex, p), stamp(chain, p, zone)));
            }
            var message = memory.message();
            nodes.put(syntheticId(chainIndex, i), new AnchorResolver.Node(labels.text(), labels.capturedAt(),
                    message != null ? message : labels.text(), !memory.derivedFrom().isEmpty(), predecessors));
        }
        return id -> Optional.ofNullable(nodes.get(id));
    }

    /**
     * The dates found in {@code text} against {@code anchor}, each re-based: kept when it resolves to the anchor, read
     * again at its base when it resolves elsewhere, dropped when it cannot be told.
     */
    private static List<DateSpan> dates(AnchorResolver resolver, long id, String text, LocalDate anchor) {
        var out = new ArrayList<DateSpan>();
        for (var span : TemporalExpressions.find(text, anchor).found()) {
            var base = resolver.base(id, span.span());
            if (base.isEmpty()) continue;
            if (base.get().equals(anchor)) {
                out.add(span);
                continue;
            }
            TemporalExpressions.find(text, base.get()).found().stream()
                    .filter(s -> s.start() == span.start() && s.end() == span.end()).findFirst().ifPresent(out::add);
        }
        return out;
    }

    /**
     * The end-to-end facts: terms at the configured term threshold, then each enabled relation type's relations and
     * denials at its own threshold, kept only between written terms. A span takes its labelled entity's id when the
     * type agrees with the one that id was first recorded under.
     */
    private static Facts modelFacts(CaseRun run, GraphCases.Case labels, long id, List<OntologyRecord> records,
                                    Configuration configuration) {
        var classes = configuration.statementClasses();
        var terms = Statements.at(run, configuration.terms(), classes);
        var recorded = new HashMap<String, String>();
        for (var r : records) {
            if (r instanceof Term t) recorded.put(t.id(), t.type());
        }
        var termIds = new HashMap<String, String>();
        for (var t : terms.terms()) {
            var entity = labels.entityAt(t.span());
            String termId = "span:" + id + ":" + t.span();
            if (entity != null) {
                var type = recorded.get(entity.id());
                if (type == null || type.equals(t.type())) {
                    termId = entity.id();
                    recorded.putIfAbsent(termId, t.type());
                }
            }
            termIds.putIfAbsent(t.span(), termId);
        }
        var relations = new ArrayList<Statements.Claim>();
        var denials = new ArrayList<Statements.Claim>();
        configuration.relations().forEach((type, threshold) -> {
            var at = Statements.at(run, threshold, classes);
            for (var c : at.relations()) {
                if (between(c, type, termIds.keySet())) relations.add(c);
            }
            for (var c : at.denials()) {
                if (between(c, type, termIds.keySet())) denials.add(c);
            }
        });
        return StatementRecords.fromStatements(terms, relations, denials,
                span -> Objects.requireNonNull(termIds.get(span), "a written span has a term id"));
    }

    private static boolean between(Statements.Claim c, String type, Set<String> spans) {
        return c.type().equals(type) && spans.contains(c.from()) && spans.contains(c.to());
    }

    /**
     * The gold facts: every entity that is not noise, and each relation that is not noise and holds, ended or was
     * denied, with its endpoints; an unasserted relation claims nothing.
     */
    private static Facts goldFacts(GraphCases.Case labels, OntologySchema schema) {
        var claims = new ArrayList<ClaimFact>();
        var ends = new LinkedHashSet<String>();
        for (var r : labels.relations()) {
            if (r.noise()) continue;
            var status = switch (labels.scoredStatus(r, schema)) {
                case GraphCases.HOLDS -> Status.HOLDS;
                case GraphCases.ENDED -> Status.ENDED;
                case GraphCases.DENIED -> Status.DENIED;
                default -> null;
            };
            if (status == null) continue;
            var valence = r.valence();
            claims.add(new ClaimFact(r.from(), r.type(), r.to(), status, interval(r.valid()),
                    valence == null ? null : Valence.valueOf(valence.toUpperCase(Locale.ROOT)), 1.0));
            ends.add(r.from());
            ends.add(r.to());
        }
        var terms = new ArrayList<TermFact>();
        for (var e : labels.entities()) {
            if (e.noise() && !ends.contains(e.id())) continue;
            terms.add(new TermFact(e.id(), e.span(), e.type(), 1.0, interval(e.occurs())));
        }
        return new Facts(terms, claims);
    }

    private static @Nullable EdtfInterval interval(@Nullable String edtf) {
        return edtf == null ? null : EdtfInterval.parse(edtf);
    }
}
