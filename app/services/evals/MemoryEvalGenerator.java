package services.evals;

import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import memory.MemorySimilarity;
import memory.MemoryStoreFactory;
import models.Agent;
import models.Memory;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a memory-recall eval suite from an agent's own corpus (JCLAW-529).
 *
 * <p>Synthesised rather than sourced. LOCOMO and LongMemEval score retrieval over their
 * own transcripts, which makes results comparable across systems but says nothing about
 * whether recall works on the memories this instance actually holds — and those are what
 * it has to serve. The trade is deliberate: relevance now, comparability later.
 *
 * <p>The generated artifact is personal data, so it is written only through
 * {@link MemoryEvalPaths}, never returned wholesale to a caller and never placed beside
 * the tracked suites.
 *
 * <p><b>Suites generated before JCLAW-1054 are not comparable with ones generated after.</b>
 * Clustering and the rare-shared-token pairing both run on {@link MemorySimilarity}, which
 * moved onto the search analyzer's normalization — so the cases this produces changed, not
 * merely the scores they earn. Nothing tracked is affected (these suites live outside the
 * repo by design), but a recall baseline measured on an older generated suite should be
 * re-generated rather than compared across the change. The suite fingerprint will not warn
 * about it: it covers case content, not the generator's inputs.
 */
public final class MemoryEvalGenerator {

    private MemoryEvalGenerator() {}

    private static final String EVENT_CATEGORY = "memory";

    /** Every active memory of one agent, oldest first — the corpus a suite samples. */
    private static final String ACTIVE_MEMORIES_JPQL =
            "agent.id = ?1 AND supersededAt IS NULL ORDER BY id";

    /** Generous: the maxFacts ceiling, not this, is what decides a cluster is too broad. */
    private static final int MAX_SEMANTIC_NEIGHBOURS = 50;

    private static final String INSTRUCTIONS = """
            You write evaluation questions for a memory-retrieval system. Given one stored \
            fact about a user, write the single most natural question that user might ask \
            whose answer is that fact.

            Do not quote the fact. Reuse as few of its exact words as you can while keeping \
            the question answerable — a question that repeats the fact verbatim tests string \
            matching rather than retrieval. Write it as the user would type it.

            Output only the question, on one line, with no preamble and no quotation marks.""";

    /** Functional seam for the question call, mirroring {@code MemoryAutoCapture.Extractor}. */
    @FunctionalInterface
    public interface QuestionWriter {
        @SuppressWarnings("java:S112")
        String write(List<ChatMessage> messages) throws Exception;
    }

    /** A memory lifted out of its transaction, so the model calls hold no connection. */
    private record Row(Long id, String text, String retrievalKey, Instant createdAt) {}

    private static List<Row> corpus(Agent agent) {
        return Tx.run(() -> Memory.<Memory>find(ACTIVE_MEMORIES_JPQL, agent.id).<Memory>fetch()
                .stream().map(m -> new Row(m.id, m.text, m.retrievalKey, m.createdAt)).toList());
    }

    /**
     * How a coverage question's set of distinct facts is decided.
     *
     * @param by        {@code "lexical"} groups on shared content tokens; {@code "semantic"}
     *                  groups on embedding neighbours. This choice decides what the A/B can
     *                  conclude — see {@link #generateCoverage}
     * @param threshold lexical: minimum token Jaccard. semantic: minimum cosine
     * @param maxFacts  ceiling on distinct facts per question; past it the cluster is a
     *                  topic rather than a question and its retrieval is too diffuse to
     *                  compare rankers with
     */
    public record Clustering(String by, double threshold, int minFacts, int maxFacts) {
        public boolean semantic() {
            return "semantic".equals(by);
        }

        /** JCLAW-943: {@code threshold} is then a span in days, not a similarity. */
        public boolean temporal() {
            return "temporal".equals(by);
        }
    }

