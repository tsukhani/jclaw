package services.grapheval;

import com.google.gson.JsonObject;
import memory.ReciprocalRankFusion;
import memory.graph.GraphStore;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologyRecord.Meta;
import memory.ontology.OntologyRecord.Relation;
import memory.ontology.OntologyRecord.Term;
import memory.ontology.OntologyRecord.Tier;
import memory.ontology.OntologySchema;
import models.MemoryAuthorType;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.jspecify.annotations.Nullable;
import services.decision.JevApi;
import services.grapheval.ExtractionPipeline.Decider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves one typed mention to a Term (JCLAW-1370): owner, identifier key, canonical key, fuzzy match, then a
 * decision-model choice over a shortlist code builds. A name that fails the distinctive-name gate never attaches; it
 * becomes its own Term with a candidate {@code same_as} to each Term sharing its key. Pure: the records exist only in
 * the returned list, and the same inputs give the same records and ids.
 */
public final class EntityResolver {

    /** Unset until a reviewed calibration run names a value; null keeps the fuzzy step off. */
    public static final @Nullable Double FUZZY_THRESHOLD = null;

    /** The fuzzy thresholds a calibration walks, strictest first: 0.99 down to 0.85. */
    public static final List<Double> FUZZY_GRID = grid();

    public static final String SHORTLIST_WORDING = "Which of these known entities is the mention X in the memory? "
            + "Choose new when it is none of them or you cannot tell.";
    public static final String NEW = "new";
    public static final int SHORTLIST_SIZE = 10;
    public static final String SAME_AS = "same_as";
    public static final String OWNER_FALLBACK_NAME = "The user";

    private static final String PERSON = ExtractionPipeline.OPERATOR_TYPE;
    private static final double MIN_ENTROPY_BITS = 1.5;
    private static final int MIN_DISTINCTIVE_LENGTH = 6;
    private static final Pattern WORDING_SLOT = Pattern.compile("\\bX\\b");
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    private EntityResolver() {}

    public enum Step { OWNER, IDENTIFIER, CANONICAL, FUZZY, SHORTLIST, SHORT_NAME, NEW }

    /** One typed mention, the memory it came from and the run that read it. */
    public record Mention(String surface, String type, boolean operator, long memoryId, MemoryAuthorType authorType,
                          Instant recordedAt, LocalDate anchor, String memoryText, @Nullable String runId) {
        public Mention(String surface, String type, boolean operator, long memoryId, MemoryAuthorType authorType,
                       Instant recordedAt, LocalDate anchor, String memoryText) {
            this(surface, type, operator, memoryId, authorType, recordedAt, anchor, memoryText, null);
        }
    }

    /** The model a shortlist is asked of, and the code signals that rank it. */
    public record Shortlist(String model, Decider decider, SignalLookup signals) {}

    /**
     * {@code ownerName} is USER.md's Name; {@code fuzzyThreshold} null keeps the fuzzy step off, and
     * {@code shortlistThreshold} null (no certificate section) keeps the model out.
     */
    public record Settings(OntologySchema schema, @Nullable String ownerName, @Nullable Double fuzzyThreshold,
                           @Nullable Double shortlistThreshold, @Nullable Shortlist shortlist) {}

    /** What code knows about a shortlist candidate against the mention's memory. */
    public interface SignalLookup {
        @Nullable Double vectorSimilarity(Term term);

        int entityOverlap(Term term);

        double keywordOverlap(Term term);

        @Nullable Long daysApart(Term term);
    }

    /**
     * {@code records} is the whole graph after the mention; {@code termId} its Term; {@code attached} whether it
     * joined a Term that existed before; {@code candidates} the Terms a candidate {@code same_as} now links it to.
     */
    public record Resolved(List<OntologyRecord> records, String termId, Step step, boolean attached,
                           List<String> candidates) {
        public Resolved {
            records = List.copyOf(records);
            candidates = List.copyOf(candidates);
        }
    }

    /** One shortlist option, its gloss templated from records: a name, a type and at most one relation. */
    public record Option(String name, String type, @Nullable String relationType, @Nullable String otherName) {
        public String criterion() {
            var tail = relationType == null || otherName == null ? "" : ", " + relationType + " " + otherName;
            return name + " (" + type + tail + ")";
        }
    }

    private static List<Double> grid() {
        var out = new ArrayList<Double>();
        for (int i = 99; i >= 85; i--) out.add(i / 100.0);
        return List.copyOf(out);
    }

