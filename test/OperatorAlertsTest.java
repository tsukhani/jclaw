import models.EventLog;
import models.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.OperatorAlerts;
import services.Tx;
import utils.AppClock;
import utils.CircuitBreaker.Reason;
import utils.CircuitBreaker.State;
import utils.CircuitBreaker.Stats;
import utils.CircuitBreaker.Transition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * JCLAW-1279: what reaches the operator's alert channel when unattended work fails, and what does
 * not. The breaker decisions are driven directly, each test on a breaker name of its own, so none
 * of them needs the process-wide destination key set.
 */
class OperatorAlertsTest extends UnitTest {

    private static final String SUBJECT = "LLM provider 'flaky'";
    private static final Instant T0 = Instant.parse("2026-09-22T03:00:00Z");

    @BeforeEach
    void setup() {
        EventLogger.flush();
        Fixtures.deleteDatabase();
    }

    @Test
    void anOutageIsAnnouncedOnceAndItsRecoveryOnce() {
        var name = breaker();

        var opened = OperatorAlerts.breakerAlert(name, SUBJECT, trip());
        assertNotNull(opened);
        assertTrue(opened.contains(SUBJECT) && opened.contains("CONSECUTIVE_FAILURES")
                && opened.contains("3 of the last 3 calls failed"), opened);
        assertNull(OperatorAlerts.breakerAlert(name, SUBJECT, move(State.OPEN, State.HALF_OPEN, Reason.COOLDOWN_ELAPSED)));
        assertNull(OperatorAlerts.breakerAlert(name, SUBJECT, move(State.HALF_OPEN, State.OPEN, Reason.PROBE_FAILED)),
                "a failed probe continues the outage already announced");

        var recovered = OperatorAlerts.breakerAlert(name, SUBJECT, recover());
        assertNotNull(recovered);
        assertTrue(recovered.contains(SUBJECT) && recovered.contains("recovered"), recovered);
    }

    @Test
    void aBreakerTheOperatorIsolatesIsNeitherAnnouncedNorItsRestore() {
        var name = breaker();

        assertNull(OperatorAlerts.breakerAlert(name, SUBJECT, move(State.CLOSED, State.OPEN, Reason.MANUAL_TRIP)));
        assertNull(OperatorAlerts.breakerAlert(name, SUBJECT, move(State.HALF_OPEN, State.OPEN, Reason.PROBE_FAILED)),
                "still the outage the operator started");
        assertNull(OperatorAlerts.breakerAlert(name, SUBJECT, move(State.OPEN, State.CLOSED, Reason.MANUAL_RESET)));
    }

    @Test
    void aBreakerThatKeepsReopeningIsAnnouncedAtMostOncePerWindow() {
        var name = breaker();
        var window = OperatorAlerts.REPEAT_WINDOW;

        assertNotNull(at(T0, name, trip()));
        assertNotNull(at(T0.plusSeconds(60), name, recover()));

        assertNull(at(T0.plusSeconds(120), name, trip()), "reopened inside the window");
        assertNull(at(T0.plusSeconds(180), name, recover()), "an outage that was not announced has no recovery to announce");

        assertNull(at(T0.plusSeconds(240), name, trip()));
        assertNotNull(at(T0.plus(window).plusSeconds(1), name, move(State.HALF_OPEN, State.OPEN, Reason.PROBE_FAILED)),
                "an outage still running when the window ends is announced then");
        assertNotNull(at(T0.plus(window).plusSeconds(60), name, recover()));
    }

    @Test
    void withNoDestinationABreakerTransitionIsNeitherRecordedNorSent() {
        var name = breaker();

        OperatorAlerts.onBreakerTransition(name, SUBJECT, trip());

        assertNull(OperatorAlerts.breakerAlert(name, SUBJECT, recover()),
                "no outage was recorded, so there is no recovery to announce");
    }

    @Test
    void aFailedOccurrenceNamesTheTaskTheErrorAndWhenItRunsAgain() {
        var next = ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, ZoneId.of("UTC"));

        var text = OperatorAlerts.occurrenceAlert("Morning briefing", "main", "retries exhausted", "read timed out", next);

        assertEquals("JClaw alert: task 'Morning briefing' (agent main) failed (retries exhausted): read timed out. "
                + "It runs again at 2026-09-23 09:00 UTC.", text);
        var truncated = OperatorAlerts.occurrenceAlert("t", null, "permanent error", "x".repeat(5000), next);
        assertTrue(truncated.length() < 400, "a long error is cut short, got " + truncated.length() + " chars");
    }

    @Test
    void theDestinationMustBeAChannelWithATarget() {
        assertNull(OperatorAlerts.rejectionFor("telegram:123456"));
        assertNull(OperatorAlerts.rejectionFor("slack:C0123"));
        assertNull(OperatorAlerts.rejectionFor(""), "blank turns alerts off");
        assertNotNull(OperatorAlerts.rejectionFor("telegram"));
        assertNotNull(OperatorAlerts.rejectionFor("telegram:"));
        assertNotNull(OperatorAlerts.rejectionFor("pigeon:42"));
        assertNotNull(OperatorAlerts.rejectionFor("tool:send_gmail_message"));

        assertNotNull(ConfigService.setWithSideEffects(OperatorAlerts.KEY, "pigeon:42"));
        assertNull(ConfigService.get(OperatorAlerts.KEY), "a refused destination is not stored");
    }

    @Test
    void anAlertReachesTheChosenConversation() {
        var agent = Tx.run(() -> AgentService.create("alerts-web-" + System.nanoTime(), "test-provider", "test-model"));
        var conversation = Tx.run(() -> ConversationService.create(agent, "web", "admin"));

        var result = OperatorAlerts.deliver(agent, "web:" + conversation.id, "JClaw alert: test");

        assertTrue(result.ok(), result.reason());
        var contents = Tx.run(() -> Message.find("conversation = ?1", conversation).fetch()).stream()
                .map(m -> ((Message) m).content).toList();
        assertEquals(List.of("JClaw alert: test"), contents);
    }

    @Test
    void anUndeliverableAlertIsLoggedAndNotThrown() {
        var agent = Tx.run(() -> AgentService.create("alerts-unbound-" + System.nanoTime(), "test-provider", "test-model"));
        var spec = "telegram:" + System.nanoTime();

        var result = OperatorAlerts.deliver(agent, spec, "JClaw alert: test");

        assertFalse(result.ok(), "the agent has no Telegram binding");
        EventLogger.flush();
        var logged = Tx.run(() -> EventLog.find("category = ?1", OperatorAlerts.CATEGORY).fetch()).stream()
                .filter(e -> ((EventLog) e).message.contains(spec)).count();
        assertEquals(1, logged);
    }

    private static String breaker() {
        return "alerts-test-" + System.nanoTime();
    }

    private static Transition trip() {
        return new Transition(State.CLOSED, State.OPEN, Reason.CONSECUTIVE_FAILURES,
                new Stats(State.OPEN, 3, 3, 0, Reason.CONSECUTIVE_FAILURES));
    }

    private static Transition recover() {
        return move(State.HALF_OPEN, State.CLOSED, Reason.PROBE_SUCCEEDED);
    }

    private static Transition move(State from, State to, Reason reason) {
        return new Transition(from, to, reason, new Stats(to, 0, 0, 0, reason));
    }

    private static String at(Instant when, String name, Transition transition) {
        return AppClock.callWith(Clock.fixed(when, ZoneOffset.UTC), () -> OperatorAlerts.breakerAlert(name, SUBJECT, transition));
    }
}
