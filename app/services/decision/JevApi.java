package services.decision;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import services.BreakerAlarms;
import services.EventLogger;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.RetryScheduler;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One {@code POST /v1/systemone} at a time, for every decision provider that speaks it: TypeSafe AI's
 * JEV, for the Jev browser engine and the router's classifier, and an Ollama server's System One models
 * (JCLAW-1336), for the router's classifier. A port of jev-ultrafast's {@code model.post_json} and
 * {@code model.validate_choice} (MIT, Browser Use; notice in {@code conf/browser/jev-ultrafast-LICENSE}).
 *
 * <p>Each {@link Target} runs under its own breaker, so an outage of one provider never stops the other.
 * JEV's, {@link #BREAKER_NAME}, is shared by both its consumers, so an outage that one of them
 * discovers stops the other sending too.
 */
public final class JevApi {

    static final String ENDPOINT = "https://api.typesafe.ai/v1/systemone";
    public static final String MODEL = "jev-latest";
    public static final String BREAKER_NAME = "decision:jev";
    /** Fixed: three counted failures in a row, or half of the last ten, open it for 60 s. */
    public static final CircuitBreaker.Config BREAKER_CONFIG =
            CircuitBreaker.Config.of(10, 0.5, 3, 60_000L).withConsecutiveFailures(3);
    public static final String INVALID = "Invalid Jev response";

