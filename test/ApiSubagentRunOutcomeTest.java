import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.ApiSubagentRunsController;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import models.Task;
import models.TaskRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.Tx;
import tools.JClawApiTool;
import utils.AppClock;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A list row carries an excerpt of a run's outcome, and {@code GET /api/subagent-runs/{id}} returns it whole (JF-140).
 * The agent-scoped half of the single read is {@code AgentScopedAccessTest}'s.
 *
 * <p>No {@code Fixtures.deleteDatabase()}: every seeded row hangs off a parent agent of this class's own, and every
 * list read filters to that agent, so a sibling's rows never reach an assertion.
 */
class ApiSubagentRunOutcomeTest extends FunctionalTest {

    private static final int BOUND = ApiSubagentRunsController.OUTCOME_EXCERPT_CHARS;

    @BeforeEach
    void login() {
        AuthFixture.seedAdminPassword("changeme");
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    @Test
    void anOutcomeAtTheBoundIsListedWhole() {
        var outcome = "a".repeat(BOUND);
        var seeded = seedRuns(1, SubagentRun.Status.FAILED, outcome);

        var row = onlyRowOf(seeded.parentAgentId());

        assertEquals(outcome, row.get("outcome").getAsString());
        assertFalse(row.get("outcomeTruncated").getAsBoolean());
    }

    @Test
    void anOutcomeOneCharacterOverTheBoundIsCutAndMarked() {
        var seeded = seedRuns(1, SubagentRun.Status.FAILED, "a".repeat(BOUND) + "b");

        var row = onlyRowOf(seeded.parentAgentId());

        assertEquals("a".repeat(BOUND) + "…", row.get("outcome").getAsString());
        assertTrue(row.get("outcomeTruncated").getAsBoolean());
    }

    @Test
    void aRunStillRunningListsNoOutcomeAndNoCut() {
        var seeded = seedRuns(1, SubagentRun.Status.RUNNING, null);

        var row = onlyRowOf(seeded.parentAgentId());

        assertTrue(row.get("outcome").isJsonNull(), row.toString());
        assertFalse(row.get("outcomeTruncated").getAsBoolean());
    }

    @Test
    void theRunsOwnReadReturnsItsWholeOutcome() {
        var outcome = "The harness exited 3.\n" + "x".repeat(BOUND * 50);
        var seeded = seedRuns(1, SubagentRun.Status.FAILED, outcome);
        var runId = seeded.runIds()[0];

        var resp = GET("/api/subagent-runs/" + runId);

        assertStatus(200, resp);
        var run = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals(runId, run.get("id").getAsLong());
        assertEquals(seeded.parentAgentId(), run.get("parentAgentId").getAsLong());
        assertEquals("FAILED", run.get("status").getAsString());
        assertEquals(outcome, run.get("outcome").getAsString());
        assertFalse(run.get("outcomeTruncated").getAsBoolean());
    }

    @Test
    void aRunThatDoesNotExistIs404() {
        var resp = GET("/api/subagent-runs/" + Long.MAX_VALUE);

        assertStatus(404, resp);
        var body = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals("not_found", body.get("code").getAsString());
        assertEquals("Run " + Long.MAX_VALUE + " not found.", body.get("message").getAsString());
    }

    @Test
    void aDefaultPageOfRunsWithEveryFieldAtItsLongestFitsUnderTheJclawApiCap() {
        int seededCount = ApiSubagentRunsController.LIST_DEFAULT_LIMIT + 5;
        var seeded = seedRuns(seededCount, SubagentRun.Status.FAILED, "e".repeat(10_000));

        var resp = GET("/api/subagent-runs?parentAgentId=" + seeded.parentAgentId());

        assertStatus(200, resp);
        var body = getContent(resp);
        var rows = JsonParser.parseString(body).getAsJsonArray();
        assertEquals(ApiSubagentRunsController.LIST_DEFAULT_LIMIT, rows.size());
        assertEquals(String.valueOf(seededCount), resp.getHeader("X-Total-Count"));
        // Every row was long and cut, so the page measured is the one the excerpt exists for.
        for (var row : rows) {
            assertEquals(BOUND + 1, row.getAsJsonObject().get("outcome").getAsString().length());
            assertTrue(row.getAsJsonObject().get("outcomeTruncated").getAsBoolean());
        }
        assertTrue(body.length() < JClawApiTool.MAX_RESPONSE_CHARS,
                "a default page ran to " + body.length() + " characters");
    }

    // --- seam ---------------------------------------------------------------------------------

    private record Seeded(long parentAgentId, long[] runIds) {}

    private static JsonObject onlyRowOf(long parentAgentId) {
        var resp = GET("/api/subagent-runs?parentAgentId=" + parentAgentId);
        assertStatus(200, resp);
        JsonArray rows = JsonParser.parseString(getContent(resp)).getAsJsonArray();
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0).getAsJsonObject();
    }

    /**
     * {@code count} runs a task fire started, every text field at its longest: agent names at the API's 64-character
     * limit (a child's adds the spawn's suffix), the task name and label at 255 and the folder at 512, the columns'
     * lengths.
     */
    private static Seeded seedRuns(int count, SubagentRun.Status status, String outcome) {
        return commit(() -> {
            var parent = agent(padded("jf140-p-", 64));
            var child = agent(parent.name + "-sub-" + padded("", 12));
            var childConversation = new Conversation();
            childConversation.agent = child;
            childConversation.channelType = "web";
            childConversation.save();
            var taskRun = taskRun(parent, padded("jf140-task-", 255));
            var ids = new long[count];
            for (int i = 0; i < count; i++) {
                var run = new SubagentRun();
                run.parentAgent = parent;
                run.childAgent = child;
                run.parentTaskRun = taskRun;
                run.childConversation = childConversation;
                run.status = status;
                run.startedAt = AppClock.now();
                if (status != SubagentRun.Status.RUNNING) run.endedAt = AppClock.now();
                run.label = padded("jf140-label-", 255);
                run.outcome = outcome;
                run.workdir = "/" + padded("jf140-folder-", 511);
                run.runtime = "acp";
                run.save();
                ids[i] = run.id;
            }
            return new Seeded(parent.id, ids);
        });
    }

    /** {@code prefix}, a token unique to this call, and padding to {@code length} characters. */
    private static String padded(String prefix, int length) {
        var name = prefix + UUID.randomUUID().toString().replace("-", "");
        return name.length() >= length ? name.substring(0, length) : name + "z".repeat(length - name.length());
    }

    private static Agent agent(String name) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private static TaskRun taskRun(Agent agent, String taskName) {
        var task = new Task();
        task.agent = agent;
        task.name = taskName;
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.COMPLETED;
        task.nextRunAt = AppClock.now();
        task.save();
        var run = new TaskRun();
        run.task = task;
        run.startedAt = AppClock.now();
        run.completedAt = AppClock.now();
        run.status = TaskRun.Status.COMPLETED;
        run.save();
        return run;
    }

    /** Commit off-thread: the test body is already in a transaction this request would not see. */
    private static <T> T commit(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
