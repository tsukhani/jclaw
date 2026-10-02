import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import memory.ontology.OntologySchema;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.decision.JevApi;
import services.decision.JevException;
import services.graphspike.ExtractionPipeline;
import services.graphspike.ExtractionPipeline.CaseRun;
import services.graphspike.ExtractionPipeline.Decider;
import services.graphspike.ExtractionPipeline.Decision;
import services.graphspike.MentionProposer;
import services.graphspike.MentionProposer.Proposal;
import utils.HttpFactories;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/** JCLAW-1344: proposer parsing and the two-stage decision flow, against the spec's I/O matrix. */
class ExtractionPipelineTest extends UnitTest {

    private static final String TEXT = "Dana Reyes works at Harborlight Analytics.";
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final OntologySchema SCHEMA = OntologySchema.seed();

    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();

    /** A valid answer: {@code p} on {@code choice}, the rest spread evenly over the other options. */
    private static JsonObject answer(Set<String> ids, String choice, double p) {
        var probabilities = new JsonObject();
        ids.forEach(id -> probabilities.addProperty(id, id.equals(choice) ? p : (1 - p) / (ids.size() - 1)));
        var answer = new JsonObject();
        answer.addProperty("choice", choice);
        answer.addProperty("confidence", p);
        answer.add("probabilities", probabilities);
        return answer;
    }

    /**
     * Answers each question from {@code choices}, keyed by the mention or by {@code from -> to}, read back from the
     * question's rules; an unlisted relation is {@code none} and an unlisted mention {@code not_an_entity}.
     */
    private Decider scripted(Map<String, String> choices, Map<String, Double> confidence) {
        return request -> {
            requests.add(request.deepCopy());
            var answers = new JsonObject();
            for (var q : request.getAsJsonObject("questions").entrySet()) {
                var question = q.getValue().getAsJsonObject();
                var m = QUOTED.matcher(question.getAsJsonObject("instructions").get("rules").getAsString());
                m.find();
                var key = m.group(1);
                var fallback = ExtractionPipeline.NOT_AN_ENTITY;
                if (q.getKey().startsWith("p")) {
                    m.find();
                    key = key + " -> " + m.group(1);
                    fallback = ExtractionPipeline.NONE;
                }
                var ids = question.getAsJsonObject("criteria").keySet();
                answers.add(q.getKey(), answer(ids, choices.getOrDefault(key, fallback), confidence.getOrDefault(key, 0.9)));
            }
            var response = new JsonObject();
            response.add("answers", answers);
            return response;
        };
    }

    private static Proposal proposal(String... mentions) {
        return new Proposal(List.of(mentions), 0, null);
    }

    private static CaseRun run(String text, Proposal proposal, Decider decider) {
        return ExtractionPipeline.run(SCHEMA, "c", text, proposal, "tev1", decider, 0.5);
    }

    @Test
    void aNonVerbatimMentionIsDiscardedAndCountedAndNeverAsked() {
        var proposal = MentionProposer.parseReply(TEXT, "{\"mentions\":[\"Dan Reyes\",\"Dana Reyes\",\"Dana Reyes\"]}");
        assertNull(proposal.failure());
        assertEquals(List.of("Dana Reyes"), proposal.mentions());
        assertEquals(1, proposal.discarded());

        var run = run(TEXT, proposal, scripted(Map.of("Dana Reyes", "Person"), Map.of()));
        assertEquals(1, run.discardedMentions());
        assertEquals(1, requests.size(), "one term request, and no pair to relate");
        var questions = requests.getFirst().getAsJsonObject("questions");
        assertEquals(Set.of("m0"), questions.keySet());
        assertTrue(questions.toString().contains("Dana Reyes"));
        assertFalse(questions.toString().contains("Dan Reyes\\\""));
    }

    @Test
    void aFencedReplyParses() {
        var proposal = MentionProposer.parseReply(TEXT, "```json\n{\"mentions\":[\"Dana Reyes\"]}\n```");
        assertEquals(List.of("Dana Reyes"), proposal.mentions());
    }

    @Test
    void aMalformedProposerReplyIsAFailureAndAsksNothing() {
        for (var reply : new String[] {"not json", "", "[\"Dana Reyes\"]", "{\"names\":[]}", "{\"mentions\":[1]}"}) {
            var proposal = MentionProposer.parseReply(TEXT, reply);
            assertNotNull(proposal.failure(), "a failure, never an empty list: " + reply);
            var run = run(TEXT, proposal, scripted(Map.of(), Map.of()));
            assertEquals(proposal.failure(), run.proposerFailure());
            assertEquals(List.of(), run.decisions());
        }
        assertEquals(List.of(), requests, "no decision asked");
        assertNull(MentionProposer.parseReply(TEXT, "{\"mentions\":[]}").failure(), "an empty list is an answer");
    }

