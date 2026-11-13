package llm.routing;

import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.NotificationBus;
import utils.AppClock;
import utils.SecretRedactor;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether the router's classifier keeps failing, so the operator is told rather than left with a log line per turn.
 * A failed classification falls back to the keyword rules, which still route every turn, so from the chat it looks
 * like working routing: a retired classifier model went unnoticed for four hours that way (JF-398).
 *
 * <p>Runs are kept per classifier, named as {@link RouterPolicy.Candidate#describe()} names it, so choosing another
 * classifier starts from a clean record and tests that mint their own provider never see each other's runs.
 */
public final class ClassifierHealth {

    /** Consecutive failed classifications that raise the alert; one or two can be a provider's blip. */
    public static final int ALERT_AFTER = 3;

    /** The bus event that tells clients the classifier's alert whenever it changes. */
    public static final String BUS_EVENT = "router.classifier";

    static final int MAX_ERROR_CHARS = 300;

    private static final String ROUTER = "router";

    /**
     * @param since     when the run's first failure happened, ISO-8601
     * @param lastError why the newest one failed, with credentials masked
     */
    public record Alert(String classifier, int consecutiveFailures, String since, String lastError) {}

    private static final class Run {
        int failures;
        @Nullable Instant since;
        String lastError = "";
    }

    private static final Map<String, Run> RUNS = new HashMap<>();

    private ClassifierHealth() {}

    /** Records that {@code classifier} could not classify a turn, raising the alert on the {@link #ALERT_AFTER}th in a row. */
    public static void failed(String classifier, @Nullable String reason) {
        Alert alert;
        synchronized (RUNS) {
            var run = RUNS.computeIfAbsent(classifier, _ -> new Run());
            run.failures++;
            if (run.since == null) run.since = AppClock.now();
            run.lastError = clean(reason);
            if (run.failures < ALERT_AFTER) return;
            alert = alertOf(classifier, run);
        }
        if (alert.consecutiveFailures() == ALERT_AFTER) {
            EventLogger.error(ROUTER, "Classifier %s has failed on %d turns in a row, so the keyword rules are routing: %s"
                    .formatted(classifier, ALERT_AFTER, alert.lastError()));
        }
        publish(classifier, alert, false);
    }

    /** Records that {@code classifier} classified a turn, which ends its run of failures and any alert. */
    public static void succeeded(String classifier) {
        Run ended;
        synchronized (RUNS) {
            ended = RUNS.remove(classifier);
        }
        if (ended == null || ended.failures < ALERT_AFTER) return;
        EventLogger.info(ROUTER, "Classifier %s answered again after %d failed turns".formatted(classifier, ended.failures));
        publish(classifier, null, false);
    }

    /** {@code classifier}'s alert, or null while it has failed fewer than {@link #ALERT_AFTER} turns in a row. */
    public static @Nullable Alert alertFor(@Nullable String classifier) {
        if (classifier == null) return null;
        synchronized (RUNS) {
            var run = RUNS.get(classifier);
            return run != null && run.failures >= ALERT_AFTER ? alertOf(classifier, run) : null;
        }
    }

    /**
     * Tells clients {@code classifier}'s alert once the settings write that may have changed which classifier routes
     * commits, so a refused or rolled-back write announces nothing.
     */
    public static void publishCurrent(@Nullable String classifier) {
        publish(classifier, alertFor(classifier), true);
    }

    /** {@code reason} on one line, credentials masked and cut to {@link #MAX_ERROR_CHARS}. */
    static String clean(@Nullable String reason) {
        if (reason == null || reason.isBlank()) return "no reason given";
        var line = SecretRedactor.redact(reason.strip().replaceAll("\\s+", " "));
        return line.length() > MAX_ERROR_CHARS ? line.substring(0, MAX_ERROR_CHARS) + "…" : line;
    }

    private static Alert alertOf(String classifier, Run run) {
        return new Alert(classifier, run.failures, String.valueOf(run.since), run.lastError);
    }

    // A turn's outcome publishes at once: the alert lives here, not in a row a client would wait to read, and a turn can
    // run inside a transaction that holds an after-commit event until the turn ends.
    private static void publish(@Nullable String classifier, @Nullable Alert alert, boolean afterCommit) {
        var data = new LinkedHashMap<String, Object>();
        if (classifier != null) data.put("classifier", classifier);
        if (alert != null) data.put("alert", alert);
        if (afterCommit) NotificationBus.publishDetachedAfterCommit(BUS_EVENT, data);
        else NotificationBus.publishDetached(BUS_EVENT, data);
    }
}
