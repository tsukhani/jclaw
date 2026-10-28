package services.grapheval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;
import org.jspecify.annotations.Nullable;
import services.TimezoneResolver;
import services.grapheval.CaseMeasurement.DecisionModel;
import services.grapheval.Certifier.ClassWalk;
import services.grapheval.Certifier.SpotCheck;
import services.grapheval.GateBounds.MemoryCounts;
import services.grapheval.GraphCases.Case;
import services.grapheval.V2Scoring.AgreedSample;
import services.grapheval.V2Scoring.Checks;
import services.grapheval.V2Scoring.Tallies;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static utils.GsonHolder.GSON;

/**
 * One certification invocation over a frozen split and its re-score: the stored run, the ledger line, the blind
 * sheet and, when certified, the certificate, all under the certification root.
 */
public final class CertificationRun {

    public static final String SHEETS = "sheets";
    public static final String CERTIFICATION = "certification";

    private CertificationRun() {}

    /**
     * One certification invocation's inputs. {@code agentId} holds the committed cases as memories for the run and is
     * null for a held-out split, whose cases {@code held} maps by memory id; {@code digests} is each model's Ollama
     * digest.
     */
    public record CertifyRequest(Path root, CertificationSplit split, CertificationSplit.Source source,
                                 OntologySchema schema, @Nullable String agentId, @Nullable String ownerName,
                                 Map<String, HeldOut.HeldCase> held, Sequences sequences, List<DecisionModel> models,
                                 Map<String, String> digests, int runs, long agreedSeed, double agreedShare,
                                 double checkShare, double recallFloor, int concurrency, List<Case> secondLabels,
                                 @Nullable String secondLabelsReason, List<Adjudications.Verdict> adjudications) {
        public CertifyRequest {
            held = Map.copyOf(held);
            models = List.copyOf(models);
            digests = Map.copyOf(digests);
            secondLabels = List.copyOf(secondLabels);
            adjudications = List.copyOf(adjudications);
        }
    }

    /**
     * One model's certification result. {@code stages} are each run's gold-fed stage scores as stored;
     * {@code sequencing} and {@code sequences} are null on a void run; {@code certificate} is the written
     * certificate's id when the status is certified.
     */
    public record CertifiedModel(String model, String digest, int runs, int memoriesChanged, int failedDecisions,
                                 @Nullable SpotCheck spotCheck,
                                 SequenceHarness.@Nullable SequenceSpotCheck sequenceSpotCheck,
                                 List<JsonElement> stages, Certifier.@Nullable Sequencing sequencing, Tallies tallies,
                                 AgreedSample agreedSample, Map<String, Integer> unjudgedByGate, Checks checks,
                                 SequenceHarness.@Nullable ModelReport sequences,
                                 Certifier.@Nullable Power lineagePower, Certifier.Verdict verdict,
                                 @Nullable String certificate, List<Certifier.Requirement> requirements) {
        public CertifiedModel {
            requirements = List.copyOf(requirements);
        }
    }

    /**
     * A certification run's or a re-score's report: counts and fingerprints only, never a timing.
     * {@code bootstrapSeed} and {@code bootstrapResamples} are the recall bootstrap's.
     */
    public record CertificationReport(String kind, String set, String split, String schema, String extraction,
                                      String cases, String sequences, String guide, double startingThreshold,
                                      List<String> relationOrder, int memories, double recallFloor,
                                      Agreement.Result agreement, String sequencesNote, long bootstrapSeed,
                                      int bootstrapResamples, List<CertifiedModel> models) {}

    /** One model scored from its stored run, with the blind sheet and, when certified, the certificate. */
    private record Scored(CertifiedModel model, JsonObject sheet, @Nullable CertificateDocument certificate) {}