    @Test
    void bothStagesAskOneRequestEachAndOfferOnlyAllowedRelations() {
        var run = run(TEXT, proposal("Dana Reyes", "Harborlight Analytics"), scripted(Map.of(
                "Dana Reyes", "Person",
                "Harborlight Analytics", "Organization",
                "Dana Reyes -> Harborlight Analytics", "works_at"), Map.of()));
        assertEquals(2, requests.size());
        var relation = requests.getLast();
        assertEquals("tev1", relation.get("model").getAsString());
        assertEquals(TEXT, relation.getAsJsonObject("state").get("memory").getAsString());
        var options = relation.getAsJsonObject("questions").getAsJsonObject("p0").getAsJsonObject("criteria").keySet();
        assertTrue(options.contains("works_at") && options.contains(ExtractionPipeline.NONE), options.toString());
        assertFalse(options.contains("family_of"), options.toString());

        var written = run.decisions().stream().filter(d -> d.outcome().equals(ExtractionPipeline.WRITTEN)).toList();
        assertEquals(3, written.size());
        var works = written.getLast();
        assertEquals(ExtractionPipeline.RELATION, works.stage());
        assertEquals("works_at", works.choice());
        assertEquals(0.9, works.confidence(), 1e-9);
    }

    @Test
    void aPairWithNoAllowedRelationIsPrunedUnasked() {
        var text = "Felix Amari loves jazz in Lake Verrin.";
        var run = run(text, proposal("Lake Verrin", "jazz"),
                scripted(Map.of("Lake Verrin", "Place", "jazz", "Topic"), Map.of()));
        assertEquals(2, run.prunedPairs(), "both directions");
        assertEquals(1, requests.size(), "the term request only");
    }

    @Test
    void aRelationOnAnUnsureEndpointIsAskedButNotWritten() {
        var run = run(TEXT, proposal("Dana Reyes", "Harborlight Analytics"), scripted(Map.of(
                "Dana Reyes", "Person",
                "Harborlight Analytics", "Organization",
                "Dana Reyes -> Harborlight Analytics", "works_at"), Map.of("Harborlight Analytics", 0.4)));
        var outcomes = run.decisions().stream().map(Decision::outcome).toList();
        // Organization -> Person has no allowed relation, so only one pair is asked.
        assertEquals(List.of(ExtractionPipeline.WRITTEN, ExtractionPipeline.ABSTAINED,
                ExtractionPipeline.ENDPOINT_ABSTAINED), outcomes);
        assertEquals(1, run.prunedPairs());
    }

    @Test
    void aDecisionErrorFailsEveryQuestionInItsRequest() {
        Decider refusing = _ -> {
            throw new JevException("Jev returned HTTP 400");
        };
        var run = run(TEXT, proposal("Dana Reyes", "Harborlight Analytics"), refusing);
        assertEquals(2, run.decisions().size());
        run.decisions().forEach(d -> {
            assertTrue(d.failed());
            assertEquals("Jev returned HTTP 400", d.detail());
        });

        Decider broken = _ -> {
            throw new IllegalStateException("secret detail");
        };
        var other = run(TEXT, proposal("Dana Reyes"), broken).decisions().getFirst();
        assertEquals("IllegalStateException", other.detail(), "only the type, never the message");
    }

    @Test
    void anInvalidAnswerIsAFailure() {
        Decider missing = _ -> new JsonObject();
        assertEquals(JevApi.INVALID, run(TEXT, proposal("Dana Reyes"), missing).decisions().getFirst().detail());

        Decider unknownChoice = request -> {
            var answers = new JsonObject();
            answers.add("m0", answer(Set.of("Spaceship", "Person"), "Spaceship", 0.9));
            var response = new JsonObject();
            response.add("answers", answers);
            return response;
        };
        var d = run(TEXT, proposal("Dana Reyes"), unknownChoice).decisions().getFirst();
        assertTrue(d.failed());
        assertEquals(JevApi.INVALID, d.detail());
        assertNull(d.choice());
    }

    @Test
    void theJevDeciderSpeaksTheRealWireThroughJevApi() {
        JevBreakerTestSync.acquire();
        try {
            var sent = new CopyOnWriteArrayList<Request>();
            var bodies = new CopyOnWriteArrayList<JsonObject>();
            Interceptor jev = chain -> {
                var buffer = new Buffer();
                chain.request().body().writeTo(buffer);
                sent.add(chain.request());
                var body = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
                bodies.add(body);
                var answers = new JsonObject();
                for (var q : body.getAsJsonObject("questions").entrySet()) {
                    var ids = q.getValue().getAsJsonObject().getAsJsonObject("criteria").keySet();
                    answers.add(q.getKey(), answer(ids, q.getKey().startsWith("m") ? "Person" : "none", 0.8));
                }
                var result = new JsonObject();
                result.add("answers", answers);
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                        .message("canned").body(ResponseBody.create(result.toString(),
                                MediaType.get("application/json"))).build();
            };
            var client = new OkHttpClient.Builder().addInterceptor(jev).build();
            var run = HttpFactories.callWith(client, () -> run(TEXT, proposal("Dana Reyes"),
                    Decider.jev("graphspike-test-key", 5_000)));

            assertEquals(1, sent.size());
            assertEquals("Bearer graphspike-test-key", sent.getFirst().header("Authorization"));
            var question = bodies.getFirst().getAsJsonObject("questions").getAsJsonObject("m0");
            assertEquals("choice", question.get("type").getAsString());
            assertEquals(SCHEMA.termTypes().size() + 1, question.getAsJsonObject("criteria").size());
            var decision = run.decisions().getFirst();
            assertEquals(ExtractionPipeline.WRITTEN, decision.outcome());
            assertEquals("Person", decision.choice());
        } finally {
            JevBreakerTestSync.release();
        }
    }
}