    /**
     * The distinctive-name gate, after Graphiti: the Shannon entropy of the key's characters, spaces removed, is at
     * least 1.5 bits, and the key has at least 6 characters or at least 2 tokens. A bare first name fails it. Both
     * count code points.
     */
    public static boolean distinctive(String key) {
        var chars = key.replace(" ", "");
        if (chars.isEmpty()) return false;
        boolean longEnough = key.codePointCount(0, key.length()) >= MIN_DISTINCTIVE_LENGTH
                || key.strip().split(" +").length >= 2;
        return longEnough && entropy(chars) >= MIN_ENTROPY_BITS;
    }

    /** Shannon entropy in bits of {@code s}'s code points. */
    public static double entropy(String s) {
        var counts = new HashMap<Integer, Integer>();
        s.codePoints().forEach(c -> counts.merge(c, 1, Integer::sum));
        double n = s.codePointCount(0, s.length());
        double bits = 0;
        for (int count : counts.values()) {
            double p = count / n;
            bits -= p * Math.log(p) / Math.log(2);
        }
        return bits;
    }

    // ---- resolve ----

    /** Resolves {@code m} against {@code graph} for {@code agentId}. Never calls the decider without a threshold. */
    public static Resolved resolve(long agentId, List<OntologyRecord> graph, Mention m, Settings s) {
        var g = new Graph(agentId, graph);
        var ownerId = TermIds.owner(agentId);
        boolean guest = m.authorType() == MemoryAuthorType.GUEST_TURN;
        var key = ExactMatchResolver.normalize(m.surface(), m.type());
        var ownerName = s.ownerName();

        boolean namedOwner = ownerName != null && m.type().equals(PERSON)
                && key.equals(ExactMatchResolver.normalize(ownerName, PERSON));
        if (!guest && (m.operator() || namedOwner)) {
            var name = ownerName == null ? OWNER_FALLBACK_NAME : ownerName;
            var t = g.term(ownerId);
            boolean existed = t != null;
            if (t == null) {
                g.put(new Term(g.meta(ownerId), PERSON, name, List.of(), List.of()));
            } else if (ownerName != null && !t.name().equals(ownerName)) {
                var aliases = new ArrayList<>(t.aliases());
                if (!aliases.contains(t.name())) aliases.add(t.name());
                aliases.remove(ownerName);
                g.put(new Term(t.meta(), t.type(), ownerName, t.mappingIds(), t.evidenceIds(), aliases, t.mergedInto()));
            }
            g.attach(ownerId, m);
            return g.resolved(ownerId, Step.OWNER, existed, List.of());
        }

        if (ExactMatchResolver.isIdentifier(m.surface(), m.type())) {
            var id = g.end(TermIds.identifier(agentId, ExactMatchResolver.identifierKey(m.surface())));
            boolean existed = g.term(id) != null;
            if (!existed) g.put(new Term(g.meta(id), TermIds.ARTIFACT, m.surface().strip(), List.of(), List.of()));
            g.attach(id, m);
            return g.resolved(id, Step.IDENTIFIER, existed, List.of());
        }

        if (!distinctive(key)) {
            var sameKey = g.candidates(m.type(), ownerId, t -> keys(t).contains(key));
            var id = TermIds.shortName(agentId, m.type(), key, m.memoryId());
            sameKey.remove(id);
            return g.created(id, m, Step.SHORT_NAME, List.copyOf(sameKey), s.schema());
        }

        var canonical = g.candidates(m.type(), ownerId, t -> keys(t).contains(key));
        if (canonical.size() == 1) return g.attached(canonical.getFirst(), m, Step.CANONICAL);
        if (canonical.size() > 1) return g.ambiguous(key, m, Step.CANONICAL, canonical, s.schema());

        var fuzzy = s.fuzzyThreshold();
        if (fuzzy != null) {
            var near = g.candidates(m.type(), ownerId, t -> keys(t).stream()
                    .anyMatch(k -> distinctive(k) && JARO_WINKLER.apply(k, key) >= fuzzy));
            if (near.size() == 1) return g.attached(near.getFirst(), m, Step.FUZZY);
            if (near.size() > 1) return g.ambiguous(key, m, Step.FUZZY, near, s.schema());
        }

        var shortlist = s.shortlist();
        var threshold = s.shortlistThreshold();
        if (threshold != null && shortlist != null) {
            // Mirrors the fuzzy gate, so a short-name or identifier Term never takes a model attach.
            var pool = g.terms().stream().filter(t -> t.type().equals(m.type()) && !t.id().equals(ownerId)
                    && t.mergedInto() == null && distinctive(ExactMatchResolver.normalize(t.name(), t.type()))
                    && !ExactMatchResolver.isIdentifier(t.name(), t.type())).toList();
            if (!pool.isEmpty()) {
                var chosen = choose(g, pool, m, shortlist, threshold);
                if (chosen != null) return g.attached(chosen, m, Step.SHORTLIST);
                return g.created(newId(g, agentId, key, m), m, Step.SHORTLIST, List.of(), s.schema());
            }
        }
        return g.created(newId(g, agentId, key, m), m, Step.NEW, List.of(), s.schema());
    }

