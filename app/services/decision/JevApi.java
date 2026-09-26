package services.decision;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jspecify.annotations.Nullable;
import services.BreakerAlarms;
import services.EventLogger;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.RetryScheduler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * TypeSafe AI's JEV, one {@code POST /v1/systemone} at a time, for every consumer: the Jev browser
 * engine and the router's classifier. A port of jev-ultrafast's {@code model.post_json} and
 * {@code model.validate_choice} (MIT, Browser Use; notice in {@code conf/browser/jev-ultrafast-LICENSE}).
 *
 * <p>Every call runs under one breaker, {@link #BREAKER}, shared by both consumers, so an outage
 * that one of them discovers stops the other sending too.
 */
public final class JevApi {

    static final String ENDPOINT = "https://api.typesafe.ai/v1/systemone";
    public static final String MODEL = "jev-latest";
    public static final String BREAKER = "decision:jev";
    /** Fixed: three counted failures in a row, or half of the last ten, open it for 60 s. */
    public static final CircuitBreaker.Config BREAKER_CONFIG =
            CircuitBreaker.Config.of(10, 0.5, 3, 60_000L).withConsecutiveFailures(3);
    public static final String INVALID = "Invalid Jev response";
    private static final String BREAKER_OPEN = "JEV's circuit breaker is open: not calling TypeSafe until it recovers";
    private static final String ISOLATED =
            "JEV was isolated by the operator: not calling TypeSafe until the cooldown ends or it is restored";

    private static final String CATEGORY = "decision";
    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);
    private static final long BACKOFF_MS = 500;
    private static final Set<Integer> RETRIED_STATUS = Set.of(429, 503, 529);

    private JevApi() {}

    /**
     * POST {@code body}, up to {@code attempts} times within {@code attemptTimeoutMs} each; one attempt
     * never retries. A request has no side effects, so a timeout or a dropped connection is retried as
     * well as 429, 503 and 529. The whole loop is one outcome for the breaker.
     *
     * @throws JevException.BreakerOpen without sending anything, while the breaker is open or isolated
     * @throws JevException             when TypeSafe gave no usable answer
     */
    public static JsonObject post(String apiKey, JsonObject body, int attempts, long attemptTimeoutMs) {
        var breaker = breaker();
        var admission = breaker.admit();
        if (!admission.allowed()) throw openBreakerFailure(breaker);
        var reported = false;
        try {
            var result = send(apiKey, body, attempts, attemptTimeoutMs);
            breaker.recordSuccess(0L, admission.probeWindow());
            reported = true;
            return result;
        } catch (Outage e) {
            breaker.recordFailure(admission.probeWindow());
            reported = true;
            throw e;
        } finally {
            // A probe holds a HALF_OPEN permit until it reports, and a refused key or a malformed answer
            // is TypeSafe answering: stranding the permit would keep the breaker shut for good.
            if (!reported && admission.probe()) breaker.recordSuccess(0L, admission.probeWindow());
        }
    }

    private static JsonObject send(String apiKey, JsonObject body, int attempts, long attemptTimeoutMs) {
        var request = new Request.Builder().url(ENDPOINT)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        for (int attempt = 1; ; attempt++) {
            var call = HttpFactories.general().newCall(request);
            call.timeout().timeout(attemptTimeoutMs, TimeUnit.MILLISECONDS);
            try (var response = call.execute()) {
                int code = response.code();
                if (!response.isSuccessful()) {
                    EventLogger.warn(CATEGORY, "Jev request %d of %d failed: HTTP %d".formatted(attempt, attempts, code));
                }
                if (RETRIED_STATUS.contains(code) && attempt < attempts) {
                    pause(BACKOFF_MS << (attempt - 1));
                    continue;
                }
                if (code == 401 || code == 403) {
                    throw new JevException("TypeSafe refused the Jev API key (HTTP %d); the operator must update it "
                            .formatted(code) + "in Settings → Decision Providers");
                }
                if (code == 429 || code >= 500) throw new Outage("Jev returned HTTP " + code);
                if (!response.isSuccessful()) throw new JevException("Jev returned HTTP " + code);
                var parsed = JsonParser.parseString(response.body().string());
                if (!parsed.isJsonObject()) throw new JevException(INVALID);
                return parsed.getAsJsonObject();
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, "Jev request %d of %d failed: %s"
                        .formatted(attempt, attempts, e.getClass().getSimpleName()));
                if (attempt >= attempts) throw new Outage("Jev unreachable");
                pause(BACKOFF_MS << (attempt - 1));
            } catch (JsonParseException _) {
                throw new JevException(INVALID);
            }
        }
    }

    /**
     * The answer, if it chooses one of {@code ids} and its probabilities cover exactly those ids,
     * are finite numbers in [0, 1] summing to 1 within 0.02, and peak at the choice.
     */
    public static JsonObject validateChoice(@Nullable JsonElement raw, Set<String> ids) {
        try {
            if (raw == null || !raw.isJsonObject()) throw new JevException(INVALID);
            var answer = raw.getAsJsonObject();
            var choice = answer.getAsJsonPrimitive("choice");
            var probabilities = answer.getAsJsonObject("probabilities");
            if (choice == null || !choice.isString() || !ids.contains(choice.getAsString())
                    || probabilities == null || !probabilities.keySet().equals(ids)) {
                throw new JevException(INVALID);
            }
            double sum = 0;
            double max = Double.NEGATIVE_INFINITY;
            for (var p : probabilities.entrySet()) {
                double value = unitNumber(p.getValue());
                sum += value;
                max = Math.max(max, value);
            }
            unitNumber(answer.get("confidence"));
            if (Math.abs(sum - 1) >= 0.02 || unitNumber(probabilities.get(choice.getAsString())) < max - 1e-6) {
                throw new JevException(INVALID);
            }
            return answer;
        } catch (JevException e) {
            throw e;
        } catch (RuntimeException _) {
            throw new JevException(INVALID);
        }
    }

    /** One {@code choice} question: JEV picks a key of {@code criteria}, reading {@code instructions}. */
    public static JsonObject choiceQuestion(JsonObject criteria, JsonObject instructions) {
        var question = new JsonObject();
        question.addProperty("type", "choice");
        question.add("criteria", criteria);
        question.add("instructions", instructions);
        return question;
    }

    /** The breaker every JEV call runs under, created on first use. */
    public static CircuitBreaker breaker() {
        return CircuitBreakers.find(BREAKER).orElseGet(JevApi::registerBreaker);
    }

    private static CircuitBreaker registerBreaker() {
        var breaker = CircuitBreakers.get(BREAKER, BREAKER_CONFIG);
        breaker.setTransitionListener(BreakerAlarms.listener(BREAKER, "Decision provider JEV"));
        return breaker;
    }

    /** An operator's isolation is a different fact from TypeSafe failing, and says so. */
    private static JevException.BreakerOpen openBreakerFailure(CircuitBreaker breaker) {
        return new JevException.BreakerOpen(
                breaker.stats().reason() == CircuitBreaker.Reason.MANUAL_TRIP ? ISOLATED : BREAKER_OPEN);
    }

    private static double unitNumber(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JevException(INVALID);
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new JevException(INVALID);
        return value;
    }

    /** A wait that unmounts a virtual-thread caller (JDK-8373224). */
    private static void pause(long millis) {
        RetryScheduler.schedule(() -> null, millis).join();
    }

    /** A failure the breaker counts: a transport error, a timeout, HTTP 5xx or 429. */
    private static final class Outage extends JevException {
        Outage(String message) {
            super(message);
        }
    }
}