    /**
     * Generate a suite of at most {@code sampleSize} cases for {@code agent}.
     *
     * <p>Sampling is a deterministic stride across the corpus in id order rather than a
     * random draw: a suite is a measuring stick, and one that selects different memories
     * each time it is built cannot be compared with the run before it.
     */
    public static MemoryEvalSuite generate(Agent agent, String suiteId, int sampleSize,
                                           QuestionWriter writer) {
        var rows = corpus(agent);
        if (rows.isEmpty()) {
            return new MemoryEvalSuite(suiteId, "No memories to sample.", corpusFingerprint(rows), List.of());
        }

        int stride = Math.max(1, rows.size() / Math.max(1, sampleSize));
        var cases = new ArrayList<MemoryEvalCase>();
        for (int i = 0; i < rows.size() && cases.size() < sampleSize; i += stride) {
            var row = rows.get(i);
            String question;
            try {
                question = writer.write(List.of(
                        ChatMessage.system(INSTRUCTIONS),
                        ChatMessage.user(row.text()))).strip();
            } catch (Exception e) {
                EventLogger.warn(EVENT_CATEGORY,
                        "Eval question generation failed for memory %d: %s".formatted(row.id(), e.getMessage()));
                continue;
            }
            if (question.isBlank() || question.lines().count() > 1) continue;
            cases.add(new MemoryEvalCase("mem-" + row.id(), question, List.of(goldFor(row, rows))));
        }
        return new MemoryEvalSuite(suiteId,
                "Generated from %d memories of agent %s".formatted(rows.size(), agent.name),
                corpusFingerprint(rows), cases);
    }

    private static final String COVERAGE_INSTRUCTIONS = """
            You write evaluation questions for a memory-retrieval system. Given several \
            related facts about a user, write the single broad question that would need \
            ALL of them to be answered well.

            Ask about the shared subject at the level the whole group covers, not about any \
            one fact. Do not quote the facts or enumerate them. Write it as the user would \
            type it.

            Output only the question, on one line, with no preamble and no quotation marks.""";