    private static String newId(Graph g, long agentId, String key, Mention m) {
        var id = TermIds.distinctive(agentId, m.type(), key);
        return g.has(id) ? TermIds.shortName(agentId, m.type(), key, m.memoryId()) : id;
    }

    /** The chosen Term's id, or null on {@code new}, a choice below the threshold or any failure. */
    private static @Nullable String choose(Graph g, List<Term> pool, Mention m, Shortlist shortlist, double threshold) {
        try {
            var ranked = rank(pool, shortlist.signals());
            var top = ranked.subList(0, Math.min(SHORTLIST_SIZE, ranked.size()));
            var options = top.stream().map(id -> option(g, Objects.requireNonNull(g.term(id)))).toList();
            var question = shortlistQuestion(SHORTLIST_WORDING, m.surface(), options);
            var state = new JsonObject();
            state.addProperty("memory", m.memoryText());
            var decision = ExtractionPipeline.choose(shortlist.model(), state, question, optionIds(options.size()),
                    shortlist.decider());
            var choice = decision.choice();
            if (choice == null || choice.equals(NEW) || decision.confidence() < threshold) return null;
            int index = Integer.parseInt(choice.substring(1)) - 1;
            return top.get(index);
        } catch (RuntimeException _) {
            return null;
        }
    }

    /**
     * Candidate ids best first: the four signals each rank the candidates sorted by id (vector, entity and keyword
     * descending, days apart ascending, a null last, ties by position), fused by {@link ReciprocalRankFusion}.
     */
    public static List<String> rank(List<Term> candidates, SignalLookup signals) {
        var sorted = candidates.stream().sorted(Comparator.comparing(Term::id)).toList();
        var vector = new ArrayList<@Nullable Double>();
        var entity = new ArrayList<@Nullable Double>();
        var keyword = new ArrayList<@Nullable Double>();
        var days = new ArrayList<@Nullable Double>();
        for (var t : sorted) {
            vector.add(signals.vectorSimilarity(t));
            entity.add((double) signals.entityOverlap(t));
            keyword.add(signals.keywordOverlap(t));
            var d = signals.daysApart(t);
            days.add(d == null ? null : -d.doubleValue());
        }
        var fused = ReciprocalRankFusion.fuse(ReciprocalRankFusion.DEFAULT_K, signalOrder(vector), signalOrder(entity),
                signalOrder(keyword), signalOrder(days));
        return fused.stream().map(r -> sorted.get((int) r.id()).id()).toList();
    }

    /** Positions ordered by value descending, a null last, ties by position. */
    public static List<Long> signalOrder(List<@Nullable Double> values) {
        var out = new ArrayList<Long>();
        for (long i = 0; i < values.size(); i++) out.add(i);
        out.sort((a, b) -> {
            var x = values.get(a.intValue());
            var y = values.get(b.intValue());
            if (x == null || y == null) {
                if (x != null) return -1;
                if (y != null) return 1;
                return Long.compare(a, b);
            }
            int c = Double.compare(y, x);
            return c != 0 ? c : Long.compare(a, b);
        });
        return out;
    }

    private static Option option(Graph g, Term t) {
        for (var r : g.relations()) {
            // From the from end only: "X works_at Y" glossed on Y would read reversed.
            if (r.type().equals(SAME_AS) || !r.from().equals(t.id())) continue;
            var end = g.term(r.to());
            if (end != null) return new Option(t.name(), t.type(), r.type(), end.name());
        }
        return new Option(t.name(), t.type(), null, null);
    }

    private static Set<String> optionIds(int options) {
        var ids = new LinkedHashSet<String>();
        for (int i = 1; i <= options; i++) ids.add("t" + i);
        ids.add(NEW);
        return ids;
    }

