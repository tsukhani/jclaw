package services;

import models.Agent;
import models.Task;
import org.jspecify.annotations.Nullable;
import utils.AppClock;
import utils.CircuitBreaker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Messages to the operator, on a channel they chose, when unattended work fails (JCLAW-1279): a
 * circuit breaker opening or recovering, a recurring task's occurrence failing for good, an MCP server
 * moved to the handshake protocol, stored credentials that do not decrypt at boot, and a persona file
 * left out of an agent's prompt because it matched a prompt-injection rule.
 *
 * <p>{@link #KEY} holds the destination as a delivery spec ({@code telegram:<chat id>}), sent
 * through the main agent's bindings. Unset, nothing is decided and nothing is sent. The decision
 * is made on the caller's thread and the send on a virtual thread of its own, because a breaker
 * reports from inside a model call, which a slow channel must not hold up.
 */
public final class OperatorAlerts {

    public static final String KEY = "alerts.delivery";

    /** Event-log category for an alert that could not be delivered. */
    public static final String CATEGORY = "OPERATOR_ALERT";

    /** A breaker that keeps reopening is announced at most once per window; an outage still running when it ends is announced then. */
    public static final Duration REPEAT_WINDOW = Duration.ofMinutes(15);

    private static final int MAX_ERROR_CHARS = 300;
    private static final DateTimeFormatter NEXT_RUN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    /** One per breaker, from its opening until it closes. */
    private static final class Outage {
        boolean manual;
        boolean announced;
    }

    private static final Map<String, Outage> OUTAGES = new HashMap<>();
    private static final Map<String, Instant> LAST_ANNOUNCED = new HashMap<>();
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<String>> PERSONA_LISTENERS = new CopyOnWriteArrayList<>();

    /** The SHA-256 of the content last reported per {@code agent/file}, cleared when the file is next seen clean. */
    private static final Map<String, String> WITHHELD_PERSONA_FILES = new ConcurrentHashMap<>();

    private OperatorAlerts() {}

    /** Called by {@link BreakerAlarms} for every transition of every breaker. */
    public static void onBreakerTransition(String name, String subject, CircuitBreaker.Transition transition) {
        if (destination() == null) return;
        var text = breakerAlert(name, subject, transition);
        if (text != null) send(text);
    }

    /**
     * The alert {@code transition} warrants, or null, recording the outage it belongs to either way.
     * An outage is announced once: reopening after a failed probe is the same outage. One the operator
     * started by isolating the breaker is not announced, and neither is its end.
     */
    public static synchronized @Nullable String breakerAlert(
            String name, String subject, CircuitBreaker.Transition transition) {
        return switch (transition.to()) {
            case OPEN -> opened(name, subject, transition);
            case CLOSED -> closed(name, subject, transition);
            case HALF_OPEN -> null;
        };
    }

    private static @Nullable String opened(String name, String subject, CircuitBreaker.Transition transition) {
        var outage = OUTAGES.computeIfAbsent(name, _ -> new Outage());
        if (transition.reason() == CircuitBreaker.Reason.MANUAL_TRIP) outage.manual = true;
        if (outage.manual || outage.announced) return null;
        var now = AppClock.now();
        var last = LAST_ANNOUNCED.get(name);
        if (last != null && now.isBefore(last.plus(REPEAT_WINDOW))) return null;
        outage.announced = true;
        LAST_ANNOUNCED.put(name, now);
        return "JClaw alert: " + BreakerAlarms.describe(subject, transition);
    }

    private static @Nullable String closed(String name, String subject, CircuitBreaker.Transition transition) {
        var outage = OUTAGES.remove(name);
        if (outage == null || !outage.announced || transition.reason() == CircuitBreaker.Reason.MANUAL_RESET) {
            return null;
        }
        return "JClaw: " + BreakerAlarms.describe(subject, transition);
    }

    /**
     * Called when JClaw moves an MCP server from Automatic to Handshake because it failed protocol
     * 2026-07-28 while the handshake still worked (JCLAW-1443).
     */
    public static void onMcpProtocolSwitched(String serverName, String reason) {
        var text = mcpProtocolSwitchAlert(serverName, reason);
        for (var listener : LISTENERS) listener.accept(text);
        if (destination() == null) return;
        send(text);
    }

    public static String mcpProtocolSwitchAlert(String serverName, String reason) {
        var shortReason = reason.length() > MAX_ERROR_CHARS ? reason.substring(0, MAX_ERROR_CHARS) + "…" : reason;
        return ("JClaw alert: MCP server '%s' was moved to the Handshake protocol: %s. It stays there until its"
                + " Protocol is set back to Automatic on the MCP Servers page.").formatted(serverName, shortReason);
    }

    /**
     * Test-only: the protocol-switch alerts any thread decides while {@code body} runs, whether or
     * not a destination is set. Listener-based, so a connect thread's alert is seen.
     */
    public static List<String> captureMcpAlertsForTest(Runnable body) {
        return capture(LISTENERS, body);
    }

    private static List<String> capture(List<Consumer<String>> listeners, Runnable body) {
        var seen = new CopyOnWriteArrayList<String>();
        Consumer<String> listener = seen::add;
        listeners.add(listener);
        try {
            body.run();
        } finally {
            listeners.remove(listener);
        }
        return List.copyOf(seen);
    }

    /**
     * Called each time a prompt withholds a persona file that matched a prompt-injection rule. Alerts
     * once per content of that file, so a file left as it is does not alert on every turn.
     *
     * @return whether this content had not been reported for the file before
     */
    public static boolean onPersonaFileWithheld(String agentName, String filename, String rule, String content) {
        var hash = sha256(content);
        if (hash.equals(WITHHELD_PERSONA_FILES.put(agentName + "/" + filename, hash))) return false;
        var text = personaFileAlert(agentName, filename, rule);
        for (var listener : PERSONA_LISTENERS) listener.accept(text);
        send(text);
        return true;
    }

    /** Called each time a persona file is not withheld, so the same content withheld again later is reported again. */
    public static void onPersonaFileClean(String agentName, String filename) {
        WITHHELD_PERSONA_FILES.remove(agentName + "/" + filename);
    }

    public static String personaFileAlert(String agentName, String filename, String rule) {
        return ("JClaw alert: %s of agent '%s' matched the prompt-injection rule \"%s\" and is left out of its prompt."
                + " The file is unchanged in the agent's workspace; it is loaded again once it no longer matches.")
                .formatted(filename, agentName, rule);
    }

    /** Test-only: the persona-file alerts any thread decides while {@code body} runs, whether or not a destination is set. */
    public static List<String> capturePersonaAlertsForTest(Runnable body) {
        return capture(PERSONA_LISTENERS, body);
    }

    /** Called once a boot when stored credentials do not decrypt under this installation's secret (JCLAW-1455). */
    public static void onUnreadableCredentials(int count) {
        if (destination() == null) return;
        send(unreadableCredentialsAlert(count));
    }

    public static String unreadableCredentialsAlert(int count) {
        return ("JClaw alert: %d stored credential(s) did not decrypt under this installation's secret (PLAY_SECRET in"
                + " certs/.env), so each reads as missing. Restore the certs/.env they were saved under and restart,"
                + " or enter them again in Settings.").formatted(count);
    }

    /** Called when an occurrence of a recurring task fails for good and the task moves on to {@code next}. */
    public static void onOccurrenceFailed(Task task, @Nullable String agentName, String reason, String error,
                                          Instant next) {
        if (destination() == null) return;
        send(occurrenceAlert(task.name, agentName, reason, error, next.atZone(TimezoneResolver.resolve(task))));
    }

    public static String occurrenceAlert(String taskName, @Nullable String agentName, String reason, String error,
                                         ZonedDateTime next) {
        var shortError = error.length() > MAX_ERROR_CHARS ? error.substring(0, MAX_ERROR_CHARS) + "…" : error;
        return "JClaw alert: task '%s'%s failed (%s): %s. It runs again at %s."
                .formatted(taskName, agentName != null ? " (agent " + agentName + ")" : "", reason, shortError,
                        NEXT_RUN.format(next));
    }

    /** Why {@code value} cannot be the alert destination, or null when it can. Blank turns alerts off. */
    public static @Nullable String rejectionFor(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        var spec = DeliverySpec.parse(value);
        var target = spec.target();
        if (!spec.isDispatchChannel() || target == null || target.isBlank()) {
            return KEY + " must be channel:target, with a channel of telegram, slack, whatsapp or web (got '"
                    + value.trim() + "').";
        }
        return null;
    }

    /**
     * Deliver {@code text} to {@code spec} through {@code via}'s bindings. A failure is logged and
     * returned, never thrown: an alert must not break the breaker, the task or the turn behind it.
     */
    public static DeliveryDispatcher.DispatchResult deliver(Agent via, String spec, String text) {
        DeliveryDispatcher.DispatchResult result;
        try {
            result = Tx.run(() -> DeliveryDispatcher.dispatchSpec(via, spec, text));
        } catch (RuntimeException e) {
            result = DeliveryDispatcher.DispatchResult.failedDelivery(String.valueOf(e.getMessage()));
        }
        if (!result.ok()) {
            EventLogger.warn(CATEGORY, "Operator alert not delivered to %s: %s".formatted(spec, result.reason()));
        }
        return result;
    }

    private static void send(String text) {
        var spec = destination();
        if (spec == null) return;
        Thread.ofVirtual().name("operator-alert").inheritInheritableThreadLocals(false).start(() -> {
            var main = Tx.run(() -> Agent.findByName(Agent.MAIN_AGENT_NAME));
            if (main == null) {
                EventLogger.warn(CATEGORY, "Operator alert not delivered: there is no main agent to send it through.");
                return;
            }
            deliver(main, spec, text);
        });
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static @Nullable String destination() {
        var spec = ConfigService.get(KEY);
        return spec == null || spec.isBlank() ? null : spec.trim();
    }
}
