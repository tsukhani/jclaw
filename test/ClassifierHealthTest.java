import llm.routing.ClassifierHealth;
import llm.routing.ClassifierHealth.Alert;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EventLogger;
import services.NotificationBus;
import utils.AppClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

class ClassifierHealthTest extends UnitTest {

    private static final Instant FIRST = Instant.parse("2026-11-13T05:22:26Z");
    private static final String GONE = "HTTP 410 from ollama-cloud: {\"error\":\"model was retired\"}";

    /** Each test's own classifier, so concurrent classes never share a run. */
    private final String classifier = "ch-" + UUID.randomUUID().toString().substring(0, 8) + "/m";

    private final List<Map<String, Object>> published = new CopyOnWriteArrayList<>();

    private void recordingEvents(Runnable body) {
        var off = NotificationBus.subscribeEvents((type, data) -> {
            if (ClassifierHealth.BUS_EVENT.equals(type) && classifier.equals(data.get("classifier"))) published.add(data);
        });
        try {
            body.run();
        } finally {
            off.run();
        }
    }

    /** Runs {@code body} on a thread with no transaction, as a settings write that has committed reaches it. */
    private static void outsideTheHarnessTransaction(Runnable body) {
        var t = Thread.ofPlatform().start(body);
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private List<EventLogger.Captured> routerEvents(Runnable body) {
        return EventLogger.captureForTest(body).stream()
                .filter(e -> e.category().equals("router") && e.message().contains(classifier))
                .toList();
    }

    @Test
    void twoFailuresAreNoAlertAndTheThirdRaisesItWithTheRunsStartAndNewestReason() {
        recordingEvents(() -> {
            AppClock.runWith(Clock.fixed(FIRST, ZoneOffset.UTC), () -> ClassifierHealth.failed(classifier, "first"));
            ClassifierHealth.failed(classifier, "second");
            assertNull(ClassifierHealth.alertFor(classifier));
            assertEquals(List.of(), published, "no alert, so nothing to tell the clients");

            ClassifierHealth.failed(classifier, GONE);
            var expected = new Alert(classifier, 3, FIRST.toString(), GONE);
            assertEquals(expected, ClassifierHealth.alertFor(classifier));
            assertEquals(List.of(Map.of("classifier", classifier, "alert", expected)), published);
        });
    }

    @Test
    void theThirdFailureLogsOneErrorAndLaterFailuresOnlyUpdateTheAlert() {
        ClassifierHealth.failed(classifier, "first");
        ClassifierHealth.failed(classifier, "second");
        var logged = routerEvents(() -> recordingEvents(() -> {
            ClassifierHealth.failed(classifier, GONE);
            ClassifierHealth.failed(classifier, "fourth");
        }));
        assertEquals(List.of(new EventLogger.Captured("ERROR", "router", null, null,
                "Classifier " + classifier + " has failed on 3 turns in a row, so the keyword rules are routing: " + GONE,
                null)), logged);
        assertEquals(4, ClassifierHealth.alertFor(classifier).consecutiveFailures());
        assertEquals("fourth", ClassifierHealth.alertFor(classifier).lastError());
        assertEquals(2, published.size(), "the clients hear each failure while the alert stands");
    }

    @Test
    void aSuccessBeforeTheThirdFailureStartsTheCountAgain() {
        ClassifierHealth.failed(classifier, "first");
        ClassifierHealth.failed(classifier, "second");
        ClassifierHealth.succeeded(classifier);
        ClassifierHealth.failed(classifier, "third");
        ClassifierHealth.failed(classifier, "fourth");
        assertNull(ClassifierHealth.alertFor(classifier));
        ClassifierHealth.failed(classifier, "fifth");
        assertEquals(3, ClassifierHealth.alertFor(classifier).consecutiveFailures());
    }

    @Test
    void aSuccessAfterTheAlertClearsItAndTellsTheClients() {
        for (var i = 0; i < 3; i++) ClassifierHealth.failed(classifier, GONE);
        var logged = routerEvents(() -> recordingEvents(() -> ClassifierHealth.succeeded(classifier)));
        assertNull(ClassifierHealth.alertFor(classifier));
        assertEquals(List.of(Map.of("classifier", classifier)), published);
        assertEquals(List.of(new EventLogger.Captured("INFO", "router", null, null,
                "Classifier " + classifier + " answered again after 3 failed turns", null)), logged);
    }

    @Test
    void aSuccessWithNoAlertStandingTellsNobody() {
        ClassifierHealth.failed(classifier, "first");
        var logged = routerEvents(() -> recordingEvents(() -> ClassifierHealth.succeeded(classifier)));
        assertEquals(List.of(), published);
        assertEquals(List.of(), logged);
    }

    @Test
    void oneClassifiersFailuresNeverCountTowardAnother() {
        var other = classifier + "-other";
        ClassifierHealth.failed(classifier, "a");
        ClassifierHealth.failed(other, "b");
        ClassifierHealth.failed(classifier, "c");
        ClassifierHealth.failed(other, "d");
        assertNull(ClassifierHealth.alertFor(classifier));
        assertNull(ClassifierHealth.alertFor(other));
    }

    @Test
    void publishingTheCurrentClassifierSendsItsAlertOrNone() {
        recordingEvents(() -> {
            outsideTheHarnessTransaction(() -> ClassifierHealth.publishCurrent(classifier));
            assertEquals(List.of(Map.of("classifier", classifier)), published, "no alert stands yet");
            for (var i = 0; i < 3; i++) ClassifierHealth.failed(classifier, GONE);
            published.clear();
            outsideTheHarnessTransaction(() -> ClassifierHealth.publishCurrent(classifier));
        });
        assertEquals(List.of(Map.of("classifier", classifier, "alert", ClassifierHealth.alertFor(classifier))), published);
        assertNull(ClassifierHealth.alertFor(null));
    }

    @Test
    void aSettingsWriteStillOpenAnnouncesNothingUntilItCommits() {
        recordingEvents(() -> ClassifierHealth.publishCurrent(classifier));
        assertEquals(List.of(), published, "the harness's own transaction has not committed");
    }

    @Test
    void theReasonKeepsOneLineMasksACredentialAndIsCutAtItsLimit() {
        ClassifierHealth.failed(classifier, "upstream\n  said:\tno");
        ClassifierHealth.failed(classifier, null);
        ClassifierHealth.failed(classifier, "x".repeat(400));
        assertEquals("x".repeat(300) + "…", ClassifierHealth.alertFor(classifier).lastError());
        ClassifierHealth.failed(classifier, "x".repeat(300));
        assertEquals("x".repeat(300), ClassifierHealth.alertFor(classifier).lastError(), "exactly at the limit is kept whole");
        ClassifierHealth.failed(classifier, "   ");
        assertEquals("no reason given", ClassifierHealth.alertFor(classifier).lastError());
        ClassifierHealth.failed(classifier, "upstream\n  said:\tno");
        assertEquals("upstream said: no", ClassifierHealth.alertFor(classifier).lastError());
        ClassifierHealth.failed(classifier, "refused api_key=sk-live-0123456789abcdef");
        assertEquals("refused api_key=" + utils.SecretRedactor.MASK, ClassifierHealth.alertFor(classifier).lastError());
    }
}