    /**
     * The shortlist as one choice question: options {@code t1..tN} glossed from records, plus {@code new};
     * {@code wording} rendered with the quoted mention in place of X.
     */
    public static JsonObject shortlistQuestion(String wording, String mention, List<Option> options) {
        var criteria = new JsonObject();
        for (int i = 0; i < options.size(); i++) criteria.addProperty("t" + (i + 1), options.get(i).criterion());
        criteria.addProperty(NEW, "None of these, or it cannot be told");
        var instructions = new JsonObject();
        instructions.addProperty("rules", WORDING_SLOT.matcher(wording)
                .replaceAll(Matcher.quoteReplacement("\"" + mention + "\"")));
        return JevApi.choiceQuestion(criteria, instructions);
    }

    /** The shortlist question the extraction fingerprint renders: mention X over two placeholder options. */
    public static JsonObject fingerprintShortlistQuestion(String wording) {
        return shortlistQuestion(wording, "X", List.of(new Option("Y", "Person", null, null),
                new Option("Z", "Organization", "works_at", "Y")));
    }

    private static Set<String> keys(Term t) {
        var out = new HashSet<String>();
        out.add(ExactMatchResolver.normalize(t.name(), t.type()));
        t.aliases().forEach(a -> out.add(ExactMatchResolver.normalize(a, t.type())));
        return out;
    }

    // ---- merge and undo ----

    /**
     * {@code graph} with {@code from}'s Mappings and Evidence copied onto {@code into} under derived ids and
     * {@code from.mergedInto} set. Relations are not copied. A claim-bearing Evidence is not copied when {@code into}
     * already holds a claim from its source. Into the owner, a guest's Evidence is never copied, nor a Mapping left
     * with none.
     *
     * @throws IllegalArgumentException when either id names no Term, the two are one Term or differ in type, either
     *     is already merged, {@code from} is the owner, or {@code into} is the owner and every Evidence of
     *     {@code from} is a guest's
     */
    public static List<OntologyRecord> merge(long agentId, List<OntologyRecord> graph, String from, String into) {
        var g = new Graph(agentId, graph);
        var source = g.term(from);
        var target = g.term(into);
        if (source == null || target == null) {
            throw new IllegalArgumentException("merge: no Term '" + (source == null ? from : into) + "'");
        }
        if (from.equals(into)) throw new IllegalArgumentException("merge: '" + from + "' into itself");
        if (!source.type().equals(target.type())) {
            throw new IllegalArgumentException("merge: " + source.type() + " '" + from + "' into " + target.type()
                    + " '" + into + "'");
        }
        if (source.mergedInto() != null) {
            throw new IllegalArgumentException("merge: '" + from + "' is already merged into " + source.mergedInto());
        }
        // A merged target would let a mergedInto cycle or an onward chain form.
        if (target.mergedInto() != null) {
            throw new IllegalArgumentException("merge: '" + into + "' is itself merged into " + target.mergedInto());
        }
        var ownerId = TermIds.owner(agentId);
        if (from.equals(ownerId)) throw new IllegalArgumentException("merge: the owner's Term never merges away");
        boolean intoOwner = into.equals(ownerId);
        if (intoOwner && source.evidenceIds().stream().map(g::evidence)
                .allMatch(e -> e == null || e.authorType() == MemoryAuthorType.GUEST_TURN)) {
            throw new IllegalArgumentException("merge: a guest's Term '" + from + "' never joins the owner");
        }
        var claimedSources = new HashSet<String>();
        for (var id : target.evidenceIds()) {
            var e = g.evidence(id);
            if (e != null && claimBearing(e)) claimedSources.add(e.source());
        }
        var evidenceCopies = new LinkedHashMap<String, String>();
        for (var id : source.evidenceIds()) {
            var e = g.evidence(id);
            if (e == null || (claimBearing(e) && claimedSources.contains(e.source()))) continue;
            if (intoOwner && e.authorType() == MemoryAuthorType.GUEST_TURN) continue;
            var copyId = TermIds.derived("ev", into, id);
            evidenceCopies.put(id, copyId);
            var subject = from.equals(e.subjectId()) ? into : e.subjectId();
            g.put(new Evidence(g.meta(copyId), e.source(), subject, e.authorType(), e.confidence(), e.runId(),
                    e.recordedAt(), e.retiredAt(), e.retiredBy(), e.lineage(), e.changedBy(), e.anchor(), e.status(),
                    e.valid(), e.occurs(), e.valence()));
        }
        var mappingCopies = new ArrayList<String>();
        for (var id : source.mappingIds()) {
            if (!(g.get(id) instanceof Mapping mapping)) continue;
            var evidence = mapping.evidenceIds().stream().map(evidenceCopies::get).filter(Objects::nonNull).toList();
            if (evidence.isEmpty()) continue;
            var copyId = TermIds.derived("map", into, id);
            mappingCopies.add(copyId);
            g.put(new Mapping(g.meta(copyId), into, mapping.source(), evidence, mapping.surfaces()));
        }
        g.put(new Term(target.meta(), target.type(), target.name(), union(target.mappingIds(), mappingCopies),
                union(target.evidenceIds(), evidenceCopies.values()), target.aliases(), target.mergedInto()));
        g.put(new Term(source.meta(), source.type(), source.name(), source.mappingIds(), source.evidenceIds(),
                source.aliases(), into));
        return g.records();
    }