    /**
     * Generate coverage cases: broad questions whose answer needs several distinct facts.
     *
     * <p>This is what measures whether a block answers a question rather than merely
     * containing a hit. A single-fact suite cannot: a block holding one fact three times
     * and a block holding three different facts score identically on recall, because both
     * contain "the" answer.
     *
     * <p><b>The clustering signal decides what the suite can conclude, so pick it against
     * the comparison being run.</b> A gold grouping built on the same signal a ranker
     * scores on settles the comparison before it runs. Grouping on token Jaccard to
     * evaluate a ranker that penalises token Jaccard produced exactly that: a clean
     * monotone decline that restated the clustering choice rather than measuring anything.
     * Semantic clustering groups on embedding cosine, so use it against any lexical
     * ranker, and lexical clustering against a purely semantic one.
     *
     * <p>Neither signal is fully independent of retrieval, because recall is hybrid: the
     * lexical one correlates with its keyword leg and the semantic one with its vector leg,
     * and each inflates absolute coverage accordingly. That bias applies to both arms of an
     * A/B equally, so a suite compares two rankers honestly while an absolute coverage
     * number from it means little.
     */
    public static MemoryEvalSuite generateCoverage(Agent agent, String suiteId, int maxCases,
                                                   Clustering clustering, QuestionWriter writer) {
        var rows = corpus(agent);
        var cases = new ArrayList<MemoryEvalCase>();
        var used = new HashSet<Long>();

        for (var seed : rows) {
            if (cases.size() >= maxCases) break;
            if (used.contains(seed.id())) continue;
            var cluster = clusterAround(agent, seed, rows, clustering);
            var groups = distinctFacts(cluster);
            // Fewer than three distinct facts is not a coverage question — there is
            // nothing for the budget to have to choose between. Past a ceiling it stops being a
            // question too: measured on this corpus, clusters of 13 and 21 facts produced
            // "what is JClaw and how do I use it in my work?" — a topic, whose retrieval is
            // diffuse enough to add noise to a comparison rather than signal.
            if (groups.size() < clustering.minFacts() || groups.size() > clustering.maxFacts()) continue;

            String question;
            try {
                question = writer.write(List.of(
                        ChatMessage.system(COVERAGE_INSTRUCTIONS),
                        ChatMessage.user(cluster.stream().map(Row::text)
                                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b)))).strip();
            } catch (Exception e) {
                EventLogger.warn(EVENT_CATEGORY,
                        "Coverage question generation failed near memory %d: %s".formatted(seed.id(), e.getMessage()));
                continue;
            }
            if (question.isBlank() || question.lines().count() > 1) continue;

            cases.add(new MemoryEvalCase("cov-" + seed.id(), question,
                    MemoryEvalCase.SHAPE_COVERAGE, groups));
            cluster.forEach(r -> used.add(r.id()));
        }
        return new MemoryEvalSuite(suiteId,
                "Coverage suite: %d clusters over %d memories of agent %s"
                        .formatted(cases.size(), rows.size(), agent.name),
                corpusFingerprint(rows), cases);
    }

    private static final String BRIDGE_INSTRUCTIONS = """
            You write evaluation questions for a memory-retrieval system. You are given two \
            stored facts about a user: a RELATION fact, which says how the user is connected \
            to someone or something, and a TARGET fact, which states something about that \
            same subject without repeating the connection.

            Write the single question the user would ask that refers to the subject through \
            the RELATION — the way they would actually say it, not by name — and whose answer \
            is the TARGET fact. Do not name the subject. Do not quote either fact.

            Output only the question, on one line, with no preamble and no quotation marks.""";

    /**
     * Words that state how the user is connected to a subject. A memory carrying one of
     * these can bridge a question to a memory that names only the subject.
     */
    private static final Set<String> RELATION_WORDS = Set.of(
            "son", "sons", "daughter", "daughters", "child", "children", "kid", "kids",
            "wife", "husband", "spouse", "partner", "mother", "father", "parents",
            "brother", "sister", "sibling", "siblings", "friend", "colleague", "manager",
            "employer", "company", "team", "client", "landlord", "neighbour", "neighbor",
            "doctor", "dog", "cat", "pet", "laptop", "phone", "car", "house", "apartment");

    /** A token in this few memories is an entity rather than vocabulary. */
    private static final int RARE_TOKEN_MAX_DOCS = 3;

    /**
     * Generate bridge cases: the query asks through a relation, the gold memory names only
     * the entity (JCLAW-529).
     *
     * <p>This is the case class {@link #generate} structurally cannot reach. That mode
     * writes each question from the gold memory's own text, so query and answer always
     * share vocabulary — while the failure this measures is precisely a question whose
     * words appear nowhere in the memory that answers it. The bridging relation lives in a
     * <em>different</em> row, and pairing the two is the whole job.
     *
     * <p><b>Pairs on rare shared content tokens, deliberately not on capitalisation.</b>
     * Retrieval-key generation gathers a memory's neighbours from
     * {@code JpaMemoryStore.entityNames}, a capitalisation rule; generating gold with that
     * same rule would select for pairs the keys already link and report the mechanism's own
     * heuristic back as a score. Rarity is independent of it, so a case survives or fails on
     * retrieval rather than on agreeing with the fix.
     */
    public static MemoryEvalSuite generateBridge(Agent agent, String suiteId, int maxCases,
                                                 QuestionWriter writer) {
        var rows = corpus(agent);
        var docFreq = docFrequencies(rows);

        var cases = new ArrayList<MemoryEvalCase>();
        var usedTargets = new HashSet<Long>();
        for (var relation : rows) {
            if (cases.size() >= maxCases) break;
            cases.addAll(bridgeCasesFor(relation, rows, docFreq, writer,
                    maxCases - cases.size(), usedTargets));
        }
        return new MemoryEvalSuite(suiteId,
                "Bridge suite: %d relation-phrased questions over %d memories of agent %s"
                        .formatted(cases.size(), rows.size(), agent.name),
                corpusFingerprint(rows), cases);
    }

    /** How many corpus rows each content token appears in — the rarity signal pairing uses. */
    private static Map<String, Integer> docFrequencies(List<Row> rows) {
        var docFreq = new HashMap<String, Integer>();
        for (var r : rows) {
            for (var t : MemorySimilarity.contentTokens(r.text())) docFreq.merge(t, 1, Integer::sum);
        }
        return docFreq;
    }

    /**
     * At most {@code limit} bridge cases pairing {@code relation} with targets it can bridge
     * to. Adds every target it uses to {@code usedTargets}, so no memory becomes the gold of
     * two cases.
     */
    private static List<MemoryEvalCase> bridgeCasesFor(Row relation, List<Row> rows,
            Map<String, Integer> docFreq, QuestionWriter writer, int limit, Set<Long> usedTargets) {
        var relationTokens = MemorySimilarity.contentTokens(relation.text());
        if (relationTokens.stream().noneMatch(RELATION_WORDS::contains)) return List.of();

        var out = new ArrayList<MemoryEvalCase>();
        for (var target : rows) {
            if (out.size() >= limit) break;
            if (target.id().equals(relation.id()) || usedTargets.contains(target.id())) continue;
            var question = bridgeQuestion(relation, relationTokens, target, docFreq, writer);
            if (question == null) continue;
            out.add(new MemoryEvalCase("bridge-" + target.id(), question,
                    MemoryEvalCase.SHAPE_BRIDGE, List.of(goldFor(target, rows))));
            usedTargets.add(target.id());
        }
        return out;
    }

    /** A bridge question over the pair, or null when the pair cannot carry one. */
    private static String bridgeQuestion(Row relation, Set<String> relationTokens, Row target,
            Map<String, Integer> docFreq, QuestionWriter writer) {
        var targetTokens = MemorySimilarity.contentTokens(target.text());
        // The target must NOT already carry the relation, or there is no gap to bridge
        // and the case would measure ordinary recall under a bridge case's name.
        if (targetTokens.stream().anyMatch(RELATION_WORDS::contains)) return null;
        if (relationTokens.stream().noneMatch(t ->
                targetTokens.contains(t) && docFreq.getOrDefault(t, 0) <= RARE_TOKEN_MAX_DOCS)) {
            return null;
        }

        String question;
        try {
            question = writer.write(List.of(
                    ChatMessage.system(BRIDGE_INSTRUCTIONS),
                    ChatMessage.user("RELATION: %s\nTARGET: %s"
                            .formatted(relation.text(), target.text())))).strip();
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY,
                    "Bridge question generation failed for memory %d: %s"
                            .formatted(target.id(), e.getMessage()));
            return null;
        }
        if (question.isBlank() || question.lines().count() > 1) return null;
        // The writer is told not to name the subject and mostly obeys; when it does
        // not, the query reaches the gold by name and the case is ordinary recall
        // wearing a bridge label. Measured 1 in 11 on a real corpus — rare enough to
        // miss by inspection, common enough to move a suite of this size.
        if (namesItsOwnGold(question, target.text())) return null;
        return question;
    }

    /**
     * Whether {@code question} names an entity the gold memory also names.
     *
     * <p>Uses the same entity-name rule that retrieval-key generation seeds on, which is
     * safe here in a way it would not be for pairing: this only ever <em>removes</em> cases,
     * and the ones it removes are the ones the keyword leg could already answer. It cannot
     * manufacture a case that entity-name linking happens to be good at.
     */
    static boolean namesItsOwnGold(String question, String goldText) {
        return memory.JpaMemoryStore.entityNames(goldText).stream().anyMatch(question::contains);
    }

    private static final String TEMPORAL_INSTRUCTIONS = """
            You write evaluation questions for a memory-retrieval system. You are given \
            several facts a user's assistant recorded during one stretch of time, and the \
            dates that stretch covers.

            Write the single question the user would ask to get that stretch back, phrased \
            through an explicit time reference — a month, a season, a year, "back in", \
            "around when". The time reference must be in the question. Do not quote the \
            facts, do not enumerate them, and do not name a specific day.

            Output only the question, on one line, with no preamble and no quotation marks.""";

    /** Rendered into the temporal prompt so the model has a real span to phrase against. */
    private static final DateTimeFormatter SPAN_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy")
                    .withZone(ZoneOffset.UTC);

    /**
     * Generate temporal cases: questions that reach a stretch of the corpus through an
     * explicit time reference (JCLAW-943).
     *
     * <p>The shape the other three modes structurally cannot produce. Each of them writes a
     * question from memory <em>text</em>, so time never enters the query unless a memory
     * happened to mention it; here the span itself is the handle, and the gold is whatever
     * was recorded inside it regardless of subject.
     *
     * <p>Consequently a temporal cluster is not a topic. Its members share only a period, so
     * a lexical or semantic ranker has no reason to return them together, and a low score
     * here is the expected starting point rather than a defect — that is what makes it a
     * usable baseline for the epic's temporal stories.
     */
    public static MemoryEvalSuite generateTemporal(Agent agent, String suiteId, int maxCases,
                                                   Clustering clustering, QuestionWriter writer) {
        var rows = corpus(agent).stream().filter(r -> r.createdAt() != null).toList();
        var cases = new ArrayList<MemoryEvalCase>();
        var used = new HashSet<Long>();
        for (var seed : rows) {
            if (cases.size() >= maxCases) break;
            if (used.contains(seed.id())) continue;
            var cluster = temporalCluster(seed, rows, clustering.threshold());
            var groups = distinctFacts(cluster);
            if (groups.size() < clustering.minFacts() || groups.size() > clustering.maxFacts()) continue;

            var last = cluster.stream().map(Row::createdAt).max(Instant::compareTo).orElse(seed.createdAt());
            String question;
            try {
                question = writer.write(List.of(
                        ChatMessage.system(TEMPORAL_INSTRUCTIONS),
                        ChatMessage.user("DATES: %s to %s\nFACTS:\n%s".formatted(
                                SPAN_FORMAT.format(seed.createdAt()), SPAN_FORMAT.format(last),
                                cluster.stream().map(Row::text).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b))))).strip();
            } catch (Exception e) {
                EventLogger.warn(EVENT_CATEGORY,
                        "Temporal question generation failed near memory %d: %s".formatted(seed.id(), e.getMessage()));
                continue;
            }
            if (question.isBlank() || question.lines().count() > 1) continue;

            cases.add(new MemoryEvalCase("temp-" + seed.id(), question,
                    MemoryEvalCase.SHAPE_TEMPORAL, groups));
            cluster.forEach(r -> used.add(r.id()));
        }
        return new MemoryEvalSuite(suiteId,
                "Temporal suite: %d spans of %.0f day(s) over %d memories of agent %s"
                        .formatted(cases.size(), clustering.threshold(), rows.size(), agent.name),
                corpusFingerprint(rows), cases);
    }

    private static final String MULTIHOP_INSTRUCTIONS = """
            You write evaluation questions for a memory-retrieval system. You are given two \
            groups of facts about a user. The groups are connected: something named in the \
            FIRST group is what the SECOND group is about.

            Write the single question that needs facts from BOTH groups to answer — one that \
            cannot be answered from either group on its own. Refer to the connecting subject \
            the way the FIRST group describes it, not by the name the SECOND group uses. Do \
            not quote either group and do not enumerate the facts.

            Output only the question, on one line, with no preamble and no quotation marks.""";

    /**
     * Generate multi-hop cases: two clusters chained through a shared entity, with the
     * memories of both marked gold (JCLAW-943).
     *
     * <p>Distinct from {@code bridge}, which is one relation memory and one target memory.
     * Here each end is a whole cluster, so answering needs facts from both sides rather than
     * one hop to a single row — and both sides count toward coverage, which is what makes a
     * partial traversal visible as a partial score instead of a pass.
     *
     * <p>Pairs on a rare shared content token, for the reason {@link #generateBridge}
     * documents at length: {@code entityNames} is what retrieval-key generation seeds on, so
     * pairing with it would select for links the mechanism already makes and report its own
     * heuristic back as a score.
     */
    public static MemoryEvalSuite generateMultiHop(Agent agent, String suiteId, int maxCases,
                                                   Clustering clustering, QuestionWriter writer) {
        var rows = corpus(agent);
        var docFreq = docFrequencies(rows);
        var clusters = new ArrayList<List<Row>>();
        var seen = new HashSet<Long>();
        for (var seed : rows) {
            if (seen.contains(seed.id())) continue;
            var cluster = clusterAround(agent, seed, rows, clustering);
            cluster.forEach(r -> seen.add(r.id()));
            if (cluster.size() >= 2) clusters.add(cluster);
        }

        var cases = new ArrayList<MemoryEvalCase>();
        var usedClusters = new HashSet<Integer>();
        for (int i = 0; i < clusters.size() && cases.size() < maxCases; i++) {
            if (usedClusters.contains(i)) continue;
            for (int j = i + 1; j < clusters.size() && cases.size() < maxCases; j++) {
                if (usedClusters.contains(j)) continue;
                var link = sharedRareToken(clusters.get(i), clusters.get(j), docFreq);
                if (link == null) continue;
                var question = multiHopQuestion(clusters.get(i), clusters.get(j), writer);
                if (question == null) continue;
                var groups = new ArrayList<>(distinctFacts(clusters.get(i)));
                groups.addAll(distinctFacts(clusters.get(j)));
                cases.add(new MemoryEvalCase("hop-%d-%d".formatted(
                        clusters.get(i).getFirst().id(), clusters.get(j).getFirst().id()),
                        question, MemoryEvalCase.SHAPE_MULTIHOP, groups));
                usedClusters.add(i);
                usedClusters.add(j);
                break;
            }
        }
        return new MemoryEvalSuite(suiteId,
                "Multi-hop suite: %d chained cluster pairs over %d memories of agent %s"
                        .formatted(cases.size(), rows.size(), agent.name),
                corpusFingerprint(rows), cases);
    }

    /** A content token both clusters carry that is rare enough corpus-wide to be an entity. */
    private static String sharedRareToken(List<Row> a, List<Row> b, Map<String, Integer> docFreq) {
        var aTokens = new HashSet<String>();
        a.forEach(r -> aTokens.addAll(MemorySimilarity.contentTokens(r.text())));
        var bTokens = new HashSet<String>();
        b.forEach(r -> bTokens.addAll(MemorySimilarity.contentTokens(r.text())));
        return aTokens.stream()
                .filter(bTokens::contains)
                .filter(t -> docFreq.getOrDefault(t, 0) <= RARE_TOKEN_MAX_DOCS)
                .min(Comparator.comparingInt(t -> docFreq.getOrDefault(t, 0)))
                .orElse(null);
    }

    private static String multiHopQuestion(List<Row> first, List<Row> second, QuestionWriter writer) {
        String question;
        try {
            question = writer.write(List.of(
                    ChatMessage.system(MULTIHOP_INSTRUCTIONS),
                    ChatMessage.user("FIRST GROUP:\n%s\n\nSECOND GROUP:\n%s".formatted(
                            first.stream().map(Row::text).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b),
                            second.stream().map(Row::text).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b))))).strip();
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY, "Multi-hop question generation failed: " + e.getMessage());
            return null;
        }
        return question.isBlank() || question.lines().count() > 1 ? null : question;
    }

    /**
     * Distinct-fact counts for the clusters {@code threshold} would produce, without
     * writing any questions.
     *
     * <p>The threshold is the one number in coverage generation that has to be measured
     * rather than picked: too high and every cluster is a set of paraphrases that
     * collapses to a single fact, too low and unrelated memories are declared part of one
     * question. Sweeping it through the full generator would spend a model call per
     * surviving cluster per sweep point.
     */
    public static List<Integer> clusterSizes(Agent agent, Clustering clustering) {
        var rows = corpus(agent);
        var sizes = new ArrayList<Integer>();
        var used = new HashSet<Long>();
        for (var seed : rows) {
            if (used.contains(seed.id())) continue;
            var cluster = clusterAround(agent, seed, rows, clustering);
            sizes.add(distinctFacts(cluster).size());
            cluster.forEach(r -> used.add(r.id()));
        }
        return sizes;
    }

    /** Memories related to the seed, by whichever signal {@code clustering} names. */
    private static List<Row> clusterAround(Agent agent, Row seed, List<Row> all, Clustering clustering) {
        if (clustering.temporal()) return temporalCluster(seed, all, clustering.threshold());
        return clustering.semantic()
                ? semanticCluster(agent, seed, all, clustering.threshold())
                : lexicalCluster(seed, all, clustering.threshold());
    }

    /**
     * Memories written within {@code spanDays} of the seed (JCLAW-943).
     *
     * <p>Groups on {@code createdAt} — when the memory was written — which is the only time
     * axis the store actually has. It is not when the fact became true, so a temporal case
     * asks "what was I dealing with around then", not "what was true in March". Reading it
     * as the latter would credit retrieval for a distinction the corpus cannot make.
     */
    private static List<Row> temporalCluster(Row seed, List<Row> all, double spanDays) {
        var span = Duration.ofMinutes((long) (spanDays * 24 * 60));
        var cluster = new ArrayList<Row>();
        cluster.add(seed);
        for (var other : all) {
            if (other.id().equals(seed.id()) || other.createdAt() == null) continue;
            if (Duration.between(seed.createdAt(), other.createdAt()).abs().compareTo(span) <= 0) {
                cluster.add(other);
            }
        }
        return cluster;
    }

    private static List<Row> lexicalCluster(Row seed, List<Row> all, double threshold) {
        var seedTokens = MemorySimilarity.contentTokens(seed.text());
        var cluster = new ArrayList<Row>();
        cluster.add(seed);
        for (var other : all) {
            if (other.id().equals(seed.id())) continue;
            if (MemorySimilarity.jaccard(seedTokens, MemorySimilarity.contentTokens(other.text())) >= threshold) {
                cluster.add(other);
            }
        }
        return cluster;
    }

    /** Embedding neighbours of the seed, restricted to the corpus rows already in hand. */
    private static List<Row> semanticCluster(Agent agent, Row seed, List<Row> all, double minCosine) {
        var byId = all.stream().collect(Collectors.toMap(Row::id, r -> r, (a, b) -> a));
        // Seed embedded as a document, matching what the index holds (JCLAW-529). Bare text
        // against statement+key vectors compares a format difference rather than a
        // similarity, which shrinks every cluster and quietly weakens the coverage suites
        // built from them — the same asymmetry that stopped capture-time dedup firing.
        var ids = MemoryStoreFactory.get().semanticNeighbours(
                String.valueOf(agent.id), seed.text(), seed.retrievalKey(),
                MAX_SEMANTIC_NEIGHBOURS, minCosine);
        var cluster = new ArrayList<Row>();
        cluster.add(seed);
        for (var id : ids) {
            var row = byId.get(id);
            if (row != null && !id.equals(seed.id())) cluster.add(row);
        }
        return cluster;
    }

    /**
     * Collapse a cluster into distinct facts: each group holds one fact and the
     * paraphrases restating it, so covering a fact once is enough and a corpus that
     * repeats itself does not inflate the target.
     */
    private static List<List<Long>> distinctFacts(List<Row> cluster) {
        var groups = new ArrayList<List<Row>>();
        for (var row : cluster) {
            var tokens = MemorySimilarity.Tokens.of(row.text());
            var match = groups.stream().filter(g -> MemorySimilarity.isDuplicate(
                    tokens, MemorySimilarity.Tokens.of(g.getFirst().text()), 0.85, 0.82, 0.5)).findFirst();
            if (match.isPresent()) {
                match.get().add(row);
            } else {
                var g = new ArrayList<Row>();
                g.add(row);
                groups.add(g);
            }
        }
        return groups.stream().map(g -> g.stream().map(Row::id).toList()).toList();
    }

    /**
     * Every memory that answers the question as well as the source does: the source plus
     * any near-duplicate of it.
     *
     * <p>Without this the harness would score a correct retrieval as a miss whenever the
     * corpus holds a fact more than once, penalising exactly what dedup is for. Uses the
     * same duplicate test capture uses, so what counts as the same fact here is what
     * counts as the same fact there.
     */
    private static List<Long> goldFor(Row source, List<Row> all) {
        var gold = new ArrayList<Long>();
        gold.add(source.id());
        var sourceTokens = MemorySimilarity.Tokens.of(source.text());
        for (var other : all) {
            if (other.id().equals(source.id())) continue;
            if (MemorySimilarity.isDuplicate(sourceTokens, MemorySimilarity.Tokens.of(other.text()),
                    0.85, 0.82, 0.5)) {
                gold.add(other.id());
            }
        }
        return gold;
    }

    /**
     * Identifies the corpus a suite was built from, so a report can say whether it is
     * still describing the same store. Ids and count only — never the texts, which must
     * not travel in an artifact any further than they already do.
     */
    private static String corpusFingerprint(List<Row> rows) {
        long sum = 0;
        for (var r : rows) sum = sum * 31 + r.id();
        return "%d:%08x".formatted(rows.size(), sum & 0xFFFFFFFFL);
    }

    /** Production question writer: the agent's own model, which is local on this install. */
    public static QuestionWriter writerFor(Agent agent) {
        var provider = ProviderRegistry.get(agent.modelProvider);
        if (provider == null) return null;
        return msgs -> SessionCompactor.firstChoiceText(
                provider.chat(agent.modelId, msgs, List.of(), 120, null, null));
    }
}
