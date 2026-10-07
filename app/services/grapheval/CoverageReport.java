package services.grapheval;

import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;
import services.grapheval.CompetencyQuestions.Question;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphCases.Entity;
import services.grapheval.GraphCases.Relation;
import services.grapheval.HeldOut.HeldCase;
import services.grapheval.StageScorer.StageRun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * How much of what the held-out memories say the schema covers (JCLAW-1374), from their coverage labels and, per
 * model, the gold-fed typing stage. Counts only: no memory id, text or span. Pure: no I/O, no clock, no JPA.
 */
public final class CoverageReport {

    /** The strata, in report order. */
    public static final List<String> STRATA = List.of("zero-named", "one-named", "two-named", "three-or-more-named",
            "owner-not-named", "negation", "plans-or-uncertainty", "numbers", "quantity", "agent-instruction", "ended",
            "guest");
    /** The gold types the confusion matrix reads. */
    public static final List<String> CONFUSED = List.of("Project", "System", "Artifact");
    public static final String OTHER = "other";
    public static final String NOT_AN_ENTITY = "notAnEntity";
    public static final String FAILED = "failed";

    private static final Set<String> ASSERTED = Set.of(GraphCases.HOLDS, GraphCases.ENDED, GraphCases.DENIED);

    private CoverageReport() {}

    /** Per question: {@code represented} for a claim or null facet, {@code unrepresented} for a not-representable one. */
    public record QuestionCount(String id, @Nullable String facet, @Nullable Integer represented,
                                @Nullable Integer unrepresented) {}

    public record Cell(String from, String relation, String to, int memories, List<String> questions) {
        public Cell {
            questions = List.copyOf(questions);
        }
    }

    public record Grid(List<Cell> cells, int covered, int allowed) {
        public Grid {
            cells = List.copyOf(cells);
        }
    }

    /** Memories per kind, in list order with zeros, memories with any, and that share of the counted. */
    public record Kinds(Map<String, Integer> byKind, int any, @Nullable Double share) {}

    public record Share(int count, @Nullable Double share) {}

    /** Labelled cases counted, and those left out for lacking the three coverage fields. */
    public record Labelled(int counted, int lackingFields) {}

    /** {@code questions} is null when there is no question file. */
    public record Sections(@Nullable List<QuestionCount> questions, Grid grid, Kinds notRepresentable, Kinds numbers,
                           Share backReference, Map<String, Share> strata, Labelled labelled) {}

    /** One model's gold Project, System and Artifact spans against what its typing stage chose. */
    public record Confusion(String model, Map<String, Map<String, Integer>> rows) {}

    /** The label sections over the labelled {@code cases} that carry all three coverage fields. */
    public static Sections sections(List<HeldCase> cases, @Nullable List<Question> questions, OntologySchema schema) {
        var counted = counted(cases);
        int n = counted.size();
        List<QuestionCount> questionCounts = null;
        if (questions != null) {
            questionCounts = questions.stream().map(q -> question(q, counted, schema)).toList();
        }
        return new Sections(questionCounts, grid(counted, questions, schema),
                kinds(counted, CompetencyQuestions.NOT_REPRESENTABLE, h -> coverage(h).notRepresentable()),
                kinds(counted, CompetencyQuestions.NUMBER_KINDS, h -> coverage(h).numbers()),
                share(count(counted, h -> coverage(h).backReference()), n), strata(counted),
                new Labelled(n, cases.size() - n));
    }

    /**
     * Each model's typing decisions over every run, in {@code byModel}'s order, on the counted cases: a decision on a
     * gold Project, System or Artifact mention, matched as the stage scorer matches it.
     */
    public static List<Confusion> confusions(List<HeldCase> cases, Map<String, List<StageRun>> byModel) {
        var byId = new HashMap<String, Case>();
        for (var h : counted(cases)) byId.put(String.valueOf(h.memoryId()), h.labels());
        var out = new ArrayList<Confusion>();
        byModel.forEach((model, runs) -> {
            var rows = new LinkedHashMap<String, Map<String, Integer>>();
            for (var gold : CONFUSED) {
                var row = new LinkedHashMap<String, Integer>();
                for (var column : columns()) row.put(column, 0);
                rows.put(gold, row);
            }
            for (var run : runs) {
                var c = byId.get(run.caseId());
                if (c == null) continue;
                for (var d : run.typing()) {
                    if (!d.stage().equals(ExtractionPipeline.TERM)) continue;
                    var entity = c.entityAt(d.subject());
                    if (entity == null || !d.subject().equals(entity.mention())) continue;
                    var row = rows.get(entity.type());
                    if (row != null) row.merge(column(d), 1, Integer::sum);
                }
            }
            out.add(new Confusion(model, rows));
        });
        return List.copyOf(out);
    }

    private static List<String> columns() {
        var columns = new ArrayList<>(CONFUSED);
        columns.addAll(List.of(OTHER, NOT_AN_ENTITY, FAILED));
        return columns;
    }

    private static String column(ExtractionPipeline.Decision d) {
        var choice = d.choice();
        if (choice == null) return FAILED;
        if (choice.equals(ExtractionPipeline.NOT_AN_ENTITY)) return NOT_AN_ENTITY;
        return CONFUSED.contains(choice) ? choice : OTHER;
    }

    private static List<HeldCase> counted(List<HeldCase> cases) {
        return cases.stream().filter(h -> h.coverage() != null).toList();
    }

    /** Only called on counted cases. */
    private static HeldOut.Coverage coverage(HeldCase h) {
        var coverage = h.coverage();
        if (coverage == null) throw new IllegalStateException("a counted case carries its coverage labels");
        return coverage;
    }