    /**
     * {@code graph} with the copies {@link #merge} made from {@code from} removed, pruned from its target, and
     * {@code from.mergedInto} cleared.
     *
     * @throws IllegalArgumentException when {@code from} names no Term, is not merged, or its target is itself merged
     *     (the copies travelled onward, so that merge is undone first)
     */
    public static List<OntologyRecord> undo(long agentId, List<OntologyRecord> graph, String from) {
        var g = new Graph(agentId, graph);
        var source = g.term(from);
        if (source == null) throw new IllegalArgumentException("undo: no Term '" + from + "'");
        var into = source.mergedInto();
        if (into == null) throw new IllegalArgumentException("undo: '" + from + "' is not merged");
        var target = g.term(into);
        if (target != null && target.mergedInto() != null) {
            throw new IllegalArgumentException("undo: '" + into + "' is merged into " + target.mergedInto()
                    + "; undo that first");
        }
        var copies = new HashSet<String>();
        source.mappingIds().forEach(id -> copies.add(TermIds.derived("map", into, id)));
        source.evidenceIds().forEach(id -> copies.add(TermIds.derived("ev", into, id)));
        copies.forEach(g::remove);
        if (target != null) {
            g.put(new Term(target.meta(), target.type(), target.name(),
                    target.mappingIds().stream().filter(id -> !copies.contains(id)).toList(),
                    target.evidenceIds().stream().filter(id -> !copies.contains(id)).toList(), target.aliases(),
                    target.mergedInto()));
        }
        g.put(new Term(source.meta(), source.type(), source.name(), source.mappingIds(), source.evidenceIds(),
                source.aliases(), null));
        return g.records();
    }

    private static boolean claimBearing(Evidence e) {
        return e.status() != null || e.valid() != null || e.occurs() != null || e.valence() != null;
    }

    private static List<String> union(List<String> ids, Iterable<String> more) {
        var out = new LinkedHashSet<>(ids);
        more.forEach(out::add);
        return List.copyOf(out);
    }

    /** The working copy of one agent's graph, in input order with new records appended. */
    private static final class Graph {
        private final long agentId;
        private final LinkedHashMap<String, OntologyRecord> byId = new LinkedHashMap<>();

        Graph(long agentId, List<OntologyRecord> records) {
            this.agentId = agentId;
            records.forEach(r -> byId.put(r.id(), r));
        }

        Meta meta(String id) {
            return Meta.fresh(id, agentId, Tier.TENTATIVE);
        }

        boolean has(String id) {
            return byId.containsKey(id);
        }

        @Nullable OntologyRecord get(String id) {
            return byId.get(id);
        }

        @Nullable Term term(String id) {
            return byId.get(id) instanceof Term t ? t : null;
        }

        @Nullable Evidence evidence(String id) {
            return byId.get(id) instanceof Evidence e ? e : null;
        }

        void put(OntologyRecord r) {
            byId.put(r.id(), r);
        }

        void remove(String id) {
            byId.remove(id);
        }

        List<OntologyRecord> records() {
            return List.copyOf(byId.values());
        }

        List<Term> terms() {
            return byId.values().stream().filter(Term.class::isInstance).map(Term.class::cast).toList();
        }

        List<Relation> relations() {
            return byId.values().stream().filter(Relation.class::isInstance).map(Relation.class::cast)
                    .sorted(Comparator.comparing(Relation::id)).toList();
        }