    /**
     * Refuses a certification run before it asks anything: a split hash or the sequences fingerprint drifted, or a
     * model's ledger state does not admit {@code runs}.
     *
     * @throws IllegalArgumentException naming the drifted id, or quoting the refusing ledger line
     */
    public static void admit(CertifyRequest req) {
        var drift = req.split().verify(req.source());
        if (drift != null) throw new IllegalArgumentException(drift);
        try {
            for (var m : req.models()) {
                SplitUses.admit(req.root(), req.split().split(), m.name(), digest(req, m), req.runs());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String digest(CertifyRequest req, DecisionModel m) {
        var digest = req.digests().get(m.name());
        if (digest == null) throw new IllegalArgumentException("no digest for model " + m.name());
        return digest;
    }

    public static CertificationReport certify(CertifyRequest req, EvalProgress progress) {
        return certify(req, progress, MemoryBounds.INSTANCE);
    }

    /**
     * One certification invocation: refuses a reuse or a drift, runs the split's set (gold-fed stages, end to end and
     * a single run's spot-check), then the sequence answers with theirs, stores each model's run and appends its
     * ledger line, and scores the stored run: gates, classes and pooled gates with the back-off, the sequences at the
     * configuration reached, the verdict, the blind sheet and, when certified, the certificate.
     */
    public static CertificationReport certify(CertifyRequest req, EvalProgress progress, GateBounds bounds) {
        admit(req);
        var split = req.split();
        var schema = req.schema();
        var cases = split.casesFrom(req.source());
        var measured = split.set().equals(CertificationSplit.SET_HELDOUT)
                ? CaseMeasurement.measureHeld(cases, req.held(), req.ownerName(), schema, req.models(), req.runs(),
                        req.concurrency(), progress)
                : CaseMeasurement.measureStored(Objects.requireNonNull(req.agentId(), "the cases set needs an agent"),
                        cases, req.ownerName(), schema, req.models(), req.runs(), req.concurrency(), progress, false);
        var zone = TimezoneResolver.appZone();
        var answers = SequenceHarness.answers(req.sequences(), schema, req.models(), req.runs(), req.concurrency(),
                progress, zone);
        var agreement = Agreement.compare(cases, req.secondLabels(), schema.symmetricSet())
                .withReason(req.secondLabelsReason());
        var guide = req.source().guide();
        var models = new ArrayList<CertifiedModel>();
        try {
            for (var m : req.models()) {
                var data = measured.measured().getOrDefault(m.name(), List.of());
                var chains = Objects.requireNonNull(answers.get(m.name()));
                var seqSpot = req.runs() == 1 ? SequenceHarness.spotCheck(schema, req.sequences(), m,
                        chains.getFirst(), req.concurrency(), zone) : null;
                var passes = new ArrayList<StoredRun.Pass>();
                for (int r = 0; r < data.size(); r++) {
                    passes.add(new StoredRun.Pass(data.get(r).e2e(), chains.get(r), GSON.toJsonTree(data.get(r).stages())));
                }
                StoredRun.write(req.root(), new StoredRun(split.split(), m.name(), digest(req, m), schema.fingerprint(),
                        ExtractionPipeline.fingerprint(schema), req.agreedSeed(), req.agreedShare(), passes,
                        measured.spotChecks().get(m.name()), seqSpot, measured.integrity().checked()
                        - measured.integrity().unchanged(), req.recallFloor()));
                var stored = StoredRun.read(req.root(), split.split(), m.name(), schema);
                SplitUses.append(req.root(), split.split(), m.name(), digest(req, m), ledgerState(stored));
                var scored = scoreStored(stored, split, cases, req.sequences(), schema, guide, req.adjudications(),
                        req.checkShare(), req.recallFloor(), agreement, bounds, zone);
                writeOutputs(req.root(), split, scored);
                models.add(scored.model());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return report(split, schema, guide, cases.size(), req.recallFloor(), agreement, models);
    }

    private static String ledgerState(StoredRun stored) {
        if (stored.failedDecisions() > 0) return SplitUses.VOID;
        return spotCheckDiffered(stored) ? SplitUses.NEEDS_SECOND_RUN : SplitUses.USED;
    }

    private static boolean spotCheckDiffered(StoredRun stored) {
        var spot = stored.spotCheck();
        var seq = stored.sequenceSpotCheck();
        return stored.passes().size() == 1
                && (spot != null && spot.differing() > 0 || seq != null && seq.differing() > 0);
    }

    /**
     * Scores a stored certification run again under the verdicts on file and its own recall floor, asking no model
     * and redrawing nothing.
     *
     * @throws IllegalArgumentException when a split hash drifted or the stored schema or extraction fingerprint is
     *                                  not the running one
     */
    public static CertificationReport rescore(Path root, CertificationSplit split, CertificationSplit.Source source,
                                              OntologySchema schema, String model, Sequences sequences,
                                              double checkShare, List<Case> secondLabels,
                                              @Nullable String secondLabelsReason,
                                              List<Adjudications.Verdict> adjudications) {
        var drift = split.verify(source);
        if (drift != null) throw new IllegalArgumentException(drift);
        try {
            var stored = StoredRun.read(root, split.split(), model, schema);
            if (!stored.schema().equals(schema.fingerprint())) {
                throw new IllegalArgumentException("the stored run's schema %s differs from the running %s"
                        .formatted(stored.schema(), schema.fingerprint()));
            }
            var extraction = ExtractionPipeline.fingerprint(schema);
            if (!stored.extraction().equals(extraction)) {
                throw new IllegalArgumentException("the stored run's extraction %s differs from the running %s"
                        .formatted(stored.extraction(), extraction));
            }
            var cases = split.casesFrom(source);
            var agreement = Agreement.compare(cases, secondLabels, schema.symmetricSet()).withReason(secondLabelsReason);
            var scored = scoreStored(stored, split, cases, sequences, schema, source.guide(), adjudications,
                    checkShare, stored.recallFloor(), agreement, MemoryBounds.INSTANCE, TimezoneResolver.appZone());
            writeOutputs(root, split, scored);
            return report(split, schema, source.guide(), cases.size(), stored.recallFloor(), agreement,
                    List.of(scored.model()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CertificationReport report(CertificationSplit split, OntologySchema schema, String guide,
                                              int memories, double recallFloor, Agreement.Result agreement,
                                              List<CertifiedModel> models) {
        return new CertificationReport(CERTIFICATION, split.set(), split.split(), schema.fingerprint(),
                ExtractionPipeline.fingerprint(schema), split.cases(), split.sequences(), guide,
                split.startingThreshold(), split.relationOrder(), memories, recallFloor, agreement,
                SequenceHarness.DEVELOPMENT_NOTE, MemoryBounds.BOOTSTRAP_SEED, MemoryBounds.RESAMPLES, models);
    }

    /** The one scoring path both a full run and a re-score take, from the stored form. */
    private static Scored scoreStored(StoredRun stored, CertificationSplit split, List<Case> cases, Sequences sequences,
                                      OntologySchema schema, String guide, List<Adjudications.Verdict> adjudications,
                                      double checkShare, double recallFloor, Agreement.Result agreement,
                                      GateBounds bounds, ZoneId zone) {
        var book = new Adjudications.Book(adjudications, guide, stored.agreedSeed(), stored.agreedShare(), checkShare);
        var start = split.startingThreshold();
        var v2 = V2Scoring.v2(cases, stored.passes().stream().map(StoredRun.Pass::cases).toList(), schema, book, start,
                split.relationOrder(), recallFloor, bounds, split.set());
        int failed = stored.failedDecisions();
        var sequencing = failed > 0 ? null : v2.sequencing();
        SequenceHarness.ModelReport seq = null;
        if (sequencing != null) {
            seq = SequenceHarness.scoreAt(schema, sequences.chains(), stored.model(),
                    stored.passes().stream().map(StoredRun.Pass::chains).toList(), sequencing.reached(), start,
                    stored.sequenceSpotCheck(), zone, RecordBounds.INSTANCE);
        }
        var judgment = v2.judgment();
        var verdict = Certifier.verdict(sequencing, new Certifier.VerdictInputs(stored.memoriesChanged() > 0, failed,
                spotCheckDiffered(stored), judgment.labelError(), seq == null ? null : seq.timeline(),
                agreement.complete(), judgment.unjudgedCount(), judgment.uncheckedMarked()));
        CertificateDocument certificate = null;
        if (verdict.status().equals(Certifier.CERTIFIED) && sequencing != null && seq != null) {
            var timeline = seq.runs().stream().map(SequenceHarness.RunReport::endToEnd)
                    .max((a, b) -> Double.compare(a.bound(), b.bound())).orElseThrow();
            certificate = CertificateDocument.of(stored.model(), stored.digest(), verdict.status(), split, guide,
                    stored.schema(), stored.extraction(), sequencing, seq.lineage(), timeline, seq.timeline(),
                    recallFloor);
        }
        var model = new CertifiedModel(stored.model(), stored.digest(), stored.passes().size(),
                stored.memoriesChanged(), failed, stored.spotCheck(), stored.sequenceSpotCheck(),
                stored.passes().stream().map(StoredRun.Pass::stages).toList(), sequencing, v2.tallies(),
                v2.agreedSample(), judgment.unjudgedByGate(), V2Scoring.checks(book, judgment), seq,
                seq == null ? null : lineagePower(seq.lineage()), verdict,
                certificate == null ? null : certificate.id(),
                sequencing == null ? List.of() : sequencing.requirements());
        return new Scored(model, sheet(split, guide, book, cases, v2.inScope()), certificate);
    }

    /** Lineage's power at its walked n, as {@link Certifier} gives every other class's; 0 when disabled. */
    private static Certifier.Power lineagePower(ClassWalk lineage) {
        var at = lineage.threshold() == null ? List.<MemoryCounts>of()
                : List.of(new MemoryCounts("", lineage.n(), lineage.k(), 0, 0, 0));
        return Certifier.Power.of(RecordBounds.INSTANCE, at);
    }

    /**
     * The blind sheet: every unmatched written record and every sampled agreed record at the configuration reached,
     * each with its memory's text, sorted by case and record. Nothing on it says which side a record is on or which
     * model wrote it; re-scoring takes each record's side and inclusion from the stored run, so a blind verdict needs
     * neither.
     */
    private static JsonObject sheet(CertificationSplit split, String guide, Adjudications.Book book, List<Case> cases,
                                    List<GraphEvalScorer.GateRecord> inScope) {
        var texts = new HashMap<String, String>();
        cases.forEach(c -> texts.put(c.id(), c.text()));
        var records = new ArrayList<GraphEvalScorer.GateRecord>();
        for (var r : inScope) {
            if (!r.agreed() || book.sampled(r)) records.add(r);
        }
        records.sort((a, b) -> a.caseId().equals(b.caseId()) ? a.record().compareTo(b.record())
                : a.caseId().compareTo(b.caseId()));
        var out = new JsonArray();
        for (var r : records) {
            var o = new JsonObject();
            o.addProperty("caseId", r.caseId());
            o.addProperty("record", r.record());
            o.addProperty("text", texts.get(r.caseId()));
            out.add(o);
        }
        var sheet = new JsonObject();
        sheet.addProperty("split", split.split());
        sheet.addProperty("guide", guide);
        sheet.addProperty("agreedSeed", book.seed());
        sheet.addProperty("agreedShare", book.share());
        sheet.addProperty("drawnAt", Adjudications.DRAWN_AT);
        sheet.add("records", out);
        return sheet;
    }

    public static Path sheetPath(Path root, String split, String model) {
        return root.resolve(SHEETS).resolve(split + "-" + StoredRun.fileName(model) + ".json");
    }

    private static void writeOutputs(Path root, CertificationSplit split, Scored scored) throws IOException {
        var sheet = sheetPath(root, split.split(), scored.model().model());
        Files.createDirectories(sheet.getParent());
        Files.writeString(sheet, GSON.toJson(scored.sheet()));
        var certificate = scored.certificate();
        if (certificate != null) {
            certificate.write(root);
            return;
        }
        var stale = CertificateDocument.path(root, scored.model().model());
        if (!Files.exists(stale)) return;
        try {
            var held = JsonParser.parseString(Files.readString(stale));
            var heldSplit = held.isJsonObject() ? held.getAsJsonObject().get("split") : null;
            if (heldSplit != null && heldSplit.isJsonPrimitive() && heldSplit.getAsString().equals(split.split())) {
                Files.delete(stale);
            }
        } catch (JsonParseException _) {
            // Not a certificate this split wrote; leave it for the reader to refuse.
        }
    }
}
