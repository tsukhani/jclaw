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
import services.decision.OllamaDecision;
import services.graphspike.CandidateGenerator;
import services.graphspike.CandidateGenerator.Candidate;
import services.graphspike.ExtractionPipeline;
import services.graphspike.ExtractionPipeline.CaseRun;
import services.graphspike.ExtractionPipeline.Decider;
import utils.HttpFactories;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** JCLAW-1344, JCLAW-1356: the two-stage decision flow over generated candidates. */
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

    private static CaseRun run(String text, List<Candidate> candidates, Decider decider) {
        return ExtractionPipeline.run(SCHEMA, "c", text, candidates, "tev1", decider);
    }

    private static List<Candidate> spans(String... spans) {
        return Arrays.stream(spans).map(Candidate::of).toList();
    }

    @Test
    void theOperatorIsWrittenAsAPersonWithoutATypingQuestion() {
        var text = "The user works at Harborlight Analytics.";
        var run = run(text, CandidateGenerator.generate(text), scripted(Map.of(
                "Harborlight Analytics", "Organization",
                "The user -> Harborlight Analytics", "works_at"), Map.of()));

        assertEquals(2, requests.size());
        var typing = requests.getFirst().getAsJsonObject("questions");
        assertEquals(Set.of("m0"), typing.keySet(), "only the non-operator candidate is typed");
        assertFalse(typing.toString().contains("The user"), typing.toString());

        var operator = run.decisions().getFirst();
        assertTrue(operator.operator());
        assertEquals("Person", operator.choice());
        assertEquals(1.0, operator.confidence(), 1e-9);
        var works = run.decisions().getLast();
        assertEquals(ExtractionPipeline.RELATION, works.stage());
        assertEquals("The user", works.from());
        assertEquals("works_at", works.choice());
        assertTrue(works.writes(0.9));
    }

    @Test
    void anImplicitOperatorIsAskedAboutAsTheUser() {
        var text = "Works at Harborlight Analytics.";
        var candidates = CandidateGenerator.generate(text);
        assertTrue(candidates.getFirst().implicit(), candidates.toString());
        var run = run(text, candidates, scripted(Map.of(
                "Harborlight Analytics", "Organization",
                "the user -> Harborlight Analytics", "works_at"), Map.of()));
        assertEquals("works_at", run.decisions().getLast().choice());
        assertEquals("the user", run.decisions().getLast().from());
    }

    @Test
    void everyAllowedOrderedPairIsAskedAndOnlyAllowedRelationsOffered() {
        var run = run(TEXT, spans("Dana Reyes", "Harborlight Analytics"), scripted(Map.of(
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
        assertEquals(1, run.prunedPairs(), "Organization -> Person has no allowed relation");
        assertEquals(3, run.decisions().stream().filter(d -> d.writes(0.5)).count());
    }

    @Test
    void aPairWithNoAllowedRelationIsPrunedUnasked() {
        var text = "Felix Amari loves jazz in Lake Verrin.";
        var run = run(text, spans("Lake Verrin", "jazz"),
                scripted(Map.of("Lake Verrin", "Place", "jazz", "Topic"), Map.of()));
        assertEquals(2, run.prunedPairs(), "both directions");
        assertEquals(1, requests.size(), "the term request only");
    }

    @Test
    void aRejectedCandidateIsNeverRelatedAndALowConfidenceIsNotWritten() {
        var run = run(TEXT, spans("Dana Reyes", "Harborlight Analytics"), scripted(Map.of(
                "Dana Reyes", "Person",
                "Harborlight Analytics", ExtractionPipeline.NOT_AN_ENTITY), Map.of("Dana Reyes", 0.4)));
        assertEquals(1, requests.size(), "one typed term leaves no pair");
        var dana = run.decisions().getFirst();
        assertFalse(dana.writes(0.5));
        assertTrue(dana.writes(0.4));
        assertFalse(run.decisions().getLast().writes(0.0), "not_an_entity is never written");
    }

    @Test
    void aDecisionErrorFailsEveryQuestionInItsRequest() {
        Decider refusing = _ -> {
            throw new JevException("Ollama returned HTTP 400");
        };
        var run = run(TEXT, spans("Dana Reyes", "Harborlight Analytics"), refusing);
        assertEquals(2, run.decisions().size());
        run.decisions().forEach(d -> {
            assertTrue(d.failed());
            assertEquals("Ollama returned HTTP 400", d.failure());
        });

        Decider broken = _ -> {
            throw new IllegalStateException("secret detail");
        };
        var other = run(TEXT, spans("Dana Reyes"), broken).decisions().getFirst();
        assertEquals("IllegalStateException", other.failure(), "only the type, never the message");
    }

    @Test
    void anInvalidAnswerIsAFailure() {
        Decider missing = _ -> new JsonObject();
        assertEquals(JevApi.INVALID, run(TEXT, spans("Dana Reyes"), missing).decisions().getFirst().failure());

        Decider unknownChoice = request -> {
            var answers = new JsonObject();
            answers.add("m0", answer(Set.of("Spaceship", "Person"), "Spaceship", 0.9));
            var response = new JsonObject();
            response.add("answers", answers);
            return response;
        };
        var d = run(TEXT, spans("Dana Reyes"), unknownChoice).decisions().getFirst();
        assertTrue(d.failed());
        assertEquals(JevApi.INVALID, d.failure());
        assertNull(d.choice());
    }

    @Test
    void theOllamaDeciderSendsKeepAliveAndPinsAfterATimeout() throws Exception {
        var base = "http://192.168.1.20:11434";
        JevBreakerTestSync.acquire();
        try {
            var sent = new CopyOnWriteArrayList<Request>();
            var bodies = new CopyOnWriteArrayList<JsonObject>();
            var hang = new AtomicBoolean();
            Interceptor ollama = chain -> {
                sent.add(chain.request());
                var path = chain.request().url().encodedPath();
                var json = "{\"done\":true}";
                if (path.equals("/api/ps")) {
                    json = "{\"models\":[{\"name\":\"tev1:latest\"}]}";
                } else if (!path.equals("/api/generate")) {
                    var buffer = new Buffer();
                    chain.request().body().writeTo(buffer);
                    var body = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
                    bodies.add(body);
                    if (hang.get()) {
                        try {
                            Thread.sleep(1_300);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    var answers = new JsonObject();
                    for (var q : body.getAsJsonObject("questions").entrySet()) {
                        answers.add(q.getKey(), answer(q.getValue().getAsJsonObject().getAsJsonObject("criteria").keySet(),
                                "Person", 0.8));
                    }
                    var result = new JsonObject();
                    result.add("answers", answers);
                    json = result.toString();
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                        .message("canned").body(ResponseBody.create(json, MediaType.get("application/json"))).build();
            };
            var client = new OkHttpClient.Builder().addInterceptor(ollama).build();

            var answered = HttpFactories.callWith(client, () -> run(TEXT, spans("Dana Reyes"),
                    Decider.ollama(base, "tev1", 5_000)));
            assertEquals("Person", answered.decisions().getFirst().choice());
            assertTrue(sent.getFirst().url().toString().startsWith(base + "/"), sent.getFirst().url().toString());
            assertEquals("tev1", bodies.getFirst().get("model").getAsString());
            assertTrue(bodies.getFirst().has("keep_alive"), bodies.getFirst().toString());
            assertTrue(sent.stream().noneMatch(r -> r.url().encodedPath().equals("/api/generate")), "no pin while it answers");

            hang.set(true);
            var timedOut = HttpFactories.callWith(client, () -> run(TEXT, spans("Dana Reyes"),
                    Decider.ollama(base, "tev1", 1_000)));
            var failed = timedOut.decisions().getFirst();
            assertTrue(failed.failed());
            assertTrue(failed.failure().contains("did not answer"), failed.failure());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (sent.stream().noneMatch(r -> r.url().encodedPath().equals("/api/generate"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(sent.stream().anyMatch(r -> r.url().encodedPath().equals("/api/generate")), "the timeout pinned tev1");
            // Let the pin finish so it cannot answer for another class's pin of the same model.
            HttpFactories.callWith(client, () -> OllamaDecision.pin(base, "tev1"))
                    .get(10, TimeUnit.SECONDS);
        } finally {
            JevBreakerTestSync.release();
        }
    }
}