        /** The end of {@code id}'s {@code mergedInto} chain; a cycle stops where it would repeat. */
        String end(String id) {
            var seen = new HashSet<String>();
            var current = id;
            while (seen.add(current)) {
                var t = term(current);
                var next = t == null ? null : t.mergedInto();
                if (next == null || term(next) == null) return current;
                current = next;
            }
            return current;
        }

        /** The distinct chain ends of the non-owner Terms of {@code type} that {@code qualifies}, in graph order. */
        List<String> candidates(String type, String ownerId, Predicate<Term> qualifies) {
            var out = new LinkedHashSet<String>();
            for (var t : terms()) {
                if (!t.type().equals(type) || t.id().equals(ownerId) || !qualifies.test(t)) continue;
                var end = end(t.id());
                if (!end.equals(ownerId)) out.add(end);
            }
            return new ArrayList<>(out);
        }

        Resolved resolved(String termId, Step step, boolean attached, List<String> candidates) {
            return new Resolved(records(), termId, step, attached, candidates);
        }

        Resolved attached(String termId, Mention m, Step step) {
            attach(termId, m);
            return resolved(termId, step, true, List.of());
        }

        /** Nothing attaches: a new Term with a candidate {@code same_as} to each qualifier. */
        Resolved ambiguous(String key, Mention m, Step step, List<String> qualifiers, OntologySchema schema) {
            var id = TermIds.distinctive(agentId, m.type(), key);
            if (has(id)) id = TermIds.shortName(agentId, m.type(), key, m.memoryId());
            var others = new ArrayList<>(qualifiers);
            others.remove(id);
            return created(id, m, step, others, schema);
        }

        /** {@code id} as a Term of the mention's type (kept if a re-pass already made it), linked to {@code others}. */
        Resolved created(String id, Mention m, Step step, List<String> others, OntologySchema schema) {
            if (term(id) == null) put(new Term(meta(id), m.type(), m.surface().strip(), List.of(), List.of()));
            attach(id, m);
            for (var other : others) sameAs(schema, id, other, m);
            return resolved(id, step, false, others);
        }

        /** Grounds {@code termId} in the mention's memory: Mapping, claim-free Evidence and a new alias. */
        void attach(String termId, Mention m) {
            var source = GraphStore.memorySource(m.memoryId());
            var evidenceId = Evidence.claimId(termId, source);
            if (!has(evidenceId)) put(evidence(evidenceId, source, termId, m));
            var surface = m.surface().strip();
            var mappingId = TermIds.derived("map", termId, source);
            var existing = byId.get(mappingId) instanceof Mapping mapping ? mapping : null;
            var surfaces = new ArrayList<String>(existing == null ? List.of() : existing.surfaces());
            if (!surface.isEmpty() && !surfaces.contains(surface)) surfaces.add(surface);
            var evidenceIds = existing == null ? List.of(evidenceId) : union(existing.evidenceIds(), List.of(evidenceId));
            put(new Mapping(existing == null ? meta(mappingId) : existing.meta(), termId, source, evidenceIds, surfaces));
            var t = Objects.requireNonNull(term(termId));
            var aliases = new ArrayList<>(t.aliases());
            if (!surface.isEmpty() && !surface.equals(t.name()) && !aliases.contains(surface)) aliases.add(surface);
            put(new Term(t.meta(), t.type(), t.name(), union(t.mappingIds(), List.of(mappingId)),
                    union(t.evidenceIds(), List.of(evidenceId)), aliases, t.mergedInto()));
        }

        /** A candidate {@code same_as}: claim-free Evidence from the mention's memory, so it answers UNKNOWN. */
        void sameAs(OntologySchema schema, String a, String b, Mention m) {
            var id = StatementRecords.relationId(schema, SAME_AS, a, b);
            var from = a.compareTo(b) <= 0 ? a : b;
            var to = from.equals(a) ? b : a;
            var source = GraphStore.memorySource(m.memoryId());
            var evidenceId = Evidence.claimId(id, source);
            if (!has(evidenceId)) put(evidence(evidenceId, source, id, m));
            var relation = byId.get(id) instanceof Relation r ? r : new Relation(meta(id), SAME_AS, from, to, List.of());
            put(new Relation(relation.meta(), relation.type(), relation.from(), relation.to(), relation.weight(),
                    union(relation.evidenceIds(), List.of(evidenceId))));
        }

        private Evidence evidence(String id, String source, String subjectId, Mention m) {
            return new Evidence(meta(id), source, subjectId, m.authorType(), null, m.runId(), m.recordedAt(), null,
                    null, null, null, m.anchor(), null, null, null, null);
        }
    }
}