    private static QuestionCount question(Question q, List<HeldCase> counted, OntologySchema schema) {
        var facet = q.facet();
        if (facet != null && CompetencyQuestions.NOT_REPRESENTABLE.contains(facet)) {
            return new QuestionCount(q.id(), facet, null,
                    count(counted, h -> coverage(h).notRepresentable().contains(facet)));
        }
        return new QuestionCount(q.id(), facet, count(counted, h -> represents(h.labels(), q, schema)), null);
    }

    private static boolean represents(Case c, Question q, OntologySchema schema) {
        var relation = q.relation();
        var facet = q.facet();
        if (relation == null) {
            return c.entities().stream().anyMatch(e -> !e.noise() && !e.operator()
                    && e.type().equals(q.types().getFirst()) && (!"occurs".equals(facet) || e.occurs() != null));
        }
        for (var r : c.relations()) {
            if (!asserted(r, relation) || !endpoints(c, r, q.types().get(0), q.types().get(1))) continue;
            if (facet == null || claimPresent(c, r, facet, schema)) return true;
        }
        return false;
    }

    private static boolean claimPresent(Case c, Relation r, String facet, OntologySchema schema) {
        return switch (facet) {
            case "valid" -> r.valid() != null;
            case "valence" -> r.valence() != null;
            case "occurs" -> datedOccurs(c.entity(r.from()), schema) || datedOccurs(c.entity(r.to()), schema);
            default -> true;
        };
    }

    private static boolean datedOccurs(@Nullable Entity e, OntologySchema schema) {
        if (e == null || e.occurs() == null) return false;
        var type = schema.termTypes().get(e.type());
        return type != null && type.dated();
    }

    /** A non-noise relation of {@code type} whose raw status is holds, ended or denied. */
    private static boolean asserted(Relation r, String type) {
        return !r.noise() && r.type().equals(type) && ASSERTED.contains(r.status());
    }

    private static boolean endpoints(Case c, Relation r, String from, String to) {
        var f = c.entity(r.from());
        var t = c.entity(r.to());
        return f != null && t != null && f.type().equals(from) && t.type().equals(to);
    }

    private static Grid grid(List<HeldCase> counted, @Nullable List<Question> questions, OntologySchema schema) {
        var cells = new ArrayList<Cell>();
        int covered = 0;
        for (var from : schema.termTypes().keySet()) {
            for (var relation : schema.relations().keySet()) {
                for (var to : schema.termTypes().keySet()) {
                    if (!schema.allows(relation, from, to)) continue;
                    int memories = count(counted, h -> h.labels().relations().stream()
                            .anyMatch(r -> asserted(r, relation) && endpoints(h.labels(), r, from, to)));
                    var needing = questions == null ? List.<String>of() : questions.stream()
                            .filter(q -> relation.equals(q.relation()) && q.types().get(0).equals(from)
                                    && q.types().get(1).equals(to))
                            .map(Question::id).toList();
                    if (memories > 0) covered++;
                    cells.add(new Cell(from, relation, to, memories, needing));
                }
            }
        }
        return new Grid(cells, covered, cells.size());
    }

    private static Kinds kinds(List<HeldCase> counted, List<String> kinds,
                               Function<HeldCase, List<String>> labels) {
        var byKind = new LinkedHashMap<String, Integer>();
        for (var kind : kinds) byKind.put(kind, count(counted, h -> labels.apply(h).contains(kind)));
        int any = count(counted, h -> !labels.apply(h).isEmpty());
        return new Kinds(byKind, any, rate(any, counted.size()));
    }

    private static Map<String, Share> strata(List<HeldCase> counted) {
        int n = counted.size();
        var out = new LinkedHashMap<String, Share>();
        out.put("zero-named", share(count(counted, h -> named(h.labels()) == 0), n));
        out.put("one-named", share(count(counted, h -> named(h.labels()) == 1), n));
        out.put("two-named", share(count(counted, h -> named(h.labels()) == 2), n));
        out.put("three-or-more-named", share(count(counted, h -> named(h.labels()) >= 3), n));
        out.put("owner-not-named", share(count(counted, h -> {
            var operator = h.labels().entity(GraphCases.OPERATOR);
            return operator != null && operator.implicit();
        }), n));
        out.put("negation", share(count(counted, h -> tagOrStatus(h.labels(), GraphCases.NEGATED,
                GraphCases.DENIED)), n));
        out.put("plans-or-uncertainty", share(count(counted, h -> tagOrStatus(h.labels(), GraphCases.UNASSERTED,
                GraphCases.UNASSERTED)), n));
        out.put("numbers", share(count(counted, h -> !coverage(h).numbers().isEmpty()), n));
        out.put("quantity", share(count(counted, h -> coverage(h).numbers().contains("quantity")), n));
        out.put("agent-instruction", share(count(counted,
                h -> coverage(h).notRepresentable().contains("instruction")), n));
        out.put("ended", share(count(counted, h -> tagOrStatus(h.labels(), GraphCases.ENDED, GraphCases.ENDED)), n));
        out.put("guest", share(count(counted, h -> h.authorType() == MemoryAuthorType.GUEST_TURN), n));
        return out;
    }

    /** Entities other than the operator, noise included. */
    private static int named(Case c) {
        return (int) c.entities().stream().filter(e -> !e.operator()).count();
    }

    private static boolean tagOrStatus(Case c, String tag, String status) {
        return c.tags().contains(tag) || c.relations().stream().anyMatch(r -> r.status().equals(status));
    }

    private static int count(List<HeldCase> counted, Predicate<HeldCase> in) {
        return (int) counted.stream().filter(in).count();
    }

    private static Share share(int count, int counted) {
        return new Share(count, rate(count, counted));
    }

    private static @Nullable Double rate(int count, int counted) {
        return counted == 0 ? null : (double) count / counted;
    }
}