    private static final String CATEGORY = "decision";
    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);
    private static final long BACKOFF_MS = 500;
    private static final Set<Integer> RETRIED_STATUS = Set.of(429, 503, 529);

    private JevApi() {}

    /**
     * Where a call goes and what it runs under.
     *
     * @param name        how log lines and failures name the provider
     * @param breakerLabel how the breaker's messages and alarms name it
     * @param service     who is not called while the breaker is open
     * @param apiKey      sent as a bearer token; null sends none
     * @param guarded     dial through the SSRF-guarded client, for an address the operator chose
     */
    public record Target(String name, String breakerLabel, String service, String url, @Nullable String apiKey,
                         String breakerName, boolean guarded) {}

    /** TypeSafe's JEV, with {@code apiKey}. */
    public static Target jev(String apiKey) {
        return new Target("Jev", "JEV", "TypeSafe", ENDPOINT, apiKey, BREAKER_NAME, false);
    }

    /** {@link #post(Target, JsonObject, int, long)} to TypeSafe's JEV. */
    public static JsonObject post(String apiKey, JsonObject body, int attempts, long attemptTimeoutMs) {
        return post(jev(apiKey), body, attempts, attemptTimeoutMs);
    }

    /**
     * POST {@code body}, up to {@code attempts} times within {@code attemptTimeoutMs} each; one attempt
     * never retries. A request has no side effects, so a timeout or a dropped connection is retried as
     * well as 429, 503 and 529. The whole loop is one outcome for the breaker.
     *
     * @throws JevException.BreakerOpen without sending anything, while the breaker is open or isolated
     * @throws JevException             when the provider gave no usable answer
     */
    public static JsonObject post(Target target, JsonObject body, int attempts, long attemptTimeoutMs) {
        var breaker = breaker(target);
        var admission = breaker.admit();
        if (!admission.allowed()) throw openBreakerFailure(target, breaker);
        var reported = false;
        try {
            var result = send(target, body, attempts, attemptTimeoutMs);
            breaker.recordSuccess(0L, admission.probeWindow());
            reported = true;
            return result;
        } catch (JevException.Outage e) {
            breaker.recordFailure(admission.probeWindow());
            reported = true;
            throw e;
        } finally {
            // A probe holds a HALF_OPEN permit until it reports, and a refused key or a malformed answer
            // is the provider answering: stranding the permit would keep the breaker shut for good.
            if (!reported && admission.probe()) breaker.recordSuccess(0L, admission.probeWindow());
        }
    }

    private static JsonObject send(Target target, JsonObject body, int attempts, long attemptTimeoutMs) {
        var builder = new Request.Builder().url(target.url()).post(RequestBody.create(body.toString(), JSON));
        if (target.apiKey() != null) builder.header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + target.apiKey());
        var request = builder.build();
        var client = target.guarded() ? HttpFactories.generalGuarded() : HttpFactories.general();
        for (int attempt = 1; ; attempt++) {
            var call = client.newCall(request);
            call.timeout().timeout(attemptTimeoutMs, TimeUnit.MILLISECONDS);
            try (var response = call.execute()) {
                int code = response.code();
                if (!response.isSuccessful()) {
                    EventLogger.warn(CATEGORY, "%s request %d of %d failed: HTTP %d"
                            .formatted(target.name(), attempt, attempts, code));
                }
                if (RETRIED_STATUS.contains(code) && attempt < attempts) {
                    pause(BACKOFF_MS << (attempt - 1));
                    continue;
                }
                failOnStatus(target, response);
                return parseAnswer(response);
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, "%s request %d of %d failed: %s"
                        .formatted(target.name(), attempt, attempts, e.getClass().getSimpleName()));
                if (attempt >= attempts) {
                    // The address is the operator's, never the model's, so naming a timeout scans nothing (JCLAW-1337).
                    var timedOut = e instanceof InterruptedIOException;
                    throw new JevException.Outage(timedOut
                            ? "%s did not answer within %s".formatted(target.name(), seconds(attemptTimeoutMs))
                            : target.name() + " unreachable", timedOut);
                }
                pause(BACKOFF_MS << (attempt - 1));
            }
        }
    }

    /** Ends the call on a status that is not retried, or has run out of retries. */
    private static void failOnStatus(Target target, Response response) {
        int code = response.code();
        if ((code == 401 || code == 403) && target.apiKey() != null) {
            throw new JevException("%s refused the %s API key (HTTP %d); the operator must update it "
                    .formatted(target.service(), target.name(), code) + "in Settings → Decision Providers");
        }
        // Status only: the body comes from a server whose address the operator chose (JCLAW-778).
        if (code == 429 || code >= 500) throw new JevException.Outage(target.name() + " returned HTTP " + code, false);
        if (!response.isSuccessful()) throw new JevException(target.name() + " returned HTTP " + code);
    }

    private static JsonObject parseAnswer(Response response) throws IOException {
        try {
            var parsed = JsonParser.parseString(response.body().string());
            if (!parsed.isJsonObject()) throw new JevException(INVALID);
            return parsed.getAsJsonObject();
        } catch (JsonParseException _) {
            throw new JevException(INVALID);
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
        return breaker(jev(""));
    }

    /** The breaker every call to {@code target} runs under, created on first use with {@link #BREAKER_CONFIG}. */
    public static CircuitBreaker breaker(Target target) {
        return CircuitBreakers.find(target.breakerName()).orElseGet(() -> {
            var breaker = CircuitBreakers.get(target.breakerName(), BREAKER_CONFIG);
            breaker.setTransitionListener(BreakerAlarms.listener(target.breakerName(),
                    "Decision provider " + target.breakerLabel()));
            return breaker;
        });
    }

    /** An operator's isolation is a different fact from the provider failing, and says so. */
    private static JevException.BreakerOpen openBreakerFailure(Target target, CircuitBreaker breaker) {
        return new JevException.BreakerOpen(breaker.stats().reason() == CircuitBreaker.Reason.MANUAL_TRIP
                ? "%s was isolated by the operator: not calling %s until the cooldown ends or it is restored"
                        .formatted(target.breakerLabel(), target.service())
                : "%s's circuit breaker is open: not calling %s until it recovers"
                        .formatted(target.breakerLabel(), target.service()));
    }

    private static double unitNumber(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JevException(INVALID);
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new JevException(INVALID);
        return value;
    }

    private static String seconds(long millis) {
        return millis % 1000 == 0 ? millis / 1000 + " s" : millis + " ms";
    }

    /** A wait that unmounts a virtual-thread caller (JDK-8373224). */
    private static void pause(long millis) {
        RetryScheduler.schedule(() -> null, millis).join();
    }
}
