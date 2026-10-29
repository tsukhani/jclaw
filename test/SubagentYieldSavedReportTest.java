import agents.ToolContext;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.Tx;
import tools.SubagentYieldTool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * {@code subagent_yield} on a run whose result is no longer in memory (a restart, or a turn that collected it and
 * was then stopped) reads the report the run's row kept. Every row hangs off conversations this class creates.
 */
class SubagentYieldSavedReportTest extends UnitTest {

    private static final String NOTHING_TO_COLLECT = "{\"results\":[],\"count\":0,\"note\":\"Nothing to collect: "
            + "no async subagent spawned in this conversation or task is waiting to be collected. "
            + "Calling subagent_yield again will not change that.\"}";

    private static final String FINISHED_NOTE = "Nothing is waiting to be collected. The runs in finished have ended "
            + "and keep their reports: pass the ids of any whose report you have not seen as runIds to read it. "
            + "Calling subagent_yield with all=true again will not change that.";

    private Agent parent;
    private Agent child;
    private Conversation conv;
    private Conversation otherConv;
    private Conversation childConv;

    @BeforeEach
    void setup() {
        var tag = Long.toString(System.nanoTime());
        parent = AgentService.create("yield-saved-p-" + tag, "openrouter", "gpt-4.1");
        child = AgentService.create("yield-saved-c-" + tag, "openrouter", "gpt-4.1");
        conv = ConversationService.create(parent, "web", "u-" + tag);
        otherConv = ConversationService.create(parent, "web", "o-" + tag);
        childConv = ConversationService.create(child, "subagent", null);
    }

    @Test
    void collectingAFinishedRunAgainReturnsItsSavedReport() {
        long run = seedRun(conv, SubagentRun.Status.COMPLETED, "SAVED_REPORT");

        assertEquals("{\"results\":[{\"run_id\":\"" + run + "\",\"reply\":\"SAVED_REPORT\",\"status\":\"COMPLETED\"}],"
                + "\"count\":1}", yieldIn(conv, "{\"runIds\":[\"" + run + "\"]}"));
    }

    @Test
    void collectingAFailedRunAgainReturnsItsSavedError() {
        long run = seedRun(conv, SubagentRun.Status.FAILED, "provider down");

        assertEquals("{\"results\":[{\"run_id\":\"" + run + "\",\"reply\":\"\",\"status\":\"FAILED\","
                + "\"error\":\"provider down\"}],\"count\":1}", yieldIn(conv, "{\"runIds\":[\"" + run + "\"]}"));
    }

    @Test
    void aFinishedRunOfAnotherConversationIsNotReadable() {
        long run = seedRun(otherConv, SubagentRun.Status.COMPLETED, "NOT_THIS_CHATS");

        assertEquals(missing(run), yieldIn(conv, "{\"runIds\":[\"" + run + "\"]}"));
    }

    @Test
    void aRunStillRunningThatNothingHereAwaitsIsReportedMissing() {
        long run = seedRun(conv, SubagentRun.Status.RUNNING, null);

        assertEquals(missing(run), yieldIn(conv, "{\"runIds\":[\"" + run + "\"]}"));
    }

    @Test
    void allWithNothingWaitingNamesThisConversationsFinishedRunsNewestFirst() {
        long older = seedRun(conv, SubagentRun.Status.COMPLETED, "A");
        long newer = seedRun(conv, SubagentRun.Status.FAILED, "B");
        seedRun(conv, SubagentRun.Status.RUNNING, null);
        seedRun(otherConv, SubagentRun.Status.COMPLETED, "C");

        assertEquals("{\"results\":[],\"count\":0,\"finished\":["
                + "{\"run_id\":\"" + newer + "\",\"status\":\"FAILED\"},"
                + "{\"run_id\":\"" + older + "\",\"status\":\"COMPLETED\"}],"
                + "\"note\":\"" + FINISHED_NOTE + "\"}", yieldIn(conv, "{\"all\":true}"));
    }

    @Test
    void allWithNothingWaitingAndNothingFinishedSaysNothingToCollect() {
        seedRun(conv, SubagentRun.Status.RUNNING, null);

        assertEquals(NOTHING_TO_COLLECT, yieldIn(conv, "{\"all\":true}"));
    }

    @Test
    void allNamesTenFinishedRunsInFull() {
        var runs = seedFinished(10);

        assertEquals(runs.reversed(), namedFinished());
    }

    @Test
    void allLeavesOutTheOldestPastTen() {
        var runs = seedFinished(11);

        assertEquals(runs.subList(1, 11).reversed(), namedFinished());
    }

    private List<String> namedFinished() {
        var finished = JsonParser.parseString(yieldIn(conv, "{\"all\":true}")).getAsJsonObject().getAsJsonArray("finished");
        return finished.asList().stream().map(e -> e.getAsJsonObject().get("run_id").getAsString()).toList();
    }

    private List<String> seedFinished(int n) {
        var ids = new ArrayList<String>();
        IntStream.range(0, n).forEach(i -> ids.add(String.valueOf(seedRun(conv, SubagentRun.Status.COMPLETED, "R" + i))));
        return ids;
    }

    private String yieldIn(Conversation scope, String args) {
        return ToolContext.withConversation(scope.id, () -> new SubagentYieldTool().execute(args, parent));
    }

    private static String missing(long run) {
        return "{\"results\":[{\"run_id\":\"" + run + "\",\"status\":\"UNKNOWN\","
                + "\"error\":\"no pending async subagent (already collected, or not an async spawn)\"}],\"count\":1}";
    }

    private long seedRun(Conversation parentConv, SubagentRun.Status status, String outcome) {
        return Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = parent;
            run.childAgent = child;
            run.parentConversation = parentConv;
            run.childConversation = childConv;
            run.startedAt = Instant.now();
            run.status = status;
            if (status != SubagentRun.Status.RUNNING) run.endedAt = Instant.now();
            run.outcome = outcome;
            run.save();
            return run.id;
        });
    }
}
