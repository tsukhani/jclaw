package controllers;

import com.google.gson.JsonObject;
import memory.MemoryAutoCapture;
import models.Agent;
import play.db.jpa.NoTransaction;
import play.mvc.Before;
import play.mvc.Controller;
import services.Tx;
import services.evals.EvalCapture;
import services.evals.EvalDatasetLoader;
import services.evals.EvalRunner;
import services.evals.EvalSuite;
import services.evals.MemoryEvalPaths;
import utils.ApiResponses;

import java.nio.file.Path;
import java.util.List;

import static utils.GsonHolder.GSON;

/**
 * Drives an eval suite against a live agent and returns the recorded run
 * (JCLAW-883). The offline half — validating the dataset and scoring a recording —
 * stays in {@code ./jclaw.sh evals}, which boots no framework; this endpoint exists
 * because driving an agent needs what that CLI deliberately does without: JPA, a
 * configured provider, and the tool registry.
 *
 * <p>Gated by {@link LoadtestAuthCheck} — loopback origin plus {@code X-Loadtest-Auth}
 * carrying {@code application.secret}. Same trust boundary and same reason as the
 * loadtest endpoints: an operator-run measurement harness on the local host, with no
 * plaintext admin credential to log in with. Sharing the gate rather than inventing a
 * second one keeps the number of ways into the harness surface at one.
 *
 * <p>The response body IS the recorded-run file. Redirect it to disk and score it with
 * {@code ./jclaw.sh evals --responses}; keeping capture and scoring as two steps means
 * a sweep can be re-scored against a changed suite without paying the model again.
 */
public class ApiEvalsController extends Controller {

    /** Where the suites live, relative to the app root. */
    private static final String SUITE_DIR = "evals/suites";

    /**
     * Untracked suites, for content that cannot be committed — a benchmark-derived suite
     * would republish the dataset through the public mirror (JCLAW-942).
     *
     * <p>A subdirectory of {@link MemoryEvalPaths#LOCAL_DIR} rather than the directory
     * itself: that one already holds generated MEMORY-eval suites, whose
     * {@code corpusFingerprint} key this loader rejects outright. Two suite formats in one
     * directory means whichever loader runs second fails on the other's files.
     */
    private static final String LOCAL_SUITE_DIR = MemoryEvalPaths.LOCAL_DIR + "/suites";

    /**
     * Upper bound on the fan-out an operator can request. The ceiling exists so a
     * sweep cannot be turned into a load test against a shared provider by a typo
     * in the request body.
     */
    private static final int MAX_CONCURRENCY = 16;

    @Before
    static void requireLoadtestAuth() {
        LoadtestAuthCheck.checkLoadtestAuth();
    }

