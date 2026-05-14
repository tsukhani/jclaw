import models.Agent;
import models.Conversation;
import models.EventLog;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import services.SubagentRegistry;
import services.Tx;
import slash.Commands;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JCLAW-271: per-subcommand coverage for {@code /subagent list/info/log/kill}.
 *
 * <p>Each test seeds a SubagentRun row directly (no need to drive the
 * full spawn tool — these tests exercise the slash command, not the
 * spawn pipeline) and asserts the response text + side effects on the
 * audit row, event log, and registry.
 */
class SubagentSlashCommandsTest extends UnitTest {

    private Agent parentAgent;
    private Agent childAgent;
    private Conversation parentConv;
    private Conversation childConv;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        SubagentRegistry.clear();
        parentAgent = AgentService.create("p-slash", "openrouter", "gpt-4.1");
        childAgent = AgentService.create("c-slash", "openrouter", "gpt-4.1");
        parentConv = ConversationService.create(parentAgent, "web", "u");
        childConv = ConversationService.create(childAgent, "subagent", null);
    }

    @AfterEach
    void teardown() {
        SubagentRegistry.clear();
        EventLogger.clear();
    }

    private SubagentRun seedRun(SubagentRun.Status status) {
        return Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = parentAgent;
            run.childAgent = childAgent;
            run.parentConversation = parentConv;
            run.childConversation = childConv;
            run.status = status;
            if (status != SubagentRun.Status.RUNNING) {
                run.endedAt = Instant.now();
                run.outcome = "test outcome for " + status.name().toLowerCase();
            }
            run.save();
            return run;
        });
    }

    // ── parse ──────────────────────────────────────────────────────────

    @Test
    void parseRecognizesSubagent() {
        assertEquals(Commands.Command.SUBAGENT, Commands.parse("/subagent").orElseThrow());
        assertEquals(Commands.Command.SUBAGENT, Commands.parse("/SUBAGENT list").orElseThrow());
    }

    // ── /subagent list ────────────────────────────────────────────────

    @Test
    void listReturnsRunningAndRecentRunsScopedToCurrentConversation() {
        var running = seedRun(SubagentRun.Status.RUNNING);
        var completed = seedRun(SubagentRun.Status.COMPLETED);

        // Another parent conversation with its own RUNNING row — must NOT
        // appear in the current conversation's list.
        var otherConv = ConversationService.create(parentAgent, "web", "other");
        Tx.run(() -> {
            var stray = new SubagentRun();
            stray.parentAgent = parentAgent;
            stray.childAgent = childAgent;
            stray.parentConversation = otherConv;
            stray.childConversation = childConv;
            stray.status = SubagentRun.Status.RUNNING;
            stray.save();
            return stray.id;
        });

        var result = Commands.handle("/subagent list", parentAgent, "web", "u", parentConv).orElseThrow();

        var text = result.responseText();
        assertTrue(text.contains("#" + running.id), "running run id present: " + text);
        assertTrue(text.contains("#" + completed.id), "terminal run id present: " + text);
        assertTrue(text.contains("RUNNING"), "RUNNING status word present: " + text);
        assertTrue(text.contains("COMPLETED"), "COMPLETED status word present: " + text);
        // RUNNING should sort before COMPLETED.
        assertTrue(text.indexOf("RUNNING") < text.indexOf("COMPLETED"),
                "RUNNING must list before terminal rows: " + text);
        // Stray run from other conversation must not appear by id.
        assertFalse(text.contains("u-other"),
                "list must not mention other-conversation rows: " + text);
    }

    @Test
    void listOnEmptyConversationReturnsFriendlyMessage() {
        var result = Commands.handle("/subagent list", parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("No subagent runs"),
                "expected friendly empty-state, got: " + result.responseText());
    }

    @Test
    void listWithoutConversationReturnsErrorMessage() {
        var result = Commands.handle("/subagent list", parentAgent, "web", "u", null).orElseThrow();
        assertTrue(result.responseText().contains("requires a parent conversation"),
                "null conversation must surface an explanatory message, got: " + result.responseText());
    }

    @Test
    void noArgsDefaultsToList() {
        seedRun(SubagentRun.Status.RUNNING);
        var withArg = Commands.handle("/subagent list",
                parentAgent, "web", "u", parentConv).orElseThrow().responseText();
        var noArg = Commands.handle("/subagent",
                parentAgent, "web", "u", parentConv).orElseThrow().responseText();
        // Both render the same list (timestamps may differ in pathological
        // sub-second races — strip them out before compare).
        assertEquals(stripTimestamps(withArg), stripTimestamps(noArg));
    }

    // ── /subagent info ────────────────────────────────────────────────

    @Test
    void infoReturnsAllRecordedMetadataForGivenId() {
        var run = seedRun(SubagentRun.Status.COMPLETED);
        EventLogger.recordSubagentSpawn(parentAgent.name, childAgent.name,
                String.valueOf(run.id), "session", "fresh");
        EventLogger.flush();

        var result = Commands.handle("/subagent info " + run.id,
                parentAgent, "web", "u", parentConv).orElseThrow();
        var text = result.responseText();
        assertTrue(text.contains("Subagent run #" + run.id), "id heading: " + text);
        assertTrue(text.contains("Parent agent: " + parentAgent.name));
        assertTrue(text.contains("Child agent: " + childAgent.name));
        assertTrue(text.contains("Parent conversation: " + parentConv.id));
        assertTrue(text.contains("Child conversation: " + childConv.id));
        assertTrue(text.contains("Status: COMPLETED"));
        assertTrue(text.contains("Mode: session"), "mode from event payload: " + text);
        assertTrue(text.contains("Context: fresh"), "context from event payload: " + text);
        assertTrue(text.contains("Outcome: test outcome"), "outcome from row: " + text);
    }

    @Test
    void infoOnUnknownIdReturnsNotFound() {
        var result = Commands.handle("/subagent info 999999",
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("not found"),
                "missing-id 404: " + result.responseText());
    }

    @Test
    void infoWithoutIdReturnsUsageHint() {
        var result = Commands.handle("/subagent info",
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("Missing run id"),
                "usage hint: " + result.responseText());
    }

    // ── /subagent log ─────────────────────────────────────────────────

    @Test
    void logReturnsEventLogRowsMatchingRunId() {
        var run = seedRun(SubagentRun.Status.COMPLETED);
        // Three lifecycle events for this run.
        EventLogger.recordSubagentSpawn(parentAgent.name, childAgent.name,
                String.valueOf(run.id), "session", "fresh");
        EventLogger.recordSubagentComplete(parentAgent.name, childAgent.name,
                String.valueOf(run.id), "session", "fresh", "ok");
        EventLogger.flush();
        // Plus an unrelated event for a different run id.
        EventLogger.recordSubagentSpawn(parentAgent.name, childAgent.name,
                "9999", "session", "fresh");
        EventLogger.flush();

        var result = Commands.handle("/subagent log " + run.id,
                parentAgent, "web", "u", parentConv).orElseThrow();
        var text = result.responseText();
        assertTrue(text.contains("Events for subagent run #" + run.id),
                "heading: " + text);
        assertTrue(text.contains("SUBAGENT_SPAWN"), "spawn event: " + text);
        assertTrue(text.contains("SUBAGENT_COMPLETE"), "complete event: " + text);
        // Unrelated id 9999 must not appear in the output.
        assertFalse(text.contains("9999"),
                "log must filter out events from other run ids, got: " + text);
    }

    @Test
    void logOnUnknownIdReturnsNotFound() {
        var result = Commands.handle("/subagent log 12345",
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("not found"),
                "404 path: " + result.responseText());
    }

    // ── /subagent kill ────────────────────────────────────────────────

    @Test
    void killTransitionsRunningRowToKilledAndEmitsEvent() throws Exception {
        var run = seedRun(SubagentRun.Status.RUNNING);

        // Synthesize an in-flight VT so the kill primitive has a real
        // thread to interrupt. The VT body registers itself with the
        // registry via registerWithThread (mirroring the production
        // spawn-tool path) and parks in a loop until interrupted.
        var cancelLatch = new CountDownLatch(1);
        var cancelled = new AtomicBoolean(false);
        var stopFlag = new AtomicBoolean(false);
        var fut = new java.util.concurrent.CompletableFuture<Void>();
        var started = new CountDownLatch(1);
        Thread.ofVirtual().name("test-fake-runner-" + run.id).start(() -> {
            SubagentRegistry.registerWithThread(run.id, fut, Thread.currentThread());
            started.countDown();
            try {
                while (!stopFlag.get() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException _) {
                cancelled.set(true);
                cancelLatch.countDown();
                Thread.currentThread().interrupt();
            }
            fut.complete(null);
        });
        // Wait for the VT to register before driving the kill — otherwise
        // a near-instant kill would find an empty slot and skip the
        // thread interrupt.
        started.await(2, TimeUnit.SECONDS);

        var result = Commands.handle("/subagent kill " + run.id,
                parentAgent, "web", "u", parentConv).orElseThrow();
        var text = result.responseText();
        assertTrue(text.contains("killed"), "kill confirmation: " + text);

        // Audit row flipped to KILLED with our reason recorded.
        JPA.em().clear();
        var fresh = (SubagentRun) SubagentRun.findById(run.id);
        assertEquals(SubagentRun.Status.KILLED, fresh.status,
                "kill must flip status to KILLED");
        assertNotNull(fresh.endedAt, "kill must stamp endedAt");
        assertNotNull(fresh.outcome, "kill must record an outcome");
        assertTrue(fresh.outcome.contains("Killed by operator"),
                "outcome includes operator marker: " + fresh.outcome);

        // SUBAGENT_KILL event emitted.
        EventLogger.flush();
        var killEvents = EventLog.find(
                "category = ?1 ORDER BY timestamp DESC",
                EventLogger.SUBAGENT_KILL).fetch();
        assertEquals(1, killEvents.size(),
                "exactly one SUBAGENT_KILL event after kill");

        // Fake future was cancelled. The VT body sets `cancelled` when its
        // sleep is interrupted; wait briefly for the cancel signal to land.
        var observed = cancelLatch.await(2, TimeUnit.SECONDS);
        assertTrue(observed && cancelled.get(),
                "registered Future must observe the interrupt within 2s");

        stopFlag.set(true);
    }

    @Test
    void killOnTerminalRunIsIdempotent() {
        var run = seedRun(SubagentRun.Status.COMPLETED);

        var result = Commands.handle("/subagent kill " + run.id,
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("already completed"),
                "idempotent terminal-state message: " + result.responseText());

        JPA.em().clear();
        var fresh = (SubagentRun) SubagentRun.findById(run.id);
        assertEquals(SubagentRun.Status.COMPLETED, fresh.status,
                "kill on terminal run must not flip status");

        // No SUBAGENT_KILL event for the no-op.
        EventLogger.flush();
        var killEvents = EventLog.find(
                "category = ?1", EventLogger.SUBAGENT_KILL).fetch();
        assertTrue(killEvents.isEmpty(),
                "no SUBAGENT_KILL event when kill was a no-op");
    }

    @Test
    void killOnUnknownIdReturnsNotFound() {
        var result = Commands.handle("/subagent kill 9999999",
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("not found"),
                "missing-id message: " + result.responseText());
    }

    @Test
    void killWithoutIdReturnsUsageHint() {
        var result = Commands.handle("/subagent kill",
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("Missing run id"),
                "usage hint: " + result.responseText());
    }

    // ── /subagent history ─────────────────────────────────────────────

    @Test
    void historyReturnsFormattedTranscriptForOwnedRun() {
        var run = seedRun(SubagentRun.Status.COMPLETED);
        // Seed three messages on the child conversation in chronological order.
        Tx.run(() -> {
            var fresh = (models.Conversation) models.Conversation.findById(childConv.id);
            var m1 = new models.Message();
            m1.conversation = fresh;
            m1.role = "user";
            m1.content = "what is 2+2?";
            m1.save();
            var m2 = new models.Message();
            m2.conversation = fresh;
            m2.role = "assistant";
            m2.content = "4";
            m2.save();
            var m3 = new models.Message();
            m3.conversation = fresh;
            m3.role = "user";
            m3.content = "thanks";
            m3.save();
        });

        var result = Commands.handle("/subagent history " + run.id,
                parentAgent, "web", "u", parentConv).orElseThrow();
        var text = result.responseText();
        assertTrue(text.contains("Transcript for subagent run #" + run.id),
                "heading: " + text);
        assertTrue(text.contains("user:"), "role-prefixed lines present: " + text);
        assertTrue(text.contains("assistant:"), "assistant line present: " + text);
        assertTrue(text.contains("what is 2+2?"), "content present: " + text);
        assertTrue(text.contains("assistant: 4"), "assistant reply present: " + text);
        assertTrue(text.contains("thanks"), "third message present: " + text);
        // Chronological order — user before assistant before thanks.
        assertTrue(text.indexOf("what is 2+2?") < text.indexOf("assistant: 4"),
                "messages must appear in chronological order: " + text);
        assertTrue(text.indexOf("assistant: 4") < text.indexOf("thanks"),
                "third message comes last: " + text);
    }

    @Test
    void historyRefusesUnownedRun() {
        var run = seedRun(SubagentRun.Status.COMPLETED);
        Tx.run(() -> {
            var fresh = (models.Conversation) models.Conversation.findById(childConv.id);
            var msg = new models.Message();
            msg.conversation = fresh;
            msg.role = "user";
            msg.content = "secret content";
            msg.save();
        });

        var stranger = services.AgentService.create("stranger", "openrouter", "gpt-4.1");
        var result = Commands.handle("/subagent history " + run.id,
                stranger, "web", "u", parentConv).orElseThrow();
        var text = result.responseText();
        assertTrue(text.contains("not owned by the calling agent"),
                "permission rejection: " + text);
        assertFalse(text.contains("secret content"),
                "rejection must not leak child-message content: " + text);
    }

    @Test
    void historyOnUnknownIdReturnsNotFound() {
        var result = Commands.handle("/subagent history 99999",
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("not found"),
                "missing-id 404: " + result.responseText());
    }

    @Test
    void historyWithoutIdReturnsUsageHint() {
        var result = Commands.handle("/subagent history",
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("Missing run id"),
                "usage hint: " + result.responseText());
    }

    // ── argument parsing ──────────────────────────────────────────────

    @Test
    void unknownSubcommandReturnsHelpfulError() {
        var result = Commands.handle("/subagent frobnicate",
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("Unknown subcommand"),
                "unknown sub: " + result.responseText());
        assertTrue(result.responseText().contains("list, info"),
                "lists available subcommands: " + result.responseText());
        assertTrue(result.responseText().contains("history"),
                "history sub appears in available list: " + result.responseText());
    }

    @Test
    void nonNumericIdReturnsParseError() {
        var result = Commands.handle("/subagent info xyz",
                parentAgent, "web", "u", parentConv).orElseThrow();
        assertTrue(result.responseText().contains("Invalid run id"),
                "parse error: " + result.responseText());
    }

    // ── helpers ───────────────────────────────────────────────────────

    /** Strip ISO-8601-ish timestamp substrings so two list responses generated
     *  microseconds apart still compare equal. */
    private static String stripTimestamps(String s) {
        return s.replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[^ ]*", "<ts>");
    }
}