    /**
     * {@code POST /api/evals/capture} with
     * {@code {"suite": "<id>", "agent": "<name>", "concurrency": <n>?}}.
     *
     * <p>Both {@code suite} and {@code agent} are required. Defaulting the agent —
     * to the main one, or to whatever is first in the table — would let a sweep run
     * against the operator's working agent by omission, which is exactly the accident
     * worth designing out.
     */
    public static void capture() {
        var body = JsonBodyReader.readJsonBody();
        var suiteId = JsonBodyReader.requiredOr400(body, "suite");
        var agentName = JsonBodyReader.requiredOr400(body, "agent");

        var agent = Agent.findByName(agentName);
        if (agent == null && Agent.EVALTEST_AGENT_NAME.equalsIgnoreCase(agentName)) {
            // Provision the eval agent on first use, like LoadTestRunner does for its
            // benchmark agents. It lands with an empty tool surface, so the first sweep
            // against a fresh one fails its tool checks until the operator grants what
            // the suite needs — which is the safe direction to be wrong in.
            agent = EvalCapture.ensureEvalAgent();
            if (agent == null) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                        ("Cannot provision %s: no '%s' agent to copy a provider and model from. "
                                + "Create %s in the agent editor instead.")
                                .formatted(Agent.EVALTEST_AGENT_NAME, Agent.MAIN_AGENT_NAME,
                                        Agent.EVALTEST_AGENT_NAME));
                throw ApiResponses.unreachable();
            }
        }
        if (agent == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No agent named '%s'".formatted(agentName));
            throw ApiResponses.unreachable();
        }

        var suite = resolveSuite(suiteId, body != null && body.has("local")
                && body.get("local").getAsBoolean());
        int concurrency = Math.clamp(readInt(body, "concurrency", EvalRunner.DEFAULT_CONCURRENCY),
                1, MAX_CONCURRENCY);

        renderJSON(GSON.toJson(EvalCapture.run(suite, agent, concurrency)));
    }

    /** Bound on one ingest request, so a corpus load cannot become an unbounded write. */
    private static final int MAX_INGEST_PAIRS = 500;

    /** What one ingest run did. {@code perPairMs} is what tells a caller whether the
     *  rest of a corpus fits the time it has. */
    public record IngestResult(String agent, int pairs, int captured, int storedMemories,
                               long elapsedMs, long perPairMs) {}

    /**
     * {@code POST /api/evals/memory-ingest} with
     * {@code {"agent": "<name>", "pairs": [{"user": "...", "assistant": "..."}], "limit": <n>?}}.
     *
     * <p>Loads a benchmark corpus through the real capture pipeline (JCLAW-942), so what a
     * memory eval then scores is what capture would actually have stored — extractor,
     * content guards, dedup and the consolidation judge included. Writing rows straight to
     * the store would measure retrieval against a corpus the system never produces.
     *
     * <p><b>Sequential by construction.</b> {@link MemoryAutoCapture#capture} serializes per
     * agent and skips rather than queues when the lock is held, so a parallel fan-out would
     * silently drop most of the corpus as {@code capture_in_flight}. One pair at a time is
     * the only shape that ingests what was asked for; {@code perPairMs} comes back so the
     * caller can size the remainder rather than guess.
     *
     * <p>Same loopback + {@code X-Loadtest-Auth} gate as the rest of this controller. It
     * spends a model call per pair and writes to an agent's long-term memory, which is
     * exactly the pair of properties that gate exists for.
     *
     * <p><b>{@code @NoTransaction} is load-bearing.</b> Play wraps a request in a JPA
     * transaction and {@link Tx#run} joins an existing one rather than opening its own, so
     * without this every pair's writes would accumulate in a single request transaction
     * held open across every LLM round-trip in the batch — the exact invariant JCLAW-525,
     * JCLAW-807 and JCLAW-960 restructured the capture pipeline to preserve, and a pooled
     * connection pinned for minutes. Measured before the annotation: 50 pairs captured
     * according to the event log while the table showed none, because nothing had
     * committed yet. The cost is that the counts below need their own transaction — a bare
     * finder on a {@code @NoTransaction} path throws "No active EntityManager".
     */
    @NoTransaction
    public static void memoryIngest() {
        var body = JsonBodyReader.readJsonBody();
        var agentName = JsonBodyReader.requiredOr400(body, "agent");
        var agent = Tx.run(() -> Agent.findByName(agentName));
        if (agent == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No agent named '%s'".formatted(agentName));
            throw ApiResponses.unreachable();
        }
        if (body == null || !body.has("pairs") || !body.get("pairs").isJsonArray()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "pairs must be a JSON array");
            throw ApiResponses.unreachable();
        }
        var pairs = body.getAsJsonArray("pairs");
        int limit = Math.clamp(readInt(body, "limit", pairs.size()), 0, MAX_INGEST_PAIRS);

        long before = Tx.run(() -> models.Memory.count("agent.id = ?1", agent.id));
        long t0 = System.currentTimeMillis();
        int captured = 0;
        int n = Math.min(limit, pairs.size());
        for (int i = 0; i < n; i++) {
            var p = pairs.get(i).getAsJsonObject();
            var user = p.has("user") ? p.get("user").getAsString() : null;
            var assistant = p.has("assistant") ? p.get("assistant").getAsString() : null;
            captured += MemoryAutoCapture.captureSync(agent, user, assistant).captured();
        }
        long elapsed = System.currentTimeMillis() - t0;
        long after = Tx.run(() -> models.Memory.count("agent.id = ?1", agent.id));
        renderJSON(GSON.toJson(new IngestResult(agent.name, n, captured, (int) (after - before),
                elapsed, n == 0 ? 0 : elapsed / n)));
    }

    /**
     * Load the dataset and pick one suite by id. There is one file per suite, and the
     * capture records its content fingerprint, so the caller can always tell exactly
     * which content ran without naming a version up front.
     *
     * <p>{@code local} selects {@link #LOCAL_SUITE_DIR} instead of the tracked
     * suites (JCLAW-942). A suite derived from a licensed benchmark cannot live in
     * {@code evals/suites}: that directory is tracked and this repository is mirrored
     * publicly, so committing one would republish the dataset. A boolean rather than a
     * caller-supplied path — the two legal directories are both known here, so there is no
     * reason to accept a string that would then need traversal defences.
     */
    private static EvalSuite resolveSuite(String suiteId, boolean local) {
        var dir = local ? LOCAL_SUITE_DIR : SUITE_DIR;
        List<EvalSuite> suites;
        try {
            suites = EvalDatasetLoader.loadAll(Path.of(dir));
        } catch (RuntimeException e) {
            // Also the path a production distribution takes: evals/ is a developer
            // artifact and does not ship, so say that rather than 500 on a missing dir.
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "Cannot read the eval dataset at %s: %s".formatted(dir, e.getMessage()));
            return null;  // unreachable: error() throws
        }
        var match = suites.stream()
                .filter(s -> s.id().equals(suiteId))
                .findFirst()
                .orElse(null);
        if (match == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND,
                    "No suite '%s' in %s".formatted(suiteId, dir));
        }
        return match;
    }

    private static int readInt(JsonObject body, String key, int defaultValue) {
        if (body == null || !body.has(key) || body.get(key).isJsonNull()) return defaultValue;
        try {
            return body.get(key).getAsInt();
        } catch (RuntimeException _) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "'%s' must be a number".formatted(key));
            return defaultValue;  // unreachable: error() throws
        }
    }
}
